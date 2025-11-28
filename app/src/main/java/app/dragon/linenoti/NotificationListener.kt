package app.dragon.linenoti

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class NotificationListener : NotificationListenerService() {

    companion object {
        const val CHANNEL_ID = "line_message_channel_v1"
        const val TAG = "LineNoti_Debug"
        const val MAX_CHAT_HISTORY_SIZE = 30
        const val MAX_MSG_PER_CHAT = 20
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    data class MyMessage(
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val stickerUri: Uri?,
        val iconPath: String?
    )

    // メモリ上の履歴
    private val chatHistory = java.util.LinkedHashMap<String, MutableList<MyMessage>>(
        16, 0.75f, true
    )

    // グループ名の一時保管
    private val chatMetadata = mutableMapOf<String, String>()

    // ★追加: Intentのキャッシュ (後出しジャンケン対応用)
    private val chatIntents = mutableMapOf<String, PendingIntent>()

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        cleanUpCache(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != "jp.naver.line.android") return
        val notification = sbn.notification
        val extras = sbn.notification.extras

        val groupName = extras.getString("android.conversationTitle")
            ?: extras.getString("android.hiddenConversationTitle")

        val rawTitle = extras.getString("android.title") ?: "不明"
        val senderName = if (!groupName.isNullOrEmpty()) {
            rawTitle.replace("$groupName: ", "")
                .replace("$groupName : ", "")
                .replace(groupName, "")
                .trim()
        } else { rawTitle }
        val finalSenderName = if (senderName.isEmpty()) rawTitle else senderName

        val text = extras.getCharSequence("android.text")?.toString() ?: "スタンプ"
        val lineMessageId = extras.getString("line.message.id")

        if (notification.category == NotificationCompat.CATEGORY_CALL) return
        if (lineMessageId == null) {
            if (text.contains("不在着信") || text.contains("通話")) return
            if (notification.category == NotificationCompat.CATEGORY_PROMO) return
        }
        if (text == "新着メッセージがあります") return

        val stickerUrl = extras.getString("line.sticker.url")
        val chatId = extras.getString("line.chat.id") ?: "unknown_chat"
        val originalPendingIntent = notification.contentIntent // 今回のIntent

        // Reply Action抽出
        var replyPendingIntent: PendingIntent? = null
        var replyRemoteInputs: Array<android.app.RemoteInput>? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notification.actions?.lastOrNull()?.let { action ->
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    replyPendingIntent = action.actionIntent
                    replyRemoteInputs = action.remoteInputs.map { it as android.app.RemoteInput }.toTypedArray()
                }
            }
        }

        val largeIconObj = extras.get("android.largeIcon")

        if (!groupName.isNullOrEmpty()) {
            chatMetadata[chatId] = groupName
        }

        serviceScope.launch {
            // 画像取得 (非同期)
            val stickerUri = if (stickerUrl != null) {
                downloadImageToCache(applicationContext, stickerUrl)
            } else { null }

            val iconPath = if (largeIconObj != null) {
                saveIconAndGetPath(applicationContext, largeIconObj, finalSenderName)
            } else { null }

            // ★★★ 重複 & 更新チェックロジック ★★★
            var isDuplicate = false
            var needUpdate = false

            synchronized(chatHistory) {
                val list = chatHistory[chatId]
                if (list != null && list.isNotEmpty()) {
                    val lastMsg = list.last()
                    val isSameText = (lastMsg.text == text)
                    val isRecent = (System.currentTimeMillis() - lastMsg.timestamp < 1500)

                    if (isSameText && isRecent) {
                        isDuplicate = true

                        // 重複だが「アイコンが新しくなった」場合
                        if (iconPath != null && lastMsg.iconPath != iconPath) {
                            Log.d(TAG, "重複: アイコン更新を検知")
                            // 最新の履歴のアイコンパスを書き換える
                            val updatedMsg = lastMsg.copy(iconPath = iconPath)
                            list[list.size - 1] = updatedMsg
                            needUpdate = true
                        }

                        // 重複だが「Intentが新しくなった」場合
                        val cachedIntent = chatIntents[chatId]
                        if (originalPendingIntent != null && originalPendingIntent != cachedIntent) {
                            Log.d(TAG, "重複: Intent更新を検知")
                            // キャッシュ更新 (後で使う)
                            chatIntents[chatId] = originalPendingIntent
                            needUpdate = true
                        }
                    }
                }

                // 重複でないなら新規追加
                if (!isDuplicate) {
                    if (!chatHistory.containsKey(chatId)) {
                        if (chatHistory.size >= MAX_CHAT_HISTORY_SIZE) {
                            val it = chatHistory.iterator()
                            if (it.hasNext()) { it.next(); it.remove() }
                        }
                        chatHistory[chatId] = mutableListOf()
                    }
                    val newList = chatHistory[chatId]!!
                    val newMessage = MyMessage(finalSenderName, text, System.currentTimeMillis(), stickerUri, iconPath)
                    newList.add(newMessage)
                    if (newList.size > MAX_MSG_PER_CHAT) newList.removeAt(0)

                    // 新規ならIntentもキャッシュ更新
                    if (originalPendingIntent != null) {
                        chatIntents[chatId] = originalPendingIntent
                    }
                }
            }

            // 「重複していて、かつ更新も不要」ならここで終了
            if (isDuplicate && !needUpdate) {
                return@launch
            }

            // 更新が必要、もしくは新規通知なら、通知を発行する
            // 使うIntentはキャッシュ(最新版)を優先
            val finalIntent = chatIntents[chatId] ?: originalPendingIntent

            updateHistoryAndNotify(
                finalSenderName,
                chatId,
                text,
                stickerUri,
                iconPath,
                finalIntent, // 更新されたIntentを渡す
                replyPendingIntent,
                replyRemoteInputs
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn?.packageName != "jp.naver.line.android") return
        val extras = sbn.notification.extras
        val chatId = sbn.notification.extras.getString("line.chat.id") ?: return

        val isUserAction = reason == REASON_CANCEL || reason == REASON_CLICK || reason == REASON_GROUP_SUMMARY_CANCELED
        var shouldDelete = isUserAction

        if (reason == REASON_APP_CANCEL) {
            if (isLineAppForeground(applicationContext)) {
                shouldDelete = true
                Log.d(TAG, "LINE起動中につき履歴を削除 (既読判定)")
            } else {
                shouldDelete = false
                Log.d(TAG, "LINE裏起動につき履歴を保持 (送信取消/自動同期の可能性)")

                // 送信取り消しメッセージの注入
                val groupName = extras.getString("android.conversationTitle") ?: extras.getString("android.hiddenConversationTitle")
                val rawTitle = extras.getString("android.title") ?: "不明"
                val senderName = if (!groupName.isNullOrEmpty()) {
                    rawTitle.replace("$groupName: ", "").replace("$groupName : ", "").replace(groupName, "").trim()
                } else { rawTitle }
                val finalSenderName = if (senderName.isEmpty()) rawTitle else senderName
                val originalIntent = sbn.notification.contentIntent

                var cachedIconPath: String? = null
                synchronized(chatHistory) {
                    cachedIconPath = chatHistory[chatId]?.lastOrNull()?.iconPath
                }

                serviceScope.launch {
                    updateHistoryAndNotify(
                        finalSenderName, chatId, "⚠️ 送信取り消しを検出", null, cachedIconPath,
                        originalIntent, null, null
                    )
                }
            }
        }

        if (shouldDelete) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(chatId.hashCode())
            synchronized(chatHistory){
                chatHistory.remove(chatId)
                chatIntents.remove(chatId) // Intentキャッシュも消す
            }
        }
    }

    // --- 以下、ヘルパー関数 ---

    private fun isLineAppForeground(context: Context): Boolean {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
            if (stats != null && stats.isNotEmpty()) {
                val sorted = stats.sortedByDescending { it.lastTimeUsed }
                if (sorted.isNotEmpty()) {
                    val topApp = sorted[0]
                    // 3秒以内に使われたか
                    val isRecent = (time - topApp.lastTimeUsed) < 3000
                    return topApp.packageName == "jp.naver.line.android" && isRecent
                }
            }
        } catch (e: Exception) { Log.e(TAG, "UsageStats check failed: ${e.message}") }
        return false
    }

    private fun saveIconAndGetPath(context: Context, iconObj: Any, nameKey: String): String? {
        try {
            val bitmap = when (iconObj) {
                is Bitmap -> iconObj
                is Icon -> {
                    val drawable = iconObj.loadDrawable(context) ?: return null
                    drawableToBitmap(drawable)
                }
                else -> null
            } ?: return null
            val cachePath = File(context.cacheDir, "user_icons")
            if (!cachePath.exists()) cachePath.mkdirs()
            val fileName = "icon_${nameKey.hashCode()}.png"
            val file = File(cachePath, fileName)
            if (!file.exists()) {
                val stream = FileOutputStream(file)
                val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
                scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.close()
            }
            return file.absolutePath
        } catch (e: Exception) { return null }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private suspend fun downloadImageToCache(context: Context, url: String): Uri? {
        val cachePath = File(context.cacheDir, "images")
        if (!cachePath.exists()) cachePath.mkdirs()
        val fileName = "sticker_${url.hashCode()}.png"
        val file = File(cachePath, fileName)
        if (file.exists()) return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        return try {
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as BitmapDrawable).bitmap
                val stream = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.close()
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } else { null }
        } catch (e: Exception) { null }
    }

    private fun cleanUpCache(context: Context) {
        try {
            val imageCache = File(context.cacheDir, "images")
            if (imageCache.exists()) imageCache.listFiles()?.forEach { it.delete() }
            val iconCache = File(context.cacheDir, "user_icons")
            if (iconCache.exists()) iconCache.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) { }
    }

    private fun updateHistoryAndNotify(
        senderName: String,
        chatId: String,
        messageText: String,
        stickerUri: Uri?,
        senderIconPath: String?,
        originalIntent: PendingIntent?,
        replyPendingIntent: PendingIntent?,
        replyRemoteInputs: Array<android.app.RemoteInput>?
    ) {
        val context = applicationContext
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ★修正: メモリ管理なので chatHistory から直接リストを取得
        val historyList = synchronized(chatHistory) {
            chatHistory[chatId]?.toList() ?: emptyList()
        }

        val audioAttributes = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "LINEメッセージ通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "LINEのメッセージを再通知します"
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 100, 100, 100, 100)
        }
        manager.createNotificationChannel(channel)

        val storedGroupName = chatMetadata[chatId]
        val isGroup = storedGroupName != null

        val defaultBitmap = try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_groups_default) // ← リソース名確認
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.parseColor("#0e803c"))
            if (drawable != null) {
                drawable.setBounds(0, 0, 128, 128)
                drawable.draw(canvas)
            }
            bitmap
        } catch (e: Exception) {
            val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.GREEN)
            b
        }

        val mainBitmap: Bitmap = if (isGroup) {
            defaultBitmap
        } else {
            // 1対1なら最新の送信者の顔 (senderIconPath)
            if (senderIconPath != null) {
                BitmapFactory.decodeFile(senderIconPath) ?: defaultBitmap
            } else {
                defaultBitmap
            }
        }
        val mainIconCompat = IconCompat.createWithBitmap(mainBitmap)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val shortcutPerson = Person.Builder()
                .setName(storedGroupName ?: senderName)
                .setIcon(mainIconCompat)
                .setKey(chatId)
                .build()
            val shortcut = ShortcutInfoCompat.Builder(context, chatId)
                .setLongLived(true)
                .setShortLabel(storedGroupName ?: senderName)
                .setPerson(shortcutPerson)
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }

        val mePerson = Person.Builder().setName("自分").build()
        val style = NotificationCompat.MessagingStyle(mePerson)
        style.conversationTitle = storedGroupName
        if (isGroup) {
            style.isGroupConversation = true
        }

        for (msg in historyList) {
            val msgIcon = if (msg.iconPath != null) {
                try {
                    val bm = BitmapFactory.decodeFile(msg.iconPath)
                    IconCompat.createWithBitmap(bm)
                } catch (e: Exception) {
                    IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)
                }
            } else {
                IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)
            }

            val msgPerson = Person.Builder()
                .setName(msg.senderName)
                .setIcon(msgIcon)
                .setKey(msg.senderName)
                .build()

            val styleMessage = NotificationCompat.MessagingStyle.Message(
                msg.text,
                msg.timestamp,
                msgPerson
            )
            if (msg.stickerUri != null) {
                styleMessage.setData("image/png", msg.stickerUri)
            }
            style.addMessage(styleMessage)
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_line_bubble) // ← アイコンリソース名確認
            .setLargeIcon(mainBitmap)
            .setColor(getColor(R.color.black))
            .setStyle(style)
            .setShortcutId(chatId)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(chatId)
            .setAutoCancel(true)
            .setContentIntent(originalIntent)

        if (replyPendingIntent != null && replyRemoteInputs != null) {
            val coreRemoteInputs = replyRemoteInputs
                .map { androidx.core.app.RemoteInput.Builder(it.resultKey).setLabel(it.label).build() }
                .toTypedArray()

            val actionBuilder = NotificationCompat.Action.Builder(
                R.drawable.ic_launcher_foreground,
                "返信",
                replyPendingIntent
            )
            coreRemoteInputs.forEach { input ->
                actionBuilder.addRemoteInput(input)
            }
            val action = actionBuilder.setAllowGeneratedReplies(true).build()
            notificationBuilder.addAction(action)
        }

        manager.notify(chatId.hashCode(), notificationBuilder.build())
    }
}
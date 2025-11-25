package app.dragon.linenoti

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
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
        const val CHANNEL_ID = "line_message_channel_v1" // バージョンUP
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

    private val chatHistory = java.util.LinkedHashMap<String, MutableList<MyMessage>>(
        16, 0.75f, true
    )
    private val chatMetadata = mutableMapOf<String, String>()

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
        val originalPendingIntent = notification.contentIntent

        var replyPendingIntent: PendingIntent? = null
        // ★修正: android.app.RemoteInput を使用する
        var replyRemoteInputs: Array<android.app.RemoteInput>? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notification.actions?.lastOrNull()?.let { action ->
                // RemoteInputの取得に成功したら、PendingIntentとRemoteInputsを取り出す
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    replyPendingIntent = action.actionIntent

                    // ★修正: RemoteInput[] の型を android.app.RemoteInput[] に変換する
                    replyRemoteInputs = action.remoteInputs
                        .map { it as android.app.RemoteInput } // キャスト
                        .toTypedArray()
                }
            }
        }

        val largeIconObj = extras.get("android.largeIcon")

        // グループ名を記憶
        if (!groupName.isNullOrEmpty()) {
            chatMetadata[chatId] = groupName
        }

        serviceScope.launch {
            val stickerUri = if (stickerUrl != null) {
                downloadImageToCache(applicationContext, stickerUrl)
            } else { null }

            val iconPath = if (largeIconObj != null) {
                saveIconAndGetPath(applicationContext, largeIconObj, finalSenderName)
            } else { null }

            updateHistoryAndNotify(
                finalSenderName,
                chatId,
                text,
                stickerUri,
                iconPath,
                originalPendingIntent,
                replyPendingIntent,
                replyRemoteInputs
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName != "jp.naver.line.android") return
        val chatId = sbn.notification.extras.getString("line.chat.id")
        if (chatId != null) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(chatId.hashCode())
            synchronized(chatHistory){
                chatHistory.remove(chatId)
            }
        }
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
                // アイコンは小さくていいので圧縮して保存
                val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
                scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.close()
            }
            return file.absolutePath
        } catch (e: Exception) { return null }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
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
        if (file.exists()) {
            return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
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
        val storedGroupName = chatMetadata[chatId]
        val isGroup = storedGroupName != null // グループかどうかの判定

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

        // 1. 履歴更新
        synchronized(chatHistory) {
            if (!chatHistory.containsKey(chatId)) {
                if (chatHistory.size >= MAX_CHAT_HISTORY_SIZE) {
                    val it = chatHistory.iterator()
                    if (it.hasNext()) { it.next(); it.remove() }
                }
                chatHistory[chatId] = mutableListOf()
            }
        }
        val historyList = chatHistory[chatId]!!

        val lastMsg = historyList.lastOrNull()
        val isDuplicate = if (lastMsg != null) {
            val isSameText = (lastMsg.text == messageText)
            val isRecent = (System.currentTimeMillis() - lastMsg.timestamp < 1500)
            isSameText && isRecent
        } else { false }

        if (!isDuplicate) {
            val newMessage = MyMessage(senderName, messageText, System.currentTimeMillis(), stickerUri, senderIconPath)
            historyList.add(newMessage)
            if (historyList.size > MAX_MSG_PER_CHAT) historyList.removeAt(0)
        } else { return }

        // ★修正: 安全なデフォルト画像の作成処理
        // ここでエラーが起きても絶対にアプリを落とさないように try-catch で守る
        val defaultBitmap = try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_groups_default)

            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 安全な色の指定方法 (#06C755 = LINEグリーン)
            canvas.drawColor(android.graphics.Color.parseColor("#085728"))

            if (drawable != null) {
                drawable.setBounds(0, 0, 128, 128)
                drawable.draw(canvas)
            }
            bitmap
        } catch (e: Exception) {
            // 万が一失敗したら、ただの緑色の四角を作る（緊急回避）
            Log.e(TAG, "デフォルト画像作成エラー: ${e.message}")
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            bitmap
        }

        // 2. チャット全体のアイコン（LargeIcon & ShortcutIcon）を決める
        val mainBitmap: Bitmap = if (isGroup) {
            // グループならデフォルト画像
            defaultBitmap
        } else {
            // 1対1なら最新の送信者の顔
            if (senderIconPath != null) {
                // ファイル読み込み
                val decoded = BitmapFactory.decodeFile(senderIconPath)
                if (decoded != null) {
                    decoded
                } else {
                    // 読み込み失敗したらデフォルト
                    defaultBitmap
                }
            } else {
                // パスがないならデフォルト
                defaultBitmap
            }
        }

        val mainIconCompat = IconCompat.createWithBitmap(mainBitmap)

        // 3. ショートカット登録
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val shortcutPerson = Person.Builder()
                .setName(storedGroupName ?: senderName)
                .setIcon(mainIconCompat) // グループなら固定アイコンになる
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

        // 4. MessagingStyle構築
        val mePerson = Person.Builder().setName("自分").build()
        val style = NotificationCompat.MessagingStyle(mePerson)

        style.conversationTitle = storedGroupName

        // ★ここ重要！「これはグループチャットです」と強制的に設定する
        // これがないと、Androidは「conversationTitle」があっても気を利かせて1対1モード(アイコン非表示)にすることがある
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
                .setIcon(msgIcon) // ここにセットしたアイコンが左側に出る
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
            .setSmallIcon(R.drawable.baseline_chat_bubble_24)
            // ★重要: 通知の右側に出る「全体のアイコン」を明示的にセット
            // これで「最後の人の顔」がチャットの顔になるのを防げる
            .setLargeIcon(mainBitmap)
            .setColor(getColor(R.color.black))
            .setStyle(style)
            .setShortcutId(chatId)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(chatId)
            .setAutoCancel(true)
            .setContentIntent(originalIntent)

        // 5. 通知アクションの追加
        if (replyPendingIntent != null && replyRemoteInputs != null) {

            // NotificationCompat.Action.Builder は androidx.core の RemoteInput を期待するため
            // android.app.RemoteInput から androidx.core.app.RemoteInput に変換する
            val coreRemoteInputs = replyRemoteInputs
                .map { androidx.core.app.RemoteInput.Builder(it.resultKey).setLabel(it.label).build() }
                .toTypedArray()

            // A. Action Builderを初期化
            val actionBuilder = NotificationCompat.Action.Builder(
                R.drawable.ic_launcher_foreground,
                "返信",
                replyPendingIntent
            )

            coreRemoteInputs.forEach { input ->
                actionBuilder.addRemoteInput(input)
            }

            val action: NotificationCompat.Action = actionBuilder
                .setAllowGeneratedReplies(true)
                .build()

            notificationBuilder.addAction(action)
        }

        manager.notify(chatId.hashCode(), notificationBuilder.build())
    }
}
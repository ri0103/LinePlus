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
import kotlinx.coroutines.runBlocking

class NotificationListener : NotificationListenerService() {

    companion object {
        const val CHANNEL_ID = "line_message_channel_v1" // バージョンUP
        const val TAG = "LineNoti_Debug"
        const val MAX_MSG_PER_CHAT = 20
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        // DBインスタンスの初期化
        db = AppDatabase.getDatabase(applicationContext)
    }

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


        serviceScope.launch {
            val lastMsg = db.messageDao().getLatestMessage(chatId)
            val isDuplicate = if (lastMsg != null) {
                val isSameText = (lastMsg.messageText == text)
                val isRecent = (System.currentTimeMillis() - lastMsg.timestamp < 1500)
                isSameText && isRecent
            } else {
                false
            }

            // 重複ならここで終了（DB保存もしないし、通知更新もしない）
            if (isDuplicate) {
                Log.d(TAG, "Duplicate skipped: $text")
                return@launch
            }

            val stickerUri = if (stickerUrl != null) {
                downloadImageToCache(applicationContext, stickerUrl)
            } else { null }

            val iconPath = if (largeIconObj != null) {
                saveIconAndGetPath(applicationContext, largeIconObj, finalSenderName)
            } else { null }

            val newEntity = MessageEntity(
                chatId = chatId,
                senderName = finalSenderName,
                messageText = text,
                timestamp = System.currentTimeMillis(),
                stickerUri = stickerUri?.toString(), // URIを文字列に変換して保存
                iconPath = iconPath
            )

            db.messageDao().insert(newEntity)

            updateHistoryAndNotify(
                groupName,
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
            serviceScope.launch {
                db.messageDao().deleteHistoryByChatId(chatId)
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
        groupName: String?,
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

        val historyList = runBlocking {
            db.messageDao().getLatestMessages(chatId, MAX_MSG_PER_CHAT).reversed()
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

        val isGroup = !groupName.isNullOrEmpty()

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
            defaultBitmap
        } else {
            if (senderIconPath != null) {
                BitmapFactory.decodeFile(senderIconPath) ?: defaultBitmap
            } else {
                defaultBitmap
            }
        }
        val mainIconCompat = IconCompat.createWithBitmap(mainBitmap)

        // 3. ショートカット登録
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val shortcutPerson = Person.Builder()
                .setName(groupName ?: senderName)
                .setIcon(mainIconCompat)
                .setKey(chatId)
                .build()

            val shortcut = ShortcutInfoCompat.Builder(context, chatId)
                .setLongLived(true)
                .setShortLabel(groupName ?: senderName)
                .setPerson(shortcutPerson)
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }

        // 4. MessagingStyle構築
        val mePerson = Person.Builder().setName("自分").build()
        val style = NotificationCompat.MessagingStyle(mePerson)

        style.conversationTitle = groupName

        if (isGroup) {
            style.isGroupConversation = true
        }

        for (entity in historyList) {
            // 各メッセージのアイコン読み込み
            val msgIcon = if (entity.iconPath != null) {
                try {
                    val bm = BitmapFactory.decodeFile(entity.iconPath)
                    IconCompat.createWithBitmap(bm)
                } catch (e: Exception) {
                    IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)
                }
            } else {
                IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)
            }

            val msgPerson = Person.Builder()
                .setName(entity.senderName)
                .setIcon(msgIcon) // ここで個別の顔をセット
                .setKey(entity.senderName)
                .build()

            val styleMessage = NotificationCompat.MessagingStyle.Message(
                entity.messageText,
                entity.timestamp,
                msgPerson
            )
            if (entity.stickerUri != null) {
                styleMessage.setData("image/png", Uri.parse(entity.stickerUri))
            }
            style.addMessage(styleMessage)
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_chat_bubble_24)
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
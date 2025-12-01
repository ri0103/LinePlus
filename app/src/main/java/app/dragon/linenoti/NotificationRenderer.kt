package app.dragon.linenoti

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

class NotificationRenderer(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "line_message_channel_v1"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "LINEメッセージ通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "LINEのメッセージ通知を使いやすくします。"
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 100, 100, 100, 100)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun showNotification(
        chatId: String,
        groupName: String?,
        senderName: String,
        messages: List<MyMessage>,
        intent: PendingIntent?,
        replyIntent: PendingIntent?,
        replyRemoteInputs: Array<android.app.RemoteInput>?,
        wearableActions: List<NotificationCompat.Action>?
    ) {
        val isGroup = groupName != null

        // メイン画像の作成
        val latestIconPath = messages.lastOrNull()?.iconPath
        val mainBitmap = createMainBitmap(latestIconPath, isGroup)
        val mainIconCompat = IconCompat.createWithBitmap(mainBitmap)

        // ショートカット (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pushShortcut(chatId, groupName ?: senderName, mainIconCompat)
        }

        // MessagingStyle の構築
        val mePerson = Person.Builder().setName("自分").build()
        // ★修正: プロパティアクセスではなく、セッターメソッドを使用するように変更
        val style = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle(groupName)
            .setGroupConversation(isGroup)

        for (msg in messages) {
            val person = createPerson(msg)
            val styleMsg = NotificationCompat.MessagingStyle.Message(
                msg.text,
                msg.timestamp,
                person
            )
            if (msg.stickerUri != null) {
                styleMsg.setData("image/png", msg.stickerUri)
            }
            style.addMessage(styleMsg)
        }

        // 1. バブルを開いたときのIntentを作成
        val bubbleIntent = Intent(context, BubbleActivity::class.java).apply {
            putExtra("EXTRA_CHAT_ID", chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // Mutable必須
        )

        // 2. バブルのメタデータを作成
        val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
            bubblePendingIntent,
            mainIconCompat // グループアイコンや送信者アイコン
        )
            .setDesiredHeight(600) // バブルの高さ
            .setSuppressNotification(false)
            .setAutoExpandBubble(false)
            .build()

        // Builder
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_line_bubble2) // ★リソースIDは適宜修正してください
            .setBubbleMetadata(bubbleMetadata)
            .setLargeIcon(mainBitmap)
            .setColor(android.graphics.Color.parseColor("#0e803c"))
            .setStyle(style)
            .setShortcutId(chatId)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(chatId)
            .setAutoCancel(true)
            .setContentIntent(intent)

        if (intent != null) {
            val openLineAction = NotificationCompat.Action.Builder(
                R.drawable.ic_launcher_foreground, // 適当なアイコン（アプリアイコン等）
                "LINEで開く", // ボタンの文字
                intent // 本家LINEのトーク画面を開くIntent
            ).build()

            builder.addAction(openLineAction)
        }

        // 返信アクション
        if (replyIntent != null && replyRemoteInputs != null) {
            addReplyAction(builder, replyIntent, replyRemoteInputs)
        }

        // 2. ★追加: Wear OS用 アクションの追加
        if (wearableActions != null && wearableActions.isNotEmpty()) {
            val wearableExtender = NotificationCompat.WearableExtender()

            // 受け取ったアクションを全部Extenderに追加する
            for (action in wearableActions) {
                wearableExtender.addAction(action)
            }

            // Builderに適用 (これでウォッチ側にボタンが出るようになる)
            builder.extend(wearableExtender)
        }

        notificationManager.notify(chatId.hashCode(), builder.build())
    }

    fun cancelNotification(chatId: String) {
        notificationManager.cancel(chatId.hashCode())
    }

    private fun createMainBitmap(iconPath: String?, isGroup: Boolean): Bitmap {
        if (isGroup) return createDefaultBitmap()

        return if (iconPath != null) {
            BitmapFactory.decodeFile(iconPath) ?: createDefaultBitmap()
        } else {
            createDefaultBitmap()
        }
    }

    private fun createDefaultBitmap(): Bitmap {
        // デフォルトアイコン生成ロジック
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_groups_default) // ★リソースID
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#0e803c")) // LINE Green
        drawable?.let {
            it.setBounds(0, 0, 128, 128)
            it.draw(canvas)
        }
        return bitmap
    }

    private fun createPerson(msg: MyMessage): Person {
        val icon = if (msg.iconPath != null) {
            try {
                val bm = BitmapFactory.decodeFile(msg.iconPath)
                IconCompat.createWithBitmap(bm)
            } catch (e: Exception) {
                IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)
            }
        } else {
            IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)
        }

        return Person.Builder()
            .setName(msg.senderName)
            .setIcon(icon)
            .setKey(msg.senderName)
            .build()
    }

    private fun pushShortcut(chatId: String, label: String, icon: IconCompat) {
        val shortcutPerson = Person.Builder()
            .setName(label)
            .setIcon(icon)
            .setKey(chatId)
            .build()

        val shortcut = ShortcutInfoCompat.Builder(context, chatId)
            .setLongLived(true)
            .setShortLabel(label)
            .setPerson(shortcutPerson)
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private fun addReplyAction(
        builder: NotificationCompat.Builder,
        pendingIntent: PendingIntent,
        remoteInputs: Array<android.app.RemoteInput>
    ) {
        val coreRemoteInputs = remoteInputs.map {
            androidx.core.app.RemoteInput.Builder(it.resultKey).setLabel(it.label).build()
        }.toTypedArray()

        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground, // ★アイコン
            "返信",
            pendingIntent
        ).apply {
            coreRemoteInputs.forEach { addRemoteInput(it) }
            setAllowGeneratedReplies(true)
        }.build()

        builder.addAction(action)
    }
}
package app.dragon.linenoti

import android.app.PendingIntent
import android.content.ContentValues.TAG
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // 各機能コンポーネント
    private lateinit var repository: ChatRepository
    private lateinit var imageManager: ImageManager
    private lateinit var renderer: NotificationRenderer

    override fun onCreate() {
        super.onCreate()
        // コンポーネントの初期化
        repository = ChatRepository()
        imageManager = ImageManager(applicationContext)
        renderer = NotificationRenderer(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        imageManager.clearCache()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != "jp.naver.line.android") return
        val notification = sbn.notification
        val extras = notification.extras

        // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
        // ★デバッグ用ログ出力: 通知の中身を全部吐き出す
        // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
        Log.d(TAG, "🚨 --- LINE通知受信 (${System.currentTimeMillis()}) ---")
        Log.d(TAG, "ID: ${sbn.id}, Tag: ${sbn.tag}, Key: ${sbn.key}")
        Log.d(TAG, "PostTime: ${sbn.postTime}")
        Log.d(TAG, "IsGroup: ${extras.getBoolean("android.isGroupConversation")}")

        for (key in extras.keySet()) {
            val value = extras.get(key)
            // 画像データなどは長すぎるので型だけ表示
            val valueStr = when(value) {
                is Bitmap -> "[Bitmap ${value.width}x${value.height}]"
                is Icon -> "[Icon]"
                else -> value.toString()
            }
            Log.d(TAG, "Extra: $key = $valueStr")
        }
        Log.d(TAG, "------------------------------------------")
        // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

        // 1. データ抽出 (簡単なパースはここで行う)
        val text = extras.getCharSequence("android.text")?.toString() ?: "スタンプ"
        // フィルタリング
        if (shouldIgnore(notification, text)) return

        val chatId = extras.getString("line.chat.id") ?: "unknown_chat"

        // グループ名 & 送信者名の解決
        val groupName = extras.getString("android.conversationTitle")
            ?: extras.getString("android.hiddenConversationTitle")
        val rawTitle = extras.getString("android.title") ?: "不明"
        val senderName = resolveSenderName(rawTitle, groupName)

        val stickerUrl = extras.getString("line.sticker.url")
        val originalIntent = notification.contentIntent

        // 返信アクション抽出
        val (replyIntent, replyRemoteInputs) = extractReplyActions(notification)

        // --- 2. ★追加★ Wear OS用 Action抽出 (WearableExtender) ---
        // 本家通知に含まれている「ウォッチ用拡張機能」を取り出す
        val wearableExtender = NotificationCompat.WearableExtender(notification)
        val wearableActions = wearableExtender.actions // これがウォッチ用アクションのリスト

        // 画像
        val largeIconObj = extras.get("android.largeIcon")

        serviceScope.launch {
            // 2. 画像処理 (非同期)
            val stickerUri = stickerUrl?.let { imageManager.downloadSticker(it) }
            val iconPath = imageManager.saveIcon(largeIconObj, senderName)

            val lineMessageId = extras.getString("line.message.id")

            // 3. データの保存と更新判定
            val shouldNotify = repository.addMessage(
                messageId = lineMessageId,
                chatId = chatId,
                senderName = senderName,
                text = text,
                stickerUri = stickerUri,
                iconPath = iconPath,
                groupName = groupName,
                intent = originalIntent
            )

            // 4. 通知の発行
            if (shouldNotify) {
                // 最新の情報を取得して表示
                val messages = repository.getMessages(chatId)
                // Intentはキャッシュにある最新のものを使用（後出し更新対応）
                val finalIntent = repository.getIntent(chatId) ?: originalIntent

                renderer.showNotification(
                    chatId = chatId,
                    groupName = repository.getGroupName(chatId), // 保存されている最新のグループ名
                    senderName = senderName,
                    messages = messages,
                    intent = finalIntent,
                    replyIntent = replyIntent,
                    replyRemoteInputs = replyRemoteInputs,
                    wearableActions = wearableActions
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn?.packageName != "jp.naver.line.android") return
        val chatId = sbn.notification.extras.getString("line.chat.id") ?: return

        serviceScope.launch {
            repository.removeChat(chatId)
            renderer.cancelNotification(chatId)
        }
    }

    // --- Helper Functions ---

    private fun shouldIgnore(notification: android.app.Notification, text: String): Boolean {
        val lineMessageId = notification.extras.getString("line.message.id")

        if (notification.category == NotificationCompat.CATEGORY_CALL) return true
        if (lineMessageId == null) {
            if (text.contains("不在着信") || text.contains("通話")) return true
            if (notification.category == NotificationCompat.CATEGORY_PROMO) return true
        }
        if (text == "新着メッセージがあります") return true
        return false
    }

    private fun resolveSenderName(rawTitle: String, groupName: String?): String {
        if (groupName.isNullOrEmpty()) return rawTitle
        return rawTitle.replace("$groupName: ", "")
            .replace("$groupName : ", "")
            .replace(groupName, "")
            .trim()
            .ifEmpty { rawTitle }
    }

    private fun extractReplyActions(notification: android.app.Notification): Pair<PendingIntent?, Array<android.app.RemoteInput>?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notification.actions?.lastOrNull()?.let { action ->
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    return Pair(
                        action.actionIntent,
                        action.remoteInputs.map { it as android.app.RemoteInput }.toTypedArray()
                    )
                }
            }
        }
        return Pair(null, null)
    }
}
package app.dragon.linenoti

import android.app.PendingIntent
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedHashMap

class ChatRepository {

    companion object {
        private const val MAX_CHAT_HISTORY_SIZE = 30
        private const val MAX_MSG_PER_CHAT = 20
        private const val TAG = "ChatRepo"
    }

    // Coroutine環境で安全にロックするためのMutex
    private val mutex = Mutex()

    // 履歴データ (LRUキャッシュ)
    private val chatHistory = LinkedHashMap<String, MutableList<MyMessage>>(16, 0.75f, true)

    // メタデータ
    private val chatMetadata = mutableMapOf<String, String>() // GroupName
    private val chatIntents = mutableMapOf<String, PendingIntent>() // Intent

    // メッセージを追加する (戻り値: 通知を更新すべきかどうか)
    suspend fun addMessage(
        chatId: String,
        senderName: String,
        text: String,
        stickerUri: android.net.Uri?,
        iconPath: String?,
        groupName: String?,
        intent: PendingIntent?
    ): Boolean {
        mutex.withLock {
            // グループ名更新
            if (!groupName.isNullOrEmpty()) {
                chatMetadata[chatId] = groupName
            }

            // Intent更新
            if (intent != null) {
                // 既存より新しいIntentなら更新
                if (chatIntents[chatId] != intent) {
                    chatIntents[chatId] = intent
                }
            }

            // 重複チェック
            val list = chatHistory[chatId]
            if (list != null && list.isNotEmpty()) {
                val lastMsg = list.last()
                val isSameText = (lastMsg.text == text)
                val isRecent = (System.currentTimeMillis() - lastMsg.timestamp < 1500)

                if (isSameText && isRecent) {
                    // 重複検知！
                    var updated = false

                    // アイコンだけ新しくなっている場合の更新処理
                    if (iconPath != null && lastMsg.iconPath != iconPath) {
                        Log.d(TAG, "重複だがアイコン更新を検知")
                        val newMsg = lastMsg.copy(iconPath = iconPath)
                        list[list.size - 1] = newMsg
                        updated = true
                    }

                    // 重複で、かつ更新も不要なら false を返す
                    return updated
                }
            }

            // 新規追加
            if (!chatHistory.containsKey(chatId)) {
                // 履歴サイズ制限
                if (chatHistory.size >= MAX_CHAT_HISTORY_SIZE) {
                    val it = chatHistory.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                chatHistory[chatId] = mutableListOf()
            }

            val newList = chatHistory[chatId]!!
            newList.add(MyMessage(senderName, text, System.currentTimeMillis(), stickerUri, iconPath))

            if (newList.size > MAX_MSG_PER_CHAT) {
                newList.removeAt(0)
            }

            return true // 通知すべき
        }
    }

    suspend fun getMessages(chatId: String): List<MyMessage> {
        mutex.withLock {
            return chatHistory[chatId]?.toList() ?: emptyList()
        }
    }

    suspend fun getGroupName(chatId: String): String? {
        mutex.withLock {
            return chatMetadata[chatId]
        }
    }

    suspend fun getIntent(chatId: String): PendingIntent? {
        mutex.withLock {
            return chatIntents[chatId]
        }
    }

    suspend fun removeChat(chatId: String) {
        mutex.withLock {
            chatHistory.remove(chatId)
            chatIntents.remove(chatId)
            chatMetadata.remove(chatId)
        }
    }
}
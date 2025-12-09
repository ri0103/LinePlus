package app.dragon.linenoti

import android.app.PendingIntent
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.filter

object ChatRepository {
    private const val MAX_CHAT_HISTORY_SIZE = 30
    private const val MAX_MSG_PER_CHAT = 100
    private const val TAG = "ChatRepo"

    // Coroutine環境で安全にロックするためのMutex
    private val mutex = Mutex()
    private val chatHistory = LinkedHashMap<String, MutableList<MyMessage>>(16, 0.75f, true)
    val chatMetadata = mutableMapOf<String, String>() // GroupName
    private val chatIntents = mutableMapOf<String, PendingIntent>() // Intent

    private val _messagesUpdateFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    // メッセージを追加する (戻り値: 通知を更新すべきかどうか)
    suspend fun addMessage(
        messageId: String?,
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

            // 重複チェック
            val list = chatHistory[chatId]
            // 2. IDによる重複チェック & 更新
            if (messageId != null && list != null) {
                // 同じIDのメッセージを探す (インデックスも知りたいので indexOfFirst は使わずループか find)
                val existingIndex = list.indexOfFirst { it.messageId == messageId }

                if (existingIndex != -1) {
                    //重複上書き処理は一旦お休み
//                    val existingMsg = list[existingIndex]
//                    var isContentUpdated = false
//
//                    // A. アイコン更新チェック
//                    // (新しいアイコンがあり、かつ既存と違う場合)
//                    if (iconPath != null && existingMsg.iconPath != iconPath) {
//                        Log.d(TAG, "ID重複: アイコン更新を検知")
//                        isContentUpdated = true
//                    }
//
//                    // B. スタンプ更新チェック
//                    // (新しいスタンプURIがあり、かつ既存と違う場合)
//                    if (stickerUri != null && existingMsg.stickerUri != stickerUri) {
//                        Log.d(TAG, "ID重複: スタンプ更新を検知")
//                        isContentUpdated = true
//                    }
//
//                    // 更新があるならリストを書き換える
//                    if (isContentUpdated) {
//                        // アイコンやスタンプを新しいものに更新したメッセージを作る
//                        val updatedMsg = existingMsg.copy(
//                            iconPath = iconPath ?: existingMsg.iconPath, // 新しいのがなければ古いの維持
//                            stickerUri = stickerUri ?: existingMsg.stickerUri
//                        )
//                        list[existingIndex] = updatedMsg
//                        _messagesUpdateFlow.tryEmit(chatId)
//                    }
//
//                    return isContentUpdated
                    return false
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
            newList.add(MyMessage(messageId ,senderName, text, System.currentTimeMillis(), stickerUri, iconPath))

            if(intent != null){
                chatIntents[chatId] = intent
            }

            if (newList.size > MAX_MSG_PER_CHAT) {
                newList.removeAt(0)
            }

            _messagesUpdateFlow.tryEmit(chatId)

            return true
        }
    }

    suspend fun getMessages(chatId: String): List<MyMessage> {
        mutex.withLock {
            return chatHistory[chatId]?.toList() ?: emptyList()
        }
    }

    // ★追加: リアルタイム監視用のFlow
    fun getMessagesFlow(chatId: String): Flow<List<MyMessage>> {
        return _messagesUpdateFlow
            .filter { it == chatId } // このチャットの更新だけ拾う
            .onStart { emit(chatId) } // 購読開始時に今のデータを一度流す
            .map {
                getMessages(chatId) // 最新リストを取得
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
package app.dragon.linenoti

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageDao {
    // 最新のメッセージを20件取得 (通知表示用)
    @Query("SELECT * FROM message_history WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestMessages(chatId: String, limit: Int): List<MessageEntity>

    // ★追加: 重複チェック用に「最新の1件だけ」を取得する命令
    @Query("SELECT * FROM message_history WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(chatId: String): MessageEntity?

    // メッセージを挿入
    @Insert
    suspend fun insert(message: MessageEntity)

    // 履歴削除
    @Query("DELETE FROM message_history WHERE chatId = :chatId")
    suspend fun deleteHistoryByChatId(chatId: String)
}
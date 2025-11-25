package app.dragon.linenoti

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.net.Uri

@Entity(tableName = "message_history")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0, // 主キー (自動生成)
    val chatId: String,         // 会話ID
    val senderName: String,     // 送信者名
    val messageText: String,    // メッセージ本文
    val timestamp: Long,        // タイムスタンプ
    val stickerUri: String?,    // スタンプURI (文字列で保存)
    val iconPath: String?       // アイコンファイルパス (文字列で保存)
)
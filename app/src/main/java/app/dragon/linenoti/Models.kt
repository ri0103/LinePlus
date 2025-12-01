package app.dragon.linenoti

import android.app.PendingIntent
import android.net.Uri

data class MyMessage(
    val messageId: String?,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val stickerUri: Uri?,
    val iconPath: String?
)
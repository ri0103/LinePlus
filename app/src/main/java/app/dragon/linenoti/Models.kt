package app.dragon.linenoti

import android.net.Uri

data class MyMessage(
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val stickerUri: Uri?,
    val iconPath: String?
)
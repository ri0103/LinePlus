package app.dragon.linenoti

import android.app.PendingIntent
import android.os.Bundle
import android.app.ActivityOptions
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra("EXTRA_CHAT_ID") ?: return

        setContent {
            MaterialTheme {
                BubbleScreen(chatId)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(chatId: String) {
    val messages by ChatRepository.getMessagesFlow(chatId).collectAsState(initial = emptyList())
    var groupName by remember { mutableStateOf<String?>(null) }

    var targetIntent by remember { mutableStateOf<PendingIntent?>(null) }
    // スクロール状態の管理
    val listState = rememberLazyListState()

    LaunchedEffect(chatId) {
        groupName = ChatRepository.getGroupName(chatId)
        targetIntent = ChatRepository.getIntent(chatId)
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = groupName ?: "トーク",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF2F4F7) // LINEっぽいヘッダー色
                ),
                actions = {
                    if (targetIntent != null) {
                        //バブルから本家トーク画面を開く
                        IconButton(onClick = {
                            try {
                                val options = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
                                    ActivityOptions.makeBasic()
                                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                                        .toBundle()
                                } else {
                                    // Android 13以下は標準オプションでOK（またはnull）
                                    ActivityOptions.makeBasic().toBundle()
                                }

                                // 本家LINEを開く（オプション付きで！）
                                targetIntent?.send(null, 0, null, null, null, null, options)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }) {
                            // アイコンは "Open In New" (四角から矢印が出るやつ) がわかりやすい
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open LINE",
                                tint = Color.Black
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        // 背景色
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF8C9FB8)) // LINEのトーク背景っぽい色
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    ChatBubbleRow(msg)
                }
            }
        }
    }
}

@Composable
fun ChatBubbleRow(msg: MyMessage) {
    // 送信者が「自分」かどうか判定 (名前で簡易判定)
    val isMe = msg.senderName == "自分"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        // --- 相手の場合：アイコン表示 ---
        if (!isMe) {
            UserAvatar(iconPath = msg.iconPath)
            Spacer(modifier = Modifier.width(8.dp))
        }

        // --- メッセージ本体 ---
        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            // 名前 (相手の場合のみ)
            if (!isMe) {
                Text(
                    text = msg.senderName,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = Color.White,
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false
                        )
                    ),
                    modifier = Modifier.padding(top = 4.dp,bottom = 6.dp, start = 2.dp)
                )
            }

            // 吹き出し または スタンプ
            if (msg.stickerUri != null) {
                // スタンプ表示
                StickerImage(uri = msg.stickerUri!!)
            } else {
                // テキスト吹き出し
                MessageBubble(text = msg.text, isMe = isMe)
            }
        }

        Text(
            text = formatTime(msg.timestamp),
            style = TextStyle(
                fontSize = 8.sp,
                color = Color.White.copy(alpha = 0.7f),
                platformStyle = PlatformTextStyle(
                    includeFontPadding = false // ★重要：文字の上下の余白を消す
                )
            ),
            modifier = Modifier
                .align((Alignment.Bottom))
                .padding(start = 10.dp)
        )

    }
}

@Composable
fun UserAvatar(iconPath: String?) {
    val context = LocalContext.current

    // 画像ソースの決定
    val model = if (iconPath != null) {
        File(iconPath) // 保存されたファイルパス
    } else {
        R.drawable.ic_launcher_foreground // デフォルト
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            .crossfade(true)
            .build(),
        contentDescription = "Icon",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White)
    )
}

@Composable
fun StickerImage(uri: android.net.Uri) {
    AsyncImage(
        model = uri,
        contentDescription = "Sticker",
        modifier = Modifier
            .size(108.dp) // スタンプっぽいサイズ
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
fun MessageBubble(text: String, isMe: Boolean) {
    val bubbleColor = if (isMe) Color(0xFF06C755) else Color.White // LINE Green vs White
    val textColor = if (isMe) Color.White else Color.Black

    Surface(
        color = bubbleColor,
        shape = RoundedCornerShape(
            topStart =  if (isMe) 12.dp else 3.dp,
            topEnd = 12.dp,
            bottomStart = 12.dp,
            bottomEnd = if (isMe) 3.dp else 12.dp
        ),
        shadowElevation = 1.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = textColor,
            fontSize = 14.sp
        )
    }
}

// タイムスタンプのフォーマット
fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
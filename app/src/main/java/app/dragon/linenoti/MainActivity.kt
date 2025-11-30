package app.dragon.linenoti

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 権限状態の管理
    var isPostNotiGranted by remember { mutableStateOf(checkPostNotificationPermission(context)) }
    var isListenerGranted by remember { mutableStateOf(checkListenerPermission(context)) }
    var isBatteryGranted by remember { mutableStateOf(checkBatteryOptimization(context)) }
//    var isUsageStatsGranted by remember { mutableStateOf(checkUsageStatsPermission(context)) }

    // 画面に戻るたびに再チェック
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPostNotiGranted = checkPostNotificationPermission(context)
                isListenerGranted = checkListenerPermission(context)
                isBatteryGranted = checkBatteryOptimization(context)
//                isUsageStatsGranted = checkUsageStatsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 通知権限リクエスト用のランチャー
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isPostNotiGranted = isGranted
    }

    val lineGreen = Color(0xFF06C755)
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F0F0))
            .verticalScroll(scrollState) // スクロール可能に
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ヘッダー
        Spacer(modifier = Modifier.height(20.dp))
        Icon(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Logo",
            tint = lineGreen,
            modifier = Modifier.size(80.dp)
        )
        Text("LINE+ 設定", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("以下の3つの設定を有効にしてください", fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(32.dp))

        // --- Step 1: 通知の許可 ---
        SettingItemCard(
            title = "1. 通知の表示許可",
            desc = "アプリが通知を出すために必要です。",
            isDone = isPostNotiGranted,
            buttonLabel = "許可する",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Android 12以下は自動で許可されている
                    isPostNotiGranted = true
                }
            }
        )

        // --- Step 2: 読み取り権限 ---
        SettingItemCard(
            title = "2. 通知へのアクセス",
            desc = "LINEのメッセージを読み取るために必要です。",
            isDone = isListenerGranted,
            buttonLabel = "設定画面へ",
            onClick = { openNotificationAccessSettings(context) }
        )

        // --- Step 3: バッテリー制限解除 ---
        SettingItemCard(
            title = "3. バッテリー制限なし",
            desc = "バックグラウンドで常時監視するために必須です。",
            isDone = isBatteryGranted,
            buttonLabel = "制限を解除",
            onClick = { requestIgnoreBatteryOptimizations(context) }
        )

//        SettingItemCard(
//            title = "4. 使用状況へのアクセス",
//            desc = "「既読」と「送信取消」を見分けるために必要です。\n(これをONにすると送信取消された通知が消えずに残ります)",
//            isDone = isUsageStatsGranted,
//            buttonLabel = "設定画面へ",
//            onClick = { openUsageStatsSettings(context) }
//        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- 完了後の案内 ---
        if (isPostNotiGranted && isListenerGranted && isBatteryGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("✅ 準備完了！", fontWeight = FontWeight.Bold, color = lineGreen, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("最後に、本家LINEの「メッセージ通知」を【サイレント】に設定すれば完了です。", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { openLineAppSettings(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("LINEの設定を開く")
                    }
                }
            }
        }
    }
}

// 共通のカードコンポーネント
@Composable
fun SettingItemCard(
    title: String,
    desc: String,
    isDone: Boolean,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(desc, fontSize = 12.sp, color = Color.Gray)
            }
            if (isDone) {
                Text("✅", fontSize = 24.sp)
            } else {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06C755)),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(buttonLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

// --- ロジック関数たち ---

// 1. 通知許可チェック (Android 13+)
fun checkPostNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true // Android 12以下は不要
    }
}

// 2. リスナー許可チェック
fun checkListenerPermission(context: Context): Boolean {
    val sets = NotificationManagerCompat.getEnabledListenerPackages(context)
    return sets.contains(context.packageName)
}

// 3. バッテリー最適化チェック
fun checkBatteryOptimization(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

// Intent: 通知アクセス設定
fun openNotificationAccessSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (e: Exception) { }
}

// Intent: バッテリー最適化の無視をリクエスト (ダイアログが出る)
fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // 万が一ダイアログが出せない機種用
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.startActivity(intent)
        } catch (ex: Exception) {}
    }
}

fun checkUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun openUsageStatsSettings(context: Context) {
    try {
        context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
    } catch (e: Exception) { }
}

// Intent: LINE設定
fun openLineAppSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, "jp.naver.line.android")
        }
        context.startActivity(intent)
    } catch (e: Exception) { }
}
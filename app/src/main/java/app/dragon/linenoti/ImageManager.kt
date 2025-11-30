package app.dragon.linenoti

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ImageManager(private val context: Context) {

    // スタンプ画像をダウンロードして保存
    suspend fun downloadSticker(url: String): Uri? = withContext(Dispatchers.IO) {
        val cachePath = File(context.cacheDir, "images")
        if (!cachePath.exists()) cachePath.mkdirs()

        val fileName = "sticker_${url.hashCode()}.png"
        val file = File(cachePath, fileName)

        if (file.exists()) {
            return@withContext FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }

        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()

        return@withContext try {
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as BitmapDrawable).bitmap
                saveBitmapToFile(bitmap, file)
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // アイコンを保存してパスを返す
    suspend fun saveIcon(iconObj: Any?, nameKey: String): String? = withContext(Dispatchers.IO) {
        if (iconObj == null) return@withContext null

        try {
            val bitmap = when (iconObj) {
                is Bitmap -> iconObj
                is Icon -> {
                    val drawable = iconObj.loadDrawable(context) ?: return@withContext null
                    drawableToBitmap(drawable)
                }
                else -> return@withContext null
            }

            val cachePath = File(context.cacheDir, "user_icons")
            if (!cachePath.exists()) cachePath.mkdirs()

            val fileName = "icon_${nameKey.hashCode()}.png"
            val file = File(cachePath, fileName)

            if (!file.exists()) {
                // アイコンは小さくリサイズして保存
                val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
                saveBitmapToFile(scaled, file)
            }
            return@withContext file.absolutePath
        } catch (e: Exception) {
            return@withContext null
        }
    }

    // キャッシュ削除
    fun clearCache() {
        try {
            File(context.cacheDir, "images").deleteRecursively()
            File(context.cacheDir, "user_icons").deleteRecursively()
        } catch (e: Exception) { }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
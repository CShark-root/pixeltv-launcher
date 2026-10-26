package com.pixelden.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.system.AdbClient
import com.pixelden.launcher.ui.toHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Что можно досмотреть: запись системного списка «Продолжить просмотр» (Watch Next). */
data class WatchItem(
    val id: Long,
    val pkg: String,
    val title: String,
    val subtitle: String,
    /** Досмотрено, 0..1; null — неизвестно (например, следующая серия). */
    val progress: Float?,
    val intentUri: String,
    val poster: String,
)

/**
 * Ряд «Продолжить просмотр». Приложения (Кинопоиск, SmartTube, Okko…) сами пишут в системный список, на чём
 * остановились; лаунчер его только читает. Нужно право READ_TV_LISTINGS — его выдаём через встроенный adb.
 */
class WatchNextRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<WatchItem>>(emptyList())
    val items: StateFlow<List<WatchItem>> = _items

    private var observer: ContentObserver? = null
    private var reload: Job? = null

    private fun granted() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Включить ряд: получить право, прочитать список и следить за ним. */
    fun start() {
        if (observer != null) return
        scope.launch(Dispatchers.IO) {
            if (!granted()) {
                // Без вопроса на экране: если доступа к отладке ещё нет, его выдаст «Настроить всё».
                runCatching { AdbClient(context).shell("pm grant ${context.packageName} $PERMISSION", ask = false) }
                    .onFailure { DebugLog.i("продолжить просмотр: нет права, adb — ${it.message}") }
            }
            if (!granted()) return@launch
            withContext(Dispatchers.Main) {
                if (observer != null) return@withContext
                observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = refresh()
                }.also { context.contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, it) }
            }
            refresh()
        }
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        _items.value = emptyList()
        posters.evictAll()
    }

    /** Перечитать список. Приложения пишут пачками — ждём, пока утихнет. */
    fun refresh() {
        reload?.cancel()
        reload = scope.launch(Dispatchers.IO) {
            delay(500)
            _items.value = runCatching { query() }.onFailure { DebugLog.e("продолжить просмотр", it) }.getOrDefault(emptyList())
        }
    }

    private fun query(): List<WatchItem> {
        val cols = arrayOf(
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        )
        val pm = context.packageManager
        val out = ArrayList<WatchItem>()
        context.contentResolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC",
        )?.use { cur ->
            fun s(i: Int) = cur.getString(i).orEmpty()
            while (cur.moveToNext() && out.size < LIMIT) {
                if (!cur.isNull(12) && cur.getInt(12) == 0) continue
                val pkg = s(1)
                // Удалённое приложение могло оставить записи — их не показываем.
                if (runCatching { pm.getPackageInfo(pkg, 0) }.isFailure) continue
                val intent = s(8)
                if (intent.isBlank()) continue
                val episode = listOfNotNull(
                    s(4).takeIf { it.isNotBlank() }?.let { "S$it" },
                    s(5).takeIf { it.isNotBlank() }?.let { "E$it" },
                ).joinToString("") + s(3).takeIf { it.isNotBlank() && !it.equals(s(2), ignoreCase = true) }?.let { " $it" }.orEmpty()
                val pos = cur.getLong(6); val dur = cur.getLong(7)
                out += WatchItem(
                    id = cur.getLong(0), pkg = pkg,
                    title = s(2).ifBlank { s(3) },
                    subtitle = episode.trim(),
                    progress = if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null,
                    intentUri = intent,
                    poster = s(9).ifBlank { s(10) },
                )
            }
        }
        return out
    }

    /** Открыть запись: ссылка, которую оставило приложение; не вышло — просто приложение. */
    fun open(item: WatchItem): Boolean = runCatching {
        context.startActivity(Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.recoverCatching {
        context.startActivity(context.packageManager.getLeanbackLaunchIntentForPackage(item.pkg)
            ?: context.packageManager.getLaunchIntentForPackage(item.pkg)!!)
    }.isSuccess

    // Постеры ужимаем до размера карточки и держим в видеопамяти; не больше 12 штук (~3 МБ).
    private val posters = LruCache<String, ImageBitmap>(12)

    suspend fun poster(uri: String, w: Int, h: Int): ImageBitmap? {
        if (uri.isBlank()) return null
        posters.get(uri)?.let { return it }
        val img = withContext(Dispatchers.IO) { runCatching { decode(uri, w, h)?.toHardware()?.asImageBitmap() }.getOrNull() } ?: return null
        posters.put(uri, img)
        return img
    }

    private fun decode(uri: String, w: Int, h: Int): Bitmap? {
        val bytes = if (uri.startsWith("http")) {
            val conn = URL(uri).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
        } else context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Как ContentScale.Crop: заполняем карточку, лишнее срезаем.
        val k = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val sw = (bmp.width * k).toInt().coerceAtLeast(w); val sh = (bmp.height * k).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val out = Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
        if (scaled !== bmp) bmp.recycle()
        if (out !== scaled) scaled.recycle()
        return out
    }

    companion object {
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"
        private const val LIMIT = 15
    }
}

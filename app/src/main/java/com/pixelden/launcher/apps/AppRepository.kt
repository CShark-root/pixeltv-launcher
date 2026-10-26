package com.pixelden.launcher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.LruCache
import com.pixelden.launcher.ui.toHardware
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
)

data class InputEntry(val id: String, val label: String, val type: Int)

class AppRepository(private val context: Context, private val scope: CoroutineScope) {

    private val pm: PackageManager = context.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _inputs = MutableStateFlow<List<InputEntry>>(emptyList())
    val inputs: StateFlow<List<InputEntry>> = _inputs

    /**
     * Картинка в кэше и сколько карточек на экране её сейчас рисуют. Выброшенную из кэша картинку
     * освобождаем сразу, как только её никто не рисует, — а не когда-нибудь сборщиком мусора.
     */
    private class Slot(val img: ImageBitmap) { var users = 0; var dropped = false }
    private val lock = Any()
    private val live = java.util.IdentityHashMap<ImageBitmap, Slot>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // Баннеры 320x180 ~230 КБ; 48 штук ≈ 11 МБ — разумный потолок для ТВ с 1,8 ГБ.
    private val images = object : LruCache<String, Slot>(48) {
        override fun entryRemoved(evicted: Boolean, key: String, old: Slot, new: Slot?) = synchronized(lock) {
            old.dropped = true
            if (old.users == 0) free(old)
        }
    }

    private fun free(s: Slot) {
        live.remove(s.img)
        // Через пару кадров: экран мог ещё не перерисоваться без этой картинки.
        main.postDelayed({ synchronized(lock) { if (s.users == 0) s.img.asAndroidBitmap().recycle() } }, 200)
    }

    /**
     * Выбросить из кэша все картинки. Те, что сейчас не на экране, освобождаются сразу; возвращает их объём.
     * Картинки на экране освободятся, когда их карточки уйдут с экрана.
     */
    fun trimImages(): Long = synchronized(lock) {
        val idle = images.snapshot().values.filter { it.users == 0 }.sumOf { it.img.asAndroidBitmap().allocationByteCount.toLong() }
        images.evictAll()
        idle
    }

    /** Картинки, которые держат карточки на экране: [banner] и [icon] выдают их с отметкой, вернуть — [release]. */
    fun release(img: ImageBitmap) = synchronized(lock) {
        val s = live[img] ?: return@synchronized
        s.users--
        if (s.users == 0 && s.dropped) free(s)
    }

    init {
        refresh()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                i.data?.schemeSpecificPart?.let { pkg -> images.remove("b:$pkg"); images.remove("i:$pkg") }
                refresh()
            }
        }, filter)
    }

    fun refresh() {
        scope.launch {
            _apps.value = withContext(Dispatchers.IO) { queryApps() }
            _inputs.value = withContext(Dispatchers.IO) { queryInputs() }
        }
    }

    private fun queryApps(): List<AppEntry> {
        val result = LinkedHashMap<String, AppEntry>()
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in pm.queryIntentActivities(leanback, 0)) {
            val ai = ri.activityInfo
            result[ai.packageName] = AppEntry(
                ai.packageName, ri.loadLabel(pm).toString(),
                ComponentName(ai.packageName, ai.name), leanback = true,
            )
        }
        // Обычные лаунчер-приложения без TV-активити (например, CX Explorer, PCAPdroid).
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val ai = ri.activityInfo
            if (ai.packageName in result) continue
            result[ai.packageName] = AppEntry(
                ai.packageName, ri.loadLabel(pm).toString(),
                ComponentName(ai.packageName, ai.name), leanback = false,
            )
        }
        result.remove(context.packageName)
        return result.values.sortedBy { it.label.lowercase() }
    }

    private fun queryInputs(): List<InputEntry> = try {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        tim.tvInputList
            .filter { it.isPassthroughInput || it.type == TvInputInfo.TYPE_TUNER }
            .map { info ->
                val custom = info.loadCustomLabel(context)?.toString()
                InputEntry(info.id, custom?.takeIf { it.isNotBlank() } ?: info.loadLabel(context).toString(), info.type)
            }
    } catch (e: Exception) {
        emptyList()
    }

    fun launchIntent(pkg: String): Intent? {
        val entry = _apps.value.firstOrNull { it.pkg == pkg }
        val intent = if (entry != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(if (entry.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
        } else {
            pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun inputIntent(inputId: String): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Баннер приложения (16:9) или null, если его нет. Отмечен как занятый — вернуть через [release]. */
    suspend fun banner(pkg: String): ImageBitmap? = acquire("b:$pkg") {
        val d = runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            ?: _apps.value.firstOrNull { it.pkg == pkg }?.let { runCatching { pm.getActivityBanner(it.component) }.getOrNull() }
        d?.toBitmap(320, 180)?.toHardware()?.asImageBitmap()
    }

    /** Иконка приложения. Отмечена как занятая — вернуть через [release]. */
    suspend fun icon(pkg: String): ImageBitmap? = acquire("i:$pkg") {
        runCatching { pm.getApplicationIcon(pkg) }.getOrNull()?.toBitmap(128, 128)?.toHardware()?.asImageBitmap()
    }

    /** Иконка для веб-панели: обычная картинка мимо кэша — её сожмут в PNG и забудут. */
    fun iconBitmap(pkg: String): Bitmap? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()?.toBitmap(128, 128)

    private suspend fun acquire(key: String, load: () -> ImageBitmap?): ImageBitmap? {
        synchronized(lock) { images.get(key)?.let { it.users++; return it.img } }
        val img = withContext(Dispatchers.IO) { load() } ?: return null
        synchronized(lock) {
            // Пока грузили, эту же картинку могла загрузить соседняя карточка — берём её, свою выбрасываем.
            images.get(key)?.let { it.users++; img.asAndroidBitmap().recycle(); return it.img }
            val s = Slot(img).also { it.users = 1 }
            live[img] = s
            images.put(key, s)
        }
        return img
    }

    private fun Drawable.toBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, w, h)
        draw(Canvas(bmp))
        return bmp
    }
}

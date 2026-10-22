package com.pixelden.launcher.scenario

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.ConfigStore
import com.pixelden.launcher.data.LauncherConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал отладки: последние события движка сценариев, кнопок и ошибок. Виден в лаунчере и в logcat (тег PixelDen). */
object DebugLog {
    data class Entry(val time: Long, val msg: String, val error: Boolean)

    private const val MAX = 500
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Писать ли в журнал каждое нажатие кнопки пульта (включается в «Настройки → Отладка»). */
    var logKeys by androidx.compose.runtime.mutableStateOf(false)

    @Synchronized
    private fun add(e: Entry) {
        buffer.addLast(e)
        while (buffer.size > MAX) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    fun i(msg: String) {
        Log.i("PixelDen", msg)
        add(Entry(System.currentTimeMillis(), msg, false))
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.w("PixelDen", msg, t)
        val detail = t?.let { ": ${it.javaClass.simpleName} ${it.message ?: ""}" } ?: ""
        add(Entry(System.currentTimeMillis(), msg + detail, true))
    }

    fun clear() = synchronized(this) { buffer.clear(); _entries.value = emptyList() }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun format(e: Entry) = "${fmt.format(Date(e.time))} ${if (e.error) "!" else " "} ${e.msg}"

    /** Конфиг + журнал + состояние служб — в /sdcard/Download/pixelden-debug.txt. */
    fun dumpToFile(context: Context, cfg: LauncherConfig): File {
        val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "pixelden-debug.txt")
        out.parentFile?.mkdirs()
        out.writeText(buildString {
            appendLine("PixelDen debug dump ${Date()}")
            appendLine("accessibility service: ${if (DenAccessibilityService.instance != null) "bound" else "NOT bound"}")
            appendLine("canWrite system settings: ${android.provider.Settings.System.canWrite(context)}")
            appendLine("can draw overlays: ${android.provider.Settings.canDrawOverlays(context)}")
            appendLine()
            appendLine("=== log (oldest first)")
            entries.value.forEach { appendLine(format(it)) }
            appendLine()
            appendLine("=== config")
            appendLine(ConfigStore.json.encodeToString(LauncherConfig.serializer(), cfg))
        })
        return out
    }
}

/**
 * Служебные команды через adb (приёмник защищён правом DUMP — его есть у adb shell, но нет у обычных приложений):
 *   am broadcast -a com.pixelden.launcher.DUMP -n com.pixelden.launcher/.scenario.DebugReceiver
 *   am broadcast -a com.pixelden.launcher.RUN --es name "Имя сценария" -n com.pixelden.launcher/.scenario.DebugReceiver
 *   am broadcast -a com.pixelden.launcher.KEYLOG --ez on true -n com.pixelden.launcher/.scenario.DebugReceiver
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = PixelDenApp.instance
        when (intent.action) {
            "com.pixelden.launcher.DUMP" -> {
                val f = DebugLog.dumpToFile(context, app.store.current)
                DebugLog.i("dump → ${f.absolutePath}")
                resultData = f.absolutePath
            }
            "com.pixelden.launcher.RUN" -> {
                val name = intent.getStringExtra("name").orEmpty()
                val s = app.store.current.scenarios.firstOrNull { it.name.equals(name, ignoreCase = true) || it.id == name }
                if (s == null) { DebugLog.e("RUN: сценарий «$name» не найден"); resultData = "not found" }
                else { DebugLog.i("RUN по команде adb: ${s.name}"); app.engine.run(s, "adb"); resultData = "started" }
            }
            "com.pixelden.launcher.KEYLOG" -> {
                DebugLog.logKeys = intent.getBooleanExtra("on", true)
                DebugLog.i("журнал кнопок: ${if (DebugLog.logKeys) "вкл" else "выкл"}")
            }
        }
    }
}

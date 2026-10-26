package com.pixelden.launcher.scenario

import com.pixelden.launcher.i18n.tr
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.Action
import com.pixelden.launcher.data.ConfigStore
import com.pixelden.launcher.data.PressType
import com.pixelden.launcher.data.Scenario
import com.pixelden.launcher.data.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScenarioEngine(
    private val context: Context,
    private val store: ConfigStore,
    private val scope: CoroutineScope,
) {
    private val running = mutableMapOf<String, Job>()
    private var bootFired = false

    /** Последнее событие для маскота: "Готово: …" или текст ошибки. */
    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events

    fun scenario(id: String) = store.current.scenarios.firstOrNull { it.id == id }

    fun keyBindings(keyCode: Int): Map<PressType, Scenario> =
        store.current.scenarios
            .filter { it.enabled && (it.trigger as? Trigger.Key)?.keyCode == keyCode }
            .associateBy { (it.trigger as Trigger.Key).press }

    fun onBoot(source: String) {
        if (bootFired) { DebugLog.i("загрузка ($source): уже обработана"); return }
        bootFired = true
        val boot = store.current.scenarios.filter { it.trigger is Trigger.Boot }
        DebugLog.i("загрузка ($source): сценариев с триггером загрузки ${boot.size}, включено ${boot.count { it.enabled }}")
        for (s in boot) {
            if (!s.enabled) continue
            val t = s.trigger as Trigger.Boot
            scope.launch {
                delay(t.delaySec * 1000L)
                run(s, tr("загрузка"))
            }
        }
    }

    /**
     * Экран включился (ACTION_SCREEN_ON). Сразу после загрузки он тоже включается — это уже обработал [onBoot],
     * поэтому первые полторы минуты после старта системы пропускаем.
     */
    fun onWake() {
        if (android.os.SystemClock.elapsedRealtime() < 90_000) return
        val wake = store.current.scenarios.filter { it.enabled && it.trigger is Trigger.Wake }
        DebugLog.i("выход из ожидания: сценариев ${wake.size}")
        for (s in wake) scope.launch {
            delay((s.trigger as Trigger.Wake).delaySec * 1000L)
            run(s, tr("выход из ожидания"))
        }
    }

    fun run(s: Scenario, source: String = tr("вручную")) {
        // Запускают и из главного потока, и из веб-панели — таблицу работающих трогаем только в главном.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(kotlinx.coroutines.Dispatchers.Main) { run(s, source) }
            return
        }
        running[s.id]?.cancel()
        val job = scope.launch {
            DebugLog.i("▶ ${s.name} [$source], шагов: ${s.actions.size}")
            try {
                s.actions.forEachIndexed { i, a ->
                    DebugLog.i("  ${i + 1}. ${a.javaClass.simpleName}")
                    execute(a)
                }
                DebugLog.i("✓ ${s.name}")
                _events.value = tr("Готово: {0}", (s.name))
            } catch (e: kotlinx.coroutines.CancellationException) {
                DebugLog.i("✗ ${s.name}: перезапущен")
                throw e
            } catch (e: Exception) {
                DebugLog.e("✗ ${s.name}", e)
                _events.value = tr("Не вышло: {0} ({1})", (s.name), (e.message ?: e.javaClass.simpleName))
            }
        }
        running[s.id] = job
        // Завершённые не копим (scope на главном потоке — снимаем там же).
        job.invokeOnCompletion { scope.launch(kotlinx.coroutines.Dispatchers.Main) { if (running[s.id] === job) running.remove(s.id) } }
    }

    fun clearEvent() { _events.value = null }

    /** Реплика маскоту от других частей лаунчера (таймер сна, веб-панель). */
    fun notify(text: String) { _events.value = text }

    suspend fun sleepNow() {
        service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    /**
     * Запуск окна. Android 10 не даёт приложениям открывать окна из фона, поэтому запускаем через
     * контекст службы спецвозможностей (система её привязала — ей можно) и только потом — через своё приложение.
     */
    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val svc = DenAccessibilityService.instance
        if (svc != null) svc.startActivity(intent) else context.startActivity(intent)
    }

    private suspend fun execute(a: Action) {
        val apps = PixelDenApp.instance.apps
        when (a) {
            is Action.LaunchApp -> {
                val i = apps.launchIntent(a.pkg) ?: error(tr("приложение {0} не найдено", (a.pkg)))
                start(i)
            }
            is Action.OpenInput -> start(apps.inputIntent(a.inputId))
            is Action.Volume -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * a.percent / 100).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            }
            is Action.Mute -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (a.on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            is Action.Brightness -> {
                // У Xiaomi подсветку панели держит global/picture_backlight (0..100): ТВ сам применяет новое значение.
                // Стандартная screen_brightness на этом телевизоре ничего не меняет.
                val v = a.percent.coerceIn(0, 100)
                val ok = runCatching { Settings.Global.putInt(context.contentResolver, "picture_backlight", v) }.getOrDefault(false)
                if (!ok) {
                    if (!Settings.System.canWrite(context)) error(tr("нет прав на запись настроек"))
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v * 255 / 100)
                }
                DebugLog.i("    подсветка → $v")
            }
            Action.Sleep -> service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            Action.GoHome -> service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Action.Back -> service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            is Action.Wait -> delay(a.seconds * 1000L)
            is Action.OpenSettings -> start(Intent(a.intentAction))
            is Action.ClickText -> clickText(a.text, a.timeoutSec)
            is Action.PutSetting -> putSetting(a)
            is Action.Toast -> Toast.makeText(context, a.text, Toast.LENGTH_SHORT).show()
            is Action.SleepTimer -> com.pixelden.launcher.system.SleepTimer.set(a.minutes)
        }
    }

    /** Ждём, пока поднимется служба спецвозможностей (после загрузки ей нужно несколько секунд). */
    private suspend fun service(timeoutMs: Long = 30_000): DenAccessibilityService {
        var waited = 0L
        while (waited < timeoutMs) {
            DenAccessibilityService.instance?.let { return it }
            delay(250); waited += 250
        }
        error(tr("служба специальных возможностей PixelTv выключена"))
    }

    private suspend fun clickText(text: String, timeoutSec: Int) {
        val svc = service()
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val root = svc.rootInActiveWindow
            val node = root?.let { findByText(it, text) }
            if (node != null) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                val ok = (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) { delay(600); return }
            }
            delay(300)
        }
        error(tr("не нашёл «{0}» на экране", text))
    }

    /** Сначала точное совпадение текста, потом — подстрока. */
    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(text) ?: return null
        return candidates.firstOrNull { it.text?.toString()?.trim().equals(text.trim(), ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun putSetting(a: Action.PutSetting) {
        val cr = context.contentResolver
        val ok = when (a.namespace) {
            "system" -> Settings.System.putString(cr, a.key, a.value)
            "secure" -> Settings.Secure.putString(cr, a.key, a.value)
            else -> Settings.Global.putString(cr, a.key, a.value)
        }
        if (!ok) error(tr("настройка {0}/{1} не записалась", (a.namespace), (a.key)))
    }

    companion object {
        const val TAG = "PixelTv"
    }
}

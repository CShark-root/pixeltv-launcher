package com.pixelden.launcher.scenario

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

    fun onBoot() {
        if (bootFired) return
        bootFired = true
        for (s in store.current.scenarios) {
            val t = s.trigger as? Trigger.Boot ?: continue
            if (!s.enabled) continue
            scope.launch {
                delay(t.delaySec * 1000L)
                run(s)
            }
        }
    }

    fun run(s: Scenario) {
        running[s.id]?.cancel()
        running[s.id] = scope.launch {
            Log.i(TAG, "run ${s.name}")
            try {
                for (a in s.actions) execute(a)
                _events.value = "Готово: ${s.name}"
            } catch (e: Exception) {
                Log.w(TAG, "scenario ${s.name} failed", e)
                _events.value = "Не вышло: ${s.name} (${e.message ?: e.javaClass.simpleName})"
            }
        }
    }

    fun clearEvent() { _events.value = null }

    private suspend fun execute(a: Action) {
        val apps = PixelDenApp.instance.apps
        when (a) {
            is Action.LaunchApp -> {
                val i = apps.launchIntent(a.pkg) ?: error("приложение ${a.pkg} не найдено")
                context.startActivity(i)
            }
            is Action.OpenInput -> context.startActivity(apps.inputIntent(a.inputId))
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
                if (!Settings.System.canWrite(context)) error("нет права WRITE_SETTINGS")
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (a.percent * 255 / 100).coerceIn(0, 255))
            }
            Action.Sleep -> service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            Action.GoHome -> service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Action.Back -> service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            is Action.Wait -> delay(a.seconds * 1000L)
            is Action.OpenSettings -> context.startActivity(Intent(a.intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            is Action.ClickText -> clickText(a.text, a.timeoutSec)
            is Action.PutSetting -> putSetting(a)
            is Action.Toast -> Toast.makeText(context, a.text, Toast.LENGTH_SHORT).show()
        }
    }

    /** Ждём, пока поднимется служба спецвозможностей (после загрузки ей нужно несколько секунд). */
    private suspend fun service(timeoutMs: Long = 30_000): DenAccessibilityService {
        var waited = 0L
        while (waited < timeoutMs) {
            DenAccessibilityService.instance?.let { return it }
            delay(250); waited += 250
        }
        error("служба специальных возможностей PixelDen выключена")
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
        error("не нашёл «$text» на экране")
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
        if (!ok) error("настройка ${a.namespace}/${a.key} не записалась")
    }

    companion object {
        const val TAG = "PixelDen"
    }
}

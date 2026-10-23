package com.pixelden.launcher.scenario

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.PressType

/**
 * Ловит кнопки пульта для сценариев и нажимает пункты меню по ClickText.
 *
 * Как работают типы нажатий (вернуть «съеденную» кнопку системе нельзя, поэтому так):
 *  - SHORT  — кнопка полностью перехватывается, её обычное действие пропадает;
 *  - LONG   — если на кнопке нет SHORT, обычное короткое нажатие работает как раньше,
 *             а при удержании срабатывает сценарий;
 *  - DOUBLE — первое нажатие работает как обычно, второе в течение 400 мс запускает сценарий.
 */
class DenAccessibilityService : AccessibilityService() {

    private var downAt = 0L
    private var longFired = false
    private var swallowUntilUp = false
    private var lastUpKey = 0
    private var lastUpAt = 0L

    override fun onServiceConnected() {
        instance = this
        DebugLog.i("служба кнопок подключена (flags=${serviceInfo?.flags})")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** Вписать текст в поле ввода, выбранное на экране (текст набирают на телефоне). */
    fun typeText(text: String, append: Boolean): Boolean {
        val node = rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false
        val value = if (append) node.text?.toString().orEmpty() + text else text
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        DebugLog.i("текст с телефона (${text.length} симв.) → ${node.packageName}: ${if (ok) "вписан" else "не принят"}")
        return ok
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        KeyCapture.consume(event)?.let { return it }

        val app = PixelDenApp.instance
        val code = keyId(event)
        val bindings = app.engine.keyBindings(code)
        if (DebugLog.logKeys && event.repeatCount == 0) {
            DebugLog.i(
                "кнопка ${KeyEvent.keyCodeToString(event.keyCode)} (${code}" + (if (code >= SCAN_BASE) ", скан-код ${event.scanCode}" else "") + ") " +
                    (if (event.action == KeyEvent.ACTION_DOWN) "↓" else "↑") +
                    if (bindings.isEmpty()) "" else " → привязано: " + bindings.map { "${it.key}: ${it.value.name}" }.joinToString()
            )
        }
        if (bindings.isEmpty()) return false
        val short = bindings[PressType.SHORT]
        val long = bindings[PressType.LONG]
        val double = bindings[PressType.DOUBLE]
        val now = SystemClock.uptimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downAt = now
                    longFired = false
                    if (double != null && lastUpKey == code && now - lastUpAt < 400) {
                        swallowUntilUp = true
                        app.engine.run(double, "кнопка ×2")
                        return true
                    }
                    return short != null
                }
                if (long != null && !longFired && now - downAt >= 600) {
                    longFired = true
                    app.engine.run(long, "кнопка удержание")
                }
                return short != null || longFired || swallowUntilUp
            }
            KeyEvent.ACTION_UP -> {
                lastUpKey = code
                lastUpAt = now
                if (swallowUntilUp) { swallowUntilUp = false; return true }
                if (longFired) return true
                if (short != null) {
                    // Пульт без автоповтора: длинное определяем по длительности при отпускании.
                    if (long != null && now - downAt >= 600) app.engine.run(long, "кнопка удержание") else app.engine.run(short, "кнопка")
                    return true
                }
                // Только «удержание»: пульт Xiaomi не шлёт автоповтор, поэтому считаем по длительности здесь.
                if (long != null && now - downAt >= 600) {
                    app.engine.run(long, "кнопка удержание")
                    return true
                }
                return false
            }
        }
        return false
    }

    companion object {
        @Volatile
        var instance: DenAccessibilityService? = null
    }
}

/** Режим «нажмите кнопку на пульте» в редакторе сценариев. */
object KeyCapture {
    @Volatile
    private var callback: ((Int) -> Unit)? = null

    val active get() = callback != null

    fun start(cb: (Int) -> Unit) { callback = cb }
    fun stop() { callback = null }

    /** null — не перехватываем; true — событие съедено. */
    fun consume(e: KeyEvent): Boolean? {
        val cb = callback ?: return null
        if (e.action == KeyEvent.ACTION_UP) {
            callback = null
            cb(keyId(e))
        }
        return true
    }
}

/** Кнопки без системного кода (KEYCODE_UNKNOWN) различаем по скан-коду: такие есть у пульта Mi TV 4S. */
const val SCAN_BASE = 100_000

fun keyId(e: KeyEvent): Int =
    if (e.keyCode == KeyEvent.KEYCODE_UNKNOWN && e.scanCode > 0) SCAN_BASE + e.scanCode else e.keyCode

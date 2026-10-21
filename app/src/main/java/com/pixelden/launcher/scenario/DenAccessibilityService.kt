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
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
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
        val bindings = app.engine.keyBindings(event.keyCode)
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
                    if (double != null && lastUpKey == event.keyCode && now - lastUpAt < 400) {
                        swallowUntilUp = true
                        app.engine.run(double)
                        return true
                    }
                    return short != null
                }
                if (long != null && !longFired && now - downAt >= 600) {
                    longFired = true
                    app.engine.run(long)
                }
                return short != null || longFired || swallowUntilUp
            }
            KeyEvent.ACTION_UP -> {
                lastUpKey = event.keyCode
                lastUpAt = now
                if (swallowUntilUp) { swallowUntilUp = false; return true }
                if (longFired) return true
                if (short != null) {
                    // Пульт без автоповтора: длинное определяем по длительности при отпускании.
                    if (long != null && now - downAt >= 600) app.engine.run(long) else app.engine.run(short)
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
            cb(e.keyCode)
        }
        return true
    }
}

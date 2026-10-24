package com.pixelden.launcher.system

import com.pixelden.launcher.i18n.tr
import android.widget.Toast
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.scenario.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Таймер сна «выключить через час»: за минуту предупреждает, потом усыпляет ТВ. */
object SleepTimer {
    /** Шаги, которые перебирает плитка таймера; 0 — выкл. */
    val STEPS = listOf(0, 15, 30, 45, 60, 90, 120, 180)

    private val _endsAt = MutableStateFlow<Long?>(null)
    /** Время срабатывания (System.currentTimeMillis) или null, если таймер выключен. */
    val endsAt: StateFlow<Long?> = _endsAt
    private var job: Job? = null

    fun set(minutes: Int) {
        val app = PixelDenApp.instance
        job?.cancel()
        if (minutes <= 0) {
            if (_endsAt.value != null) DebugLog.i("таймер сна отменён")
            _endsAt.value = null
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _endsAt.value = end
        DebugLog.i("таймер сна: через $minutes мин")
        job = app.scope.launch {
            val warn = end - 60_000L - System.currentTimeMillis()
            if (warn > 0) {
                delay(warn)
                app.engine.notify(tr("Через минуту засыпаю… Таймер сна."))
                Toast.makeText(app, tr("PixelTv: ТВ уснёт через минуту"), Toast.LENGTH_LONG).show()
            }
            delay((end - System.currentTimeMillis()).coerceAtLeast(0))
            _endsAt.value = null
            DebugLog.i("таймер сна сработал")
            runCatching { app.engine.sleepNow() }.onFailure { DebugLog.e("таймер сна: не удалось усыпить ТВ", it) }
        }
    }

    /** Минут до сна (с округлением вверх) или null. */
    fun minutesLeft(now: Long = System.currentTimeMillis()): Int? =
        _endsAt.value?.let { ((it - now + 59_999) / 60_000).toInt().coerceAtLeast(0) }

    /** Следующий шаг для плитки: от оставшегося времени к ближайшему большему, после максимума — выкл. */
    fun nextStep(): Int {
        val left = minutesLeft() ?: 0
        return STEPS.firstOrNull { it > left } ?: 0
    }
}

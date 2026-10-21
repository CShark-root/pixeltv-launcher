package com.pixelden.launcher.scenario

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.data.Trigger
import java.util.Calendar

object Scheduler {

    /** Перепланировать все сценарии по времени. Вызывается при старте, загрузке и любом изменении. */
    fun scheduleAll(context: Context, cfg: LauncherConfig) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (s in cfg.scenarios) {
            val pi = pending(context, s.id)
            am.cancel(pi)
            val t = s.trigger as? Trigger.Time ?: continue
            if (!s.enabled || t.days.isEmpty()) continue
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime(t), pi)
        }
    }

    private fun pending(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, AlarmReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Ближайший момент hh:mm в один из выбранных дней (1 = пн … 7 = вс). */
    fun nextTime(t: Trigger.Time, from: Calendar = Calendar.getInstance()): Long {
        val c = (from.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, t.hour)
            set(Calendar.MINUTE, t.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (c.after(from) && isoDay(c) in t.days) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun isoDay(c: Calendar): Int = when (c.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 7
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = PixelDenApp.instance
        intent.getStringExtra("id")?.let { id -> app.engine.scenario(id)?.let { app.engine.run(it) } }
        Scheduler.scheduleAll(context, app.store.current)
    }
}

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = PixelDenApp.instance
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) app.engine.onBoot()
        Scheduler.scheduleAll(context, app.store.current)
    }
}

package com.pixelden.launcher.system

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.pixelden.launcher.scenario.DebugLog
import java.io.File
import kotlinx.coroutines.launch

data class HealthSnapshot(
    val ramTotal: Long,
    val ramAvail: Long,
    val lowMemory: Boolean,
    /** Сколько занимает сам лаунчер (PSS), байт. */
    val launcherPss: Long,
    val storageTotal: Long,
    val storageFree: Long,
    /** Самая горячая термозона, °C, если система даёт её прочитать. */
    val tempC: Float?,
    val cpuCores: Int,
    /** Текущие частоты ядер, МГц (пусто, если недоступны). */
    val cpuMhz: List<Int>,
    val uptimeMs: Long,
)

data class RecentApp(val pkg: String, val lastUsed: Long, val foregroundMs: Long, val label: String)

/** «Здоровье ТВ»: память, место, температура, недавние приложения и очистка фона. */
object Health {

    fun snapshot(context: Context): HealthSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = runCatching { am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss * 1024L }.getOrDefault(0L)
        val fs = StatFs(Environment.getDataDirectory().path)
        val cores = Runtime.getRuntime().availableProcessors()
        return HealthSnapshot(
            ramTotal = mi.totalMem,
            ramAvail = mi.availMem,
            lowMemory = mi.lowMemory,
            launcherPss = pss,
            storageTotal = fs.totalBytes,
            storageFree = fs.availableBytes,
            tempC = readTemp(),
            cpuCores = cores,
            cpuMhz = (0 until cores).mapNotNull { readInt("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.div(1000) },
            uptimeMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun readInt(path: String): Int? = runCatching { File(path).readText().trim().toInt() }.getOrNull()

    private fun readTemp(): Float? {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        // Датчики отдают миллиградусы (или градусы на старых ядрах); отбрасываем явный мусор.
        return zones.mapNotNull { z -> readInt("${z.path}/temp")?.let { if (it > 1000) it / 1000f else it.toFloat() } }
            .filter { it in 1f..150f }
            .maxOrNull()
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Приложения, которые открывали за последние [hours] часов, свежие первыми. Нужен доступ к статистике. */
    fun recentApps(context: Context, hours: Int = 6): List<RecentApp> {
        if (!hasUsageAccess(context)) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return usm.queryAndAggregateUsageStats(now - hours * 3_600_000L, now).values
            .filter { it.lastTimeUsed > now - hours * 3_600_000L && it.packageName != context.packageName && it.totalTimeInForeground > 0 }
            .map { RecentApp(it.packageName, it.lastTimeUsed, it.totalTimeInForeground, label(context, it.packageName)) }
            .sortedByDescending { it.lastUsed }
    }

    /**
     * Закрывает фоновые процессы приложений [pkgs]. Система сама не даст убить то, что на экране
     * или нужно ей самой. Возвращает, сколько памяти освободилось.
     */
    fun freeMemory(context: Context, pkgs: Collection<String>): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        pkgs.filter { it != context.packageName }.forEach { runCatching { am.killBackgroundProcesses(it) } }
        // Система освобождает память не мгновенно.
        Thread.sleep(800)
        val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val freed = (after - before).coerceAtLeast(0)
        DebugLog.i("очистка фона: ${pkgs.size} пакетов, освобождено ${freed / 1_048_576} МБ")
        return freed
    }
}

fun mb(bytes: Long) = bytes / 1_048_576

fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)

/** Название приложения из системы (для служебных пакетов без значка на главном экране). */
fun label(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/**
 * Сторож памяти: раз в минуту проверяет свободную память ТВ и при нехватке закрывает фоновые приложения
 * (не чаще раза в 10 минут). Открытое на экране система закрыть не даст.
 */
object MemoryGuard {
    private var job: kotlinx.coroutines.Job? = null
    private var lastRun = 0L

    fun apply(enabled: Boolean) {
        val app = com.pixelden.launcher.PixelDenApp.instance
        if (!enabled) { job?.cancel(); job = null; return }
        if (job?.isActive == true) return
        job = app.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { check(app) }
            }
        }
    }

    private fun check(app: com.pixelden.launcher.PixelDenApp) {
        val s = Health.snapshot(app)
        // Сам лаунчер раздулся — сбрасываем свой кэш картинок.
        if (s.launcherPss > 150L * 1_048_576) {
            app.apps.trimImages()
            DebugLog.i("сторож памяти: PixelTv занял ${mb(s.launcherPss)} МБ — кэш иконок сброшен")
        }
        val freePct = s.ramAvail * 100 / s.ramTotal
        if ((freePct >= 15 && !s.lowMemory) || System.currentTimeMillis() - lastRun < 10 * 60_000L) return
        lastRun = System.currentTimeMillis()
        val pkgs = app.apps.apps.value.map { it.pkg } + Health.recentApps(app).map { it.pkg }
        val freed = Health.freeMemory(app, pkgs.toSet())
        DebugLog.i("сторож памяти: свободно было $freePct%, закрыл фон — освобождено ${mb(freed)} МБ")
        if (freed > 30L * 1_048_576) app.engine.notify("Прибрался в памяти: +${mb(freed)} МБ")
    }
}

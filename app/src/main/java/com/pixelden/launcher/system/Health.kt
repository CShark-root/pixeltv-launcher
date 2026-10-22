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

package com.pixelden.launcher.system

import com.pixelden.launcher.i18n.tr
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
        val pss = runCatching { SelfClean.pss() }.getOrDefault(0L)
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
        fun avail() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = avail()
        pkgs.filter { it != context.packageName }.forEach { runCatching { am.killBackgroundProcesses(it) } }
        // Система освобождает память не мгновенно: ждём до 2 секунд и берём лучший замер.
        var best = before
        repeat(4) { Thread.sleep(500); best = maxOf(best, avail()) }
        val freed = best - before
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
 * Сторож памяти: каждые 30 секунд смотрит свободную память ТВ. Меньше [LIMIT] — убирается:
 *  1. закрывает фоновые приложения стандартно (что система разрешает) и убирается в самом лаунчере;
 *  2. если памяти всё ещё мало — через adb останавливает сторонние приложения, которые висят в фоне.
 * Не чаще раза в 5 минут.
 */
object MemoryGuard {
    const val LIMIT = 350L * 1_048_576
    private var job: kotlinx.coroutines.Job? = null
    private var lastRun = 0L

    fun apply(enabled: Boolean) {
        val app = com.pixelden.launcher.PixelDenApp.instance
        if (!enabled) { job?.cancel(); job = null; return }
        if (job?.isActive == true) return
        job = app.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { check(app) }.onFailure { DebugLog.e("сторож памяти", it) } }
            }
        }
    }

    private fun avail(app: Context) = ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.availMem

    private fun check(app: com.pixelden.launcher.PixelDenApp) {
        val start = avail(app)
        if (start >= LIMIT || System.currentTimeMillis() - lastRun < 5 * 60_000L) return
        lastRun = System.currentTimeMillis()
        val pkgs = app.apps.apps.value.map { it.pkg } + Health.recentApps(app).map { it.pkg }
        Health.freeMemory(app, pkgs.toSet())
        SelfClean.run()
        var stopped = emptyList<String>()
        if (avail(app) < LIMIT) {
            stopped = runCatching { forceStopBackground(app) }
                .onFailure { DebugLog.i("сторож памяти: adb недоступен — ${it.message}") }.getOrDefault(emptyList())
            if (stopped.isNotEmpty()) Thread.sleep(1500)
        }
        val freed = (avail(app) - start).coerceAtLeast(0)
        DebugLog.i("сторож памяти: было свободно ${mb(start)} МБ, стало ${mb(start + freed)} МБ" +
            if (stopped.isEmpty()) "" else "; остановлены: " + stopped.joinToString())
        if (freed > 30L * 1_048_576) app.engine.notify(tr("Прибрался в памяти: +{0} МБ", (mb(freed))))
    }

    /**
     * Остановить через adb сторонние приложения, которые висят в фоне. Не трогает: открытое на экране,
     * «заметное» (служба на переднем плане — например, музыка), клавиатуру, спецвозможности и сам лаунчер.
     * Возвращает остановленные пакеты.
     */
    private fun forceStopBackground(context: Context): List<String> {
        val pm = context.packageManager
        val cr = context.contentResolver
        val keep = buildSet {
            add(context.packageName)
            android.provider.Settings.Secure.getString(cr, "default_input_method")?.let { add(it.substringBefore('/')) }
            android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services")?.split(':')?.forEach { add(it.substringBefore('/')) }
        }
        val candidates = pm.getInstalledApplications(0)
            .filter { it.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 }
            .map { it.packageName }
            .filter { it !in keep && TvCleanup.isPackageName(it) }
        if (candidates.isEmpty()) return emptyList()
        // Состояние процессов из «dumpsys activity lru»: fore/fg — на экране, vis — виден, prcp — заметен
        // (музыка, служба на переднем плане), pers/psvc — системный, bkup — резервное копирование.
        val script = "lru=\$(dumpsys activity lru); for p in ${candidates.joinToString(" ")}; do " +
            "re=\":\$(echo \$p | sed 's/\\./\\\\./g')(:[^/ ]*)?/\"; " +
            "echo \"\$lru\" | grep -qE \"\$re\" || continue; " +
            "echo \"\$lru\" | grep -E \"\$re\" | grep -qE '#[0-9]+: (fore|fg|top|vis|prcp|pers|psvc|bkup)' && continue; " +
            "am force-stop \$p; echo \$p; done"
        return AdbClient(context).shell(script, ask = false).lines().map { it.trim() }.filter { it in candidates }
    }
}

package com.pixelden.launcher

import android.app.Application
import com.pixelden.launcher.apps.AppRepository
import com.pixelden.launcher.data.ConfigStore
import com.pixelden.launcher.data.Defaults
import com.pixelden.launcher.scenario.ScenarioEngine
import com.pixelden.launcher.scenario.Scheduler
import com.pixelden.launcher.scenario.Templates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PixelDenApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: ConfigStore
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var engine: ScenarioEngine
        private set
    lateinit var weather: com.pixelden.launcher.weather.WeatherRepository
        private set
    lateinit var news: com.pixelden.launcher.weather.NewsRepository
        private set
    lateinit var rates: com.pixelden.launcher.weather.RatesRepository
        private set
    lateinit var web: com.pixelden.launcher.web.WebServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ConfigStore(this)
        apps = AppRepository(this, scope)
        engine = ScenarioEngine(this, store, scope)
        weather = com.pixelden.launcher.weather.WeatherRepository(store, scope).also { it.start() }
        news = com.pixelden.launcher.weather.NewsRepository(scope).also { if (store.current.talkNews) it.start() }

        // При первом запуске кладём шаблоны выключенными, чтобы было с чего начать.
        if (!store.current.templatesOffered) {
            store.update { it.copy(scenarios = it.scenarios + Templates.initial(), templatesOffered = true) }
        }
        store.update(Defaults::apply)
        Scheduler.scheduleAll(this, store.current)

        rates = com.pixelden.launcher.weather.RatesRepository(scope).also {
            if (store.current.showWidgets && "rates" in store.current.widgets) it.start()
        }
        web = com.pixelden.launcher.web.WebServer(this).also { if (store.current.webPanel) it.start() }
        com.pixelden.launcher.system.MemoryGuard.apply(store.current.memoryGuard)

        // Контроль памяти: раз в полчаса — в журнал. Рост от записи к записи сразу покажет утечку.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30 * 60_000L)
                val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                val stats = mi.memoryStats
                com.pixelden.launcher.scenario.DebugLog.i(
                    "память: всего ${mi.totalPss / 1024} МБ · картинки ${(stats["summary.native-heap"]?.toIntOrNull() ?: 0) / 1024} МБ" +
                        " · видеопамять ${(stats["summary.graphics"]?.toIntOrNull() ?: 0) / 1024} МБ · Java ${(stats["summary.java-heap"]?.toIntOrNull() ?: 0) / 1024} МБ",
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Системе не хватает памяти — отдаём кэш иконок, он подгрузится заново.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            apps.trimImages()
            com.pixelden.launcher.scenario.DebugLog.i("мало памяти (уровень $level): кэш иконок очищен")
        }
    }

    companion object {
        lateinit var instance: PixelDenApp
            private set
    }
}

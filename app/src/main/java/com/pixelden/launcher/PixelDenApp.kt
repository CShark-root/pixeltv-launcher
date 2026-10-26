package com.pixelden.launcher

import com.pixelden.launcher.i18n.tr
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
    lateinit var watchNext: com.pixelden.launcher.apps.WatchNextRepository
        private set
    lateinit var web: com.pixelden.launcher.web.WebServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ConfigStore(this)
        // Язык — до всего остального: списки разделов и подписи создаются с первым обращением.
        com.pixelden.launcher.i18n.I18n.init(this, store.current.language)
        apps = AppRepository(this, scope)
        engine = ScenarioEngine(this, store, scope)
        watchNext = com.pixelden.launcher.apps.WatchNextRepository(this, scope).also { if (store.current.showWatchNext) it.start() }
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
        com.pixelden.launcher.system.NetStatus.start(this)
        // Доступ к уведомлениям: если его ещё нет, пробуем включить тихо (без вопроса на экране).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (!nm.isNotificationListenerAccessGranted(com.pixelden.launcher.system.Notices.component(this@PixelDenApp))) runCatching {
                com.pixelden.launcher.system.AdbClient(this@PixelDenApp).shell("cmd notification allow_listener $packageName/$packageName.system.NoticeListener", ask = false)
            }
        }
        // Выход из ожидания: «экран включён» приходит только приёмнику, зарегистрированному из кода.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) = engine.onWake()
        }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON))
        com.pixelden.launcher.system.Updater.apply(store.current.autoUpdate)

        // Контроль памяти: раз в полчаса — в журнал. Рост от записи к записи сразу покажет утечку.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30 * 60_000L)
                val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                val stats = mi.memoryStats
                com.pixelden.launcher.scenario.DebugLog.i(
                    tr("память: всего {0} МБ · картинки {1} МБ", (mi.totalPss / 1024), ((stats["summary.native-heap"]?.toIntOrNull() ?: 0) / 1024)) +
                        tr(" · видеопамять {0} МБ · Java {1} МБ", ((stats["summary.graphics"]?.toIntOrNull() ?: 0) / 1024), ((stats["summary.java-heap"]?.toIntOrNull() ?: 0) / 1024)),
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

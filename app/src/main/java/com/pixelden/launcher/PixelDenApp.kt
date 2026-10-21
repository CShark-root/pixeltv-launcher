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

class PixelDenApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: ConfigStore
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var engine: ScenarioEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ConfigStore(this)
        apps = AppRepository(this, scope)
        engine = ScenarioEngine(this, store, scope)

        // При первом запуске кладём шаблоны выключенными, чтобы было с чего начать.
        if (!store.current.templatesOffered) {
            store.update { it.copy(scenarios = it.scenarios + Templates.initial(), templatesOffered = true) }
        }
        store.update(Defaults::apply)
        Scheduler.scheduleAll(this, store.current)
    }

    companion object {
        lateinit var instance: PixelDenApp
            private set
    }
}

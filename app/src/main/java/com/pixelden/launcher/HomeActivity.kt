package com.pixelden.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pixelden.launcher.scenario.Scheduler
import com.pixelden.launcher.ui.DenThemeProvider
import com.pixelden.launcher.ui.HomeScreen
import com.pixelden.launcher.ui.ScenarioEditorScreen
import com.pixelden.launcher.ui.ScenarioListScreen
import com.pixelden.launcher.ui.SettingsScreen
import com.pixelden.launcher.ui.DebugLogScreen
import com.pixelden.launcher.ui.themeFrom
import kotlinx.coroutines.delay

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Scenarios : Screen
    data object Log : Screen
    data class Editor(val id: String) : Screen
}

class HomeActivity : ComponentActivity() {

    private var lastInput by mutableLongStateOf(SystemClock.uptimeMillis())
    private var homeTick by mutableIntStateOf(0)
    private var screen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = PixelDenApp.instance
        // Лаунчер стартует первым после загрузки — дублируем boot-триггер на случай,
        // если BOOT_COMPLETED придёт раньше, чем процесс успеет подписаться.
        if (SystemClock.elapsedRealtime() < 5 * 60_000) app.engine.onBoot("старт лаунчера")

        // Без этого права лаунчер не видит картинки для обоев и не сохраняет резервную копию.
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        setContent {
            val cfg by app.store.config.collectAsState()
            val theme = remember(cfg) { themeFrom(cfg) }

            // Перепланируем будильники при любом изменении сценариев.
            LaunchedEffect(cfg.scenarios) { Scheduler.scheduleAll(this@HomeActivity, cfg) }

            var idle by remember { mutableStateOf(false) }
            LaunchedEffect(cfg.mascotSleepMinutes) {
                while (true) {
                    idle = SystemClock.uptimeMillis() - lastInput > cfg.mascotSleepMinutes * 60_000L
                    delay(5_000)
                }
            }
            LaunchedEffect(lastInput) { if (idle) idle = false }

            DenThemeProvider(theme) {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        cfg, idle, homeTick,
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenScenarios = { screen = Screen.Scenarios },
                    )
                    Screen.Settings -> SettingsScreen(cfg, onOpenLog = { screen = Screen.Log })
                    Screen.Log -> DebugLogScreen()
                    Screen.Scenarios -> ScenarioListScreen(cfg) { id -> screen = Screen.Editor(id) }
                    is Screen.Editor -> ScenarioEditorScreen(cfg, s.id) { screen = Screen.Scenarios }
                }
                BackHandler(enabled = screen != Screen.Home) {
                    screen = when (screen) {
                        is Screen.Editor -> Screen.Scenarios
                        Screen.Log -> Screen.Settings
                        else -> Screen.Home
                    }
                }
                // На главном экране BACK ничего не делает (а не закрывает лаунчер).
                BackHandler(enabled = screen == Screen.Home) {}
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        // Кнопка MENU на главном экране — сразу в настройки.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && screen == Screen.Home) {
            screen = Screen.Settings
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Повторное нажатие HOME, когда лаунчер уже на экране: вернуться на главный и к началу.
        if (Intent.ACTION_MAIN == intent.action) {
            screen = Screen.Home
            homeTick++
        }
    }

    override fun onResume() {
        super.onResume()
        lastInput = SystemClock.uptimeMillis()
        PixelDenApp.instance.apps.refresh()
    }
}

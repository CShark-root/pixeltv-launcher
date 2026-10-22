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
import com.pixelden.launcher.ui.softTransition
import com.pixelden.launcher.ui.PixelCoverOverlay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent

private sealed interface Screen {
    data object Home : Screen
    data class Settings(val page: String? = null) : Screen
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
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = { softTransition(if (theme.animations) cfg.screenTransition else "none") },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.Home -> HomeScreen(
                                cfg, idle, homeTick,
                                onOpenSettings = { go(Screen.Settings()) },
                                onOpenScenarios = { go(Screen.Scenarios) },
                            )
                            is Screen.Settings -> SettingsScreen(
                                cfg, s.page,
                                onOpenPage = { go(Screen.Settings(it)) },
                                onOpenLog = { go(Screen.Log) },
                            )
                            Screen.Log -> DebugLogScreen()
                            Screen.Scenarios -> ScenarioListScreen(cfg) { id -> go(Screen.Editor(id)) }
                            is Screen.Editor -> ScenarioEditorScreen(cfg, s.id) { go(Screen.Scenarios) }
                        }
                    }
                    PixelCoverOverlay(cover.value, coverSeed, theme.colors.bg)
                }
                BackHandler(enabled = screen != Screen.Home) {
                    go(
                        when (screen) {
                            is Screen.Editor -> Screen.Scenarios
                            Screen.Log -> Screen.Settings("debug")
                            is Screen.Settings -> if ((screen as Screen.Settings).page != null) Screen.Settings() else Screen.Home
                            else -> Screen.Home
                        }
                    )
                }
                // На главном экране BACK ничего не делает (а не закрывает лаунчер).
                BackHandler(enabled = screen == Screen.Home) {}
            }
        }
    }

    // ------------------------------------------------------------------ переходы между экранами

    private val cover = Animatable(0f)
    private var coverSeed by mutableIntStateOf(0)
    private var navJob: Job? = null

    /** Сменить экран с выбранным переходом: «пиксели» — шторка из квадратов, «растворение» — через AnimatedContent. */
    private fun go(target: Screen) {
        if (target == screen) return
        val cfg = PixelDenApp.instance.store.current
        if (!cfg.animations || cfg.screenTransition != "pixels") { screen = target; return }
        navJob?.cancel()
        // AndroidUiDispatcher.Main даёт кадровые часы Compose — без них Animatable падает.
        navJob = lifecycleScope.launch(AndroidUiDispatcher.Main) {
            coverSeed++
            cover.animateTo(1f, tween(170, easing = LinearEasing))
            screen = target
            delay(30)
            coverSeed++
            cover.animateTo(0f, tween(210, easing = LinearEasing))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        // Кнопка MENU на главном экране — сразу в настройки.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && screen == Screen.Home) {
            go(Screen.Settings())
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Повторное нажатие HOME, когда лаунчер уже на экране: вернуться на главный и к началу.
        if (Intent.ACTION_MAIN == intent.action) {
            go(Screen.Home)
            homeTick++
        }
    }

    override fun onResume() {
        super.onResume()
        lastInput = SystemClock.uptimeMillis()
        PixelDenApp.instance.apps.refresh()
    }
}

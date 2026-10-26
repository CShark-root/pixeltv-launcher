package com.pixelden.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.i18n.I18n
import com.pixelden.launcher.i18n.tr
import com.pixelden.launcher.scenario.DenAccessibilityService
import com.pixelden.launcher.system.Setup
import com.pixelden.launcher.system.TvCleanup
import com.pixelden.launcher.weather.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Шаги мастера первого запуска. */
private const val STEP_LANGUAGE = 0
private const val STEP_PERMISSIONS = 1
private const val STEP_MASCOT = 2
private const val STEP_WEATHER = 3
private const val STEP_FAVORITES = 4
private const val STEP_CLEANUP = 5
private const val STEP_DONE = 6

/**
 * Мастер первого запуска: язык, права, маскот, город, избранное, чистка. Любой шаг можно пропустить.
 * Шаг хранится в настройках: после выбора языка лаунчер перезапускается и продолжает с того же места.
 */
@Composable
fun OnboardingScreen(cfg: LauncherConfig) {
    val app = PixelDenApp.instance
    val store = app.store
    val context = LocalContext.current
    val t = LocalDen.current
    val scope = rememberCoroutineScope()
    val step = cfg.onboardingStep
    var busy by remember { mutableStateOf(false) }
    var cityEdit by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Place>>(emptyList()) }
    var cityError by remember { mutableStateOf<String?>(null) }

    fun go(next: Int) = store.update { it.copy(onboardingStep = next) }
    fun finish() = store.update { it.copy(onboardingDone = true, onboardingStep = 0) }
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_LONG).show()

    /** Действие через adb ТВ в фоне; по готовности — следующий шаг. */
    fun background(next: Int, block: () -> String) {
        if (busy) return
        busy = true
        toast(tr("Если ТВ спросит «Разрешить отладку?» — отметьте «Всегда» и нажмите OK"))
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching(block).getOrElse {
                    if (it is java.net.ConnectException) tr("Включите «Отладку по сети» в настройках «Для разработчиков»")
                    else tr("Не вышло: {0}", it.message ?: it.javaClass.simpleName)
                }
            }
            busy = false
            toast(msg)
            go(next)
        }
    }

    val titles = listOf(
        "Language / Язык", tr("Права"), tr("Маскот"), tr("Погода"), tr("Избранное"), tr("Чистка ТВ"), tr("Готово!"),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(t.colors.bg)
            .onPreviewKeyEvent { e ->
                // Пока открыто окно ввода города, «Назад» закрывает его.
                if (cityEdit || e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp && step > STEP_LANGUAGE) go(step - 1)
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            // Слева — маскот-проводник.
            Column(Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MascotSprite(mascotById(cfg.mascot), 6, MascotMood.IDLE, t.animations, headroom = 2)
                Spacer(Modifier.height(16.dp))
                PText("${step + 1} / ${titles.size}", 16.sp, t.colors.dim)
            }
            Column(Modifier.weight(1f).padding(start = 48.dp)) {
                PText(titles[step.coerceIn(0, titles.lastIndex)], 40.sp, t.colors.text)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().height(620.dp)) {
                    when (step) {
                        STEP_LANGUAGE -> {
                            item { PText("Выберите язык · Choose your language", 18.sp, t.colors.dim, Modifier.padding(bottom = 16.dp)) }
                            items(listOf("auto" to "Как в системе · System default", "ru" to "Русский", "en" to "English")) { (id, name) ->
                                SettingRow(name, if (cfg.language == id) "•" else null, focusRequester = if (id == "auto") rememberAutoFocus() else null) {
                                    store.update { it.copy(language = id, onboardingStep = STEP_PERMISSIONS) }
                                    // Подписи создаются с запуском процесса — перезапускаемся на новом языке.
                                    if (I18n.resolve(id) != I18n.lang) scope.launch { delay(400); android.os.Process.killProcess(android.os.Process.myPid()) }
                                }
                            }
                        }
                        STEP_PERMISSIONS -> {
                            item {
                                PText(tr("Чтобы работали кнопки пульта, сценарии, подсветка, чистка, уведомления и «Продолжить просмотр», PixelTv нужны права. Он выдаст их себе сам через отладку ТВ — компьютер не нужен."), 17.sp, t.colors.dim, Modifier.padding(bottom = 16.dp), maxLines = 4)
                            }
                            item {
                                val ok = DenAccessibilityService.instance != null
                                SettingRow(if (busy) tr("Выдаю права…") else tr("Выдать все права"), if (ok) tr("готово") else null,
                                    hint = tr("Нужна «Отладка по USB» (и «по сети», если есть) в «Для разработчиков»"),
                                    focusRequester = rememberAutoFocus()) {
                                    background(STEP_MASCOT) { Setup.grantAll(context) }
                                }
                            }
                            item { SettingRow(tr("Пропустить"), hint = tr("Можно сделать позже: Настройки → Права и службы")) { go(STEP_MASCOT) } }
                        }
                        STEP_MASCOT -> {
                            item { PText(tr("Кто будет жить на главном экране?"), 17.sp, t.colors.dim, Modifier.padding(bottom = 16.dp)) }
                            item {
                                val m = mascotById(cfg.mascot)
                                SettingRow(m.name, "< >", hint = m.note, focusRequester = rememberAutoFocus(),
                                    onLeft = { store.update { it.copy(mascot = MASCOTS.map { x -> x.id }.cycle(it.mascot, -1)) } },
                                    onRight = { store.update { it.copy(mascot = MASCOTS.map { x -> x.id }.cycle(it.mascot, 1)) } },
                                    onClick = { go(STEP_WEATHER) })
                            }
                            item { ToggleRow(tr("Маскот разговаривает"), cfg.mascotSpeaks) { v -> store.update { it.copy(mascotSpeaks = v) } } }
                            item { SettingRow(tr("Дальше")) { go(STEP_WEATHER) } }
                        }
                        STEP_WEATHER -> item { WeatherStep(cfg, found, cityError, onSearch = { cityEdit = true }, onPicked = { found = emptyList() }) { go(STEP_FAVORITES) } }
                        STEP_FAVORITES -> {
                            item { PText(tr("Отметьте приложения для ряда «Избранное» — порядок потом можно поменять."), 17.sp, t.colors.dim, Modifier.padding(bottom = 12.dp), maxLines = 2) }
                            item { SettingRow(tr("Дальше"), focusRequester = rememberAutoFocus()) { go(STEP_CLEANUP) } }
                            val apps = app.apps.apps.value
                            items(apps, key = { it.pkg }) { e ->
                                ToggleRow(e.label, e.pkg in cfg.favorites) { on ->
                                    store.update { it.copy(favorites = if (on) (it.favorites + e.pkg).distinct() else it.favorites - e.pkg) }
                                }
                            }
                        }
                        STEP_CLEANUP -> {
                            item {
                                PText(tr("Отключить рекламу, аналитику, PatchWall и предустановленный мусор? Приложения не удаляются, а вернуть всё можно одной кнопкой в «Чистке ТВ»."), 17.sp, t.colors.dim, Modifier.padding(bottom = 16.dp), maxLines = 4)
                            }
                            item {
                                SettingRow(if (busy) tr("Отключаю…") else tr("Отключить рекомендуемое"), focusRequester = rememberAutoFocus()) {
                                    background(STEP_DONE) { tr("Отключено: {0}", TvCleanup.disableRecommended(context)) }
                                }
                            }
                            item { SettingRow(tr("Пропустить")) { go(STEP_DONE) } }
                        }
                        else -> {
                            item {
                                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PText(tr("• Кнопка «Меню» на главном экране — настройки"), 18.sp, t.colors.text)
                                    PText(tr("• Удержание OK на приложении — избранное, категории, скрыть"), 18.sp, t.colors.text)
                                    PText(tr("• Настройки → С телефона — удобная настройка со смартфона"), 18.sp, t.colors.text)
                                    PText(tr("• Сценарии — кнопки пульта, время, включение ТВ"), 18.sp, t.colors.text)
                                }
                            }
                            item { SettingRow(tr("Начать"), focusRequester = rememberAutoFocus()) { finish() } }
                        }
                    }
                    // Вернуться на шаг назад — строкой внизу или кнопкой «Назад» на пульте.
                    if (step > STEP_LANGUAGE) item { SettingRow(tr("← Назад")) { go(step - 1) } }
                }
            }
        }
        if (cityEdit) {
            TextEditOverlay(tr("Город"), "", onDismiss = { cityEdit = false }) { q ->
                cityEdit = false
                if (q.isNotBlank()) scope.launch {
                    runCatching { app.weather.searchCity(q) }
                        .onSuccess { list -> found = list; cityError = if (list.isEmpty()) tr("Город «{0}» не найден", q) else null }
                        .onFailure { cityError = tr("Поиск не удался: {0} {1}", it.javaClass.simpleName, it.message ?: "") }
                }
            }
        }
    }
}

/** Шаг «Погода»: найти город по названию. */
@Composable
private fun WeatherStep(cfg: LauncherConfig, found: List<Place>, error: String?, onSearch: () -> Unit, onPicked: () -> Unit, onNext: () -> Unit) {
    val app = PixelDenApp.instance
    val t = LocalDen.current
    val scope = rememberCoroutineScope()
    Column {
        PText(tr("Город для погоды на главном экране:"), 17.sp, t.colors.dim, Modifier.padding(bottom = 12.dp))
        SettingRow(tr("Город"), cfg.weatherCity ?: tr("не выбран"), hint = error ?: tr("OK — найти город по названию"), focusRequester = rememberAutoFocus(), onClick = onSearch)
        found.forEach { p ->
            SettingRow(p.name, hint = p.region) {
                app.store.update { it.copy(weatherCity = p.name, weatherLat = p.lat, weatherLon = p.lon) }
                onPicked()
                scope.launch { app.weather.refresh() }
            }
        }
        SettingRow(if (cfg.weatherCity != null) tr("Дальше") else tr("Пропустить"), onClick = onNext)
    }
}

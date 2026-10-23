package com.pixelden.launcher.ui

import android.Manifest
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.BuildConfig
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.Category
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.scenario.DenAccessibilityService
import com.pixelden.launcher.scenario.Templates
import java.io.File


/**
 * Настройки: [page] == null — хаб с плитками разделов, иначе — страница раздела.
 * Переходы между хабом и страницами идут через общую навигацию (с эффектом перехода), BACK возвращает на хаб.
 */
@Composable
fun SettingsScreen(
    cfg: LauncherConfig,
    page: String?,
    onOpenPage: (String) -> Unit,
    onOpenLog: () -> Unit = {},
    onBackToHub: () -> Unit = {},
) {
    when (page) {
        null -> SettingsHub(cfg, onOpenPage)
        // Калибровке нужен весь экран без рамок настроек.
        "calib" -> CalibrationScreen(onExit = onBackToHub)
        else -> SettingsPage(cfg, page, onOpenLog)
    }
}

@Composable
private fun SettingsPage(cfg: LauncherConfig, page: String, onOpenLog: () -> Unit) {
    val app = PixelDenApp.instance
    val store = app.store
    val context = LocalContext.current
    val t = LocalDen.current
    val apps by app.apps.apps.collectAsState()
    val first = rememberAutoFocus()
    val section = SECTIONS.firstOrNull { it.key == page }
    var renameCat by remember { mutableStateOf<Category?>(null) }
    var cityEdit by remember { mutableStateOf(false) }
    var keyEdit by remember { mutableStateOf(false) }
    var cityResults by remember { mutableStateOf<List<com.pixelden.launcher.weather.Place>>(emptyList()) }
    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
    fun refreshWeather() { pageScope.launch { app.weather.refresh() } }
    fun pickCity(p: com.pixelden.launcher.weather.Place) {
        store.update { it.copy(weatherCity = p.name, weatherLat = p.lat, weatherLon = p.lon) }
        refreshWeather()
    }
    var pickWallpaper by remember { mutableStateOf(false) }
    var pickSlot by remember { mutableStateOf<String?>(null) }
    var tvBacklight by remember { mutableStateOf(readBacklight(context)) }
    // Сканирование папок с картинками — в фоне и только на странице оформления.
    val wallpapers by produceState(emptyList<String>(), page) {
        if (page == "look") value = withContext(Dispatchers.IO) { listWallpapers() }
    }

    fun set(f: (LauncherConfig) -> LauncherConfig) = store.update(f)

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            // FocusRequester на списке отдаёт фокус первому пункту страницы при открытии.
            Modifier.widthIn(max = 980.dp).fillMaxSize().focusRequester(first),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 32.dp),
        ) {
            item { PText("Настройки  >  ${section?.title ?: page}", 15.sp, t.colors.dim) }
            item {
                Row(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    section?.let { PixelIcon(it.icon, 6, t.colors.accent, t.colors.text, Modifier.padding(end = 16.dp)) }
                    PText(section?.title ?: page, 40.sp, t.colors.text)
                }
            }
            item { PText("< > меняют значение · OK — выбрать · BACK — к разделам", 14.sp, t.colors.dim, Modifier.padding(bottom = 16.dp)) }

            if (page == "mascot") {
                item {
                    val m = mascotById(cfg.mascot)
                    SettingRow(
                        "Кто живёт в лаунчере", m.name, hint = m.note,
                        onLeft = { set { it.copy(mascot = MASCOTS.map { m -> m.id }.cycle(it.mascot, -1)) } },
                        onRight = { set { it.copy(mascot = MASCOTS.map { m -> m.id }.cycle(it.mascot, 1)) } },
                        trailing = { MascotSprite(m, 2, MascotMood.IDLE, t.animations, headroom = 1, modifier = Modifier.padding(start = 12.dp)) },
                    )
                }
                item { ToggleRow("Показывать маскота", cfg.mascotEnabled) { v -> set { it.copy(mascotEnabled = v) } } }
                item { ToggleRow("Маскот разговаривает", cfg.mascotSpeaks, "Приветствие, название приложения в фокусе, итоги сценариев") { v -> set { it.copy(mascotSpeaks = v) } } }
                item {
                    val levels = listOf("молчит", "редко", "обычно", "болтун")
                    SettingRow("Болтливость", levels[cfg.mascotChatter.coerceIn(0, 3)],
                        hint = "Как часто маскот сам что-то говорит",
                        onLeft = { set { it.copy(mascotChatter = it.mascotChatter.stepIn(-1, 0..3)) } },
                        onRight = { set { it.copy(mascotChatter = it.mascotChatter.stepIn(1, 0..3)) } })
                }
                item { ToggleRow("Факты", cfg.talkFacts, "Любопытные факты про котов, кино, космос, игры") { v -> set { it.copy(talkFacts = v) } } }
                item { ToggleRow("Праздники", cfg.talkHolidays, "Поздравляет с праздниками и памятными датами") { v -> set { it.copy(talkHolidays = v) } } }
                item {
                    ToggleRow("Свежие новости", cfg.talkNews, "Заголовки Ленты.ру, обновляются раз в час") { v ->
                        set { it.copy(talkNews = v) }
                        if (v) app.news.start() else app.news.stop()
                    }
                }
                item { ToggleRow("Называть приложение в фокусе", cfg.mascotNamesApps) { v -> set { it.copy(mascotNamesApps = v) } } }
                item {
                    SettingRow("Засыпает через", "${cfg.mascotSleepMinutes} мин",
                        onLeft = { set { it.copy(mascotSleepMinutes = it.mascotSleepMinutes.stepIn(-1, 1..60)) } },
                        onRight = { set { it.copy(mascotSleepMinutes = it.mascotSleepMinutes.stepIn(1, 1..60)) } })
                }
                item {
                    SettingRow("Размер маскота", "${cfg.mascotScale}",
                        onLeft = { set { it.copy(mascotScale = it.mascotScale.stepIn(-1, 2..6)) } },
                        onRight = { set { it.copy(mascotScale = it.mascotScale.stepIn(1, 2..6)) } })
                }
            }

            if (page == "look") {
                item {
                    val ids = FONTS.map { it.first }
                    SettingRow("Шрифт", (FONTS.firstOrNull { it.first == cfg.font } ?: FONTS.first()).second,
                        onLeft = { set { it.copy(font = ids.cycle(it.font, -1)) } },
                        onRight = { set { it.copy(font = ids.cycle(it.font, 1)) } })
                }
                item {
                    SettingRow("Акцентный цвет", "${ACCENTS.indexOf(cfg.accent) + 1}/${ACCENTS.size}",
                        onLeft = { set { it.copy(accent = ACCENTS.cycle(it.accent, -1)) } },
                        onRight = { set { it.copy(accent = ACCENTS.cycle(it.accent, 1)) } },
                        trailing = { ColorSwatch(cfg.accent) })
                }
                item {
                    SettingRow("Цвет фона", "${BACKGROUNDS.indexOf(cfg.background) + 1}/${BACKGROUNDS.size}",
                        onLeft = { set { it.copy(background = BACKGROUNDS.cycle(it.background, -1)) } },
                        onRight = { set { it.copy(background = BACKGROUNDS.cycle(it.background, 1)) } },
                        trailing = { ColorSwatch(cfg.background) })
                }
                item {
                    val options = listOf<String?>(null) + SCENES.map { it.first } + wallpapers
                    SettingRow("Обои", wallName(cfg.wallpaperPath) ?: "нет",
                        hint = "OK — выбрать с предпросмотром · < > — листать · живые сцены идут первыми",
                        onLeft = { set { it.copy(wallpaperPath = options.cycle(it.wallpaperPath, -1)) } },
                        onRight = { set { it.copy(wallpaperPath = options.cycle(it.wallpaperPath, 1)) } },
                        trailing = { WallThumb(cfg.wallpaperPath, cfg.background, Modifier.padding(start = 12.dp).size(64.dp, 36.dp)) },
                        onClick = { pickSlot = null; pickWallpaper = true })
                }
                item {
                    ToggleRow("Погода на обоях", cfg.weatherOnWallpaper, "Дождь, снег, туман и молнии по реальной погоде за окном") { v ->
                        set { it.copy(weatherOnWallpaper = v) }
                    }
                }
                item {
                    ToggleRow("Обои по времени суток", cfg.wallpaperByTime, "Утром, днём, вечером и ночью — свои обои") { v ->
                        set { it.copy(wallpaperByTime = v) }
                    }
                }
                if (cfg.wallpaperByTime) {
                    items(TIME_SLOTS, key = { "slot" + it.first }) { (slot, label) ->
                        val p = cfg.timeWallpapers[slot]
                        SettingRow("   $label", wallName(p) ?: "как общие",
                            hint = if (p != null) "OK — выбрать · удержание OK — как общие" else "OK — выбрать",
                            trailing = { WallThumb(p ?: cfg.wallpaperPath, cfg.background, Modifier.padding(start = 12.dp).size(64.dp, 36.dp)) },
                            onLongClick = { set { it.copy(timeWallpapers = it.timeWallpapers - slot) } },
                            onClick = { pickSlot = slot; pickWallpaper = true })
                    }
                }
                item {
                    SettingRow("Затемнение обоев", "${cfg.wallpaperDim}%",
                        onLeft = { set { it.copy(wallpaperDim = it.wallpaperDim.stepIn(-10, 0..90)) } },
                        onRight = { set { it.copy(wallpaperDim = it.wallpaperDim.stepIn(10, 0..90)) } })
                }
                item { ToggleRow("Анимации", cfg.animations) { v -> set { it.copy(animations = v) } } }
                item {
                    val ids = SCREEN_TRANSITIONS.map { it.first }
                    SettingRow("Переход между экранами", (SCREEN_TRANSITIONS.firstOrNull { it.first == cfg.screenTransition } ?: SCREEN_TRANSITIONS.first()).second,
                        onLeft = { set { it.copy(screenTransition = ids.cycle(it.screenTransition, -1)) } },
                        onRight = { set { it.copy(screenTransition = ids.cycle(it.screenTransition, 1)) } })
                }
                item {
                    SettingRow("Увеличение в фокусе", "${cfg.focusScale}%",
                        onLeft = { set { it.copy(focusScale = it.focusScale.stepIn(-1, 0..15)) } },
                        onRight = { set { it.copy(focusScale = it.focusScale.stepIn(1, 0..15)) } })
                }
                item {
                    SettingRow("Толщина пиксельной рамки", "${cfg.pixelBorder}",
                        onLeft = { set { it.copy(pixelBorder = it.pixelBorder.stepIn(-1, 1..8)) } },
                        onRight = { set { it.copy(pixelBorder = it.pixelBorder.stepIn(1, 1..8)) } })
                }
            }

            if (page == "effects") {
                item { PText("Можно включать несколько сразу — они сочетаются.", 14.sp, t.colors.dim, Modifier.padding(4.dp, 0.dp, 0.dp, 8.dp)) }
                items(FOCUS_EFFECTS, key = { "fx" + it.first }) { (id, label) ->
                    ToggleRow(label.removePrefix("Только "), id in cfg.focusEffects) { on ->
                        set { c -> c.copy(focusEffects = if (on) (c.focusEffects + id).distinct() else c.focusEffects - id) }
                    }
                }
            }

            if (page == "tv") {
                item {
                    SettingRow("Подсветка экрана", "$tvBacklight%",
                        hint = "Меняет подсветку панели (то же, что в настройках изображения ТВ)",
                        onLeft = { tvBacklight = setBacklight(context, tvBacklight - 5) },
                        onRight = { tvBacklight = setBacklight(context, tvBacklight + 5) })
                }
                items(TV_PAGES, key = { "tv" + it.second }) { (label, action) ->
                    SettingRow(label) {
                        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { toast(context, "Эта страница на ТВ недоступна") }
                    }
                }
            }

            if (page == "weather") {
                item { ToggleRow("Показывать погоду", cfg.weatherEnabled) { v -> set { it.copy(weatherEnabled = v) }; refreshWeather() } }
                item {
                    SettingRow("Город", cfg.weatherCity ?: "не выбран", hint = "OK — найти город по названию") { cityEdit = true }
                }
                item {
                    SettingRow(
                        "Ключ API Яндекс Погоды", if (cfg.yandexWeatherKey.isBlank()) "нет" else "задан",
                        hint = "Без ключа погода берётся у Open-Meteo. Ключ выдают на developer.tech.yandex.ru",
                        onLongClick = { set { it.copy(yandexWeatherKey = "") }; refreshWeather() },
                    ) { keyEdit = true }
                }
                item {
                    val w by app.weather.now.collectAsState()
                    val err by app.weather.error.collectAsState()
                    SettingRow(
                        "Источник", err?.let { "ошибка" } ?: w?.source ?: "—",
                        hint = err ?: w?.let { "${it.temp}°, ${it.sky.text} · обновлено " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.updatedAt)) },
                    )
                }
                item { SettingRow("Обновить сейчас") { refreshWeather() } }
            }

            if (page == "grid") {
                item {
                    SettingRow("Колонок во «Все приложения»", "${cfg.columns}",
                        onLeft = { set { it.copy(columns = it.columns.stepIn(-1, 3..10)) } },
                        onRight = { set { it.copy(columns = it.columns.stepIn(1, 3..10)) } })
                }
                item {
                    SettingRow("Размер карточек в рядах", "${cfg.cardSize}%",
                        onLeft = { set { it.copy(cardSize = it.cardSize.stepIn(-10, 60..160)) } },
                        onRight = { set { it.copy(cardSize = it.cardSize.stepIn(10, 60..160)) } })
                }
                item { ToggleRow("Подписи под карточками", cfg.showLabels) { v -> set { it.copy(showLabels = v) } } }
                item { ToggleRow("Баннеры приложений", cfg.preferBanners, "Выкл — везде иконки") { v -> set { it.copy(preferBanners = v) } } }
            }

            if (page == "clock") {
                item { ToggleRow("24-часовой формат", cfg.clock24h) { v -> set { it.copy(clock24h = v) } } }
                item { ToggleRow("Секунды", cfg.showSeconds) { v -> set { it.copy(showSeconds = v) } } }
                item { ToggleRow("Дата", cfg.showDate) { v -> set { it.copy(showDate = v) } } }
                item { ToggleRow("Приветствие при запуске", cfg.greeting) { v -> set { it.copy(greeting = v) } } }
            }

            if (page == "rows") {
                item { ToggleRow("Избранное", cfg.showFavorites) { v -> set { it.copy(showFavorites = v) } } }
                item { ToggleRow("Входы (HDMI, ТВ)", cfg.showInputs) { v -> set { it.copy(showInputs = v) } } }
                item { ToggleRow("Категории", cfg.showCategories) { v -> set { it.copy(showCategories = v) } } }
                item { ToggleRow("Все приложения", cfg.showAllApps) { v -> set { it.copy(showAllApps = v) } } }
                item { ToggleRow("Кнопки ручных сценариев", cfg.showManualScenarios, "Сценарии с триггером «вручную» — кнопками под часами") { v -> set { it.copy(showManualScenarios = v) } } }
            }

            if (page == "cats") {
                items(cfg.categories, key = { "c" + it.id }) { cat ->
                    SettingRow(
                        cat.name, "${cat.apps.size} прил.",
                        hint = "OK — переименовать · < > — порядок · удержание OK — удалить",
                        onLeft = { set { c -> c.copy(categories = c.categories.move(cat, -1)) } },
                        onRight = { set { c -> c.copy(categories = c.categories.move(cat, 1)) } },
                        onLongClick = { set { c -> c.copy(categories = c.categories - cat) } },
                        onClick = { renameCat = cat },
                    )
                }
                item {
                    SettingRow("+ Новая категория") {
                        set { it.copy(categories = it.categories + Category(Templates.newId(), "Категория ${it.categories.size + 1}")) }
                    }
                }
            }

            if (page == "hidden") {
                items(apps, key = { "h" + it.pkg }) { e ->
                    val hidden = e.pkg in cfg.hidden
                    SettingRow(
                        cfg.renamed[e.pkg] ?: e.label,
                        if (hidden) "скрыто" else "видно",
                        onLeft = { set { it.copy(hidden = if (hidden) it.hidden - e.pkg else it.hidden + e.pkg) } },
                        onRight = { set { it.copy(hidden = if (hidden) it.hidden - e.pkg else it.hidden + e.pkg) } },
                    )
                }
            }

            if (page == "perm") {
                item {
                    val on = DenAccessibilityService.instance != null
                    SettingRow(
                        "Служба кнопок и сценариев", if (on) "работает" else "выключена",
                        hint = "Нужна для кнопок пульта, «нажать по тексту», сна, Домой/Назад. OK — открыть настройки",
                    ) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                item {
                    val ok = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    SettingRow("Системные настройки (WRITE_SECURE_SETTINGS)", if (ok) "есть" else "нет",
                        hint = "adb shell pm grant com.pixelden.launcher android.permission.WRITE_SECURE_SETTINGS")
                }
                item {
                    SettingRow("Яркость (WRITE_SETTINGS)", if (Settings.System.canWrite(context)) "есть" else "нет",
                        hint = "adb shell appops set com.pixelden.launcher WRITE_SETTINGS allow")
                }
            }

            if (page == "debug") {
                item { SettingRow("Открыть журнал", hint = "Запуски сценариев, их шаги, ошибки и нажатия кнопок", onClick = onOpenLog) }
                item {
                    ToggleRow("Записывать нажатия кнопок", DebugLog.logKeys, "Нажмите кнопку пульта — в журнале будет её код и привязанные сценарии") { v ->
                        DebugLog.logKeys = v
                    }
                }
                item {
                    SettingRow("Сохранить журнал и конфиг в файл", hint = "/sdcard/Download/pixelden-debug.txt") {
                        toast(context, "Сохранено: " + DebugLog.dumpToFile(context, cfg).name)
                    }
                }
            }

            if (page == "backup") {
                item {
                    SettingRow("Сохранить настройки и сценарии", hint = store.exportFile().absolutePath) {
                        toast(context, store.exportBackup().fold({ "Сохранено: ${it.name}" }, { "Ошибка: ${it.message}" }))
                    }
                }
                item {
                    SettingRow("Загрузить из файла", hint = "Заменит текущие настройки содержимым pixelden-backup.json") {
                        toast(context, store.importBackup().fold({ "Загружено" }, { "Ошибка: ${it.message}" }))
                    }
                }
            }

            if (page == "phone") {
                item {
                    ToggleRow("Настройка с телефона", cfg.webPanel, "Страница в домашней сети: сценарии, обои, порядок приложений, текст на ТВ") { v ->
                        set { it.copy(webPanel = v) }
                        if (v) app.web.start() else app.web.stop()
                    }
                }
                if (cfg.webPanel) {
                    item {
                        val ip = com.pixelden.launcher.web.WebServer.lanAddress()
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                .pixelBox(t.colors.accent, t.colors.surface, t.border).padding(24.dp),
                        ) {
                            PText("Откройте на телефоне:", 16.sp, t.colors.dim)
                            PText(if (ip != null) "http://$ip:${com.pixelden.launcher.web.WebServer.PORT}" else "ТВ не в сети", 34.sp, t.colors.text, Modifier.padding(vertical = 6.dp))
                            PText("PIN:  ${cfg.webPin}", 34.sp, t.colors.accent)
                            PText("Телефон должен быть в той же сети Wi-Fi.", 14.sp, t.colors.dim, Modifier.padding(top = 8.dp))
                        }
                    }
                    item {
                        SettingRow("Новый PIN", hint = "Старый перестанет подходить — телефон спросит PIN заново") {
                            set { it.copy(webPin = com.pixelden.launcher.data.Defaults.newPin()) }
                        }
                    }
                }
            }

            if (page == "widgets") {
                item { ToggleRow("Ряд виджетов под часами", cfg.showWidgets) { v -> set { it.copy(showWidgets = v) }; if (v) app.rates.start() } }
                // Порядок строк постоянный — переключение не двигает строку и не уводит фокус.
                items(WIDGETS, key = { "w" + it.first }) { (id, name) ->
                    ToggleRow(name, id in cfg.widgets) { on ->
                        // Новый виджет встаёт на своё место из общего списка, а не в конец.
                        set { c -> c.copy(widgets = WIDGETS.map { it.first }.filter { w -> if (w == id) on else w in c.widgets }) }
                        if (on && id == "rates") app.rates.start()
                    }
                }
            }

            if (page == "health") {
                item {
                    ToggleRow("Сторож памяти", cfg.memoryGuard, "Когда памяти меньше 15%, сам закрывает фоновые приложения (не чаще раза в 10 минут)") { v ->
                        set { it.copy(memoryGuard = v) }
                        com.pixelden.launcher.system.MemoryGuard.apply(v)
                    }
                }
                item { HealthPanel() }
            }

            if (page == "about") {
                item { SettingRow("PixelTv ${BuildConfig.VERSION_NAME}", hint = "Шрифты: Tiny5, Handjet, Press Start 2P, Pixelify Sans (SIL OFL)") }
            }
        }

        if (pickWallpaper) {
            val slot = pickSlot
            WallpaperPicker(
                current = if (slot != null) cfg.timeWallpapers[slot] else cfg.wallpaperPath,
                files = SCENES.map { it.first } + wallpapers,
                background = cfg.background,
                onDismiss = { pickWallpaper = false },
            ) { path ->
                set {
                    if (slot == null) it.copy(wallpaperPath = path)
                    else it.copy(timeWallpapers = if (path == null) it.timeWallpapers - slot else it.timeWallpapers + (slot to path))
                }
                pickWallpaper = false
            }
        }

        if (cityEdit) {
            TextEditOverlay("Город", cfg.weatherCity ?: "", onDismiss = { cityEdit = false }) { q ->
                cityEdit = false
                if (q.isNotBlank()) pageScope.launch {
                    val found = runCatching { app.weather.searchCity(q) }.getOrElse { 
                        com.pixelden.launcher.scenario.DebugLog.e("поиск города «$q» не удался", it)
                        toast(context, "Поиск не удался: ${it.javaClass.simpleName} ${it.message ?: ""}"); null
                    } ?: return@launch
                    when (found.size) {
                        0 -> toast(context, "Город «$q» не найден")
                        1 -> pickCity(found.first())
                        else -> cityResults = found
                    }
                }
            }
        }
        if (cityResults.isNotEmpty()) {
            Overlay("Какой город?", onDismiss = { cityResults = emptyList() }) {
                items(cityResults.size) { i ->
                    val p = cityResults[i]
                    SettingRow(p.name, hint = p.region, focusRequester = if (i == 0) rememberAutoFocus() else null) {
                        pickCity(p); cityResults = emptyList()
                    }
                }
            }
        }
        if (keyEdit) {
            TextEditOverlay("Ключ API Яндекс Погоды", cfg.yandexWeatherKey, onDismiss = { keyEdit = false }) { k ->
                set { it.copy(yandexWeatherKey = k.trim()) }
                keyEdit = false
                refreshWeather()
            }
        }

        renameCat?.let { cat ->
            TextEditOverlay("Название категории", cat.name, onDismiss = { renameCat = null }) { name ->
                if (name.isNotBlank()) set { c -> c.copy(categories = c.categories.map { if (it.id == cat.id) it.copy(name = name.trim()) else it }) }
                renameCat = null
            }
        }
    }
}

private fun List<Category>.move(cat: Category, step: Int): List<Category> {
    val l = toMutableList()
    val i = l.indexOfFirst { it.id == cat.id }
    val j = (i + step).coerceIn(0, l.lastIndex)
    if (i < 0 || i == j) return this
    l.add(j, l.removeAt(i))
    return l
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** Миниатюра обоев: 4K-картинка ужимается в 16 раз, хватает и для плитки, и для превью в строке. */
@Composable
fun WallThumb(path: String?, background: Long, modifier: Modifier = Modifier) {
    val img by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                if (path.startsWith("scene:")) sceneStill(path).asImageBitmap()
                else decodeSampled(path, 320, 180)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(background)), contentAlignment = Alignment.Center) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Выбор обоев плитками с предпросмотром. Первая плитка — без обоев (просто цвет фона). */
@Composable
private fun WallpaperPicker(
    current: String?,
    files: List<String>,
    background: Long,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val t = LocalDen.current
    val options = listOf<String?>(null) + files
    val first = rememberAutoFocus()
    Overlay("Обои", onDismiss, width = 960.dp) {
        items(options.chunked(3)) { row ->
            Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { path ->
                    var focused by remember { mutableStateOf(false) }
                    val selected = path == current
                    Column(Modifier.width(290.dp)) {
                        Box(
                            Modifier
                                .size(290.dp, 163.dp)
                                .let { if (path == options.first()) it.focusRequester(first) else it }
                                .tvClickable(onFocus = { focused = it }) { onPick(path) }
                                .pixelBox(
                                    when { focused -> t.colors.accent; selected -> t.colors.text.copy(alpha = 0.6f); else -> Color.Transparent },
                                    t.colors.surface, t.border,
                                )
                                .padding((t.border + 1).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            WallThumb(path, background, Modifier.fillMaxSize())
                            if (path == null) PText("Без обоев", 16.sp, t.colors.dim)
                        }
                        PText(
                            (if (selected) "• " else "") + (wallName(path)?.replace('_', ' ')?.take(30) ?: "Только цвет фона"),
                            13.sp,
                            if (focused) t.colors.accent else t.colors.dim,
                            Modifier.padding(top = 6.dp, start = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Системные страницы настроек телевизора. */
private val TV_PAGES = listOf(
    "Все настройки ТВ" to Settings.ACTION_SETTINGS,
    "Изображение и экран" to Settings.ACTION_DISPLAY_SETTINGS,
    "Звук и аудиовыход" to Settings.ACTION_SOUND_SETTINGS,
    "Сеть и Wi-Fi" to Settings.ACTION_WIFI_SETTINGS,
    "Приложения" to Settings.ACTION_APPLICATION_SETTINGS,
    "Дата и время" to Settings.ACTION_DATE_SETTINGS,
    "Специальные возможности" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    "Для разработчиков" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
)

/** Подсветка Xiaomi: global/picture_backlight, 0..100. ТВ применяет новое значение сам. */
private fun readBacklight(context: Context): Int =
    runCatching { Settings.Global.getInt(context.contentResolver, "picture_backlight") }.getOrDefault(100)

private fun setBacklight(context: Context, value: Int): Int {
    val v = value.coerceIn(0, 100)
    runCatching { Settings.Global.putInt(context.contentResolver, "picture_backlight", v) }
        .onFailure { toast(context, "Нет прав: adb shell pm grant com.pixelden.launcher android.permission.WRITE_SECURE_SETTINGS") }
    return v
}

/** Название обоев для строки настроек: сцена или имя файла. */
fun wallName(path: String?): String? = path?.let { sceneName(it) ?: File(it).nameWithoutExtension.take(24) }

private fun <T> List<T>.moveItem(item: T, step: Int): List<T> {
    val l = toMutableList()
    val i = l.indexOf(item)
    val j = (i + step).coerceIn(0, l.lastIndex)
    if (i < 0 || i == j) return this
    l.add(j, l.removeAt(i))
    return l
}

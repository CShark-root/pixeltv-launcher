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

/** Какие разделы настроек раскрыты — живёт, пока жив процесс лаунчера. */
private var openSections by mutableStateOf(setOf<String>())

@Composable
fun SettingsScreen(cfg: LauncherConfig, onOpenLog: () -> Unit = {}) {
    val app = PixelDenApp.instance
    val store = app.store
    val context = LocalContext.current
    val t = LocalDen.current
    val apps by app.apps.apps.collectAsState()
    val first = rememberAutoFocus()
    var renameCat by remember { mutableStateOf<Category?>(null) }
    var pickWallpaper by remember { mutableStateOf(false) }
    var tvBacklight by remember { mutableStateOf(readBacklight(context)) }
    val wallpapers = remember { listWallpapers() }

    fun set(f: (LauncherConfig) -> LauncherConfig) = store.update(f)

    /** Выпадающий раздел: заголовок всегда виден, содержимое — только когда раскрыт. */
    fun LazyListScope.fold(
        key: String,
        title: String,
        summary: String? = null,
        focus: FocusRequester? = null,
        content: LazyListScope.() -> Unit,
    ) {
        val open = key in openSections
        item(key = "fold-$key") {
            SettingRow(
                (if (open) "v  " else ">  ") + title,
                value = summary,
                focusRequester = focus,
                arrows = false,
                onLeft = { openSections = openSections - key },
                onRight = { openSections = openSections + key },
                onClick = { openSections = if (open) openSections - key else openSections + key },
            )
        }
        if (open) content()
    }

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 980.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 32.dp),
        ) {
            item { PText("Настройки", 40.sp, t.colors.text, Modifier.padding(bottom = 4.dp)) }
            item { PText("OK — раскрыть раздел · < > меняют значение · BACK — назад", 14.sp, t.colors.dim, Modifier.padding(bottom = 14.dp)) }

            fold("mascot", "Маскот", mascotById(cfg.mascot).name, focus = first) {
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

            fold("look", "Внешний вид", (FONTS.firstOrNull { it.first == cfg.font } ?: FONTS.first()).second) {
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
                    val options = listOf<String?>(null) + wallpapers
                    SettingRow("Обои", cfg.wallpaperPath?.let { File(it).nameWithoutExtension.take(24) } ?: "нет",
                        hint = "OK — выбрать с предпросмотром · < > — листать",
                        onLeft = { set { it.copy(wallpaperPath = options.cycle(it.wallpaperPath, -1)) } },
                        onRight = { set { it.copy(wallpaperPath = options.cycle(it.wallpaperPath, 1)) } },
                        trailing = { WallThumb(cfg.wallpaperPath, cfg.background, Modifier.padding(start = 12.dp).size(64.dp, 36.dp)) },
                        onClick = { pickWallpaper = true })
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

            fold("effects", "Эффекты при наведении", "${cfg.focusEffects.size} вкл") {
                item { PText("Можно включать несколько сразу — они сочетаются.", 14.sp, t.colors.dim, Modifier.padding(4.dp, 0.dp, 0.dp, 8.dp)) }
                items(FOCUS_EFFECTS, key = { "fx" + it.first }) { (id, label) ->
                    ToggleRow(label.removePrefix("Только "), id in cfg.focusEffects) { on ->
                        set { c -> c.copy(focusEffects = if (on) (c.focusEffects + id).distinct() else c.focusEffects - id) }
                    }
                }
            }

            fold("tv", "Телевизор", "подсветка ${tvBacklight}%") {
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

            fold("grid", "Сетка и карточки", "${cfg.columns} колонок") {
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

            fold("clock", "Часы и шапка") {
                item { ToggleRow("24-часовой формат", cfg.clock24h) { v -> set { it.copy(clock24h = v) } } }
                item { ToggleRow("Секунды", cfg.showSeconds) { v -> set { it.copy(showSeconds = v) } } }
                item { ToggleRow("Дата", cfg.showDate) { v -> set { it.copy(showDate = v) } } }
                item { ToggleRow("Приветствие при запуске", cfg.greeting) { v -> set { it.copy(greeting = v) } } }
            }

            fold("rows", "Ряды главного экрана") {
                item { ToggleRow("Избранное", cfg.showFavorites) { v -> set { it.copy(showFavorites = v) } } }
                item { ToggleRow("Входы (HDMI, ТВ)", cfg.showInputs) { v -> set { it.copy(showInputs = v) } } }
                item { ToggleRow("Категории", cfg.showCategories) { v -> set { it.copy(showCategories = v) } } }
                item { ToggleRow("Все приложения", cfg.showAllApps) { v -> set { it.copy(showAllApps = v) } } }
            }

            fold("cats", "Категории", "${cfg.categories.size}") {
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

            fold("hidden", "Скрытые приложения", "скрыто ${apps.count { it.pkg in cfg.hidden }} из ${apps.size}") {
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

            fold("perm", "Права и службы", if (DenAccessibilityService.instance != null) "служба работает" else "служба выключена") {
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

            fold("debug", "Отладка", if (DebugLog.logKeys) "кнопки пишутся" else null) {
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

            fold("backup", "Резервная копия") {
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

            fold("about", "О программе", BuildConfig.VERSION_NAME) {
                item { SettingRow("PixelDen ${BuildConfig.VERSION_NAME}", hint = "Шрифты: Tiny5, Handjet, Press Start 2P, Pixelify Sans (SIL OFL)") }
            }
        }

        if (pickWallpaper) {
            WallpaperPicker(
                current = cfg.wallpaperPath,
                files = wallpapers,
                background = cfg.background,
                onDismiss = { pickWallpaper = false },
            ) { path ->
                set { it.copy(wallpaperPath = path) }
                pickWallpaper = false
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

private fun listWallpapers(): List<String> {
    val pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    return listOf(File(pics, "Wallpapers"), pics)
        .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
        .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
        .map { it.absolutePath }
        .distinct()
        .sorted()
}

/** Миниатюра обоев: 4K-картинка ужимается в 16 раз, хватает и для плитки, и для превью в строке. */
@Composable
fun WallThumb(path: String?, background: Long, modifier: Modifier = Modifier) {
    val img by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 16 })?.asImageBitmap()
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
                            (if (selected) "• " else "") + (path?.let { File(it).nameWithoutExtension.replace('_', ' ').take(30) } ?: "Только цвет фона"),
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

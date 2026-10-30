package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.BuildConfig
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.scenario.DenAccessibilityService

/** Раздел настроек: плитка на хабе и отдельная страница. Иконка — пиксельная сетка 9×9 ('#' акцент, '+' светлый). */
class Section(
    val key: String,
    val title: String,
    val icon: List<String>,
    val summary: (SummaryCtx) -> String,
)

class SummaryCtx(val cfg: LauncherConfig, val appsCount: Int, val backlight: Int)

val SECTIONS = listOf(
    Section("mascot", tr("Маскот"), listOf(
        ".#.....#.", ".##...##.", ".#######.", "##+###+##", "#########", "##.#.#.##", ".#######.", "..#...#..", ".........",
    )) { mascotById(it.cfg.mascot).name + if (it.cfg.mascotEnabled) "" else tr(" (скрыт)") },
    Section("phone", tr("С телефона"), listOf(
        "..#####..", "..#+++#..", "..#+++#..", "..#+++#..", "..#+++#..", "..#+++#..", "..##+##..", "..#####..", ".........",
    )) { if (it.cfg.webPanel) "${com.pixelden.launcher.web.WebServer.lanAddress() ?: tr("нет сети")}:${com.pixelden.launcher.web.WebServer.PORT} · PIN ${it.cfg.webPin}" else tr("выключено") },
    Section("look", tr("Внешний вид"), listOf(
        "..#####..", ".#+++++#.", "#+#+++#+#", "#+++++++#", "#+#+++##.", "#+++++#..", ".#+++++#.", "..#####..", ".........",
    )) { (FONTS.firstOrNull { f -> f.first == it.cfg.font } ?: FONTS.first()).second + (sceneName(it.cfg.wallpaperPath)?.let { s -> " · $s" } ?: if (it.cfg.wallpaperPath != null) tr(" · обои") else "") },
    Section("effects", tr("Эффекты наведения"), listOf(
        "....#....", "....#....", "..#.#.#..", "...###...", "#########", "...###...", "..#.#.#..", "....#....", "....#....",
    )) { s -> s.cfg.focusEffects.mapNotNull { id -> FOCUS_EFFECTS.firstOrNull { it.first == id }?.second?.removePrefix(tr("Только ")) }.joinToString(", ").ifEmpty { tr("нет") } },
    Section("weather", tr("Погода"), listOf(
        "y.y.y....", ".yyy.....", "yyyyy....", ".yyy###..", "y.##+++#.", ".#++++++#", "#+++++++#", ".#######.", ".........",
    )) { it.cfg.weatherCity?.let { c -> if (it.cfg.yandexWeatherKey.isBlank()) "$c · Open-Meteo" else tr("{0} · Яндекс", c) } ?: tr("город не выбран") },
    Section("tv", tr("Телевизор"), listOf(
        "..+...+..", "...+.+...", "#########", "#+++++++#", "#+++++++#", "#+++++++#", "#########", "...#.#...", "..#####..",
    )) { tr("подсветка {0}%", (it.backlight)) },
    Section("catalog", tr("Приложения для ТВ"), listOf(
        "###.###..", "#+#.#+#..", "###.###..", ".........", "###..#...", "#+#..#...", "###.###..", ".....#...", ".........",
    )) { tr("{0} приложений · установка в 1 кнопку", com.pixelden.launcher.system.AppCatalog.APPS.size) },
    Section("cleanup", tr("Чистка ТВ"), listOf(
        "......##.", ".....##..", "....##...", "...##....", "..###....", ".#+++#...", "#+#+#+#..", "#+#+#+#..", ".........",
    )) { tr("отключено {0} · вернуть одной кнопкой", (it.cfg.cleanupDisabled.size)) },
    Section("calib", tr("Калибровка экрана"), listOf(
        "#########", "#+#+#+#+#", "#+#+#+#+#", "#+#+#+#+#", "#+#+#+#+#", "#########", "#++++####", "#########", ".........",
    )) { tr("чёрный, контраст, гамма, цвет") },
    Section("health", tr("Здоровье ТВ"), listOf(
        ".##...##.", "#++#.#++#", "#+++#+++#", "#+++++++#", ".#+++++#.", "..#+++#..", "...#+#...", "....#....", ".........",
    )) { tr("память, температура, очистка") },
    Section("widgets", tr("Виджеты"), listOf(
        "####.####", "#++#.#++#", "####.####", ".........", "####.####", "#++#.#++#", "####.####", ".........", ".........",
    )) { if (!it.cfg.showWidgets) tr("выключены") else it.cfg.widgets.mapNotNull { id -> WIDGETS.firstOrNull { w -> w.first == id }?.second?.substringBefore(' ')?.lowercase() }.joinToString(", ") },
    Section("grid", tr("Сетка и карточки"), listOf(
        "###.###..", "#+#.#+#..", "###.###..", ".........", "###.###..", "#+#.#+#..", "###.###..", ".........", ".........",
    )) { tr("{0} колонок · {1}%", (it.cfg.columns), (it.cfg.cardSize)) },
    Section("clock", tr("Часы и шапка"), listOf(
        "..#####..", ".#+++++#.", "#+++#+++#", "#+++#+++#", "#+++###+#", "#+++++++#", ".#+++++#.", "..#####..", ".........",
    )) { (if (it.cfg.clock24h) tr("24 ч") else tr("12 ч")) + if (it.cfg.showSeconds) tr(" · секунды") else "" },
    Section("rows", tr("Ряды главного экрана"), listOf(
        "#########", ".........", "#####....", ".........", "#######..", ".........", "###......", ".........", ".........",
    )) { listOfNotNull(
        tr("избранное").takeIf { _ -> it.cfg.showFavorites }, tr("входы").takeIf { _ -> it.cfg.showInputs },
        tr("категории").takeIf { _ -> it.cfg.showCategories }, tr("все").takeIf { _ -> it.cfg.showAllApps },
    ).joinToString(", ") },
    Section("cats", tr("Категории"), listOf(
        "###......", "#++####..", "#+++++##.", "#++++++#.", "#++++++#.", "#++++++#.", "########.", ".........", ".........",
    )) { "${it.cfg.categories.size}" },
    Section("hidden", tr("Скрытые приложения"), listOf(
        "........#", "...###.#.", ".##+++#..", "#++###.+#", "#++##.++#", "#++#.#++#", ".##.++##.", ".#.###...", "#........",
    )) { tr("скрыто {0} · всего {1}", (it.cfg.hidden.size), (it.appsCount)) },
    Section("perm", tr("Права и службы"), listOf(
        "..###....", ".#+++#...", ".#+.+#...", ".#+++#...", "..###....", "...#.....", "...###...", "...#.....", "...##....",
    )) { if (DenAccessibilityService.instance != null) tr("служба кнопок работает") else tr("служба кнопок выключена") },
    Section("debug", tr("Отладка"), listOf(
        "#..#.#..#", ".#.###.#.", "..#+++#..", "##+#+#+##", "..#+++#..", "##+#+#+##", "..#+++#..", ".#.###.#.", "#.......#",
    )) { if (DebugLog.logKeys) "журнал · кнопки пишутся" else "журнал" },
    Section("backup", tr("Резервная копия"), listOf(
        "#######..", "#+###+##.", "#+###+#+#", "#+++++++#", "#+#####+#", "#+#+++#+#", "#+#+++#+#", "#########", ".........",
    )) { "pixelden-backup.json" },
    Section("about", tr("О программе"), listOf(
        "..#####..", ".#+++++#.", "#+++#+++#", "#+++++++#", "#+++#+++#", "#+++#+++#", ".#++#++#.", "..#####..", ".........",
    )) { "PixelTv ${BuildConfig.VERSION_NAME}" },
)

/**
 * Раздел верхнего уровня — плитка на главном экране настроек. [pages] показываются внутри раздела подряд,
 * каждая под своим заголовком; [links] — отдельные экраны, на них ведут строки-ссылки в конце раздела.
 */
class Group(
    val key: String,
    val title: String,
    val icon: List<String>,
    val pages: List<String>,
    val links: List<String>,
    val summary: (SummaryCtx) -> String,
)

val GROUPS = listOf(
    Group("g_home", tr("Главный экран"), Icons.HOME, listOf("rows", "widgets", "clock", "grid", "weather"), emptyList()) {
        listOfNotNull(
            tr("избранное").takeIf { _ -> it.cfg.showFavorites }, tr("виджеты").takeIf { _ -> it.cfg.showWidgets },
            tr("погода").takeIf { _ -> it.cfg.weatherEnabled }, tr("{0} колонок", it.cfg.columns),
        ).joinToString(" · ")
    },
    Group("g_look", tr("Оформление"), Icons.LOOK, listOf("look", "effects"), emptyList()) {
        (FONTS.firstOrNull { f -> f.first == it.cfg.font } ?: FONTS.first()).second +
            (sceneName(it.cfg.wallpaperPath)?.let { s -> " · $s" } ?: if (it.cfg.wallpaperPath != null) tr(" · обои") else "")
    },
    Group("g_mascot", tr("Маскот"), Icons.MASCOT, listOf("mascot"), emptyList()) {
        mascotById(it.cfg.mascot).name + if (it.cfg.mascotEnabled) "" else tr(" (скрыт)")
    },
    Group("g_tv", tr("Телевизор"), Icons.TV, listOf("tv"), listOf("health", "cleanup", "calib")) {
        tr("подсветка {0}%", (it.backlight)) + tr(" · здоровье · чистка")
    },
    Group("g_apps", tr("Приложения"), Icons.APPS, emptyList(), listOf("catalog", "cats", "hidden")) {
        tr("{0} установлено · каталог", it.appsCount)
    },
    Group("g_system", tr("Система"), Icons.GEAR, listOf("phone", "perm", "backup", "about"), listOf("debug")) {
        "PixelTv ${BuildConfig.VERSION_NAME}" + if (it.cfg.webPanel) tr(" · с телефона") else ""
    },
)

/** Куда ведёт BACK со страницы: со страницы раздела — в раздел, с раздела — на главный экран настроек (null). */
fun settingsParent(page: String): String? = GROUPS.firstOrNull { page in it.pages || page in it.links }?.key

/** Последняя открытая страница — на неё вернётся фокус при возврате на хаб. */
private var lastSection: String? = null

@Composable
fun PixelIcon(grid: List<String>, pixel: Int, accent: Color, light: Color, modifier: Modifier = Modifier, sun: Color = Color(0xFFFFD25A)) {
    // Иконка — картинка 9×9, растянутая без сглаживания: квадратики не расходятся швами при масштабе
    // и рисуются одной командой, а не десятками прямоугольников.
    val img = remember(grid, accent, light, sun) {
        val w = grid.maxOf { it.length }
        val px = IntArray(w * grid.size)
        grid.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val c = when (ch) { '#' -> accent; '+' -> light; 'y' -> sun; else -> null }
                if (c != null) px[y * w + x] = c.toArgb()
            }
        }
        android.graphics.Bitmap.createBitmap(px, w, grid.size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Canvas(modifier.size((9 * pixel).dp)) {
        drawImage(img, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
    }
}

@Composable
fun SettingsHub(cfg: LauncherConfig, onOpen: (String) -> Unit) {
    val t = LocalDen.current
    val context = LocalContext.current
    val apps by PixelDenApp.instance.apps.apps.collectAsState()
    val backlight = remember {
        runCatching { Settings.Global.getInt(context.contentResolver, "picture_backlight") }.getOrDefault(100)
    }
    val ctx = SummaryCtx(cfg, apps.size, backlight)
    val restore = remember { lastSection?.takeIf { k -> GROUPS.any { it.key == k } } ?: GROUPS.first().key }

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1720.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 36.dp),
        ) {
            item { PText(tr("Настройки"), 44.sp, t.colors.text) }
            item { PText(tr("Выберите раздел · BACK — на главный экран"), 15.sp, t.colors.dim, Modifier.padding(bottom = 22.dp)) }
            // Три колонки по реальной ширине экрана — плитки никогда не уезжают за край.
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 20.dp
                    val tileW = (maxWidth - gap * 2) / 3
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        GROUPS.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                row.forEach { s ->
                                    GroupTile(s, ctx, tileW, autoFocus = s.key == restore) {
                                        lastSection = s.key
                                        onOpen(s.key)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Плитка раздела: иконка сверху, под ней название и краткое содержимое. */
@Composable
private fun GroupTile(g: Group, ctx: SummaryCtx, width: Dp, autoFocus: Boolean, onClick: () -> Unit) {
    val t = LocalDen.current
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) fr.tryFocus() }
    val anim = if (t.animations) tween<Float>(180, easing = FastOutSlowInEasing) else snap()
    val p by animateFloatAsState(if (focused) 1f else 0f, anim, label = "tile")

    Column(
        Modifier
            .width(width)
            .focusRequester(fr)
            .tvClickable(onFocus = { focused = it }, onClick = onClick)
            .pixelBox(Color.Transparent, if (focused) t.colors.surfaceHi else t.colors.surface, t.border)
            .drawWithContent {
                drawContent()
                if (p > 0.01f) pixelFrame(t.colors.accent.copy(alpha = p), null, t.border.dp.toPx(), inflate = (1f - p) * 8.dp.toPx())
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        StickerIcon(g.icon, focused, pixel = 3.dp)
        PText(g.title, 22.sp, if (focused) t.colors.text else t.colors.text.copy(alpha = 0.85f), Modifier.padding(top = 10.dp))
        PText(g.summary(ctx), 14.sp, if (focused) t.colors.accent else t.colors.dim, Modifier.padding(top = 4.dp))
    }
}

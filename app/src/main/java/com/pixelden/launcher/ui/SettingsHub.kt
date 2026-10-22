package com.pixelden.launcher.ui

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
    Section("mascot", "Маскот", listOf(
        ".#.....#.", ".##...##.", ".#######.", "##+###+##", "#########", "##.#.#.##", ".#######.", "..#...#..", ".........",
    )) { mascotById(it.cfg.mascot).name + if (it.cfg.mascotEnabled) "" else " (скрыт)" },
    Section("phone", "С телефона", listOf(
        "..#####..", "..#+++#..", "..#+++#..", "..#+++#..", "..#+++#..", "..#+++#..", "..##+##..", "..#####..", ".........",
    )) { if (it.cfg.webPanel) "${com.pixelden.launcher.web.WebServer.lanAddress() ?: "нет сети"}:${com.pixelden.launcher.web.WebServer.PORT} · PIN ${it.cfg.webPin}" else "выключено" },
    Section("look", "Внешний вид", listOf(
        "..#####..", ".#+++++#.", "#+#+++#+#", "#+++++++#", "#+#+++##.", "#+++++#..", ".#+++++#.", "..#####..", ".........",
    )) { (FONTS.firstOrNull { f -> f.first == it.cfg.font } ?: FONTS.first()).second + (sceneName(it.cfg.wallpaperPath)?.let { s -> " · $s" } ?: if (it.cfg.wallpaperPath != null) " · обои" else "") },
    Section("effects", "Эффекты наведения", listOf(
        "....#....", "....#....", "..#.#.#..", "...###...", "#########", "...###...", "..#.#.#..", "....#....", "....#....",
    )) { s -> s.cfg.focusEffects.mapNotNull { id -> FOCUS_EFFECTS.firstOrNull { it.first == id }?.second?.removePrefix("Только ") }.joinToString(", ").ifEmpty { "нет" } },
    Section("weather", "Погода", listOf(
        "y.y.y....", ".yyy.....", "yyyyy....", ".yyy###..", "y.##+++#.", ".#++++++#", "#+++++++#", ".#######.", ".........",
    )) { it.cfg.weatherCity?.let { c -> if (it.cfg.yandexWeatherKey.isBlank()) "$c · Open-Meteo" else "$c · Яндекс" } ?: "город не выбран" },
    Section("tv", "Телевизор", listOf(
        "..+...+..", "...+.+...", "#########", "#+++++++#", "#+++++++#", "#+++++++#", "#########", "...#.#...", "..#####..",
    )) { "подсветка ${it.backlight}%" },
    Section("calib", "Калибровка экрана", listOf(
        "#########", "#+#+#+#+#", "#+#+#+#+#", "#+#+#+#+#", "#+#+#+#+#", "#########", "#++++####", "#########", ".........",
    )) { "чёрный, контраст, гамма, цвет" },
    Section("health", "Здоровье ТВ", listOf(
        ".##...##.", "#++#.#++#", "#+++#+++#", "#+++++++#", ".#+++++#.", "..#+++#..", "...#+#...", "....#....", ".........",
    )) { "память, температура, очистка" },
    Section("widgets", "Виджеты", listOf(
        "####.####", "#++#.#++#", "####.####", ".........", "####.####", "#++#.#++#", "####.####", ".........", ".........",
    )) { if (!it.cfg.showWidgets) "выключены" else it.cfg.widgets.mapNotNull { id -> WIDGETS.firstOrNull { w -> w.first == id }?.second?.substringBefore(' ')?.lowercase() }.joinToString(", ") },
    Section("grid", "Сетка и карточки", listOf(
        "###.###..", "#+#.#+#..", "###.###..", ".........", "###.###..", "#+#.#+#..", "###.###..", ".........", ".........",
    )) { "${it.cfg.columns} колонок · ${it.cfg.cardSize}%" },
    Section("clock", "Часы и шапка", listOf(
        "..#####..", ".#+++++#.", "#+++#+++#", "#+++#+++#", "#+++###+#", "#+++++++#", ".#+++++#.", "..#####..", ".........",
    )) { (if (it.cfg.clock24h) "24 ч" else "12 ч") + if (it.cfg.showSeconds) " · секунды" else "" },
    Section("rows", "Ряды главного экрана", listOf(
        "#########", ".........", "#####....", ".........", "#######..", ".........", "###......", ".........", ".........",
    )) { listOfNotNull(
        "избранное".takeIf { _ -> it.cfg.showFavorites }, "входы".takeIf { _ -> it.cfg.showInputs },
        "категории".takeIf { _ -> it.cfg.showCategories }, "все".takeIf { _ -> it.cfg.showAllApps },
    ).joinToString(", ") },
    Section("cats", "Категории", listOf(
        "###......", "#++####..", "#+++++##.", "#++++++#.", "#++++++#.", "#++++++#.", "########.", ".........", ".........",
    )) { "${it.cfg.categories.size}" },
    Section("hidden", "Скрытые приложения", listOf(
        "........#", "...###.#.", ".##+++#..", "#++###.+#", "#++##.++#", "#++#.#++#", ".##.++##.", ".#.###...", "#........",
    )) { "скрыто ${it.cfg.hidden.size} · всего ${it.appsCount}" },
    Section("perm", "Права и службы", listOf(
        "..###....", ".#+++#...", ".#+.+#...", ".#+++#...", "..###....", "...#.....", "...###...", "...#.....", "...##....",
    )) { if (DenAccessibilityService.instance != null) "служба кнопок работает" else "служба кнопок выключена" },
    Section("debug", "Отладка", listOf(
        "#..#.#..#", ".#.###.#.", "..#+++#..", "##+#+#+##", "..#+++#..", "##+#+#+##", "..#+++#..", ".#.###.#.", "#.......#",
    )) { if (DebugLog.logKeys) "журнал · кнопки пишутся" else "журнал" },
    Section("backup", "Резервная копия", listOf(
        "#######..", "#+###+##.", "#+###+#+#", "#+++++++#", "#+#####+#", "#+#+++#+#", "#+#+++#+#", "#########", ".........",
    )) { "pixelden-backup.json" },
    Section("about", "О программе", listOf(
        "..#####..", ".#+++++#.", "#+++#+++#", "#+++++++#", "#+++#+++#", "#+++#+++#", ".#++#++#.", "..#####..", ".........",
    )) { "PixelTv ${BuildConfig.VERSION_NAME}" },
)

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
    val restore = remember { lastSection ?: SECTIONS.first().key }

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1720.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 36.dp),
        ) {
            item { PText("Настройки", 44.sp, t.colors.text) }
            item { PText("Выберите раздел · BACK — на главный экран", 15.sp, t.colors.dim, Modifier.padding(bottom = 22.dp)) }
            // Три колонки по реальной ширине экрана — плитки никогда не уезжают за край.
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 20.dp
                    val tileW = (maxWidth - gap * 2) / 3
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        SECTIONS.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                row.forEach { s ->
                                    SectionTile(s, ctx, tileW, autoFocus = s.key == restore) {
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

@Composable
private fun SectionTile(s: Section, ctx: SummaryCtx, width: Dp, autoFocus: Boolean, onClick: () -> Unit) {
    val t = LocalDen.current
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) fr.tryFocus() }
    val anim = if (t.animations) tween<Float>(180, easing = FastOutSlowInEasing) else snap()
    val p by animateFloatAsState(if (focused) 1f else 0f, anim, label = "tile")

    Row(
        Modifier
            .width(width)
            .focusRequester(fr)
            .tvClickable(onFocus = { focused = it }, onClick = onClick)
            .pixelBox(Color.Transparent, if (focused) t.colors.surfaceHi else t.colors.surface, t.border)
            .drawWithContent {
                drawContent()
                if (p > 0.01f) pixelFrame(t.colors.accent.copy(alpha = p), null, t.border.dp.toPx(), inflate = (1f - p) * 8.dp.toPx())
            }
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIcon(
            s.icon, 5,
            accent = if (focused) t.colors.accent else t.colors.text.copy(alpha = 0.75f),
            // Светлые детали без фокуса — приглушённый текст, а не цвет плитки: иначе они сливаются с фоном.
            light = if (focused) t.colors.text else t.colors.text.copy(alpha = 0.32f).compositeOver(t.colors.surface),
        )
        Column(Modifier.padding(start = 18.dp)) {
            PText(s.title, 20.sp, if (focused) t.colors.text else t.colors.text.copy(alpha = 0.85f))
            PText(s.summary(ctx), 14.sp, if (focused) t.colors.accent else t.colors.dim, Modifier.padding(top = 4.dp))
        }
    }
}

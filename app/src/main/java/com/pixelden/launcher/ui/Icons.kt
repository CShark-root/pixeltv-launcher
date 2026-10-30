package com.pixelden.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Иконки PixelTv: заполненные силуэты на сетке 16×16 («наклейки»). Символы:
 * '#' — основная заливка, 'd' — тёмная заливка, 'k' — чёрная деталь, 'w' — белая деталь, '.' — пусто.
 * Объём (светлый верх, тёмный низ) и обводка считаются при отрисовке — рисовать их в сетке не нужно.
 */
object Icons {
    val TV = listOf(
        ".##############.", "#dddddddddddddd#", "#dwwddddddddddd#", "#dwdddddddddddd#", "#dddddddddddddd#",
        "#dddddddddddddd#", "#dddddddddddddd#", "#dddddddddddddd#", "#dddddddddddddd#", "################",
        ".##############.", "......####......", "....########....",
    )
    val PHONE = listOf(
        "....########....", "...#dddddddd#...", "...#dwwddddd#...", "...#dwdddddd#...", "...#dddddddd#...",
        "...#dddddddd#...", "...#dddddddd#...", "...#dddddddd#...", "...#dddddddd#...", "...##########...",
        "...####kk####...", "....########....",
    )
    /** Щит: свой тёмный контур и четверти двух тонов крест-накрест. */
    val SHIELD: List<String> by lazy {
        val sil = listOf(
            ".......##.......", "..############..", ".##############.", ".##############.", ".##############.",
            ".##############.", ".##############.", ".##############.", ".##############.", "..############..",
            "...##########...", "....########....", ".....######.....", "......####......", ".......##.......",
        )
        fun s(x: Int, y: Int) = y in sil.indices && x in 0..15 && sil[y][x] == '#'
        sil.indices.map { y ->
            (0..15).joinToString("") { x ->
                when {
                    !s(x, y) -> "."
                    !s(x - 1, y) || !s(x + 1, y) || !s(x, y - 1) || !s(x, y + 1) -> "k"
                    (y <= 7) == (x < 8) -> "#"
                    else -> "d"
                }
            }
        }
    }
    val LOCK = listOf(
        ".....######.....", "....##....##....", "....##....##....", "....##....##....", "..############..",
        "..############..", "..############..", "..#####kk#####..", "..#####kk#####..", "..######k#####..",
        "..############..", "..############..",
    )
    val TIMER = listOf(
        "...##########...", "...##########...", "....#dddddd#....", "....#dwwwwd#....", ".....#dwwd#.....",
        "......#ww#......", "......#ww#......", ".....#dwwd#.....", "....#ddwwdd#....", "....#wwwwww#....",
        "...##########...", "...##########...",
    )
    val INFO = listOf(
        ".....######.....", "...##########...", "..######kk####..", ".#######kk#####.", ".##############.",
        "#######kk#######", "#######kk#######", "#######kk#######", "#######kk#######", ".######kk######.",
        ".######kk######.", "..############..", "...##########...", ".....######.....",
    )
    val BELL = listOf(
        ".......##.......", "......####......", ".....######.....", "....########....", "....########....",
        "....########....", "....########....", "...##########...", "..############..", "................",
        ".......##.......", ".......##.......",
    )
    val CALENDAR = listOf(
        "...##......##...", ".##############.", "#dddddddddddddd#", "#dddddddddddddd#", "################",
        "################", "###kk##kk##kk###", "################", "###kk##kk##ww###", "################",
        "###kk##kk##kk###", "################", ".##############.",
    )
    val COIN = listOf(
        ".....######.....", "...##########...", "..#####kk#####..", ".#####kkkkk####.", ".####kk########.",
        "#####kk#########", "######kkkk######", "######kkkk######", "#########kk#####", ".########kk####.",
        ".####kkkkk#####.", "..#####kk#####..", "...##########...", ".....######.....",
    )
    val MEMORY = listOf(
        ".##############.", "################", "##ddd##dd##ddd##", "##ddd##dd##ddd##", "##ddd##dd##ddd##",
        "################", "################", "#k#k#k#k#k#k#k##", ".##############.",
    )
    val TRAFFIC = listOf(
        "...#............", "..###....ddd....", ".#####...ddd....", "#######..ddd....", "..###....ddd....",
        "..###....ddd....", "..###....ddd....", "..###..ddddddd..", "..###...ddddd...", "..###....ddd....",
        "..###.....d.....",
    )
    val CLOUD = listOf(
        "......####......", ".....######.....", "..###########...", ".#############..", ".##############.",
        "################", "################", ".##############.",
    )
    val SUN = listOf(
        ".......##.......", ".......##.......", "..##...##...##..", "..##........##..", "......####......",
        ".....######.....", "....########....", "###.########.###", "###.########.###", "....########....",
        ".....######.....", "......####......", "..##........##..", "..##...##...##..", ".......##.......",
        ".......##.......",
    )
    val MOON = listOf(
        "....######......", "..#####.........", ".####...........", ".###............", "####............",
        "####............", "####............", "####........#...", ".####......##...", ".#######.####...",
        "..##########....", "....######......",
    )
    val NEWS = listOf(
        ".############...", "##dddddddddd##..", "##dddddddddd##..", "##############..", "##kkkkkkkkkk##..",
        "##############..", "##kkkkkkkkkk##..", "##############..", "##kkkkkk######..", "##############..",
        ".############...",
    )
    val HOME = listOf(
        ".......##.......", "......####......", ".....######.....", "....########....", "...##########...",
        "..############..", ".##############.", "################", "..############..", "..############..",
        "..####dddd####..", "..####dddd####..", "..####dddd####..",
    )
    val LOOK = listOf(
        "....#######.....", "..###########...", ".####ww##dd###..", "#####ww##dd####.", "################",
        "###dd######kk###", "###dd######kk###", "################", ".##############.", "..##########....",
        "....######......",
    )
    val MASCOT = listOf(
        ".#............#.", ".##..........##.", ".###........###.", ".##############.", "################",
        "################", "###wk######wk###", "###kk######kk###", "################", "#######kk#######",
        ".##############.", "..############..", "....########....",
    )
    val APPS = listOf(
        ".######..######.", ".######..######.", ".######..######.", ".######..######.", ".######..######.",
        ".######..######.", "................", "................", ".######..dddddd.", ".######..dddddd.",
        ".######..dddddd.", ".######..dddddd.", ".######..dddddd.", ".######..dddddd.",
    )
    val GEAR = listOf(
        "......####......", "..##..####..##..", "..##.######.##..", "...##########...", "..############..",
        "..############..", "#######kk#######", "######kkkk######", "######kkkk######", "#######kk#######",
        "..############..", "..############..", "...##########...", "..##.######.##..", "..##..####..##..",
        "......####......",
    )
    val BACKUP = listOf(
        "##############..", "###dddddddd###..", "###ddddddkd####.", "###dddddddd#####", "################",
        "##wwwwwwwwwwww##", "##wwkkkkkkkkww##", "##wwwwwwwwwwww##", "##wwkkkkkkwwww##", "################",
        "################",
    )
    val SIGNAL = listOf(
        ".............##.", ".............##.", ".............##.", ".........##..##.", ".........##..##.",
        ".........##..##.", ".....##..##..##.", ".....##..##..##.", ".....##..##..##.", ".##..##..##..##.",
        ".##..##..##..##.", ".##..##..##..##.",
    )
    /** Нет сети — те же столбики, но тёмные. */
    val SIGNAL_OFF = SIGNAL.map { it.replace('#', 'd') }
    /** Сеть по кабелю: узел и два устройства. */
    val ETHERNET = listOf(
        "......####......", "......####......", "......####......", ".......##.......", "..############..",
        "..##........##..", "..##........##..", ".####......####.", ".####......####.", ".####......####.",
    )
    val DOWNLOAD = listOf(
        "......####......", "......####......", "......####......", "......####......", "...##########...",
        "....########....", ".....######.....", "......####......", ".......##.......", "................",
        "dd............dd", "dddddddddddddddd", "dddddddddddddddd",
    )
    val TRASH = listOf(
        ".....######.....", ".##############.", ".##############.", "..############..", "..##k##kk##k##..",
        "..##k##kk##k##..", "..##k##kk##k##..", "..##k##kk##k##..", "..##k##kk##k##..", "..##k##kk##k##..",
        "..############..", "...##########...",
    )
}

private val OUTLINE = Color(0xFF0E0C12)
private val HALO = Color(0xFFF1EDF4)
private val MONO_MAIN = listOf(Color(0xFFF2EFF7), Color(0xFFBDB9CA), Color(0xFF7F7A92))
private val MONO_DARK = listOf(Color(0xFF6D6882), Color(0xFF4B475A), Color(0xFF36333F))

/** Сетка иконки → картинка 20×20 (поле в 2 точки под обводку). Обводка и акцентный цвет — только в фокусе. */
private fun render(grid: List<String>, focused: Boolean, accent: Color): android.graphics.Bitmap {
    // По вертикали иконка встаёт по центру клетки 16×16 — у всех одинаковая «посадка».
    val rows = grid.map { it.padEnd(16, '.').take(16) }
    val top = (16 - rows.size) / 2
    fun at(x: Int, y: Int): Char = rows.getOrNull(y - top)?.getOrNull(x) ?: '.'
    fun solid(x: Int, y: Int) = x in 0..15 && at(x, y) != '.'
    val main = if (focused) listOf(lerp(accent, Color.White, 0.55f), accent, lerp(accent, Color.Black, 0.35f)) else MONO_MAIN
    val dark = if (focused) listOf(lerp(accent, Color.Black, 0.2f), lerp(accent, Color.Black, 0.45f), lerp(accent, Color.Black, 0.6f)) else MONO_DARK
    val n = 20
    val px = IntArray(n * n)
    // Обводка: тёмная — вплотную к силуэту (с диагоналями), светлая — вокруг тёмной.
    val ring = Array(n) { IntArray(n) }
    if (focused) {
        for (y in 0 until n) for (x in 0 until n) {
            val gx = x - 2; val gy = y - 2
            if (solid(gx, gy)) continue
            if ((-1..1).any { dy -> (-1..1).any { dx -> solid(gx + dx, gy + dy) } }) ring[y][x] = 1
        }
        for (y in 0 until n) for (x in 0 until n) {
            if (ring[y][x] != 0 || solid(x - 2, y - 2)) continue
            if ((-1..1).any { dy -> (-1..1).any { dx -> ring.getOrNull(y + dy)?.getOrNull(x + dx) == 1 } }) ring[y][x] = 2
        }
    }
    for (y in 0 until n) for (x in 0 until n) {
        val gx = x - 2; val gy = y - 2
        val ch = if (gx in 0..15) at(gx, gy) else '.'
        val c: Color? = when {
            ring[y][x] == 1 -> OUTLINE
            ring[y][x] == 2 -> HALO
            ch == 'k' -> OUTLINE
            ch == 'w' -> Color.White
            ch == '#' || ch == 'd' -> {
                val p = if (ch == '#') main else dark
                // Край заливки — там, где кончается сама заливка (пусто или другая заливка). Чёрные и белые
                // детали внутри края не создают: иначе вокруг них появляются тёмные «вырезы».
                fun edge(c: Char) = c == '.' || ((c == '#' || c == 'd') && c != ch)
                when {
                    edge(at(gx, gy - 1)) -> p[0]
                    edge(at(gx, gy + 1)) -> p[2]
                    else -> p[1]
                }
            }
            else -> null
        }
        if (c != null) px[y * n + x] = c.toArgb()
    }
    return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * Иконка-«наклейка». [pixel] — размер одного пикселя иконки на экране; сама иконка — 20 таких пикселей.
 * В обычном виде — серый силуэт с объёмом, в фокусе — акцентный цвет темы с обводкой.
 */
@Composable
fun StickerIcon(grid: List<String>, focused: Boolean = false, modifier: Modifier = Modifier, pixel: Dp = 2.dp) {
    val accent = LocalDen.current.colors.accent
    val img = remember(grid, focused, accent) { render(grid, focused, accent).asImageBitmap() }
    Canvas(modifier.size(pixel * 20)) {
        drawImage(img, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
    }
}

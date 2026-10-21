package com.pixelden.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/*
 * Маскоты собираются из слоёв: тело (статичная сетка) + оверлеи глаз, рта, хвоста и клешней,
 * у каждого из которых несколько кадров. Поведения (моргание, взгляд, хвост, клешни, дыхание,
 * прыжок, сон) крутятся независимо, поэтому движения не повторяются по кругу.
 *
 * Символы палитры: K контур, o тело, d тень, l блик, w белый, p розовый, y тёмный акцент,
 * s полоски, n нос, i радужка, e зрачок, h блик в глазу. '.' — прозрачно (в оверлее — «не трогать»).
 */

private typealias Grid = List<String>

data class Layer(val x: Int, val y: Int, val frames: List<Grid>)

data class Mascot(
    val id: String,
    val name: String,
    val note: String,
    val body: Grid,
    val palette: Map<Char, Color>,
    val eyes: List<Pair<Int, Int>>,
    val mouth: Pair<Int, Int>?,
    val tail: Layer? = null,
    val clawLeft: Layer? = null,
    val clawRight: Layer? = null,
    /** Откуда вылетают «Z» во сне (в пикселях спрайта). */
    val zFrom: Pair<Int, Int>,
) {
    /** Ширина холста с учётом хвоста и клешней, которые торчат за контур тела. */
    val width = (listOf(body.maxOf { it.length }) +
        listOfNotNull(tail, clawLeft, clawRight).map { l -> l.x + l.frames.maxOf { f -> f.maxOf { it.length } } }).max()
    val height = body.size
}

// ---------------------------------------------------------------- кадры глаз (3×3) и рта

enum class Eyes { OPEN, LOOK_L, LOOK_R, HALF, CLOSED, HAPPY }

private val EYE_FRAMES: Map<Eyes, Grid> = mapOf(
    Eyes.OPEN to listOf("hei", "iei", "iii"),
    Eyes.LOOK_L to listOf("eih", "eii", "iii"),
    Eyes.LOOK_R to listOf("hie", "iie", "iii"),
    Eyes.HALF to listOf("ooo", "iei", "iii"),
    Eyes.CLOSED to listOf("ooo", "ooo", "KKK"),
    Eyes.HAPPY to listOf("ooo", "oKo", "KoK"),
)

private val MOUTH_CLOSED: Grid = listOf(".KK.")
private val MOUTH_OPEN: Grid = listOf("KppK", ".KK.")

// ---------------------------------------------------------------- кот

private val CAT_BODY: Grid = listOf(
    "..K............K..",
    "..KK..........KK..",
    "..KpK........KpK..",
    "..KppKKKKKKKKppK..",
    ".KooooooooooooooK.",
    ".KoosoooooooosooK.",
    "KooooooooooooooooK",
    "KooooooooooooooooK",
    "KooooooooooooooooK",
    "KooooooooooooooooK",
    "KwwooooonnooooowwK",
    ".KwwoooooooooowwK.",
    "..KKooooooooooKK..",
    "...KooooooooooK...",
    "..KoowwwwwwwwooK..",
    "..KoowwwwwwwwooK..",
    "..KsoowwwwwwoosK..",
    "..KooKwwwwwwKooK..",
    "..KwwKKKKKKKKwwK..",
    "...KK........KK...",
)

private val CAT_TAIL = Layer(
    15, 10, listOf(
        listOf("....K.", "...KoK", "...KoK", "..KoK.", "..KoK.", ".KoK..", "KoK...", "KK...."),
        listOf("......", "....KK", "...KoK", "..KoK.", ".KoK..", ".KoK..", "KoK...", "KK...."),
        listOf("......", "......", "....KK", "..KKoK", ".KooK.", "KoKK..", "KoK...", "KK...."),
    )
)

// ---------------------------------------------------------------- лис

private val FOX_BODY: Grid = listOf(
    "..K............K..",
    "..KK..........KK..",
    "..KyK........KyK..",
    "..KyyKKKKKKKKyyK..",
    ".KooooooooooooooK.",
    ".KllooooooooooooK.",
    "KooooooooooooooooK",
    "KooooooooooooooooK",
    "KooooooooooooooooK",
    "KwoooooooooooooowK",
    "KwwwoooonnoooowwwK",
    ".KwwwwwwwwwwwwwwK.",
    "..KKwwwwwwwwwwKK..",
    "...KooooooooooK...",
    "..KoowwwwwwwwooK..",
    "..KoowwwwwwwwooK..",
    "..KooowwwwwwoooK..",
    "..KyyKwwwwwwKyyK..",
    "..KyyKKKKKKKKyyK..",
    "...KK........KK...",
)

private val FOX_TAIL = Layer(
    15, 9, listOf(
        listOf("....KK.", "...KwwK", "..KwwwK", "..KoooK", ".KoooK.", ".KoooK.", "KoooK..", "KooK...", "KKK...."),
        listOf(".......", "....KKK", "...KwwK", "..KoowK", ".KoooK.", ".KoooK.", "KoooK..", "KooK...", "KKK...."),
        listOf(".......", ".......", ".......", "...KKKK", ".KKoowK", "KoooowK", "KoooKK.", "KooK...", "KKK...."),
    )
)

// ---------------------------------------------------------------- краб

private val CRAB_BODY: Grid = listOf(
    "......KKKKKKKKKKKK......",
    ".....KooooooooooooK.....",
    "....KoollooooooooooK....",
    "....KooooooooooooooK....",
    "...KooooooooooooooooK...",
    "...KooooooooooooooooK...",
    "...KooooooooooooooooK...",
    "...KooooooooooooooooK...",
    "...KooooooooooooooooK...",
    "....KddddddddddddddK....",
    ".....KKKKKKKKKKKKKK.....",
    "....K.K.K......K.K.K....",
    "...K.K.K........K.K.K...",
)

private val CLAW_DOWN: Grid = listOf(".....", ".....", "KKK..", "KoK..", "KoKKK", "KoooK")
private val CLAW_OPEN: Grid = listOf(".....", "KK.KK", "KoKoK", ".KooK", "..KKK", "...KK")
private val CLAW_UP: Grid = listOf("KK...", "KoK..", "KoKK.", "KooK.", ".KoK.", "..KoK")
private fun Grid.mirror(): Grid = map { it.reversed() }

private val CRAB_CLAW_L = Layer(0, 2, listOf(CLAW_DOWN, CLAW_OPEN, CLAW_UP))
private val CRAB_CLAW_R = Layer(19, 2, listOf(CLAW_DOWN.mirror(), CLAW_OPEN.mirror(), CLAW_UP.mirror()))

// ---------------------------------------------------------------- набор

private fun c(hex: Long) = Color(hex)

private fun catPalette(body: Long, stripe: Long, iris: Long, chest: Long = 0xFFF6F1EA, outline: Long = 0xFF1D1A22) = mapOf(
    'K' to c(outline), 'o' to c(body), 's' to c(stripe), 'w' to c(chest),
    'p' to c(0xFFE89AA6), 'n' to c(0xFFE07A8A), 'i' to c(iris), 'e' to c(0xFF121014), 'h' to c(0xFFFFFFFF),
)

val MASCOTS = listOf(
    Mascot(
        "pinch", "Pinch", "Оранжевый пиксельный краб (в духе Claude)",
        CRAB_BODY,
        mapOf(
            'K' to c(0xFF3A1F18), 'o' to c(0xFFD97757), 'd' to c(0xFFB35A3C), 'l' to c(0xFFF2A585),
            'i' to c(0xFF1B1A1F), 'e' to c(0xFF1B1A1F), 'h' to c(0xFFFFFFFF), 'p' to c(0xFF8E3A2A),
        ),
        eyes = listOf(8 to 4, 13 to 4), mouth = 10 to 7,
        clawLeft = CRAB_CLAW_L, clawRight = CRAB_CLAW_R,
        zFrom = 18 to 0,
    ),
    Mascot(
        "ember", "Ember", "Огненный лис (в духе Kit из Firefox)",
        FOX_BODY,
        mapOf(
            'K' to c(0xFF2A160E), 'o' to c(0xFFF2762E), 'l' to c(0xFFFFA25E), 'y' to c(0xFF5A2410),
            'w' to c(0xFFFFF4E6), 'n' to c(0xFF2A160E), 'i' to c(0xFF7A3B10), 'e' to c(0xFF120A06),
            'h' to c(0xFFFFFFFF), 'p' to c(0xFFE07A8A),
        ),
        eyes = listOf(4 to 7, 11 to 7), mouth = 7 to 11,
        tail = FOX_TAIL, zFrom = 15 to 0,
    ),
    Mascot(
        "sprocket", "Sprocket", "Серый полосатый кот",
        CAT_BODY, catPalette(0xFF8E8E99, 0xFF5B5B66, 0xFF7FB24A),
        eyes = listOf(4 to 7, 11 to 7), mouth = 7 to 11, tail = CAT_TAIL, zFrom = 15 to 0,
    ),
    Mascot(
        "nyx", "Nyx", "Чёрный кот с зелёными глазами",
        CAT_BODY, catPalette(0xFF2E2B35, 0xFF26232C, 0xFFB8E05A, chest = 0xFF3C3844, outline = 0xFF0C0B0F),
        eyes = listOf(4 to 7, 11 to 7), mouth = 7 to 11, tail = CAT_TAIL, zFrom = 15 to 0,
    ),
    Mascot(
        "mango", "Mango", "Рыжий кот",
        CAT_BODY, catPalette(0xFFE8913A, 0xFFB9621B, 0xFF4E9A3A),
        eyes = listOf(4 to 7, 11 to 7), mouth = 7 to 11, tail = CAT_TAIL, zFrom = 15 to 0,
    ),
    Mascot(
        "frost", "Frost", "Белый кот с голубыми глазами",
        CAT_BODY, catPalette(0xFFEDEDF2, 0xFFD9D9E2, 0xFF4C9BE8, chest = 0xFFFFFFFF, outline = 0xFF5A5866),
        eyes = listOf(4 to 7, 11 to 7), mouth = 7 to 11, tail = CAT_TAIL, zFrom = 15 to 0,
    ),
)

fun mascotById(id: String) = MASCOTS.firstOrNull { it.id == id } ?: MASCOTS.first()

enum class MascotMood { IDLE, SLEEP, HAPPY }

/** Мгновенное состояние всех слоёв. */
private data class Pose(
    val eyes: Eyes = Eyes.OPEN,
    val mouthOpen: Boolean = false,
    val tail: Int = 0,
    val clawL: Int = 0,
    val clawR: Int = 0,
    val dy: Float = 0f,
    val squash: Float = 1f,
    val zs: List<Pair<Float, Float>> = emptyList(),
)

/**
 * Рисует маскота. [pixel] — размер пикселя спрайта в dp.
 * [jumpKey] — увеличьте, чтобы маскот обрадовался и подпрыгнул. [talking] — шевелит ртом.
 */
@Composable
fun MascotSprite(
    mascot: Mascot,
    pixel: Int,
    mood: MascotMood,
    animate: Boolean,
    jumpKey: Int = 0,
    talking: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var pose by remember(mascot.id) { mutableStateOf(Pose()) }
    var excited by remember { mutableStateOf(false) }

    // Основные поведения. Каждое — своя корутина со своим ритмом.
    LaunchedEffect(mascot.id, mood, animate) {
        pose = Pose(eyes = if (mood == MascotMood.SLEEP) Eyes.CLOSED else Eyes.OPEN)
        if (!animate) return@LaunchedEffect
        coroutineScope {
            // Дыхание: медленное сжатие по синусу.
            launch {
                var t = 0f
                while (isActive) {
                    val period = if (mood == MascotMood.SLEEP) 3200f else 2200f
                    val depth = if (mood == MascotMood.SLEEP) 0.05f else 0.03f
                    if (!excited) pose = pose.copy(squash = 1f - depth * ((sin(t / period * 2 * PI).toFloat() + 1f) / 2f))
                    delay(80); t += 80f
                }
            }
            if (mood == MascotMood.SLEEP) {
                // Сон: поднимающиеся «Z».
                launch {
                    while (isActive) {
                        val moved = pose.zs.map { (age, x) -> (age + 0.05f) to x }.filter { it.first < 1f }
                        val spawn = if (Random.nextInt(24) == 0 && moved.size < 3) listOf(0f to Random.nextFloat()) else emptyList()
                        pose = pose.copy(zs = moved + spawn, eyes = Eyes.CLOSED)
                        delay(110)
                    }
                }
                return@coroutineScope
            }
            // Моргание в несколько фаз, иногда двойное.
            launch {
                while (isActive) {
                    delay(Random.nextLong(2200, 6000))
                    if (excited) continue
                    repeat(if (Random.nextInt(5) == 0) 2 else 1) {
                        val keep = pose.eyes
                        for (e in listOf(Eyes.HALF, Eyes.CLOSED, Eyes.HALF)) { pose = pose.copy(eyes = e); delay(55) }
                        pose = pose.copy(eyes = if (keep == Eyes.HALF || keep == Eyes.CLOSED) Eyes.OPEN else keep)
                        delay(120)
                    }
                }
            }
            // Взгляд по сторонам.
            launch {
                while (isActive) {
                    delay(Random.nextLong(3500, 9000))
                    if (excited) continue
                    val dir = if (Random.nextBoolean()) Eyes.LOOK_L else Eyes.LOOK_R
                    pose = pose.copy(eyes = dir)
                    delay(Random.nextLong(700, 1800))
                    if (Random.nextInt(3) == 0) {
                        pose = pose.copy(eyes = if (dir == Eyes.LOOK_L) Eyes.LOOK_R else Eyes.LOOK_L)
                        delay(Random.nextLong(500, 1200))
                    }
                    if (pose.eyes == Eyes.LOOK_L || pose.eyes == Eyes.LOOK_R) pose = pose.copy(eyes = Eyes.OPEN)
                }
            }
            // Хвост: серии виляний с паузами.
            mascot.tail?.let { tail ->
                launch {
                    val cycle = listOf(0, 1, 2, 1)
                    while (isActive) {
                        val wags = Random.nextInt(4, 12)
                        val speed = Random.nextLong(110, 220)
                        repeat(wags) { i -> pose = pose.copy(tail = cycle[i % cycle.size] % tail.frames.size); delay(speed) }
                        pose = pose.copy(tail = 0)
                        delay(Random.nextLong(1500, 5000))
                    }
                }
            }
            // Клешни: щёлкнуть или помахать.
            if (mascot.clawLeft != null) {
                launch {
                    while (isActive) {
                        delay(Random.nextLong(2500, 7000))
                        if (excited) continue
                        when (Random.nextInt(3)) {
                            0 -> repeat(3) { pose = pose.copy(clawL = 1, clawR = 1); delay(120); pose = pose.copy(clawL = 0, clawR = 0); delay(120) }
                            1 -> repeat(4) { pose = pose.copy(clawR = 2); delay(200); pose = pose.copy(clawR = 0); delay(160) }
                            else -> { pose = pose.copy(clawL = 2); delay(500); pose = pose.copy(clawL = 1); delay(150); pose = pose.copy(clawL = 0) }
                        }
                    }
                }
            }
            // Иногда маленький подскок на месте.
            launch {
                while (isActive) {
                    delay(Random.nextLong(9000, 20000))
                    if (excited) continue
                    pose = pose.copy(dy = -1f); delay(120); pose = pose.copy(dy = 0f)
                }
            }
        }
    }

    // Рот, пока маскот «говорит».
    LaunchedEffect(talking, animate) {
        if (!talking || !animate) { pose = pose.copy(mouthOpen = false); return@LaunchedEffect }
        while (isActive) {
            pose = pose.copy(mouthOpen = !pose.mouthOpen)
            delay(Random.nextLong(110, 200))
        }
    }

    // Радость: присесть → прыжок с растяжением → приземление → глаза ^^.
    LaunchedEffect(jumpKey) {
        if (jumpKey == 0 || !animate || mood == MascotMood.SLEEP) return@LaunchedEffect
        excited = true
        val claws = mascot.clawLeft != null
        pose = pose.copy(squash = 0.82f, eyes = Eyes.HAPPY); delay(110)
        for (s in 1..6) { pose = pose.copy(dy = -s.toFloat(), squash = 1.12f, clawL = if (claws) 2 else 0, clawR = if (claws) 2 else 0); delay(28) }
        delay(60)
        for (s in 5 downTo 0) { pose = pose.copy(dy = -s.toFloat(), squash = 1.05f); delay(32) }
        pose = pose.copy(squash = 0.85f); delay(90)
        pose = pose.copy(squash = 1f, clawL = 0, clawR = 0); delay(900)
        pose = pose.copy(eyes = Eyes.OPEN)
        excited = false
    }

    val headroom = 8
    Canvas(modifier.size((mascot.width * pixel).dp, ((mascot.height + headroom) * pixel).dp)) {
        val px = pixel.dp.toPx()
        val p = pose
        val baseTop = headroom * px
        withTransform({
            translate(top = p.dy * px)
            // Сжатие от «пола», чтобы маскот не отрывался от земли при дыхании.
            scale(scaleX = 2f - p.squash, scaleY = p.squash, pivot = Offset(size.width / 2, size.height))
        }) {
            mascot.tail?.let { drawGrid(it.frames[p.tail % it.frames.size], it.x, it.y, mascot.palette, px, baseTop) }
            drawGrid(mascot.body, 0, 0, mascot.palette, px, baseTop)
            mascot.clawLeft?.let { drawGrid(it.frames[p.clawL % it.frames.size], it.x, it.y, mascot.palette, px, baseTop) }
            mascot.clawRight?.let { drawGrid(it.frames[p.clawR % it.frames.size], it.x, it.y, mascot.palette, px, baseTop) }
            val eye = EYE_FRAMES.getValue(p.eyes)
            for ((ex, ey) in mascot.eyes) drawGrid(eye, ex, ey, mascot.palette, px, baseTop)
            mascot.mouth?.let { (mx, my) -> drawGrid(if (p.mouthOpen) MOUTH_OPEN else MOUTH_CLOSED, mx, my, mascot.palette, px, baseTop) }
        }
        // «Z» во сне летят вверх-вправо и тают.
        val zColor = mascot.palette['h'] ?: Color.White
        for ((age, jitter) in p.zs) {
            val zx = (mascot.zFrom.first + age * 4 + jitter * 2) * px
            val zy = baseTop + (mascot.zFrom.second - age * 7) * px
            val big = age > 0.45f
            drawZ(zx, zy, px * (if (big) 1f else 0.7f), zColor.copy(alpha = (1f - age).coerceIn(0f, 1f)))
        }
    }
}

private fun DrawScope.drawGrid(grid: Grid, ox: Int, oy: Int, palette: Map<Char, Color>, px: Float, top: Float) {
    grid.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            if (ch == '.') return@forEachIndexed
            val color = palette[ch] ?: return@forEachIndexed
            // +0.5px перекрытие убирает щели между пикселями на дробной плотности.
            drawRect(color, Offset((ox + x) * px, top + (oy + y) * px), Size(px + 0.5f, px + 0.5f))
        }
    }
}

private val Z_GLYPH = listOf("KKK", "..K", ".K.", "K..", "KKK")

private fun DrawScope.drawZ(x: Float, y: Float, px: Float, color: Color) {
    Z_GLYPH.forEachIndexed { r, row ->
        row.forEachIndexed { c, ch ->
            if (ch == 'K') drawRect(color, Offset(x + c * px, y + r * px), Size(px + 0.5f, px + 0.5f))
        }
    }
}

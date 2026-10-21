package com.pixelden.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Пиксельный спрайт. Символы сетки:
 *  '.' — прозрачно, 'e' — верхний пиксель глаза, 'E' — нижний пиксель глаза.
 *  Остальные символы берутся из палитры. При моргании и во сне 'e' закрашивается
 *  цветом тела ('o'), а 'E' остаётся — получается закрытый глаз-чёрточка.
 */
data class Mascot(
    val id: String,
    val name: String,
    val note: String,
    val grid: List<String>,
    val palette: Map<Char, Color>,
) {
    val width = grid.maxOf { it.length }
    val height = grid.size
}

private val CLAUDE_GRID = listOf(
    "..oooooooooo..",
    "..oooooooooo..",
    "..ooeooooeoo..",
    "ooooEooooEoooo",
    "oooooooooooooo",
    "..oooooooooo..",
    "..oooooooooo..",
    "..o.o....o.o..",
    "..o.o....o.o..",
)

private val FOX_GRID = listOf(
    "o..........o",
    "oo........oo",
    "oyo......oyo",
    "oyooooooooyo",
    "oooooooooooo",
    "ooeooooooeoo",
    "wwEooooooEww",
    "wwwwoooowwww",
    ".wwwwkkwwww.",
    "..wwwwwwww..",
    "...wwwwww...",
)

private val CAT_GRID = listOf(
    ".o..........o.",
    ".oo........oo.",
    ".opo......opo.",
    ".oooooooooooo.",
    ".oosoooooosoo.",
    ".ooeooooooeoo.",
    ".ooEooooooEoo.",
    ".oooooppooooo.",
    "h.oooooooooo.h",
    "..oooooooooo..",
    ".oosoooooosoo.",
    ".oooooooooooo.",
    ".oo.oo..oo.oo.",
)

private fun c(hex: Long) = Color(hex)

val MASCOTS = listOf(
    Mascot(
        "pinch", "Pinch", "Оранжевый пиксельный краб (в духе Claude)",
        CLAUDE_GRID,
        mapOf('o' to c(0xFFD97757), 'e' to c(0xFF1B1A1F), 'E' to c(0xFF1B1A1F)),
    ),
    Mascot(
        "ember", "Ember", "Огненный лис (в духе Kit из Firefox)",
        FOX_GRID,
        mapOf(
            'o' to c(0xFFF2762E), 'y' to c(0xFF7A2E12), 'w' to c(0xFFFFF4E6),
            'k' to c(0xFF2A1A14), 'e' to c(0xFF2A1A14), 'E' to c(0xFF2A1A14),
        ),
    ),
    Mascot(
        "sprocket", "Sprocket", "Серый полосатый кот",
        CAT_GRID,
        mapOf(
            'o' to c(0xFF8E8E99), 's' to c(0xFF5B5B66), 'p' to c(0xFFE89AA6),
            'h' to c(0xFFD6D6DE), 'e' to c(0xFF1A1A1A), 'E' to c(0xFF1A1A1A),
        ),
    ),
    Mascot(
        "nyx", "Nyx", "Чёрный кот с зелёными глазами",
        CAT_GRID,
        mapOf(
            'o' to c(0xFF2E2B35), 's' to c(0xFF2E2B35), 'p' to c(0xFFB46A7A),
            'h' to c(0xFF8A8794), 'e' to c(0xFFB8E05A), 'E' to c(0xFFB8E05A),
        ),
    ),
    Mascot(
        "mango", "Mango", "Рыжий кот",
        CAT_GRID,
        mapOf(
            'o' to c(0xFFE8913A), 's' to c(0xFFB9621B), 'p' to c(0xFFF2A3A3),
            'h' to c(0xFFFFE2C2), 'e' to c(0xFF2A1A0E), 'E' to c(0xFF2A1A0E),
        ),
    ),
    Mascot(
        "frost", "Frost", "Белый кот с голубыми глазами",
        CAT_GRID,
        mapOf(
            'o' to c(0xFFEDEDF2), 's' to c(0xFFEDEDF2), 'p' to c(0xFFF4A7B9),
            'h' to c(0xFFB8B8C4), 'e' to c(0xFF4C9BE8), 'E' to c(0xFF4C9BE8),
        ),
    ),
)

fun mascotById(id: String) = MASCOTS.firstOrNull { it.id == id } ?: MASCOTS.first()

enum class MascotMood { IDLE, SLEEP, HAPPY }

/**
 * Рисует маскота. [pixel] — размер одного пикселя спрайта в dp.
 * [jumpKey] — увеличивайте, чтобы маскот подпрыгнул (например, при запуске приложения).
 */
@Composable
fun MascotSprite(
    mascot: Mascot,
    pixel: Int,
    mood: MascotMood,
    animate: Boolean,
    jumpKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    var blink by remember { mutableStateOf(false) }
    var bob by remember { mutableIntStateOf(0) }
    val jump = remember { Animatable(0f) }

    LaunchedEffect(animate, mood) {
        if (!animate) { bob = 0; blink = false; return@LaunchedEffect }
        while (true) {
            delay(if (mood == MascotMood.SLEEP) 1400 else 650)
            bob = if (bob == 0) 1 else 0
            if (mood != MascotMood.SLEEP && Random.nextInt(6) == 0) {
                blink = true; delay(140); blink = false
            }
        }
    }
    LaunchedEffect(jumpKey) {
        if (jumpKey == 0 || !animate) return@LaunchedEffect
        jump.animateTo(-3f, tween(140))
        jump.animateTo(0f, tween(220))
    }

    val eyesClosed = blink || mood == MascotMood.SLEEP
    val density = LocalDensity.current
    val px = with(density) { pixel.dp.toPx() }
    val dy = (if (mood == MascotMood.SLEEP) 0 else bob) + jump.value

    Box(modifier.size((mascot.width * pixel).dp, ((mascot.height + 2) * pixel).dp)) {
        Canvas(
            Modifier
                .size((mascot.width * pixel).dp, (mascot.height * pixel).dp)
                .offset { IntOffset(0, ((dy + 1) * px).toInt()) }
        ) {
            val body = mascot.palette['o'] ?: Color.White
            mascot.grid.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch ->
                    if (ch == '.') return@forEachIndexed
                    val color = when {
                        ch == 'e' && eyesClosed -> body
                        else -> mascot.palette[ch] ?: body
                    }
                    // +0.5px перекрытие убирает щели между пикселями на дробной плотности.
                    drawRect(color, Offset(x * px, y * px), Size(px + 0.5f, px + 0.5f))
                }
            }
        }
    }
}

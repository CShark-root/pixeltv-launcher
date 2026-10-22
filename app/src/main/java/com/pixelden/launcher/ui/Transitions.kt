package com.pixelden.launcher.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

val SCREEN_TRANSITIONS = listOf(
    "pixels" to "Пиксели",
    "fade" to "Растворение",
    "none" to "Без перехода",
)

/** Переход «растворение»: новый экран проявляется с лёгким приближением, старый быстро гаснет. */
fun <S> AnimatedContentTransitionScope<S>.softTransition(mode: String): ContentTransform =
    if (mode == "fade") {
        (fadeIn(tween(240, delayMillis = 60, easing = FastOutSlowInEasing)) +
            scaleIn(tween(300, easing = FastOutSlowInEasing), initialScale = 0.96f)) togetherWith
            (fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 1.02f))
    } else {
        EnterTransition.None togetherWith ExitTransition.None
    }

/**
 * Пиксельная шторка: экран закрывается квадратами в случайном порядке и так же открывается.
 * [progress] 0 — ничего не закрыто, 1 — экран закрыт целиком. [seed] меняет порядок квадратов от раза к разу.
 */
@Composable
fun PixelCoverOverlay(progress: Float, seed: Int, color: Color) {
    if (progress <= 0f) return
    val cols = 32
    val rows = 18
    val order = remember(seed) {
        val r = Random(seed)
        FloatArray(cols * rows) { r.nextFloat() }
    }
    Canvas(Modifier.fillMaxSize()) {
        val cw = size.width / cols
        val ch = size.height / rows
        for (i in order.indices) {
            if (order[i] >= progress) continue
            val x = i % cols
            val y = i / cols
            drawRect(color, Offset(x * cw, y * ch), Size(cw + 1f, ch + 1f))
        }
    }
}

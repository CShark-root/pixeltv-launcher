package com.pixelden.launcher.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Прокрутка «с запасом»: пока элемент в фокусе внутри центральной зоны, экран стоит на месте.
 * Сдвиг начинается, только когда фокус подходит к краю ближе чем на [edge] размера экрана,
 * и экран сдвигается ровно настолько, чтобы элемент вернулся на эту границу.
 * (Стандартное поведение Compose на ТВ держит фокус в фиксированной точке — экран «прилипает» к курсору.)
 */
@OptIn(ExperimentalFoundationApi::class)
class EdgeScrollSpec(private val edge: Float, animate: Boolean) : BringIntoViewSpec {

    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        tween(if (animate) 320 else 0, easing = FastOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * edge
        val end = offset + size
        return when {
            // элемент крупнее свободной зоны — просто выровнять по началу зоны
            size > containerSize - 2 * margin -> offset - margin
            offset < margin -> offset - margin
            end > containerSize - margin -> end - (containerSize - margin)
            else -> 0f
        }
    }
}

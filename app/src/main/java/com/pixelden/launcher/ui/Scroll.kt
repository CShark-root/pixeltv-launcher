package com.pixelden.launcher.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Прокрутка «с запасом» и крупными шагами.
 *
 * Пока элемент в фокусе внутри зоны [edge]…(1 − [edge]) экрана, ничего не двигается. Когда фокус
 * заходит за край зоны, экран сдвигается сразу так, чтобы элемент встал на [land] от этого края —
 * так сдвигов в несколько раз меньше, чем при «подтягивании» на пару пикселей за нажатие.
 * Двигаясь вверх, список упирается в начало — поэтому к шапке он возвращается до самого верха.
 *
 * Анимация — быстрая пружина без отскока: при повторном нажатии во время сдвига она плавно
 * перенацеливается, а не начинает заново (с tween это и давало рывки).
 */
@OptIn(ExperimentalFoundationApi::class)
class EdgeScrollSpec(
    private val edge: Float = 0.15f,
    private val land: Float = 0.30f,
    animate: Boolean = true,
) : BringIntoViewSpec {

    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        if (animate) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1100f) else snap()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = containerSize * edge
        val end = containerSize * (1 - edge)
        val itemEnd = offset + size
        return when {
            // элемент крупнее зоны — показать его начало у верхней границы зоны
            size > end - start -> offset - start
            // ушёл за верхний край зоны — поставить его на [land] от верха
            offset < start -> offset - containerSize * land
            // ушёл за нижний край — поставить его конец на [land] от низа
            itemEnd > end -> itemEnd - containerSize * (1 - land)
            else -> 0f
        }
    }
}

package com.pixelden.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/** Ключ карточки в фокусе — нужен эффекту «затемнение остальных». */
object CardFocus {
    var key by mutableStateOf<String?>(null)
}

/** Ореол вокруг карточки в фокусе: несколько расходящихся полупрозрачных пиксельных рамок. */
fun Modifier.focusGlow(active: Boolean, color: Color): Modifier = if (!active) this else drawBehind {
    val step = 3.dp.toPx()
    for (k in 1..5) {
        val inset = -step * k
        val a = 0.22f / k
        drawRect(color.copy(alpha = a), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset))
    }
}

/**
 * Картинка карточки с эффектом фокуса. Рисует баннер на весь блок (или иконку по центру, если баннера нет).
 */
@Composable
fun CardImage(image: ImageBitmap, isBanner: Boolean, effects: Set<String>, focused: Boolean, accent: Color) {
    // Пиксельное проявление: заранее ужатые копии, которые сменяют друг друга при получении фокуса.
    val pixelSteps = remember(image, "pixel" in effects) {
        if ("pixel" !in effects) emptyList() else listOf(16, 8, 4).map { k ->
            val src = image.asAndroidBitmap()
            Bitmap.createScaledBitmap(src, maxOf(1, src.width / k), maxOf(1, src.height / k), false).asImageBitmap()
        }
    }
    var pixelStage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focused, effects) {
        if ("pixel" in effects && focused) {
            for (i in pixelSteps.indices) { pixelStage = i; delay(80) }
        }
        pixelStage = -1
    }

    // Глюк: короткие всплески RGB-расслоения и сдвигов полос, потом пауза.
    var glitchX by remember { mutableIntStateOf(0) }
    var glitchBars by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    LaunchedEffect(focused, effects) {
        glitchX = 0; glitchBars = emptyList()
        if ("glitch" !in effects || !focused) return@LaunchedEffect
        while (isActive) {
            repeat(Random.nextInt(3, 7)) {
                glitchX = Random.nextInt(-4, 5)
                glitchBars = List(Random.nextInt(1, 4)) { Random.nextFloat() to Random.nextFloat() * 0.08f + 0.02f }
                delay(Random.nextLong(50, 110))
            }
            glitchX = 0; glitchBars = emptyList()
            delay(Random.nextLong(900, 2200))
        }
    }

    // ЭЛТ: светлая полоса медленно «бежит» сверху вниз.
    var crtRoll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(focused, effects) {
        if ("crt" !in effects || !focused) return@LaunchedEffect
        while (isActive) {
            crtRoll = (crtRoll + 0.012f) % 1.2f
            delay(33)
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val stage = pixelStage
        val img = if (stage >= 0 && stage < pixelSteps.size) pixelSteps[stage] else image
        val quality = if (img === image) FilterQuality.Medium else FilterQuality.None

        fun put(bmp: ImageBitmap, dx: Float = 0f, filter: ColorFilter? = null, alpha: Float = 1f) {
            if (isBanner) {
                // Обрезка по центру под 16:9 (как ContentScale.Crop).
                val scale = maxOf(size.width / bmp.width, size.height / bmp.height)
                val sw = (size.width / scale).toInt().coerceAtMost(bmp.width)
                val sh = (size.height / scale).toInt().coerceAtMost(bmp.height)
                drawImage(
                    bmp,
                    srcOffset = IntOffset((bmp.width - sw) / 2, (bmp.height - sh) / 2),
                    srcSize = IntSize(sw, sh),
                    dstOffset = IntOffset(dx.toInt(), 0),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha = alpha, colorFilter = filter, filterQuality = quality,
                )
            } else {
                val side = size.height * 0.55f
                drawImage(
                    bmp,
                    srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(((size.width - side) / 2 + dx).toInt(), ((size.height - side) / 2).toInt()),
                    dstSize = IntSize(side.toInt(), side.toInt()),
                    alpha = alpha, colorFilter = filter, filterQuality = quality,
                )
            }
        }

        val shift = glitchX * 1.5f.dp.toPx()
        if ("glitch" in effects && focused && glitchX != 0) {
            put(img, -shift, ColorFilter.tint(Color(0xFFFF3B5C), BlendMode.Modulate), 0.75f)
            put(img, shift, ColorFilter.tint(Color(0xFF3BE8FF), BlendMode.Modulate), 0.75f)
            put(img, 0f, alpha = 0.7f)
            for ((y, h) in glitchBars) {
                drawRect(Color.White.copy(alpha = 0.18f), Offset(0f, y * size.height), Size(size.width, h * size.height))
                drawRect(accent.copy(alpha = 0.25f), Offset(shift * 2, y * size.height + 2f), Size(size.width, 2f))
            }
        } else {
            put(img)
        }

        if ("crt" in effects && focused) scanlines(crtRoll)
    }
}

private fun DrawScope.scanlines(roll: Float) {
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(Color.Black.copy(alpha = 0.28f), Offset(0f, y), Size(size.width, step / 2))
        y += step
    }
    val bandY = (roll - 0.1f) * size.height
    drawRect(Color.White.copy(alpha = 0.08f), Offset(0f, bandY), Size(size.width, size.height * 0.12f))
}

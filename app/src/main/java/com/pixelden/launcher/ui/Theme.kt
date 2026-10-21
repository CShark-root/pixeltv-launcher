package com.pixelden.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.R
import com.pixelden.launcher.data.LauncherConfig

data class DenColors(
    val accent: Color,
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val text: Color,
    val dim: Color,
)

data class DenTheme(
    val colors: DenColors,
    val font: FontFamily,
    val fontScale: Float,
    val animations: Boolean,
    val focusScale: Float,
    val border: Int,
)

val FONTS = listOf(
    "tiny5" to "Tiny5",
    "handjet" to "Handjet",
    "press" to "Press Start 2P",
    "pixelify" to "Pixelify Sans",
)

val ACCENTS = listOf(
    0xFFD97757, 0xFFF2762E, 0xFFE8C547, 0xFF8BD450,
    0xFF4FD1C5, 0xFF4C9BE8, 0xFF9B7BF2, 0xFFF06292, 0xFFEDEDF2,
)

val BACKGROUNDS = listOf(
    0xFF14121A, 0xFF000000, 0xFF0E1726, 0xFF101C14, 0xFF1E1420, 0xFF1A1A1A, 0xFF231A12,
)

private val pixelify = FontFamily(Font(R.font.pixelify_sans))
private val tiny5 = FontFamily(Font(R.font.tiny5))
private val press = FontFamily(Font(R.font.press_start_2p))

// Handjet — переменный шрифт; берём полужирное начертание, оно читается на ТВ лучше всего.
@OptIn(ExperimentalTextApi::class)
private val handjet = FontFamily(
    Font(R.font.handjet, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
)

fun themeFrom(cfg: LauncherConfig): DenTheme {
    val bg = Color(cfg.background)
    val surface = Color(
        red = (bg.red + 0.06f).coerceAtMost(1f),
        green = (bg.green + 0.06f).coerceAtMost(1f),
        blue = (bg.blue + 0.07f).coerceAtMost(1f),
    )
    val surfaceHi = Color(
        red = (bg.red + 0.12f).coerceAtMost(1f),
        green = (bg.green + 0.12f).coerceAtMost(1f),
        blue = (bg.blue + 0.13f).coerceAtMost(1f),
    )
    val (font, scale) = when (cfg.font) {
        "handjet" -> handjet to 1.2f
        // Press Start очень широкий — ужимаем, чтобы подписи помещались.
        "press" -> press to 0.62f
        "pixelify" -> pixelify to 1f
        else -> tiny5 to 1.05f
    }
    return DenTheme(
        colors = DenColors(
            accent = Color(cfg.accent), bg = bg, surface = surface, surfaceHi = surfaceHi,
            text = Color(0xFFF4F1EA), dim = Color(0xFF9A96A6),
        ),
        font = font,
        fontScale = scale,
        animations = cfg.animations,
        focusScale = 1f + cfg.focusScale / 100f,
        border = cfg.pixelBorder,
    )
}

val LocalDen = staticCompositionLocalOf { themeFrom(LauncherConfig()) }

@Composable
fun DenThemeProvider(theme: DenTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDen provides theme, content = content)
}

@Composable
fun PText(
    text: String,
    size: TextUnit = 18.sp,
    color: Color = LocalDen.current.colors.text,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val t = LocalDen.current
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = color, fontFamily = t.font, fontSize = size * t.fontScale),
    )
}

/** Рамка с «ступенчатыми» пиксельными углами. */
fun DrawScope.pixelFrame(color: Color, fill: Color?, step: Float) {
    val w = size.width
    val h = size.height
    if (fill != null) {
        drawRect(fill, Offset(step, 0f), Size(w - 2 * step, h))
        drawRect(fill, Offset(0f, step), Size(w, h - 2 * step))
    }
    if (color.alpha > 0f) {
        drawRect(color, Offset(step, 0f), Size(w - 2 * step, step))
        drawRect(color, Offset(step, h - step), Size(w - 2 * step, step))
        drawRect(color, Offset(0f, step), Size(step, h - 2 * step))
        drawRect(color, Offset(w - step, step), Size(step, h - 2 * step))
    }
}

fun Modifier.pixelBox(border: Color, fill: Color?, stepDp: Int = 3): Modifier = drawBehind {
    pixelFrame(border, fill, stepDp.dp.toPx())
}

private fun isSelectKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER ||
    code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
    code == KeyEvent.KEYCODE_BUTTON_A

/**
 * Фокусируемый элемент для пульта: короткое нажатие OK — [onClick],
 * удержание OK — [onLongClick]. Состояние фокуса отдаётся в [onFocus].
 */
fun Modifier.tvClickable(
    onFocus: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onKey: ((Int) -> Boolean)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    var longFired by remember { mutableStateOf(false) }
    var downSeen by remember { mutableStateOf(false) }
    this
        .onFocusChanged { onFocus?.invoke(it.isFocused) }
        .onPreviewKeyEvent { e ->
            val native = e.nativeKeyEvent
            if (e.type == KeyEventType.KeyDown && onKey != null && !isSelectKey(native.keyCode)) {
                return@onPreviewKeyEvent onKey(native.keyCode)
            }
            if (!isSelectKey(native.keyCode)) return@onPreviewKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> {
                    if (native.repeatCount == 0) {
                        longFired = false
                        downSeen = true
                    } else if (!longFired && onLongClick != null &&
                        (native.isLongPress || native.eventTime - native.downTime >= 550)
                    ) {
                        longFired = true
                        onLongClick()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    // Отпускание без нажатия (например, OK держали ещё на прошлом экране) игнорируем.
                    if (downSeen && !longFired) onClick()
                    downSeen = false
                    longFired = false
                    true
                }
                else -> false
            }
        }
        .focusable()
}

/** Текстовая кнопка в пиксельной рамке. */
@Composable
fun PButton(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val t = LocalDen.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .tvClickable(onFocus = { focused = it }, onLongClick = onLongClick, onClick = onClick)
            .pixelBox(
                border = if (focused) t.colors.accent else Color.Transparent,
                fill = if (focused) t.colors.surfaceHi else t.colors.surface,
                stepDp = t.border,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        PText(text, size, if (focused) t.colors.text else t.colors.dim)
    }
}

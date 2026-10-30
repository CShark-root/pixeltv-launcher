package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

val FOCUS_EFFECTS = listOf(
    "scale" to tr("Только увеличение"),
    "dim" to tr("Затемнение остальных"),
    "glow" to tr("Свечение"),
    "glitch" to tr("Глюк"),
    "pixel" to tr("Пиксельное проявление"),
    "crt" to tr("ЭЛТ-развёртка"),
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
// Готовый Typeface с толщиной 600 собираем один раз сами: с Font(variationSettings) текст на этом ТВ
// рисовался заметно медленнее (90-й перцентиль кадра в настройках ~100 мс).
private val handjet: FontFamily by lazy {
    val ctx = com.pixelden.launcher.PixelDenApp.instance
    runCatching {
        val file = java.io.File(ctx.cacheDir, "handjet.ttf")
        if (!file.exists()) ctx.resources.openRawResource(R.font.handjet).use { i -> file.outputStream().use { i.copyTo(it) } }
        val tf = android.graphics.Typeface.Builder(file).setFontVariationSettings("'wght' 600").build()
        FontFamily(androidx.compose.ui.text.font.Typeface(tf))
    }.getOrElse { FontFamily(Font(R.font.handjet)) }
}

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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DenThemeProvider(theme: DenTheme, content: @Composable () -> Unit) {
    val scroll = remember(theme.animations) { EdgeScrollSpec(animate = theme.animations) }
    CompositionLocalProvider(
        LocalDen provides theme,
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides scroll,
        content = content,
    )
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

/** Скругление углов карточек, кнопок и рамок — одно на весь интерфейс. */
val CORNER = 8.dp

/**
 * Рамка и заливка со скруглёнными углами. [step] — толщина рамки.
 * [inflate] > 0 рисует рамку снаружи границ на это расстояние (для анимации «рамка сжимается на карточку»).
 */
fun DrawScope.pixelFrame(color: Color, fill: Color?, step: Float, inflate: Float = 0f) {
    val x0 = -inflate
    val y0 = -inflate
    val w = size.width + 2 * inflate
    val h = size.height + 2 * inflate
    val r = CORNER.toPx().coerceAtMost(minOf(w, h) / 2)
    if (fill != null) drawRoundRect(fill, Offset(x0, y0), Size(w, h), CornerRadius(r))
    if (color.alpha > 0f) {
        // Обводка рисуется внутрь, чтобы не вылезать за размер элемента.
        val s = step.coerceAtMost(3.dp.toPx())
        drawRoundRect(color, Offset(x0 + s / 2, y0 + s / 2), Size(w - s, h - s), CornerRadius((r - s / 2).coerceAtLeast(0f)), style = Stroke(s))
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

package com.pixelden.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Безопасный requestFocus: элемент может ещё не быть в композиции. */
suspend fun FocusRequester.tryFocus() {
    repeat(10) {
        if (runCatching { requestFocus() }.isSuccess) return
        delay(30)
    }
}

@Composable
fun SectionTitle(text: String) {
    val t = LocalDen.current
    PText(text.uppercase(), 15.sp, t.colors.accent, Modifier.padding(top = 22.dp, bottom = 6.dp, start = 4.dp))
}

/**
 * Строка настройки: < > меняют значение, OK — то же, что > (или [onClick], если задан).
 */
@Composable
fun SettingRow(
    label: String,
    value: String? = null,
    hint: String? = null,
    focusRequester: FocusRequester? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Рисовать ли «< значение >» в фокусе. У заголовков разделов ◀ ▶ сворачивают, а не листают. */
    arrows: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalDen.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .tvClickable(
                onFocus = { focused = it },
                onLongClick = onLongClick,
                onKey = { code ->
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.let { it(); true } ?: false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> onRight?.let { it(); true } ?: false
                        else -> false
                    }
                },
                onClick = { (onClick ?: onRight)?.invoke() },
            )
            .pixelBox(
                border = if (focused) t.colors.accent else Color.Transparent,
                fill = if (focused) t.colors.surfaceHi else t.colors.surface,
                stepDp = t.border,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PText(label, 18.sp, if (focused) t.colors.text else t.colors.text.copy(alpha = 0.85f))
            if (hint != null) PText(hint, 13.sp, t.colors.dim, maxLines = 3)
        }
        trailing?.invoke()
        if (value != null) {
            val showArrows = arrows && (onLeft != null || onRight != null)
            PText(
                if (showArrows && focused) "< $value >" else value,
                18.sp,
                if (focused) t.colors.accent else t.colors.dim,
                Modifier.padding(start = 16.dp),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
fun ToggleRow(label: String, value: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    SettingRow(
        label = label,
        value = if (value) "ВКЛ" else "ВЫКЛ",
        hint = hint,
        onLeft = { onChange(!value) },
        onRight = { onChange(!value) },
    )
}

fun <T> List<T>.cycle(current: T, step: Int): T {
    if (isEmpty()) return current
    val i = indexOf(current).let { if (it < 0) 0 else it }
    return this[((i + step) % size + size) % size]
}

fun Int.stepIn(step: Int, range: IntRange): Int = (this + step).coerceIn(range)

/** Затемнённый оверлей с панелью посередине; BACK закрывает. */
@Composable
fun Overlay(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 620.dp,
    content: LazyListScope.() -> Unit,
) {
    val t = LocalDen.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            // Сам оверлей не фокусируемый: BACK ловим заранее, пока событие идёт к кнопке внутри.
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) onDismiss()
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(width)
                .heightIn(max = 620.dp)
                .pixelBox(t.colors.accent, t.colors.bg, t.border)
                .padding(22.dp)
        ) {
            PText(title, 22.sp, t.colors.accent, Modifier.padding(bottom = 12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp), content = content)
        }
    }
}

/** Первый элемент оверлея получает фокус при открытии. */
@Composable
fun rememberAutoFocus(): FocusRequester {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.tryFocus() }
    return fr
}

@Composable
fun TextEditOverlay(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val t = LocalDen.current
    var text by remember { mutableStateOf(initial) }
    val fr = rememberAutoFocus()
    var focused by remember { mutableStateOf(false) }
    Overlay(title, onDismiss) {
        item {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = t.colors.text, fontFamily = t.font, fontSize = 20.sp * t.fontScale),
                cursorBrush = SolidColor(t.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fr)
                    .onFocusChanged { focused = it.isFocused }
                    .pixelBox(if (focused) t.colors.accent else t.colors.dim, t.colors.surface, t.border)
                    .padding(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PButton("Готово") { onDone(text) }
                PButton("Отмена", onClick = onDismiss)
            }
        }
    }
}

@Composable
fun ColorSwatch(color: Long) {
    Box(
        Modifier
            .padding(start = 12.dp)
            .size(22.dp)
            .pixelBox(Color.White.copy(alpha = 0.6f), Color(color), 2)
    )
}

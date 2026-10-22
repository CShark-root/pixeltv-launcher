package com.pixelden.launcher.ui

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.scenario.DebugLog

/**
 * Калибровка экрана «как в играх»: шаблон на весь экран, ← → сразу меняют настройку изображения ТВ.
 * Настройки Xiaomi лежат в global/picture_* — телевизор применяет их сам, как подсветку.
 */
private class CalStep(
    val key: String,
    val title: String,
    val hint: String,
    val range: IntRange,
    val step: Int = 1,
    /** Для перечислений: значение → название. */
    val names: Map<Int, String>? = null,
    val pattern: @Composable () -> Unit,
)

private val STEPS = listOf(
    CalStep(
        "picture_mode", "Режим изображения",
        "Для естественной картинки выберите «Кино». «Яркий» красивее в магазине, но искажает цвета. " +
            "Режим выбирается первым: остальные шаги настраивают именно его.",
        0..4, names = linkedMapOf(1 to "Кино", 0 to "Пользовательский", 2 to "Спорт", 3 to "Яркий", 4 to "Очень яркий"),
    ) { OverviewPattern() },
    CalStep(
        "picture_backlight", "Подсветка",
        "Сколько света даёт экран. Белое поле должно быть ярким, но не слепить в вашей комнате. " +
            "Вечером обычно хватает 40–60.",
        0..100, 5,
    ) { BacklightPattern() },
    CalStep(
        "picture_brightness", "Уровень чёрного",
        "Уменьшайте, пока ЛЕВЫЙ знак не исчезнет совсем, а СРЕДНИЙ ещё едва виден. " +
            "Если средний пропал — тени будут «проваливаться», сделайте шаг назад.",
        0..100,
    ) { BlackPattern() },
    CalStep(
        "picture_contrast", "Контраст",
        "Повышайте, пока ПРАВЫЙ знак на белом не начнёт пропадать, затем шаг назад. " +
            "Все три знака должны оставаться различимыми.",
        0..100,
    ) { WhitePattern() },
    CalStep(
        "picture_gamma", "Гамма",
        "Отойдите или прищурьтесь: СРЕДНИЙ квадрат должен слиться с полосатым фоном. " +
            "Если сливается левый — выберите светлее, если правый — темнее.",
        1..3, names = linkedMapOf(1 to "Тёмная", 2 to "Стандартная", 3 to "Светлая"),
    ) { GammaPattern() },
    CalStep(
        "picture_color_temperature", "Цветовая температура",
        "Белый и серые полосы должны быть нейтральными, как лист бумаги, — без синевы и без желтизны. " +
            "Для фильмов правильнее «Тёплая».",
        1..3, names = linkedMapOf(1 to "Холодная", 2 to "Стандартная", 3 to "Тёплая"),
    ) { TemperaturePattern() },
    CalStep(
        "picture_saturation", "Насыщенность",
        "Цвета сочные, но лица естественные, не красные. В цветных градиентах должны оставаться " +
            "все оттенки — если яркий конец сливается в одно пятно, уменьшите.",
        0..100,
    ) { ColorPattern() },
    CalStep(
        "picture_sharpness", "Чёткость",
        "Уменьшайте, пока вокруг тонких линий и букв не пропадут светлые ореолы. " +
            "Слишком мало — линии станут мыльными.",
        0..100,
    ) { SharpnessPattern() },
)

private fun read(context: Context, key: String): Int? =
    runCatching { Settings.Global.getInt(context.contentResolver, key) }.getOrNull()

private fun write(context: Context, key: String, v: Int): Boolean =
    runCatching { Settings.Global.putInt(context.contentResolver, key, v) }.getOrDefault(false)

@Composable
fun CalibrationScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val t = LocalDen.current
    // Как было до калибровки — чтобы можно было всё вернуть.
    val original = remember { STEPS.associate { it.key to read(context, it.key) } }
    var index by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    val step = STEPS[index]
    val value = remember(index, tick) { read(context, step.key) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }

    fun change(dir: Int) {
        val cur = value ?: return
        val next = step.names?.keys?.toList()?.let { keys ->
            val i = keys.indexOf(cur).let { if (it < 0) 0 else it }
            keys[(i + dir).coerceIn(0, keys.lastIndex)]
        } ?: (cur + dir * step.step).coerceIn(step.range)
        if (next != cur && write(context, step.key, next)) tick++
    }

    fun restore() {
        // Режим — первым: при его смене ТВ подставляет свои значения остальных настроек.
        original["picture_mode"]?.let { write(context, "picture_mode", it) }
        original.forEach { (k, v) -> if (v != null && k != "picture_mode") write(context, k, v) }
        DebugLog.i("калибровка: возвращены прежние настройки")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (!done) change(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!done) change(1)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> panel = !panel
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (done) onExit() else if (index < STEPS.lastIndex) { index++; panel = true } else done = true
                    }
                    KeyEvent.KEYCODE_BACK -> when {
                        done -> { restore(); onExit() }
                        index > 0 -> { index--; panel = true }
                        else -> onExit()
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable(),
    ) {
        if (done) {
            Column(Modifier.align(Alignment.Center).width(900.dp).pixelBox(t.colors.accent, t.colors.bg, t.border).padding(32.dp)) {
                PText("Готово!", 40.sp, t.colors.text)
                STEPS.forEach { s ->
                    val v = read(context, s.key)
                    val was = original[s.key]
                    PText(
                        "${s.title}: ${label(s, v)}" + if (was != v) "   (было ${label(s, was)})" else "",
                        18.sp, if (was != v) t.colors.accent else t.colors.dim, Modifier.padding(top = 6.dp),
                    )
                }
                PText("OK — оставить так · BACK — вернуть как было", 18.sp, t.colors.text, Modifier.padding(top = 20.dp))
            }
            return@Box
        }

        step.pattern()

        if (panel) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .width(1100.dp)
                    .pixelBox(t.colors.accent, Color(0xF0101014), t.border)
                    .padding(horizontal = 26.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PText("${index + 1}/${STEPS.size}  ${step.title}", 26.sp, t.colors.text, Modifier.weight(1f))
                    PText("<  ${label(step, value)}  >", 28.sp, t.colors.accent)
                }
                PText(step.hint, 16.sp, t.colors.dim, Modifier.padding(top = 8.dp), maxLines = 4)
                PText("< > менять · OK — дальше · BACK — назад · вверх/вниз — спрятать подсказку", 14.sp, t.colors.dim.copy(alpha = 0.7f), Modifier.padding(top = 10.dp))
            }
        }
    }
}

private fun label(s: CalStep, v: Int?): String = when {
    v == null -> "нет доступа"
    s.names != null -> s.names[v] ?: v.toString()
    else -> v.toString()
}

// ------------------------------------------------------------------ шаблоны

private fun grey(v: Int) = Color(v / 255f, v / 255f, v / 255f)

@Composable
private fun Label(text: String, color: Color, modifier: Modifier = Modifier) = PText(text, 18.sp, color, modifier)

/** Обзор: цветные полосы, серая шкала и телесные тона. */
@Composable
private fun OverviewPattern() {
    Canvas(Modifier.fillMaxSize()) {
        val bars = listOf(0xFFBFBFBF, 0xFFBFBF00, 0xFF00BFBF, 0xFF00BF00, 0xFFBF00BF, 0xFFBF0000, 0xFF0000BF)
        val w = size.width / bars.size
        bars.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * w, 0f), Size(w + 1, size.height * 0.45f)) }
        greyRamp(0f, size.height * 0.45f, size.width, size.height * 0.12f, 16)
        skin(size.height * 0.57f, size.height * 0.15f)
    }
}

/** Ступени серого 0…100% и белое поле. */
@Composable
private fun BacklightPattern() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.White, Offset(size.width * 0.3f, size.height * 0.08f), Size(size.width * 0.4f, size.height * 0.3f))
        greyRamp(0f, size.height * 0.45f, size.width, size.height * 0.2f, 11)
    }
}

private fun DrawScope.greyRamp(x: Float, y: Float, w: Float, h: Float, steps: Int) {
    val sw = w / steps
    for (i in 0 until steps) drawRect(grey(i * 255 / (steps - 1)), Offset(x + i * sw, y), Size(sw + 1, h))
}

private fun DrawScope.skin(y: Float, h: Float) {
    val tones = listOf(0xFFFFDBAC, 0xFFF1C27D, 0xFFE0AC69, 0xFFC68642, 0xFF8D5524, 0xFF5C3A1E)
    val w = size.width / tones.size
    tones.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * w, y), Size(w + 1, h)) }
}

/** Уровень чёрного: три знака на чистом чёрном и ступени 0…32. */
@Composable
private fun BlackPattern() {
    val levels = listOf(4 to "не виден", 10 to "едва виден", 22 to "виден")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.height * 0.26f
            levels.forEachIndexed { i, (v, _) ->
                val cx = size.width * (0.25f + i * 0.25f)
                drawSymbol(grey(v), Offset(cx - s / 2, size.height * 0.12f), s)
            }
            val n = 17
            val sw = size.width * 0.8f / n
            for (i in 0 until n) drawRect(grey(i * 2), Offset(size.width * 0.1f + i * sw, size.height * 0.5f), Size(sw - 4, size.height * 0.08f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 212.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            levels.forEach { (_, n) -> Label(n, grey(70)) }
        }
    }
}

/** Контраст: три знака на белом. */
@Composable
private fun WhitePattern() {
    val levels = listOf(225 to "виден", 240 to "виден", 250 to "едва виден")
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.height * 0.26f
            levels.forEachIndexed { i, (v, _) ->
                val cx = size.width * (0.25f + i * 0.25f)
                drawSymbol(grey(v), Offset(cx - s / 2, size.height * 0.12f), s)
            }
            val n = 16
            val sw = size.width * 0.8f / n
            for (i in 0 until n) drawRect(grey(225 + i * 2), Offset(size.width * 0.1f + i * sw, size.height * 0.5f), Size(sw - 4, size.height * 0.08f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 212.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            levels.forEach { (_, n) -> Label(n, grey(140)) }
        }
    }
}

/** Пиксельный знак-котик для шаблонов чёрного и белого. */
private fun DrawScope.drawSymbol(c: Color, at: Offset, s: Float) {
    val grid = listOf("#.....#", "##...##", "#######", "#.###.#", "#######", ".#####.", "..#.#..")
    val p = s / grid.size
    grid.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') drawRect(c, Offset(at.x + x * p, at.y + y * p), Size(p + 0.5f, p + 0.5f)) } }
}

/** Гамма: фон из чёрно-белых строк через пиксель (оптически 50%) и три сплошных серых. */
@Composable
private fun GammaPattern() {
    val greys = listOf(170 to "темнее", 186 to "норма 2.2", 202 to "светлее")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black)
            var y = 0f
            while (y < size.height) { drawRect(Color.White, Offset(0f, y), Size(size.width, 1f)); y += 2f }
            val s = size.height * 0.28f
            greys.forEachIndexed { i, (v, _) ->
                drawRect(grey(v), Offset(size.width * (0.25f + i * 0.25f) - s / 2, size.height * 0.15f), Size(s, s))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 240.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            greys.forEach { (_, n) -> Box(Modifier.background(Color.Black).padding(6.dp)) { Label(n, Color.White) } }
        }
    }
}

/** Цветовая температура: белое поле и плавная серая шкала. */
@Composable
private fun TemperaturePattern() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.White, Offset(0f, 0f), Size(size.width, size.height * 0.3f))
        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)), Offset(0f, size.height * 0.3f), Size(size.width, size.height * 0.2f))
        greyRamp(0f, size.height * 0.5f, size.width, size.height * 0.15f, 11)
    }
}

/** Насыщенность: градиенты основных цветов, телесные тона и полосы. */
@Composable
private fun ColorPattern() {
    Canvas(Modifier.fillMaxSize()) {
        val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta, Color.Yellow)
        val h = size.height * 0.08f
        colors.forEachIndexed { i, c ->
            drawRect(Brush.horizontalGradient(listOf(Color.Black, c, Color.White)), Offset(0f, i * h), Size(size.width, h))
        }
        skin(h * 6, size.height * 0.14f)
    }
}

/** Чёткость: сетка в пиксель, тонкие линии и мелкие знаки. */
@Composable
private fun SharpnessPattern() {
    Box(Modifier.fillMaxSize().background(grey(128))) {
        Canvas(Modifier.fillMaxSize()) {
            val top = size.height * 0.62f
            // Вертикальные и горизонтальные линии толщиной 1, 2 и 3 пикселя.
            listOf(1f, 2f, 3f).forEachIndexed { k, w ->
                val x0 = size.width * (0.05f + k * 0.32f)
                var x = x0
                while (x < x0 + size.width * 0.12f) { drawRect(Color.White, Offset(x, size.height * 0.05f), Size(w, top * 0.4f)); x += w * 4 }
                var y = size.height * 0.05f
                while (y < size.height * 0.05f + top * 0.4f) { drawRect(Color.Black, Offset(x0 + size.width * 0.14f, y), Size(size.width * 0.12f, w)); y += w * 4 }
            }
            // Шахматка в пиксель.
            val cx = size.width * 0.35f
            val cy = size.height * 0.36f
            for (yy in 0 until 120) for (xx in 0 until 160 step 2) {
                val off = if (yy % 2 == 0) 0 else 1
                drawRect(Color.Black, Offset(cx + xx + off, cy + yy), Size(1f, 1f))
            }
            // Тонкие круги.
            for (r in 20..110 step 18) drawCircle(Color.White, r.toFloat(), Offset(size.width * 0.75f, size.height * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
        Column(Modifier.padding(start = 80.dp, top = 300.dp)) {
            PText("Мелкий текст: съешь же ещё этих мягких французских булок", 12.sp, Color.White)
            PText("Мелкий текст: съешь же ещё этих мягких французских булок", 12.sp, Color.Black)
        }
    }
}

package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.system.PatternVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Калибровка экрана «как в играх»: шаблон на весь экран, ← → сразу меняют настройку изображения ТВ.
 *
 * Настройки Xiaomi лежат в global/picture_*. Но видеопроцессор телевизора (MediaTek) обрабатывает только
 * видеослой — интерфейс он не трогает. Поэтому шаблон сначала рисуется в картинку, кодируется в короткий
 * ролик и показывается видеоплеером: тогда контраст, гамма и цвет действуют на него, как на фильм.
 */
private class CalStep(
    val key: String,
    val title: String,
    val hint: String,
    val range: IntRange,
    val step: Int = 1,
    /** Для перечислений: значение → название. */
    val names: Map<Int, String>? = null,
    /** Подписи поверх видео (они в интерфейсе и видеопроцессором не меняются). */
    val labels: (@Composable () -> Unit)? = null,
    val draw: DrawScope.() -> Unit,
)

private val STEPS = listOf(
    CalStep(
        "picture_mode", tr("Режим изображения"),
        tr("Для естественной картинки выберите «Кино». «Яркий» красивее в магазине, но искажает цвета. ") +
            tr("Режим выбирается первым: остальные шаги настраивают именно его."),
        0..4, names = linkedMapOf(1 to tr("Кино"), 0 to tr("Пользовательский"), 2 to tr("Спорт"), 3 to tr("Яркий"), 4 to tr("Очень яркий")),
    ) { overview() },
    CalStep(
        "picture_backlight", tr("Подсветка"),
        tr("Сколько света даёт экран. Белое поле должно быть ярким, но не слепить в вашей комнате. ") +
            tr("Вечером обычно хватает 40–60."),
        0..100, 5,
    ) { backlight() },
    CalStep(
        "picture_brightness", tr("Уровень чёрного"),
        tr("Уменьшайте, пока ЛЕВЫЙ знак не исчезнет совсем, а СРЕДНИЙ ещё едва виден. ") +
            tr("Если средний пропал — тени будут «проваливаться», сделайте шаг назад."),
        0..100, labels = { Labels(listOf(tr("не виден"), tr("едва виден"), tr("виден")), grey(90)) },
    ) { blackLevel() },
    CalStep(
        "picture_contrast", tr("Контраст"),
        tr("Повышайте, пока ПРАВЫЙ знак на белом не начнёт пропадать, затем шаг назад. ") +
            tr("Все три знака должны оставаться различимыми."),
        0..100, labels = { Labels(listOf(tr("виден"), tr("виден"), tr("едва виден")), grey(140)) },
    ) { whiteLevel() },
    CalStep(
        "picture_gamma", tr("Гамма"),
        tr("Отойдите или прищурьтесь: СРЕДНИЙ квадрат должен слиться с полосатым фоном. ") +
            tr("Если сливается левый — выберите светлее, если правый — темнее."),
        1..3, names = linkedMapOf(1 to tr("Тёмная"), 2 to tr("Стандартная"), 3 to tr("Светлая")),
        labels = { Labels(listOf(tr("темнее"), tr("норма"), tr("светлее")), Color.White, top = 240, boxed = true) },
    ) { gamma() },
    CalStep(
        "picture_color_temperature", tr("Цветовая температура"),
        tr("Белый и серые полосы должны быть нейтральными, как лист бумаги, — без синевы и без желтизны. ") +
            tr("Для фильмов правильнее «Тёплая»."),
        1..3, names = linkedMapOf(1 to tr("Холодная"), 2 to tr("Стандартная"), 3 to tr("Тёплая")),
    ) { temperature() },
    CalStep(
        "picture_saturation", tr("Насыщенность"),
        tr("Цвета сочные, но лица естественные, не красные. В цветных градиентах должны оставаться ") +
            tr("все оттенки — если яркий конец сливается в одно пятно, уменьшите."),
        0..100,
    ) { colors() },
    CalStep(
        "picture_sharpness", tr("Чёткость"),
        tr("Уменьшайте, пока вокруг тонких линий и букв не пропадут светлые ореолы. ") +
            tr("Слишком мало — линии станут мыльными."),
        0..100,
    ) { sharpness() },
)

/** Версия рисунков: поменялись шаблоны — ролики пересоздаются. */
private const val PATTERNS_VERSION = 1

private fun read(context: Context, key: String): Int? =
    runCatching { Settings.Global.getInt(context.contentResolver, key) }.getOrNull()

private fun write(context: Context, key: String, v: Int): Boolean =
    runCatching { Settings.Global.putInt(context.contentResolver, key, v) }.getOrDefault(false)

private fun videoFile(context: Context, s: CalStep) = File(context.cacheDir, "calib/v${PATTERNS_VERSION}_${s.key}.mp4")

/** Рисует шаблон в картинку 1920×1080 — тем же кодом, что и на экране. */
private fun render(s: CalStep): Bitmap {
    val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp.asImageBitmap())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(1920f, 1080f)) { s.draw(this) }
    return bmp
}

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

    // Готовые ролики шаблонов. Пока ролика нет, шаблон рисуется интерфейсом (без обработки ТВ).
    val videos = remember { mutableStateMapOf<String, File>() }
    var preparing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Текущий шаг — первым, остальные по порядку.
        for (s in listOf(STEPS[0]) + STEPS.drop(1)) {
            val f = videoFile(context, s)
            if (!f.exists()) {
                preparing = tr("Готовлю шаблоны для видеопроцессора: {0}/{1}", (STEPS.indexOf(s) + 1), (STEPS.size))
                val ok = withContext(Dispatchers.Default) {
                    runCatching {
                        f.parentFile?.mkdirs()
                        val bmp = render(s)
                        PatternVideo.encode(bmp, f)
                        bmp.recycle()
                    }.onFailure { DebugLog.e("калибровка: не удалось сделать ролик ${s.key}", it); f.delete() }.isSuccess
                }
                if (!ok) continue
            }
            videos[s.key] = f
        }
        preparing = null
    }

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
                PText(tr("Готово!"), 40.sp, t.colors.text)
                STEPS.forEach { s ->
                    val v = read(context, s.key)
                    val was = original[s.key]
                    PText(
                        "${s.title}: ${label(s, v)}" + if (was != v) tr("   (было {0})", (label(s, was))) else "",
                        18.sp, if (was != v) t.colors.accent else t.colors.dim, Modifier.padding(top = 6.dp),
                    )
                }
                PText(tr("OK — оставить так · BACK — вернуть как было"), 18.sp, t.colors.text, Modifier.padding(top = 20.dp))
            }
            return@Box
        }

        val video = videos[step.key]
        if (video != null) VideoPattern(video) else Canvas(Modifier.fillMaxSize()) { step.draw(this) }
        step.labels?.invoke()

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
                PText(
                    preparing ?: if (video == null) tr("Шаблон в интерфейсе — настройки кроме подсветки на нём не видны")
                    else tr("< > менять · OK — дальше · BACK — назад · вверх/вниз — спрятать подсказку"),
                    14.sp, if (preparing != null || video == null) t.colors.accent else t.colors.dim.copy(alpha = 0.7f),
                    Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/** Ролик шаблона по кругу на SurfaceView — его кадры идут через видеопроцессор ТВ. */
@Composable
private fun VideoPattern(file: File) {
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var surfaceReady by remember { mutableStateOf<SurfaceHolder?>(null) }
    LaunchedEffect(file, surfaceReady) {
        val holder = surfaceReady ?: return@LaunchedEffect
        runCatching {
            player.reset()
            player.setDataSource(file.path)
            player.setDisplay(holder)
            player.isLooping = true
            player.prepare()
            player.start()
        }.onFailure { DebugLog.e("калибровка: ролик ${file.name} не играет", it) }
    }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = h }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = null; runCatching { player.reset() } }
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun label(s: CalStep, v: Int?): String = when {
    v == null -> tr("нет доступа")
    s.names != null -> s.names[v] ?: v.toString()
    else -> v.toString()
}

@Composable
private fun Labels(names: List<String>, color: Color, top: Int = 212, boxed: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = top.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        names.forEach { n ->
            if (boxed) Box(Modifier.background(Color.Black).padding(6.dp)) { PText(n, 18.sp, color) }
            else PText(n, 18.sp, color)
        }
    }
}

// ------------------------------------------------------------------ шаблоны (рисуются в 1920×1080)

private fun grey(v: Int) = Color(v / 255f, v / 255f, v / 255f)

private fun DrawScope.greyRamp(x: Float, y: Float, w: Float, h: Float, steps: Int) {
    val sw = w / steps
    for (i in 0 until steps) drawRect(grey(i * 255 / (steps - 1)), Offset(x + i * sw, y), Size(sw + 1, h))
}

private fun DrawScope.skin(y: Float, h: Float) {
    val tones = listOf(0xFFFFDBAC, 0xFFF1C27D, 0xFFE0AC69, 0xFFC68642, 0xFF8D5524, 0xFF5C3A1E)
    val w = size.width / tones.size
    tones.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * w, y), Size(w + 1, h)) }
}

/** Обзор: цветные полосы, серая шкала и телесные тона. */
private fun DrawScope.overview() {
    drawRect(Color.Black)
    val bars = listOf(0xFFBFBFBF, 0xFFBFBF00, 0xFF00BFBF, 0xFF00BF00, 0xFFBF00BF, 0xFFBF0000, 0xFF0000BF)
    val w = size.width / bars.size
    bars.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * w, 0f), Size(w + 1, size.height * 0.45f)) }
    greyRamp(0f, size.height * 0.45f, size.width, size.height * 0.12f, 16)
    skin(size.height * 0.57f, size.height * 0.15f)
}

/** Ступени серого 0…100% и белое поле. */
private fun DrawScope.backlight() {
    drawRect(Color.Black)
    drawRect(Color.White, Offset(size.width * 0.3f, size.height * 0.08f), Size(size.width * 0.4f, size.height * 0.3f))
    greyRamp(0f, size.height * 0.45f, size.width, size.height * 0.2f, 11)
}

/** Уровень чёрного: три знака на чистом чёрном и ступени 0…32. */
private fun DrawScope.blackLevel() {
    drawRect(Color.Black)
    val s = size.height * 0.26f
    listOf(4, 10, 22).forEachIndexed { i, v -> drawSymbol(grey(v), Offset(size.width * (0.25f + i * 0.25f) - s / 2, size.height * 0.12f), s) }
    val n = 17
    val sw = size.width * 0.8f / n
    for (i in 0 until n) drawRect(grey(i * 2), Offset(size.width * 0.1f + i * sw, size.height * 0.5f), Size(sw - 4, size.height * 0.08f))
}

/** Контраст: три знака на белом и ступени 225…255. */
private fun DrawScope.whiteLevel() {
    drawRect(Color.White)
    val s = size.height * 0.26f
    listOf(225, 240, 250).forEachIndexed { i, v -> drawSymbol(grey(v), Offset(size.width * (0.25f + i * 0.25f) - s / 2, size.height * 0.12f), s) }
    val n = 16
    val sw = size.width * 0.8f / n
    for (i in 0 until n) drawRect(grey(225 + i * 2), Offset(size.width * 0.1f + i * sw, size.height * 0.5f), Size(sw - 4, size.height * 0.08f))
}

/** Пиксельный знак-котик для шаблонов чёрного и белого. */
private fun DrawScope.drawSymbol(c: Color, at: Offset, s: Float) {
    val grid = listOf("#.....#", "##...##", "#######", "#.###.#", "#######", ".#####.", "..#.#..")
    val p = s / grid.size
    grid.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') drawRect(c, Offset(at.x + x * p, at.y + y * p), Size(p + 0.5f, p + 0.5f)) } }
}

/** Гамма: фон из чёрно-белых строк через пиксель (оптически 50%) и три сплошных серых. */
private fun DrawScope.gamma() {
    drawRect(Color.Black)
    var y = 0f
    while (y < size.height) { drawRect(Color.White, Offset(0f, y), Size(size.width, 1f)); y += 2f }
    val s = size.height * 0.28f
    listOf(170, 186, 202).forEachIndexed { i, v -> drawRect(grey(v), Offset(size.width * (0.25f + i * 0.25f) - s / 2, size.height * 0.15f), Size(s, s)) }
}

/** Цветовая температура: белое поле и серые шкалы. */
private fun DrawScope.temperature() {
    drawRect(Color.Black)
    drawRect(Color.White, Offset(0f, 0f), Size(size.width, size.height * 0.3f))
    drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)), Offset(0f, size.height * 0.3f), Size(size.width, size.height * 0.2f))
    greyRamp(0f, size.height * 0.5f, size.width, size.height * 0.15f, 11)
}

/** Насыщенность: градиенты основных цветов и телесные тона. */
private fun DrawScope.colors() {
    drawRect(Color.Black)
    val list = listOf(Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta, Color.Yellow)
    val h = size.height * 0.08f
    list.forEachIndexed { i, c -> drawRect(Brush.horizontalGradient(listOf(Color.Black, c, Color.White)), Offset(0f, i * h), Size(size.width, h)) }
    skin(h * 6, size.height * 0.14f)
}

/** Чёткость: линии в 1–3 пикселя, шахматка в пиксель, тонкие круги и мелкий текст. */
private fun DrawScope.sharpness() {
    drawRect(grey(128))
    val top = size.height * 0.62f
    listOf(1f, 2f, 3f).forEachIndexed { k, w ->
        val x0 = size.width * (0.05f + k * 0.32f)
        var x = x0
        while (x < x0 + size.width * 0.12f) { drawRect(Color.White, Offset(x, size.height * 0.05f), Size(w, top * 0.4f)); x += w * 4 }
        var y = size.height * 0.05f
        while (y < size.height * 0.05f + top * 0.4f) { drawRect(Color.Black, Offset(x0 + size.width * 0.14f, y), Size(size.width * 0.12f, w)); y += w * 4 }
    }
    val cx = size.width * 0.35f
    val cy = size.height * 0.36f
    for (yy in 0 until 120) for (xx in 0 until 160 step 2) drawRect(Color.Black, Offset(cx + xx + yy % 2, cy + yy), Size(1f, 1f))
    for (r in 20..110 step 18) drawCircle(Color.White, r.toFloat(), Offset(size.width * 0.75f, size.height * 0.42f), style = Stroke(1f))
    val paint = android.graphics.Paint().apply { textSize = 22f; isAntiAlias = true }
    drawContext.canvas.nativeCanvas.apply {
        paint.color = android.graphics.Color.WHITE
        drawText(tr("Мелкий текст: съешь же ещё этих мягких французских булок"), 160f, 640f, paint)
        paint.color = android.graphics.Color.BLACK
        drawText(tr("Мелкий текст: съешь же ещё этих мягких французских булок"), 160f, 680f, paint)
    }
}

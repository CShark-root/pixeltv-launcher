package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Маскоты — листы спрайтов из assets/mascots/<id>.png (их рисует tools/MascotGen.cs).
 * Кадр 64x48, все смотрят вправо; при ходьбе влево отражаем. Анимация только целыми кадрами
 * и целыми пикселями — никакого растяжения, поэтому ничего не «плывёт» и не дрожит.
 */

data class Mascot(val id: String, val name: String, val note: String)

val MASCOTS = listOf(
    Mascot("pinch", "Pinch", tr("Оранжевый кирпичик")),
    Mascot("ember", "Ember", tr("Огненный лис")),
    Mascot("sprocket", "Sprocket", tr("Серый полосатый кот")),
    Mascot("nyx", "Nyx", tr("Чёрный кот с зелёными глазами")),
    Mascot("mango", "Mango", tr("Рыжий полосатый кот")),
    Mascot("frost", "Frost", tr("Белый кот с голубыми глазами")),
)

fun mascotById(id: String) = MASCOTS.firstOrNull { it.id == id } ?: MASCOTS.first()

enum class MascotMood { IDLE, SLEEP }

/**
 * [left] — первая видимая колонка спрайта (без свечения), [headY] — строка на уровне глаз.
 * Считаются по кадру sit0 при загрузке, чтобы реплика вставала рядом с любым маскотом.
 */
private class Sheet(val image: ImageBitmap, val fw: Int, val fh: Int, val index: Map<String, Int>, val left: Int, val right: Int, val top: Int, val headY: Int)

private object SheetCache {
    private val sheets = HashMap<String, Sheet>()
    private var meta: Pair<IntSize, List<String>>? = null

    fun get(context: Context, id: String): Sheet = sheets.getOrPut(id) {
        val (size, names) = meta ?: context.assets.open("mascots/frames.txt").bufferedReader().use { r ->
            val (w, h) = r.readLine().trim().split(" ").map { it.toInt() }
            IntSize(w, h) to r.readLine().trim().split(" ")
        }.also { meta = it }
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val bmp = context.assets.open("mascots/$id.png").use { BitmapFactory.decodeStream(it, null, opts) }!!
        var left = size.width; var right = 0; var topRow = size.height; var bottomRow = 0
        for (y in 0 until size.height) for (x in 0 until size.width) {
            if ((bmp.getPixel(x, y) ushr 24) > 200) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < topRow) topRow = y
                if (y > bottomRow) bottomRow = y
            }
        }
        Sheet(
            bmp.asImageBitmap(), size.width, size.height, names.withIndex().associate { it.value to it.index },
            left = left, right = right, top = topRow, headY = topRow + (bottomRow - topRow) * 2 / 5,
        )
    }
}

/** Что маскот делает прямо сейчас. */
private data class Pose(
    val frame: String = "sit0",
    val x: Int = 0,          // смещение по «полу», в пикселях спрайта
    val dy: Int = 0,         // подъём (прыжок), в пикселях спрайта
    val faceLeft: Boolean = false,
    val zs: List<Pair<Float, Float>> = emptyList(),
)

/**
 * Маскот. [pixel] — размер пикселя спрайта в dp; [roam] — сколько пикселей спрайта он может
 * бродить по горизонтали (0 — сидит на месте). [jumpKey] — увеличьте, чтобы обрадовался.
 * [talking] — пока говорит, смотрит на зрителя.
 */
@Composable
fun MascotSprite(
    mascot: Mascot,
    pixel: Int,
    mood: MascotMood,
    animate: Boolean,
    modifier: Modifier = Modifier,
    jumpKey: Int = 0,
    talking: Boolean = false,
    roam: Int = 0,
    headroom: Int = 10,
    /** Реплика в облачке над головой; ходит вместе с маскотом. */
    bubble: String? = null,
    /** Пасхалка: увеличьте — маскот скажет «bruh.». «67» он изредка показывает сам. */
    bruhKey: Int = 0,
) {
    val context = LocalContext.current
    val sheet = remember(mascot.id) { SheetCache.get(context, mascot.id) }
    var pose by remember(mascot.id) { mutableStateOf(Pose(x = roam)) }
    var busy by remember { mutableStateOf(false) }   // прыжок перебивает остальное
    var lastMood by remember { mutableStateOf(mood) }
    var wokeUp by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf<String?>(null) }
    val meme: suspend (Int) -> Unit = { kind ->
        playMeme(
            kind,
            set = { pose = pose.copy(frame = it) },
            dy = { pose = pose.copy(dy = it) },
            say = { caption = it },
        )
    }
    if (lastMood != mood) { if (lastMood == MascotMood.SLEEP) wokeUp = true; lastMood = mood }

    LaunchedEffect(mascot.id, mood, animate, roam, talking) {
        if (!animate) { pose = pose.copy(frame = if (mood == MascotMood.SLEEP) "sleep0" else "sit0", dy = 0, zs = emptyList()); return@LaunchedEffect }
        if (mood == MascotMood.SLEEP) {
            var t = 0
            while (isActive) {
                val zs = pose.zs.map { (age, j) -> (age + 0.04f) to j }.filter { it.first < 1f } +
                    (if (t % 30 == 0) listOf(0f to Random.nextFloat()) else emptyList())
                pose = pose.copy(frame = if ((t / 20) % 2 == 0) "sleep0" else "sleep1", dy = 0, zs = zs)
                delay(100); t++
            }
        }
        if (talking) {
            // Говорит — повернулся к зрителю и иногда моргает.
            while (isActive) {
                if (!busy) pose = pose.copy(frame = "look", zs = emptyList())
                delay(Random.nextLong(1500, 3200))
                if (!busy) { pose = pose.copy(frame = "lookblink"); delay(260) }
            }
        }
        // Проснулся — удивлённо подскочить, потом сесть.
        if (wokeUp) {
            wokeUp = false
            pose = pose.copy(frame = "surprise", zs = emptyList()); delay(650)
            pose = pose.copy(frame = "sit0")
        }
        coroutineScope {
            // Главный цикл поведения: сидеть → (посмотреть на зрителя | пройтись | помечтать) → …
            launch {
                pose = pose.copy(zs = emptyList())
                while (isActive) {
                    sitFor(Random.nextLong(2500, 6000), { busy }) { pose = pose.copy(frame = it) }
                    val set: (String) -> Unit = { pose = pose.copy(frame = it) }
                    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    val sleepy = h >= 23 || h < 6
                    // Вес действий: ночью чаще зевает, днём чаще гуляет и танцует.
                    when (Random.nextInt(100)) {
                        in 0..21 -> lookAtViewer({ busy }, set)
                        in 22..43 -> if (roam > 0) walkTo(Random.nextInt(0, roam + 1), { pose }, { busy }) { pose = it }
                        in 44..55 -> if (!busy) wave(set)
                        in 56..65 -> if (!busy) groom(set)
                        in 66..75 -> if (!busy) { if (sleepy || Random.nextBoolean()) yawn(set) else stretch(set) }
                        in 76..83 -> if (!busy) stretch(set)
                        in 84..90 -> if (!busy && !sleepy) dance(set)
                        // Пасхалка «67» — изредка, в случайный момент (около раза в 10 минут).
                        91 -> if (!busy) meme(0)
                        else -> if (!busy) { set("happy"); delay(900) }
                    }
                }
            }
        }
    }

    // Пасхалка «bruh.»: упёрлись в потолок экрана.
    LaunchedEffect(bruhKey) {
        if (bruhKey == 0 || !animate || mood == MascotMood.SLEEP) return@LaunchedEffect
        while (busy) delay(100)
        busy = true
        try { meme(1) } finally { busy = false }
    }

    // Радость при запуске приложения: подпрыгнуть и улыбнуться.
    LaunchedEffect(jumpKey) {
        if (jumpKey == 0 || !animate || mood == MascotMood.SLEEP) return@LaunchedEffect
        busy = true
        // присесть → невысокий плавный подскок → мягко приземлиться → улыбнуться
        pose = pose.copy(frame = "crouch", dy = 0); delay(140)
        for (dy in listOf(1, 2, 3, 3, 3, 2, 1, 0)) { pose = pose.copy(frame = "jump", dy = dy); delay(60) }
        pose = pose.copy(frame = "crouch", dy = 0); delay(130)
        pose = pose.copy(frame = "happy"); delay(1000)
        pose = pose.copy(frame = "sit0")
        busy = false
    }

    val widthPx = sheet.fw + roam
    val t = LocalDen.current
    Box(modifier.size((widthPx * pixel).dp, ((sheet.fh + headroom) * pixel).dp)) {
    if (bubble != null) {
        // Реплика слева от маскота: хвостик-указатель касается видимого края спрайта, по высоте — на уровне глаз.
        SpeechBubble(
            bubble,
            Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val gapPx = 6.dp.roundToPx()
                    IntOffset(
                        -((roam - pose.x + sheet.fw - (if (pose.faceLeft) sheet.fw - 1 - sheet.right else sheet.left)) * pixel).dp.roundToPx() - gapPx,
                        ((headroom + sheet.headY) * pixel).dp.roundToPx() - 20.dp.roundToPx(),
                    )
                }
                // Длинная реплика растёт только влево: по умолчанию она центрировалась и заезжала на маскота.
                .wrapContentWidth(Alignment.End, unbounded = true),
        )
    }
    Canvas(Modifier.matchParentSize()) {
        val px = pixel.dp.toPx()
        val p = pose
        val col = sheet.index[p.frame] ?: 0
        val left = p.x * px
        val top = (headroom - p.dy) * px
        val w = sheet.fw * px
        val h = sheet.fh * px
        val draw: DrawScope.() -> Unit = {
            drawImage(
                sheet.image,
                srcOffset = IntOffset(col * sheet.fw, 0),
                srcSize = IntSize(sheet.fw, sheet.fh),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(w.toInt(), h.toInt()),
                filterQuality = FilterQuality.None,
            )
        }
        if (p.faceLeft) scale(-1f, 1f, pivot = Offset(left + w / 2, top + h / 2), block = draw) else draw()

        // «Z» во сне: из-за головы вверх, тают.
        // Считаем в пикселях спрайта и округляем: буквы стоят на той же сетке, что и маскот, и не рассыпаются.
        val dir = if (p.faceLeft) -1 else 1
        val headX = p.x + (sheet.left + sheet.right) / 2 + 4 * dir
        for ((age, jitter) in p.zs) {
            val k = if (age > 0.45f) 2 else 1
            val gx = (headX + dir * (age * 7f + jitter * 2f)).roundToInt() - if (dir < 0) 3 * k else 0
            val gy = (headroom - p.dy + sheet.top - 1 - age * 12f).roundToInt() - 5 * k
            drawZ(gx * px, gy * px, px * k, Color.White.copy(alpha = (1f - age).coerceIn(0f, 1f)))
        }
    }
    caption?.let { text ->
        // Подпись мема — по центру над головой, вместе с подскоками.
        MemeCaption(text, Modifier.align(Alignment.TopStart).layout { m, _ ->
            val pl = m.measure(Constraints())
            val cx = ((pose.x + (sheet.left + sheet.right) / 2f + 0.5f) * pixel).dp.roundToPx()
            val top = ((headroom - pose.dy + sheet.top) * pixel).dp.roundToPx() - 6.dp.roundToPx()
            layout(0, 0) { pl.place(cx - pl.width / 2, top - pl.height) }
        })
    }
    }
}

/** Плавное моргание: полуприкрыть → закрыть → полуприкрыть → открыть. */
private suspend fun blink(set: (String) -> Unit, open: String = "sit0") {
    set("half"); delay(70)
    set("blink"); delay(110)
    set("half"); delay(70)
    set(open)
}

/**
 * Спокойно сидит. Тело не дёргается: лишь изредка моргает и плавно поводит хвостом.
 */
private suspend fun sitFor(ms: Long, busy: () -> Boolean, set: (String) -> Unit) {
    var left = ms
    if (!busy()) set("sit0")
    while (left > 0) {
        val pause = Random.nextLong(2200, 4500)
        delay(minOf(pause, left)); left -= pause
        if (left <= 0 || busy()) continue
        if (Random.nextInt(3) == 0) {
            // хвост: два медленных взмаха
            repeat(2) { set("sit1"); delay(420); set("sit0"); delay(380) }
            left -= 1600
        } else {
            blink(set)
            left -= 250
        }
    }
}

/** Поворачивает голову к зрителю — «ломает четвёртую стену». */
private suspend fun lookAtViewer(busy: () -> Boolean, set: (String) -> Unit) {
    if (busy()) return
    set("look")
    delay(Random.nextLong(1400, 2800))
    if (Random.nextBoolean() && !busy()) { set("lookblink"); delay(260); set("look"); delay(Random.nextLong(600, 1400)) }
    if (!busy()) set("sit0")
}

/** Машет лапкой: поворачивается к зрителю и качает лапку вверх-вниз. */
private suspend fun wave(set: (String) -> Unit) {
    repeat(Random.nextInt(2, 4)) { set("look"); delay(230); set("wave"); delay(230) }
    set("look"); delay(500); set("sit0")
}

/** Зевает: прикрывает глаза, широко открывает рот, медленно закрывает. */
private suspend fun yawn(set: (String) -> Unit) {
    set("half"); delay(160)
    set("yawn"); delay(Random.nextLong(900, 1300))
    set("blink"); delay(220)
    set("half"); delay(160)
    set("sit0")
}

/** Потягивается: лапки вверх, глаза зажмурены. */
private suspend fun stretch(set: (String) -> Unit) {
    set("crouch"); delay(180)
    set("stretch"); delay(Random.nextLong(900, 1400))
    set("crouch"); delay(150)
    set("sit0")
}

/** Умывается лапкой — несколько движений у мордочки. */
private suspend fun groom(set: (String) -> Unit) {
    repeat(Random.nextInt(3, 6)) { set("groom0"); delay(190); set("groom1"); delay(190) }
    set("blink"); delay(150); set("sit0")
}

/** Танцует: лапки по очереди вверх, в ритм. */
private suspend fun dance(set: (String) -> Unit) {
    repeat(Random.nextInt(3, 6)) { set("danceL"); delay(260); set("danceR"); delay(260) }
    set("happy"); delay(500); set("sit0")
}

private suspend fun walkTo(target: Int, pose: () -> Pose, busy: () -> Boolean, set: (Pose) -> Unit) {
    var phase = 0
    while (pose().x != target) {
        if (busy()) { delay(100); continue }
        val p = pose()
        val dir = if (target > p.x) 1 else -1
        // 1 пиксель за кадр и неспешный темп — походка плавная, без рывков.
        val nx = if (dir > 0) minOf(p.x + 1, target) else maxOf(p.x - 1, target)
        set(p.copy(frame = "walk${phase % 8}", x = nx, faceLeft = dir < 0))
        phase++
        delay(150)
    }
    set(pose().copy(frame = "sit0"))
}

private val Z_GLYPH = listOf("KKK", "..K", ".K.", "K..", "KKK")

private fun DrawScope.drawZ(x: Float, y: Float, px: Float, color: Color) {
    Z_GLYPH.forEachIndexed { r, row ->
        row.forEachIndexed { c, ch ->
            // Без нахлёста: с полупрозрачным цветом нахлёст давал тёмные швы между квадратиками.
            if (ch == 'K') drawRect(color, Offset(x + c * px, y + r * px), Size(px, px))
        }
    }
}

/** Облачко реплики в пиксельном стиле: ступенчатая рамка и хвостик-указатель вправо, к маскоту. */
@Composable
private fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    val t = LocalDen.current
    val border = t.colors.text.copy(alpha = 0.92f)
    val fill = t.colors.surface
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .pixelBox(border, fill, 3)
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) { PText(text, 15.sp, t.colors.text, Modifier.widthIn(max = 330.dp), maxLines = 4) }
        // Хвостик: заходит на рамку на 1 «пиксель», чтобы шов был открыт.
        Canvas(Modifier.offset(x = (-3).dp).size(9.dp, 15.dp)) {
            val u = size.width / 3
            val px = listOf(
                "B..",
                "FB.",
                "FFB",
                "FB.",
                "B..",
            )
            px.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch ->
                    val col = when (ch) { 'B' -> border; 'F' -> fill; else -> null } ?: return@forEachIndexed
                    drawRect(col, Offset(c * u, r * u), Size(u + 0.5f, u + 0.5f))
                }
            }
        }
    }
}

/**
 * Мемная выходка с подписью над головой. Кадры — из обычного набора, смысл делают подпись и ритм.
 * В конце (и при прерывании) маскот садится и подпись исчезает.
 */
private suspend fun playMeme(
    kind: Int,
    set: (String) -> Unit,
    dy: (Int) -> Unit,
    say: (String?) -> Unit,
) {
    try {
        when (kind) {
            // «67»: руки вверх-вниз по очереди
            0 -> {
                repeat(4) { set("danceL"); say("6"); delay(190); set("danceR"); say("7"); delay(190) }
                set("happy"); say("67!"); delay(1100)
            }
            // «bruh.»: удивился — и смотрит в камеру
            else -> { set("surprise"); say("bruh."); delay(700); set("look"); delay(1300) }
        }
    } finally {
        say(null); dy(0); set("sit0")
    }
}

/** Подпись мема: крупно, белым с жёсткой чёрной тенью — как в классических мемах. */
@Composable
private fun MemeCaption(text: String, modifier: Modifier = Modifier) {
    val t = LocalDen.current
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = Color.White,
            fontFamily = t.font,
            fontSize = 26.sp * t.fontScale,
            shadow = Shadow(Color.Black, Offset(4f, 4f), 0f),
        ),
        maxLines = 1,
    )
}

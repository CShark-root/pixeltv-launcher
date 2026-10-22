package com.pixelden.launcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.weather.Sky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Живые пиксельные сцены: значение wallpaperPath вида "scene:…". */
val SCENES = listOf(
    "scene:sky" to "Пейзаж по времени суток",
    "scene:city" to "Ночной город",
    "scene:fire" to "Камин",
)

fun sceneName(path: String?): String? = SCENES.firstOrNull { it.first == path }?.second

/** Слоты обоев по времени суток. */
val TIME_SLOTS = listOf("morning" to "Утро 5–11", "day" to "День 11–17", "evening" to "Вечер 17–22", "night" to "Ночь 22–5")

fun timeSlot(hour: Float): String = when (hour.toInt()) {
    in 5..10 -> "morning"
    in 11..16 -> "day"
    in 17..21 -> "evening"
    else -> "night"
}

fun currentHour(): Float = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }

/** Какие обои сейчас на экране: слот времени суток (если включён и задан) или общие. */
fun activeWallpaper(cfg: LauncherConfig, hour: Float): String? =
    (if (cfg.wallpaperByTime) cfg.timeWallpapers[timeSlot(hour)] else null) ?: cfg.wallpaperPath

/**
 * Картинка, ужатая под экран: степень двойки, при которой она ещё не меньше [maxW]×[maxH].
 * Фото 6000×4000 с телефона занимает так ~8 МБ вместо ~96.
 */
fun decodeSampled(path: String, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Фон главного экрана: обои или сцена, затемнение и погода поверх. */
@Composable
fun Backdrop(cfg: LauncherConfig, paused: () -> Boolean = { false }) {
    val hour by produceState(currentHour()) {
        while (true) { delay(60_000); value = currentHour() }
    }
    val weather by PixelDenApp.instance.weather.now.collectAsState()
    val animate = LocalDen.current.animations
    val sky = if (cfg.weatherEnabled) weather?.sky else null
    val path = activeWallpaper(cfg, hour)

    when {
        path == null -> Unit
        path.startsWith("scene:") -> LiveScene(path, hour, sky, animate, paused)
        else -> ImageWallpaper(path)
    }
    if (path != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = cfg.wallpaperDim / 100f)))
    if (cfg.weatherOnWallpaper && animate && sky != null) WeatherParticles(sky, paused)
}

@Composable
private fun ImageWallpaper(path: String) {
    val conf = LocalConfiguration.current
    val density = LocalDensity.current
    val w = with(density) { conf.screenWidthDp * this.density }.toInt().coerceAtLeast(1280)
    val h = with(density) { conf.screenHeightDp * this.density }.toInt().coerceAtLeast(720)
    val img by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { decodeSampled(path, w, h)?.asImageBitmap() }.getOrNull() }
    }
    // Отдельный слой: обои не перерисовываются при каждом сдвиге списка.
    img?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer(), contentScale = ContentScale.Crop) }
}

// ------------------------------------------------------------------ кадровые часы

/** Время в мс, обновляемое ~[fps] раз в секунду. Читать только в фазе рисования — тогда нет перекомпоновок. */
@Composable
private fun frameTime(animate: Boolean, fps: Int = 30, paused: () -> Boolean = { false }): androidx.compose.runtime.MutableLongState {
    val time = remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate, fps) {
        if (!animate) return@LaunchedEffect
        val step = 1000L / fps
        var last = 0L
        while (true) {
            // Во время прокрутки кадр не меняем: GPU телевизора занят списком, фон подождёт.
            withFrameMillis { ms -> if (ms - last >= step && !paused()) { last = ms; time.longValue = ms } }
        }
    }
    return time
}

// ------------------------------------------------------------------ цвета

private const val W = 192
private const val H = 108

private fun rgb(c: Int) = c or 0xFF000000.toInt()
private fun lerpRgb(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * k).toInt().coerceIn(0, 255)
    return rgb((ch(16) shl 16) or (ch(8) shl 8) or ch(0))
}
private fun Int.toColor(alpha: Float = 1f) = Color(this or 0xFF000000.toInt()).copy(alpha = alpha)

private class SkyKey(val h: Float, val top: Int, val bottom: Int, val night: Float)

private val SKY_KEYS = listOf(
    SkyKey(0f, 0x0B1028, 0x1C2148, 1f),
    SkyKey(5f, 0x0B1028, 0x1C2148, 1f),
    SkyKey(6.5f, 0x2E3A6E, 0xF2A271, 0.35f),
    SkyKey(9f, 0x3A7BD5, 0xA8D8F0, 0f),
    SkyKey(17f, 0x3A7BD5, 0xA8D8F0, 0f),
    SkyKey(19f, 0x33285A, 0xF07A4F, 0.4f),
    SkyKey(21f, 0x0B1028, 0x1C2148, 1f),
    SkyKey(24f, 0x0B1028, 0x1C2148, 1f),
)

private class SkyLook(val top: Int, val bottom: Int, val night: Float)

/** Небо в момент [hour]; в пасмурную погоду цвета уходят в серый. */
private fun skyLook(hour: Float, sky: Sky?): SkyLook {
    val i = SKY_KEYS.indexOfLast { it.h <= hour }.coerceIn(0, SKY_KEYS.size - 2)
    val a = SKY_KEYS[i]
    val b = SKY_KEYS[i + 1]
    val t = (hour - a.h) / (b.h - a.h)
    var top = lerpRgb(a.top, b.top, t)
    var bottom = lerpRgb(a.bottom, b.bottom, t)
    val grey = when (sky) {
        Sky.CLOUDY -> 0.3f
        Sky.OVERCAST, Sky.DRIZZLE, Sky.RAIN, Sky.SLEET, Sky.SNOW -> 0.55f
        Sky.FOG -> 0.65f
        Sky.THUNDER -> 0.7f
        else -> 0f
    }
    if (grey > 0f) {
        fun toGrey(c: Int): Int {
            val l = (((c shr 16) and 255) * 0.3f + ((c shr 8) and 255) * 0.59f + (c and 255) * 0.11f).toInt()
            return lerpRgb(c, (l shl 16) or (l shl 8) or l, grey)
        }
        top = toGrey(top); bottom = toGrey(bottom)
    }
    return SkyLook(top, bottom, a.night + (b.night - a.night) * t)
}

private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

private class Px(val w: Int, val h: Int) {
    val a = IntArray(w * h)
    fun set(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) a[y * w + x] = rgb(c) }
    fun rect(x: Int, y: Int, rw: Int, rh: Int, c: Int) { for (yy in y until y + rh) for (xx in x until x + rw) set(xx, yy, c) }
    fun disk(cx: Int, cy: Int, r: Int, c: Int) {
        for (y in -r..r) for (x in -r..r) if (x * x + y * y <= r * r + r) set(cx + x, cy + y, c)
    }
    fun bitmap(): Bitmap = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}

/** Небо градиентом с пиксельным растром (как в старых играх). */
private fun Px.skyGradient(look: SkyLook, horizon: Int) {
    val steps = 10
    for (y in 0 until h) for (x in 0 until w) {
        val f = (y.toFloat() / horizon).coerceIn(0f, 1f)
        val level = floor(f * steps + BAYER[(y and 3) * 4 + (x and 3)] / 16f) / steps
        set(x, y, lerpRgb(look.top, look.bottom, level))
    }
}

private fun ridge(x: Int, seed: Float, base: Float, amp: Float): Int =
    (base - amp * (sin(x * 0.045f + seed) * 0.6f + sin(x * 0.13f + seed * 2.1f) * 0.3f + sin(x * 0.31f + seed * 3.7f) * 0.1f)).toInt()

private fun snowy(sky: Sky?) = sky == Sky.SNOW || sky == Sky.SLEET

// ------------------------------------------------------------------ сцены

@Composable
private fun LiveScene(kind: String, hour: Float, sky: Sky?, animate: Boolean, paused: () -> Boolean) {
    // Окна города мигают раз в 15 секунд; остальное статичное перестраиваем раз в 5 минут.
    val cityTick by produceState(0L, kind) {
        if (kind != "scene:city") return@produceState
        while (true) { value = System.currentTimeMillis() / 15_000; delay(15_000) }
    }
    val slot = (hour * 12).toInt()
    val still by produceState<ImageBitmap?>(null, kind, slot, sky, cityTick) {
        value = withContext(Dispatchers.Default) {
            when (kind) {
                "scene:city" -> buildCity(hour, sky, cityTick)
                "scene:fire" -> buildFireplace()
                else -> buildLandscape(hour, sky)
            }.asImageBitmap()
        }
    }
    val look = remember(slot, sky) { skyLook(hour, sky) }
    val time = frameTime(animate, if (kind == "scene:fire") 20 else 24, paused)
    val fire = remember(kind) { if (kind == "scene:fire") DoomFire(60, 40) else null }
    val stars = remember { val r = Random(7); List(70) { Triple(r.nextInt(W), r.nextInt(52), r.nextFloat() * 6f) } }
    val clouds = remember(sky) {
        val n = when (sky) { Sky.CLEAR, null -> 2; Sky.PARTLY -> 4; Sky.CLOUDY -> 6; else -> 8 }
        val r = Random(11); List(n) { floatArrayOf(r.nextFloat() * (W + 40), 4f + r.nextInt(30), 0.6f + r.nextFloat() * 1.2f, 8f + r.nextInt(10)) }
    }

    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val img = still ?: return@Canvas
        val t = time.longValue
        drawImage(img, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
        val cx = size.width / W
        val cy = size.height / H
        fun cell(x: Float, y: Float, w: Float, h: Float, c: Color) =
            drawRect(c, Offset(floor(x) * cx, floor(y) * cy), Size(w * cx, h * cy))

        when (kind) {
            "scene:fire" -> fire?.let { drawFire(it, t, cx, cy) }
            else -> {
                if (look.night > 0.2f && sky in listOf(null, Sky.CLEAR, Sky.PARTLY)) {
                    for ((x, y, ph) in stars) {
                        val a = (0.55f + 0.45f * sin(t / 700f + ph)) * look.night
                        cell(x.toFloat(), y.toFloat(), 1f, 1f, Color(0xFFF4F1EA).copy(alpha = a.coerceIn(0f, 1f)))
                    }
                }
                val cloudC = lerpRgb(if (sky == Sky.CLEAR || sky == Sky.PARTLY || sky == null) 0xF4F6FA else 0xB8BCC8, 0x3A3F5A, look.night).toColor(0.92f)
                for (c in clouds) {
                    val x = ((c[0] + t / 1000f * c[2]) % (W + 40)) - 20
                    val y = c[1]; val w = c[3]
                    cell(x, y + 1, w, 2f, cloudC)
                    cell(x + w * 0.2f, y, w * 0.55f, 1f, cloudC)
                    cell(x + w * 0.3f, y - 1, w * 0.3f, 1f, cloudC)
                }
                if (kind == "scene:sky") {
                    // Дым из трубы домика.
                    for (i in 0 until 5) {
                        val age = ((t / 1000f + i * 0.7f) % 3.5f)
                        val s = 1f + age * 0.6f
                        cell(146f + age * 2.2f, 74f - age * 5f, s, s, Color(0xFFCFCFD8).copy(alpha = (0.55f - age * 0.15f).coerceIn(0f, 1f)))
                    }
                } else {
                    // Красные огни на крышах и машины внизу.
                    val blink = (t / 600) % 2 == 0L
                    if (blink) for (x in intArrayOf(31, 88, 151)) cell(x.toFloat(), cityRoof(x).toFloat() - 3, 1f, 1f, Color(0xFFFF3B3B))
                    for (i in 0 until 6) {
                        val dir = if (i % 2 == 0) 1 else -1
                        val sp = 14f + i * 3
                        val x = ((t / 1000f * sp + i * 41) % (W + 10)).let { if (dir > 0) it - 5 else W - it + 5 }
                        cell(x, if (dir > 0) 104f else 106f, 2f, 1f, if (dir > 0) Color(0xFFFFE9A0) else Color(0xFFFF5040))
                    }
                }
            }
        }
    }
}

/** Пейзаж: горы, холмы, ёлки и домик; солнце или луна идут по дуге. */
private fun buildLandscape(hour: Float, sky: Sky?): Bitmap {
    val p = Px(W, H)
    val look = skyLook(hour, sky)
    p.skyGradient(look, 80)
    val night = look.night
    // Солнце днём, луна ночью.
    if (hour in 6f..20f) {
        val k = (hour - 6f) / 14f
        val x = (16 + k * (W - 32)).toInt(); val y = (72 - sin(k * PI).toFloat() * 58).toInt()
        val dim = if (sky == null || sky == Sky.CLEAR || sky == Sky.PARTLY) 0f else 0.5f
        p.disk(x, y, 6, lerpRgb(0xFFB040, look.bottom, dim))
        p.disk(x, y, 4, lerpRgb(0xFFE07A, look.bottom, dim))
    } else {
        val k = (((hour - 20f) + 24f) % 24f) / 10f
        val x = (16 + k * (W - 32)).toInt(); val y = (70 - sin(k * PI).toFloat() * 56).toInt()
        p.disk(x, y, 5, 0xE8E6F0)
        p.set(x - 2, y - 1, 0xC4C2D2); p.set(x + 1, y + 2, 0xC4C2D2); p.set(x + 2, y - 2, 0xC4C2D2)
    }
    val snow = snowy(sky)
    val layers = listOf(
        Triple(66f to 14f, lerpRgb(lerpRgb(0x5A6A9A, look.bottom, 0.35f), 0x161A30, night), 1.3f),
        Triple(82f to 7f, lerpRgb(0x3E7A4A, 0x142018, night), 4.2f),
        Triple(96f to 4f, lerpRgb(0x24502E, 0x0A140C, night), 8.9f),
    )
    for ((li, layer) in layers.withIndex()) {
        val (geo, color, seed) = layer
        for (x in 0 until W) {
            val top = ridge(x, seed, geo.first, geo.second)
            for (y in top until H) p.set(x, y, color)
            if (snow || (li == 0 && geo.first - top > 10)) p.set(x, top, lerpRgb(0xF0F4FA, 0x8890A8, night))
        }
    }
    // Ёлки на переднем плане.
    val r = Random(3)
    var x = 4
    val tree = lerpRgb(0x1B3C22, 0x060C07, night)
    while (x < W) {
        if (x !in 132..160) {
            val base = ridge(x, 8.9f, 96f, 4f)
            val h = 8 + r.nextInt(7)
            for (i in 0 until h) {
                val half = (i * 3 / 5).coerceAtLeast(0)
                for (dx in -half..half) p.set(x + dx, base - h + i, if (snow && i % 3 == 0) 0xE8EEF6 else tree)
            }
            p.rect(x, base, 1, 2, lerpRgb(0x4A2E1E, 0x140C08, night))
        }
        x += 7 + r.nextInt(11)
    }
    // Домик с трубой.
    val hx = 136; val hy = ridge(144, 8.9f, 96f, 4f) - 9
    p.rect(hx, hy, 15, 10, lerpRgb(0x8A5A3E, 0x24160F, night))
    for (i in 0..5) p.rect(hx - 1 + i, hy - 1 - i, 17 - i * 2, 1, if (snow) 0xE8EEF6 else lerpRgb(0xA0423A, 0x30120F, night))
    p.rect(hx + 11, hy - 6, 2, 4, lerpRgb(0x6A3A2A, 0x1A0E0A, night))
    val lit = night > 0.3f
    p.rect(hx + 3, hy + 3, 3, 3, if (lit) 0xFFD27A else lerpRgb(0x9AC6E0, 0x303850, night))
    p.rect(hx + 9, hy + 4, 3, 6, lerpRgb(0x4A2E1E, 0x140C08, night))
    return p.bitmap()
}

private val CITY = run {
    // Силуэт: (x, ширина, высота) для дальнего и ближнего рядов.
    val r = Random(21)
    val back = mutableListOf<Triple<Int, Int, Int>>()
    var x = -4
    while (x < W) { val w = 8 + r.nextInt(12); back += Triple(x, w, 30 + r.nextInt(40)); x += w }
    val front = mutableListOf<Triple<Int, Int, Int>>()
    x = -2
    while (x < W) { val w = 10 + r.nextInt(16); front += Triple(x, w, 18 + r.nextInt(48)); x += w + r.nextInt(3) }
    back to front
}

private fun cityRoof(x: Int): Int {
    val b = CITY.second.firstOrNull { x >= it.first && x < it.first + it.second } ?: return 100
    return 102 - b.third
}

/** Город: два ряда домов, окна зажигаются вечером и гаснут к ночи. */
private fun buildCity(hour: Float, sky: Sky?, tick: Long): Bitmap {
    val p = Px(W, H)
    val look = skyLook(hour, sky)
    p.skyGradient(look, 90)
    val night = look.night
    val litP = when (hour.toInt()) { in 8..16 -> 0.04f; in 17..18 -> 0.35f; in 19..23 -> 0.6f; in 0..1 -> 0.3f; in 2..5 -> 0.08f; else -> 0.3f }
    val (back, front) = CITY
    var wi = 0
    fun building(b: Triple<Int, Int, Int>, color: Int, dimWin: Float) {
        val (bx, bw, bh) = b
        val top = 102 - bh
        p.rect(bx, top, bw, bh, color)
        for (y in top + 2 until 100 step 3) for (x in bx + 1 until bx + bw - 1 step 2) {
            wi++
            // У каждого окна свой ритм — свет меняется вразнобой, а не всем домом сразу.
            val period = 2 + (wi * 7) % 9
            val h = Random((wi * 131 + (tick + wi % 5) / period).toInt()).nextFloat()
            val c = if (h < litP) lerpRgb(if (wi % 3 == 0) 0xFFC060 else 0xFFDF8A, color, dimWin)
            else lerpRgb(0x3A5070, color, 0.5f + night * 0.4f)
            p.set(x, y, c)
        }
    }
    back.forEach { building(it, lerpRgb(lerpRgb(0x4A5068, look.bottom, 0.3f), 0x1E2238, night), 0.45f) }
    front.forEach { building(it, lerpRgb(0x2A2E40, 0x0E1018, night), 0f) }
    for (x in intArrayOf(31, 88, 151)) p.rect(x, cityRoof(x) - 2, 1, 2, 0x50586A)
    p.rect(0, 102, W, 6, 0x15161C)
    for (x in 0 until W step 8) p.rect(x, 105, 4, 1, 0x4A4A50)
    if (snowy(sky)) front.forEach { p.rect(it.first, 102 - it.third, it.second, 1, 0xE8EEF6) }
    return p.bitmap()
}

/** Камин: кирпичная стена, каменный портал, полка и поленья. Огонь рисуется отдельно. */
private fun buildFireplace(): Bitmap {
    val p = Px(W, H)
    for (y in 0 until H) for (x in 0 until W) {
        val row = y / 4
        val off = if (row % 2 == 0) 0 else 5
        val mortar = y % 4 == 3 || (x + off) % 10 == 9
        val v = Random(row * 97 + (x + off) / 10).nextInt(3)
        p.set(x, y, if (mortar) 0x2A1612 else intArrayOf(0x5A2E24, 0x52291F, 0x63342A)[v])
    }
    // Мягкая виньетка к краям.
    for (y in 0 until H) for (x in 0 until W) {
        val d = (abs(x - W / 2f) / (W / 2f)).let { it * it } * 0.6f + (y / H.toFloat()) * 0.1f
        val i = y * W + x
        p.a[i] = lerpRgb(p.a[i], 0x0A0504, d)
    }
    p.rect(48, 34, 96, 5, 0x4A2E1E)   // полка
    p.rect(48, 38, 96, 1, 0x2E1C12)
    p.rect(56, 39, 80, 58, 0x6E6A66)  // портал
    for (y in 39 until 97 step 5) for (x in 56 until 136 step 9) p.rect(x + (y / 5 % 2) * 4, y, 1, 5, 0x55524F)
    p.rect(66, 52, 60, 45, 0x0A0605) // топка
    p.rect(0, 97, W, 11, 0x3A2418)    // пол
    for (y in 99 until H step 3) p.rect(0, y, W, 1, 0x2C1B12)
    // Поленья.
    p.rect(74, 90, 44, 4, 0x5A3A22)
    p.rect(80, 87, 32, 3, 0x6A4428)
    p.set(74, 91, 0x8A6040); p.set(117, 91, 0x8A6040)
    // Свечи и рамка на полке.
    p.rect(60, 28, 2, 6, 0xE8E0C8); p.rect(130, 27, 2, 7, 0xE8E0C8)
    p.rect(88, 22, 16, 12, 0x8A6A3A); p.rect(90, 24, 12, 8, 0x2A3A5A)
    return p.bitmap()
}

private val FIRE_PALETTE = intArrayOf(
    0x070707, 0x1F0707, 0x2F0F07, 0x470F07, 0x571707, 0x671F07, 0x771F07, 0x8F2707, 0x9F2F07, 0xAF3F07,
    0xBF4707, 0xC74707, 0xDF4F07, 0xDF5707, 0xDF5707, 0xD75F07, 0xD75F07, 0xD7670F, 0xCF6F0F, 0xCF770F,
    0xCF7F0F, 0xCF8717, 0xC78717, 0xC78F17, 0xC7971F, 0xBF9F1F, 0xBF9F1F, 0xBFA727, 0xBFA727, 0xBFAF2F,
    0xB7AF2F, 0xB7B72F, 0xB7B737, 0xCFCF6F, 0xDFDF9F, 0xEFEFC7, 0xFFFFFF,
)

/** Классический «огонь из Doom»: тепло поднимается снизу, остывая и сносясь в стороны. */
private class DoomFire(val w: Int, val h: Int) {
    val heat = IntArray(w * h)
    val px = IntArray(w * h)
    val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val img = bmp.asImageBitmap()
    private val r = Random(5)
    var lastStep = 0L

    fun step() {
        for (x in 0 until w) {
            // Под поленьями жарче, к краям топки — слабее.
            val edge = abs(x - w / 2f) / (w / 2f)
            heat[(h - 1) * w + x] = if (edge > 0.85f) 0 else (36 - (edge * 10).toInt() - r.nextInt(5)).coerceAtLeast(0)
        }
        for (y in 0 until h - 1) for (x in 0 until w) {
            val src = heat[(y + 1) * w + x]
            val rnd = r.nextInt(4)
            val dx = (x - (rnd and 1) + r.nextInt(2)).coerceIn(0, w - 1)
            heat[y * w + dx] = (src - (rnd shr 1) - (if (r.nextInt(3) == 0) 1 else 0)).coerceAtLeast(0)
        }
        for (i in heat.indices) px[i] = if (heat[i] < 3) 0 else rgb(FIRE_PALETTE[heat[i]])
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}

private fun DrawScope.drawFire(fire: DoomFire, t: Long, cx: Float, cy: Float) {
    if (t - fire.lastStep >= 50 || fire.lastStep == 0L) { fire.step(); fire.lastStep = t }
    drawImage(
        fire.img,
        dstOffset = IntOffset((66 * cx).toInt(), (52 * cy).toInt()),
        dstSize = IntSize((60 * cx).toInt(), (40 * cy).toInt()),
        filterQuality = FilterQuality.None,
    )
    // Тёплый отсвет по комнате мерцает вместе с пламенем.
    val flicker = 0.13f + 0.05f * sin(t / 90f) + 0.03f * sin(t / 37f)
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFFFF9A3C).copy(alpha = flicker), Color.Transparent),
            center = Offset(96 * cx, 80 * cy), radius = size.width * 0.55f,
        ),
    )
    // Искры и огоньки свечей.
    for (i in 0 until 10) {
        val age = ((t / 1000f + i * 0.37f) % 2.2f)
        val x = 80 + (i * 37 % 32) + sin(age * 3f + i) * 3f
        val y = 70 - age * 14f
        if (y > 52) drawRect(Color(0xFFFFB040).copy(alpha = (1f - age / 2.2f)), Offset(floor(x) * cx, floor(y) * cy), Size(cx, cy))
    }
    for (x in intArrayOf(60, 130)) {
        val k = if ((t / 140 + x) % 3 == 0L) 2f else 3f
        drawRect(Color(0xFFFFC860), Offset(x * cx, (27 - k + if (x == 130) -1 else 0) * cy), Size(2 * cx, k * cy))
    }
}

// ------------------------------------------------------------------ погода поверх обоев

/** Дождь, снег, туман и вспышки грозы по реальной погоде. Пиксели привязаны к той же сетке, что и сцены. */
@Composable
fun WeatherParticles(sky: Sky, paused: () -> Boolean = { false }) {
    val kind = when (sky) {
        Sky.DRIZZLE -> 1; Sky.RAIN -> 2; Sky.THUNDER -> 3; Sky.SNOW -> 4; Sky.SLEET -> 5; Sky.FOG -> 6
        else -> return
    }
    val time = frameTime(true, 24, paused)
    val drops = remember(kind) {
        val n = when (kind) { 1 -> 45; 2, 3 -> 110; 4 -> 90; 5 -> 80; else -> 6 }
        val r = Random(kind)
        List(n) { floatArrayOf(r.nextFloat() * W, r.nextFloat() * H, 0.7f + r.nextFloat() * 0.6f, r.nextFloat() * 6f) }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val t = time.longValue / 1000f
        val cx = size.width / W
        val cy = size.height / H
        for ((i, d) in drops.withIndex()) {
            val snow = kind == 4 || (kind == 5 && i % 2 == 0)
            when {
                kind == 6 -> {
                    val y = d[1] * 0.8f + 10
                    val x = ((d[0] + t * 3f * d[2]) % (W + 60)) - 60
                    drawRect(Color(0xFFD8DCE4).copy(alpha = 0.07f), Offset(floor(x) * cx, floor(y) * cy), Size(60 * cx, 4 * cy))
                }
                snow -> {
                    val y = (d[1] + t * 9f * d[2]) % H
                    val x = (d[0] + sin(t * 0.9f + d[3]) * 3f + W) % W
                    val s = if (d[2] > 1.1f) 2f else 1f
                    drawRect(Color.White.copy(alpha = 0.75f), Offset(floor(x) * cx, floor(y) * cy), Size(s * cx, s * cy))
                }
                else -> {
                    val speed = if (kind == 1) 45f else 85f
                    val y = (d[1] + t * speed * d[2]) % (H + 6) - 6
                    val x = (d[0] - (y + 6) * 0.15f + W) % W
                    val len = if (kind == 1) 2f else 3f + d[2]
                    drawRect(Color(0xFFB8CCE8).copy(alpha = if (kind == 1) 0.35f else 0.5f), Offset(floor(x) * cx, floor(y) * cy), Size(cx * 0.6f, len * cy))
                }
            }
        }
        if (kind == 3) {
            // Вспышка молнии раз в ~11 секунд: два коротких всполоха.
            val ph = t % 11f
            val a = when { ph < 0.08f -> 0.35f; ph in 0.18f..0.26f -> 0.22f; else -> 0f }
            if (a > 0f) drawRect(Color.White.copy(alpha = a))
        }
    }
}

/** Обои, присланные с телефона, лежат во внутренней памяти лаунчера. */
fun uploadedWallpaperDir(context: android.content.Context) = java.io.File(context.filesDir, "wallpapers")

/** Картинки для обоев: присланные с телефона, Pictures/Wallpapers и Pictures. */
fun listWallpapers(context: android.content.Context = PixelDenApp.instance): List<String> {
    val pics = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
    return listOf(uploadedWallpaperDir(context), java.io.File(pics, "Wallpapers"), pics)
        .flatMap { dir -> dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty() }
        .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
        .map { it.absolutePath }
        .distinct()
}

/** Неподвижный кадр сцены — для миниатюр в настройках. */
fun sceneStill(kind: String): Bitmap = when (kind) {
    "scene:city" -> buildCity(currentHour(), null, System.currentTimeMillis() / 15_000)
    "scene:fire" -> buildFireplace()
    else -> buildLandscape(currentHour(), null)
}

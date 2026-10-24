package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.system.Health
import com.pixelden.launcher.system.SleepTimer
import com.pixelden.launcher.system.gb
import com.pixelden.launcher.weather.Holidays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Виджеты главного экрана: id, название для настроек. */
val WIDGETS = listOf(
    "calendar" to tr("Праздники"),
    "rates" to tr("Курсы валют"),
    "timer" to tr("Таймер сна"),
    "health" to tr("Память ТВ"),
    "sun" to tr("Восход и закат"),
    "net" to tr("Интернет"),
    "moon" to tr("Фаза луны"),
    "news" to tr("Последняя новость"),
)

/** Сколько виджетов можно включить одновременно — больше не помещается в ряд. */
const val MAX_WIDGETS = 4

/** Окна виджетов рисуются на корне главного экрана (внутри ряда они не заняли бы весь экран). */
object WidgetDialogs {
    var rates by mutableStateOf(false)
}

private val ICON_CAL = listOf("#.#...#.#", "#########", "#+++++++#", "#+#+#+#+#", "#+++++++#", "#+#+#+#+#", "#+++++++#", "#########", ".........")
private val ICON_RATE = listOf("....#....", "..#####..", ".#+.#....", ".#+.#....", "..#####..", "....#.+#.", "....#.+#.", "..#####..", "....#....")
private val ICON_MOON = listOf("...###...", "..##.....", ".##......", ".##...+..", ".##..+++.", ".##...+..", "..##.....", "...###...", ".........")
private val ICON_RAM = listOf(".#.#.#.#.", "#########", "#+++++++#", "#+##+##+#", "#+##+##+#", "#+++++++#", "#########", ".#.#.#.#.", ".........")

@Composable
fun WidgetRow(cfg: LauncherConfig, onTopFocus: () -> Unit, onOpenHealth: () -> Unit) {
    Row(
        Modifier.padding(bottom = 12.dp).onFocusChanged { if (it.hasFocus) onTopFocus() },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (id in cfg.widgets.take(MAX_WIDGETS)) when (id) {
            "calendar" -> CalendarTile()
            "rates" -> RatesTile()
            "timer" -> TimerTile()
            "health" -> HealthTile(onOpenHealth)
            "sun" -> SunTile(cfg)
            "net" -> NetTile()
            "moon" -> MoonTile()
            "news" -> NewsTile()
        }
    }
}

@Composable
private fun WidgetTile(
    icon: List<String>,
    title: String,
    value: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val t = LocalDen.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .widthIn(min = 230.dp, max = 440.dp)
            .tvClickable(onFocus = { focused = it }, onLongClick = onLongClick, onClick = onClick)
            .pixelBox(if (focused) t.colors.accent else Color.Transparent, (if (focused) t.colors.surfaceHi else t.colors.surface).copy(alpha = 0.88f), t.border)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIcon(icon, 3, if (focused) t.colors.accent else t.colors.text.copy(alpha = 0.7f), if (focused) t.colors.text else t.colors.surfaceHi)
        Column(Modifier.padding(start = 12.dp)) {
            PText(title, 13.sp, t.colors.dim)
            PText(value, 16.sp, if (focused) t.colors.text else t.colors.text.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun RatesTile() {
    val app = PixelDenApp.instance
    val cfg by app.store.config.collectAsState()
    val rates by app.rates.rates.collectAsState()
    val base = com.pixelden.launcher.weather.Currencies.base(cfg)
    val codes = com.pixelden.launcher.weather.Currencies.shown(cfg)
    // Сменили валюты — перезагрузить курсы.
    LaunchedEffect(base, codes) { if (rates.map { it.code } != codes) app.rates.refresh() }
    val value = codes.joinToString("  ") { c -> c + " " + (rates.firstOrNull { it.code == c }?.let { fmt2(it.value) } ?: "…") }
    WidgetTile(ICON_RATE, tr("Курс к {0} · OK — выбрать", base), value) { WidgetDialogs.rates = true }
}

/** Число с двумя знаками в формате языка интерфейса (80,54 или 80.54). */
private fun fmt2(v: Double) = String.format(Locale(com.pixelden.launcher.i18n.I18n.lang), "%.2f", v)

@Composable
private fun TimerTile() {
    val endsAt by SleepTimer.endsAt.collectAsState()
    val now by produceState(System.currentTimeMillis(), endsAt) {
        while (endsAt != null) { value = System.currentTimeMillis(); delay(15_000) }
    }
    val left = endsAt?.let { ((it - now + 59_999) / 60_000).toInt() }
    WidgetTile(
        ICON_MOON, tr("Таймер сна"),
        if (left == null) tr("выкл · OK — включить") else tr("через {0} мин", left),
        onLongClick = { SleepTimer.set(0) },
    ) { SleepTimer.set(SleepTimer.nextStep()) }
}

@Composable
private fun HealthTile(onClick: () -> Unit) {
    val context = LocalContext.current
    val snap by produceState<com.pixelden.launcher.system.HealthSnapshot?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { Health.snapshot(context) }.getOrNull() }
            delay(10_000)
        }
    }
    val s = snap
    WidgetTile(
        ICON_RAM, tr("Свободно памяти"),
        if (s == null) "…" else tr("{0} из {1} ГБ", (gb(s.ramAvail)), (gb(s.ramTotal))) + (s.tempC?.let { " · ${it.toInt()}°" } ?: ""),
        onClick = onClick,
    )
}


/** Сегодняшний или ближайший праздник. Просто плитка — без отдельного окна. */
@Composable
private fun CalendarTile() {
    val (title, value) = remember {
        val today = Holidays.today()
        if (today != null) tr("Сегодня") to today
        else Holidays.next()?.let { (name, d) -> tr("Через {0}", (days(d))) to name } ?: (tr("Праздники") to "—")
    }
    WidgetTile(ICON_CAL, title, value) {}
}

private fun days(n: Int) = "$n " + when {
    n % 10 == 1 && n % 100 != 11 -> tr("день")
    n % 10 in 2..4 && n % 100 !in 12..14 -> tr("дня")
    else -> tr("дней")
}

private val ICON_SUN = listOf("....y....", ".y.....y.", "...yyy...", "..yyyyy..", "y.yyyyy.y", "..yyyyy..", "...yyy...", ".y.....y.", "....y....")
private val ICON_NET = listOf("....#....", "...###...", "..#.#.#..", "....#....", "....#....", "..#.#.#..", "...###...", "....#....", ".........")
private val ICON_FULL = listOf("...###...", ".##+++##.", ".#+++++#.", "#+++++++#", "#+++++++#", "#+++++++#", ".#+++++#.", ".##+++##.", "...###...")
private val ICON_NEWS = listOf("#########", "#+++++++#", "#+#####+#", "#+++++++#", "#+##+##+#", "#+++++++#", "#+##+##+#", "#########", ".........")

/** Восход и закат по координатам города — считается на ТВ, без интернета. */
@Composable
private fun SunTile(cfg: LauncherConfig) {
    val lat = cfg.weatherLat
    val lon = cfg.weatherLon
    val value = remember(lat, lon, Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) {
        if (lat == null || lon == null) tr("выберите город в «Погоде»") else {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val (rise, set) = sunTimes(lat, lon)
            "↑ " + (rise?.let { fmt.format(it) } ?: "—") + "   ↓ " + (set?.let { fmt.format(it) } ?: "—")
        }
    }
    WidgetTile(ICON_SUN, tr("Восход и закат"), value) {}
}

/** Алгоритм NOAA (точность ~минута): время восхода и заката сегодня, по местному времени. */
private fun sunTimes(lat: Double, lon: Double): Pair<java.util.Date?, java.util.Date?> {
    val cal = Calendar.getInstance()
    val n = cal.get(Calendar.DAY_OF_YEAR)
    val lngHour = lon / 15
    fun rad(d: Double) = Math.toRadians(d)
    fun deg(r: Double) = Math.toDegrees(r)
    fun norm(v: Double, m: Double) = ((v % m) + m) % m
    fun at(rising: Boolean): java.util.Date? {
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * Math.sin(rad(m)) + 0.020 * Math.sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(Math.atan(0.91764 * Math.tan(rad(l)))), 360.0)
        ra = (ra + (Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90)) / 15
        val sinDec = 0.39782 * Math.sin(rad(l))
        val cosDec = Math.cos(Math.asin(sinDec))
        val cosH = (Math.cos(rad(90.833)) - sinDec * Math.sin(rad(lat))) / (cosDec * Math.cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null   // полярный день или ночь
        val h = (if (rising) 360 - deg(Math.acos(cosH)) else deg(Math.acos(cosH))) / 15
        val ut = norm(h + ra - 0.06571 * t - 6.622 - lngHour, 24.0)
        val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        c.add(Calendar.SECOND, (ut * 3600).toInt())
        return c.time
    }
    return at(true) to at(false)
}

/** Сколько ТВ скачал и отправил с момента включения. */
@Composable
private fun NetTile() {
    val value by produceState("…") {
        while (true) {
            val rx = android.net.TrafficStats.getTotalRxBytes()
            val tx = android.net.TrafficStats.getTotalTxBytes()
            value = if (rx < 0) tr("нет данных") else "↓ ${size(rx)}   ↑ ${size(tx)}"
            delay(10_000)
        }
    }
    WidgetTile(ICON_NET, tr("Интернет с включения ТВ"), value) {}
}

private fun size(b: Long): String =
    if (b >= 1_073_741_824) String.format(Locale(com.pixelden.launcher.i18n.I18n.lang), "%.1f ", b / 1_073_741_824.0) + tr("ГБ")
    else "${b / 1_048_576} " + tr("МБ")

/** Фаза луны по синодическому месяцу (точности «на глаз» хватает). */
@Composable
private fun MoonTile() {
    val (name, lit) = remember(Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) {
        val synodic = 29.530588853
        val knownNew = 947182440000L   // новолуние 6 января 2000, 18:14 UTC
        val age = (((System.currentTimeMillis() - knownNew) / 86_400_000.0) % synodic + synodic) % synodic
        val lit = ((1 - Math.cos(2 * Math.PI * age / synodic)) / 2 * 100).toInt()
        val name = when {
            age < 1.85 -> tr("новолуние")
            age < 5.54 -> tr("растущий серп")
            age < 9.23 -> tr("первая четверть")
            age < 12.92 -> tr("растущая луна")
            age < 16.61 -> tr("полнолуние")
            age < 20.30 -> tr("убывающая луна")
            age < 23.99 -> tr("последняя четверть")
            age < 27.68 -> tr("убывающий серп")
            else -> tr("новолуние")
        }
        name to lit
    }
    WidgetTile(ICON_FULL, tr("Луна · {0}%", lit), name) {}
}

/** Свежий заголовок из выбранного источника новостей; OK — следующий. */
@Composable
private fun NewsTile() {
    val news by PixelDenApp.instance.news.titles.collectAsState()
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { PixelDenApp.instance.news.start() }
    val title = news.getOrNull(i % maxOf(1, news.size)) ?: tr("загружаю…")
    WidgetTile(ICON_NEWS, tr("Новости · OK — следующая"), title) { i++ }
}

/** Выбор валют для виджета курсов: валюта счёта и до трёх валют. */
@Composable
fun RatesChooser(cfg: LauncherConfig) {
    val app = PixelDenApp.instance
    val context = LocalContext.current
    val base = com.pixelden.launcher.weather.Currencies.base(cfg)
    val shown = com.pixelden.launcher.weather.Currencies.shown(cfg)
    val all = com.pixelden.launcher.weather.Currencies.ALL
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun reload() = scope.launch { app.rates.refresh() }
    Overlay(tr("Курсы валют"), onDismiss = { WidgetDialogs.rates = false }) {
        item {
            SettingRow(tr("Валюта счёта"), "< $base >", focusRequester = rememberAutoFocus(),
                hint = tr("В чём считать: сколько {0} стоит 1 единица валюты", base),
                onLeft = { app.store.update { it.copy(ratesBase = all.cycle(base, -1)) }; reload() },
                onRight = { app.store.update { it.copy(ratesBase = all.cycle(base, 1)) }; reload() })
        }
        item { PText(tr("Показывать (до {0}):", com.pixelden.launcher.weather.Currencies.MAX_SHOWN), 15.sp, LocalDen.current.colors.dim, Modifier.padding(4.dp, 8.dp, 0.dp, 6.dp)) }
        all.filter { it != base }.forEach { code ->
            item {
                ToggleRow(code, code in shown) { on ->
                    if (on && shown.size >= com.pixelden.launcher.weather.Currencies.MAX_SHOWN) {
                        android.widget.Toast.makeText(context, tr("Не больше {0} валют — сначала выключите какую-нибудь", com.pixelden.launcher.weather.Currencies.MAX_SHOWN), android.widget.Toast.LENGTH_SHORT).show()
                        return@ToggleRow
                    }
                    app.store.update { it.copy(rateCodes = if (on) shown + code else shown - code) }
                    reload()
                }
            }
        }
        item { SettingRow(tr("Готово"), onClick = { WidgetDialogs.rates = false }) }
    }
}

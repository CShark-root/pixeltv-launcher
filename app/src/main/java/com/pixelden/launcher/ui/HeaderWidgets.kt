package com.pixelden.launcher.ui

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
    "calendar" to "Календарь и праздники",
    "rates" to "Курсы валют",
    "timer" to "Таймер сна",
    "health" to "Память ТВ",
)

private val ICON_CAL = listOf("#.#...#.#", "#########", "#+++++++#", "#+#+#+#+#", "#+++++++#", "#+#+#+#+#", "#+++++++#", "#########", ".........")
private val ICON_RATE = listOf("....#....", "..#####..", ".#+.#....", ".#+.#....", "..#####..", "....#.+#.", "....#.+#.", "..#####..", "....#....")
private val ICON_MOON = listOf("...###...", "..##.....", ".##......", ".##...+..", ".##..+++.", ".##...+..", "..##.....", "...###...", ".........")
private val ICON_RAM = listOf(".#.#.#.#.", "#########", "#+++++++#", "#+##+##+#", "#+##+##+#", "#+++++++#", "#########", ".#.#.#.#.", ".........")

@Composable
fun WidgetRow(cfg: LauncherConfig, onTopFocus: () -> Unit, onOpenHealth: () -> Unit) {
    var calendar by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(bottom = 12.dp).onFocusChanged { if (it.hasFocus) onTopFocus() },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (id in cfg.widgets) when (id) {
            "calendar" -> CalendarTile { calendar = true }
            "rates" -> RatesTile()
            "timer" -> TimerTile()
            "health" -> HealthTile(onOpenHealth)
        }
    }
    if (calendar) CalendarOverlay { calendar = false }
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
            .widthIn(min = 230.dp)
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
private fun CalendarTile(onClick: () -> Unit) {
    val (title, value) = remember {
        val today = Holidays.today()
        if (today != null) "Сегодня" to today
        else Holidays.next()?.let { (name, d) -> "Через ${days(d)}" to name } ?: ("Календарь" to "")
    }
    WidgetTile(ICON_CAL, title, value, onClick = onClick)
}

private fun days(n: Int) = "$n " + when {
    n % 10 == 1 && n % 100 != 11 -> "день"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "дня"
    else -> "дней"
}

@Composable
private fun RatesTile() {
    val repo = PixelDenApp.instance.rates
    val rates by repo.rates.collectAsState()
    val usd = rates.firstOrNull { it.code == "USD" }
    val eur = rates.firstOrNull { it.code == "EUR" }
    fun fmt(r: com.pixelden.launcher.weather.Rate?, code: String) = "$code " + (r?.let { "%.2f".format(it.value) } ?: "…")
    // OK переключает: курсы ↔ изменение за день.
    var change by remember { mutableStateOf(false) }
    fun delta(r: com.pixelden.launcher.weather.Rate?, code: String) =
        "$code " + (r?.let { val d = it.value - it.previous; (if (d >= 0) "+" else "") + "%.2f".format(d) } ?: "…")
    val value = if (change) delta(usd, "USD") + "  " + delta(eur, "EUR") else fmt(usd, "USD") + "  " + fmt(eur, "EUR")
    WidgetTile(ICON_RATE, if (change) "Изменение за день" else "Курс ЦБ", value) { change = !change }
}

@Composable
private fun TimerTile() {
    val endsAt by SleepTimer.endsAt.collectAsState()
    val now by produceState(System.currentTimeMillis(), endsAt) {
        while (endsAt != null) { value = System.currentTimeMillis(); delay(15_000) }
    }
    val left = endsAt?.let { ((it - now + 59_999) / 60_000).toInt() }
    WidgetTile(
        ICON_MOON, "Таймер сна",
        if (left == null) "выкл · OK — включить" else "через $left мин",
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
        ICON_RAM, "Свободно памяти",
        if (s == null) "…" else "${gb(s.ramAvail)} из ${gb(s.ramTotal)} ГБ" + (s.tempC?.let { " · ${it.toInt()}°" } ?: ""),
        onClick = onClick,
    )
}

/** Календарь месяца: праздники подсвечены, < > листают месяцы. */
@Composable
private fun CalendarOverlay(onDismiss: () -> Unit) {
    val t = LocalDen.current
    var shift by remember { mutableIntStateOf(0) }
    val month = remember(shift) { Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, shift) } }
    val title = remember(shift) { SimpleDateFormat("LLLL yyyy", Locale("ru")).format(month.time).replaceFirstChar { it.uppercase() } }
    val today = remember { Calendar.getInstance() }
    val holidays = remember(shift) {
        val c = month.clone() as Calendar
        (1..c.getActualMaximum(Calendar.DAY_OF_MONTH)).mapNotNull { d -> c.set(Calendar.DAY_OF_MONTH, d); Holidays.today(c)?.let { d to it } }
    }
    Overlay("Календарь", onDismiss, width = 640.dp) {
        item {
            SettingRow(title, "", focusRequester = rememberAutoFocus(), hint = "< > — другой месяц · BACK — закрыть",
                onLeft = { shift-- }, onRight = { shift++ }, onClick = onDismiss)
        }
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Cell(it, t.colors.dim) } }
                val c = month.clone() as Calendar
                val lead = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
                val max = c.getActualMaximum(Calendar.DAY_OF_MONTH)
                val isThisMonth = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.MONTH) == today.get(Calendar.MONTH)
                val hol = holidays.map { it.first }.toSet()
                ((1 - lead)..max).chunked(7).forEach { week ->
                    Row {
                        week.forEach { d ->
                            val isToday = isThisMonth && d == today.get(Calendar.DAY_OF_MONTH)
                            val weekend = (d + lead - 1) % 7 >= 5
                            Cell(
                                if (d < 1) "" else "$d",
                                when { isToday -> t.colors.bg; d in hol -> Color(0xFFFFD25A); weekend -> t.colors.accent; else -> t.colors.text },
                                if (isToday) t.colors.accent else null,
                            )
                        }
                    }
                }
            }
        }
        holidays.forEach { (d, name) -> item { PText("$d — $name", 15.sp, t.colors.dim, Modifier.padding(top = 4.dp)) } }
        if (holidays.isEmpty()) item { PText("В этом месяце праздников нет", 15.sp, t.colors.dim) }
    }
}

@Composable
private fun Cell(text: String, color: Color, fill: Color? = null) {
    val t = LocalDen.current
    Box(
        Modifier.size(78.dp, 38.dp).let { if (fill != null) it.pixelBox(Color.Transparent, fill, 2) else it },
        contentAlignment = Alignment.Center,
    ) { PText(text, 17.sp, color) }
}

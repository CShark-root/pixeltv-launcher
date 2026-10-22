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
    "calendar" to "Праздники",
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
    Row(
        Modifier.padding(bottom = 12.dp).onFocusChanged { if (it.hasFocus) onTopFocus() },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (id in cfg.widgets) when (id) {
            "calendar" -> CalendarTile()
            "rates" -> RatesTile()
            "timer" -> TimerTile()
            "health" -> HealthTile(onOpenHealth)
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


/** Сегодняшний или ближайший праздник. Просто плитка — без отдельного окна. */
@Composable
private fun CalendarTile() {
    val (title, value) = remember {
        val today = Holidays.today()
        if (today != null) "Сегодня" to today
        else Holidays.next()?.let { (name, d) -> "Через ${days(d)}" to name } ?: ("Праздники" to "—")
    }
    WidgetTile(ICON_CAL, title, value) {}
}

private fun days(n: Int) = "$n " + when {
    n % 10 == 1 && n % 100 != 11 -> "день"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "дня"
    else -> "дней"
}

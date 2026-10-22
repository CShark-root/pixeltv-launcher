package com.pixelden.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.system.Health
import com.pixelden.launcher.system.HealthSnapshot
import com.pixelden.launcher.system.RecentApp
import com.pixelden.launcher.system.gb
import com.pixelden.launcher.system.mb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Страница «Здоровье ТВ»: обновляется каждые 2 секунды, пока открыта. */
@Composable
fun HealthPanel() {
    val app = PixelDenApp.instance
    val context = LocalContext.current
    val t = LocalDen.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<HealthSnapshot?>(null) }
    var recent by remember { mutableStateOf<List<RecentApp>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        var n = 0
        while (true) {
            snap = withContext(Dispatchers.IO) { runCatching { Health.snapshot(context) }.getOrNull() }
            // Список приложений дороже — раз в 10 секунд.
            if (n++ % 5 == 0) recent = withContext(Dispatchers.IO) { runCatching { Health.recentApps(context) }.getOrDefault(emptyList()) }
            delay(2_000)
        }
    }

    val s = snap
    val labels = remember(app.apps.apps.value) { app.apps.apps.value.associate { it.pkg to (app.store.current.renamed[it.pkg] ?: it.label) } }

    Column(Modifier.fillMaxWidth()) {
        if (s == null) { PText("Считаю…", 18.sp, t.colors.dim); return@Column }
        val used = s.ramTotal - s.ramAvail
        Meter("Оперативная память", "занято ${gb(used)} из ${gb(s.ramTotal)} ГБ" + if (s.lowMemory) " · мало!" else "", used.toFloat() / s.ramTotal)
        SettingRow("PixelTv занимает", "${mb(s.launcherPss)} МБ", hint = "Сам лаунчер вместе с обоями и иконками")
        val usedDisk = s.storageTotal - s.storageFree
        Meter("Память для приложений", "свободно ${gb(s.storageFree)} из ${gb(s.storageTotal)} ГБ", usedDisk.toFloat() / s.storageTotal)
        SettingRow("Температура", s.tempC?.let { "${"%.0f".format(it)}°C" } ?: "нет датчика",
            hint = s.tempC?.let { if (it >= 80) "Горячо — проверьте, не закрыты ли вентиляционные щели" else "В норме" })
        SettingRow("Процессор", "${s.cpuCores} " + (if (s.cpuCores in 2..4) "ядра" else "ядер") + (s.cpuMhz.takeIf { it.isNotEmpty() }?.let { " · ${it.max()} МГц" } ?: ""))
        SettingRow("Без перезагрузки", uptime(s.uptimeMs))
        SettingRow(
            if (busy) "Освобождаю…" else "Освободить память",
            hint = "Закрывает приложения, которые висят в фоне. Открытое сейчас не трогает.",
        ) {
            if (busy) return@SettingRow
            busy = true
            scope.launch {
                val pkgs = app.apps.apps.value.map { it.pkg } + recent.map { it.pkg }
                val freed = withContext(Dispatchers.IO) { Health.freeMemory(context, pkgs.toSet()) }
                busy = false
                val text = if (freed > 8 * 1_048_576) "Освободил ${mb(freed)} МБ!" else "Фон и так чистый."
                app.engine.notify(text)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                refresh++
            }
        }

        SectionTitle("Недавно открытые")
        if (!Health.hasUsageAccess(context)) {
            PText("Нужен доступ к статистике использования:\nadb shell appops set com.pixelden.launcher GET_USAGE_STATS allow", 14.sp, t.colors.dim, Modifier.padding(4.dp))
        } else if (recent.isEmpty()) {
            PText("За последние 6 часов ничего не открывали", 14.sp, t.colors.dim, Modifier.padding(4.dp))
        } else {
            recent.take(12).forEach { r ->
                SettingRow(
                    labels[r.pkg] ?: r.label, ago(r.lastUsed),
                    hint = "на экране ${mins(r.foregroundMs)} · OK — закрыть в фоне",
                ) {
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { Health.freeMemory(context, listOf(r.pkg)) }
                        Toast.makeText(context, "${labels[r.pkg] ?: r.label}: освобождено ${mb(freed)} МБ", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

/** Строка с полоской заполнения; краснеет, когда занято больше 85%. */
@Composable
private fun Meter(label: String, value: String, fraction: Float) {
    val t = LocalDen.current
    val f = fraction.coerceIn(0f, 1f)
    SettingRow(label, value, trailing = {
        Box(Modifier.padding(start = 16.dp).height(14.dp).fillMaxWidth(0.28f).background(t.colors.bg)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(if (f > 0.85f) Color(0xFFE5534B) else t.colors.accent))
        }
    })
}

private fun uptime(ms: Long): String {
    val h = ms / 3_600_000
    return if (h >= 24) "${h / 24} д ${h % 24} ч" else "$h ч ${ms / 60_000 % 60} мин"
}

private fun mins(ms: Long) = if (ms < 60_000) "меньше минуты" else "${ms / 60_000} мин"

private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when { m < 1 -> "только что"; m < 60 -> "$m мин назад"; else -> "${m / 60} ч назад" }
}

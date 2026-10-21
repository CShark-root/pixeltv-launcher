package com.pixelden.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.data.Action
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.data.PressType
import com.pixelden.launcher.data.Scenario
import com.pixelden.launcher.data.Trigger
import com.pixelden.launcher.scenario.DenAccessibilityService
import com.pixelden.launcher.scenario.KeyCapture
import com.pixelden.launcher.scenario.Scheduler
import com.pixelden.launcher.scenario.Templates

// ---------------------------------------------------------------- описания

private val DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

fun keyName(code: Int): String = when (code) {
    0 -> "не выбрана"
    KeyEvent.KEYCODE_MENU -> "Меню"
    KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> "Голос / Поиск"
    KeyEvent.KEYCODE_HOME -> "Домой"
    KeyEvent.KEYCODE_BACK -> "Назад"
    KeyEvent.KEYCODE_VOLUME_UP -> "Громкость +"
    KeyEvent.KEYCODE_VOLUME_DOWN -> "Громкость −"
    KeyEvent.KEYCODE_VOLUME_MUTE -> "Без звука"
    KeyEvent.KEYCODE_TV_INPUT -> "Источник"
    KeyEvent.KEYCODE_SETTINGS -> "Настройки"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}

private fun pressName(p: PressType) = when (p) {
    PressType.SHORT -> "короткое"
    PressType.LONG -> "удержание"
    PressType.DOUBLE -> "двойное"
}

fun triggerText(t: Trigger): String = when (t) {
    is Trigger.Boot -> "после загрузки, через ${t.delaySec} с"
    is Trigger.Key -> "кнопка «${keyName(t.keyCode)}», ${pressName(t.press)}"
    is Trigger.Time -> "%02d:%02d, %s".format(
        t.hour, t.minute,
        if (t.days.size == 7) "каждый день" else t.days.sorted().joinToString(" ") { DAYS[it - 1] },
    )
    Trigger.Manual -> "вручную"
}

fun actionText(a: Action, cfg: LauncherConfig): String {
    val apps = PixelDenApp.instance.apps.apps.value
    return when (a) {
        is Action.LaunchApp -> "Открыть: " + (cfg.renamed[a.pkg] ?: apps.firstOrNull { it.pkg == a.pkg }?.label ?: a.pkg.ifEmpty { "?" })
        is Action.OpenInput -> "Вход: ${a.label.ifEmpty { "?" }}"
        is Action.Volume -> "Громкость ${a.percent}%"
        is Action.Mute -> if (a.on) "Выключить звук" else "Включить звук"
        is Action.Brightness -> "Яркость ${a.percent}%"
        Action.Sleep -> "Перевести ТВ в сон"
        Action.GoHome -> "Домой"
        Action.Back -> "Назад"
        is Action.Wait -> "Подождать ${a.seconds} с"
        is Action.OpenSettings -> "Открыть настройки: " + (SETTINGS_PAGES.firstOrNull { it.second == a.intentAction }?.first ?: a.intentAction)
        is Action.ClickText -> "Нажать «${a.text}»"
        is Action.PutSetting -> "Записать ${a.namespace}/${a.key} = ${a.value}"
        is Action.Toast -> "Показать «${a.text}»"
    }
}

private val SETTINGS_PAGES = listOf(
    "Звук" to "android.settings.SOUND_SETTINGS",
    "Все настройки" to "android.settings.SETTINGS",
    "Wi-Fi" to "android.settings.WIFI_SETTINGS",
    "Экран" to "android.settings.DISPLAY_SETTINGS",
    "Приложения" to "android.settings.APPLICATION_SETTINGS",
    "Дата и время" to "android.settings.DATE_SETTINGS",
    "Для разработчиков" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
    "Спецвозможности" to "android.settings.ACCESSIBILITY_SETTINGS",
)

private val NEW_ACTIONS: List<Pair<String, () -> Action>> = listOf(
    "Открыть приложение" to { Action.LaunchApp() },
    "Переключить вход (HDMI/ТВ)" to { Action.OpenInput() },
    "Громкость" to { Action.Volume() },
    "Звук вкл/выкл" to { Action.Mute() },
    "Яркость" to { Action.Brightness() },
    "Сон" to { Action.Sleep },
    "Домой" to { Action.GoHome },
    "Назад" to { Action.Back },
    "Подождать" to { Action.Wait() },
    "Открыть страницу настроек" to { Action.OpenSettings() },
    "Нажать пункт по тексту" to { Action.ClickText() },
    "Записать системную настройку" to { Action.PutSetting() },
    "Показать сообщение" to { Action.Toast("Привет!") },
)

// ---------------------------------------------------------------- список

@Composable
fun ScenarioListScreen(cfg: LauncherConfig, onEdit: (String) -> Unit) {
    val app = PixelDenApp.instance
    val t = LocalDen.current
    val first = rememberAutoFocus()
    var menuFor by remember { mutableStateOf<Scenario?>(null) }

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 980.dp).fillMaxSize(), contentPadding = PaddingValues(40.dp, 32.dp)) {
            item { PText("Сценарии", 40.sp) }
            item { PText("OK — изменить · ◀ ▶ — вкл/выкл · удержание OK — запустить, копия, удалить", 14.sp, t.colors.dim, Modifier.padding(bottom = 12.dp)) }
            if (DenAccessibilityService.instance == null) {
                item {
                    PText("⚠ Служба PixelDen выключена: кнопки пульта и «нажать по тексту» не будут работать.", 15.sp, t.colors.accent, Modifier.padding(bottom = 10.dp), maxLines = 2)
                }
            }
            item {
                SettingRow("+ Новый сценарий", focusRequester = first) {
                    val s = Templates.blank()
                    app.store.update { it.copy(scenarios = it.scenarios + s) }
                    onEdit(s.id)
                }
            }
            items(cfg.scenarios, key = { it.id }) { s ->
                fun toggle() = updateScenario(s.id) { it.copy(enabled = !it.enabled) }
                SettingRow(
                    s.name,
                    if (s.enabled) "ВКЛ" else "ВЫКЛ",
                    hint = triggerText(s.trigger) + " · действий: ${s.actions.size}",
                    onLeft = ::toggle, onRight = ::toggle,
                    onLongClick = { menuFor = s },
                    onClick = { onEdit(s.id) },
                )
            }
        }

        menuFor?.let { s ->
            Overlay(s.name, onDismiss = { menuFor = null }) {
                item { SettingRow("▶ Запустить сейчас", focusRequester = rememberAutoFocus()) { app.engine.run(s); menuFor = null } }
                item {
                    SettingRow("Сделать копию") {
                        app.store.update { it.copy(scenarios = it.scenarios + s.copy(id = Templates.newId(), name = s.name + " (копия)", enabled = false)) }
                        menuFor = null
                    }
                }
                item {
                    SettingRow("Удалить") {
                        app.store.update { it.copy(scenarios = it.scenarios.filter { x -> x.id != s.id }) }
                        reschedule()
                        menuFor = null
                    }
                }
                item { SettingRow("Закрыть") { menuFor = null } }
            }
        }
    }
}

private fun updateScenario(id: String, f: (Scenario) -> Scenario) {
    PixelDenApp.instance.store.update { c -> c.copy(scenarios = c.scenarios.map { if (it.id == id) f(it) else it }) }
    reschedule()
}

private fun reschedule() {
    val app = PixelDenApp.instance
    Scheduler.scheduleAll(app, app.store.current)
}

// ---------------------------------------------------------------- редактор

private sealed interface EditorDialog {
    data object Name : EditorDialog
    data object CaptureKey : EditorDialog
    data object AddAction : EditorDialog
    data class EditAction(val index: Int) : EditorDialog
    data class ActionMenu(val index: Int) : EditorDialog
}

@Composable
fun ScenarioEditorScreen(cfg: LauncherConfig, id: String, onClose: () -> Unit) {
    val app = PixelDenApp.instance
    val t = LocalDen.current
    val s = cfg.scenarios.firstOrNull { it.id == id }
    if (s == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
        return
    }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    val first = rememberAutoFocus()

    fun upd(f: (Scenario) -> Scenario) = updateScenario(id, f)
    fun updTrigger(tr: Trigger) = upd { it.copy(trigger = tr) }
    fun updActions(f: (MutableList<Action>) -> Unit) = upd { it.copy(actions = it.actions.toMutableList().also(f)) }

    val triggerTypes = listOf("Вручную", "После загрузки", "Кнопка пульта", "По времени")
    val currentType = when (s.trigger) {
        Trigger.Manual -> 0; is Trigger.Boot -> 1; is Trigger.Key -> 2; is Trigger.Time -> 3
    }
    fun switchType(step: Int) {
        val next = ((currentType + step) % 4 + 4) % 4
        updTrigger(when (next) { 1 -> Trigger.Boot(); 2 -> Trigger.Key(); 3 -> Trigger.Time(); else -> Trigger.Manual })
    }

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 980.dp).fillMaxSize(), contentPadding = PaddingValues(40.dp, 32.dp)) {
            item { PText(s.name, 36.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            item { SettingRow("Название", s.name, focusRequester = first) { dialog = EditorDialog.Name } }
            item { ToggleRow("Включён", s.enabled) { v -> upd { it.copy(enabled = v) } } }

            item { SectionTitle("Когда") }
            item { SettingRow("Триггер", triggerTypes[currentType], onLeft = { switchType(-1) }, onRight = { switchType(1) }) }
            when (val tr = s.trigger) {
                is Trigger.Boot -> item {
                    SettingRow("Задержка после загрузки", "${tr.delaySec} с",
                        hint = "Службе спецвозможностей нужно ~10 с, чтобы подняться",
                        onLeft = { updTrigger(tr.copy(delaySec = tr.delaySec.stepIn(-5, 0..300))) },
                        onRight = { updTrigger(tr.copy(delaySec = tr.delaySec.stepIn(5, 0..300))) })
                }
                is Trigger.Key -> {
                    item {
                        SettingRow("Кнопка", keyName(tr.keyCode), hint = "OK — нажать нужную кнопку на пульте") { dialog = EditorDialog.CaptureKey }
                    }
                    item {
                        val types = PressType.entries
                        SettingRow("Тип нажатия", pressName(tr.press),
                            hint = when (tr.press) {
                                PressType.SHORT -> "Кнопка полностью перехватывается — её обычное действие пропадёт"
                                PressType.LONG -> "Короткое нажатие работает как обычно"
                                PressType.DOUBLE -> "Первое нажатие работает как обычно, второе запускает сценарий"
                            },
                            onLeft = { updTrigger(tr.copy(press = types.cycle(tr.press, -1))) },
                            onRight = { updTrigger(tr.copy(press = types.cycle(tr.press, 1))) })
                    }
                }
                is Trigger.Time -> {
                    item {
                        SettingRow("Часы", "%02d".format(tr.hour),
                            onLeft = { updTrigger(tr.copy(hour = (tr.hour + 23) % 24)) },
                            onRight = { updTrigger(tr.copy(hour = (tr.hour + 1) % 24)) })
                    }
                    item {
                        SettingRow("Минуты", "%02d".format(tr.minute),
                            onLeft = { updTrigger(tr.copy(minute = (tr.minute + 55) % 60)) },
                            onRight = { updTrigger(tr.copy(minute = (tr.minute + 5) % 60)) },
                            onLongClick = { updTrigger(tr.copy(minute = (tr.minute + 1) % 60)) })
                    }
                    item {
                        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DAYS.forEachIndexed { i, d ->
                                val day = i + 1
                                val on = day in tr.days
                                PButton(if (on) "■ $d" else "□ $d", size = 15.sp) {
                                    updTrigger(tr.copy(days = if (on) tr.days - day else (tr.days + day).sorted()))
                                }
                            }
                        }
                    }
                }
                Trigger.Manual -> item {
                    PText("Запуск кнопкой на главном экране или из списка сценариев", 14.sp, t.colors.dim, Modifier.padding(4.dp))
                }
            }

            item { SectionTitle("Что сделать") }
            itemsIndexed(s.actions) { i, a ->
                SettingRow(
                    "${i + 1}. ${actionText(a, cfg)}",
                    hint = "OK — изменить · ◀ ▶ — порядок · удержание OK — меню",
                    onLeft = { updActions { l -> if (i > 0) l.add(i - 1, l.removeAt(i)) } },
                    onRight = { updActions { l -> if (i < l.lastIndex) l.add(i + 1, l.removeAt(i)) } },
                    onLongClick = { dialog = EditorDialog.ActionMenu(i) },
                    onClick = { dialog = EditorDialog.EditAction(i) },
                )
            }
            item { SettingRow("+ Добавить действие") { dialog = EditorDialog.AddAction } }

            item { SectionTitle("") }
            item { SettingRow("▶ Запустить сейчас") { app.engine.run(s) } }
            item {
                SettingRow("Удалить сценарий") {
                    app.store.update { it.copy(scenarios = it.scenarios.filter { x -> x.id != id }) }
                    reschedule()
                    onClose()
                }
            }
        }

        when (val d = dialog) {
            EditorDialog.Name -> TextEditOverlay("Название", s.name, onDismiss = { dialog = null }) { name ->
                if (name.isNotBlank()) upd { it.copy(name = name.trim()) }
                dialog = null
            }
            EditorDialog.CaptureKey -> KeyCaptureOverlay(onDismiss = { dialog = null }) { code ->
                (s.trigger as? Trigger.Key)?.let { updTrigger(it.copy(keyCode = code)) }
                dialog = null
            }
            EditorDialog.AddAction -> Overlay("Новое действие", onDismiss = { dialog = null }) {
                itemsIndexed(NEW_ACTIONS) { i, (label, make) ->
                    SettingRow(label, focusRequester = if (i == 0) rememberAutoFocus() else null) {
                        val a = make()
                        updActions { it.add(a) }
                        dialog = if (a is Action.Sleep || a is Action.GoHome || a is Action.Back) null
                        else EditorDialog.EditAction(s.actions.size)
                    }
                }
            }
            is EditorDialog.ActionMenu -> Overlay("Действие ${d.index + 1}", onDismiss = { dialog = null }) {
                item { SettingRow("Изменить", focusRequester = rememberAutoFocus()) { dialog = EditorDialog.EditAction(d.index) } }
                item {
                    SettingRow("Дублировать") {
                        updActions { l -> l.getOrNull(d.index)?.let { l.add(d.index + 1, it) } }
                        dialog = null
                    }
                }
                item { SettingRow("Удалить") { updActions { l -> if (d.index in l.indices) l.removeAt(d.index) }; dialog = null } }
                item { SettingRow("Закрыть") { dialog = null } }
            }
            is EditorDialog.EditAction -> {
                val a = s.actions.getOrNull(d.index)
                if (a == null) dialog = null else ActionEditor(a, cfg, onDismiss = { dialog = null }) { na ->
                    updActions { l -> if (d.index in l.indices) l[d.index] = na }
                }
            }
            null -> Unit
        }
    }
}

@Composable
private fun KeyCaptureOverlay(onDismiss: () -> Unit, onKey: (Int) -> Unit) {
    val serviceOn = DenAccessibilityService.instance != null
    DisposableEffect(Unit) {
        KeyCapture.start { code -> onKey(code) }
        onDispose { KeyCapture.stop() }
    }
    Overlay("Нажмите кнопку на пульте", onDismiss) {
        item {
            PText(
                if (serviceOn) "Любая кнопка, включая «Меню» и голосовую. Отмена — подождите или выберите ниже."
                else "Служба PixelDen выключена: перехват кнопок недоступен. Включите её в «Настройки → Права и службы».",
                16.sp, maxLines = 4,
            )
        }
        if (!serviceOn) item { SettingRow("Закрыть", focusRequester = rememberAutoFocus(), onClick = onDismiss) }
    }
}

@Composable
private fun ActionEditor(a: Action, cfg: LauncherConfig, onDismiss: () -> Unit, onChange: (Action) -> Unit) {
    val app = PixelDenApp.instance
    var textEdit by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    textEdit?.let { (initial, done) ->
        TextEditOverlay("Текст", initial, onDismiss = { textEdit = null }) { v -> done(v); textEdit = null }
        return
    }

    when (a) {
        is Action.LaunchApp -> {
            val apps by app.apps.apps.collectAsState()
            Overlay("Какое приложение", onDismiss) {
                itemsIndexed(apps.filter { it.pkg !in cfg.hidden }, key = { _, e -> e.pkg }) { i, e ->
                    SettingRow(cfg.renamed[e.pkg] ?: e.label, if (e.pkg == a.pkg) "✓" else null,
                        focusRequester = if (i == 0) rememberAutoFocus() else null) {
                        onChange(a.copy(pkg = e.pkg)); onDismiss()
                    }
                }
            }
        }
        is Action.OpenInput -> {
            val inputs by app.apps.inputs.collectAsState()
            Overlay("Какой вход", onDismiss) {
                if (inputs.isEmpty()) item { SettingRow("Входы не найдены", focusRequester = rememberAutoFocus(), onClick = onDismiss) }
                itemsIndexed(inputs, key = { _, e -> e.id }) { i, e ->
                    SettingRow(e.label, focusRequester = if (i == 0) rememberAutoFocus() else null) {
                        onChange(a.copy(inputId = e.id, label = e.label)); onDismiss()
                    }
                }
            }
        }
        is Action.Volume -> Overlay("Громкость", onDismiss) {
            item {
                SettingRow("Уровень", "${a.percent}%", focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(percent = a.percent.stepIn(-5, 0..100))) },
                    onRight = { onChange(a.copy(percent = a.percent.stepIn(5, 0..100))) },
                    onClick = onDismiss)
            }
        }
        is Action.Brightness -> Overlay("Яркость", onDismiss) {
            item {
                SettingRow("Уровень", "${a.percent}%", focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(percent = a.percent.stepIn(-5, 0..100))) },
                    onRight = { onChange(a.copy(percent = a.percent.stepIn(5, 0..100))) },
                    onClick = onDismiss)
            }
        }
        is Action.Mute -> Overlay("Звук", onDismiss) {
            item { SettingRow("Выключить звук", if (a.on) "✓" else null, focusRequester = rememberAutoFocus()) { onChange(Action.Mute(true)); onDismiss() } }
            item { SettingRow("Включить звук", if (!a.on) "✓" else null) { onChange(Action.Mute(false)); onDismiss() } }
        }
        is Action.Wait -> Overlay("Пауза", onDismiss) {
            item {
                SettingRow("Секунд", "${a.seconds}", focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(seconds = a.seconds.stepIn(-1, 1..600))) },
                    onRight = { onChange(a.copy(seconds = a.seconds.stepIn(1, 1..600))) },
                    onClick = onDismiss)
            }
        }
        is Action.OpenSettings -> Overlay("Какая страница", onDismiss) {
            itemsIndexed(SETTINGS_PAGES) { i, (label, action) ->
                SettingRow(label, if (action == a.intentAction) "✓" else null, focusRequester = if (i == 0) rememberAutoFocus() else null) {
                    onChange(a.copy(intentAction = action)); onDismiss()
                }
            }
            item { SettingRow("Своё действие (intent)…", a.intentAction.takeIf { s -> SETTINGS_PAGES.none { it.second == s } }) { textEdit = a.intentAction to { v -> onChange(a.copy(intentAction = v.trim())) } } }
        }
        is Action.ClickText -> Overlay("Нажать пункт по тексту", onDismiss) {
            item { SettingRow("Текст на экране", a.text.ifEmpty { "—" }, focusRequester = rememberAutoFocus()) { textEdit = a.text to { v -> onChange(a.copy(text = v)) } } }
            item {
                SettingRow("Ждать до", "${a.timeoutSec} с",
                    onLeft = { onChange(a.copy(timeoutSec = a.timeoutSec.stepIn(-1, 1..60))) },
                    onRight = { onChange(a.copy(timeoutSec = a.timeoutSec.stepIn(1, 1..60))) })
            }
            item { SettingRow("Готово", onClick = onDismiss) }
        }
        is Action.PutSetting -> Overlay("Системная настройка", onDismiss) {
            val spaces = listOf("global", "secure", "system")
            item {
                SettingRow("Раздел", a.namespace, focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(namespace = spaces.cycle(a.namespace, -1))) },
                    onRight = { onChange(a.copy(namespace = spaces.cycle(a.namespace, 1))) })
            }
            item { SettingRow("Ключ", a.key.ifEmpty { "—" }) { textEdit = a.key to { v -> onChange(a.copy(key = v.trim())) } } }
            item { SettingRow("Значение", a.value.ifEmpty { "—" }) { textEdit = a.value to { v -> onChange(a.copy(value = v.trim())) } } }
            item { SettingRow("Готово", onClick = onDismiss) }
        }
        is Action.Toast -> Overlay("Сообщение", onDismiss) {
            item { SettingRow("Текст", a.text.ifEmpty { "—" }, focusRequester = rememberAutoFocus()) { textEdit = a.text to { v -> onChange(a.copy(text = v)) } } }
            item { SettingRow("Готово", onClick = onDismiss) }
        }
        Action.Sleep, Action.GoHome, Action.Back -> onDismiss()
    }
}

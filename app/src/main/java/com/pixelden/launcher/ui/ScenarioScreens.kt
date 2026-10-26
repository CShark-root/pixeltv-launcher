package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
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

private val DAYS = listOf(tr("Пн"), tr("Вт"), tr("Ср"), tr("Чт"), tr("Пт"), tr("Сб"), tr("Вс"))

fun keyName(code: Int): String = when (code) {
    0 -> tr("не выбрана")
    in com.pixelden.launcher.scenario.SCAN_BASE..Int.MAX_VALUE -> tr("Кнопка без кода №{0}", (code - com.pixelden.launcher.scenario.SCAN_BASE))
    KeyEvent.KEYCODE_MENU -> tr("Меню")
    KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> tr("Голос / Поиск")
    KeyEvent.KEYCODE_HOME -> tr("Домой")
    KeyEvent.KEYCODE_BACK -> tr("Назад")
    KeyEvent.KEYCODE_VOLUME_UP -> tr("Громкость +")
    KeyEvent.KEYCODE_VOLUME_DOWN -> tr("Громкость -")
    KeyEvent.KEYCODE_VOLUME_MUTE -> tr("Без звука")
    KeyEvent.KEYCODE_TV_INPUT -> tr("Источник")
    KeyEvent.KEYCODE_SETTINGS -> tr("Настройки")
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}

private fun pressName(p: PressType) = when (p) {
    PressType.SHORT -> tr("короткое")
    PressType.LONG -> tr("удержание")
    PressType.DOUBLE -> tr("двойное")
}

fun triggerText(t: Trigger): String = when (t) {
    is Trigger.Boot -> tr("после загрузки, через {0} с", (t.delaySec))
    is Trigger.Wake -> tr("при выходе из ожидания, через {0} с", (t.delaySec))
    is Trigger.Key -> tr("кнопка «{0}», {1}", (keyName(t.keyCode)), (pressName(t.press)))
    is Trigger.Time -> "%02d:%02d, %s".format(
        t.hour, t.minute,
        if (t.days.size == 7) tr("каждый день") else t.days.sorted().joinToString(" ") { DAYS[it - 1] },
    )
    Trigger.Manual -> tr("вручную")
}

fun actionText(a: Action, cfg: LauncherConfig): String {
    val apps = PixelDenApp.instance.apps.apps.value
    return when (a) {
        is Action.LaunchApp -> tr("Открыть: ") + (cfg.renamed[a.pkg] ?: apps.firstOrNull { it.pkg == a.pkg }?.label ?: a.pkg.ifEmpty { "?" })
        is Action.OpenInput -> tr("Вход: {0}", (a.label.ifEmpty { "?" }))
        is Action.Volume -> tr("Громкость {0}%", (a.percent))
        is Action.Mute -> if (a.on) tr("Выключить звук") else tr("Включить звук")
        is Action.Brightness -> tr("Яркость {0}%", (a.percent))
        Action.Sleep -> tr("Перевести ТВ в сон")
        Action.GoHome -> tr("Домой")
        Action.Back -> tr("Назад")
        is Action.Wait -> tr("Подождать {0} с", (a.seconds))
        is Action.OpenSettings -> tr("Открыть настройки: ") + (SETTINGS_PAGES.firstOrNull { it.second == a.intentAction }?.first ?: a.intentAction)
        is Action.ClickText -> tr("Нажать «{0}»", (a.text))
        is Action.PutSetting -> tr("Записать {0}/{1} = {2}", (a.namespace), (a.key), (a.value))
        is Action.Toast -> tr("Показать «{0}»", (a.text))
        is Action.SleepTimer -> if (a.minutes > 0) tr("Таймер сна {0} мин", (a.minutes)) else tr("Отменить таймер сна")
    }
}

private val SETTINGS_PAGES = listOf(
    tr("Звук") to "android.settings.SOUND_SETTINGS",
    tr("Все настройки") to "android.settings.SETTINGS",
    "Wi-Fi" to "android.settings.WIFI_SETTINGS",
    tr("Экран") to "android.settings.DISPLAY_SETTINGS",
    tr("Приложения") to "android.settings.APPLICATION_SETTINGS",
    tr("Дата и время") to "android.settings.DATE_SETTINGS",
    tr("Для разработчиков") to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
    tr("Спецвозможности") to "android.settings.ACCESSIBILITY_SETTINGS",
)

private val NEW_ACTIONS: List<Pair<String, () -> Action>> = listOf(
    tr("Открыть приложение") to { Action.LaunchApp() },
    tr("Переключить вход (HDMI/ТВ)") to { Action.OpenInput() },
    tr("Громкость") to { Action.Volume() },
    tr("Звук вкл/выкл") to { Action.Mute() },
    tr("Яркость") to { Action.Brightness() },
    tr("Сон") to { Action.Sleep },
    tr("Домой") to { Action.GoHome },
    tr("Назад") to { Action.Back },
    tr("Подождать") to { Action.Wait() },
    tr("Открыть страницу настроек") to { Action.OpenSettings() },
    tr("Нажать пункт по тексту") to { Action.ClickText() },
    tr("Записать системную настройку") to { Action.PutSetting() },
    tr("Показать сообщение") to { Action.Toast(tr("Привет!")) },
    tr("Таймер сна") to { Action.SleepTimer() },
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
            item { PText(tr("Сценарии"), 40.sp) }
            item { PText(tr("OK — изменить · < > — вкл/выкл · удержание OK — запустить, копия, удалить"), 14.sp, t.colors.dim, Modifier.padding(bottom = 12.dp)) }
            if (DenAccessibilityService.instance == null) {
                item {
                    PText(tr("! Служба PixelTv выключена: кнопки пульта и «нажать по тексту» не будут работать."), 15.sp, t.colors.accent, Modifier.padding(bottom = 10.dp), maxLines = 2)
                }
            }
            item {
                SettingRow(tr("+ Новый сценарий"), focusRequester = first) {
                    val s = Templates.blank()
                    app.store.update { it.copy(scenarios = it.scenarios + s) }
                    onEdit(s.id)
                }
            }
            items(cfg.scenarios, key = { it.id }) { s ->
                fun toggle() = updateScenario(s.id) { it.copy(enabled = !it.enabled) }
                SettingRow(
                    s.name,
                    if (s.enabled) tr("ВКЛ") else tr("ВЫКЛ"),
                    hint = triggerText(s.trigger) + tr(" · действий: {0}", (s.actions.size)),
                    onLeft = ::toggle, onRight = ::toggle,
                    onLongClick = { menuFor = s },
                    onClick = { onEdit(s.id) },
                )
            }
        }

        menuFor?.let { s ->
            Overlay(s.name, onDismiss = { menuFor = null }) {
                item { SettingRow(tr("> Запустить сейчас"), focusRequester = rememberAutoFocus()) { app.engine.run(s); menuFor = null } }
                item {
                    SettingRow(tr("Сделать копию")) {
                        app.store.update { it.copy(scenarios = it.scenarios + s.copy(id = Templates.newId(), name = s.name + tr(" (копия)"), enabled = false)) }
                        menuFor = null
                    }
                }
                item {
                    SettingRow(tr("Удалить")) {
                        app.store.update { it.copy(scenarios = it.scenarios.filter { x -> x.id != s.id }) }
                        reschedule()
                        menuFor = null
                    }
                }
                item { SettingRow(tr("Закрыть")) { menuFor = null } }
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

    val triggerTypes = listOf(tr("Вручную"), tr("После загрузки"), tr("Кнопка пульта"), tr("По времени"), tr("Выход из ожидания"))
    val currentType = when (s.trigger) {
        Trigger.Manual -> 0; is Trigger.Boot -> 1; is Trigger.Key -> 2; is Trigger.Time -> 3; is Trigger.Wake -> 4
    }
    fun switchType(step: Int) {
        val next = ((currentType + step) % 5 + 5) % 5
        updTrigger(when (next) { 1 -> Trigger.Boot(); 2 -> Trigger.Key(); 3 -> Trigger.Time(); 4 -> Trigger.Wake(); else -> Trigger.Manual })
    }

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 980.dp).fillMaxSize(), contentPadding = PaddingValues(40.dp, 32.dp)) {
            item { PText(s.name, 36.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            item { SettingRow(tr("Название"), s.name, focusRequester = first) { dialog = EditorDialog.Name } }
            item { ToggleRow(tr("Включён"), s.enabled) { v -> upd { it.copy(enabled = v) } } }

            item { SectionTitle(tr("Когда")) }
            item { SettingRow(tr("Триггер"), triggerTypes[currentType], onLeft = { switchType(-1) }, onRight = { switchType(1) }) }
            when (val tr = s.trigger) {
                is Trigger.Wake -> item {
                    SettingRow(tr("Задержка после включения"), tr("{0} с", (tr.delaySec)),
                        hint = tr("Когда ТВ включили с пульта из режима ожидания. После полной загрузки срабатывает «После загрузки»"),
                        onLeft = { updTrigger(tr.copy(delaySec = tr.delaySec.stepIn(-1, 0..120))) },
                        onRight = { updTrigger(tr.copy(delaySec = tr.delaySec.stepIn(1, 0..120))) })
                }
                is Trigger.Boot -> item {
                    SettingRow(tr("Задержка после загрузки"), tr("{0} с", (tr.delaySec)),
                        hint = tr("Службе спецвозможностей нужно ~10 с, чтобы подняться"),
                        onLeft = { updTrigger(tr.copy(delaySec = tr.delaySec.stepIn(-5, 0..300))) },
                        onRight = { updTrigger(tr.copy(delaySec = tr.delaySec.stepIn(5, 0..300))) })
                }
                is Trigger.Key -> {
                    item {
                        SettingRow(tr("Кнопка"), keyName(tr.keyCode), hint = tr("OK — нажать нужную кнопку на пульте")) { dialog = EditorDialog.CaptureKey }
                    }
                    item {
                        val types = PressType.entries
                        SettingRow(tr("Тип нажатия"), pressName(tr.press),
                            hint = when (tr.press) {
                                PressType.SHORT -> tr("Кнопка полностью перехватывается — её обычное действие пропадёт")
                                PressType.LONG -> tr("Короткое нажатие работает как обычно")
                                PressType.DOUBLE -> tr("Первое нажатие работает как обычно, второе запускает сценарий")
                            },
                            onLeft = { updTrigger(tr.copy(press = types.cycle(tr.press, -1))) },
                            onRight = { updTrigger(tr.copy(press = types.cycle(tr.press, 1))) })
                    }
                }
                is Trigger.Time -> {
                    item {
                        SettingRow(tr("Часы"), "%02d".format(tr.hour),
                            onLeft = { updTrigger(tr.copy(hour = (tr.hour + 23) % 24)) },
                            onRight = { updTrigger(tr.copy(hour = (tr.hour + 1) % 24)) })
                    }
                    item {
                        SettingRow(tr("Минуты"), "%02d".format(tr.minute),
                            onLeft = { updTrigger(tr.copy(minute = (tr.minute + 55) % 60)) },
                            onRight = { updTrigger(tr.copy(minute = (tr.minute + 5) % 60)) },
                            onLongClick = { updTrigger(tr.copy(minute = (tr.minute + 1) % 60)) })
                    }
                    item {
                        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DAYS.forEachIndexed { i, d ->
                                val day = i + 1
                                val on = day in tr.days
                                PButton(if (on) "[x] $d" else "[ ] $d", size = 15.sp) {
                                    updTrigger(tr.copy(days = if (on) tr.days - day else (tr.days + day).sorted()))
                                }
                            }
                        }
                    }
                }
                Trigger.Manual -> item {
                    PText(tr("Запуск кнопкой на главном экране или из списка сценариев"), 14.sp, t.colors.dim, Modifier.padding(4.dp))
                }
            }

            item { SectionTitle(tr("Что сделать")) }
            itemsIndexed(s.actions) { i, a ->
                SettingRow(
                    "${i + 1}. ${actionText(a, cfg)}",
                    hint = tr("OK — изменить · < > — порядок · удержание OK — меню"),
                    onLeft = { updActions { l -> if (i > 0) l.add(i - 1, l.removeAt(i)) } },
                    onRight = { updActions { l -> if (i < l.lastIndex) l.add(i + 1, l.removeAt(i)) } },
                    onLongClick = { dialog = EditorDialog.ActionMenu(i) },
                    onClick = { dialog = EditorDialog.EditAction(i) },
                )
            }
            item { SettingRow(tr("+ Добавить действие")) { dialog = EditorDialog.AddAction } }

            item { SectionTitle("") }
            item { SettingRow(tr("> Запустить сейчас")) { app.engine.run(s) } }
            item {
                SettingRow(tr("Удалить сценарий")) {
                    app.store.update { it.copy(scenarios = it.scenarios.filter { x -> x.id != id }) }
                    reschedule()
                    onClose()
                }
            }
        }

        when (val d = dialog) {
            EditorDialog.Name -> TextEditOverlay(tr("Название"), s.name, onDismiss = { dialog = null }) { name ->
                if (name.isNotBlank()) upd { it.copy(name = name.trim()) }
                dialog = null
            }
            EditorDialog.CaptureKey -> KeyCaptureOverlay(onDismiss = { dialog = null }) { code ->
                (s.trigger as? Trigger.Key)?.let { updTrigger(it.copy(keyCode = code)) }
                dialog = null
            }
            EditorDialog.AddAction -> Overlay(tr("Новое действие"), onDismiss = { dialog = null }) {
                itemsIndexed(NEW_ACTIONS) { i, (label, make) ->
                    SettingRow(label, focusRequester = if (i == 0) rememberAutoFocus() else null) {
                        val a = make()
                        updActions { it.add(a) }
                        dialog = if (a is Action.Sleep || a is Action.GoHome || a is Action.Back) null
                        else EditorDialog.EditAction(s.actions.size)
                    }
                }
            }
            is EditorDialog.ActionMenu -> Overlay(tr("Действие {0}", (d.index + 1)), onDismiss = { dialog = null }) {
                item { SettingRow(tr("Изменить"), focusRequester = rememberAutoFocus()) { dialog = EditorDialog.EditAction(d.index) } }
                item {
                    SettingRow(tr("Дублировать")) {
                        updActions { l -> l.getOrNull(d.index)?.let { l.add(d.index + 1, it) } }
                        dialog = null
                    }
                }
                item { SettingRow(tr("Удалить")) { updActions { l -> if (d.index in l.indices) l.removeAt(d.index) }; dialog = null } }
                item { SettingRow(tr("Закрыть")) { dialog = null } }
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
    Overlay(tr("Нажмите кнопку на пульте"), onDismiss) {
        item {
            PText(
                if (serviceOn) tr("Любая кнопка, включая «Меню» и голосовую. Отмена — подождите или выберите ниже.")
                else tr("Служба PixelTv выключена: перехват кнопок недоступен. Включите её в «Настройки → Права и службы»."),
                16.sp, maxLines = 4,
            )
        }
        if (!serviceOn) item { SettingRow(tr("Закрыть"), focusRequester = rememberAutoFocus(), onClick = onDismiss) }
    }
}

@Composable
private fun ActionEditor(a: Action, cfg: LauncherConfig, onDismiss: () -> Unit, onChange: (Action) -> Unit) {
    val app = PixelDenApp.instance
    var textEdit by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }

    textEdit?.let { (initial, done) ->
        TextEditOverlay(tr("Текст"), initial, onDismiss = { textEdit = null }) { v -> done(v); textEdit = null }
        return
    }

    when (a) {
        is Action.LaunchApp -> {
            val apps by app.apps.apps.collectAsState()
            Overlay(tr("Какое приложение"), onDismiss) {
                itemsIndexed(apps.filter { it.pkg !in cfg.hidden }, key = { _, e -> e.pkg }) { i, e ->
                    SettingRow(cfg.renamed[e.pkg] ?: e.label, if (e.pkg == a.pkg) "•" else null,
                        focusRequester = if (i == 0) rememberAutoFocus() else null) {
                        onChange(a.copy(pkg = e.pkg)); onDismiss()
                    }
                }
            }
        }
        is Action.OpenInput -> {
            val inputs by app.apps.inputs.collectAsState()
            Overlay(tr("Какой вход"), onDismiss) {
                if (inputs.isEmpty()) item { SettingRow(tr("Входы не найдены"), focusRequester = rememberAutoFocus(), onClick = onDismiss) }
                itemsIndexed(inputs, key = { _, e -> e.id }) { i, e ->
                    SettingRow(e.label, focusRequester = if (i == 0) rememberAutoFocus() else null) {
                        onChange(a.copy(inputId = e.id, label = e.label)); onDismiss()
                    }
                }
            }
        }
        is Action.Volume -> Overlay(tr("Громкость"), onDismiss) {
            item {
                SettingRow(tr("Уровень"), "${a.percent}%", focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(percent = a.percent.stepIn(-5, 0..100))) },
                    onRight = { onChange(a.copy(percent = a.percent.stepIn(5, 0..100))) },
                    onClick = onDismiss)
            }
        }
        is Action.Brightness -> Overlay(tr("Яркость"), onDismiss) {
            item {
                SettingRow(tr("Уровень"), "${a.percent}%", focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(percent = a.percent.stepIn(-5, 0..100))) },
                    onRight = { onChange(a.copy(percent = a.percent.stepIn(5, 0..100))) },
                    onClick = onDismiss)
            }
        }
        is Action.Mute -> Overlay(tr("Звук"), onDismiss) {
            item { SettingRow(tr("Выключить звук"), if (a.on) "•" else null, focusRequester = rememberAutoFocus()) { onChange(Action.Mute(true)); onDismiss() } }
            item { SettingRow(tr("Включить звук"), if (!a.on) "•" else null) { onChange(Action.Mute(false)); onDismiss() } }
        }
        is Action.Wait -> Overlay(tr("Пауза"), onDismiss) {
            item {
                SettingRow(tr("Секунд"), "${a.seconds}", focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(seconds = a.seconds.stepIn(-1, 1..600))) },
                    onRight = { onChange(a.copy(seconds = a.seconds.stepIn(1, 1..600))) },
                    onClick = onDismiss)
            }
        }
        is Action.OpenSettings -> Overlay(tr("Какая страница"), onDismiss) {
            itemsIndexed(SETTINGS_PAGES) { i, (label, action) ->
                SettingRow(label, if (action == a.intentAction) "•" else null, focusRequester = if (i == 0) rememberAutoFocus() else null) {
                    onChange(a.copy(intentAction = action)); onDismiss()
                }
            }
            item { SettingRow(tr("Своё действие (intent)…"), a.intentAction.takeIf { s -> SETTINGS_PAGES.none { it.second == s } }) { textEdit = a.intentAction to { v -> onChange(a.copy(intentAction = v.trim())) } } }
        }
        is Action.ClickText -> Overlay(tr("Нажать пункт по тексту"), onDismiss) {
            item { SettingRow(tr("Текст на экране"), a.text.ifEmpty { "—" }, focusRequester = rememberAutoFocus()) { textEdit = a.text to { v -> onChange(a.copy(text = v)) } } }
            item {
                SettingRow(tr("Ждать до"), tr("{0} с", (a.timeoutSec)),
                    onLeft = { onChange(a.copy(timeoutSec = a.timeoutSec.stepIn(-1, 1..60))) },
                    onRight = { onChange(a.copy(timeoutSec = a.timeoutSec.stepIn(1, 1..60))) })
            }
            item { SettingRow(tr("Готово"), onClick = onDismiss) }
        }
        is Action.PutSetting -> Overlay(tr("Системная настройка"), onDismiss) {
            val spaces = listOf("global", "secure", "system")
            item {
                SettingRow(tr("Раздел"), a.namespace, focusRequester = rememberAutoFocus(),
                    onLeft = { onChange(a.copy(namespace = spaces.cycle(a.namespace, -1))) },
                    onRight = { onChange(a.copy(namespace = spaces.cycle(a.namespace, 1))) })
            }
            item { SettingRow(tr("Ключ"), a.key.ifEmpty { "—" }) { textEdit = a.key to { v -> onChange(a.copy(key = v.trim())) } } }
            item { SettingRow(tr("Значение"), a.value.ifEmpty { "—" }) { textEdit = a.value to { v -> onChange(a.copy(value = v.trim())) } } }
            item { SettingRow(tr("Готово"), onClick = onDismiss) }
        }
        is Action.Toast -> Overlay(tr("Сообщение"), onDismiss) {
            item { SettingRow(tr("Текст"), a.text.ifEmpty { "—" }, focusRequester = rememberAutoFocus()) { textEdit = a.text to { v -> onChange(a.copy(text = v)) } } }
            item { SettingRow(tr("Готово"), onClick = onDismiss) }
        }
        is Action.SleepTimer -> Overlay(tr("Таймер сна"), onDismiss) {
            item {
                SettingRow(tr("Через"), if (a.minutes > 0) tr("{0} мин", (a.minutes)) else tr("отменить"), focusRequester = rememberAutoFocus(),
                    hint = tr("0 — отменить уже запущенный таймер"),
                    onLeft = { onChange(a.copy(minutes = a.minutes.stepIn(-5, 0..600))) },
                    onRight = { onChange(a.copy(minutes = a.minutes.stepIn(5, 0..600))) },
                    onClick = onDismiss)
            }
        }
        Action.Sleep, Action.GoHome, Action.Back -> onDismiss()
    }
}

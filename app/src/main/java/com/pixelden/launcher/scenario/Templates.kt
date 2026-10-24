package com.pixelden.launcher.scenario

import com.pixelden.launcher.i18n.tr
import android.view.KeyEvent
import com.pixelden.launcher.data.Action
import com.pixelden.launcher.data.PressType
import com.pixelden.launcher.data.Scenario
import com.pixelden.launcher.data.Trigger
import java.util.UUID

object Templates {

    fun newId() = UUID.randomUUID().toString().take(8)

    /** Шаблоны, которые кладутся при первом запуске. Все выключены: S/PDIF нужен только на ТВ с оптическим выходом. */
    fun initial(): List<Scenario> = listOf(
        Scenario(
            id = newId(),
            name = tr("S/PDIF после загрузки"),
            enabled = false,
            trigger = Trigger.Boot(delaySec = 15),
            actions = listOf(
                Action.OpenSettings("android.settings.SOUND_SETTINGS"),
                Action.ClickText(tr("Динамики"), 10),
                Action.ClickText("S/PDIF", 6),
                Action.Wait(1),
                Action.GoHome,
            ),
        ),
        Scenario(
            id = newId(),
            name = tr("Удержание Меню → SmartTube"),
            enabled = false,
            trigger = Trigger.Key(KeyEvent.KEYCODE_MENU, PressType.LONG),
            actions = listOf(Action.LaunchApp("org.smarttube.stable")),
        ),
        Scenario(
            id = newId(),
            name = tr("Вечер: громкость 20%"),
            enabled = false,
            trigger = Trigger.Time(22, 0),
            actions = listOf(Action.Volume(20)),
        ),
        Scenario(
            id = newId(),
            name = tr("Ночь: спать"),
            enabled = false,
            trigger = Trigger.Time(1, 30),
            actions = listOf(Action.Sleep),
        ),
    )

    fun blank() = Scenario(id = newId(), name = tr("Новый сценарий"), trigger = Trigger.Manual)
}

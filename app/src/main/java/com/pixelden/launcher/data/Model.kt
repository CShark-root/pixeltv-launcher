package com.pixelden.launcher.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Всё состояние лаунчера. Хранится одним JSON-файлом, он же — резервная копия. */
@Serializable
data class LauncherConfig(
    // Маскот
    val mascot: String = "pinch",
    val mascotEnabled: Boolean = true,
    val mascotSleepMinutes: Int = 3,
    val mascotSpeaks: Boolean = true,
    val mascotScale: Int = 6,

    // Внешний вид
    val font: String = "pixelify",
    val accent: Long = 0xFFD97757,
    val background: Long = 0xFF14121A,
    val wallpaperPath: String? = null,
    val wallpaperDim: Int = 40,
    val animations: Boolean = true,
    val focusScale: Int = 6,
    val pixelBorder: Int = 3,

    // Сетка
    val columns: Int = 6,
    val cardSize: Int = 100,
    val showLabels: Boolean = true,
    val preferBanners: Boolean = true,

    // Шапка
    val clock24h: Boolean = true,
    val showSeconds: Boolean = false,
    val showDate: Boolean = true,
    val greeting: Boolean = true,

    // Ряды
    val showFavorites: Boolean = true,
    val showInputs: Boolean = true,
    val showCategories: Boolean = true,
    val showAllApps: Boolean = true,

    // Приложения
    val hidden: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    val categories: List<Category> = emptyList(),
    val renamed: Map<String, String> = emptyMap(),

    // Сценарии
    val scenarios: List<Scenario> = emptyList(),
    val templatesOffered: Boolean = false,
)

@Serializable
data class Category(val id: String, val name: String, val apps: List<String> = emptyList())

@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val actions: List<Action> = emptyList(),
)

@Serializable
enum class PressType { SHORT, LONG, DOUBLE }

@Serializable
sealed class Trigger {
    /** После загрузки ТВ, с задержкой. */
    @Serializable @SerialName("boot")
    data class Boot(val delaySec: Int = 10) : Trigger()

    /** Кнопка пульта. Нужна служба специальных возможностей. */
    @Serializable @SerialName("key")
    data class Key(val keyCode: Int = 0, val press: PressType = PressType.LONG) : Trigger()

    /** Время и дни недели (1 = пн … 7 = вс). */
    @Serializable @SerialName("time")
    data class Time(val hour: Int = 21, val minute: Int = 0, val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7)) : Trigger()

    /** Только вручную: из списка сценариев или плиткой на главном экране. */
    @Serializable @SerialName("manual")
    data object Manual : Trigger()
}

@Serializable
sealed class Action {
    @Serializable @SerialName("launch")
    data class LaunchApp(val pkg: String = "") : Action()

    @Serializable @SerialName("input")
    data class OpenInput(val inputId: String = "", val label: String = "") : Action()

    @Serializable @SerialName("volume")
    data class Volume(val percent: Int = 30) : Action()

    @Serializable @SerialName("mute")
    data class Mute(val on: Boolean = true) : Action()

    @Serializable @SerialName("brightness")
    data class Brightness(val percent: Int = 50) : Action()

    @Serializable @SerialName("sleep")
    data object Sleep : Action()

    @Serializable @SerialName("home")
    data object GoHome : Action()

    @Serializable @SerialName("back")
    data object Back : Action()

    @Serializable @SerialName("wait")
    data class Wait(val seconds: Int = 2) : Action()

    @Serializable @SerialName("open_settings")
    data class OpenSettings(val intentAction: String = "android.settings.SOUND_SETTINGS") : Action()

    @Serializable @SerialName("click_text")
    data class ClickText(val text: String = "", val timeoutSec: Int = 8) : Action()

    @Serializable @SerialName("setting")
    data class PutSetting(val namespace: String = "global", val key: String = "", val value: String = "") : Action()

    @Serializable @SerialName("toast")
    data class Toast(val text: String = "") : Action()
}

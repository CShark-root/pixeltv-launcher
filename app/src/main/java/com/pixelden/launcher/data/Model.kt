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
    val mascotScale: Int = 4,

    // Внешний вид
    val font: String = "tiny5",
    val accent: Long = 0xFFD97757,
    val background: Long = 0xFF14121A,
    val wallpaperPath: String? = null,
    val wallpaperDim: Int = 40,
    val animations: Boolean = true,
    val focusScale: Int = 6,
    val pixelBorder: Int = 3,
    /** Устарело: один эффект. Переносится в [focusEffects] миграцией v4. */
    val focusEffect: String = "scale",
    /** Эффекты карточки в фокусе, можно сочетать: scale, dim, glow, glitch, pixel, crt. */
    val focusEffects: List<String> = listOf("scale"),
    /** Переход между экранами лаунчера: pixels, fade, none. */
    val screenTransition: String = "pixels",

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
    /** Кнопки запуска ручных сценариев под шапкой. */
    val showManualScenarios: Boolean = false,

    // Приложения
    val hidden: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    val categories: List<Category> = emptyList(),
    val renamed: Map<String, String> = emptyMap(),

    // Сценарии
    val scenarios: List<Scenario> = emptyList(),
    val templatesOffered: Boolean = false,
    /** Версия разовых правок по умолчанию (скрытие мусора и т. п.), см. [Defaults]. */
    val defaultsVersion: Int = 0,
)

object Defaults {
    const val VERSION = 4

    /** Пустышки обязательной предустановки и системные пакеты, которым не место на главном экране. */
    val JUNK = listOf(
        "ru.ntv.client.tv", "ru.ok.androidtv", "ru.tv1.android.tv", "com.vgtrk.androidtv",
        "com.ctcmediagroup.videomore", "ru.ivi.client", "ru.rt.video.app.tv", "tv.okko.androidtv",
        "ru.start.androidmobile", "com.xiaomi.tvhome.fakepremier",
        "com.mstar.android.tv.disclaimercustomization", "com.mediatek.tv.service",
        "com.mediatek.wwtv.webview", "com.mediatek.wwtv.tvcenter", "com.android.deskclock",
        "com.xiaomi.mitv.tvmanager",
    )

    fun apply(cfg: LauncherConfig): LauncherConfig {
        var c = cfg
        if (c.defaultsVersion < 1) c = c.copy(hidden = (c.hidden + JUNK).distinct())
        // v2: Pixelify Sans не понравился — переводим на Tiny5.
        if (c.defaultsVersion < 2 && c.font == "pixelify") c = c.copy(font = "tiny5")
        // v3: новые спрайты 32x32 — масштаб 4 даёт ~128 dp.
        if (c.defaultsVersion < 3) c = c.copy(mascotScale = 4)
        // v4: эффекты фокуса стали набором — переносим выбранный раньше эффект вместе с увеличением.
        if (c.defaultsVersion < 4) c = c.copy(focusEffects = (listOf("scale") + c.focusEffect).distinct())
        return c.copy(defaultsVersion = VERSION)
    }
}

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

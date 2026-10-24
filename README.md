# PixelTv

A pixel-art home screen for Android TV with a living mascot, scenarios, phone control and a built-in TV cleaner. Made for and tested on **Xiaomi Mi TV** (P1, 4S), works on most Android TV 9+ devices.

[Русская версия ниже](#pixeltv-по-русски)

## Features

- **Home screen**: favorites, categories, inputs (HDMI/TV), all apps; hide and rename apps; pixel fonts, accents, focus effects (zoom, glow, glitch, CRT, pixel reveal).
- **Mascots**: six pixel pets that blink, walk, wave, sleep and talk: greetings, weather, holidays, fun facts, news headlines (choose an RSS source), plus a couple of easter eggs.
- **Live wallpapers**: pixel landscape following the time of day, night city, fireplace; real rain/snow/fog on top of any wallpaper; separate wallpapers for morning/day/evening/night.
- **Widgets**: next holiday, exchange rates, sleep timer, free memory; weather (Open-Meteo or Yandex).
- **Scenarios**: triggers on boot, remote buttons (short/long/double, including buttons without a keycode), schedule or manual; actions: launch app, switch input, volume, brightness, sleep, sleep timer, tap a menu item by its text, write system settings and more.
- **Set up from your phone**: a page on your home network (PIN-protected) to edit scenarios, reorder apps, upload wallpapers, type text on the TV and install APKs.
- **TV cleanup**: disable ads, analytics, PatchWall and preinstalled junk directly from the TV, and restore everything with one button. Network, sound, HDMI, remote and Google Play are never touched.
- **TV health**: memory, storage, temperature, recently used apps, "free memory", optional memory guard.
- **Display calibration**: game-style patterns for black level, contrast, gamma, color temperature, saturation and sharpness, played as video so the TV picture processor applies.
- **Auto-update** from GitHub Releases; the TV asks you to confirm the install.
- Russian and English UI.

## Install

1. On the TV: Settings → Device Preferences → About → click *Build* 7 times, then enable **USB debugging** (and **Network debugging**, if your TV has a separate switch) in Developer options.
2. Download `PixelTv-x.y.z.apk` from [Releases](https://github.com/CShark-root/pixeltv-launcher/releases) and install it (e.g. `adb install PixelTv-x.y.z.apk`, or with a file manager / Send Files to TV).
3. Open PixelTv → **Settings → Permissions & services → Grant all permissions automatically**. The TV asks “Allow debugging?” once; tick *Always* and press OK. This grants the permissions, enables the buttons service and makes PixelTv the home screen. No computer needed.
4. Optional: **Settings → TV cleanup → Disable recommended**.

Manual alternative (from a computer):

```sh
adb shell pm grant com.pixelden.launcher android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.pixelden.launcher SYSTEM_ALERT_WINDOW allow
adb shell appops set com.pixelden.launcher GET_USAGE_STATS allow
adb shell settings put secure enabled_accessibility_services com.pixelden.launcher/com.pixelden.launcher.scenario.DenAccessibilityService
adb shell cmd package set-home-activity com.pixelden.launcher/.HomeActivity
```

## Build

JDK 17 and the Android SDK (platform 34) are needed.

```sh
./gradlew assembleRelease -Ppixeltv.repo=CShark-root/pixeltv-launcher
```

Without a release key the APK is signed with the debug key. Release builds read `~/.pixeltv/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the path in `PIXELTV_KEYSTORE`.

Translations live in `app/src/main/assets/i18n/<lang>.json`: the Russian phrase is the key. `tools/i18n/WrapStrings.java` wraps new strings into `tr("…")` and lists all keys.

## Notes

- Picture settings on MediaTek/MStar TVs only affect the video layer, so the UI itself isn't changed by contrast/gamma; backlight changes everything.
- Some Xiaomi firmwares reinstall preloaded apps at boot; PixelTv hides them on the home screen and the cleanup disables them.
- Fonts: Tiny5, Handjet, Press Start 2P, Pixelify Sans, all under the SIL Open Font License (see `licenses/`).

## License

GPL-3.0. See [LICENSE](LICENSE).

---

## PixelTv по-русски

Пиксельный главный экран для Android TV с живым маскотом, сценариями, настройкой с телефона и встроенной чисткой ТВ. Сделан и проверен на **Xiaomi Mi TV** (P1, 4S), работает на большинстве Android TV 9+.

**Что умеет:** избранное, категории, входы HDMI; маскоты с репликами, праздниками, фактами и новостями; живые пиксельные обои и погода на обоях; виджеты (праздники, курсы ЦБ, таймер сна, память); сценарии по кнопкам пульта, времени и включению ТВ; настройка с телефона по PIN (сценарии, обои, ввод текста на ТВ, установка APK); чистка ТВ с возвратом одной кнопкой; «Здоровье ТВ» и сторож памяти; калибровка экрана; автообновление с GitHub.

**Установка:**
1. На ТВ включите «Для разработчиков» (7 нажатий на «Сборка»), там — «Отладка по USB» (и «Отладка по сети», если есть отдельный переключатель).
2. Скачайте APK из [Releases](https://github.com/CShark-root/pixeltv-launcher/releases) и установите (через `adb install` или Send Files to TV).
3. Откройте PixelTv → **Настройки → Права и службы → Выдать все права автоматически**. ТВ один раз спросит «Разрешить отладку?» — отметьте «Всегда» и нажмите OK.
4. По желанию: **Настройки → Чистка ТВ → Отключить рекомендуемое**.

Лицензия — GPL-3.0.

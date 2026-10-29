package com.pixelden.launcher.system

import com.pixelden.launcher.i18n.tr
import android.content.Context
import com.pixelden.launcher.scenario.DebugLog

/**
 * Выдать лаунчеру все права одной кнопкой — через adb самого ТВ, без компьютера.
 * Нужна включённая «Отладка по сети»; при первом разе ТВ спросит «Разрешить отладку?».
 */
object Setup {
    /**
     * Служба спецвозможностей (кнопки пульта, сценарии «нажать в меню») включена? Android иногда снимает её
     * при обновлении приложения — тогда включаем обратно сами: прямой записью настройки (право WRITE_SECURE_SETTINGS
     * выдаёт «Настроить всё»), а без права — через adb ТВ, без вопроса на экране. Блокирующий.
     */
    fun ensureAccessibility(context: Context): Boolean {
        val cr = context.contentResolver
        val ours = android.content.ComponentName(context, com.pixelden.launcher.scenario.DenAccessibilityService::class.java)
        val current = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.takeIf { it.isNotBlank() && it != "null" }?.split(':').orEmpty()
        val on = android.provider.Settings.Secure.getInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (on && current.any { android.content.ComponentName.unflattenFromString(it) == ours }) return true
        // Чужие службы не трогаем — дописываем свою. В команду adb идут только записи вида «пакет/класс».
        val value = (current.filter { Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(it) } + ours.flattenToString()).distinct().joinToString(":")
        val ok = runCatching {
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
            android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess || runCatching {
            AdbClient(context).shell("settings put secure enabled_accessibility_services '$value'; settings put secure accessibility_enabled 1", ask = false)
        }.isSuccess
        DebugLog.i(if (ok) "служба спецвозможностей была выключена — включил снова" else "служба спецвозможностей выключена, включить не вышло")
        return ok
    }

    fun grantAll(context: Context): String {
        val p = context.packageName
        val adb = AdbClient(context)
        val service = "$p/$p.scenario.DenAccessibilityService"
        // Службы спецвозможностей других приложений не выключаем — дописываем свою к списку.
        val current = adb.shell("settings get secure enabled_accessibility_services").trim()
            .takeIf { it.isNotBlank() && it != "null" }?.split(':').orEmpty()
            // В команду идут только похожие на «пакет/класс» записи и в кавычках — строка пришла из настроек.
            .filter { Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(it) }
        val services = (current + service).distinct().joinToString(":")
        adb.shell(
            listOf(
                "pm grant $p android.permission.WRITE_SECURE_SETTINGS",
                "pm grant $p android.permission.READ_EXTERNAL_STORAGE",
                "pm grant $p android.permission.WRITE_EXTERNAL_STORAGE",
                // Android 13+: картинки для обоев читаются по отдельному праву.
                "pm grant $p android.permission.READ_MEDIA_IMAGES",
                // Ряд «Продолжить просмотр»: чтение системного списка Watch Next.
                "pm grant $p android.permission.READ_TV_LISTINGS",
                // Android 13+: «ограниченные настройки» — без этого приложению из APK не дают включить спецвозможности.
                "appops set $p ACCESS_RESTRICTED_SETTINGS allow",
                // Android 12+: сценарии по времени ставят точные будильники.
                "appops set $p SCHEDULE_EXACT_ALARM allow",
                "appops set $p SYSTEM_ALERT_WINDOW allow",
                "appops set $p WRITE_SETTINGS allow",
                "appops set $p GET_USAGE_STATS allow",
                // Шторка уведомлений в лаунчере: на ТВ доступ к уведомлениям включается только так.
                "cmd notification allow_listener $p/$p.system.NoticeListener",
                "appops set $p REQUEST_INSTALL_PACKAGES allow",
                "settings put secure enabled_accessibility_services '$services'",
                "settings put secure accessibility_enabled 1",
                "cmd package set-home-activity $p/.HomeActivity",
                "cmd role add-role-holder android.app.role.HOME $p",
            ).joinToString("; "),
        )
        DebugLog.i("права выданы через adb ТВ")
        // Право на «Продолжить просмотр» только что выдано — сразу читаем список.
        com.pixelden.launcher.PixelDenApp.instance.let { if (it.store.current.showWatchNext) it.watchNext.start() }
        return tr("Готово: права выданы, PixelTv — главный экран")
    }
}

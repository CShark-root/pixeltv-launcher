package com.pixelden.launcher.system

import com.pixelden.launcher.i18n.tr
import android.content.Context
import com.pixelden.launcher.scenario.DebugLog

/**
 * Выдать лаунчеру все права одной кнопкой — через adb самого ТВ, без компьютера.
 * Нужна включённая «Отладка по сети»; при первом разе ТВ спросит «Разрешить отладку?».
 */
object Setup {
    fun grantAll(context: Context): String {
        val p = context.packageName
        val adb = AdbClient(context)
        val service = "$p/$p.scenario.DenAccessibilityService"
        // Службы спецвозможностей других приложений не выключаем — дописываем свою к списку.
        val current = adb.shell("settings get secure enabled_accessibility_services").trim()
            .takeIf { it.isNotBlank() && it != "null" }?.split(':').orEmpty()
        val services = (current + service).distinct().joinToString(":")
        adb.shell(
            listOf(
                "pm grant $p android.permission.WRITE_SECURE_SETTINGS",
                "pm grant $p android.permission.READ_EXTERNAL_STORAGE",
                "pm grant $p android.permission.WRITE_EXTERNAL_STORAGE",
                // Android 13+: картинки для обоев читаются по отдельному праву.
                "pm grant $p android.permission.READ_MEDIA_IMAGES",
                // Android 13+: «ограниченные настройки» — без этого приложению из APK не дают включить спецвозможности.
                "appops set $p ACCESS_RESTRICTED_SETTINGS allow",
                // Android 12+: сценарии по времени ставят точные будильники.
                "appops set $p SCHEDULE_EXACT_ALARM allow",
                "appops set $p SYSTEM_ALERT_WINDOW allow",
                "appops set $p WRITE_SETTINGS allow",
                "appops set $p GET_USAGE_STATS allow",
                "appops set $p REQUEST_INSTALL_PACKAGES allow",
                "settings put secure enabled_accessibility_services $services",
                "settings put secure accessibility_enabled 1",
                "cmd package set-home-activity $p/.HomeActivity",
                "cmd role add-role-holder android.app.role.HOME $p",
            ).joinToString("; "),
        )
        DebugLog.i("права выданы через adb ТВ")
        return tr("Готово: права выданы, PixelTv — главный экран")
    }
}

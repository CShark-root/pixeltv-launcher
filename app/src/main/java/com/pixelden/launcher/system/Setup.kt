package com.pixelden.launcher.system

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
                "appops set $p SYSTEM_ALERT_WINDOW allow",
                "appops set $p WRITE_SETTINGS allow",
                "appops set $p GET_USAGE_STATS allow",
                "appops set $p REQUEST_INSTALL_PACKAGES allow",
                "settings put secure enabled_accessibility_services $services",
                "settings put secure accessibility_enabled 1",
                "cmd package set-home-activity $p/.HomeActivity",
            ).joinToString("; "),
        )
        DebugLog.i("права выданы через adb ТВ")
        return "Готово: права выданы, PixelTv — главный экран"
    }
}

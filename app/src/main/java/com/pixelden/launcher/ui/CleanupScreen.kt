package com.pixelden.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.system.AdbClient
import com.pixelden.launcher.system.TvCleanup
import com.pixelden.launcher.system.mb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Чистка ТВ»: отключение рекламы и мусора прошивки прямо с телевизора и возврат одной кнопкой.
 * Команды идут через adb самого ТВ — при первом действии ТВ спросит разрешение.
 */
@Composable
fun CleanupPanel(cfg: LauncherConfig) {
    val context = LocalContext.current
    val t = LocalDen.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var states by remember { mutableStateOf<List<TvCleanup.State>>(emptyList()) }
    var apks by remember { mutableStateOf(0L to 0) }
    LaunchedEffect(refresh) {
        states = withContext(Dispatchers.IO) { TvCleanup.scan(context) }
        apks = withContext(Dispatchers.IO) { TvCleanup.apkFiles().let { l -> l.sumOf { it.length() } to l.size } }
    }

    /** Действие через adb в фоне: на время — «Подождите…», в конце — всплывашка с итогом. */
    fun act(label: String, block: () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching(block).getOrElse { e ->
                    if (e is java.net.ConnectException) "Отладка по сети на ТВ выключена: включите её в «Для разработчиков»"
                    else "Не вышло: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            busy = null
            refresh++
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        PText(
            busy?.let { "$it… Если ТВ спросит «Разрешить отладку?» — отметьте «Всегда» и нажмите OK." }
                ?: "Приложения не удаляются, а отключаются. Сеть, звук, HDMI, пульт и Google Play не трогаются.",
            14.sp, if (busy != null) t.colors.accent else t.colors.dim, Modifier.padding(4.dp, 0.dp, 0.dp, 10.dp), maxLines = 3,
        )
        val recommended = states.count { it.enabled && it.item.recommended }
        SettingRow("Отключить рекомендуемое", if (recommended > 0) "$recommended шт." else "готово",
            hint = "Реклама, аналитика, PatchWall, лишнее от Google и предустановки") {
            act("Отключаю") { "Отключено: ${TvCleanup.disableRecommended(context)}" }
        }
        SettingRow("Вернуть всё как было", "${cfg.cleanupDisabled.size} шт.",
            hint = "Включит всё, что отключила чистка") {
            act("Возвращаю") { "Возвращено: ${TvCleanup.restoreAll(context)}" }
        }
        SettingRow("Очистить кэш приложений", hint = "Временные файлы всех приложений, ничего важного не теряется") {
            act("Чищу кэш") { "Освобождено ${mb(TvCleanup.trimCaches(context))} МБ" }
        }
        SettingRow("Удалить установщики APK", if (apks.second > 0) "${apks.second} шт. · ${mb(apks.first)} МБ" else "нет",
            hint = "Файлы .apk из «Загрузок»: приложения уже установлены") {
            act("Удаляю") { "Удалено ${mb(TvCleanup.deleteApks())} МБ" }
        }
        SettingRow("Проверить доступ", hint = "Подключиться к системе ТВ — при первом разе ТВ спросит разрешение") {
            act("Подключаюсь") { if (AdbClient(context).shell("echo ok").trim() == "ok") "Доступ есть" else "Нет ответа" }
        }

        states.groupBy { it.item.group }.forEach { (group, list) ->
            SectionTitle(group)
            list.forEach { s ->
                ToggleRow(s.item.name + if (s.item.recommended) "" else " (по желанию)", s.enabled, s.item.pkg) { on ->
                    act(if (on) "Включаю" else "Отключаю") {
                        if (on) { TvCleanup.enable(context, s.item.pkg); "Включено: ${s.item.name}" }
                        else { TvCleanup.disable(context, s.item.pkg); "Отключено: ${s.item.name}" }
                    }
                }
            }
        }
    }
}

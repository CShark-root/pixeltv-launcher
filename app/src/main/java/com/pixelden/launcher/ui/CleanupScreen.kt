package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
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
                    if (e is java.net.ConnectException) tr("Отладка по сети на ТВ выключена: включите её в «Для разработчиков»")
                    else tr("Не вышло: {0}", (e.message ?: e.javaClass.simpleName))
                }
            }
            busy = null
            refresh++
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        PText(
            busy?.let { tr("{0}… Если ТВ спросит «Разрешить отладку?» — отметьте «Всегда» и нажмите OK.", it) }
                ?: tr("Приложения не удаляются, а отключаются. Сеть, звук, HDMI, пульт и Google Play не трогаются."),
            14.sp, if (busy != null) t.colors.accent else t.colors.dim, Modifier.padding(4.dp, 0.dp, 0.dp, 10.dp), maxLines = 3,
        )
        val recommended = states.count { it.enabled && it.item.recommended }
        SettingRow(tr("Отключить рекомендуемое"), if (recommended > 0) tr("{0} шт.", recommended) else tr("готово"),
            hint = tr("Реклама, аналитика, PatchWall, лишнее от Google и предустановки")) {
            act(tr("Отключаю")) { tr("Отключено: {0}", (TvCleanup.disableRecommended(context))) }
        }
        SettingRow(tr("Вернуть всё как было"), tr("{0} шт.", (cfg.cleanupDisabled.size)),
            hint = tr("Включит всё, что отключила чистка")) {
            act(tr("Возвращаю")) { tr("Возвращено: {0}", (TvCleanup.restoreAll(context))) }
        }
        SettingRow(tr("Очистить кэш приложений"), hint = tr("Временные файлы всех приложений, ничего важного не теряется")) {
            act(tr("Чищу кэш")) { tr("Освобождено {0} МБ", (mb(TvCleanup.trimCaches(context)))) }
        }
        SettingRow(tr("Удалить установщики APK"), if (apks.second > 0) tr("{0} шт. · {1} МБ", (apks.second), (mb(apks.first))) else tr("нет"),
            hint = tr("Файлы .apk из «Загрузок»: приложения уже установлены")) {
            act(tr("Удаляю")) { tr("Удалено {0} МБ", (mb(TvCleanup.deleteApks()))) }
        }
        SettingRow(tr("Проверить доступ"), hint = tr("Подключиться к системе ТВ — при первом разе ТВ спросит разрешение")) {
            act(tr("Подключаюсь")) { if (AdbClient(context).shell("echo ok").trim() == "ok") tr("Доступ есть") else tr("Нет ответа") }
        }

        states.groupBy { it.item.group }.forEach { (group, list) ->
            SectionTitle(group)
            list.forEach { s ->
                ToggleRow(s.item.name + if (s.item.recommended) "" else tr(" (по желанию)"), s.enabled, s.item.pkg) { on ->
                    act(if (on) tr("Включаю") else tr("Отключаю")) {
                        if (on) { TvCleanup.enable(context, s.item.pkg); tr("Включено: {0}", (s.item.name)) }
                        else { TvCleanup.disable(context, s.item.pkg); tr("Отключено: {0}", (s.item.name)) }
                    }
                }
            }
        }
    }
}

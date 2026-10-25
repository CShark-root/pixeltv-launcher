package com.pixelden.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.i18n.tr
import com.pixelden.launcher.system.AppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Каталог приложений: установка в одну кнопку и по ссылке. Окно ввода ссылки — на корне экрана настроек,
 * поэтому сюда приходит только колбэк [onEnterUrl].
 */
@Composable
fun CatalogPanel(onEnterUrl: () -> Unit) {
    val context = LocalContext.current
    val t = LocalDen.current
    val scope = rememberCoroutineScope()
    val progress by AppCatalog.progress.collectAsState()
    // Пересчитать «установлено» после возврата из окна установки.
    val apps by PixelDenApp.instance.apps.apps.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val installed = remember(apps, tick) { AppCatalog.APPS.filter { AppCatalog.installed(context, it.pkg) }.map { it.pkg }.toSet() }

    fun install(app: AppCatalog.App) {
        if (app.pkg in progress) return
        scope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { AppCatalog.install(context, app) }.exceptionOrNull() }
            if (err != null) Toast.makeText(context, tr("Не вышло: {0}", err.message ?: err.javaClass.simpleName), Toast.LENGTH_LONG).show()
            tick++
        }
    }

    Column(Modifier.fillMaxWidth()) {
        PText(tr("Последние официальные версии, сборка под процессор этого ТВ. Установку подтвердите пультом."), 14.sp, t.colors.dim, Modifier.padding(4.dp, 0.dp, 0.dp, 10.dp), maxLines = 2)
        SettingRow(tr("Установить по ссылке…"), hint = tr("Прямая ссылка на .apk"), onClick = onEnterUrl)
        AppCatalog.GROUPS.forEach { (group, title) ->
            SectionTitle(title)
            AppCatalog.APPS.filter { it.group == group }.forEach { app ->
                val p = progress[app.pkg]
                val value = when {
                    p == -1 -> tr("подтвердите на ТВ")
                    p != null -> "$p%"
                    app.pkg in installed -> tr("обновить")
                    else -> tr("установить")
                }
                SettingRow(app.name, value, hint = app.description) { install(app) }
            }
        }
    }
}

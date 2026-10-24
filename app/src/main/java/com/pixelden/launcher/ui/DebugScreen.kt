package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.scenario.DenAccessibilityService

/** Журнал отладки: что запускалось, какие кнопки нажимались, где ошибки. Свежие записи сверху. */
@Composable
fun DebugLogScreen() {
    val t = LocalDen.current
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsState()
    var keys by remember { mutableStateOf(DebugLog.logKeys) }
    val first = rememberAutoFocus()
    val service = DenAccessibilityService.instance != null

    Box(Modifier.fillMaxSize().background(t.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 1200.dp).fillMaxSize(), contentPadding = PaddingValues(40.dp, 32.dp)) {
            item { PText(tr("Журнал"), 40.sp) }
            item {
                PText(
                    tr("Служба кнопок: {0} · записей: {1}", (if (service) tr("работает") else tr("ВЫКЛЮЧЕНА")), (entries.size)),
                    15.sp, if (service) t.colors.dim else t.colors.accent, Modifier.padding(bottom = 12.dp),
                )
            }
            item {
                Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PButton(if (keys) tr("Кнопки: пишутся") else tr("Кнопки: не пишутся"), Modifier.focusRequester(first), size = 14.sp) {
                        DebugLog.logKeys = !DebugLog.logKeys
                        keys = DebugLog.logKeys
                        DebugLog.i("журнал кнопок: ${if (keys) tr("вкл") else tr("выкл")}")
                    }
                    PButton(tr("Очистить"), size = 14.sp) { DebugLog.clear() }
                    PButton(tr("Сохранить в файл"), size = 14.sp) {
                        val f = DebugLog.dumpToFile(context, PixelDenApp.instance.store.current)
                        Toast.makeText(context, tr("Сохранено: {0}", (f.absolutePath)), Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (entries.isEmpty()) item { PText(tr("Пока пусто. Запустите сценарий или включите запись кнопок и понажимайте пульт."), 15.sp, t.colors.dim, maxLines = 3) }
            items(entries.asReversed()) { e ->
                PText(
                    DebugLog.format(e),
                    14.sp,
                    if (e.error) Color(0xFFFF7A7A) else t.colors.text.copy(alpha = 0.85f),
                    Modifier.padding(vertical = 2.dp),
                    maxLines = 3,
                )
            }
        }
    }
}

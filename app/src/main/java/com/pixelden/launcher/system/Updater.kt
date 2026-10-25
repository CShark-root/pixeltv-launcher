package com.pixelden.launcher.system

import com.pixelden.launcher.i18n.tr
import android.content.Context
import com.pixelden.launcher.BuildConfig
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.web.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

/** Автообновление из GitHub Releases: раз в сутки смотрит последний релиз, скачивает APK и отдаёт на установку. */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val percent: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var loop: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Проверять раз в сутки (первый раз — через минуту после старта, когда сеть уже поднялась). */
    fun apply(auto: Boolean) {
        val app = PixelDenApp.instance
        if (!auto || !enabled) { loop?.cancel(); loop = null; return }
        if (loop?.isActive == true) return
        loop = app.scope.launch {
            delay(60_000)
            while (true) {
                val found = check()
                if (found != null) app.engine.notify(tr("Вышла новая версия PixelTv {0}! Настройки → О программе", (found.version)))
                delay(24 * 3_600_000L)
            }
        }
    }

    /** Есть ли релиз новее установленного. */
    suspend fun check(): Release? {
        if (!enabled) return null
        _state.value = State.Checking
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "PixelTv/${BuildConfig.VERSION_NAME}")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = try {
                    if (c.responseCode != 200) error(tr("GitHub ответил {0}", (c.responseCode)))
                    c.inputStream.bufferedReader().use { it.readText() }
                } finally { c.disconnect() }
                val o = json.parseToJsonElement(body).jsonObject
                val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: error(tr("нет версии"))
                val asset = o["assets"]?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
                    ?: error(tr("в релизе нет APK"))
                Release(
                    tag,
                    o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    asset["browser_download_url"]!!.jsonPrimitive.content,
                    asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                )
            }
        }
        return r.fold(
            onSuccess = { rel ->
                if (newer(rel.version, BuildConfig.VERSION_NAME)) {
                    DebugLog.i("обновление: есть ${rel.version} (сейчас ${BuildConfig.VERSION_NAME})")
                    _state.value = State.Available(rel); rel
                } else { _state.value = State.UpToDate; null }
            },
            onFailure = { DebugLog.e("обновление: проверка не удалась", it); _state.value = State.Failed(it.message ?: "ошибка"); null },
        )
    }

    /** Скачать APK релиза и отдать системе — на ТВ появится окно «Установить?». */
    suspend fun install(context: Context, rel: Release) {
        _state.value = State.Downloading(0)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.cacheDir, "update.apk")
                Download.toFile(rel.apkUrl, file) { _state.value = State.Downloading(it) }
                ApkInstaller.install(context, file)
            }
        }
        ok.onSuccess { _state.value = State.Available(rel) }
            .onFailure { DebugLog.e("обновление: не удалось", it); _state.value = State.Failed(it.message ?: "ошибка") }
    }

    /** Сравнение версий вида 0.9.1 (лишние части и суффиксы вроде -beta не мешают). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

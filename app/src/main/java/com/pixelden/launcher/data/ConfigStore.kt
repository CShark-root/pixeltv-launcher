package com.pixelden.launcher.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

class ConfigStore(private val context: Context) {

    private val file = File(context.filesDir, "config.json")
    private val _config = MutableStateFlow(load())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    val current: LauncherConfig get() = _config.value

    @Synchronized
    fun update(transform: (LauncherConfig) -> LauncherConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        save(next)
    }

    private fun load(): LauncherConfig = try {
        if (file.exists()) json.decodeFromString(LauncherConfig.serializer(), file.readText()) else LauncherConfig()
    } catch (e: Exception) {
        // Битый файл не должен ронять лаунчер: откладываем его в сторону и стартуем с нуля.
        file.renameTo(File(context.filesDir, "config.broken.json"))
        LauncherConfig()
    }

    // Запись на диск — в отдельном потоке: каждое нажатие < > в настройках раньше ждало флеш-память ТВ.
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun save(cfg: LauncherConfig) {
        writer.execute {
            // Пока ждали очереди, могли прийти новые правки — пишем только самую свежую.
            if (cfg !== _config.value) return@execute
            val tmp = File(context.filesDir, "config.json.tmp")
            tmp.writeText(json.encodeToString(LauncherConfig.serializer(), cfg))
            tmp.renameTo(file)
        }
    }

    fun exportFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "pixelden-backup.json")

    fun exportBackup(): Result<File> = runCatching {
        val out = exportFile()
        out.parentFile?.mkdirs()
        out.writeText(json.encodeToString(LauncherConfig.serializer(), current))
        out
    }

    fun importBackup(): Result<Unit> = runCatching {
        val cfg = json.decodeFromString(LauncherConfig.serializer(), exportFile().readText())
        _config.value = cfg
        save(cfg)
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            classDiscriminator = "type"
        }
    }
}

package com.pixelden.launcher.system

import android.content.Context
import android.os.Build
import com.pixelden.launcher.i18n.I18n
import com.pixelden.launcher.i18n.tr
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.web.ApkInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Каталог приложений для ТВ: только официальные APK — релизы на GitHub или папки загрузок проектов.
 * Последняя версия берётся в момент установки; сборка выбирается под процессор телевизора.
 */
object AppCatalog {
    sealed interface Source
    /** Последний релиз на GitHub; [skip] — части имён сборок, которые не подходят (отладка, root, Play…). */
    data class GitHub(val repo: String, val skip: List<String> = emptyList()) : Source
    /** Папка загрузок: [folder] — адрес по архитектуре, [file] — имя файла (группа 1 — версия). */
    data class Folder(val folder: (String) -> String?, val file: (String) -> Regex) : Source

    data class App(val pkg: String, val name: String, val ru: String, val en: String, val group: String, val source: Source) {
        val description get() = if (I18n.lang == "ru") ru else en
    }

    private val ALWAYS_SKIP = listOf("debug", "legacy", "allstorage", "app-root", "google-play", "fdroid")

    val APPS = listOf(
        App("org.smarttube.stable", "SmartTube", "YouTube без рекламы для ТВ", "Ad-free YouTube for TV", "video", GitHub("yuliskov/SmartTube")),
        App("io.gh.reisxd.tizentube.cobalt", "TizenTube", "YouTube в оригинальном интерфейсе, без рекламы", "YouTube with the original TV UI, no ads", "video", GitHub("reisxd/TizenTubeCobalt")),
        App("org.videolan.vlc", "VLC", "Плеер, который откроет всё", "Plays everything", "video",
            Folder({ abi -> "https://get.videolan.org/vlc-android/last/" }, { abi -> Regex("VLC-Android-([0-9.]+)-${Regex.escape(abi)}\\.apk") })),
        App("org.xbmc.kodi", "Kodi", "Медиацентр с дополнениями", "Media center with add-ons", "video",
            Folder({ abi -> when (abi) { "armeabi-v7a" -> "https://mirrors.kodi.tv/releases/android/arm/"; "arm64-v8a" -> "https://mirrors.kodi.tv/releases/android/arm64-v8a/"; else -> null } },
                { abi -> Regex("kodi-([0-9.]+)-[A-Za-z]+-${Regex.escape(abi)}\\.apk") })),
        App("com.brouken.player", "Just Player", "Простой быстрый плеер", "Simple, fast video player", "video", GitHub("moneytoo/Player")),
        App("is.xyz.mpv", "mpv", "Плеер mpv для любых форматов", "The mpv player for any format", "video", GitHub("mpv-android/mpv-android")),
        App("org.courville.nova", "Nova Video Player", "Плеер с библиотекой фильмов для ТВ", "Player with a movie library for TV", "video", GitHub("nova-video-player/aos-AVP")),
        App("org.jellyfin.androidtv", "Jellyfin", "Клиент домашнего медиасервера Jellyfin", "Client for your Jellyfin media server", "video", GitHub("jellyfin/jellyfin-androidtv")),
        App("ru.yourok.torrserve", "TorrServe", "Смотреть торренты без скачивания", "Stream torrents without downloading", "video", GitHub("YouROK/TorrServe")),
        App("com.limelight", "Moonlight", "Игры с компьютера на ТВ (NVIDIA/Sunshine)", "Stream games from your PC (NVIDIA/Sunshine)", "games", GitHub("moonlight-stream/moonlight-android")),
        App("com.swordfish.lemuroid", "Lemuroid", "Ретро-приставки: NES, SNES, Sega, PS1…", "Retro consoles: NES, SNES, Sega, PS1…", "games", GitHub("Swordfish90/Lemuroid")),
        App("com.phlox.tvwebbrowser", "TV Bro", "Браузер, удобный с пульта", "A browser made for the remote", "tools", GitHub("truefedex/tv-bro")),
        App("org.localsend.localsend_app", "LocalSend", "Файлы с телефона и ПК по Wi-Fi", "Send files from phone or PC over Wi-Fi", "tools", GitHub("localsend/localsend")),
        App("me.zhanghai.android.files", "Material Files", "Файловый менеджер", "File manager", "tools", GitHub("zhanghai/MaterialFiles")),
        App("com.neilturner.aerialviews", "Aerial Views", "Заставка с видами Apple TV", "Apple TV-style aerial screensaver", "tools", GitHub("theothernt/AerialViews")),
    )

    val GROUPS get() = listOf("video" to tr("Видео"), "games" to tr("Игры"), "tools" to tr("Полезное"))

    /** Что сейчас ставится: пакет (или ссылка) → проценты загрузки; -1 — ждём подтверждения на ТВ. */
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress

    fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Части имён файлов для архитектур процессора. */
    private val ABI_TOKENS = mapOf(
        "arm64-v8a" to listOf("arm64-v8a", "arm64v8", "arm64"),
        "armeabi-v7a" to listOf("armeabi-v7a", "arm32v7", "armv7", "-arm.apk"),
        "x86_64" to listOf("x86_64", "x64"),
        "x86" to listOf("x86"),
    )

    /** Подходящая сборка из списка имён: сначала под процессор ТВ, потом универсальная, потом без пометки архитектуры. */
    fun pick(names: List<String>, skip: List<String> = emptyList()): String? {
        val apks = names.filter { n -> n.endsWith(".apk") && (ALWAYS_SKIP + skip).none { n.contains(it, ignoreCase = true) } }
        for (abi in Build.SUPPORTED_ABIS) {
            ABI_TOKENS[abi]?.let { tokens -> apks.firstOrNull { n -> tokens.any { n.contains(it, ignoreCase = true) } }?.let { return it } }
        }
        apks.firstOrNull { it.contains("universal", ignoreCase = true) }?.let { return it }
        val allTokens = ABI_TOKENS.values.flatten()
        return apks.firstOrNull { n -> allTokens.none { n.contains(it, ignoreCase = true) } } ?: apks.firstOrNull()
    }

    /** Последние версии: пакет → (версия, когда узнали). Живут сутки — чтобы не упереться в лимит API GitHub. */
    private val latest = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Последняя версия из кэша или с сайта; null — узнать не вышло. Блокирующий. */
    fun latestVersion(app: App): String? {
        latest[app.pkg]?.let { (v, at) -> if (System.currentTimeMillis() - at < 24 * 3_600_000L) return v }
        return runCatching { resolve(app); latest[app.pkg]?.first }.getOrNull()
    }

    /** Есть ли версия новее установленной (по числам в номерах версий). null — неизвестно. */
    fun updateAvailable(context: Context, app: App): Boolean? {
        val installed = runCatching { context.packageManager.getPackageInfo(app.pkg, 0).versionName }.getOrNull() ?: return null
        val newest = latestVersion(app) ?: return null
        fun nums(v: String) = Regex("[0-9]+").findAll(v).map { it.value.toLong() }.toList()
        val a = nums(newest); val b = nums(installed)
        if (a.isEmpty() || b.isEmpty()) return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    /** Установленные приложения каталога, для которых вышла версия новее, — для шторки. */
    private val _updates = MutableStateFlow<List<App>>(emptyList())
    val updates: StateFlow<List<App>> = _updates
    /**
     * Пересчитать [updates]. Последние версии берутся из кэша на сутки, так что в сеть ходим редко,
     * а только что обновлённое приложение пропадает из списка сразу. Блокирующий.
     */
    fun refreshUpdates(context: Context) {
        _updates.value = APPS.filter { installed(context, it.pkg) && updateAvailable(context, it) == true }
    }

    /** Прямая ссылка на APK последней версии под этот ТВ (заодно запоминает номер версии). */
    fun resolve(app: App): String = when (val s = app.source) {
        is GitHub -> {
            val o = Json.parseToJsonElement(Download.text("https://api.github.com/repos/${s.repo}/releases/latest")).jsonObject
            o["tag_name"]?.jsonPrimitive?.content?.let { latest[app.pkg] = it to System.currentTimeMillis() }
            val assets = o["assets"]!!.jsonArray.map { it.jsonObject }
            val names = assets.map { it["name"]!!.jsonPrimitive.content }
            val name = pick(names, s.skip) ?: error(tr("в релизе нет подходящего APK"))
            assets.first { it["name"]!!.jsonPrimitive.content == name }["browser_download_url"]!!.jsonPrimitive.content
        }
        is Folder -> {
            val abi = Build.SUPPORTED_ABIS.firstOrNull { s.folder(it) != null } ?: error(tr("нет сборки под процессор этого ТВ"))
            val folder = s.folder(abi)!!
            val re = s.file(abi)
            // Самая новая стабильная версия из списка файлов папки.
            val best = re.findAll(Download.text(folder)).maxWithOrNull { a, b -> version(a.groupValues[1], b.groupValues[1]) }
                ?: error(tr("не нашёл файл на сайте"))
            latest[app.pkg] = best.groupValues[1] to System.currentTimeMillis()
            folder + best.value
        }
    }

    private fun version(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** Скачать и отдать на установку приложение из каталога. Блокирующий — звать не с главного потока. */
    fun install(context: Context, app: App) = installUrl(context, resolve(app), app.pkg, app.name)

    /** Скачать APK по ссылке и отдать на установку; ТВ покажет окно подтверждения. */
    fun installUrl(context: Context, url: String, key: String = url, name: String = url.substringAfterLast('/')) {
        val file = File(context.cacheDir, "install-${key.hashCode()}.apk")
        try {
            _progress.value = _progress.value + (key to 0)
            DebugLog.i("каталог: скачиваю $name ← $url")
            Download.toFile(url, file) { p -> _progress.value = _progress.value + (key to p) }
            _progress.value = _progress.value + (key to -1)
            ApkInstaller.install(context, file)
        } finally {
            file.delete()
            _progress.value = _progress.value - key
        }
    }
}

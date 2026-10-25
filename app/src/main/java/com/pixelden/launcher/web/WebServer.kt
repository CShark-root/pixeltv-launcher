package com.pixelden.launcher.web

import com.pixelden.launcher.i18n.tr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.R
import com.pixelden.launcher.data.ConfigStore
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.scenario.DenAccessibilityService
import com.pixelden.launcher.scenario.Scheduler
import com.pixelden.launcher.system.Health
import com.pixelden.launcher.system.trimSelf
import com.pixelden.launcher.system.SleepTimer
import com.pixelden.launcher.ui.FONTS
import com.pixelden.launcher.ui.FOCUS_EFFECTS
import com.pixelden.launcher.ui.MASCOTS
import com.pixelden.launcher.ui.SCENES
import com.pixelden.launcher.ui.SCREEN_TRANSITIONS
import com.pixelden.launcher.ui.TIME_SLOTS
import com.pixelden.launcher.ui.WIDGETS
import com.pixelden.launcher.ui.decodeSampled
import com.pixelden.launcher.ui.listWallpapers
import com.pixelden.launcher.ui.uploadedWallpaperDir
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Настройка с телефона: маленький HTTP-сервер в домашней сети.
 * Страница открывается без PIN, а все /api/ — только с PIN, который показан на ТВ (Настройки → С телефона).
 */
class WebServer(private val app: PixelDenApp) {

    private var server: ServerSocket? = null
    // Не больше четырёх запросов одновременно: телефону хватит, а ТВ не захлебнётся.
    private val pool = Executors.newFixedThreadPool(4)
    /** Неудачные попытки PIN по адресам: число ошибок и до какого времени вход закрыт. */
    private val fails = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    val running get() = server?.isClosed == false

    @Synchronized
    fun start() {
        if (running) return
        runCatching {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            server = ss
            Thread({ acceptLoop(ss) }, "pixeltv-web").apply { isDaemon = true }.start()
            DebugLog.i("веб-панель: http://${lanAddress() ?: "?"}:$PORT")
        }.onFailure { DebugLog.e("веб-панель не запустилась", it) }
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            pool.execute { handle(s) }
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
        val length: Long,
    ) {
        var ip: String = ""
        fun text(limit: Int = 2_000_000): String {
            if (length > limit) error(tr("слишком большой запрос"))
            val buf = ByteArray(length.toInt())
            var read = 0
            while (read < buf.size) { val n = body.read(buf, read, buf.size - read); if (n < 0) break; read += n }
            return String(buf, 0, read, Charsets.UTF_8)
        }

        /** Тело запроса прямо в файл — APK и фото не держим в памяти целиком. */
        fun saveTo(file: File, limit: Long) {
            if (length <= 0 || length > limit) error(tr("файл пустой или слишком большой"))
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
                if (left > 0) error(tr("передача оборвалась"))
            }
        }
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val req = try { parse(input) } catch (e: Exception) { return } ?: return
            req.ip = sock.inetAddress?.hostAddress.orEmpty()
            try {
                route(req, out)
            } catch (e: Exception) {
                DebugLog.e("веб-панель: ${req.method} ${req.path}", e)
                json(out, 500, buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) })
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        // Заголовки — до пустой строки, не больше 16 КБ.
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val (method, target) = lines.first().split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        return Request(method, path, query, headers, input, headers["content-length"]?.toLongOrNull() ?: 0L)
    }

    private fun route(req: Request, out: OutputStream) {
        val cfg = app.store.current
        if (!req.path.startsWith("/api/")) {
            when (req.path) {
                "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8", app.assets.open("web/index.html").use { it.readBytes() })
                "/i18n.json" -> json(out, 200, buildJsonObject {
                    put("lang", com.pixelden.launcher.i18n.I18n.lang)
                    put("dict", JsonObject(com.pixelden.launcher.i18n.I18n.dictionary().mapValues { JsonPrimitive(it.value) }))
                })
                "/font.ttf" -> send(out, 200, "font/ttf", app.resources.openRawResource(R.font.tiny5).use { it.readBytes() }, cache = true)
                else -> send(out, 404, "text/plain", "not found".toByteArray())
            }
            return
        }
        // Подбор PIN: после 10 ошибок с одного адреса — пауза 5 минут (4 цифры иначе перебираются за минуты).
        val now = System.currentTimeMillis()
        val lock = fails[req.ip]
        if (lock != null && lock.second > now) {
            json(out, 429, buildJsonObject { put("error", tr("Слишком много неверных PIN. Подождите 5 минут.")) })
            return
        }
        val pin = req.headers["x-pin"] ?: req.query["pin"]
        if (pin != cfg.webPin) {
            val n = (lock?.first ?: 0) + 1
            fails[req.ip] = if (n >= 10) 0 to now + 5 * 60_000L else n to 0L
            if (n >= 10) DebugLog.e("веб-панель: подбор PIN с ${req.ip}, вход закрыт на 5 минут")
            json(out, 401, buildJsonObject { put("error", tr("Неверный PIN. Он показан на ТВ: Настройки → С телефона")) })
            return
        }
        fails.remove(req.ip)
        when ("${req.method} ${req.path}") {
            "GET /api/state" -> json(out, 200, state(cfg))
            "POST /api/config" -> {
                val incoming = ConfigStore.json.decodeFromString(LauncherConfig.serializer(), req.text())
                applyConfig(cfg, incoming)
                json(out, 200, buildJsonObject { put("ok", true) })
            }
            "GET /api/icon" -> {
                val pkg = req.query["pkg"].orEmpty()
                val img = runBlocking { app.apps.icon(pkg) }
                if (img == null) send(out, 404, "text/plain", ByteArray(0))
                else send(out, 200, "image/png", png(img.asAndroidBitmap()), cache = true)
            }
            "GET /api/thumb" -> {
                val path = req.query["path"].orEmpty()
                if (path !in listWallpapers()) { send(out, 404, "text/plain", ByteArray(0)); return }
                val bmp = decodeSampled(path, 320, 180) ?: error(tr("не картинка"))
                val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                bmp.recycle()
                send(out, 200, "image/jpeg", bytes, cache = true)
            }
            "POST /api/wallpaper" -> {
                val name = req.query["name"].orEmpty().substringAfterLast('/').replace(Regex("[^\\w.\\-]"), "_").ifBlank { "wallpaper.jpg" }
                val dir = uploadedWallpaperDir(app).apply { mkdirs() }
                val file = File(dir, name)
                req.saveTo(file, 40L * 1024 * 1024)
                val ok = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outWidth > 0
                if (!ok) { file.delete(); error(tr("это не картинка")) }
                DebugLog.i("веб-панель: загружены обои ${file.name} (${file.length() / 1024} КБ)")
                json(out, 200, buildJsonObject { put("path", file.absolutePath) })
            }
            "POST /api/wallpaper/delete" -> {
                val f = File(req.query["path"].orEmpty())
                if (f.parentFile?.canonicalPath != uploadedWallpaperDir(app).canonicalPath) error(tr("удалять можно только загруженные обои"))
                f.delete()
                app.store.update { c ->
                    c.copy(
                        wallpaperPath = c.wallpaperPath.takeIf { it != f.absolutePath },
                        timeWallpapers = c.timeWallpapers.filterValues { it != f.absolutePath },
                    )
                }
                json(out, 200, buildJsonObject { put("ok", true) })
            }
            "POST /api/run" -> {
                val s = app.engine.scenario(req.query["id"].orEmpty()) ?: error(tr("сценарий не найден"))
                app.engine.run(s, tr("телефон"))
                json(out, 200, buildJsonObject { put("ok", true) })
            }
            "GET /api/city" -> {
                val list = runBlocking { app.weather.searchCity(req.query["q"].orEmpty()) }
                json(out, 200, buildJsonArray {
                    list.forEach { p -> addJsonObject { put("name", p.name); put("region", p.region); put("lat", p.lat); put("lon", p.lon) } }
                })
            }
            "POST /api/text" -> {
                val svc = DenAccessibilityService.instance ?: error(tr("служба кнопок PixelTv выключена"))
                val ok = svc.typeText(req.text(10_000), append = req.query["append"] == "1")
                json(out, if (ok) 200 else 409, buildJsonObject {
                    put("ok", ok)
                    if (!ok) put("error", tr("На ТВ сейчас не выбрано поле ввода. Выберите его пультом и повторите."))
                })
            }
            "POST /api/nav" -> {
                val svc = DenAccessibilityService.instance ?: error(tr("служба кнопок PixelTv выключена"))
                val action = when (req.query["a"]) {
                    "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                    "sleep" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    else -> error(tr("неизвестное действие"))
                }
                json(out, 200, buildJsonObject { put("ok", svc.performGlobalAction(action)) })
            }
            "POST /api/timer" -> {
                SleepTimer.set(req.query["min"]?.toIntOrNull() ?: 0)
                json(out, 200, buildJsonObject { put("left", SleepTimer.minutesLeft()?.let { JsonPrimitive(it) } ?: JsonPrimitive(0)) })
            }
            "GET /api/health" -> json(out, 200, health())
            "POST /api/free" -> {
                val pkgs = app.apps.apps.value.map { it.pkg } + Health.recentApps(app).map { it.pkg }
                val freed = Health.freeMemory(app, pkgs.toSet())
                val self = Health.trimSelf(app)
                json(out, 200, buildJsonObject { put("freedMb", freed / 1_048_576); put("selfMb", self / 1_048_576) })
            }
            "GET /api/catalog" -> json(out, 200, buildJsonArray {
                com.pixelden.launcher.system.AppCatalog.APPS.forEach { a ->
                    addJsonObject {
                        put("pkg", a.pkg); put("name", a.name); put("desc", a.description); put("group", a.group)
                        put("installed", com.pixelden.launcher.system.AppCatalog.installed(app, a.pkg))
                    }
                }
            })
            "POST /api/catalog/install" -> {
                val a = com.pixelden.launcher.system.AppCatalog.APPS.firstOrNull { it.pkg == req.query["pkg"] } ?: error(tr("нет такого приложения"))
                // Скачивание может идти минуту — отвечаем сразу, ставим в фоне.
                app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.pixelden.launcher.system.AppCatalog.install(app, a) }.onFailure { DebugLog.e("каталог: ${a.name}", it) } }
                json(out, 200, buildJsonObject { put("ok", true) })
            }
            "POST /api/install-url" -> {
                val url = req.text(4096).trim()
                if (!url.startsWith("http")) error(tr("нужна ссылка http(s)"))
                app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.pixelden.launcher.system.AppCatalog.installUrl(app, url) }.onFailure { DebugLog.e("установка по ссылке", it) } }
                json(out, 200, buildJsonObject { put("ok", true) })
            }
            "POST /api/apk" -> {
                val file = File(app.cacheDir, "update.apk")
                req.saveTo(file, 300L * 1024 * 1024)
                ApkInstaller.install(app, file)
                json(out, 200, buildJsonObject { put("ok", true) })
            }
            "GET /api/log" -> send(out, 200, "text/plain; charset=utf-8",
                DebugLog.entries.value.takeLast(300).joinToString("\n") { DebugLog.format(it) }.toByteArray())
            else -> json(out, 404, buildJsonObject { put("error", tr("нет такого запроса")) })
        }
    }

    /** Ключ Яндекса наружу не отдаём: телефон присылает "__keep__", если его не меняли. */
    private fun applyConfig(old: LauncherConfig, incoming: LauncherConfig) {
        val next = incoming.copy(
            yandexWeatherKey = if (incoming.yandexWeatherKey == KEEP) old.yandexWeatherKey else incoming.yandexWeatherKey.trim(),
            webPin = incoming.webPin.takeIf { it.length in 4..8 && it.all(Char::isDigit) } ?: old.webPin,
            defaultsVersion = old.defaultsVersion,
            templatesOffered = old.templatesOffered,
            // Список отключённого ведёт сам ТВ: устаревшая копия с телефона его не затирает.
            cleanupDisabled = old.cleanupDisabled,
        )
        app.store.update { next }
        DebugLog.i("веб-панель: настройки сохранены с телефона")
        if (next.weatherLat != old.weatherLat || next.weatherLon != old.weatherLon || next.yandexWeatherKey != old.yandexWeatherKey || next.weatherEnabled != old.weatherEnabled) {
            app.scope.launch { app.weather.refresh() }
        }
        if (next.talkNews && !old.talkNews) app.news.start()
        if (!next.talkNews && old.talkNews) app.news.stop()
        if (next.talkNews && (next.newsSource != old.newsSource || next.newsCustomUrl != old.newsCustomUrl)) app.scope.launch { app.news.refresh() }
        // Язык сменился — перезапуск процесса (подписи и списки создаются один раз).
        if (com.pixelden.launcher.i18n.I18n.resolve(next.language) != com.pixelden.launcher.i18n.I18n.lang) {
            app.scope.launch { kotlinx.coroutines.delay(800); android.os.Process.killProcess(android.os.Process.myPid()) }
        }
        if (!next.webPanel) app.scope.launch { kotlinx.coroutines.delay(500); stop() }
        com.pixelden.launcher.system.MemoryGuard.apply(next.memoryGuard)
        com.pixelden.launcher.system.Updater.apply(next.autoUpdate)
        Scheduler.scheduleAll(app, next)
        app.engine.notify(tr("Настройки пришли с телефона!"))
    }

    private fun state(cfg: LauncherConfig): JsonObject {
        val config = ConfigStore.json.encodeToJsonElement(LauncherConfig.serializer(), cfg).jsonObject
        return buildJsonObject {
            put("config", JsonObject(config + ("yandexWeatherKey" to JsonPrimitive(KEEP))))
            put("hasYandexKey", cfg.yandexWeatherKey.isNotBlank())
            put("version", com.pixelden.launcher.BuildConfig.VERSION_NAME)
            put("timerLeft", SleepTimer.minutesLeft() ?: 0)
            put("a11y", DenAccessibilityService.instance != null)
            putJsonArray("apps") {
                app.apps.apps.value.sortedBy { (cfg.renamed[it.pkg] ?: it.label).lowercase() }.forEach { e ->
                    addJsonObject { put("pkg", e.pkg); put("label", e.label) }
                }
            }
            putJsonArray("inputs") { app.apps.inputs.value.forEach { i -> addJsonObject { put("id", i.id); put("label", i.label) } } }
            putJsonArray("wallpapers") {
                val up = uploadedWallpaperDir(app).canonicalPath
                listWallpapers().forEach { p -> addJsonObject { put("path", p); put("name", File(p).nameWithoutExtension); put("uploaded", File(p).parentFile?.canonicalPath == up) } }
            }
            put("scenes", pairs(SCENES))
            put("timeSlots", pairs(TIME_SLOTS))
            put("mascots", pairs(MASCOTS.map { it.id to it.name }))
            put("fonts", pairs(FONTS))
            put("effects", pairs(FOCUS_EFFECTS.map { it.first to it.second.removePrefix(tr("Только ")) }))
            put("transitions", pairs(SCREEN_TRANSITIONS))
            put("widgets", pairs(WIDGETS))
            put("languages", pairs(com.pixelden.launcher.i18n.I18n.LANGUAGES.map { (id, n) -> id to (if (id == "auto") tr("как в системе") else n) }))
            put("newsSources", pairs(listOf("auto" to tr("по языку")) + com.pixelden.launcher.weather.NewsSources.ALL.map { it.id to it.name } + ("custom" to tr("свой адрес RSS"))))
            putJsonArray("accents") { com.pixelden.launcher.ui.ACCENTS.forEach { add(it) } }
            putJsonArray("backgrounds") { com.pixelden.launcher.ui.BACKGROUNDS.forEach { add(it) } }
        }
    }

    private fun health(): JsonObject {
        val s = Health.snapshot(app)
        val labels = app.apps.apps.value.associate { it.pkg to (app.store.current.renamed[it.pkg] ?: it.label) }
        return buildJsonObject {
            put("ramTotal", s.ramTotal); put("ramAvail", s.ramAvail); put("lowMemory", s.lowMemory)
            put("launcherPss", s.launcherPss)
            put("storageTotal", s.storageTotal); put("storageFree", s.storageFree)
            s.tempC?.let { put("tempC", it) }
            put("cores", s.cpuCores)
            putJsonArray("mhz") { s.cpuMhz.forEach { add(it) } }
            put("uptimeMs", s.uptimeMs)
            put("usageAccess", Health.hasUsageAccess(app))
            putJsonArray("recent") {
                Health.recentApps(app).take(15).forEach { r ->
                    addJsonObject { put("pkg", r.pkg); put("label", labels[r.pkg] ?: r.label); put("lastUsed", r.lastUsed); put("fgMs", r.foregroundMs) }
                }
            }
        }
    }

    private fun pairs(list: List<Pair<String, String>>): JsonElement = buildJsonArray {
        list.forEach { (id, name) -> addJsonObject { put("id", id); put("name", name) } }
    }

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun json(out: OutputStream, code: Int, body: JsonElement) =
        send(out, code, "application/json; charset=utf-8", body.toString().toByteArray())

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = false) {
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            (if (cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n") +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        const val PORT = 8765
        const val KEEP = "__keep__"

        /** Адрес ТВ в домашней сети (Wi-Fi или кабель). */
        // Перебор сетевых интерфейсов не бесплатный, а плитка настроек спрашивает адрес на каждой перерисовке.
        @Volatile private var cached: Pair<Long, String?>? = null

        fun lanAddress(): String? {
            val c = cached
            if (c != null && android.os.SystemClock.elapsedRealtime() - c.first < 30_000) return c.second
            return readLanAddress().also { cached = android.os.SystemClock.elapsedRealtime() to it }
        }

        private fun readLanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

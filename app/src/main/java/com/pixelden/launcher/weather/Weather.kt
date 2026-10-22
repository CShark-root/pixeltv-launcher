package com.pixelden.launcher.weather

import com.pixelden.launcher.data.ConfigStore
import com.pixelden.launcher.scenario.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Состояние погоды, общее для Яндекса и Open-Meteo. */
enum class Sky(val text: String) {
    CLEAR("ясно"), PARTLY("малооблачно"), CLOUDY("облачно"), OVERCAST("пасмурно"),
    FOG("туман"), DRIZZLE("морось"), RAIN("дождь"), SNOW("снег"), SLEET("мокрый снег"), THUNDER("гроза"),
}

data class WeatherNow(
    val temp: Int,
    val feelsLike: Int?,
    val sky: Sky,
    val isDay: Boolean,
    val source: String,
    val updatedAt: Long,
)

data class Place(val name: String, val region: String, val lat: Double, val lon: Double)

/** Загружает погоду раз в 30 минут; с ключом — у Яндекса, без ключа — у Open-Meteo. */
class WeatherRepository(private val store: ConfigStore, private val scope: CoroutineScope) {

    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var loop: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                // при ошибке — повтор через 3 минуты, иначе раз в полчаса
                delay(if (refresh()) 30 * 60_000L else 3 * 60_000L)
            }
        }
    }

    /** true — погода загружена (или показывать нечего), false — ошибка, стоит повторить скоро. */
    suspend fun refresh(): Boolean {
        val cfg = store.current
        val lat = cfg.weatherLat
        val lon = cfg.weatherLon
        if (!cfg.weatherEnabled || lat == null || lon == null) {
            _now.value = null
            return true
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (cfg.yandexWeatherKey.isNotBlank()) yandex(lat, lon, cfg.yandexWeatherKey.trim())
                else openMeteo(lat, lon)
            }
        }
        result.onSuccess {
            _now.value = it
            _error.value = null
            DebugLog.i("погода (${it.source}): ${it.temp}°, ${it.sky.text}")
        }.onFailure {
            _error.value = it.message ?: it.javaClass.simpleName
            DebugLog.e("погода не загрузилась", it)
        }
        return result.isSuccess
    }

    /** Поиск города по названию (геокодер Open-Meteo, бесплатно). */
    suspend fun searchCity(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val root = json.parseToJsonElement(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=ru&format=json")).jsonObject
        root["results"]?.jsonArray?.map { el ->
            val o = el.jsonObject
            Place(
                name = o.str("name") ?: query,
                region = listOfNotNull(o.str("admin1"), o.str("country")).joinToString(", "),
                lat = o["latitude"]!!.jsonPrimitive.doubleOrNull!!,
                lon = o["longitude"]!!.jsonPrimitive.doubleOrNull!!,
            )
        }.orEmpty()
    }

    private fun openMeteo(lat: Double, lon: Double): WeatherNow {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day&timezone=auto"
        val cur = json.parseToJsonElement(get(url)).jsonObject["current"]!!.jsonObject
        return WeatherNow(
            temp = cur["temperature_2m"]!!.jsonPrimitive.doubleOrNull!!.roundToInt(),
            feelsLike = cur["apparent_temperature"]?.jsonPrimitive?.doubleOrNull?.roundToInt(),
            sky = wmo(cur["weather_code"]?.jsonPrimitive?.intOrNull ?: 0),
            isDay = (cur["is_day"]?.jsonPrimitive?.intOrNull ?: 1) == 1,
            source = "Open-Meteo",
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** API Яндекс Погоды: ключ передаётся заголовком X-Yandex-Weather-Key. */
    private fun yandex(lat: Double, lon: Double, key: String): WeatherNow {
        val body = get(
            "https://api.weather.yandex.ru/v2/forecast?lat=$lat&lon=$lon&lang=ru_RU&limit=1&hours=false",
            mapOf("X-Yandex-Weather-Key" to key),
        )
        val fact = json.parseToJsonElement(body).jsonObject["fact"]!!.jsonObject
        return WeatherNow(
            temp = fact["temp"]!!.jsonPrimitive.doubleOrNull!!.roundToInt(),
            feelsLike = fact["feels_like"]?.jsonPrimitive?.doubleOrNull?.roundToInt(),
            sky = yandexSky(fact.str("condition") ?: "clear"),
            isDay = fact.str("daytime") != "n",
            source = "Яндекс Погода",
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "PixelTv/1.0")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val msg = c.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                error(if (code == 403 || code == 401) "ключ не подошёл (HTTP $code)" else "HTTP $code ${msg ?: ""}")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    /** Коды погоды WMO (Open-Meteo). */
    private fun wmo(code: Int): Sky = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        3 -> Sky.OVERCAST
        45, 48 -> Sky.FOG
        51, 53, 55, 56, 57 -> Sky.DRIZZLE
        61, 63, 65, 80, 81, 82 -> Sky.RAIN
        66, 67 -> Sky.SLEET
        71, 73, 75, 77, 85, 86 -> Sky.SNOW
        95, 96, 99 -> Sky.THUNDER
        else -> Sky.CLOUDY
    }

    private fun yandexSky(c: String): Sky = when {
        c == "clear" -> Sky.CLEAR
        c == "partly-cloudy" -> Sky.PARTLY
        c == "cloudy" -> Sky.CLOUDY
        c == "overcast" -> Sky.OVERCAST
        "thunder" in c -> Sky.THUNDER
        c == "wet-snow" -> Sky.SLEET
        "snow" in c || c == "hail" -> Sky.SNOW
        c == "drizzle" || c == "light-rain" -> Sky.DRIZZLE
        "rain" in c || "showers" in c -> Sky.RAIN
        else -> Sky.CLOUDY
    }
}

/** Пиксельные иконки 9×9 для состояний погоды: 'y' — солнце, '#' — облако/осадки, '+' — светлое. */
fun skyIcon(sky: Sky, day: Boolean): List<String> = when (sky) {
    Sky.CLEAR -> if (day) listOf(
        "y...y...y", ".y..y..y.", "...yyy...", "..yyyyy..", "yyyyyyyyy", "..yyyyy..", "...yyy...", ".y..y..y.", "y...y...y",
    ) else listOf(
        "...yyy...", "..yy.....", ".yy......", ".yy......", ".yy......", ".yy......", "..yy.....", "...yyy...", ".........",
    )
    Sky.PARTLY -> listOf(
        "y.y.y....", ".yyy.....", "yyyyy....", ".yyy###..", "y.##+++#.", ".#++++++#", "#+++++++#", ".#######.", ".........",
    )
    Sky.CLOUDY, Sky.OVERCAST -> listOf(
        ".........", "...####..", "..#++++#.", ".#++++++#", "#+++++++#", "#++++++++", ".########", ".........", ".........",
    )
    Sky.FOG -> listOf(
        ".........", "#########", ".........", ".#######.", ".........", "#########", ".........", "..#####..", ".........",
    )
    Sky.DRIZZLE, Sky.RAIN -> listOf(
        "...####..", "..#++++#.", ".#++++++#", "#++++++++", ".########", ".........", "..+..+..+", ".+..+..+.", "+..+..+..",
    )
    Sky.SNOW, Sky.SLEET -> listOf(
        "...####..", "..#++++#.", ".#++++++#", "#++++++++", ".########", ".........", ".+...+...", "+++.+++.+", ".+...+...",
    )
    Sky.THUNDER -> listOf(
        "...####..", "..#++++#.", ".#++++++#", "#++++++++", ".########", "....yy...", "...yy....", "....yy...", "...y.....",
    )
}

package com.pixelden.launcher.weather

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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Праздники и памятные даты по календарю — работает без интернета. Ключ — "ММ-ДД". */
object Holidays {
    private val fixed = mapOf(
        "01-01" to "Новый год", "01-07" to "Рождество", "01-25" to "Татьянин день",
        "02-14" to "День святого Валентина", "02-23" to "День защитника Отечества",
        "03-01" to "День кошек в России", "03-08" to "Международный женский день",
        "04-01" to "День смеха", "04-12" to "День космонавтики", "04-22" to "День Земли",
        "05-01" to "Праздник весны и труда", "05-09" to "День Победы", "06-01" to "День защиты детей",
        "06-12" to "День России", "07-08" to "День семьи, любви и верности",
        "08-08" to "Всемирный день кошек", "09-01" to "День знаний", "10-05" to "День учителя",
        "10-31" to "Хэллоуин", "11-04" to "День народного единства", "12-31" to "Канун Нового года",
    )

    /** Название праздника сегодня или null. День программиста — 256-й день года. */
    fun today(c: Calendar = Calendar.getInstance()): String? {
        if (c.get(Calendar.DAY_OF_YEAR) == 256) return "День программиста"
        val key = "%02d-%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return fixed[key]
    }

    /** Ближайший праздник после сегодняшнего дня: название и через сколько дней. */
    fun next(from: Calendar = Calendar.getInstance()): Pair<String, Int>? {
        val c = from.clone() as Calendar
        for (d in 1..366) {
            c.add(Calendar.DAY_OF_YEAR, 1)
            today(c)?.let { return it to d }
        }
        return null
    }

    fun line(name: String): String = when (name) {
        "Новый год" -> "С Новым годом! Загадал желание?"
        "Канун Нового года" -> "Сегодня Новый год! Оливье готов?"
        "Хэллоуин" -> "Бу! С Хэллоуином!"
        "День смеха" -> "С Днём смеха! У тебя спина белая. Шучу."
        "День космонавтики" -> "Поехали! С Днём космонавтики!"
        "День кошек в России", "Всемирный день кошек" -> "Сегодня $name! Погладь кота. Можно меня."
        "День программиста" -> "С Днём программиста! Я — 256 пикселей любви."
        else -> "Сегодня $name!"
    }
}

/** Короткие факты для болтовни. */
object Facts {
    val all = listOf(
        "Кошки спят 12–16 часов в сутки. Я в деле.",
        "У кошки 32 мышцы в каждом ухе.",
        "Кошачье мурлыканье — около 25 герц. Это успокаивает.",
        "Лисы слышат, как мышь пищит в 40 метрах.",
        "Лисий хвост греет им нос во сне, как шарф.",
        "Кошки не чувствуют сладкий вкус.",
        "Усы помогают кошке понять, пролезет ли она в щель.",
        "Первая телепередача в СССР вышла в 1931 году.",
        "Первый пульт ДУ для ТВ был на проводе. 1950 год.",
        "Телевизор 4K — это больше 8 миллионов пикселей.",
        "Пиксель — сокращение от «picture element».",
        "Первый в мире фильм длился меньше минуты.",
        "Самый длинный фильм идёт больше 35 дней.",
        "Звёздные войны чуть не назвали «Звёздные войны: Приключения Люка Старкиллера».",
        "Pac-Man задумывали как игру про еду, а не про призраков.",
        "Тетрис придумали в Москве в 1984 году.",
        "Марио сначала звали Jumpman.",
        "На Луне есть следы, которые не исчезнут миллионы лет.",
        "Свет от Солнца летит до Земли примерно 8 минут.",
        "На Венере день длиннее года.",
        "У осьминога три сердца.",
        "Мёд не портится тысячелетиями.",
        "Бананы — ягоды, а клубника — нет.",
        "Улитки могут спать до трёх лет.",
        "Самая короткая война длилась 38 минут.",
        "Глаз страуса больше его мозга.",
        "У жирафа такой же язык по цвету, как черника.",
        "В Японии есть остров, где кошек больше, чем людей.",
        "Кошка может прыгнуть в пять раз выше своего роста.",
        "Лисы — единственные собачьи, которые лазают по деревьям. Некоторые.",
        "Первый смайлик :-) напечатали в 1982 году.",
        "Wi-Fi ничего не расшифровывается — это просто название.",
        "Первая компьютерная мышь была деревянной.",
        "Android — это имя робота-человека.",
        "Попкорн взрывается при 180 градусах.",
        "Сериалы стали «сериалами», потому что их показывали сериями в журналах.",
        "Мультфильм «Ёжик в тумане» признан лучшим мультфильмом всех времён.",
        "«Ну, погоди!» снимали почти 50 лет.",
        "Чебурашку в Японии обожают больше, чем у нас.",
        "Кот Матроскин говорит голосом Олега Табакова.",
    )
}

/** Свежие заголовки из RSS Ленты.ру (бесплатно, без ключа). Обновляются раз в час. */
class NewsRepository(private val scope: CoroutineScope) {
    private val _titles = MutableStateFlow<List<String>>(emptyList())
    val titles: StateFlow<List<String>> = _titles
    private var loop: Job? = null

    fun start() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                // при ошибке — повтор через 2 минуты, иначе раз в час
                delay(if (refresh()) 60 * 60_000L else 2 * 60_000L)
            }
        }
    }

    suspend fun refresh(): Boolean {
        val r = withContext(Dispatchers.IO) { runCatching { fetch("https://lenta.ru/rss/news") } }
        r.onSuccess { list ->
            _titles.value = list
            DebugLog.i("новости: ${list.size} заголовков")
        }.onFailure { DebugLog.e("новости не загрузились", it) }
        return r.isSuccess
    }

    private fun fetch(url: String): List<String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "PixelTv/1.0")
        try {
            val xml = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // Простой разбор RSS: заголовки внутри <item>, с CDATA или без.
            return Regex("<item>[\\s\\S]*?<title>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:]]>)?</title>")
                .findAll(xml)
                .map { it.groupValues[1].trim().replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'") }
                .filter { it.isNotBlank() }
                .take(30)
                .toList()
        } finally {
            c.disconnect()
        }
    }
}

data class Rate(val code: String, val value: Double, val previous: Double)

/** Курсы ЦБ РФ (зеркало cbr-xml-daily.ru, без ключа). Обновляются раз в 3 часа. */
class RatesRepository(private val scope: CoroutineScope) {
    private val _rates = MutableStateFlow<List<Rate>>(emptyList())
    val rates: StateFlow<List<Rate>> = _rates
    private var loop: Job? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                // при ошибке — повтор через 5 минут, иначе раз в 3 часа
                delay(if (refresh()) 3 * 3_600_000L else 5 * 60_000L)
            }
        }
    }

    suspend fun refresh(): Boolean {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://www.cbr-xml-daily.ru/daily_json.js").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "PixelTv/1.0")
                val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                val valute = json.parseToJsonElement(body).jsonObject["Valute"]!!.jsonObject
                listOf("USD", "EUR", "CNY").mapNotNull { code ->
                    val o = valute[code]?.jsonObject ?: return@mapNotNull null
                    val nominal = o["Nominal"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                    Rate(code, o["Value"]!!.jsonPrimitive.double / nominal, o["Previous"]!!.jsonPrimitive.double / nominal)
                }
            }
        }
        r.onSuccess { _rates.value = it; DebugLog.i("курсы: " + it.joinToString { "${it.code} ${"%.2f".format(it.value)}" }) }
            .onFailure { DebugLog.e("курсы не загрузились", it) }
        return r.isSuccess
    }
}

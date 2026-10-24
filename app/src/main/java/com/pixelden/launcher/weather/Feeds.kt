package com.pixelden.launcher.weather

import com.pixelden.launcher.i18n.I18n
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

/**
 * Праздники и памятные даты по календарю — работает без интернета. Ключ — "ММ-ДД".
 * Набор свой для каждого языка: российские даты англоязычному зрителю ни к чему.
 */
object Holidays {
    private val RU = mapOf(
        "01-01" to "Новый год", "01-07" to "Рождество", "01-25" to "Татьянин день",
        "02-14" to "День святого Валентина", "02-23" to "День защитника Отечества",
        "03-01" to "День кошек в России", "03-08" to "Международный женский день",
        "04-01" to "День смеха", "04-12" to "День космонавтики", "04-22" to "День Земли",
        "05-01" to "Праздник весны и труда", "05-09" to "День Победы", "06-01" to "День защиты детей",
        "06-12" to "День России", "07-08" to "День семьи, любви и верности",
        "08-08" to "Всемирный день кошек", "09-01" to "День знаний", "10-05" to "День учителя",
        "10-31" to "Хэллоуин", "11-04" to "День народного единства", "12-31" to "Канун Нового года",
    )
    private val EN = mapOf(
        "01-01" to "New Year's Day", "02-14" to "Valentine's Day", "03-17" to "St. Patrick's Day",
        "04-01" to "April Fools' Day", "04-12" to "Yuri's Night", "04-22" to "Earth Day",
        "05-04" to "Star Wars Day", "08-08" to "International Cat Day", "10-31" to "Halloween",
        "12-24" to "Christmas Eve", "12-25" to "Christmas", "12-31" to "New Year's Eve",
    )

    private fun en() = I18n.lang != "ru"

    /** Название праздника сегодня или null. День программиста — 256-й день года. */
    fun today(c: Calendar = Calendar.getInstance()): String? {
        if (c.get(Calendar.DAY_OF_YEAR) == 256) return if (en()) "Programmer's Day" else "День программиста"
        val key = "%02d-%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return (if (en()) EN else RU)[key]
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

    fun line(name: String): String = if (en()) when (name) {
        "New Year's Day" -> "Happy New Year! Made a wish?"
        "New Year's Eve" -> "It's New Year's Eve! Snacks ready?"
        "Halloween" -> "Boo! Happy Halloween!"
        "April Fools' Day" -> "Happy April Fools'! Your shoelace is untied. Kidding."
        "Yuri's Night" -> "Let's go! Happy Yuri's Night — first human in space!"
        "Star Wars Day" -> "May the Fourth be with you!"
        "International Cat Day" -> "It's International Cat Day! Pet a cat. Me counts."
        "Programmer's Day" -> "Happy Programmer's Day! I'm 256 pixels of love."
        "Christmas" -> "Merry Christmas! Cozy movie time."
        else -> "Today is $name!"
    } else when (name) {
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

/** Короткие факты для болтовни — свой набор для каждого языка. */
object Facts {
    val all get() = if (I18n.lang == "ru") RU else EN

    private val RU = listOf(
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

    private val EN = listOf(
        "Cats sleep 12–16 hours a day. I'm on it.",
        "A cat has 32 muscles in each ear.",
        "A cat's purr is about 25 hertz. It's soothing.",
        "Foxes can hear a mouse squeak 40 meters away.",
        "A fox wraps its tail around its nose to keep warm, like a scarf.",
        "Cats can't taste sweetness.",
        "Whiskers tell a cat whether it fits through a gap.",
        "The first TV remote, in 1950, had a cable.",
        "A 4K TV has more than 8 million pixels.",
        "“Pixel” is short for “picture element”.",
        "The first films were shorter than a minute.",
        "Pac-Man was meant to be a game about eating.",
        "Tetris was invented in Moscow in 1984.",
        "Mario was first called Jumpman.",
        "Footprints on the Moon will last millions of years.",
        "Sunlight takes about 8 minutes to reach Earth.",
        "A day on Venus is longer than its year.",
        "An octopus has three hearts.",
        "Honey can stay edible for thousands of years.",
        "Bananas are berries; strawberries aren't.",
        "Snails can sleep for up to three years.",
        "The shortest war in history lasted 38 minutes.",
        "An ostrich's eye is bigger than its brain.",
        "Japan has islands with more cats than people.",
        "A cat can jump five times its own height.",
        "Some foxes can climb trees.",
        "The first smiley :-) was typed in 1982.",
        "Wi-Fi doesn't stand for anything; it's just a name.",
        "The first computer mouse was made of wood.",
        "Popcorn pops at about 180 °C.",
        "Halloween pumpkins used to be turnips.",
        "A group of cats is called a clowder.",
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

    fun stop() { loop?.cancel(); loop = null; _titles.value = emptyList() }

    suspend fun refresh(): Boolean {
        val url = NewsSources.url(com.pixelden.launcher.PixelDenApp.instance.store.current)
        val r = withContext(Dispatchers.IO) { runCatching { fetch(url) } }
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
            // Простой разбор RSS и Atom: заголовки внутри <item> или <entry>, с CDATA или без.
            return Regex("<(?:item|entry)[\\s>][\\s\\S]*?<title[^>]*>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:]]>)?</title>")
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

/** Сколько единиц валюты счёта стоит 1 [code]. */
data class Rate(val code: String, val value: Double)

/**
 * Курсы валют (open.er-api.com, без ключа): любая валюта к любой. Обновляются раз в 3 часа.
 * Что показывать — в настройках виджета: валюта счёта и до трёх валют.
 */
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
        val cfg = com.pixelden.launcher.PixelDenApp.instance.store.current
        val base = Currencies.base(cfg)
        val codes = Currencies.shown(cfg)
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://open.er-api.com/v6/latest/$base").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "PixelTv/1.0")
                val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                val rates = json.parseToJsonElement(body).jsonObject["rates"]!!.jsonObject
                // API отдаёт «сколько [code] за 1 base» — переворачиваем: сколько base стоит 1 [code].
                codes.mapNotNull { code -> rates[code]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }?.let { Rate(code, 1 / it) } }
            }
        }
        r.onSuccess { _rates.value = it; DebugLog.i("курсы к $base: " + it.joinToString { "${it.code} ${"%.2f".format(it.value)}" }) }
            .onFailure { DebugLog.e("курсы не загрузились", it) }
        return r.isSuccess
    }
}

/** Валюты для виджета курсов. */
object Currencies {
    val ALL = listOf("RUB", "USD", "EUR", "CNY", "GBP", "JPY", "CHF", "TRY", "KZT", "BYN", "UAH", "INR", "AED", "CAD", "AUD", "PLN")
    const val MAX_SHOWN = 3

    /** Валюта счёта: выбранная или по языку (рубль для русского, доллар для остальных). */
    fun base(cfg: com.pixelden.launcher.data.LauncherConfig) = cfg.ratesBase.ifBlank { if (I18n.lang == "ru") "RUB" else "USD" }

    fun shown(cfg: com.pixelden.launcher.data.LauncherConfig): List<String> {
        val base = base(cfg)
        return cfg.rateCodes.filter { it != base }.ifEmpty { if (base == "RUB") listOf("USD", "EUR") else listOf("EUR", "GBP").filter { it != base } }
    }
}

/** Откуда маскот берёт новости: готовые RSS-ленты или свой адрес. */
object NewsSources {
    data class Source(val id: String, val name: String, val url: String, val lang: String)

    val ALL = listOf(
        Source("lenta", "Лента.ру", "https://lenta.ru/rss/news", "ru"),
        Source("ria", "РИА Новости", "https://ria.ru/export/rss2/archive/index.xml", "ru"),
        Source("tass", "ТАСС", "https://tass.ru/rss/v2.xml", "ru"),
        Source("bbc_ru", "BBC News Русская служба", "https://feeds.bbci.co.uk/russian/rss.xml", "ru"),
        Source("habr", "Хабр", "https://habr.com/ru/rss/articles/", "ru"),
        Source("bbc", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "en"),
        Source("guardian", "The Guardian", "https://www.theguardian.com/world/rss", "en"),
        Source("verge", "The Verge", "https://www.theverge.com/rss/index.xml", "en"),
    )

    /** auto — по языку интерфейса; custom — свой адрес из настроек. */
    fun url(cfg: com.pixelden.launcher.data.LauncherConfig): String = when (cfg.newsSource) {
        "custom" -> cfg.newsCustomUrl.ifBlank { ALL.first().url }
        "auto" -> if (I18n.lang == "ru") ALL.first { it.id == "lenta" }.url else ALL.first { it.id == "bbc" }.url
        else -> ALL.firstOrNull { it.id == cfg.newsSource }?.url ?: ALL.first().url
    }
}

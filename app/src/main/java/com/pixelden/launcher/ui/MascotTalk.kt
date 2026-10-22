package com.pixelden.launcher.ui

import com.pixelden.launcher.weather.Sky
import com.pixelden.launcher.weather.WeatherNow
import java.util.Calendar
import kotlin.random.Random

/**
 * Реплики маскота. Подбираются по времени суток, дню недели, погоде, приложению в фокусе и виду маскота.
 * Повторы гасятся: последние реплики не выпадают снова подряд.
 */
object MascotTalk {

    private val recent = ArrayDeque<String>()

    private fun pick(pool: List<String>): String {
        val fresh = pool.filter { it !in recent }.ifEmpty { pool }
        val s = fresh[Random.nextInt(fresh.size)]
        recent.addLast(s)
        while (recent.size > 12) recent.removeFirst()
        return s
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun dow() = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    fun greeting(mascotId: String): String = pick(
        when (hour()) {
            in 5..11 -> listOf("Доброе утро!", "Утро! Кофе уже?", "Проснулся? Я тоже… почти.", "Утречко!")
            in 12..17 -> listOf("Добрый день!", "Привет-привет!", "Что смотрим?", "О, ты пришёл!")
            in 18..22 -> listOf("Добрый вечер!", "Вечер — время сериалов.", "Устроимся поудобнее?", "Наконец-то вечер!")
            else -> listOf("Не спится?", "Ночной киносеанс?", "Тсс, все спят…", "Полуночник, привет.")
        } + voice(mascotId),
    )

    /** Приветствие с учётом праздника. */
    fun greetingFor(mascotId: String, holidays: Boolean): String {
        val h = if (holidays) com.pixelden.launcher.weather.Holidays.today() else null
        return if (h != null) com.pixelden.launcher.weather.Holidays.line(h) else greeting(mascotId)
    }

    /**
     * «Живая» реплика: болтовня, факт, свежая новость или праздник — что включено в настройках.
     * Новости обрезаются, чтобы влезть в облачко.
     */
    fun live(
        mascotId: String,
        weather: WeatherNow?,
        facts: Boolean,
        holidays: Boolean,
        news: List<String>,
    ): String {
        val holiday = if (holidays) com.pixelden.launcher.weather.Holidays.today() else null
        val roll = Random.nextInt(100)
        return when {
            holiday != null && roll < 15 -> com.pixelden.launcher.weather.Holidays.line(holiday)
            news.isNotEmpty() && roll < 40 -> "Новости: " + pick(news).let { if (it.length > 90) it.take(88).trimEnd() + "…" else it }
            facts && roll < 65 -> "А знаешь? " + pick(com.pixelden.launcher.weather.Facts.all)
            else -> chatter(mascotId, weather)
        }
    }

    fun welcomeBack(): String = pick(listOf("О, ты вернулся!", "Я почти уснул…", "Уф, я задремал.", "А я ждал!", "Снова здравствуй!"))

    fun afterApp(): String = pick(listOf("Как кино?", "С возвращением!", "Ну как оно?", "Что дальше?", "Я посторожил экран."))

    /** Случайная болтовня, когда ничего не происходит. */
    fun chatter(mascotId: String, weather: WeatherNow?): String {
        val h = hour()
        val pool = mutableListOf(
            "Я тут, если что.", "Пиксель к пикселю — вот и я.", "Может, что-нибудь новенькое?",
            "Ещё одну серию?", "Я посторожу пульт.", "Хвост — моя гордость.", "Смотри, как я умею!",
            "Тут уютно.", "Интересно, что сегодня в кино?", "Пульт не потерял?", "Я считаю пиксели. Их много.",
            "Моргни, если слышишь меня.", "Обожаю этот экран.",
        )
        pool += voice(mascotId)
        when {
            h >= 1 && h < 5 -> pool += listOf("Уже поздно… может, спать?", "Глаза слипаются…", "Ещё пять минуточек и спать.")
            h in 6..9 -> pool += listOf("Утро — время новостей.", "Завтракать будем?")
            h in 18..22 -> pool += listOf("Вечер — лучшее время.", "Пледик и кино?")
        }
        when (dow()) {
            Calendar.FRIDAY -> if (h >= 17) pool += listOf("Пятница! Марафон?", "Пятничный вечер — святое.")
            Calendar.MONDAY -> if (h < 13) pool += listOf("Понедельник… держимся.", "Новая неделя, ура?")
            Calendar.SATURDAY, Calendar.SUNDAY -> pool += listOf("Выходной — можно всё.", "Выходные! Никуда не спешим.")
        }
        weather?.let { pool += weatherLines(it) }
        return pick(pool)
    }

    private fun weatherLines(w: WeatherNow): List<String> {
        val t = w.temp
        val lines = mutableListOf<String>()
        when (w.sky) {
            Sky.CLEAR -> lines += if (w.isDay) listOf("Солнце! Может, прогуляться?", "За окном ясно — красота.") else listOf("Звёздная ночь сегодня.")
            Sky.PARTLY, Sky.CLOUDY -> lines += listOf("Облачка плывут, а мы смотрим кино.")
            Sky.OVERCAST -> lines += listOf("Пасмурно. Самое время для уюта.")
            Sky.FOG -> lines += listOf("Туман — как в хорроре.", "Ничего не видно… кроме экрана.")
            Sky.DRIZZLE, Sky.RAIN -> lines += listOf("Дождь — идеальная погода для кино.", "Слышишь дождь? Уютно.")
            Sky.SNOW, Sky.SLEET -> lines += listOf("Снег! Где мой шарф?", "Снежок пошёл…")
            Sky.THUNDER -> lines += listOf("Гроза… я под одеялом.", "Гром! Я не боюсь. Почти.")
        }
        if (t <= -10) lines += "Бррр, $t°! Одевайся теплее."
        if (t >= 25) lines += "Жарко: +$t°…"
        return lines
    }

    /** Комментарий к приложению в фокусе (или null — тогда просто называем приложение). */
    fun aboutApp(pkg: String): String? {
        val p = pkg.lowercase()
        val pool = when {
            "youtube" in p || "smarttube" in p || "tizentube" in p -> listOf("Опять котики на YouTube?", "Я тоже хочу в видео!", "Только не реклама…")
            "kinopoisk" in p -> listOf("Что-нибудь из классики?", "Кинопоиск! Выбираем фильм.")
            "zona" in p -> listOf("Попкорн готов?", "Кино-ночь?")
            "vlc" in p || "mediaexplorer" in p -> listOf("Свои фильмы — это уважение.", "Что у нас на флешке?")
            "steamlink" in p -> listOf("Поиграем? Я болею за тебя!", "Геймпад заряжен?")
            "s0und" in p || "twitch" in p || "twire" in p -> listOf("Стримы — моё всё.", "Кто сегодня в эфире?")
            "vending" in p -> listOf("Новое приложение? Интересно!")
            "aerialviews" in p -> listOf("Красивые виды!")
            "gallery" in p -> listOf("Покажешь фоточки?")
            else -> return null
        }
        return pick(pool)
    }

    private fun voice(id: String): List<String> = when (id) {
        "ember" -> listOf("Фыр!", "Лисы тоже любят кино.")
        "pinch" -> listOf("Клац-клац!", "Я кирпичик, но милый.")
        else -> listOf("Мяу.", "Мур-р-р…", "Мяу? Мяу!")
    }
}

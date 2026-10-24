package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.I18n
import com.pixelden.launcher.weather.Sky
import com.pixelden.launcher.weather.WeatherNow
import java.util.Calendar
import kotlin.random.Random

/**
 * Реплики маскота. Подбираются по времени суток, дню недели, погоде, приложению в фокусе и виду маскота.
 * Повторы гасятся: последние реплики не выпадают снова подряд.
 * Для каждого языка — свой набор: это живая речь, а не перевод фраз один в один.
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

    /** Выбор набора по языку интерфейса. */
    private fun <T> L(ru: T, en: T): T = if (I18n.lang == "ru") ru else en

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun dow() = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    fun greeting(mascotId: String): String = pick(
        when (hour()) {
            in 5..11 -> L(listOf("Доброе утро!", "Утро! Кофе уже?", "Проснулся? Я тоже… почти.", "Утречко!"),
                listOf("Good morning!", "Morning! Coffee yet?", "Awake? Me too… almost.", "Rise and shine!"))
            in 12..17 -> L(listOf("Добрый день!", "Привет-привет!", "Что смотрим?", "О, ты пришёл!"),
                listOf("Good afternoon!", "Hey hey!", "What are we watching?", "Oh, you're here!"))
            in 18..22 -> L(listOf("Добрый вечер!", "Вечер — время сериалов.", "Устроимся поудобнее?", "Наконец-то вечер!"),
                listOf("Good evening!", "Evening means series time.", "Shall we get cozy?", "Finally, evening!"))
            else -> L(listOf("Не спится?", "Ночной киносеанс?", "Тсс, все спят…", "Полуночник, привет."),
                listOf("Can't sleep?", "Late-night movie?", "Shh, everyone's asleep…", "Hello, night owl."))
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
            news.isNotEmpty() && roll < 40 -> L("Новости: ", "News: ") + pick(news).let { if (it.length > 90) it.take(88).trimEnd() + "…" else it }
            facts && roll < 65 -> L("А знаешь? ", "Did you know? ") + pick(com.pixelden.launcher.weather.Facts.all)
            else -> chatter(mascotId, weather)
        }
    }

    fun welcomeBack(): String = pick(L(
        listOf("О, ты вернулся!", "Я почти уснул…", "Уф, я задремал.", "А я ждал!", "Снова здравствуй!"),
        listOf("Oh, you're back!", "I almost fell asleep…", "Phew, I dozed off.", "I was waiting!", "Hello again!"),
    ))

    fun afterApp(): String = pick(L(
        listOf("Как кино?", "С возвращением!", "Ну как оно?", "Что дальше?", "Я посторожил экран."),
        listOf("How was it?", "Welcome back!", "So, how was it?", "What's next?", "I guarded the screen."),
    ))

    /** Случайная болтовня, когда ничего не происходит. */
    fun chatter(mascotId: String, weather: WeatherNow?): String {
        val h = hour()
        val pool = L(
            mutableListOf(
                "Я тут, если что.", "Пиксель к пикселю — вот и я.", "Может, что-нибудь новенькое?",
                "Ещё одну серию?", "Я посторожу пульт.", "Хвост — моя гордость.", "Смотри, как я умею!",
                "Тут уютно.", "Интересно, что сегодня в кино?", "Пульт не потерял?", "Я считаю пиксели. Их много.",
                "Моргни, если слышишь меня.", "Обожаю этот экран.",
            ),
            mutableListOf(
                "I'm here if you need me.", "Pixel by pixel, here I am.", "Something new, maybe?",
                "One more episode?", "I'll guard the remote.", "My tail is my pride.", "Look what I can do!",
                "It's cozy here.", "Wonder what's on tonight?", "Lost the remote again?", "I'm counting pixels. There are many.",
                "Blink if you can hear me.", "I love this screen.",
            ),
        )
        pool += voice(mascotId)
        when {
            h in 1..4 -> pool += L(listOf("Уже поздно… может, спать?", "Глаза слипаются…", "Ещё пять минуточек и спать."),
                listOf("It's late… bedtime?", "My eyes are closing…", "Five more minutes, then bed."))
            h in 6..9 -> pool += L(listOf("Утро — время новостей.", "Завтракать будем?"), listOf("Morning is news time.", "Breakfast?"))
            h in 18..22 -> pool += L(listOf("Вечер — лучшее время.", "Пледик и кино?"), listOf("Evening is the best time.", "Blanket and a movie?"))
        }
        when (dow()) {
            Calendar.FRIDAY -> if (h >= 17) pool += L(listOf("Пятница! Марафон?", "Пятничный вечер — святое."), listOf("Friday! Marathon?", "Friday night is sacred."))
            Calendar.MONDAY -> if (h < 13) pool += L(listOf("Понедельник… держимся.", "Новая неделя, ура?"), listOf("Monday… hang in there.", "New week, yay?"))
            Calendar.SATURDAY, Calendar.SUNDAY -> pool += L(listOf("Выходной — можно всё.", "Выходные! Никуда не спешим."), listOf("Weekend: anything goes.", "Weekend! No rush."))
        }
        weather?.let { pool += weatherLines(it) }
        return pick(pool)
    }

    private fun weatherLines(w: WeatherNow): List<String> {
        val t = w.temp
        val lines = mutableListOf<String>()
        when (w.sky) {
            Sky.CLEAR -> lines += if (w.isDay) L(listOf("Солнце! Может, прогуляться?", "За окном ясно — красота."), listOf("Sunshine! Maybe a walk?", "Clear skies out there. Lovely."))
                else L(listOf("Звёздная ночь сегодня."), listOf("Starry night tonight."))
            Sky.PARTLY, Sky.CLOUDY -> lines += L(listOf("Облачка плывут, а мы смотрим кино."), listOf("Clouds drift by, we watch movies."))
            Sky.OVERCAST -> lines += L(listOf("Пасмурно. Самое время для уюта."), listOf("Overcast. Perfect for staying in."))
            Sky.FOG -> lines += L(listOf("Туман — как в хорроре.", "Ничего не видно… кроме экрана."), listOf("Foggy, like a horror movie.", "Can't see a thing… except the screen."))
            Sky.DRIZZLE, Sky.RAIN -> lines += L(listOf("Дождь — идеальная погода для кино.", "Слышишь дождь? Уютно."), listOf("Rain is perfect movie weather.", "Hear the rain? Cozy."))
            Sky.SNOW, Sky.SLEET -> lines += L(listOf("Снег! Где мой шарф?", "Снежок пошёл…"), listOf("Snow! Where's my scarf?", "It's snowing…"))
            Sky.THUNDER -> lines += L(listOf("Гроза… я под одеялом.", "Гром! Я не боюсь. Почти."), listOf("Thunder… I'm under the blanket.", "Thunder! I'm not scared. Mostly."))
        }
        if (t <= -10) lines += L("Бррр, $t°! Одевайся теплее.", "Brrr, $t°! Dress warm.")
        if (t >= 25) lines += L("Жарко: +$t°…", "Hot: +$t°…")
        return lines
    }

    /** Комментарий к приложению в фокусе (или null — тогда просто называем приложение). */
    fun aboutApp(pkg: String): String? {
        val p = pkg.lowercase()
        val pool = when {
            "youtube" in p || "smarttube" in p || "tizentube" in p -> L(listOf("Опять котики на YouTube?", "Я тоже хочу в видео!", "Только не реклама…"),
                listOf("Cat videos again?", "I want to be in a video too!", "Please, no ads…"))
            "kinopoisk" in p -> L(listOf("Что-нибудь из классики?", "Кинопоиск! Выбираем фильм."), listOf("Something classic?", "Kinopoisk! Let's pick a movie."))
            "zona" in p -> L(listOf("Попкорн готов?", "Кино-ночь?"), listOf("Popcorn ready?", "Movie night?"))
            "vlc" in p || "mediaexplorer" in p -> L(listOf("Свои фильмы — это уважение.", "Что у нас на флешке?"), listOf("Your own movies. Respect.", "What's on the USB stick?"))
            "steamlink" in p -> L(listOf("Поиграем? Я болею за тебя!", "Геймпад заряжен?"), listOf("Gaming? I'm cheering for you!", "Is the gamepad charged?"))
            "s0und" in p || "twitch" in p || "twire" in p -> L(listOf("Стримы — моё всё.", "Кто сегодня в эфире?"), listOf("Streams are my thing.", "Who's live today?"))
            "vending" in p -> L(listOf("Новое приложение? Интересно!"), listOf("A new app? Interesting!"))
            "aerialviews" in p -> L(listOf("Красивые виды!"), listOf("Beautiful views!"))
            "gallery" in p -> L(listOf("Покажешь фоточки?"), listOf("Show me some photos?"))
            else -> return null
        }
        return pick(pool)
    }

    private fun voice(id: String): List<String> = when (id) {
        "ember" -> L(listOf("Фыр!", "Лисы тоже любят кино."), listOf("Yip!", "Foxes love movies too."))
        "pinch" -> L(listOf("Клац-клац!", "Я кирпичик, но милый."), listOf("Click-clack!", "I'm a brick, but a cute one."))
        else -> L(listOf("Мяу.", "Мур-р-р…", "Мяу? Мяу!"), listOf("Meow.", "Purrr…", "Meow? Meow!"))
    }
}

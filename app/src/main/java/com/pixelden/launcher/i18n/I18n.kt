package com.pixelden.launcher.i18n

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Перевод интерфейса. Исходный язык — русский: русская фраза и есть ключ, переводы лежат
 * в assets/i18n/<язык>.json ({"русский текст": "перевод"}). Вставки — {0}, {1}…
 * Новый язык = новый json рядом и строчка в [LANGUAGES].
 */
object I18n {
    /** Языки интерфейса: код → название на самом языке. */
    val LANGUAGES = listOf("auto" to "Auto", "ru" to "Русский", "en" to "English")

    @Volatile var lang: String = "ru"
        private set
    @Volatile private var dict: Map<String, String> = emptyMap()

    /** Вызывается при старте до первого tr(): [setting] — значение из настроек (auto/ru/en). */
    fun init(context: Context, setting: String) {
        lang = resolve(setting)
        dict = if (lang == "ru") emptyMap() else runCatching {
            val text = context.assets.open("i18n/$lang.json").bufferedReader().use { it.readText() }
            (Json.parseToJsonElement(text) as JsonObject).mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    fun resolve(setting: String): String = when (setting) {
        "ru", "en" -> setting
        else -> if (Locale.getDefault().language == "ru") "ru" else "en"
    }

    fun translate(key: String, args: Array<out Any?>): String {
        var s = dict[key] ?: key
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }

    /** Словарь целиком — для веб-панели. */
    fun dictionary(): Map<String, String> = dict
}

/** Фраза на языке интерфейса. Ключ — русский текст. */
fun tr(key: String, vararg args: Any?): String = I18n.translate(key, args)

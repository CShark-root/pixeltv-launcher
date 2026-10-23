package com.pixelden.launcher.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.scenario.DebugLog
import java.io.File

/** Что можно отключить: только из этого списка. Сеть, звук, HDMI, пульт, Play и сервисы Google сюда не входят. */
data class BloatItem(val pkg: String, val name: String, val group: String, val recommended: Boolean = true)

object TvCleanup {

    val CATALOG = listOf(
        BloatItem("com.mitv.tvhome.atv", "PatchWall — главный экран Xiaomi", "Xiaomi"),
        BloatItem("com.mitv.tvhome.michannel", "Каналы PatchWall", "Xiaomi"),
        BloatItem("com.mitv.tvhome.mitvplus", "Mi TV+ (реклама каналов)", "Xiaomi"),
        BloatItem("com.miui.tv.analytics", "Аналитика Xiaomi", "Xiaomi"),
        BloatItem("com.xiaomi.statistic", "Статистика Xiaomi", "Xiaomi"),
        BloatItem("android.autoinstalls.config.xiaomi.amelie", "Автоустановка рекламных приложений", "Xiaomi"),
        BloatItem("com.xiaomi.android.tvsetup.partnercustomizer", "Настройки партнёров (падает)", "Xiaomi"),
        BloatItem("com.xiaomi.floatingframe", "Плавающее окно", "Xiaomi"),
        BloatItem("com.xiaomi.mimusic2", "Mi Music", "Xiaomi"),
        BloatItem("com.mitv.dream", "Заставка Xiaomi", "Xiaomi"),
        BloatItem("com.xiaomi.amazon.alexa", "Alexa", "Xiaomi"),
        BloatItem("com.xm.webcontent", "Веб-витрина Xiaomi", "Xiaomi"),
        BloatItem("fusion.android.tv.demo", "Демо-режим", "Xiaomi"),
        BloatItem("com.mitv.gallery", "Галерея Xiaomi", "Xiaomi", recommended = false),
        BloatItem("com.xiaomi.mitv.smartshare", "Трансляция с телефона Xiaomi", "Xiaomi", recommended = false),
        BloatItem("com.xiaomi.mitv.updateservice", "Обновления прошивки", "Xiaomi", recommended = false),
        BloatItem("com.google.android.tvlauncher", "Стандартный лаунчер Google", "Google"),
        BloatItem("com.google.android.leanbacklauncher", "Стандартный лаунчер Android TV", "Google"),
        BloatItem("com.google.android.tvrecommendations", "Рекомендации лаунчера Google", "Google"),
        BloatItem("com.google.android.leanbacklauncher.recommendations", "Рекомендации Android TV", "Google"),
        BloatItem("com.google.android.play.games", "Play Игры", "Google"),
        BloatItem("com.google.android.videos", "Play Фильмы", "Google"),
        BloatItem("com.google.android.youtube.tvmusic", "YouTube Music", "Google"),
        BloatItem("com.google.android.feedback", "Отправка отзывов Google", "Google"),
        BloatItem("com.google.android.tv.bugreportsender", "Отправка отчётов об ошибках", "Google"),
        BloatItem("com.google.android.youtube.tv", "YouTube (если хватает SmartTube)", "Google", recommended = false),
        BloatItem("com.google.android.katniss", "Голосовой поиск Google", "Google", recommended = false),
        BloatItem("com.google.android.speech.pumpkin", "Офлайн-распознавание речи", "Google", recommended = false),
        BloatItem("com.google.android.tv.assistant", "Google Ассистент", "Google", recommended = false),
        BloatItem("com.google.android.backdrop", "Заставка Google (Ambient)", "Google", recommended = false),
        BloatItem("ru.ntv.client.tv", "НТВ (предустановка)", "Предустановки"),
        BloatItem("ru.ok.androidtv", "ОК Видео (предустановка)", "Предустановки"),
        BloatItem("ru.tv1.android.tv", "Первый канал (предустановка)", "Предустановки"),
        BloatItem("com.vgtrk.androidtv", "Россия (предустановка)", "Предустановки"),
        BloatItem("com.ctcmediagroup.videomore", "СТС (предустановка)", "Предустановки"),
        BloatItem("ru.ivi.client", "Иви (предустановка)", "Предустановки"),
        BloatItem("ru.rt.video.app.tv", "Wink (предустановка)", "Предустановки"),
        BloatItem("tv.okko.androidtv", "Okko (предустановка)", "Предустановки"),
    )

    /** Лаунчеры Google отключаем, только если главный экран — уже PixelTv, иначе ТВ останется без экрана. */
    private val LAUNCHERS = setOf("com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.mitv.tvhome.atv")

    data class State(val item: BloatItem, val enabled: Boolean)

    /** Что из каталога есть на этом ТВ и в каком состоянии. */
    fun scan(context: Context): List<State> {
        val pm = context.packageManager
        return CATALOG.mapNotNull { item ->
            val ai = runCatching { pm.getApplicationInfo(item.pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull() ?: return@mapNotNull null
            State(item, ai.enabled)
        }
    }

    private fun isDefaultHome(context: Context): Boolean {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }

    /** Отключить пакет. Отключённые лаунчером запоминаем — их вернёт «Вернуть всё». */
    fun disable(context: Context, pkg: String): String {
        if (CATALOG.none { it.pkg == pkg }) error("$pkg нет в списке разрешённых")
        if (pkg in LAUNCHERS && !isDefaultHome(context)) error("сначала сделайте PixelTv главным экраном")
        val out = AdbClient(context).shell("pm disable-user --user 0 $pkg").trim()
        if ("disabled" !in out) error(out.ifBlank { "нет ответа" })
        PixelDenApp.instance.store.update { it.copy(cleanupDisabled = (it.cleanupDisabled + pkg).distinct()) }
        DebugLog.i("чистка: отключено $pkg")
        return out
    }

    fun enable(context: Context, pkg: String): String {
        val out = AdbClient(context).shell("pm enable $pkg").trim()
        PixelDenApp.instance.store.update { it.copy(cleanupDisabled = it.cleanupDisabled - pkg) }
        DebugLog.i("чистка: включено $pkg ($out)")
        return out
    }

    /** Отключить всё рекомендуемое из найденного на ТВ. Возвращает, сколько отключено. */
    fun disableRecommended(context: Context): Int {
        val todo = scan(context).filter { it.enabled && it.item.recommended }
            .filter { it.item.pkg !in LAUNCHERS || isDefaultHome(context) }
        if (todo.isEmpty()) return 0
        val cmd = todo.joinToString("; ") { "pm disable-user --user 0 ${it.item.pkg}" }
        AdbClient(context).shell(cmd)
        PixelDenApp.instance.store.update { it.copy(cleanupDisabled = (it.cleanupDisabled + todo.map { s -> s.item.pkg }).distinct()) }
        DebugLog.i("чистка: отключено ${todo.size}: " + todo.joinToString { it.item.pkg })
        return todo.size
    }

    /** Вернуть всё, что отключал лаунчер, — как было до чистки. */
    fun restoreAll(context: Context): Int {
        val list = PixelDenApp.instance.store.current.cleanupDisabled
        if (list.isEmpty()) return 0
        AdbClient(context).shell(list.joinToString("; ") { "pm enable $it" })
        PixelDenApp.instance.store.update { it.copy(cleanupDisabled = emptyList()) }
        DebugLog.i("чистка: возвращено ${list.size}")
        return list.size
    }

    /** Очистить кэши всех приложений (система сама решает, что можно). Возвращает освобождённые байты. */
    fun trimCaches(context: Context): Long {
        val before = Environment.getDataDirectory().freeSpace
        AdbClient(context).shell("pm trim-caches 64G", timeoutMs = 120_000)
        val freed = (Environment.getDataDirectory().freeSpace - before).coerceAtLeast(0)
        DebugLog.i("чистка: кэши — освобождено ${mb(freed)} МБ")
        return freed
    }

    /** Установочные APK в «Загрузках»: приложения уже стоят, а файлы занимают место. */
    fun apkFiles(): List<File> {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return dl.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("apk", "xapk", "apks") }.orEmpty()
    }

    fun deleteApks(): Long {
        var freed = 0L
        apkFiles().forEach { f -> val s = f.length(); if (f.delete()) freed += s }
        DebugLog.i("чистка: удалены установщики — ${mb(freed)} МБ")
        return freed
    }
}

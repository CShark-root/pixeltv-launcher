package com.pixelden.launcher.system

import android.os.Debug
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.scenario.DebugLog

/**
 * Уборка в самом лаунчере. Не просит систему «почистить что-нибудь», а выбрасывает то, что лаунчер держит сам,
 * и освобождает картинки сразу, а не когда-нибудь сборщиком мусора:
 *  1. кэш иконок и баннеров: всё, что не рисуется прямо сейчас, освобождается немедленно;
 *  2. обои, если главный экран сейчас закрыт;
 *  3. спрайты маскотов, кроме выбранного;
 *  4. сборка мусора Java в два прохода — второй забирает то, что отпустили финализаторы.
 * Иконки главного экрана освобождаются и без уборки — как только главный экран закрывается.
 */
object SelfClean {
    /**
     * Память самого лаунчера. Debug.getMemoryInfo, а не ActivityManager.getProcessMemoryInfo:
     * второй на Android 10+ отдаёт приложению свежий замер не чаще раза в 5 минут.
     */
    fun pss(): Long = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss * 1024L

    data class Report(val before: Long, val after: Long) {
        val freed get() = (before - after).coerceAtLeast(0)
    }

    /** Убраться. Блокирующий (ждёт, пока память вернётся системе) — звать не с главного потока. */
    fun run(): Report {
        val app = PixelDenApp.instance
        val before = pss()
        val images = app.apps.trimImages()
        val wall = com.pixelden.launcher.ui.WallpaperCache.trim()
        com.pixelden.launcher.ui.SheetCache.keepOnly(app.store.current.mascot)
        repeat(2) { System.gc(); System.runFinalization() }
        // Освобождённые картинки уходят через 200 мс, память возвращается системе не сразу:
        // меряем две секунды и берём лучший замер.
        var after = before
        repeat(4) { Thread.sleep(500); after = minOf(after, pss()) }
        DebugLog.i("уборка в лаунчере: ${mb(before)} → ${mb(after)} МБ (иконки ${mb(images)} МБ, обои ${mb(wall)} МБ)")
        return Report(before, after)
    }
}

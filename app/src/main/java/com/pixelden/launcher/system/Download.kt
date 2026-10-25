package com.pixelden.launcher.system

import com.pixelden.launcher.BuildConfig
import com.pixelden.launcher.i18n.tr
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Скачивание файла с редиректами между хостами (GitHub, зеркала) и прогрессом в процентах. */
object Download {
    fun text(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    fun toFile(url: String, file: File, onProgress: (Int) -> Unit = {}) {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    /** Открыть соединение, вручную пройдя редиректы (HttpURLConnection не ходит между http и https сам). */
    private fun open(start: String): HttpURLConnection {
        var url = URL(start)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "PixelTv/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200) { c.disconnect(); error(tr("скачивание: HTTP {0}", code)) }
            return c
        }
        error(tr("слишком много перенаправлений"))
    }
}

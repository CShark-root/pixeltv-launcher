package com.pixelden.launcher.web

import com.pixelden.launcher.i18n.tr
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.pixelden.launcher.scenario.DebugLog
import com.pixelden.launcher.scenario.DenAccessibilityService
import java.io.File

/** Установка APK, присланного с телефона или ПК. Система спросит подтверждение на экране ТВ. */
object ApkInstaller {
    fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val sender = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(sender.intentSender)
        }
        DebugLog.i("установка APK (${apk.length() / 1024} КБ): ждём подтверждения на ТВ")
        apk.delete()
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Окно подтверждения из фона открываем через службу (Android 10 иначе не пустит).
                (DenAccessibilityService.instance ?: context).startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> DebugLog.i("APK установлен")
            else -> {
                DebugLog.e("APK не установлен: $status $msg")
                Toast.makeText(context, tr("Установка не удалась: {0}", (msg ?: status)), Toast.LENGTH_LONG).show()
            }
        }
    }
}

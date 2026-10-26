package com.pixelden.launcher.system

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pixelden.launcher.scenario.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Подключение: Wi-Fi, кабель или ничего; [online] — интернет реально есть (система проверила). */
data class NetState(val kind: Kind, val online: Boolean) {
    enum class Kind { WIFI, ETHERNET, NONE }
}

/** Что сейчас с сетью — для значка в шапке. */
object NetStatus {
    private val _state = MutableStateFlow(NetState(NetState.Kind.NONE, false))
    val state: StateFlow<NetState> = _state
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun update(caps: NetworkCapabilities?) {
            _state.value = when {
                caps == null -> NetState(NetState.Kind.NONE, false)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState(NetState.Kind.ETHERNET, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetState(NetState.Kind.WIFI, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                else -> NetState(NetState.Kind.NONE, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        update(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
            override fun onLost(network: Network) = update(null)
        })
    }
}

/**
 * Трафик за сегодня. Системный счётчик идёт с момента включения ТВ и обнуляется при перезагрузке, поэтому
 * запоминаем, сколько было в начале дня, и копим набежавшее до перезагрузок.
 */
object NetToday {
    fun bytes(context: Context): Pair<Long, Long> {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0) return -1L to -1L
        val p = context.getSharedPreferences("net_today", Context.MODE_PRIVATE)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        // Время включения ТВ с точностью до минуты — по нему узнаём перезагрузку.
        val boot = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000
        var baseRx = p.getLong("baseRx", 0); var baseTx = p.getLong("baseTx", 0)
        var carRx = p.getLong("carRx", 0); var carTx = p.getLong("carTx", 0)
        val lastRx = p.getLong("lastRx", 0); val lastTx = p.getLong("lastTx", 0)
        val savedBoot = p.getLong("boot", boot)
        if (p.getString("day", null) != day) {
            // Новый день. Если ТВ включили уже сегодня — считаем всё с момента включения.
            val bootedToday = SystemClock.elapsedRealtime() < millisSinceMidnight()
            baseRx = if (bootedToday) 0 else rx; baseTx = if (bootedToday) 0 else tx
            carRx = 0; carTx = 0
        } else if (kotlin.math.abs(boot - savedBoot) > 1 || rx < lastRx) {
            // Была перезагрузка: счётчик начался заново — до неё набежало last − base.
            carRx += (lastRx - baseRx).coerceAtLeast(0); carTx += (lastTx - baseTx).coerceAtLeast(0)
            baseRx = 0; baseTx = 0
        }
        p.edit().putString("day", day).putLong("boot", boot)
            .putLong("baseRx", baseRx).putLong("baseTx", baseTx)
            .putLong("carRx", carRx).putLong("carTx", carTx)
            .putLong("lastRx", rx).putLong("lastTx", tx).apply()
        return (carRx + rx - baseRx) to (carTx + tx - baseTx)
    }

    private fun millisSinceMidnight(): Long {
        val c = java.util.Calendar.getInstance()
        return ((c.get(java.util.Calendar.HOUR_OF_DAY) * 60L + c.get(java.util.Calendar.MINUTE)) * 60 + c.get(java.util.Calendar.SECOND)) * 1000
    }
}

/** Уведомление для шторки в лаунчере. */
data class Notice(val key: String, val pkg: String, val title: String, val text: String, val time: Long, val intent: PendingIntent?, val clearable: Boolean)

/** Уведомления других приложений. Приходят от [NoticeListener], когда тому дали доступ. */
object Notices {
    private val _items = MutableStateFlow<List<Notice>>(emptyList())
    val items: StateFlow<List<Notice>> = _items

    /** Доступ к уведомлениям есть и служба подключена. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    internal var service: NoticeListener? = null
        set(v) { field = v; _connected.value = v != null }

    fun component(context: Context) = ComponentName(context, NoticeListener::class.java)

    internal fun reload(active: Array<StatusBarNotification>?) {
        val own = service?.packageName
        _items.value = active.orEmpty()
            .filter { sbn ->
                val n = sbn.notification
                // Служебное не показываем: свои, «идёт процесс», сводки групп и без текста.
                sbn.packageName != own && !sbn.isOngoing && n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                    (!n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() || !n.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank())
            }
            .sortedByDescending { it.postTime }
            .take(30)
            .map { sbn ->
                val e = sbn.notification.extras
                Notice(
                    key = sbn.key, pkg = sbn.packageName,
                    title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                    time = sbn.postTime, intent = sbn.notification.contentIntent, clearable = sbn.isClearable,
                )
            }
    }

    /**
     * Обновить список при открытии шторки. Служба уведомлений обновляет его сама; на ТВ «с малой памятью»
     * (Xiaomi) Android её не пускает — тогда читаем уведомления через встроенный adb. Блокирующий.
     */
    /** [ask] — спросить на ТВ доступ к отладке, если его ещё нет. Возвращает false, если прочитать не вышло. */
    fun refresh(context: Context, ask: Boolean = false): Boolean {
        if (service != null) return true
        val dump = runCatching { AdbClient(context).shell("dumpsys notification --noredact", timeoutMs = 10_000, ask = ask) }
            .onFailure { DebugLog.i("шторка: уведомления не прочитать — ${it.message}") }.getOrNull() ?: return false
        _items.value = parseDump(dump, context.packageName)
        return true
    }

    /** Разбор «dumpsys notification»: запись, её флаги и строки android.title / android.text. */
    internal fun parseDump(dump: String, own: String): List<Notice> {
        val out = ArrayList<Notice>()
        var pkg: String? = null; var flags = 0; var title = ""; var text = ""
        fun flush() {
            val p = pkg ?: return
            // Как и у службы: без своих, «идёт процесс» / служб на переднем плане, сводок групп и пустых.
            val skip = p == own || flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0
            if (!skip && (title.isNotBlank() || text.isNotBlank())) out += Notice("$p#${out.size}", p, title, text, 0, null, false)
        }
        val value = Regex("""=\w+ \((.*)\)$""")
        for (line in dump.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith("NotificationRecord(") -> {
                    flush()
                    pkg = Regex("""pkg=(\S+)""").find(l)?.groupValues?.get(1)
                    flags = Regex("""flags=0x([0-9a-fA-F]+)""").find(l)?.groupValues?.get(1)?.toIntOrNull(16) ?: 0
                    title = ""; text = ""
                }
                // Дальше в выводе — архив и службы, не текущие уведомления.
                l.startsWith("mArchive") || l.startsWith("Notification listeners") -> { flush(); pkg = null }
                l.startsWith("android.title=") -> title = value.find(l)?.groupValues?.get(1).orEmpty()
                l.startsWith("android.text=") -> text = value.find(l)?.groupValues?.get(1).orEmpty()
            }
        }
        flush()
        return out.take(30)
    }

    /** Открыть: то, что приложение назначило на нажатие (или само приложение), и убрать, если убирается само. */
    fun open(context: Context, n: Notice) {
        val sent = n.intent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!sent) runCatching {
            val pm = context.packageManager
            (pm.getLeanbackLaunchIntentForPackage(n.pkg) ?: pm.getLaunchIntentForPackage(n.pkg))
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
        }
        if (n.clearable) dismiss(n)
    }

    fun dismiss(n: Notice) { runCatching { service?.cancelNotification(n.key) } }

    fun clearAll() { runCatching { service?.cancelAllNotifications() } }
}

/** Служба доступа к уведомлениям. На ТВ включается только через adb — это делает «Настроить всё». */
class NoticeListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Notices.service = this
        Notices.reload(runCatching { activeNotifications }.getOrNull())
    }

    override fun onListenerDisconnected() { Notices.service = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Notices.reload(runCatching { activeNotifications }.getOrNull())

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Notices.reload(runCatching { activeNotifications }.getOrNull())
}

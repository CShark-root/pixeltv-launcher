@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.pixelden.launcher.ui

import com.pixelden.launcher.i18n.tr
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelden.launcher.PixelDenApp
import com.pixelden.launcher.apps.AppEntry
import com.pixelden.launcher.apps.InputEntry
import com.pixelden.launcher.data.Category
import com.pixelden.launcher.data.LauncherConfig
import com.pixelden.launcher.data.Trigger
import com.pixelden.launcher.scenario.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

@Composable
fun HomeScreen(
    cfg: LauncherConfig,
    idle: Boolean,
    homeTick: Int,
    onOpenSettings: () -> Unit,
    onOpenScenarios: () -> Unit,
    onOpenHealth: () -> Unit = {},
) {
    val app = PixelDenApp.instance
    val context = LocalContext.current
    val t = LocalDen.current
    val apps by app.apps.apps.collectAsState()
    val inputs by app.apps.inputs.collectAsState()
    val watch by app.watchNext.items.collectAsState()
    val event by app.engine.events.collectAsState()

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var renameFor by remember { mutableStateOf<AppEntry?>(null) }
    var focusedLabel by remember { mutableStateOf<String?>(null) }
    var focusedPkg by remember { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf(0) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    var lastFocusKey by remember { mutableStateOf<String?>(null) }

    val visible = remember(apps, cfg.hidden) { apps.filter { it.pkg !in cfg.hidden } }
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    val favorites = remember(cfg.favorites, byPkg) { cfg.favorites.mapNotNull { byPkg[it] } }
    // Кнопки ручных сценариев — только если включены в настройках и только сценарии с действиями.
    val manual = remember(cfg.scenarios, cfg.showManualScenarios) {
        if (!cfg.showManualScenarios) emptyList()
        else cfg.scenarios.filter { it.enabled && it.trigger is Trigger.Manual && it.actions.isNotEmpty() }
    }

    fun launch(entry: AppEntry) {
        jump++
        app.apps.launchIntent(entry.pkg)?.let { runCatching { context.startActivity(it) } }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var topJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Шапка и избранное — «верх»: фокус на них возвращает экран к самому началу.
    // Запускаем чуть позже стандартной прокрутки к элементу, чтобы она нас не перебила.
    fun scrollTop() {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) return
        topJob?.cancel()
        topJob = scope.launch { delay(60); listState.animateScrollToItem(0) }
    }
    var nonce by remember { mutableStateOf(0) }
    // Ключ фокуса + счётчик: повторный запрос на ту же карточку тоже срабатывает.
    fun focusOn(key: String?) { if (key != null) restoreKey = "$key#${nonce++}" }
    fun startKey() = favorites.firstOrNull()?.let { "fav:${it.pkg}" } ?: visible.firstOrNull()?.let { "all:${it.pkg}" }

    // Старт и повторное нажатие HOME: фокус на первую карточку, экран — к шапке с часами и маскотом.
    LaunchedEffect(homeTick, visible.isNotEmpty()) {
        if (visible.isEmpty()) return@LaunchedEffect
        if (homeTick > 0 || lastFocusKey == null) {
            focusOn(startKey())
            // Карточка берёт фокус и сама прокручивает список к себе — возвращаем шапку следом.
            repeat(3) {
                delay(250)
                listState.scrollToItem(0)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(t.colors.bg)) {
        Backdrop(cfg, paused = { listState.isScrollInProgress })

        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 48.dp),
        ) {
            item {
                Header(cfg, idle, focusedLabel, focusedPkg, event, jump, onOpenSettings, onOpenScenarios, onEventShown = { app.engine.clearEvent() },
                    onTopFocus = ::scrollTop)
            }
            if (cfg.showWidgets && cfg.widgets.isNotEmpty()) {
                item { WidgetRow(cfg, onTopFocus = ::scrollTop, onOpenHealth = onOpenHealth) }
            }
            if (manual.isNotEmpty()) {
                item {
                    Row(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        manual.take(6).forEach { s -> PButton("> ${s.name}", size = 14.sp) { app.engine.run(s) } }
                    }
                }
            }
            if (cfg.showFavorites) {
                item {
                    RowTitle(tr("Избранное"))
                    if (favorites.isEmpty()) {
                        PText(tr("Удерживайте OK на приложении → «В избранное»"), 14.sp, t.colors.dim, Modifier.padding(bottom = 18.dp))
                    } else {
                        AppRow(favorites, "fav", cfg, 4, restoreKey,
                            onFocus = { e, k -> focusedLabel = labelOf(cfg, e); focusedPkg = e.pkg; lastFocusKey = k; scrollTop() },
                            onClick = ::launch, onLong = { menuFor = it })
                    }
                }
            }
            if (cfg.showWatchNext && watch.isNotEmpty()) {
                item(key = "watch") {
                    RowTitle(tr("Продолжить просмотр"))
                    WatchRow(watch, cfg, restoreKey,
                        onFocus = { w, k -> focusedLabel = w.title; focusedPkg = w.pkg; lastFocusKey = k },
                        onClick = { jump++; app.watchNext.open(it) })
                }
            }
            if (cfg.showInputs && inputs.isNotEmpty()) {
                item {
                    RowTitle(tr("Входы"))
                    InputRow(inputs) { i -> jump++; runCatching { context.startActivity(app.apps.inputIntent(i.id)) } }
                }
            }
            if (cfg.showCategories) {
                for (cat in cfg.categories) {
                    val list = cat.apps.mapNotNull { byPkg[it] }
                    if (list.isEmpty()) continue
                    item(key = "cat-${cat.id}") {
                        RowTitle(cat.name)
                        AppRow(list, "cat${cat.id}", cfg, 3, restoreKey,
                            onFocus = { e, k -> focusedLabel = labelOf(cfg, e); focusedPkg = e.pkg; lastFocusKey = k },
                            onClick = ::launch, onLong = { menuFor = it })
                    }
                }
            }
            if (cfg.showAllApps) {
                // Избранное не дублируем в общей сетке.
                val rest = visible.filter { it.pkg !in cfg.favorites }
                item { RowTitle(tr("Все приложения · {0}", (rest.size))) }
                item {
                    AppGrid(rest, cfg, restoreKey,
                        onFocus = { e, k -> focusedLabel = labelOf(cfg, e); focusedPkg = e.pkg; lastFocusKey = k },
                        onClick = ::launch, onLong = { menuFor = it })
                }
            }
        }

        if (WidgetDialogs.rates) RatesChooser(cfg)
        if (WidgetDialogs.notices) NoticeShade()
        menuFor?.let { entry ->
            AppMenu(
                entry = entry,
                cfg = cfg,
                onDismiss = { menuFor = null; focusOn(lastFocusKey) },
                onRename = { renameFor = entry; menuFor = null },
            )
        }
        renameFor?.let { entry ->
            TextEditOverlay(
                tr("Название для {0}", (entry.label)),
                cfg.renamed[entry.pkg] ?: entry.label,
                onDismiss = { renameFor = null; focusOn(lastFocusKey) },
            ) { name ->
                app.store.update {
                    it.copy(renamed = if (name.isBlank() || name == entry.label) it.renamed - entry.pkg else it.renamed + (entry.pkg to name))
                }
                renameFor = null
                focusOn(lastFocusKey)
            }
        }
    }
}

private fun labelOf(cfg: LauncherConfig, e: AppEntry) = cfg.renamed[e.pkg] ?: e.label

@Composable
private fun Header(
    cfg: LauncherConfig,
    idle: Boolean,
    focusedLabel: String?,
    focusedPkg: String?,
    event: String?,
    jump: Int,
    onOpenSettings: () -> Unit,
    onOpenScenarios: () -> Unit,
    onEventShown: () -> Unit,
    /** Фокус пришёл на кнопки шапки — вернуть экран к самому верху. */
    onTopFocus: () -> Unit = {},
) {
    val context = LocalContext.current
    val t = LocalDen.current
    val now by produceState(Date(), cfg.showSeconds) {
        while (true) {
            value = Date()
            delay(if (cfg.showSeconds) 1000 else 10_000)
        }
    }
    val timeFmt = remember(cfg.clock24h, cfg.showSeconds) {
        val base = if (cfg.clock24h) "HH:mm" else "h:mm"
        SimpleDateFormat(if (cfg.showSeconds) "$base:ss" else base, Locale.getDefault())
    }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM", Locale(com.pixelden.launcher.i18n.I18n.lang)) }

    val app = PixelDenApp.instance
    val weather by app.weather.now.collectAsState()
    val news by app.news.titles.collectAsState()
    var bubble by remember { mutableStateOf<String?>(null) }
    var appComment by remember { mutableStateOf<String?>(null) }

    /** Показать реплику на [ms] и убрать, если её не перебили другой. */
    suspend fun say(text: String, ms: Long) {
        bubble = text
        delay(ms)
        if (bubble == text) bubble = null
    }

    // Приветствие (в праздник — поздравление).
    LaunchedEffect(Unit) {
        if (cfg.greeting && cfg.mascotSpeaks) say(MascotTalk.greetingFor(cfg.mascot, cfg.talkHolidays), 5000)
    }
    // Итоги сценариев.
    LaunchedEffect(event) {
        if (event != null) {
            if (cfg.mascotSpeaks) say(event, 4000)
            onEventShown()
        }
    }
    // Болтовня: болтовня, факты, новости, праздники — с паузами по выбранной болтливости.
    LaunchedEffect(cfg.mascotChatter, cfg.mascotSpeaks, idle) {
        if (!cfg.mascotSpeaks || cfg.mascotChatter == 0 || idle) return@LaunchedEffect
        val (from, to) = when (cfg.mascotChatter) { 1 -> 360_000L to 600_000L; 3 -> 45_000L to 100_000L; else -> 120_000L to 260_000L }
        while (true) {
            delay(Random.nextLong(from, to))
            if (bubble == null) {
                say(MascotTalk.live(cfg.mascot, weather, cfg.talkFacts, cfg.talkHolidays, if (cfg.talkNews) news else emptyList()), 7000)
            }
        }
    }
    // Проснулся, когда вернулись к пульту.
    var wasIdle by remember { mutableStateOf(idle) }
    LaunchedEffect(idle) {
        if (wasIdle && !idle && cfg.mascotSpeaks) say(MascotTalk.welcomeBack(), 3500)
        wasIdle = idle
    }
    // Иногда комментирует приложение в фокусе вместо простого названия.
    LaunchedEffect(focusedPkg) {
        appComment = null
        val pkg = focusedPkg ?: return@LaunchedEffect
        if (cfg.mascotSpeaks && Random.nextInt(100) < 30) {
            appComment = MascotTalk.aboutApp(pkg)
            if (appComment != null) { delay(3500); appComment = null }
        }
    }
    val shown = bubble ?: appComment ?: if (idle || !cfg.mascotNamesApps) null else focusedLabel

    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            PText(timeFmt.format(now), 64.sp, t.colors.text)
            if (cfg.showDate) PText(dateFmt.format(now).replaceFirstChar { it.uppercase() }, 18.sp, t.colors.dim)
            WeatherLine(cfg)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .onFocusChanged { if (it.hasFocus) onTopFocus() }
                    // «Вверх» с кнопок выдвигает шторку (статус сети и уведомления). Дальше вверх идти некуда —
                    // нажатие гасим, иначе фокус уходит из лаунчера в никуда.
                    .onPreviewKeyEvent { e ->
                        if (e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { UpEgg.press(); WidgetDialogs.notices = true }
                        true
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PButton(tr("Сценарии"), size = 14.sp, onClick = onOpenScenarios)
                PButton(tr("Настройки"), size = 14.sp, onClick = onOpenSettings)
                PButton(tr("ТВ"), size = 14.sp) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        if (cfg.mascotEnabled) {
            // Место под маскота фиксированное: его размер не двигает остальной интерфейс.
            // Если маскот крупнее — он просто выходит за рамки места, не влияя на разметку.
            Box(Modifier.size(width = 560.dp, height = 190.dp), contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.wrapContentSize(Alignment.BottomEnd, unbounded = true)) {
                    // Реплика рисуется самим маскотом — облачко над головой ходит вместе с ним.
                    MascotSprite(
                        mascot = mascotById(cfg.mascot),
                        pixel = cfg.mascotScale,
                        mood = if (idle) MascotMood.SLEEP else MascotMood.IDLE,
                        animate = t.animations,
                        jumpKey = jump,
                        talking = bubble != null || appComment != null,
                        // полоса прогулок не шире места под маскота
                        roam = (360 / cfg.mascotScale - 32).coerceIn(0, 40),
                        headroom = 8,
                        bubble = shown,
                        bruhKey = UpEgg.bruh,
                    )
                }
            }
        }
    }
}


@Composable
private fun RowTitle(text: String) {
    val t = LocalDen.current
    PText(text, 17.sp, t.colors.dim, Modifier.padding(top = 10.dp, bottom = 10.dp))
}

@Composable
private fun AppRow(
    list: List<AppEntry>,
    prefix: String,
    cfg: LauncherConfig,
    /** Сколько карточек ровно помещается в ширину экрана при размере карточек 100%. */
    perScreen: Int,
    restoreKey: String?,
    onFocus: (AppEntry, String) -> Unit,
    onClick: (AppEntry) -> Unit,
    onLong: (AppEntry) -> Unit,
) {
    val state = rememberLazyListState()
    val gap = 22.dp
    val bleed = 40.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Карточки делят ширину поровну: ряд начинается по краю часов и кончается на том же отступе справа.
        val n = (perScreen * 100f / cfg.cardSize).roundToInt().coerceIn(2, 8)
        val width = (maxWidth - gap * (n - 1)) / n
        LazyRow(
            state = state,
            // Ряд шире своего места на [bleed] с каждой стороны: увеличенная карточка в фокусе уходит
            // в поля экрана, а не обрезается, и при этом карточки стоят ровно по сетке.
            modifier = Modifier.layout { m, c ->
                val extra = bleed.roundToPx() * 2
                val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
            },
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(vertical = 16.dp, horizontal = bleed),
        ) {
            items(list, key = { "$prefix:${it.pkg}" }) { e ->
                val key = "$prefix:${e.pkg}"
                AppCard(e, cfg, width, key, restoreKey, { onFocus(e, key) }, { onClick(e) }, { onLong(e) })
            }
        }
    }
}

@Composable
private fun AppGrid(
    list: List<AppEntry>,
    cfg: LauncherConfig,
    restoreKey: String?,
    onFocus: (AppEntry, String) -> Unit,
    onClick: (AppEntry) -> Unit,
    onLong: (AppEntry) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 18.dp
        // Сетка по краям часов, как и ряды; увеличенные карточки уходят в поля экрана.
        val w = (maxWidth - gap * (cfg.columns - 1)) / cfg.columns
        Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(vertical = 12.dp)) {
            list.chunked(cfg.columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { e ->
                        val key = "all:${e.pkg}"
                        AppCard(e, cfg, w, key, restoreKey, { onFocus(e, key) }, { onClick(e) }, { onLong(e) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    e: AppEntry,
    cfg: LauncherConfig,
    width: Dp,
    key: String,
    restoreKey: String?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLong: () -> Unit,
) {
    val t = LocalDen.current
    val repo = PixelDenApp.instance.apps
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(restoreKey) { if (restoreKey?.substringBefore('#') == key) fr.tryFocus() }

    val banner by produceState<ImageBitmap?>(null, e.pkg, cfg.preferBanners) {
        if (cfg.preferBanners) hold { repo.banner(e.pkg) }
    }
    val icon by produceState<ImageBitmap?>(null, e.pkg, banner) {
        if (banner == null) hold { repo.icon(e.pkg) }
    }
    val height = width * 9f / 16f
    val effects = remember(cfg.focusEffects) { cfg.focusEffects.toSet() }
    // Плавные переходы: увеличение и затемнение анимируются, а не прыгают.
    val anim = if (t.animations) tween<Float>(180, easing = FastOutSlowInEasing) else snap()
    val scale by animateFloatAsState(if (focused && "scale" in effects) t.focusScale else 1f, anim, label = "scale")
    // «Затемнение остальных»: пока в фокусе другая карточка, эта приглушена.
    val dimmed = "dim" in effects && !focused && CardFocus.key != null
    val cardAlpha by animateFloatAsState(if (dimmed) 0.35f else 1f, anim, label = "alpha")
    // Рамка фокуса «прилетает»: сжимается на карточку снаружи и проявляется.
    val frameP by animateFloatAsState(if (focused) 1f else 0f, if (t.animations) tween(200, easing = FastOutSlowInEasing) else snap(), label = "frame")
    // Запуск: короткая вспышка и лёгкое увеличение, приложение открывается в конце вспышки.
    val launchP = remember { Animatable(0f) }
    val cardScope = rememberCoroutineScope()
    // В кадр доводим карточку вместе с подписью: иначе у нижнего ряда подпись уходила за край экрана.
    val bringLabel = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    fun launchWithFlash() {
        if (!t.animations) { onClick(); return }
        cardScope.launch {
            launchP.snapTo(0f)
            launchP.animateTo(1f, tween(170, easing = FastOutSlowInEasing))
            onClick()
            delay(300)
            launchP.snapTo(0f)
        }
    }

    Column(Modifier.width(width).bringIntoViewRequester(bringLabel).graphicsLayer { alpha = cardAlpha }) {
        Box(
            Modifier
                .size(width, height)
                .focusRequester(fr)
                .graphicsLayer { val k = scale * (1f + 0.08f * launchP.value); scaleX = k; scaleY = k }
                .focusGlow("glow" in effects && focused, t.colors.accent)
                .tvClickable(
                    onFocus = {
                        focused = it
                        if (it) {
                            CardFocus.key = key; onFocused()
                            cardScope.launch { delay(30); bringLabel.bringIntoView() }
                        } else if (CardFocus.key == key) CardFocus.key = null
                    },
                    onLongClick = onLong,
                    onClick = { launchWithFlash() },
                )
                .pixelBox(Color.Transparent, t.colors.surface, t.border)
                // Рамка фокуса рисуется ПОВЕРХ картинки — так её ничто не перекроет.
                .drawWithContent {
                    drawContent()
                    if (frameP > 0.01f) pixelFrame(t.colors.accent.copy(alpha = frameP), null, t.border.dp.toPx(), inflate = (1f - frameP) * 10.dp.toPx())
                    val p = launchP.value
                    if (p > 0f) drawRect(Color.White.copy(alpha = 0.55f * kotlin.math.sin(Math.PI.toFloat() * p)))
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding((t.border + 1).dp)
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                val b = banner
                val i = icon
                when {
                    b != null -> CardImage(b, isBanner = true, effects = effects, focused = focused, accent = t.colors.accent)
                    i != null -> CardImage(i, isBanner = false, effects = effects, focused = focused, accent = t.colors.accent)
                    else -> PText(e.label.take(2).uppercase(), 22.sp, t.colors.dim)
                }
            }
        }
        if (cfg.showLabels) {
            PText(
                cfg.renamed[e.pkg] ?: e.label,
                14.sp,
                if (focused) t.colors.accent else t.colors.dim,
                Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }
}

/** Ряд «Продолжить просмотр»: те же размеры и отступы, что у ряда приложений. */
@Composable
private fun WatchRow(
    list: List<com.pixelden.launcher.apps.WatchItem>,
    cfg: LauncherConfig,
    restoreKey: String?,
    onFocus: (com.pixelden.launcher.apps.WatchItem, String) -> Unit,
    onClick: (com.pixelden.launcher.apps.WatchItem) -> Unit,
) {
    val gap = 22.dp
    val bleed = 40.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = (4 * 100f / cfg.cardSize).roundToInt().coerceIn(2, 8)
        val width = (maxWidth - gap * (n - 1)) / n
        LazyRow(
            modifier = Modifier.layout { m, c ->
                val extra = bleed.roundToPx() * 2
                val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
            },
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(vertical = 16.dp, horizontal = bleed),
        ) {
            items(list, key = { "watch:${it.id}" }) { w ->
                val key = "watch:${w.id}"
                WatchCard(w, cfg, width, key, restoreKey, { onFocus(w, key) }, { onClick(w) })
            }
        }
    }
}

@Composable
private fun WatchCard(
    w: com.pixelden.launcher.apps.WatchItem,
    cfg: LauncherConfig,
    width: Dp,
    key: String,
    restoreKey: String?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val t = LocalDen.current
    val app = PixelDenApp.instance
    val density = androidx.compose.ui.platform.LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(restoreKey) { if (restoreKey?.substringBefore('#') == key) fr.tryFocus() }
    val height = width * 9f / 16f
    val poster by produceState<ImageBitmap?>(null, w.poster) {
        value = with(density) { app.watchNext.poster(w.poster, width.roundToPx(), height.roundToPx()) }
    }
    // Нет постера — значок приложения посередине.
    val icon by produceState<ImageBitmap?>(null, w.pkg, poster) {
        if (poster == null) hold { app.apps.icon(w.pkg) }
    }
    val anim = if (t.animations) tween<Float>(180, easing = FastOutSlowInEasing) else snap()
    val scale by animateFloatAsState(if (focused && "scale" in cfg.focusEffects) t.focusScale else 1f, anim, label = "scale")
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Column(Modifier.width(width).bringIntoViewRequester(bring)) {
        Box(
            Modifier
                .size(width, height)
                .focusRequester(fr)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .tvClickable(
                    onFocus = {
                        focused = it
                        if (it) { onFocused(); scope.launch { delay(30); bring.bringIntoView() } }
                    },
                    onClick = onClick,
                )
                .pixelBox(Color.Transparent, t.colors.surface, t.border)
                .drawWithContent {
                    drawContent()
                    // Сколько уже досмотрено — полоска внизу.
                    w.progress?.let { p ->
                        val h = 4.dp.toPx()
                        drawRect(Color.Black.copy(alpha = 0.5f), Offset(0f, size.height - h), Size(size.width, h))
                        drawRect(t.colors.accent, Offset(0f, size.height - h), Size(size.width * p, h))
                    }
                    if (focused) pixelFrame(t.colors.accent, null, t.border.dp.toPx())
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().padding((t.border + 1).dp).clipToBounds(), contentAlignment = Alignment.Center) {
                val p = poster
                val i = icon
                when {
                    p != null -> Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    i != null -> Image(i, null, Modifier.size(height * 0.5f))
                    else -> PText(w.title.take(2).uppercase(), 22.sp, t.colors.dim)
                }
            }
        }
        PText(w.title, 14.sp, if (focused) t.colors.accent else t.colors.text, Modifier.padding(top = 6.dp, start = 2.dp))
        if (w.subtitle.isNotBlank()) PText(w.subtitle, 12.sp, t.colors.dim, Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun InputRow(inputs: List<InputEntry>, onClick: (InputEntry) -> Unit) {
    val t = LocalDen.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp)) {
        items(inputs, key = { it.id }) { i ->
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .tvClickable(onFocus = { focused = it }) { onClick(i) }
                    .pixelBox(if (focused) t.colors.accent else Color.Transparent, if (focused) t.colors.surfaceHi else t.colors.surface, t.border)
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Column {
                    PText(i.label, 17.sp, if (focused) t.colors.text else t.colors.dim)
                    // Плитка названа именем устройства — порт мелко ниже.
                    i.port?.let { PText(it, 12.sp, t.colors.dim) }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AppMenu(entry: AppEntry, cfg: LauncherConfig, onDismiss: () -> Unit, onRename: () -> Unit) {
    val app = PixelDenApp.instance
    val context = LocalContext.current
    val store = app.store
    val fr = rememberAutoFocus()
    val isFav = entry.pkg in cfg.favorites

    fun moveFav(step: Int) = store.update {
        val l = it.favorites.toMutableList()
        val i = l.indexOf(entry.pkg)
        val j = (i + step).coerceIn(0, l.lastIndex)
        if (i >= 0 && i != j) { l.removeAt(i); l.add(j, entry.pkg) }
        it.copy(favorites = l)
    }

    Overlay(cfg.renamed[entry.pkg] ?: entry.label, onDismiss) {
        item {
            SettingRow(if (isFav) tr("Убрать из избранного") else tr("В избранное"), focusRequester = fr) {
                store.update { if (isFav) it.copy(favorites = it.favorites - entry.pkg) else it.copy(favorites = it.favorites + entry.pkg) }
                onDismiss()
            }
        }
        if (isFav) {
            item { SettingRow(tr("Сдвинуть в избранном"), value = "< >", onLeft = { moveFav(-1) }, onRight = { moveFav(1) }) }
        }
        items(cfg.categories, key = { it.id }) { cat ->
            val inCat = entry.pkg in cat.apps
            SettingRow(if (inCat) tr("Убрать из «{0}»", (cat.name)) else tr("В категорию «{0}»", (cat.name))) {
                store.update { c ->
                    c.copy(categories = c.categories.map {
                        if (it.id != cat.id) it else it.copy(apps = if (inCat) it.apps - entry.pkg else it.apps + entry.pkg)
                    })
                }
                onDismiss()
            }
        }
        item {
            SettingRow(tr("Новая категория с этим приложением")) {
                store.update { it.copy(categories = it.categories + Category(Templates.newId(), tr("Категория {0}", (it.categories.size + 1)), listOf(entry.pkg))) }
                onDismiss()
            }
        }
        item { SettingRow(tr("Переименовать"), onClick = onRename) }
        item {
            SettingRow(tr("Скрыть")) {
                store.update { it.copy(hidden = it.hidden + entry.pkg, favorites = it.favorites - entry.pkg) }
                onDismiss()
            }
        }
        item {
            SettingRow(tr("О приложении")) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                onDismiss()
            }
        }
        item {
            SettingRow(tr("Удалить")) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                onDismiss()
            }
        }
        item { SettingRow(tr("Закрыть"), onClick = onDismiss) }
    }
}

/** Погода под датой: пиксельная иконка, температура, состояние и «ощущается как». */
@Composable
private fun WeatherLine(cfg: LauncherConfig) {
    if (!cfg.weatherEnabled || cfg.weatherLat == null) return
    val t = LocalDen.current
    val w by PixelDenApp.instance.weather.now.collectAsState()
    val now = w ?: return
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelIcon(com.pixelden.launcher.weather.skyIcon(now.sky, now.isDay), 3, t.colors.text.copy(alpha = 0.8f), t.colors.text)
        // Две строки: в одну «ощущается» и город не влезали рядом с маскотом.
        Column(Modifier.padding(start = 10.dp)) {
            PText((if (now.temp > 0) "+" else "") + "${now.temp}°  ${now.sky.text}", 18.sp, t.colors.dim)
            val extra = listOfNotNull(
                now.feelsLike?.takeIf { it != now.temp }?.let { tr("ощущается {0}{1}°", (if (it > 0) "+" else ""), it) },
                cfg.weatherCity,
            ).joinToString(" · ")
            if (extra.isNotEmpty()) PText(extra, 14.sp, t.colors.dim.copy(alpha = 0.75f))
        }
    }
}

/** Держать картинку из кэша, пока карточка на экране, и вернуть её кэшу, когда карточка ушла. */
private suspend fun androidx.compose.runtime.ProduceStateScope<ImageBitmap?>.hold(load: suspend () -> ImageBitmap?) {
    val img = load() ?: return
    value = img
    try { kotlinx.coroutines.awaitCancellation() } finally {
        value = null
        PixelDenApp.instance.apps.release(img)
    }
}

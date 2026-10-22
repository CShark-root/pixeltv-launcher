package com.pixelden.launcher.ui

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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
                    RowTitle("Избранное")
                    if (favorites.isEmpty()) {
                        PText("Удерживайте OK на приложении → «В избранное»", 14.sp, t.colors.dim, Modifier.padding(bottom = 18.dp))
                    } else {
                        AppRow(favorites, "fav", cfg, cfg.cardSize * 1.9f, restoreKey,
                            onFocus = { e, k -> focusedLabel = labelOf(cfg, e); focusedPkg = e.pkg; lastFocusKey = k; scrollTop() },
                            onClick = ::launch, onLong = { menuFor = it })
                    }
                }
            }
            if (cfg.showInputs && inputs.isNotEmpty()) {
                item {
                    RowTitle("Входы")
                    InputRow(inputs) { i -> jump++; runCatching { context.startActivity(app.apps.inputIntent(i.id)) } }
                }
            }
            if (cfg.showCategories) {
                for (cat in cfg.categories) {
                    val list = cat.apps.mapNotNull { byPkg[it] }
                    if (list.isEmpty()) continue
                    item(key = "cat-${cat.id}") {
                        RowTitle(cat.name)
                        AppRow(list, "cat${cat.id}", cfg, cfg.cardSize * 2.2f, restoreKey,
                            onFocus = { e, k -> focusedLabel = labelOf(cfg, e); focusedPkg = e.pkg; lastFocusKey = k },
                            onClick = ::launch, onLong = { menuFor = it })
                    }
                }
            }
            if (cfg.showAllApps) {
                // Избранное не дублируем в общей сетке.
                val rest = visible.filter { it.pkg !in cfg.favorites }
                item { RowTitle("Все приложения · ${rest.size}") }
                item {
                    AppGrid(rest, cfg, restoreKey,
                        onFocus = { e, k -> focusedLabel = labelOf(cfg, e); focusedPkg = e.pkg; lastFocusKey = k },
                        onClick = ::launch, onLong = { menuFor = it })
                }
            }
        }

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
                "Название для ${entry.label}",
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
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM", Locale("ru")) }

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
            Row(Modifier.onFocusChanged { if (it.hasFocus) onTopFocus() }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PButton("Сценарии", size = 14.sp, onClick = onOpenScenarios)
                PButton("Настройки", size = 14.sp, onClick = onOpenSettings)
                PButton("ТВ", size = 14.sp) {
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
    width: Float,
    restoreKey: String?,
    onFocus: (AppEntry, String) -> Unit,
    onClick: (AppEntry) -> Unit,
    onLong: (AppEntry) -> Unit,
) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        // Запас по краям: карточка в фокусе увеличивается и не должна обрезаться рядом.
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 20.dp),
    ) {
        items(list, key = { "$prefix:${it.pkg}" }) { e ->
            val key = "$prefix:${e.pkg}"
            AppCard(e, cfg, width.dp, key, restoreKey, { onFocus(e, key) }, { onClick(e) }, { onLong(e) })
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
        val w = (maxWidth - gap * (cfg.columns - 1) - 40.dp) / cfg.columns
        Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
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
        value = if (cfg.preferBanners) repo.banner(e.pkg) else null
    }
    val icon by produceState<ImageBitmap?>(null, e.pkg, banner) {
        value = if (banner == null) repo.icon(e.pkg) else null
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

    Column(Modifier.width(width).graphicsLayer { alpha = cardAlpha }) {
        Box(
            Modifier
                .size(width, height)
                .focusRequester(fr)
                .graphicsLayer { val k = scale * (1f + 0.08f * launchP.value); scaleX = k; scaleY = k }
                .focusGlow("glow" in effects && focused, t.colors.accent)
                .tvClickable(
                    onFocus = {
                        focused = it
                        if (it) { CardFocus.key = key; onFocused() } else if (CardFocus.key == key) CardFocus.key = null
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
            ) { PText("${i.label}", 17.sp, if (focused) t.colors.text else t.colors.dim) }
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
            SettingRow(if (isFav) "Убрать из избранного" else "В избранное", focusRequester = fr) {
                store.update { if (isFav) it.copy(favorites = it.favorites - entry.pkg) else it.copy(favorites = it.favorites + entry.pkg) }
                onDismiss()
            }
        }
        if (isFav) {
            item { SettingRow("Сдвинуть в избранном", value = "< >", onLeft = { moveFav(-1) }, onRight = { moveFav(1) }) }
        }
        items(cfg.categories, key = { it.id }) { cat ->
            val inCat = entry.pkg in cat.apps
            SettingRow(if (inCat) "Убрать из «${cat.name}»" else "В категорию «${cat.name}»") {
                store.update { c ->
                    c.copy(categories = c.categories.map {
                        if (it.id != cat.id) it else it.copy(apps = if (inCat) it.apps - entry.pkg else it.apps + entry.pkg)
                    })
                }
                onDismiss()
            }
        }
        item {
            SettingRow("Новая категория с этим приложением") {
                store.update { it.copy(categories = it.categories + Category(Templates.newId(), "Категория ${it.categories.size + 1}", listOf(entry.pkg))) }
                onDismiss()
            }
        }
        item { SettingRow("Переименовать", onClick = onRename) }
        item {
            SettingRow("Скрыть") {
                store.update { it.copy(hidden = it.hidden + entry.pkg, favorites = it.favorites - entry.pkg) }
                onDismiss()
            }
        }
        item {
            SettingRow("О приложении") {
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
            SettingRow("Удалить") {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                onDismiss()
            }
        }
        item { SettingRow("Закрыть", onClick = onDismiss) }
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
        PText(
            (if (now.temp > 0) "+" else "") + "${now.temp}°  ${now.sky.text}" +
                (now.feelsLike?.takeIf { it != now.temp }?.let { " · ощущается ${if (it > 0) "+" else ""}$it°" } ?: "") +
                (cfg.weatherCity?.let { " · $it" } ?: ""),
            18.sp, t.colors.dim, Modifier.padding(start = 10.dp),
        )
    }
}

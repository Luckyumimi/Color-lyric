package shop.terramoor.github.io.settings

import io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.icons.CouiIcons
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.search.CouiSearchBarDefaults
import com.itos.coui.search.SearchBar
import com.itos.coui.search.rememberCouiSearchBarState
import com.itos.coui.theme.CouiTheme
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalAppNetwork
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalLyricHistory
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSettingsConstants as Keys
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuth
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator
import io.github.andrealtb.coloroslyrics.provider.universal.api.SearchCandidate
import io.github.andrealtb.coloroslyrics.provider.universal.api.WordTimedLyricConverter
import io.github.andrealtb.coloroslyrics.provider.universal.ui.AppleMusicLoginActivity
import io.github.andrealtb.coloroslyrics.provider.universal.ui.AppleMusicTokenRefresher
import io.github.andrealtb.coloroslyrics.provider.universal.ui.AppleMusicWebToken
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.InputView
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.DividerDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Text
import shop.terramoor.github.io.settings.coui.SwipeAction
import shop.terramoor.github.io.settings.coui.SwipeActionItem
import shop.terramoor.github.io.settings.coui.TagView
import shop.terramoor.github.io.settings.coui.TagViewColors
import shop.terramoor.github.io.settings.coui.TagViewDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import shop.terramoor.github.io.R

internal enum class LyricsSection {
    HOME, LYRICON, LYRICON_CATALOG, PLAYERS, SOURCES, PROCESSING, APPLE, SEARCH, CACHE, RECENT, DETAIL,
}
private data class LyricsRoute(val section: LyricsSection, val depth: Int)

private val knownPlayers = linkedMapOf(
    "com.apple.android.music" to "Apple Music",
    "com.tencent.qqmusic" to "QQ 音乐",
    "com.netease.cloudmusic" to "网易云音乐",
    "com.hihonor.cloudmusic" to "网易云音乐（荣耀版）",
    "com.kugou.android" to "酷狗音乐",
    "com.kugou.android.lite" to "酷狗概念版",
    "cn.kuwo.player" to "酷我音乐",
    "cn.kuwo.kwmusiccar" to "酷我音乐车机版",
    "cn.wenyu.bodian" to "波点音乐",
    "cmccwm.mobilemusic" to "咪咕音乐",
    "com.miui.player" to "小米音乐",
    "com.maxmpz.audioplayer" to "Poweramp",
    "com.salt.music" to "Salt Player",
    "com.luna.music" to "汽水音乐",
    "com.spotify.music" to "Spotify",
    "ink.trantor.coneplayer" to "光锥音乐",
    "ink.trantor.coneplayer.gp" to "ConePlayer (Google Play)",
    "ink.trantor.coneplayer.go" to "ConePlayer GO",
    "com.breezy.music" to "BreezyMusic",
    "cn.toside.music.mobile" to "LX Music",
    "com.lxwalnut.music.mobile" to "LX Music (Walnut)",
)

internal fun sourceName(id: String): String = when (id) {
    "QQ" -> "QQ 音乐"
    "NETEASE" -> "网易云音乐"
    "KUGOU" -> "酷狗音乐"
    "APPLE_MUSIC" -> "Apple Music"
    else -> LyricsSourceAggregator.SourceId.entries.firstOrNull { it.name == id }?.label ?: id
}

@Composable
internal fun BuiltinLyricsPage(
    modifier: Modifier,
    bottomClearance: Dp,
    section: LyricsSection,
    navigationDepth: Int,
    navigate: (LyricsSection) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshotState by BuiltinLyricsEvents.snapshot.collectAsState()
    val snapshot = snapshotState.fields
    val currentDuration = snapshot["durationMs"]?.toLongOrNull()
    val cache by BuiltinLyricsEvents.cache.collectAsState()
    var settings by remember { mutableStateOf(BuiltinLyricsConfig.read(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var autoSearch by rememberSaveable { mutableStateOf(false) }
    var cacheQuery by rememberSaveable { mutableStateOf("") }
    var confirmClearCache by rememberSaveable { mutableStateOf(false) }
    var targetTitle by rememberSaveable { mutableStateOf("") }
    var targetArtist by rememberSaveable { mutableStateOf("") }
    var targetDuration by rememberSaveable { mutableStateOf<Long?>(null) }
    val searchTargetDuration = targetDuration ?: currentDuration?.takeIf {
        snapshot["title"] == targetTitle && snapshot["artist"].orEmpty() == targetArtist
    }
    var searchBusy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchCandidate>>(emptyList()) }
    var selectedResultSource by rememberSaveable { mutableStateOf("") }
    var noLyricResultKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recognizedLyrics by remember { mutableStateOf<Map<String, io.github.andrealtb.coloroslyrics.provider.universal.api.LyricResult>>(emptyMap()) }
    var wordTimedResultKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recognitionBusy by remember { mutableStateOf(false) }
    var visibleResultCount by rememberSaveable { mutableStateOf(20) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var apiPlayers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var extraPackage by rememberSaveable { mutableStateOf("") }
    var customStorefront by rememberSaveable { mutableStateOf("") }
    var playerQuery by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf("") }
    var detailTitle by rememberSaveable { mutableStateOf("") }
    var detailArtist by rememberSaveable { mutableStateOf("") }
    var detailDuration by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailOriginal by rememberSaveable { mutableStateOf("") }
    var detailRaw by rememberSaveable { mutableStateOf("") }
    var detailTranslation by rememberSaveable { mutableStateOf("") }
    var detailTtml by rememberSaveable { mutableStateOf("") }
    var detailBase by remember { mutableStateOf("{}") }
    var pending by remember { mutableStateOf(false) }
    var requestGeneration by remember { mutableStateOf(0) }
    var handledRevision by remember { mutableStateOf(cache.revision) }
    val failedMessage = stringResource(R.string.lyrics_hub_failed)
    val doneMessage = stringResource(R.string.lyrics_hub_done)
    val pageState = rememberSaveableStateHolder()

    fun notify(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    fun reloadSettings() { settings = BuiltinLyricsConfig.read(context) }
    fun request(operation: String, key: String = "", payload: String? = null, offset: Int = 0,
                title: String = "", artist: String = "") {
        pending = true
        requestGeneration++
        BuiltinLyricsEvents.cacheRequest(context, operation, key, payload, offset, title, artist)
    }
    fun openSearch(title: String, artist: String, durationMs: Long? = null, submitImmediately: Boolean = false) {
        searchGeneration++
        searchJob?.cancel()
        searchBusy = false
        targetTitle = title
        targetArtist = artist
        targetDuration = durationMs
        query = listOf(title, artist).filter(String::isNotBlank).joinToString(" ")
        results = emptyList()
        selectedResultSource = ""
        noLyricResultKeys = emptySet()
        recognizedLyrics = emptyMap()
        wordTimedResultKeys = emptySet()
        recognitionBusy = false
        visibleResultCount = 20
        searched = false
        autoSearch = submitImmediately
        navigate(LyricsSection.SEARCH)
    }
    fun searchRecentTrack(row: UniversalLyricHistory.Entry) =
        openSearch(row.title, row.artist, row.durationMs, submitImmediately = true)

    LaunchedEffect(Unit) {
        BuiltinLyricsEvents.requestSnapshot(context)
        AppleMusicTokenRefresher.refreshIfNeeded(context)
    }
    LaunchedEffect(snapshot["amAuthStatus"]) { reloadSettings() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reloadSettings() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(section) {
        if (section != LyricsSection.SEARCH && searchJob?.isActive == true) {
            searchGeneration++
            searchJob?.cancel()
            searchBusy = false
        }
        if (section == LyricsSection.CACHE) request("list")
        if (section == LyricsSection.PLAYERS) {
            val available = withContext(Dispatchers.IO) {
            val pm = context.packageManager
                val applications = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                applications.associate {
                    it.packageName to runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)
                } to applications.filter { it.metaData?.getBoolean("superlyricapi") == true }.mapTo(mutableSetOf()) { it.packageName }
            }
            installed = available.first
            apiPlayers = available.second
        }
    }
    LaunchedEffect(requestGeneration, pending) {
        if (pending) {
            delay(8_000)
            if (pending) { pending = false; notify(failedMessage) }
        }
    }
    LaunchedEffect(cache.revision) {
        if (cache.revision <= handledRevision) return@LaunchedEffect
        handledRevision = cache.revision
        pending = false
        when (cache.operation) {
            "get", "find" -> {
                val value = cache.payload?.let { runCatching { JSONObject(it) }.getOrNull() }
                if ((!cache.success || value == null) && cache.operation == "find" && targetTitle.isNotBlank()) {
                    openSearch(targetTitle, targetArtist, searchTargetDuration)
                } else if (!cache.success || value == null) notify(failedMessage) else {
                    detailBase = value.toString()
                    detailKey = cache.key
                    detailTitle = value.optString("songName")
                    detailArtist = value.optString("artist")
                    detailDuration = value.optLong("durationMs").takeIf { it > 0L }
                    detailOriginal = value.optString("lyric")
                    detailRaw = value.optString("rawLyric")
                    detailTranslation = value.optString("translationLyric").ifBlank { value.optString("transLyric") }
                    detailTtml = value.optString("ttml")
                    editing = false
                    navigate(LyricsSection.DETAIL)
                }
            }
            "save", "delete", "clear" -> {
                notify(if (cache.success) doneMessage else failedMessage)
                if (cache.success) {
                    if (section != LyricsSection.CACHE) navigate(LyricsSection.CACHE)
                    else request("list")
                }
            }
            "bind" -> notify(if (cache.success) doneMessage else failedMessage)
        }
    }
    AnimatedContent(
        targetState = LyricsRoute(section, navigationDepth),
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState.depth >= initialState.depth) 1 else -1
            (slideInHorizontally(tween(280)) { direction * it / 4 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(280)) { -direction * it / 4 } + fadeOut(tween(180)))
        },
        label = "Lyrics subpages",
    ) { route ->
    pageState.SaveableStateProvider(route.section.name) {
    SettingsList(Modifier.fillMaxSize(), bottomClearance) {
        val pageTitle = when (route.section) {
            LyricsSection.HOME -> R.string.lyrics_hub_title
            LyricsSection.LYRICON -> R.string.lyricon_config
            LyricsSection.LYRICON_CATALOG -> R.string.hle_pack_catalog
            LyricsSection.PLAYERS -> R.string.lyrics_hub_players
            LyricsSection.SOURCES -> R.string.lyrics_hub_sources
            LyricsSection.PROCESSING -> R.string.lyrics_hub_processing
            LyricsSection.APPLE -> R.string.lyrics_hub_apple
            LyricsSection.SEARCH -> R.string.lyrics_hub_search
            LyricsSection.CACHE -> R.string.lyrics_hub_cache
            LyricsSection.RECENT -> R.string.lyrics_hub_recent
            LyricsSection.DETAIL -> R.string.lyrics_hub_preview
        }
        if (route.section == LyricsSection.CACHE) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(pageTitle), Modifier.weight(1f).semantics { heading() },
                    color = COUITheme.colorScheme.onSurface,
                    fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { confirmClearCache = true },
                    enabled = !pending && cache.total > 0,
                    pressScaleEnabled = true,
                    minWidth = 0.dp,
                    minHeight = ButtonDefaults.MinHeight,
                    colors = ButtonDefaults.buttonColors(contentColor = COUITheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.lyrics_hub_clear_cache), style = COUITheme.textStyles.button)
                }
            }
        } else {
            Text(stringResource(pageTitle), Modifier.semantics { heading() }, color = COUITheme.colorScheme.onSurface,
                fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        }
        when (route.section) {
            LyricsSection.HOME -> {
                SettingsGroup(stringResource(R.string.lyrics_hub_current)) {
                    val title = snapshot["title"].orEmpty().takeUnless { it.isBlank() || it == "(missing)" }
                    val artist = snapshot["artist"].orEmpty().takeUnless { it.isBlank() || it == "(missing)" }
                    BasicComponent(
                        title = title ?: stringResource(R.string.lyrics_hub_no_track), summary = artist,
                        startAction = { HubCover(snapshotState.artwork, 56.dp) },
                        endActions = { if (title != null) SongDurationTag(currentDuration) },
                    )
                    HubDivider()
                    TuningDropdown(
                        title = stringResource(R.string.lyrics_hub_mode),
                        items = LyricsProviderMode.entries.map { mode -> stringResource(mode.title) },
                        selectedIndex = settings.providerMode.ordinal,
                        itemEnabled = { LyricsProviderMode.entries[it].selectable },
                    ) { index ->
                        BuiltinLyricsConfig.setProviderMode(context, LyricsProviderMode.entries[index])
                        reloadSettings()
                    }
                    UnisonCredits(snapshot["sourceUrl"], snapshot["creditName"], snapshot["creditUrl"])
                    if (settings.providerMode == LyricsProviderMode.SUPER_LYRIC) {
                        HubDivider()
                        SuperLyricStatus(snapshot["superLyricConnection"]) { notify(failedMessage) }
                    }
                    if (settings.providerMode == LyricsProviderMode.LYRICON) {
                        HubDivider()
                        HubNavigation(stringResource(R.string.lyricon_config)) { navigate(LyricsSection.LYRICON) }
                    }
                    if (settings.providerMode == LyricsProviderMode.BUILTIN) {
                        HubDivider()
                        HubNavigation(stringResource(R.string.lyrics_hub_sources)) { navigate(LyricsSection.SOURCES) }
                    }
                }
                if (settings.providerMode == LyricsProviderMode.BUILTIN) {
                    val allHistory = remember(snapshot) { UniversalLyricHistory.load(context) }
                    val history = allHistory.take(3)
                    SettingsGroup(stringResource(R.string.lyrics_hub_recent)) {
                        if (history.isEmpty()) BasicComponent(title = stringResource(R.string.lyrics_hub_empty))
                        history.forEachIndexed { index, row ->
                            if (index > 0) HubDivider()
                            HubNavigation(row.title, historySummary(row.artist, row.source),
                                startAction = { HubTrackCover(row.title, row.artist) }) {
                                searchRecentTrack(row)
                            }
                        }
                        if (allHistory.size > 3) {
                            HubDivider()
                            HubNavigation(stringResource(R.string.lyrics_hub_view_all)) { navigate(LyricsSection.RECENT) }
                        }
                    }
                    SettingsGroup(stringResource(R.string.lyrics_hub_manage)) {
                        HubNavigation(stringResource(R.string.lyrics_hub_search)) {
                            openSearch(snapshot["title"].orEmpty().takeUnless { it == "(missing)" }.orEmpty(),
                                snapshot["artist"].orEmpty().takeUnless { it == "(missing)" }.orEmpty(), currentDuration)
                        }
                        HubDivider()
                        HubNavigation(stringResource(R.string.lyrics_hub_cache)) { navigate(LyricsSection.CACHE) }
                        HubDivider()
                        HubNavigation(stringResource(R.string.lyrics_hub_players)) { navigate(LyricsSection.PLAYERS) }
                        HubDivider()
                        HubNavigation(stringResource(R.string.lyrics_hub_apple)) { reloadSettings(); navigate(LyricsSection.APPLE) }
                    }
                }
                SettingsGroup(stringResource(R.string.lyrics_hub_settings)) {
                    HubNavigation(stringResource(R.string.lyrics_hub_processing)) { navigate(LyricsSection.PROCESSING) }
                }
            }
            LyricsSection.LYRICON -> LyriconConfigPage(onCatalog = { navigate(LyricsSection.LYRICON_CATALOG) })
            LyricsSection.LYRICON_CATALOG -> LyriconConfigPage(showCatalog = true)
            LyricsSection.PLAYERS -> {
                SettingSummary(stringResource(R.string.lyrics_hub_scope_hint))
                HubSearchField(playerQuery, { playerQuery = it }, stringResource(R.string.lyrics_hub_find_player))
                SettingsGroup(null) {
                    val matching = if (playerQuery.isBlank()) emptyList() else installed.filter {
                        it.key.contains(playerQuery, ignoreCase = true) || it.value.contains(playerQuery, ignoreCase = true)
                    }.keys.toList()
                    val visible = (knownPlayers.keys.filter { it in installed } + apiPlayers +
                        settings.players.filter { it !in knownPlayers } + matching).distinct()
                    visible.forEachIndexed { index, pkg ->
                        if (index > 0) HubDivider()
                        TactileSwitchPreference(
                            checked = pkg in settings.players,
                            onCheckedChange = { enabled ->
                                val next = if (enabled) settings.players + pkg else settings.players - pkg
                                settings = settings.copy(players = next)
                                BuiltinLyricsConfig.setPlayers(context, next)
                            },
                            title = knownPlayers[pkg] ?: installed[pkg] ?: pkg,
                            enabled = true,
                            summary = pkg,
                            startAction = {
                                val icon = remember(pkg, installed) {
                                    runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(80, 80) }.getOrNull()
                                }
                                HubCover(icon, 36.dp)
                            },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingSummary(stringResource(R.string.lyrics_hub_add_player), Modifier.padding(horizontal = 12.dp))
                    InputView(
                        value = extraPackage,
                        onValueChange = { extraPackage = it },
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.lyrics_hub_package_hint),
                        label = stringResource(R.string.lyrics_hub_package_example),
                        showClearButton = true,
                    )
                    HubPrimaryButton(
                        label = stringResource(R.string.lyrics_hub_add),
                        enabled = extraPackage.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        small = false,
                    ) {
                        val pkg = extraPackage.trim()
                        if (pkg in installed) {
                            settings = settings.copy(players = settings.players + pkg)
                            BuiltinLyricsConfig.setPlayers(context, settings.players)
                            extraPackage = ""
                        } else notify(failedMessage)
                    }
                }
            }
            LyricsSection.SOURCES -> {
                LyricsSourceOrderPage(settings.sources, LyricsSourceConfigStore.ALL) { next ->
                    settings = settings.copy(sources = next)
                    BuiltinLyricsConfig.setSources(context, next)
                }
            }
            LyricsSection.PROCESSING -> SettingsGroup(null) {
                TactileSwitchPreference(settings.translation, { BuiltinLyricsConfig.setBoolean(context, Keys.KEY_TRANSLATION, it); reloadSettings() }, stringResource(R.string.lyrics_hub_translation), true,
                    summary = stringResource(R.string.lyrics_hub_translation_description))
                HubDivider()
                TactileSwitchPreference(settings.wordTiming, { BuiltinLyricsConfig.setBoolean(context, Keys.KEY_WORD_TIMING, it); reloadSettings() }, stringResource(R.string.lyrics_hub_word_timing), true,
                    summary = stringResource(R.string.lyrics_hub_word_timing_description))
                HubDivider()
                TactileSwitchPreference(settings.coarseClockFallback, { BuiltinLyricsConfig.setBoolean(context, Keys.KEY_CLOCK_LINE_FALLBACK, it); reloadSettings() }, stringResource(R.string.lyrics_hub_coarse_clock), true,
                    summary = stringResource(R.string.lyrics_hub_coarse_clock_description))
                HubDivider()
                TactileSwitchPreference(settings.rawLyric, { BuiltinLyricsConfig.setBoolean(context, Keys.KEY_RAW_LYRIC, it); reloadSettings() }, stringResource(R.string.lyrics_hub_raw), true,
                    summary = stringResource(R.string.lyrics_hub_raw_description))
                HubDivider()
                TactileSwitchPreference(settings.ttmlFirst, { BuiltinLyricsConfig.setTtmlFirst(context, it); reloadSettings() }, stringResource(R.string.lyrics_hub_ttml_first), true,
                    summary = stringResource(R.string.lyrics_hub_ttml_first_description))
                HubDivider()
                TactileSwitchPreference(settings.debug, { BuiltinLyricsConfig.setBoolean(context, Keys.KEY_DEBUG, it); reloadSettings() }, stringResource(R.string.lyrics_hub_debug), true,
                    summary = stringResource(R.string.lyrics_hub_debug_description))
            }
            LyricsSection.APPLE -> SettingsGroup(null) {
                TactileSwitchPreference(settings.officialAppleMusic, { BuiltinLyricsConfig.setOfficialAppleMusic(context, it); reloadSettings() }, stringResource(R.string.lyrics_hub_official), true,
                    summary = stringResource(R.string.lyrics_hub_official_description))
                HubDivider()
                val loginSummary = when {
                    !settings.appleLoggedIn -> R.string.lyrics_hub_signed_out
                    settings.appleStatus == AppleMusicAuth.STATUS_REJECTED -> R.string.lyrics_hub_sign_in_expired
                    settings.appleStatus == AppleMusicAuth.STATUS_NO_SUBSCRIPTION -> R.string.lyrics_hub_no_subscription
                    else -> R.string.lyrics_hub_signed_in
                }
                BasicComponent(title = stringResource(R.string.lyrics_hub_apple_account),
                    summary = stringResource(loginSummary), endActions = {
                        HubButton(stringResource(if (settings.appleLoggedIn) R.string.lyrics_hub_relogin else R.string.lyrics_hub_login_action)) {
                            context.startActivity(Intent(context, AppleMusicLoginActivity::class.java))
                        }
                    })
                if (settings.appleLoggedIn) {
                    HubDivider()
                    HubRow(stringResource(R.string.lyrics_hub_sign_out)) {
                        AppleMusicWebToken.clearCookies()
                        io.github.andrealtb.coloroslyrics.provider.universal.UniversalFeaturePrefs.updateAppleMusic(context) {
                            it.copy(mediaUserToken = "", accountStorefront = "", storefrontLanguages = emptyList(), status = AppleMusicAuth.STATUS_MISSING)
                        }
                        reloadSettings()
                    }
                }
                HubDivider()
                val storefronts = (listOf("") + AppleMusicAuth.STOREFRONTS + settings.appleStorefront).distinct()
                TuningDropdown(stringResource(R.string.lyrics_hub_storefront),
                    listOf(stringResource(R.string.lyrics_hub_storefront_auto)) + storefronts.drop(1).map(String::uppercase),
                    storefronts.indexOf(settings.appleStorefront).coerceAtLeast(0)) { index ->
                    BuiltinLyricsConfig.setAppleStorefront(context, storefronts[index]); reloadSettings()
                }
                HubDivider()
                HubField(customStorefront, { customStorefront = it }, stringResource(R.string.lyrics_hub_storefront_custom))
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    HubButton(stringResource(R.string.lyrics_hub_save)) {
                        val region = AppleMusicAuth.normalizeStorefront(customStorefront)
                        if (region.isEmpty()) notify(failedMessage) else {
                            BuiltinLyricsConfig.setAppleStorefront(context, region)
                            reloadSettings()
                            customStorefront = ""
                        }
                    }
                }
            }
            LyricsSection.SEARCH -> {
                val focus = LocalFocusManager.current
                val selectedSources = manualSearchSources(settings.sources)
                val startSearch: () -> Unit = {
                    if (!searchBusy && query.isNotBlank() && selectedSources.isNotEmpty()) {
                        focus.clearFocus()
                        searchBusy = true
                        val term = query.trim()
                        val selected = selectedSources
                        val generation = ++searchGeneration
                        searchJob?.cancel()
                        results = emptyList()
                        recognizedLyrics = emptyMap()
                        noLyricResultKeys = emptySet()
                        wordTimedResultKeys = emptySet()
                        recognitionBusy = false
                        visibleResultCount = 20
                        searched = false
                        searchJob = scope.launch {
                            try {
                                withContext(Dispatchers.IO) { UniversalAppNetwork.initialize(context) }
                                collectManualCandidates(selected, search = { source ->
                                    withContext(Dispatchers.IO) {
                                        LyricsSourceAggregator.shared.searchCandidates(source, term)
                                    }
                                }) { rows ->
                                    if (generation == searchGeneration) results = rows
                                }
                                if (generation == searchGeneration) searched = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (generation == searchGeneration) {
                                    results = emptyList()
                                    searched = true
                                    notify(failedMessage)
                                }
                            } finally {
                                if (generation == searchGeneration) searchBusy = false
                            }
                        }
                    }
                }
                LaunchedEffect(section, autoSearch, query) {
                    if (section == LyricsSection.SEARCH && autoSearch && query.isNotBlank()) {
                        autoSearch = false
                        startSearch()
                    }
                }
                LaunchedEffect(section, searched, searchGeneration) {
                    if (section != LyricsSection.SEARCH || !searched || results.isEmpty()) return@LaunchedEffect
                    val generation = searchGeneration
                    val candidates = results.take(20)
                    recognitionBusy = true
                    try {
                        val io = Dispatchers.IO.limitedParallelism(4)
                        val recognized = coroutineScope {
                            candidates.map { row ->
                                async(io) {
                                    val key = row.source + "|" + row.id
                                    key to runCatching {
                                        LyricsSourceAggregator.shared.fetchByCandidate(row, settings.ttmlFirst)
                                    }.getOrNull()
                                }
                            }.awaitAll()
                        }
                        if (generation == searchGeneration) {
                            val successes = recognized.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
                            val failures = recognized.filter { it.second == null }.mapTo(mutableSetOf()) { it.first }
                            recognizedLyrics = recognizedLyrics + successes
                            noLyricResultKeys = noLyricResultKeys + failures
                            wordTimedResultKeys = wordTimedResultKeys + successes
                                .filterValues { result ->
                                    result.format.uppercase() in setOf("QRC", "YRC", "KRC", "ELRC") ||
                                        WordTimedLyricConverter.hasRealWordTiming(result.rawLyric ?: result.lyric)
                                }.keys
                        }
                    } finally {
                        if (generation == searchGeneration) recognitionBusy = false
                    }
                }
                if (targetTitle.isNotBlank()) SettingsGroup(stringResource(R.string.lyrics_hub_current)) {
                    BasicComponent(title = targetTitle, summary = targetArtist,
                        startAction = { HubTrackCover(targetTitle, targetArtist, 48.dp) },
                        endActions = { SongDurationTag(searchTargetDuration) })
                }
                HubSearchField(
                    value = query,
                    onChange = { query = it },
                    hint = stringResource(R.string.lyrics_hub_search_hint),
                    onSearch = startSearch,
                    flushEdges = true,
                )
                val resultSources = results.mapNotNull { it.source.trim().takeIf(String::isNotBlank) }
                    .distinct()
                val filteredResults = results.filter { selectedResultSource.isBlank() || it.source.trim() == selectedResultSource }
                if (results.isNotEmpty()) {
                    SearchRecognitionBar(
                        count = filteredResults.size,
                        recognizing = recognitionBusy,
                        enabled = !recognitionBusy,
                        onConfirmNoLyrics = {
                            val confirmed = filteredResults.map { it.source + "|" + it.id }
                                .filter { it !in recognizedLyrics }
                                .toSet()
                            noLyricResultKeys = noLyricResultKeys + confirmed
                        },
                    )
                }
                if (resultSources.isNotEmpty()) {
                    SearchSourceChipTabs(
                        sources = resultSources,
                        selected = selectedResultSource,
                        onSelected = {
                            selectedResultSource = it
                            visibleResultCount = 20
                        },
                    )
                }
                if (searchBusy) SettingSummary(stringResource(R.string.lyrics_hub_searching))
                if (selectedSources.isEmpty()) {
                    SettingsGroup(null) {
                        HubNavigation(stringResource(R.string.lyrics_hub_no_sources),
                            stringResource(R.string.lyrics_hub_choose_sources)) { navigate(LyricsSection.SOURCES) }
                    }
                }
                if (filteredResults.isNotEmpty()) SettingsGroup(null) {
                    filteredResults.take(visibleResultCount).forEachIndexed { index, row ->
                        if (index > 0) HubDivider()
                        HubRow(
                            title = row.title,
                            summary = listOf(row.artist, row.album, row.formatHint)
                                .filter(String::isNotBlank).joinToString(" · "),
                            enabled = !searchBusy,
                            endActions = {
                                SearchResultTags(
                                    row,
                                    searchTargetDuration,
                                    noLyric = row.source + "|" + row.id in noLyricResultKeys,
                                    wordTimed = row.source + "|" + row.id in wordTimedResultKeys,
                                )
                            },
                            startAction = {
                                val cover = androidx.compose.runtime.key(row.coverUrl) {
                                    produceState<android.graphics.Bitmap?>(null) {
                                        value = SearchResultArtwork.load(row.coverUrl)
                                    }.value
                                }
                                HubCover(cover)
                            },
                        ) {
                            searchBusy = true
                            val title = targetTitle.ifBlank { row.title }
                            val artist = targetArtist.ifBlank { row.artist }
                            val preferTtml = settings.ttmlFirst
                            scope.launch {
                                try {
                                    val key = row.source + "|" + row.id
                                    val result = recognizedLyrics[key] ?: withContext(Dispatchers.IO) {
                                        LyricsSourceAggregator.shared.fetchByCandidate(row, preferTtml)
                                    }
                                    if (result == null) {
                                        noLyricResultKeys = noLyricResultKeys + (row.source + "|" + row.id)
                                        notify(failedMessage)
                                    } else {
                                        pending = true
                                        requestGeneration++
                                        BuiltinLyricsEvents.bindManual(context, title, artist, result,
                                            if (targetTitle.isBlank()) row.durationMs else searchTargetDuration)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    notify(failedMessage)
                                } finally {
                                    searchBusy = false
                                }
                            }
                        }
                    }
                    if (filteredResults.size > visibleResultCount) {
                        HubDivider()
                        HubRow(stringResource(R.string.lyrics_hub_load_more)) { visibleResultCount += 20 }
                    }
                } else if (searched && !searchBusy) SettingSummary(stringResource(R.string.lyrics_hub_search_no_results))
            }
            LyricsSection.CACHE -> {
                if (confirmClearCache) {
                    val dark = androidx.compose.foundation.isSystemInDarkTheme()
                    val dismiss = { confirmClearCache = false }
                    CouiDialog(
                        onDismissRequest = dismiss,
                        dark = dark,
                        placement = CouiDialogPlacement.Center,
                        title = stringResource(R.string.lyrics_hub_clear_cache),
                        message = stringResource(R.string.lyrics_hub_clear_cache_message),
                        dismissAction = CouiDialogAction(stringResource(android.R.string.cancel), onClick = dismiss),
                        confirmAction = CouiDialogAction(
                            text = stringResource(R.string.lyrics_hub_delete),
                            style = CouiDialogActionStyle.Primary,
                            colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
                            onClick = {
                                dismiss()
                                request("clear")
                            },
                        ),
                    )
                }
                if (cache.rows.isEmpty()) SettingSummary(stringResource(R.string.lyrics_hub_empty))
                else {
                    HubSearchField(cacheQuery, { cacheQuery = it }, stringResource(R.string.lyrics_cache_search))
                    val rows = cache.rows.filter { it.title.contains(cacheQuery.trim(), ignoreCase = true) ||
                        it.artist.contains(cacheQuery.trim(), ignoreCase = true) }
                    SettingsGroup(stringResource(R.string.lyrics_cache_count, cache.total)) {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) HubDivider()
                            key(row.key) {
                                SwipeActionItem(
                                    actions = listOf(SwipeAction(label = stringResource(R.string.lyrics_hub_delete))),
                                    enabled = !pending,
                                    onActionClick = { if (!pending) request("delete", row.key) },
                                ) {
                                    HubNavigation(
                                        title = row.title,
                                        summary = row.artist.takeIf(String::isNotBlank),
                                        enabled = !pending,
                                        startAction = { HubTrackCover(row.title, row.artist) },
                                        trailingContent = { CacheSourceTags(row.source) },
                                    ) { request("get", row.key) }
                                }
                            }
                        }
                        if (cache.rows.size < cache.total) {
                            if (rows.isNotEmpty()) HubDivider()
                            HubRow(stringResource(R.string.lyrics_hub_load_more), enabled = !pending) { request("list", offset = cache.rows.size) }
                        }
                    }
                }
            }
            LyricsSection.RECENT -> {
                val history = remember(snapshot) { UniversalLyricHistory.load(context) }
                if (history.isEmpty()) SettingSummary(stringResource(R.string.lyrics_hub_empty))
                else SettingsGroup(null) {
                    history.forEachIndexed { index, row ->
                        if (index > 0) HubDivider()
                        HubNavigation(
                            title = row.title,
                            summary = historySummary(row.artist, row.source),
                            enabled = !pending,
                            startAction = { HubTrackCover(row.title, row.artist) },
                        ) { searchRecentTrack(row) }
                    }
                }
            }
            LyricsSection.DETAIL -> {
                SettingsGroup(null) {
                    BasicComponent(title = detailTitle, summary = detailArtist,
                        endActions = { SongDurationTag(detailDuration) })
                    HubDivider()
                    if (editing) {
                        HubField(detailOriginal, { detailOriginal = it }, stringResource(R.string.lyrics_hub_original), multiline = true)
                        HubField(detailRaw, { detailRaw = it }, stringResource(R.string.lyrics_hub_raw_field), multiline = true)
                        HubField(detailTranslation, { detailTranslation = it }, stringResource(R.string.lyrics_hub_translation_field), multiline = true)
                        HubField(detailTtml, { detailTtml = it }, stringResource(R.string.lyrics_hub_ttml_field), multiline = true)
                        HubRow(stringResource(R.string.lyrics_hub_save), enabled = !pending) {
                            val json = JSONObject(detailBase).put("lyric", detailOriginal).put("rawLyric", detailRaw)
                                .put("translationLyric", detailTranslation).put("transLyric", detailTranslation).put("ttml", detailTtml)
                            request("save", detailKey, json.toString())
                        }
                    } else {
                        Text(detailOriginal.ifBlank { stringResource(R.string.lyrics_hub_empty) },
                            Modifier.padding(16.dp), color = COUITheme.colorScheme.onSurface, style = COUITheme.textStyles.body2)
                        val credits = remember(detailBase) { runCatching { JSONObject(detailBase) }.getOrNull() }
                        UnisonCredits(credits?.optString("sourceUrl"), credits?.optString("creditName"),
                            credits?.optString("creditUrl"))
                        HubNavigation(stringResource(R.string.lyrics_hub_replace)) { openSearch(detailTitle, detailArtist, detailDuration) }
                        if (detailKey.isNotBlank()) {
                            HubDivider()
                            HubRow(stringResource(R.string.lyrics_hub_edit)) { editing = true }
                            HubDivider()
                            HubRow(stringResource(R.string.lyrics_hub_delete), enabled = !pending) { request("delete", detailKey) }
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
internal fun HubDivider() = HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))

@Composable
internal fun HubRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    endActions: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val haptic = rememberCouiHaptic()
    BasicComponent(title = title, summary = summary, enabled = enabled, startAction = startAction,
        endActions = { endActions() }, onClick = { haptic(CouiHapticEffect.Strength); onClick() })
}

@Composable
internal fun HubNavigation(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptic = rememberCouiHaptic()
    BasicComponent(title = title, summary = summary, enabled = enabled, startAction = startAction,
        onClick = { haptic(CouiHapticEffect.Strength); onClick() },
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                trailingContent?.invoke()
                io.github.suqi8.coui.kmp.basic.Icon(
                    imageVector = CouiIcons.Outlined.Next, contentDescription = null,
                    tint = COUITheme.colorScheme.onSurfaceVariantActions, modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
internal fun HubField(value: String, onValueChange: (String) -> Unit, hint: String, multiline: Boolean = false) {
    val color = COUITheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        SettingSummary(hint, Modifier.padding(bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().background(CouiPageDefaults.themeColors().background, RoundedCornerShape(16.dp)).padding(12.dp),
            textStyle = COUITheme.textStyles.body1.copy(color = color),
            cursorBrush = SolidColor(color),
            minLines = if (multiline) 4 else 1,
            maxLines = if (multiline) 16 else 1,
            singleLine = !multiline,
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(hint, color = COUITheme.colorScheme.onSurfaceVariantSummary); inner() }
            },
        )
    }
}

@Composable
private fun HubSearchField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onSearch: (() -> Unit)? = null,
    flushEdges: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = rememberCouiSearchBarState(expanded)
    val backdrop = rememberCouiBackdrop()
    Box(modifier) {
        Box(Modifier.matchParentSize().couiBackdrop(backdrop).background(CouiPageDefaults.themeColors().background))
        val searchLayout = if (flushEdges) Modifier.layout { measurable, constraints ->
            // COUI applies its own page margins. This row is already inset by the page.
            val inset = CouiSearchBarDefaults.horizontalMargin.roundToPx()
            val width = constraints.maxWidth
            val child = measurable.measure(constraints.copy(minWidth = width + 2 * inset, maxWidth = width + 2 * inset))
            layout(width, child.height) { child.placeRelative(-inset, 0) }
        } else Modifier
        fun submit() {
            expanded = false
            onSearch?.invoke()
        }
        SearchBar(
            modifier = Modifier.fillMaxWidth().then(searchLayout).onPreviewKeyEvent { event ->
                if (onSearch == null || !expanded || (event.key != Key.Enter && event.key != Key.NumPadEnter)) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyUp) submit()
                true
            }, state = state, query = value, onQueryChange = onChange,
            onExpandedChange = { expanded = it }, placeholder = hint, cancelText = stringResource(android.R.string.cancel),
            clearDescription = stringResource(R.string.lyrics_hub_clear_search),
            dark = androidx.compose.foundation.isSystemInDarkTheme(), backdrop = backdrop,
            onSearch = ::submit,
        )
    }
}

@Composable
private fun HubButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        text = label,
        enabled = enabled,
        onClick = onClick,
        minWidth = ButtonDefaults.MinWidthSmall,
        minHeight = ButtonDefaults.MinHeightSmall,
        cornerRadius = ButtonDefaults.CornerRadiusSmall,
        insideMargin = ButtonDefaults.InsideMarginSmall,
        textStyle = COUITheme.textStyles.button.copy(fontSize = 14.sp),
    )
}

@Composable
internal fun HubPrimaryButton(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    small: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        pressScaleEnabled = true,
        minWidth = if (small) ButtonDefaults.MinWidthSmall else ButtonDefaults.MinWidth,
        minHeight = if (small) ButtonDefaults.MinHeightSmall else ButtonDefaults.MinHeight,
        cornerRadius = if (small) ButtonDefaults.CornerRadiusSmall else ButtonDefaults.CornerRadius,
        insideMargin = if (small) ButtonDefaults.InsideMarginSmall else ButtonDefaults.InsideMargin,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(label, style = if (small) COUITheme.textStyles.button.copy(fontSize = 14.sp) else COUITheme.textStyles.button)
    }
}

@Composable
private fun HubCover(cover: android.graphics.Bitmap?, size: Dp = 44.dp) {
    val shape = RoundedCornerShape(10.dp)
    if (cover != null && !cover.isRecycled) {
        Image(cover.asImageBitmap(), null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).background(CouiPageDefaults.themeColors().background, shape), contentAlignment = Alignment.Center) {
            io.github.suqi8.coui.kmp.basic.Icon(CouiIcons.Outlined.Album, null, Modifier.size(22.dp),
                tint = COUITheme.colorScheme.onSurfaceVariantActions)
        }
    }
}

@Composable
private fun HubTrackCover(title: String, artist: String, size: Dp = 44.dp) {
    val context = LocalContext.current
    val snapshot by BuiltinLyricsEvents.snapshot.collectAsState()
    val artwork = if (snapshot.fields["title"] == title && snapshot.fields["artist"].orEmpty() == artist) snapshot.artwork else null
    val cover = androidx.compose.runtime.key(title, artist) {
        produceState<android.graphics.Bitmap?>(null, artwork) {
            value = artwork ?: withContext(Dispatchers.IO) { BuiltinLyricsEvents.cover(context, title, artist) }
        }.value
    }
    HubCover(cover, size)
}

@Composable
private fun UnisonCredits(songUrl: String?, name: String?, curatorUrl: String?) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    fun trusted(value: String?, route: String): String? = value?.takeIf {
        it.matches(Regex("https://unison[.]betterlyrics[.]org/$route/[A-Za-z0-9_-]{1,128}"))
    }
    val link = trusted(songUrl, "song") ?: return
    HubDivider()
    HubNavigation("Unison") { runCatching { uriHandler.openUri(link) } }
    val profile = trusted(curatorUrl, "curator")
    if (profile != null && !name.isNullOrBlank()) {
        HubDivider()
        HubNavigation(name) { runCatching { uriHandler.openUri(profile) } }
    }
}

private fun matchesDurationSecondsDigit(targetMs: Long?, candidateMs: Long): Boolean {
    if (targetMs == null || targetMs <= 0L || candidateMs <= 0L) return false
    if (targetMs == candidateMs) return true
    return (targetMs / 1000L) % 10L == (candidateMs / 1000L) % 10L
}

private fun isWordTimedHint(format: String): Boolean = when (format.uppercase()) {
    "QRC", "YRC", "KRC", "ELRC", "WORD", "WORD_TIMED", "WORD-TIMED" -> true
    else -> false
}

private fun isNoLyricHint(format: String): Boolean = when (format.uppercase()) {
    "NONE", "NO_LYRICS", "NO-LYRICS", "INSTRUMENTAL" -> true
    else -> false
}

@Composable
private fun SongDurationTag(durationMs: Long?, colors: TagViewColors = lyricTagColors()) {
    val label = if (durationMs != null && durationMs > 0L) {
        val seconds = durationMs / 1000L
        "${seconds / 60L}:${(seconds % 60L).toString().padStart(2, '0')}"
    } else {
        stringResource(R.string.lyrics_hub_duration_unknown)
    }
    TagView(label, colors = colors)
}

@Composable
private fun SearchResultTags(
    row: SearchCandidate,
    targetDuration: Long?,
    noLyric: Boolean,
    wordTimed: Boolean,
) {
    val green = TagViewDefaults.tagViewColors(
        containerColor = Color(0xFF2E7D32).copy(alpha = 0.16f),
        contentColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF206527),
    )
    val red = TagViewDefaults.tagViewColors(
        containerColor = Color(0xFFC62828).copy(alpha = 0.16f),
        contentColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFEF9A9A) else Color(0xFFB71C1C),
    )
    Column(
        modifier = Modifier.widthIn(max = 108.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TagView(sourceName(row.source), colors = lyricTagColors())
        SongDurationTag(row.durationMs, if (matchesDurationSecondsDigit(targetDuration, row.durationMs)) green else lyricTagColors())
        when {
            wordTimed || isWordTimedHint(row.formatHint) -> TagView(
                stringResource(R.string.lyrics_hub_word_timed_tag),
                colors = green,
            )
            noLyric || isNoLyricHint(row.formatHint) -> TagView(
                stringResource(R.string.lyrics_hub_no_lyrics_tag),
                colors = red,
            )
        }
    }
}

@Composable
private fun SearchRecognitionBar(
    count: Int,
    recognizing: Boolean,
    enabled: Boolean,
    onConfirmNoLyrics: () -> Unit,
) {
    val backdrop = rememberCouiBackdrop()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (recognizing) R.string.lyrics_hub_search_recognizing else R.string.lyrics_hub_search_candidate_count,
                count,
            ),
            modifier = Modifier.weight(1f),
            color = COUITheme.colorScheme.onSurface,
            style = COUITheme.textStyles.body2,
        )
        Box(Modifier.couiBackdrop(backdrop)) {
            Button(
                onClick = onConfirmNoLyrics,
                enabled = enabled,
                pressScaleEnabled = true,
                minWidth = ButtonDefaults.MinWidthSmall,
                minHeight = ButtonDefaults.MinHeightSmall,
                cornerRadius = ButtonDefaults.CornerRadiusSmall,
                insideMargin = ButtonDefaults.InsideMarginSmall,
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(
                    stringResource(R.string.lyrics_hub_confirm_no_lyrics),
                    style = COUITheme.textStyles.button.copy(fontSize = 13.sp),
                )
            }
        }
    }
}

@Composable
private fun SearchSourceChipTabs(sources: List<String>, selected: String, onSelected: (String) -> Unit) {
    val haptic = rememberCouiHaptic()
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (source in listOf("") + sources) {
            val active = source == selected
            TagView(
                text = if (source.isEmpty()) stringResource(R.string.lyrics_hub_all_results) else sourceName(source),
                modifier = Modifier
                    .clip(shape)
                    .clickable {
                        if (!active) {
                            haptic(CouiHapticEffect.Strength)
                            onSelected(source)
                        }
                    },
                shape = shape,
                colors = if (active) TagViewDefaults.tagViewColors(
                    containerColor = COUITheme.colorScheme.primary,
                    contentColor = COUITheme.colorScheme.onPrimary,
                ) else lyricTagColors(),
                textSize = 12.dp,
                insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun lyricTagColors() = TagViewDefaults.tagViewColors(
    containerColor = COUITheme.colorScheme.onSurface.copy(alpha = 0.08f),
    contentColor = COUITheme.colorScheme.onSurface,
)

@Composable
private fun CacheSourceTags(rawSource: String) {
    val (hook, label) = lyricAttribution(rawSource) ?: return
    val colors = lyricTagColors()
    Column(
        modifier = Modifier.widthIn(max = 92.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TagView(stringResource(if (hook) R.string.lyrics_cache_hook else R.string.lyrics_cache_online), colors = colors)
        TagView(label, colors = colors)
    }
}

@Composable
private fun historySummary(artist: String, rawSource: String): String {
    val attribution = lyricAttribution(rawSource)?.let { (hook, label) ->
        stringResource(if (hook) R.string.lyrics_hub_hook_attribution else R.string.lyrics_hub_network_attribution, label)
    }
    return listOfNotNull(artist.takeIf(String::isNotBlank), attribution).joinToString(" · ")
}

@Composable
private fun lyricAttribution(rawSource: String): Pair<Boolean, String>? {
    val source = rawSource.substringBefore('|')
    val packageId = when (source) {
        "qqmusic-internal" -> "com.tencent.qqmusic"
        "kugou-internal" -> "com.kugou.android"
        "kuwo-internal" -> "cn.kuwo.player"
        else -> source.removePrefix("player/").replace(Regex("-v[0-9]+$"), "")
    }
    val knownPlayer = knownPlayers[packageId]
    val hook = source.startsWith("player/") || knownPlayer != null
    val context = LocalContext.current
    val label = if (hook) knownPlayer ?: remember(packageId) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageId, 0)).toString()
        }.getOrNull()
    } ?: stringResource(R.string.lyrics_hub_players)
    else when (val id = source.removePrefix("universal/").lowercase()) {
        "qq", "netease", "kugou" -> sourceName(id.uppercase())
        "apple", "apple_music", "applemusic", "apple-music", "apple-music/official" -> sourceName("APPLE_MUSIC")
        "lrclib" -> "LRCLIB"
        "lyricsplus" -> "LyricsPlus"
        "unison", "lrc_red", "karalyr", "amll", "lunabeat" -> sourceName(id.uppercase())
        "better_lyrics" -> "Better Lyrics"
        "paxsenix" -> "Paxsenix"
        else -> when {
            source.startsWith("universal/paxsenix/") -> "Paxsenix"
            source.startsWith("universal/") -> stringResource(R.string.lyrics_hub_network)
            else -> null
        }
    }
    return label?.let { hook to it }
}

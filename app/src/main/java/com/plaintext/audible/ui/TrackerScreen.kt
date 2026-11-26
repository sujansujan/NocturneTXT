package com.plaintext.audible.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.plaintext.audible.BuildConfig
import com.plaintext.audible.data.*
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class ActiveTab {
    UPCOMING, TRACKED, SETTINGS
}

enum class QuickFilter(val label: String) {
    ALL("ALL"),
    PINNED("PIN"),
    SERIES("SERIES"),
    AUTHOR("AUTHOR"),
    NARRATOR("NARRATOR"),
    SOON_30D("<=30d"),
    RECENT_30D("RECENT"),
    ABB("ABB"),
    READ("READ"),
    DND("DND")
}

enum class TrackedSourceFilter(val label: String, val trackType: TrackType?) {
    ALL("ALL", null),
    SERIES("SERIES", TrackType.SERIES),
    AUTHOR("AUTHOR", TrackType.AUTHOR),
    NARRATOR("NARRATOR", TrackType.NARRATOR),
    BOOK("BOOK", TrackType.BOOK)
}

enum class TrackedSortOrder(val label: String) {
    A_TO_Z("A-Z"),
    MOST_UPCOMING("UPCOMING")
}

@Immutable
data class ReleaseCardUiModel(
    val entity: ReleaseEntity,
    val upperTitle: String,
    val headerLabel: String,
    val urgencyTone: Int, // 0 = green (OUT TODAY/ABB), 1 = orange (1..7d), 2 = cyan (8..30d), 3 = comment (>30d/TBA/past)
    val isFutureOrTba: Boolean,
    val isPastOrToday: Boolean,
    val isPinned: Boolean,
    val hasDateShift: Boolean,
    val seriesValue: String,
    val sameAuthorAndNarrator: Boolean,
    val runtimeFormatted: String,
    val publisherFormatted: String,
    val matchedSourcesFormatted: String,
    val matchedSeriesItem: TrackedItemEntity?,
    val matchedStandaloneBookItem: TrackedItemEntity?,
    val hasSeriesGap: Boolean,
    val unreadGapBookNum: Int,
    val canMarkCurrentRead: Boolean,
    val primaryAuthor: String,
    val primaryNarrator: String,
    val isSeriesAlreadyTracked: Boolean,
    val isPrimaryAuthorAlreadyTracked: Boolean,
    val isPrimaryNarratorAlreadyTracked: Boolean
)

@Immutable
data class MonthGroupUiModel(
    val monthHeader: String,
    val cards: List<ReleaseCardUiModel>
)

@Composable
fun TrackerScreen(
    repo: ReleaseRepository,
    sharedImportText: String? = null,
    onConsumeSharedImport: () -> Unit = {},
    externalStatusMessage: String? = null,
    externalStatusIsWarn: Boolean = false,
    onConsumeExternalStatus: () -> Unit = {},
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val prefs by repo.prefsFlow.collectAsState()
    val syncTelemetry by repo.telemetryFlow.collectAsState()
    val isSyncing by repo.isSyncing.collectAsState()
    val isCheckingAbbBatch by repo.isCheckingAbb.collectAsState()
    val syncProgress by repo.syncProgress.collectAsState()

    val systemDark = isSystemInDarkTheme()
    val useDarkPalette = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = if (useDarkPalette) DraculaPalette else AlucardPalette

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDarkPalette
            controller.isAppearanceLightNavigationBars = !useDarkPalette
        }
    }

    val releases by repo.dao.observeReleases().collectAsState(initial = emptyList())
    val trackedItems by repo.dao.observeTrackedItems().collectAsState(initial = emptyList())
    val dndRules by repo.dao.observeDndRules().collectAsState(initial = emptyList())
    val readBooks by repo.dao.observeReadBooks().collectAsState(initial = emptyList())
    val pinnedReleases by repo.dao.observePinnedReleases().collectAsState(initial = emptyList())

    val readAsins = remember(readBooks) { readBooks.map { it.asin.lowercase(Locale.US) }.toSet() }
    val readGroupKeys = remember(readBooks) { readBooks.mapNotNull { it.groupKey.takeIf { gk -> gk.isNotBlank() } }.toSet() }
    val pinnedAsins = remember(pinnedReleases) { pinnedReleases.map { it.asin.lowercase(Locale.US) }.toSet() }
    val pinnedGroupKeys = remember(pinnedReleases) { pinnedReleases.mapNotNull { it.groupKey.takeIf { gk -> gk.isNotBlank() } }.toSet() }

    var activeTab by remember { mutableStateOf(ActiveTab.UPCOMING) }
    var lastNonHomeTab by remember { mutableStateOf<ActiveTab?>(null) }
    var activeFilter by remember { mutableStateOf(QuickFilter.ALL) }
    var trackedSourceFilter by remember { mutableStateOf(TrackedSourceFilter.ALL) }
    var trackedSortOrder by remember { mutableStateOf(TrackedSortOrder.A_TO_Z) }
    var focusedSourceItem by remember { mutableStateOf<TrackedItemEntity?>(null) }
    var localFilterOpen by remember { mutableStateOf(false) }
    var localFilterQuery by remember { mutableStateOf("") }
    var showTbaExpanded by remember { mutableStateOf(false) }
    var showAboutPage by remember { mutableStateOf(false) }
    var recentlyDeletedItem by remember { mutableStateOf<TrackedItemEntity?>(null) }
    var recentlyMarkedRead by remember { mutableStateOf<MarkReadOutcome?>(null) }
    var checkingAbbAsins by remember { mutableStateOf<Set<String>>(emptySet()) }
    var actionSheetAsin by remember { mutableStateOf<String?>(null) }

    val upcomingListState = rememberLazyListState()
    val trackedListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()

    LaunchedEffect(trackedItems) {
        val currentFocus = focusedSourceItem
        if (currentFocus != null) {
            val refreshed = trackedItems.firstOrNull { it.id == currentFocus.id }
            focusedSourceItem = refreshed
        }
    }

    var statusTickerText by remember { mutableStateOf<String?>(null) }
    var statusTickerIsWarn by remember { mutableStateOf(false) }
    var statusTickerSeq by remember { mutableIntStateOf(0) }

    val postAsciiStatus: (String, Boolean) -> Unit = remember {
        { msg, isWarn ->
            statusTickerText = msg
            statusTickerIsWarn = isWarn
            statusTickerSeq += 1
        }
    }

    val openExternalUrl: (String, String) -> Unit = remember(context) {
        { url, errMsg ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                postAsciiStatus(errMsg, true)
            }
        }
    }

    LaunchedEffect(externalStatusMessage, externalStatusIsWarn) {
        if (!externalStatusMessage.isNullOrBlank()) {
            postAsciiStatus(externalStatusMessage, externalStatusIsWarn)
            onConsumeExternalStatus()
        }
    }

    LaunchedEffect(statusTickerSeq) {
        if (statusTickerText != null) {
            delay(3600L)
            statusTickerText = null
        }
    }

    var activeSharedQuery by remember { mutableStateOf<String?>(null) }
    var isResolvingShared by remember { mutableStateOf(false) }
    var sharedResolution by remember { mutableStateOf<SharedImportResolution?>(null) }

    LaunchedEffect(sharedImportText) {
        val incoming = sharedImportText?.trim()
        if (!incoming.isNullOrBlank()) {
            activeSharedQuery = incoming
            onConsumeSharedImport()
            isResolvingShared = true
            sharedResolution = null
            val resolved = AudibleApiClient.resolveSharedInput(incoming)
            sharedResolution = resolved
            isResolvingShared = false
            if (resolved.products.isEmpty()) {
                postAsciiStatus("WARN: NO CATALOG MATCH FOR SHARED LINK", true)
            } else {
                postAsciiStatus(
                    "SHARE IMPORT: ${resolved.products.size} MATCH(ES) FOR ${resolved.extractedAsin ?: resolved.extractedQuery.uppercase()}",
                    false
                )
            }
        }
    }

    LaunchedEffect(recentlyDeletedItem) {
        if (recentlyDeletedItem != null) {
            delay(5000L)
            recentlyDeletedItem = null
        }
    }

    LaunchedEffect(recentlyMarkedRead) {
        if (recentlyMarkedRead != null) {
            delay(5000L)
            recentlyMarkedRead = null
        }
    }

    val navigateToTab: (ActiveTab) -> Unit = { targetTab ->
        if (targetTab != activeTab) {
            if (targetTab == ActiveTab.UPCOMING) {
                lastNonHomeTab = null
            } else if (activeTab != ActiveTab.UPCOMING) {
                lastNonHomeTab = activeTab
            }
            if (targetTab != ActiveTab.SETTINGS) {
                showAboutPage = false
            }
            localFilterOpen = false
            localFilterQuery = ""
            activeTab = targetTab
        } else if (targetTab == ActiveTab.SETTINGS && showAboutPage) {
            showAboutPage = false
        } else {
            val targetList = when (targetTab) {
                ActiveTab.UPCOMING -> upcomingListState
                ActiveTab.TRACKED -> trackedListState
                ActiveTab.SETTINGS -> settingsListState
            }
            scope.launch { targetList.animateScrollToItem(0) }
        }
    }

    val shouldInterceptBack = actionSheetAsin != null ||
        activeSharedQuery != null ||
        localFilterOpen ||
        localFilterQuery.isNotBlank() ||
        showAboutPage ||
        focusedSourceItem != null ||
        activeTab != ActiveTab.UPCOMING ||
        activeFilter != QuickFilter.ALL

    BackHandler(enabled = shouldInterceptBack) {
        when {
            actionSheetAsin != null -> {
                actionSheetAsin = null
            }
            activeSharedQuery != null -> {
                activeSharedQuery = null
                sharedResolution = null
                isResolvingShared = false
            }
            localFilterOpen || localFilterQuery.isNotBlank() -> {
                localFilterOpen = false
                localFilterQuery = ""
            }
            showAboutPage -> {
                showAboutPage = false
            }
            focusedSourceItem != null -> {
                focusedSourceItem = null
                activeTab = ActiveTab.TRACKED
            }
            activeTab != ActiveTab.UPCOMING -> {
                val prev = lastNonHomeTab
                if (prev != null && prev != activeTab) {
                    lastNonHomeTab = null
                    activeTab = prev
                } else {
                    lastNonHomeTab = null
                    activeTab = ActiveTab.UPCOMING
                }
            }
            activeFilter != QuickFilter.ALL -> {
                activeFilter = QuickFilter.ALL
            }
        }
    }

    var today by remember { mutableStateOf(LocalDate.now()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = LocalDate.now()
                if (now != today) {
                    today = now
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val todayIso = remember(today) { today.toString() }

    LaunchedEffect(Unit) {
        repo.syncCatalog(forceRefresh = false)
    }

    val isBottomBarVisible = remember { mutableStateOf(true) }
    LaunchedEffect(statusTickerSeq, activeTab) {
        isBottomBarVisible.value = true
    }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f && isBottomBarVisible.value) {
                    isBottomBarVisible.value = false
                } else if (available.y > 8f && !isBottomBarVisible.value) {
                    isBottomBarVisible.value = true
                }
                return Offset.Zero
            }
        }
    }

    val handleAddTracked: (TrackType, String, Int, String, String?) -> Unit = remember(repo) {
        { type, query, lastRead, initialThumb, externalId ->
            scope.launch {
                val cleaned = query.trim()
                val added = repo.addTrackedItemUnique(type, cleaned, lastRead, initialThumb, externalId)
                if (added) {
                    postAsciiStatus("+ TRACKED ${type.name}: ${cleaned.uppercase()}", false)
                    repo.syncCatalog(forceRefresh = false)
                } else {
                    postAsciiStatus("ALREADY TRACKED: ${cleaned.uppercase()}", true)
                }
            }
        }
    }

    val unreadReleases = remember(releases, readAsins, readGroupKeys) {
        releases.filterNot { ReleaseRepository.isReleaseRead(it, readAsins, readGroupKeys) }
    }
    val upcomingCount = remember(unreadReleases, todayIso) {
        unreadReleases.count { it.releaseDateIso != "TBA" && it.releaseDateIso >= todayIso }
    }
    val abbAvailableCount = remember(unreadReleases) {
        unreadReleases.count { it.abbAvailable }
    }
    val pinnedActiveCount = remember(unreadReleases, pinnedAsins, pinnedGroupKeys) {
        unreadReleases.count { ReleaseRepository.isReleasePinned(it, pinnedAsins, pinnedGroupKeys) }
    }

    val handleRunManualAbbCheck: () -> Unit = remember(repo) {
        {
            if (!repo.isCheckingAbb.value) {
                scope.launch {
                    postAsciiStatus("CHECKING AUDIOBOOKBAY...", false)
                    val newlyFound = repo.runManualAbbCheck()
                    postAsciiStatus(
                        if (newlyFound > 0) "ABB CHECK: +$newlyFound NEWLY VERIFIED" else "ABB CHECK COMPLETE (0 NEW)",
                        false
                    )
                }
            }
        }
    }

    val trackedKeySet = remember(trackedItems) {
        trackedItems.map { "${it.type.name}:${it.queryValue.trim().lowercase(Locale.US)}" }.toSet()
    }

    val syncSpinner = rememberAsciiSpinner(active = isSyncing)

    CompositionLocalProvider(LocalPtsPalette provides palette) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .systemBarsPadding()
                .nestedScroll(nestedScrollConnection)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = if (activeTab == ActiveTab.SETTINGS && showAboutPage) "~/nocturnetxt/about" else "~/nocturnetxt/${activeTab.name.lowercase(Locale.US)}",
                        color = palette.comment,
                        fontSizeSp = 13,
                        bold = true
                    )
                    if (activeTab != ActiveTab.SETTINGS) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AsciiInvertButton(
                                label = if (localFilterQuery.isNotBlank()) "/:${localFilterQuery.take(6)}" else "/",
                                active = localFilterOpen || localFilterQuery.isNotBlank(),
                                color = palette.cyan,
                                onClick = {
                                    if (localFilterOpen) {
                                        localFilterOpen = false
                                        localFilterQuery = ""
                                    } else {
                                        localFilterOpen = true
                                    }
                                }
                            )
                            val syncLabel = if (isSyncing) {
                                if (syncProgress.total > 0) {
                                    "$syncSpinner SYNC ${syncProgress.done}/${syncProgress.total}"
                                } else {
                                    "$syncSpinner SYNCING"
                                }
                            } else {
                                "SYNC"
                            }
                            BracketButton(
                                label = syncLabel,
                                color = palette.green,
                                onClick = {
                                    if (!isSyncing) {
                                        scope.launch {
                                            repo.syncCatalog(forceRefresh = true)
                                            val latestTelemetry = repo.telemetryFlow.value
                                            postAsciiStatus(
                                                "SYNC COMPLETE (${latestTelemetry.statusSummary})",
                                                latestTelemetry.lastSyncErrorCount > 0
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                AsciiRule(pattern = "- ")
                Spacer(modifier = Modifier.height(4.dp))

                if (localFilterOpen && activeTab != ActiveTab.SETTINGS) {
                    val focusManager = LocalFocusManager.current
                    val keyboardController = LocalSoftwareKeyboardController.current
                    AsciiTextField(
                        value = localFilterQuery,
                        onValueChange = { localFilterQuery = it },
                        placeholder = if (activeTab == ActiveTab.UPCOMING) {
                            "Filter title, series, author, narrator..."
                        } else {
                            "Filter followed sources..."
                        },
                        imeAction = ImeAction.Search,
                        onImeAction = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (activeSharedQuery != null) {
                    SharedImportAsciiPanel(
                        rawInput = activeSharedQuery.orEmpty(),
                        isResolving = isResolvingShared,
                        resolution = sharedResolution,
                        trackedKeySet = trackedKeySet,
                        hideCoverArt = prefs.hideCoverArt,
                        onTrack = { type, valStr, thumb, ext ->
                            handleAddTracked(type, valStr, 0, thumb, ext)
                        },
                        onOpenAbbQuery = { rawQuery ->
                            val q = URLEncoder.encode(rawQuery.trim().lowercase(), "UTF-8")
                            val abbUrl = "https://audiobookbay.lu/?s=$q&cat=undefined%2Cundefined"
                            openExternalUrl(abbUrl, "WARN: NO BROWSER APP FOUND")
                        },
                        onClose = {
                            activeSharedQuery = null
                            sharedResolution = null
                            isResolvingShared = false
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (activeTab) {
                        ActiveTab.UPCOMING -> UpcomingFeedView(
                            listState = upcomingListState,
                            today = today,
                            releases = releases,
                            trackedItems = trackedItems,
                            dndRules = dndRules,
                            readBooks = readBooks,
                            readAsins = readAsins,
                            readGroupKeys = readGroupKeys,
                            pinnedAsins = pinnedAsins,
                            pinnedGroupKeys = pinnedGroupKeys,
                            quickFilter = activeFilter,
                            localFilterQuery = localFilterQuery,
                            focusedSourceItem = focusedSourceItem,
                            recentlyMarkedRead = recentlyMarkedRead,
                            onClearFocusedSource = { focusedSourceItem = null },
                            alwaysShowCardActions = prefs.alwaysShowCardActions,
                            hideCoverArt = prefs.hideCoverArt,
                            showTbaExpanded = showTbaExpanded,
                            checkingAbbAsins = checkingAbbAsins,
                            isCheckingAbbBatch = isCheckingAbbBatch,
                            onToggleTba = { showTbaExpanded = !showTbaExpanded },
                            onRunManualAbbCheck = handleRunManualAbbCheck,
                            onClearNewBadges = {
                                scope.launch {
                                    repo.dao.clearNewDiscoveryFlags()
                                    postAsciiStatus("OK: NEW DISCOVERY BADGES CLEARED", false)
                                }
                            },
                            onOpenAudibleSearch = { card ->
                                val domain = prefs.marketplace.domain
                                val q = URLEncoder.encode("${card.entity.title} ${card.primaryAuthor}".trim(), "UTF-8")
                                val url = "https://www.$domain/search?keywords=$q&ipRedirectOverride=true&overrideBaseCountry=true"
                                openExternalUrl(url, "WARN: NO BROWSER APP FOUND")
                            },
                            onOpenAudioBookBay = { card ->
                                val directUrl = card.entity.abbPostUrl
                                val abbUrl = if (directUrl.startsWith("https://")) {
                                    directUrl
                                } else {
                                    val q = URLEncoder.encode("${card.entity.title} ${card.primaryAuthor}".trim().lowercase(), "UTF-8")
                                    "https://audiobookbay.lu/?s=$q&cat=undefined%2Cundefined"
                                }
                                openExternalUrl(abbUrl, "WARN: NO BROWSER APP FOUND")
                            },
                            onOpenOrCopyMagnet = { card ->
                                val magnet = card.entity.abbMagnetUri
                                if (magnet.startsWith("magnet:")) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("ABB Magnet URI", magnet))
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(magnet)))
                                    }.onFailure {
                                        postAsciiStatus("OK: MAGNET URI COPIED TO CLIPBOARD", false)
                                    }
                                }
                            },
                            onCopyRelease = { card ->
                                val rel = card.entity
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val seriesPart = if (rel.seriesTitle.isNotBlank()) {
                                    " (${rel.seriesTitle}${rel.seriesSequence?.let { " #$it" }.orEmpty()})"
                                } else ""
                                val summary = "${rel.title}$seriesPart | By ${rel.authors} | Narrated by ${rel.narrators} | Release: ${rel.releaseDateIso}"
                                clipboard.setPrimaryClip(ClipData.newPlainText("Audiobook Release", summary))
                                postAsciiStatus("OK: RELEASE DETAILS COPIED TO CLIPBOARD", false)
                            },
                            onExportCalendar = { card ->
                                val rel = card.entity
                                val date = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
                                if (date != null) {
                                    val startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                                    val calIntent = Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.Events.TITLE, "Audiobook Release: ${rel.title}")
                                        putExtra(
                                            CalendarContract.Events.DESCRIPTION,
                                            "By ${rel.authors} | Narrated by ${rel.narrators} | Series: ${rel.seriesTitle} | ASIN: ${rel.asin}"
                                        )
                                        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + 86_400_000L)
                                    }
                                    runCatching { context.startActivity(calIntent) }.onFailure {
                                        postAsciiStatus("WARN: NO CALENDAR APP FOUND", true)
                                    }
                                }
                            },
                            onQuickTrack = { type, value, thumbUrl, extId ->
                                handleAddTracked(type, value, 0, thumbUrl, extId)
                            },
                            onArchiveTrackedBook = { trackedBook ->
                                scope.launch {
                                    recentlyDeletedItem = trackedBook
                                    repo.dao.deleteTrackedItem(trackedBook)
                                    repo.syncCatalog(forceRefresh = false)
                                    postAsciiStatus("ARCHIVED BOOK: ${trackedBook.queryValue.uppercase()}", false)
                                }
                            },
                            onMuteBook = { card ->
                                scope.launch {
                                    repo.muteBook(card.entity.asin, card.entity.title)
                                    postAsciiStatus("DND MUTED BOOK: ${card.upperTitle}", true)
                                }
                            },
                            onMuteSeries = { card ->
                                scope.launch {
                                    repo.muteSeries(card.entity.seriesTitle, card.entity.seriesTitle)
                                    postAsciiStatus("DND MUTED SERIES: ${card.entity.seriesTitle.uppercase()}", true)
                                }
                            },
                            onUnmuteRule = { rule ->
                                scope.launch {
                                    repo.dao.deleteDndRule(rule)
                                    repo.syncCatalog(forceRefresh = false)
                                    postAsciiStatus("UNMUTED: ${rule.displayTitle.uppercase()}", false)
                                }
                            },
                            onMarkSeriesSeqRead = { trackedSeries, seq ->
                                scope.launch {
                                    repo.dao.updateSeriesReadSequence(trackedSeries.id, seq)
                                    postAsciiStatus("MARKED ${trackedSeries.queryValue.uppercase()} #$seq READ", false)
                                }
                            },
                            onAutoCheckAbb = { cardItem ->
                                val asin = cardItem.entity.asin
                                if (asin !in checkingAbbAsins) {
                                    scope.launch {
                                        checkingAbbAsins = checkingAbbAsins + asin
                                        val found = try {
                                            repo.checkSingleReleaseOnAbb(cardItem.entity)
                                        } finally {
                                            checkingAbbAsins = checkingAbbAsins - asin
                                        }
                                        if (found) {
                                            postAsciiStatus("ABB VERIFIED: ${cardItem.upperTitle}", false)
                                        } else {
                                            postAsciiStatus("ABB: NOT FOUND YET", true)
                                        }
                                    }
                                }
                            },
                            onMarkRead = { cardItem ->
                                scope.launch {
                                    val outcome = repo.markReleaseRead(cardItem.entity)
                                    recentlyMarkedRead = outcome
                                    postAsciiStatus("MARKED READ: ${cardItem.upperTitle}", false)
                                }
                            },
                            onUndoMarkRead = { outcome ->
                                scope.launch {
                                    recentlyMarkedRead = null
                                    repo.undoMarkRead(outcome)
                                    postAsciiStatus("UNDID READ: ${outcome.readBook.title.uppercase()}", false)
                                }
                            },
                            onUnmarkReadBook = { readBook ->
                                scope.launch {
                                    repo.unmarkReadBook(readBook)
                                    postAsciiStatus("UNMARKED READ: ${readBook.title.uppercase()}", false)
                                }
                            },
                            onTogglePin = { cardItem ->
                                scope.launch {
                                    val nowPinned = repo.togglePinRelease(cardItem.entity)
                                    postAsciiStatus(
                                        if (nowPinned) "* PINNED: ${cardItem.upperTitle}" else "UNPINNED: ${cardItem.upperTitle}",
                                        false
                                    )
                                }
                            },
                            onOpenActionMenu = { cardItem ->
                                actionSheetAsin = cardItem.entity.asin
                            }
                        )

                        ActiveTab.TRACKED -> TrackedEntitiesView(
                            listState = trackedListState,
                            today = today,
                            trackedItems = trackedItems,
                            releases = releases,
                            readAsins = readAsins,
                            readGroupKeys = readGroupKeys,
                            localFilterQuery = localFilterQuery,
                            sourceFilter = trackedSourceFilter,
                            sortOrder = trackedSortOrder,
                            hideCoverArt = prefs.hideCoverArt,
                            recentlyDeletedItem = recentlyDeletedItem,
                            onAddTracked = handleAddTracked,
                            onSelectSourceForFilter = { item ->
                                focusedSourceItem = item
                                activeFilter = QuickFilter.ALL
                                lastNonHomeTab = ActiveTab.TRACKED
                                localFilterOpen = false
                                localFilterQuery = ""
                                activeTab = ActiveTab.UPCOMING
                            },
                            onEditTracked = { item, newQuery, refreshMeta ->
                                scope.launch {
                                    val ok = repo.editTrackedItemAndRefresh(item, newQuery, refreshMeta)
                                    if (ok) {
                                        postAsciiStatus(
                                            if (refreshMeta) "REFRESHING META: ${newQuery.trim().uppercase()}"
                                            else "UPDATED ${item.type.name}: ${newQuery.trim().uppercase()}",
                                            false
                                        )
                                        repo.syncCatalog(forceRefresh = refreshMeta)
                                    } else {
                                        postAsciiStatus("WARN: INVALID OR DUPLICATE NAME", true)
                                    }
                                }
                            },
                            onDeleteTracked = { item ->
                                scope.launch {
                                    recentlyDeletedItem = item
                                    repo.dao.deleteTrackedItem(item)
                                    repo.syncCatalog(forceRefresh = false)
                                }
                            },
                            onUndoDelete = { restored ->
                                scope.launch {
                                    recentlyDeletedItem = null
                                    repo.dao.insertTrackedItem(restored)
                                    repo.syncCatalog(forceRefresh = false)
                                    postAsciiStatus("RESTORED ${restored.type.name}: ${restored.queryValue.uppercase()}", false)
                                }
                            },
                            onUpdateSeriesSeq = { item, newSeq ->
                                scope.launch {
                                    repo.dao.updateSeriesReadSequence(item.id, newSeq)
                                    postAsciiStatus("${item.queryValue.uppercase()} LAST READ -> #$newSeq", false)
                                }
                            },
                            onOpenAbbQuery = { rawQuery ->
                                val q = URLEncoder.encode(rawQuery.trim().lowercase(), "UTF-8")
                                val abbUrl = "https://audiobookbay.lu/?s=$q&cat=undefined%2Cundefined"
                                openExternalUrl(abbUrl, "WARN: NO BROWSER APP FOUND")
                            }
                        )

                        ActiveTab.SETTINGS -> {
                            if (showAboutPage) {
                                AboutPageView(onBack = { showAboutPage = false })
                            } else {
                                SettingsAndBackupView(
                                    listState = settingsListState,
                                    prefs = prefs,
                                    syncTelemetry = syncTelemetry,
                                    isSyncing = isSyncing,
                                    isCheckingAbbBatch = isCheckingAbbBatch,
                                    syncProgress = syncProgress,
                                    onRunManualAbbCheck = handleRunManualAbbCheck,
                                    onUpdatePrefs = { updated ->
                                        val prev = repo.prefsFlow.value
                                        repo.savePrefs(updated)
                                        if (updated.notifyHour != prev.notifyHour ||
                                            updated.notifyMinute != prev.notifyMinute
                                        ) {
                                            ReleaseCheckWorker.scheduleDailyCheck(
                                                context,
                                                updated.notifyHour,
                                                updated.notifyMinute
                                            )
                                        }
                                        val marketplaceChanged = updated.marketplaceCode != prev.marketplaceCode
                                        val catalogPrefsChanged = marketplaceChanged ||
                                            updated.englishOnly != prev.englishOnly ||
                                            updated.hideAbridged != prev.hideAbridged ||
                                            updated.hideDramatized != prev.hideDramatized
                                        if (catalogPrefsChanged) {
                                            scope.launch {
                                                repo.syncCatalog(forceRefresh = marketplaceChanged)
                                            }
                                        }
                                    },
                                    onPostStatus = postAsciiStatus,
                                    onOpenAbout = { showAboutPage = true },
                                    onExportBackup = onExportBackup,
                                    onImportBackup = onImportBackup
                                )
                            }
                        }
                    }
                }
            }

            BottomThumbBar(
                visibleState = isBottomBarVisible,
                activeTab = activeTab,
                activeFilter = activeFilter,
                trackedSourceFilter = trackedSourceFilter,
                trackedSortOrder = trackedSortOrder,
                upcomingCount = upcomingCount,
                trackedCount = trackedItems.size,
                pinnedCount = pinnedActiveCount,
                abbCount = abbAvailableCount,
                readCount = readBooks.size,
                dndCount = dndRules.size,
                statusTickerText = statusTickerText,
                statusTickerIsWarn = statusTickerIsWarn,
                onDismissStatusTicker = { statusTickerText = null },
                onSelectFilter = {
                    focusedSourceItem = null
                    activeFilter = it
                },
                onSelectTrackedFilter = { trackedSourceFilter = it },
                onSelectTrackedSort = { trackedSortOrder = it },
                onSelectTab = navigateToTab,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            val sheetRelease = remember(actionSheetAsin, releases, readAsins, readGroupKeys) {
                val targetAsin = actionSheetAsin ?: return@remember null
                releases.firstOrNull { rel ->
                    rel.asin == targetAsin && !ReleaseRepository.isReleaseRead(rel, readAsins, readGroupKeys)
                }
            }
            if (sheetRelease != null) {
                ReleaseActionSheet(
                    rel = sheetRelease,
                    trackedItems = trackedItems,
                    pinnedAsins = pinnedAsins,
                    pinnedGroupKeys = pinnedGroupKeys,
                    onTogglePin = {
                        scope.launch {
                            val nowPinned = repo.togglePinRelease(sheetRelease)
                            val titleUp = sheetRelease.title.replace(marketingSubtitleRegex, "").trim().ifBlank { sheetRelease.title }.uppercase()
                            postAsciiStatus(
                                if (nowPinned) "* PINNED: $titleUp" else "UNPINNED: $titleUp",
                                false
                            )
                        }
                    },
                    onQuickTrack = { type, value, thumbUrl, extId ->
                        handleAddTracked(type, value, 0, thumbUrl, extId)
                    },
                    onOpenOrCopyMagnet = {
                        val magnet = sheetRelease.abbMagnetUri
                        if (magnet.startsWith("magnet:")) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ABB Magnet URI", magnet))
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(magnet)))
                            }.onFailure {
                                postAsciiStatus("OK: MAGNET URI COPIED TO CLIPBOARD", false)
                            }
                        }
                        actionSheetAsin = null
                    },
                    onCopyRelease = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val seriesPart = if (sheetRelease.seriesTitle.isNotBlank()) {
                            " (${sheetRelease.seriesTitle}${sheetRelease.seriesSequence?.let { " #$it" }.orEmpty()})"
                        } else ""
                        val summary = "${sheetRelease.title}$seriesPart | By ${sheetRelease.authors} | Narrated by ${sheetRelease.narrators} | Release: ${sheetRelease.releaseDateIso}"
                        clipboard.setPrimaryClip(ClipData.newPlainText("Audiobook Release", summary))
                        postAsciiStatus("OK: RELEASE DETAILS COPIED TO CLIPBOARD", false)
                        actionSheetAsin = null
                    },
                    onExportCalendar = {
                        val date = runCatching { LocalDate.parse(sheetRelease.releaseDateIso) }.getOrNull()
                        if (date != null) {
                            val startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                            val calIntent = Intent(Intent.ACTION_INSERT).apply {
                                data = CalendarContract.Events.CONTENT_URI
                                putExtra(CalendarContract.Events.TITLE, "Audiobook Release: ${sheetRelease.title}")
                                putExtra(
                                    CalendarContract.Events.DESCRIPTION,
                                    "By ${sheetRelease.authors} | Narrated by ${sheetRelease.narrators} | Series: ${sheetRelease.seriesTitle} | ASIN: ${sheetRelease.asin}"
                                )
                                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + 86_400_000L)
                            }
                            runCatching { context.startActivity(calIntent) }.onFailure {
                                postAsciiStatus("WARN: NO CALENDAR APP FOUND", true)
                            }
                        }
                        actionSheetAsin = null
                    },
                    onMuteBook = {
                        actionSheetAsin = null
                        scope.launch {
                            repo.muteBook(sheetRelease.asin, sheetRelease.title)
                            postAsciiStatus("DND MUTED BOOK: ${sheetRelease.title.uppercase()}", true)
                        }
                    },
                    onMuteSeries = {
                        actionSheetAsin = null
                        scope.launch {
                            repo.muteSeries(sheetRelease.seriesTitle, sheetRelease.seriesTitle)
                            postAsciiStatus("DND MUTED SERIES: ${sheetRelease.seriesTitle.uppercase()}", true)
                        }
                    },
                    onDismiss = { actionSheetAsin = null }
                )
            }
        }
    }
}

@Composable
private fun ReleaseActionSheet(
    rel: ReleaseEntity,
    trackedItems: List<TrackedItemEntity>,
    pinnedAsins: Set<String>,
    pinnedGroupKeys: Set<String>,
    onTogglePin: () -> Unit,
    onQuickTrack: (TrackType, String, String, String?) -> Unit,
    onOpenOrCopyMagnet: () -> Unit,
    onCopyRelease: () -> Unit,
    onExportCalendar: () -> Unit,
    onMuteBook: () -> Unit,
    onMuteSeries: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalPtsPalette.current
    val cleanedTitle = remember(rel.title) {
        rel.title.replace(marketingSubtitleRegex, "").trim().ifBlank { rel.title }.uppercase()
    }
    val isPinned = remember(rel, pinnedAsins, pinnedGroupKeys) {
        ReleaseRepository.isReleasePinned(rel, pinnedAsins, pinnedGroupKeys)
    }

    val primaryAuthor = remember(rel.authors) {
        rel.authors.split(",").firstOrNull()?.trim().orEmpty()
    }
    val primaryNarrator = remember(rel.narrators) {
        rel.narrators.split(",").firstOrNull()?.trim()
            ?.takeIf { !it.equals("TBA", ignoreCase = true) }.orEmpty()
    }

    val isSeriesTracked = remember(rel, trackedItems) {
        rel.seriesTitle.isNotBlank() && trackedItems.any { s ->
            s.type == TrackType.SERIES && (
                (s.pinnedAsin.isNotBlank() && s.pinnedAsin.equals(rel.seriesAsin, ignoreCase = true)) ||
                    rel.seriesTitle.equals(s.queryValue, ignoreCase = true) ||
                    (s.pinnedAsin.isBlank() && rel.seriesTitle.contains(s.queryValue, ignoreCase = true))
                )
        }
    }
    val isAuthorTracked = remember(primaryAuthor, trackedItems) {
        primaryAuthor.isNotBlank() && trackedItems.any { a ->
            a.type == TrackType.AUTHOR && AudibleApiClient.isExactAuthorNameMatch(a.queryValue, primaryAuthor)
        }
    }
    val isNarratorTracked = remember(primaryNarrator, trackedItems) {
        primaryNarrator.isNotBlank() && trackedItems.any { n ->
            n.type == TrackType.NARRATOR && AudibleApiClient.isExactAuthorNameMatch(n.queryValue, primaryNarrator)
        }
    }

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AsciiBox(
                headerLabel = cleanedTitle,
                borderColor = palette.cyan,
                headerColor = palette.cyan
            ) {
                AsciiToggleRow(
                    label = "Pin Release",
                    checked = isPinned,
                    onToggle = onTogglePin
                )

                if (rel.seriesTitle.isNotBlank()) {
                    AsciiActionRow(
                        label = "Follow Series: ${rel.seriesTitle}",
                        token = if (isSeriesTracked) "[x]" else "[+]",
                        labelColor = palette.foreground,
                        tokenColor = if (isSeriesTracked) palette.green else palette.purple,
                        enabled = !isSeriesTracked,
                        onClick = {
                            val ext = "${rel.seriesAsin}|$primaryAuthor"
                            onQuickTrack(TrackType.SERIES, rel.seriesTitle, rel.coverUrl, ext)
                        }
                    )
                }

                if (primaryAuthor.isNotBlank()) {
                    AsciiActionRow(
                        label = "Follow Author: $primaryAuthor",
                        token = if (isAuthorTracked) "[x]" else "[+]",
                        labelColor = palette.foreground,
                        tokenColor = if (isAuthorTracked) palette.green else palette.yellow,
                        enabled = !isAuthorTracked,
                        onClick = {
                            onQuickTrack(TrackType.AUTHOR, primaryAuthor, rel.coverUrl, null)
                        }
                    )
                }

                if (primaryNarrator.isNotBlank()) {
                    AsciiActionRow(
                        label = "Follow Narrator: $primaryNarrator",
                        token = if (isNarratorTracked) "[x]" else "[+]",
                        labelColor = palette.foreground,
                        tokenColor = if (isNarratorTracked) palette.green else palette.orange,
                        enabled = !isNarratorTracked,
                        onClick = {
                            onQuickTrack(TrackType.NARRATOR, primaryNarrator, rel.coverUrl, null)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                AsciiRule(pattern = "- ")
                Spacer(modifier = Modifier.height(2.dp))

                if (rel.abbMagnetUri.startsWith("magnet:")) {
                    AsciiActionRow(
                        label = "Open / Copy ABB Magnet",
                        token = "[>]",
                        labelColor = palette.green,
                        tokenColor = palette.green,
                        onClick = onOpenOrCopyMagnet
                    )
                }

                AsciiActionRow(
                    label = "Copy Release Details",
                    token = "[>]",
                    labelColor = palette.foreground,
                    tokenColor = palette.cyan,
                    onClick = onCopyRelease
                )

                if (rel.releaseDateIso != "TBA") {
                    AsciiActionRow(
                        label = "Export to Calendar",
                        token = "[>]",
                        labelColor = palette.foreground,
                        tokenColor = palette.yellow,
                        onClick = onExportCalendar
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                AsciiRule(pattern = "- ")
                Spacer(modifier = Modifier.height(2.dp))

                AsciiActionRow(
                    label = "Mute Book (DND)",
                    token = "[!]",
                    labelColor = palette.red,
                    tokenColor = palette.red,
                    hapticType = HapticFeedbackType.LongPress,
                    onClick = onMuteBook
                )

                if (rel.seriesTitle.isNotBlank()) {
                    AsciiActionRow(
                        label = "Mute Series (DND)",
                        token = "[!]",
                        labelColor = palette.red,
                        tokenColor = palette.red,
                        hapticType = HapticFeedbackType.LongPress,
                        onClick = onMuteSeries
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                AsciiRule(pattern = "- ")
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BracketButton(
                        label = "CLOSE x",
                        color = palette.comment,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedImportAsciiPanel(
    rawInput: String,
    isResolving: Boolean,
    resolution: SharedImportResolution?,
    trackedKeySet: Set<String>,
    hideCoverArt: Boolean,
    onTrack: (TrackType, String, String, String?) -> Unit,
    onOpenAbbQuery: (String) -> Unit,
    onClose: () -> Unit
) {
    val palette = LocalPtsPalette.current
    val spinner = rememberAsciiSpinner(active = isResolving)

    val headerTag = resolution?.extractedAsin?.let { "ASIN $it" } ?: "SHARED LINK"
    AsciiBox(
        headerLabel = "SHARE IMPORT | $headerTag",
        borderColor = palette.cyan,
        headerColor = palette.cyan
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoText(
                text = if (isResolving) {
                    "[ $spinner RESOLVING AUDIBLE METADATA... ]"
                } else {
                    "SRC: ${rawInput.take(64)}"
                },
                color = if (isResolving) palette.cyan else palette.comment,
                fontSizeSp = 11,
                bold = isResolving,
                modifier = Modifier.weight(1f)
            )
            BracketButton(
                label = "CLOSE x",
                color = palette.red,
                onClick = onClose
            )
        }

        if (!isResolving && resolution != null) {
            if (resolution.products.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                MonoText(
                    text = "! Could not find matching Audible product for '${resolution.extractedQuery}'.",
                    color = palette.orange,
                    fontSizeSp = 12
                )
            } else {
                val topMatches = resolution.products.take(3)
                topMatches.forEachIndexed { idx, prod ->
                    Spacer(modifier = Modifier.height(4.dp))
                    if (idx > 0) {
                        AsciiRule(pattern = "- ")
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    val seriesTracked = prod.seriesTitle.isNotBlank() &&
                        "SERIES:${prod.seriesTitle.trim().lowercase(Locale.US)}" in trackedKeySet
                    val firstAuthor = prod.authors.firstOrNull()?.trim().orEmpty()
                    val authorTracked = firstAuthor.isNotBlank() &&
                        "AUTHOR:${firstAuthor.lowercase(Locale.US)}" in trackedKeySet
                    val firstNarrator = prod.narrators.firstOrNull()?.trim().orEmpty()
                    val narratorTracked = firstNarrator.isNotBlank() &&
                        "NARRATOR:${firstNarrator.lowercase(Locale.US)}" in trackedKeySet
                    val bookTracked = "BOOK:${prod.asin.lowercase(Locale.US)}" in trackedKeySet ||
                        "BOOK:${prod.title.trim().lowercase(Locale.US)}" in trackedKeySet

                    Row(verticalAlignment = Alignment.Top) {
                        if (!hideCoverArt) {
                            CoverThumbnail(
                                coverUrl = prod.coverUrl,
                                sizeDp = StandardThumbnailSize,
                                borderColor = palette.cyan
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            MonoText(
                                text = prod.title.uppercase(),
                                color = palette.foreground,
                                fontSizeSp = 13,
                                bold = true
                            )
                            if (prod.seriesTitle.isNotBlank()) {
                                val seqPart = prod.seriesSequence?.let { " #$it" }.orEmpty()
                                AsciiFieldRow("SER", "${prod.seriesTitle}$seqPart", palette.purple, 11)
                            }
                            AsciiFieldRow(
                                "AUT",
                                "${prod.authors.joinToString(", ")} | ${prod.releaseDateIso}",
                                palette.foreground,
                                11
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prod.seriesTitle.isNotBlank()) {
                            BracketButton(
                                label = if (seriesTracked) "[x] Series" else "[+ Series]",
                                active = seriesTracked,
                                color = palette.purple,
                                onClick = {
                                    onTrack(
                                        TrackType.SERIES,
                                        prod.seriesTitle,
                                        prod.coverUrl,
                                        "${prod.seriesAsin}|$firstAuthor"
                                    )
                                }
                            )
                        }
                        if (firstAuthor.isNotBlank()) {
                            BracketButton(
                                label = if (authorTracked) "[x] Author" else "[+ Author]",
                                active = authorTracked,
                                color = palette.yellow,
                                onClick = {
                                    onTrack(
                                        TrackType.AUTHOR,
                                        firstAuthor,
                                        prod.coverUrl,
                                        prod.primaryAuthorAsin.ifBlank { null }
                                    )
                                }
                            )
                        }
                        if (firstNarrator.isNotBlank()) {
                            BracketButton(
                                label = if (narratorTracked) "[x] Narrator" else "[+ Narrator]",
                                active = narratorTracked,
                                color = palette.orange,
                                onClick = {
                                    onTrack(TrackType.NARRATOR, firstNarrator, prod.coverUrl, null)
                                }
                            )
                        }
                        BracketButton(
                            label = if (bookTracked) "[x] Book" else "[+ Book]",
                            active = bookTracked,
                            color = palette.green,
                            onClick = {
                                onTrack(
                                    TrackType.BOOK,
                                    prod.title,
                                    prod.coverUrl,
                                    "${prod.asin}|$firstAuthor"
                                )
                            }
                        )
                        BracketButton(
                            label = "ABB",
                            color = palette.orange,
                            onClick = { onOpenAbbQuery("${prod.title} $firstAuthor") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomThumbBar(
    visibleState: State<Boolean>,
    activeTab: ActiveTab,
    activeFilter: QuickFilter,
    trackedSourceFilter: TrackedSourceFilter,
    trackedSortOrder: TrackedSortOrder,
    upcomingCount: Int,
    trackedCount: Int,
    pinnedCount: Int,
    abbCount: Int,
    readCount: Int,
    dndCount: Int,
    statusTickerText: String?,
    statusTickerIsWarn: Boolean,
    onDismissStatusTicker: () -> Unit,
    onSelectFilter: (QuickFilter) -> Unit,
    onSelectTrackedFilter: (TrackedSourceFilter) -> Unit,
    onSelectTrackedSort: (TrackedSortOrder) -> Unit,
    onSelectTab: (ActiveTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    val density = LocalDensity.current
    val defaultOffsetPx = remember(density) { with(density) { 140.dp.toPx() } }
    var measuredBarHeightPx by remember { mutableIntStateOf(0) }
    val hideOffsetPx = if (measuredBarHeightPx > 0) measuredBarHeightPx.toFloat() else defaultOffsetPx

    val translationY by animateFloatAsState(
        targetValue = if (visibleState.value) 0f else hideOffsetPx,
        animationSpec = tween(durationMillis = 180),
        label = "bottomBarSlide"
    )

    Surface(
        color = palette.background,
        border = BorderStroke(1.dp, palette.comment.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { measuredBarHeightPx = it.height }
            .graphicsLayer {
                this.translationY = translationY
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!statusTickerText.isNullOrBlank()) {
                val tickerColor = if (statusTickerIsWarn) palette.orange else palette.green
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceLine.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "> $statusTickerText",
                        color = tickerColor,
                        fontSizeSp = 11,
                        bold = true,
                        modifier = Modifier.weight(1f)
                    )
                    BracketButton(
                        label = "x",
                        color = palette.comment,
                        onClick = onDismissStatusTicker
                    )
                }
                AsciiRule(pattern = "-")
            }

            if (activeTab == ActiveTab.UPCOMING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(text = "FLT:", color = palette.comment, fontSizeSp = 11)
                    QuickFilter.values().forEach { filter ->
                        val chipLabel = when (filter) {
                            QuickFilter.PINNED -> "PIN:$pinnedCount"
                            QuickFilter.ABB -> "ABB:$abbCount"
                            QuickFilter.READ -> "READ:$readCount"
                            QuickFilter.DND -> "DND:$dndCount"
                            else -> filter.label
                        }
                        val chipColor = when (filter) {
                            QuickFilter.PINNED -> palette.yellow
                            QuickFilter.ABB -> palette.green
                            QuickFilter.READ -> palette.cyan
                            QuickFilter.DND -> palette.red
                            QuickFilter.RECENT_30D -> palette.orange
                            else -> palette.purple
                        }
                        AsciiInvertButton(
                            label = chipLabel,
                            active = activeFilter == filter,
                            color = chipColor,
                            onClick = { onSelectFilter(filter) }
                        )
                    }
                }
                AsciiRule(pattern = "-")
            } else if (activeTab == ActiveTab.TRACKED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(text = "FLT:", color = palette.comment, fontSizeSp = 11)
                    TrackedSourceFilter.values().forEach { tf ->
                        AsciiInvertButton(
                            label = tf.label,
                            active = trackedSourceFilter == tf,
                            color = palette.purple,
                            onClick = { onSelectTrackedFilter(tf) }
                        )
                    }
                    MonoText(text = "| SRT:", color = palette.comment, fontSizeSp = 11)
                    TrackedSortOrder.values().forEach { so ->
                        AsciiInvertButton(
                            label = so.label,
                            active = trackedSortOrder == so,
                            color = palette.cyan,
                            onClick = { onSelectTrackedSort(so) }
                        )
                    }
                }
                AsciiRule(pattern = "-")
            }

            // Numbered, inverse-video terminal navigation bar
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val navCols = rememberAsciiCols(fontSizeSp = 12, bold = true)
                val fullUp = "1:UPCOMING($upcomingCount)"
                val fullTrk = "2:TRACKED($trackedCount)"
                val fullSet = "3:SET"
                val useCompactNav = (fullUp.length + fullTrk.length + fullSet.length + 10) > navCols
                val upLabel = if (useCompactNav) "1:UP($upcomingCount)" else fullUp
                val trkLabel = if (useCompactNav) "2:TRK($trackedCount)" else fullTrk
                val navFontSp = if (useCompactNav && navCols < 30) 11 else 12

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsciiInvertButton(
                        label = upLabel,
                        active = activeTab == ActiveTab.UPCOMING,
                        color = palette.cyan,
                        fontSizeSp = navFontSp,
                        onClick = { onSelectTab(ActiveTab.UPCOMING) }
                    )
                    AsciiInvertButton(
                        label = trkLabel,
                        active = activeTab == ActiveTab.TRACKED,
                        color = palette.cyan,
                        fontSizeSp = navFontSp,
                        onClick = { onSelectTab(ActiveTab.TRACKED) }
                    )
                    AsciiInvertButton(
                        label = fullSet,
                        active = activeTab == ActiveTab.SETTINGS,
                        color = palette.cyan,
                        fontSizeSp = navFontSp,
                        onClick = { onSelectTab(ActiveTab.SETTINGS) }
                    )
                }
            }
        }
    }
}

private val marketingSubtitleRegex = Regex(
    ":\\s*(?:a\\s+|an\\s+)?(?:[a-z-]+\\s+){0,3}(?:litrpg|progression\\s+fantasy|xianxia|cultivation|wuxia|isekai|sci-fi|fantasy|gamelit|portal\\s+fantasy|dungeon\\s+core)(?:\\s+[a-z-]+){0,3}\\s*(?:adventure|epic|novel|series|saga|story|tale|book|fantasy).*$",
    RegexOption.IGNORE_CASE
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingFeedView(
    listState: LazyListState,
    today: LocalDate,
    releases: List<ReleaseEntity>,
    trackedItems: List<TrackedItemEntity>,
    dndRules: List<DndRuleEntity>,
    readBooks: List<ReadBookEntity>,
    readAsins: Set<String>,
    readGroupKeys: Set<String>,
    pinnedAsins: Set<String>,
    pinnedGroupKeys: Set<String>,
    quickFilter: QuickFilter,
    localFilterQuery: String,
    focusedSourceItem: TrackedItemEntity?,
    recentlyMarkedRead: MarkReadOutcome?,
    onClearFocusedSource: () -> Unit,
    alwaysShowCardActions: Boolean,
    hideCoverArt: Boolean,
    showTbaExpanded: Boolean,
    checkingAbbAsins: Set<String>,
    isCheckingAbbBatch: Boolean,
    onToggleTba: () -> Unit,
    onRunManualAbbCheck: () -> Unit,
    onClearNewBadges: () -> Unit,
    onOpenAudibleSearch: (ReleaseCardUiModel) -> Unit,
    onOpenAudioBookBay: (ReleaseCardUiModel) -> Unit,
    onOpenOrCopyMagnet: (ReleaseCardUiModel) -> Unit,
    onCopyRelease: (ReleaseCardUiModel) -> Unit,
    onExportCalendar: (ReleaseCardUiModel) -> Unit,
    onQuickTrack: (TrackType, String, String, String?) -> Unit,
    onArchiveTrackedBook: (TrackedItemEntity) -> Unit,
    onMuteBook: (ReleaseCardUiModel) -> Unit,
    onMuteSeries: (ReleaseCardUiModel) -> Unit,
    onUnmuteRule: (DndRuleEntity) -> Unit,
    onMarkSeriesSeqRead: (TrackedItemEntity, Int) -> Unit,
    onAutoCheckAbb: (ReleaseCardUiModel) -> Unit = {},
    onMarkRead: (ReleaseCardUiModel) -> Unit = {},
    onUndoMarkRead: (MarkReadOutcome) -> Unit = {},
    onUnmarkReadBook: (ReadBookEntity) -> Unit = {},
    onTogglePin: (ReleaseCardUiModel) -> Unit = {},
    onOpenActionMenu: (ReleaseCardUiModel) -> Unit = {}
) {
    val palette = LocalPtsPalette.current
    val todayIso = remember(today) { today.toString() }
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US) }
    val localQ = localFilterQuery.trim()
    val batchAbbSpinner = rememberAsciiSpinner(active = isCheckingAbbBatch)

    if (quickFilter == QuickFilter.DND) {
        val visibleDnd = remember(dndRules, localQ) {
            if (localQ.isBlank()) dndRules
            else dndRules.filter {
                it.displayTitle.contains(localQ, ignoreCase = true) ||
                    it.targetKey.contains(localQ, ignoreCase = true)
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 112.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "dnd_info", contentType = "info") {
                AsciiSectionHeader(
                    title = "DO NOT DISTURB (${visibleDnd.size} MUTED)",
                    color = palette.red
                )
            }
            if (visibleDnd.isEmpty()) {
                item(key = "dnd_empty", contentType = "empty") {
                    AsciiBox(headerLabel = "DND FILTER EMPTY") {
                        MonoText(
                            text = if (localQ.isNotBlank()) "No muted entries match '$localQ'." else "No muted books or series.",
                            color = palette.comment
                        )
                    }
                }
            }
            items(
                items = visibleDnd,
                key = { "dnd_${it.targetKey}" },
                contentType = { "dnd_card" }
            ) { rule ->
                AsciiBox(
                    headerLabel = "MUTED ${rule.targetType.name}",
                    borderColor = palette.red,
                    headerColor = palette.red
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "${rule.displayTitle.uppercase()} (${rule.targetKey})",
                            fontSizeSp = 13,
                            bold = true,
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = "UNMUTE",
                            color = palette.green,
                            onClick = { onUnmuteRule(rule) }
                        )
                    }
                }
            }
        }
        return
    }

    if (quickFilter == QuickFilter.READ) {
        val visibleRead = remember(readBooks, localQ) {
            if (localQ.isBlank()) readBooks
            else readBooks.filter { b ->
                b.title.contains(localQ, ignoreCase = true) ||
                    b.authors.contains(localQ, ignoreCase = true) ||
                    b.narrators.contains(localQ, ignoreCase = true) ||
                    b.seriesTitle.contains(localQ, ignoreCase = true) ||
                    b.asin.equals(localQ, ignoreCase = true)
            }
        }
        val totalReadMins = remember(visibleRead) { visibleRead.sumOf { it.runtimeMinutes } }
        val readGroups = remember(visibleRead) {
            visibleRead.groupBy { b ->
                runCatching {
                    val d = Instant.ofEpochMilli(b.readAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
                    YearMonth.from(d).format(monthFormatter).uppercase()
                }.getOrDefault("COMPLETED")
            }.map { (monthLabel, list) ->
                val mins = list.sumOf { it.runtimeMinutes }
                val dur = if (mins > 0) " | ${mins / 60}H ${mins % 60}M" else ""
                "$monthLabel (${list.size})$dur" to list
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 112.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "read_summary", contentType = "info") {
                val totalDur = if (totalReadMins > 0) " | ${totalReadMins / 60}H ${totalReadMins % 60}M" else ""
                AsciiSectionHeader(
                    title = "READ LOG (${visibleRead.size} BOOKS$totalDur)",
                    color = palette.cyan
                )
            }
            if (visibleRead.isEmpty()) {
                item(key = "read_empty", contentType = "empty") {
                    AsciiBox(headerLabel = "READ LOG EMPTY") {
                        MonoText(
                            text = if (localQ.isNotBlank()) {
                                "No completed books match '$localQ'."
                            } else {
                                "No books marked read yet.\nTap [ Mark Read ] on any release card to log it here."
                            },
                            color = palette.comment,
                            fontSizeSp = 12
                        )
                    }
                }
            }
            readGroups.forEach { (groupHeader, books) ->
                stickyHeader(key = "read_hdr_$groupHeader") {
                    AsciiSectionHeader(title = groupHeader, color = palette.purple)
                }
                items(
                    items = books,
                    key = { "read_${it.asin}" },
                    contentType = { "read_card" }
                ) { book ->
                    val completedDateStr = remember(book.readAtEpochMs) {
                        runCatching {
                            Instant.ofEpochMilli(book.readAtEpochMs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .format(dateFormatter)
                        }.getOrDefault("READ")
                    }
                    val runtimeStr = if (book.runtimeMinutes > 0) {
                        "${book.runtimeMinutes / 60}h ${book.runtimeMinutes % 60}m"
                    } else "TBA"
                    val cleanedTitle = book.title.replace(marketingSubtitleRegex, "").trim().ifBlank { book.title }
                    AsciiBox(
                        headerLabel = "READ $completedDateStr | ${book.asin}",
                        borderColor = palette.green.copy(alpha = 0.7f),
                        headerColor = palette.green
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (!hideCoverArt) {
                                CoverThumbnail(
                                    coverUrl = book.coverUrl,
                                    sizeDp = StandardThumbnailSize,
                                    borderColor = palette.green
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                MonoText(
                                    text = cleanedTitle.uppercase(),
                                    color = palette.foreground,
                                    fontSizeSp = 14,
                                    bold = true
                                )
                                if (book.seriesTitle.isNotBlank()) {
                                    val seqPart = book.seriesSequence?.let { " #$it" }.orEmpty()
                                    AsciiFieldRow("SER", "${book.seriesTitle}$seqPart", palette.purple)
                                }
                                AsciiFieldRow("AUT", book.authors, palette.foreground)
                                if (book.narrators.isNotBlank() && !book.narrators.equals(book.authors, ignoreCase = true)) {
                                    AsciiFieldRow("NAR", book.narrators, palette.yellow)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        AsciiRule(pattern = "- ")
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MonoText(
                                text = "Runtime: $runtimeStr",
                                color = palette.comment,
                                fontSizeSp = 11
                            )
                            BracketButton(
                                label = "UNREAD",
                                color = palette.orange,
                                onClick = { onUnmarkReadBook(book) }
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val (monthGroups, tbaCards, newCount) = remember(
        releases,
        trackedItems,
        readAsins,
        readGroupKeys,
        pinnedAsins,
        pinnedGroupKeys,
        quickFilter,
        localQ,
        focusedSourceItem,
        todayIso
    ) {
        val seriesList = trackedItems.filter { it.type == TrackType.SERIES }
        val authorList = trackedItems.filter { it.type == TrackType.AUTHOR }
        val narratorList = trackedItems.filter { it.type == TrackType.NARRATOR }
        val bookList = trackedItems.filter { it.type == TrackType.BOOK }

        fun toUiModel(rel: ReleaseEntity): ReleaseCardUiModel {
            val parsedDate = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
            val daysUntil = parsedDate?.let { ChronoUnit.DAYS.between(today, it) }

            val isFutureOrTba = daysUntil == null || daysUntil >= 0L
            val isPastOrToday = daysUntil != null && daysUntil <= 0L
            val isPinned = ReleaseRepository.isReleasePinned(rel, pinnedAsins, pinnedGroupKeys)
            val hasDateShift = rel.previousReleaseDateIso.isNotBlank() &&
                rel.previousReleaseDateIso != rel.releaseDateIso

            // Pure 7-bit ASCII header with no nested double brackets, plus 6-cell countdown bar for <=30d
            val countdownPart = when {
                daysUntil == null -> "TBA"
                daysUntil == 0L -> "OUT TODAY"
                daysUntil < 0L -> "${-daysUntil}d AGO"
                daysUntil in 1L..30L -> "${asciiCountdownBar(daysUntil)} T-${daysUntil}d"
                else -> "T-${daysUntil}d"
            }
            val statusSuffix = buildString {
                if (rel.abbAvailable) append(" | ABB")
                if (hasDateShift && isFutureOrTba) append(" | MOVED")
                if (rel.narratorJustAnnounced && isFutureOrTba) append(" | NAR!")
                if (isEmpty() && rel.isNewDiscovery && isFutureOrTba) append(" | NEW")
            }
            val pinPrefix = if (isPinned) "* " else ""

            // Urgency-tiered color codes:
            // 0 = green (OUT TODAY / ABB), 1 = orange (1..7d), 2 = cyan (8..30d), 3 = comment (>30d / TBA / past)
            val urgencyTone = when {
                rel.abbAvailable || daysUntil == 0L -> 0
                daysUntil != null && daysUntil in 1L..7L -> 1
                daysUntil != null && daysUntil in 8L..30L -> 2
                else -> 3
            }

            val matchedSeries = if (rel.seriesTitle.isNotBlank()) {
                seriesList.firstOrNull { s ->
                    (s.pinnedAsin.isNotBlank() && s.pinnedAsin.equals(rel.seriesAsin, ignoreCase = true)) ||
                        rel.seriesTitle.equals(s.queryValue, ignoreCase = true) ||
                        (s.pinnedAsin.isBlank() && rel.seriesTitle.contains(s.queryValue, ignoreCase = true))
                }
            } else null

            val matchedBookItem = bookList.firstOrNull { b ->
                b.pinnedAsin.equals(rel.asin, ignoreCase = true) ||
                    b.queryValue.equals(rel.asin, ignoreCase = true) ||
                    b.queryValue.equals(rel.title, ignoreCase = true)
            }

            val primaryAuthor = rel.authors.split(",").firstOrNull()?.trim().orEmpty()
            val isAuthorTracked = primaryAuthor.isNotBlank() && authorList.any { a ->
                AudibleApiClient.isExactAuthorNameMatch(a.queryValue, primaryAuthor)
            }

            val primaryNarrator = rel.narrators.split(",").firstOrNull()?.trim()
                ?.takeIf { !it.equals("TBA", ignoreCase = true) }.orEmpty()
            val isNarratorTracked = primaryNarrator.isNotBlank() && narratorList.any { n ->
                AudibleApiClient.isExactAuthorNameMatch(n.queryValue, primaryNarrator)
            }

            val seq = rel.seriesSequence
            val hasGap = matchedSeries != null && seq != null && seq > 1 && matchedSeries.lastReadSequence < (seq - 1)
            val canMarkRead = matchedSeries != null && seq != null && daysUntil != null && daysUntil <= 0L && matchedSeries.lastReadSequence < seq

            val hours = rel.runtimeMinutes / 60
            val mins = rel.runtimeMinutes % 60
            val runtimeStr = if (rel.runtimeMinutes > 0) "${hours}h ${mins}m" else "TBA"
            val pubStr = rel.publisher.ifBlank { "TBA" }
            val matchedFormatted = "[${rel.matchedTags.replace(",", "] [")}]"

            val cleanedTitle = rel.title.replace(marketingSubtitleRegex, "").trim().ifBlank { rel.title }
            val sameAuthorAndNarrator = rel.authors.trim().equals(rel.narrators.trim(), ignoreCase = true)

            return ReleaseCardUiModel(
                entity = rel,
                upperTitle = cleanedTitle.uppercase(),
                headerLabel = "$pinPrefix${rel.releaseDateIso} | $countdownPart$statusSuffix",
                urgencyTone = urgencyTone,
                isFutureOrTba = isFutureOrTba,
                isPastOrToday = isPastOrToday,
                isPinned = isPinned,
                hasDateShift = hasDateShift,
                seriesValue = if (rel.seriesTitle.isNotBlank()) {
                    "${rel.seriesTitle}${seq?.let { " #$it" }.orEmpty()}"
                } else "",
                sameAuthorAndNarrator = sameAuthorAndNarrator,
                runtimeFormatted = runtimeStr,
                publisherFormatted = pubStr,
                matchedSourcesFormatted = matchedFormatted,
                matchedSeriesItem = matchedSeries,
                matchedStandaloneBookItem = matchedBookItem,
                hasSeriesGap = hasGap,
                unreadGapBookNum = (seq ?: 1) - 1,
                canMarkCurrentRead = canMarkRead,
                primaryAuthor = primaryAuthor,
                primaryNarrator = primaryNarrator,
                isSeriesAlreadyTracked = matchedSeries != null,
                isPrimaryAuthorAlreadyTracked = isAuthorTracked,
                isPrimaryNarratorAlreadyTracked = isNarratorTracked
            )
        }

        val unread = releases.filterNot { ReleaseRepository.isReleaseRead(it, readAsins, readGroupKeys) }

        val filtered = unread.filter { rel ->
            if (focusedSourceItem != null) {
                if (!ReleaseRepository.releaseMatchesTrackedItem(rel, focusedSourceItem)) {
                    return@filter false
                }
            }
            if (localQ.isNotBlank()) {
                val matchesLocal = rel.title.contains(localQ, ignoreCase = true) ||
                    rel.authors.contains(localQ, ignoreCase = true) ||
                    rel.narrators.contains(localQ, ignoreCase = true) ||
                    rel.seriesTitle.contains(localQ, ignoreCase = true) ||
                    rel.asin.equals(localQ, ignoreCase = true)
                if (!matchesLocal) return@filter false
            }

            val isPastRelease = rel.releaseDateIso != "TBA" && rel.releaseDateIso < todayIso
            when (quickFilter) {
                QuickFilter.PINNED -> ReleaseRepository.isReleasePinned(rel, pinnedAsins, pinnedGroupKeys)
                QuickFilter.RECENT_30D -> isPastRelease
                QuickFilter.ABB -> rel.abbAvailable
                QuickFilter.ALL -> !isPastRelease
                QuickFilter.SERIES -> !isPastRelease && rel.matchedTags.contains("SERIES", ignoreCase = true)
                QuickFilter.AUTHOR -> !isPastRelease && rel.matchedTags.contains("AUTHOR", ignoreCase = true)
                QuickFilter.NARRATOR -> !isPastRelease && rel.matchedTags.contains("NARRATOR", ignoreCase = true)
                QuickFilter.SOON_30D -> {
                    val d = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(rel.releaseDateIso)) }.getOrNull()
                    d != null && d in 0..30
                }
                QuickFilter.READ, QuickFilter.DND -> false
            }
        }

        val scheduledRaw = filtered.filter { it.releaseDateIso != "TBA" }
        val sortedScheduled = if (quickFilter == QuickFilter.RECENT_30D || quickFilter == QuickFilter.ABB) {
            scheduledRaw.sortedByDescending { it.releaseDateIso }
        } else {
            scheduledRaw.sortedBy { it.releaseDateIso }
        }

        val groups = sortedScheduled
            .map(::toUiModel)
            .groupBy { card ->
                runCatching {
                    val ym = YearMonth.from(LocalDate.parse(card.entity.releaseDateIso))
                    ym.format(monthFormatter).uppercase()
                }.getOrDefault("SCHEDULED")
            }
            .map { (monthName, list) ->
                val totalMins = list.sumOf { it.entity.runtimeMinutes }
                val runtimeBadge = if (totalMins > 0) {
                    " | ${totalMins / 60}H ${totalMins % 60}M"
                } else ""
                MonthGroupUiModel(
                    monthHeader = "$monthName (${list.size})$runtimeBadge",
                    cards = list
                )
            }

        val tbas = if (quickFilter == QuickFilter.RECENT_30D || quickFilter == QuickFilter.ABB) {
            emptyList()
        } else {
            filtered.filter { it.releaseDateIso == "TBA" }.map(::toUiModel)
        }

        val newlyDiscovered = unread.count { rel ->
            (rel.isNewDiscovery || rel.narratorJustAnnounced) &&
                (rel.releaseDateIso == "TBA" || rel.releaseDateIso >= todayIso)
        }
        Triple(groups, tbas, newlyDiscovered)
    }

    val telemetryStripData = remember(releases, readBooks, readAsins, readGroupKeys, todayIso) {
        val unreadUpcoming = releases.filter { rel ->
            !ReleaseRepository.isReleaseRead(rel, readAsins, readGroupKeys) &&
                rel.releaseDateIso != "TBA" &&
                rel.releaseDateIso >= todayIso
        }.sortedBy { it.releaseDateIso }
        val nextDays = unreadUpcoming.firstOrNull()?.let { rel ->
            runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(rel.releaseDateIso)) }.getOrNull()
        }
        val nextLabel = when {
            nextDays == null -> "NONE"
            nextDays <= 0L -> "TODAY"
            else -> "T-${nextDays}d"
        }
        val soon30Count = unreadUpcoming.count { rel ->
            val d = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(rel.releaseDateIso)) }.getOrNull()
            d != null && d in 0L..30L
        }
        val readHours = readBooks.sumOf { it.runtimeMinutes } / 60
        listOf(nextLabel, "${unreadUpcoming.size}", "$soon30Count", "${readBooks.size}", "${readHours}H")
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 112.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (focusedSourceItem == null && localQ.isBlank() && quickFilter != QuickFilter.RECENT_30D && quickFilter != QuickFilter.ABB) {
            item(key = "stats_strip", contentType = "stats_strip") {
                val nextTxt = telemetryStripData[0]
                val upTxt = telemetryStripData[1]
                val soonTxt = telemetryStripData[2]
                val readTxt = telemetryStripData[3]
                val hrsTxt = telemetryStripData[4]
                val stripText = buildAnnotatedString {
                    withStyle(SpanStyle(color = palette.comment)) { append("NEXT: ") }
                    withStyle(SpanStyle(color = if (nextTxt == "TODAY") palette.green else palette.orange, fontWeight = FontWeight.Bold)) { append(nextTxt) }
                    withStyle(SpanStyle(color = palette.comment)) { append(" | UP: ") }
                    withStyle(SpanStyle(color = palette.cyan, fontWeight = FontWeight.Bold)) { append(upTxt) }
                    withStyle(SpanStyle(color = palette.comment)) { append(" | <=30d: ") }
                    withStyle(SpanStyle(color = palette.purple, fontWeight = FontWeight.Bold)) { append(soonTxt) }
                    withStyle(SpanStyle(color = palette.comment)) { append(" | READ: ") }
                    withStyle(SpanStyle(color = palette.green, fontWeight = FontWeight.Bold)) { append("$readTxt($hrsTxt)") }
                }
                Text(
                    text = stripText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    style = TextStyle(fontFeatureSettings = "liga 0, calt 0"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceLine.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }

        if (recentlyMarkedRead != null) {
            item(key = "undo_read_banner", contentType = "banner") {
                AsciiBox(
                    headerLabel = "MARKED AS READ",
                    borderColor = palette.green,
                    headerColor = palette.green
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = recentlyMarkedRead.readBook.title.uppercase(),
                            color = palette.green,
                            fontSizeSp = 12,
                            bold = true,
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = "UNDO",
                            color = palette.orange,
                            onClick = { onUndoMarkRead(recentlyMarkedRead) }
                        )
                    }
                }
            }
        }

        if (quickFilter == QuickFilter.ABB) {
            item(key = "abb_action_bar", contentType = "banner") {
                AsciiBox(
                    headerLabel = "AUDIOBOOKBAY VERIFICATION",
                    borderColor = palette.green,
                    headerColor = palette.green
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = if (isCheckingAbbBatch) "Checking recent unread drops..." else "Check recent unread releases on ABB",
                            color = palette.comment,
                            fontSizeSp = 11,
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = if (isCheckingAbbBatch) "$batchAbbSpinner CHECKING" else "CHECK ABB NOW",
                            color = palette.green,
                            onClick = onRunManualAbbCheck
                        )
                    }
                }
            }
        }

        if (focusedSourceItem != null) {
            item(key = "focused_source_banner", contentType = "banner") {
                AsciiBox(
                    headerLabel = "FILTERED BY ${focusedSourceItem.type.name}",
                    borderColor = palette.cyan,
                    headerColor = palette.cyan
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "SRC: ${focusedSourceItem.queryValue}",
                            color = palette.cyan,
                            fontSizeSp = 12,
                            bold = true,
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = "SHOW ALL",
                            color = palette.green,
                            onClick = onClearFocusedSource
                        )
                    }
                }
            }
        }

        if (newCount > 0 && quickFilter != QuickFilter.RECENT_30D && focusedSourceItem == null && localQ.isBlank()) {
            item(key = "new_discovery_banner", contentType = "banner") {
                AsciiBox(
                    headerLabel = "NEW PRE-ORDER DISCOVERED",
                    borderColor = palette.pink,
                    headerColor = palette.pink
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "$newCount newly listed release(s) found.",
                            color = palette.pink,
                            fontSizeSp = 12,
                            bold = true
                        )
                        BracketButton(
                            label = "ACK / CLEAR",
                            color = palette.pink,
                            onClick = onClearNewBadges
                        )
                    }
                }
            }
        }

        if (monthGroups.isEmpty()) {
            item(key = "empty_scoreboard", contentType = "empty") {
                AsciiBox(headerLabel = "SCOREBOARD EMPTY") {
                    MonoText(
                        text = if (localQ.isNotBlank()) {
                            "No releases match filter '$localQ'."
                        } else if (focusedSourceItem != null) {
                            "No scheduled upcoming releases found for '${focusedSourceItem.queryValue}'."
                        } else if (quickFilter == QuickFilter.PINNED) {
                            "No pinned releases yet. Expand any card and tap [ * Pin ] to star priority drops."
                        } else if (quickFilter == QuickFilter.RECENT_30D) {
                            "No unread releases from the past 30 days found for your followed sources."
                        } else if (quickFilter == QuickFilter.ABB) {
                            "No verified unread AudioBookBay releases found yet. Tap [ CHECK ABB NOW ] above or expand any released card."
                        } else {
                            "No scheduled upcoming releases match [${quickFilter.label}].\nTap [ 2:TRACKED ] on the bottom bar to search the live catalog."
                        },
                        color = palette.comment,
                        fontSizeSp = 12
                    )
                }
            }
        }

        monthGroups.forEach { group ->
            stickyHeader(key = "hdr_${group.monthHeader}") {
                AsciiSectionHeader(title = group.monthHeader, color = palette.purple)
            }
            items(
                items = group.cards,
                key = { it.entity.asin },
                contentType = { "release_card" }
            ) { card ->
                ReleaseScorecard(
                    card = card,
                    alwaysShowCardActions = alwaysShowCardActions,
                    hideCoverArt = hideCoverArt,
                    isCheckingAbb = card.entity.asin in checkingAbbAsins,
                    onOpenAudibleSearch = { onOpenAudibleSearch(card) },
                    onOpenAudioBookBay = { onOpenAudioBookBay(card) },
                    onOpenOrCopyMagnet = { onOpenOrCopyMagnet(card) },
                    onCopyRelease = { onCopyRelease(card) },
                    onExportCalendar = { onExportCalendar(card) },
                    onQuickTrack = onQuickTrack,
                    onArchiveTrackedBook = onArchiveTrackedBook,
                    onMuteBook = { onMuteBook(card) },
                    onMuteSeries = { onMuteSeries(card) },
                    onMarkSeriesSeqRead = onMarkSeriesSeqRead,
                    onMarkRead = { onMarkRead(card) },
                    onTogglePin = { onTogglePin(card) },
                    onOpenActionMenu = { onOpenActionMenu(card) },
                    onAutoCheckAbb = { cardItem ->
                        if (!cardItem.entity.abbAvailable && !cardItem.isFutureOrTba) {
                            onAutoCheckAbb(cardItem)
                        }
                    }
                )
            }
        }

        if (tbaCards.isNotEmpty()) {
            item(key = "tba_toggle", contentType = "tba_toggle") {
                BracketButton(
                    label = if (showTbaExpanded) "- HIDE TBA / UNSCHEDULED (${tbaCards.size})"
                    else "+ SHOW TBA / UNSCHEDULED (${tbaCards.size})",
                    color = palette.comment,
                    onClick = onToggleTba
                )
            }
            if (showTbaExpanded) {
                items(
                    items = tbaCards,
                    key = { "tba_${it.entity.asin}" },
                    contentType = { "release_card" }
                ) { card ->
                    ReleaseScorecard(
                        card = card,
                        alwaysShowCardActions = alwaysShowCardActions,
                        hideCoverArt = hideCoverArt,
                        isCheckingAbb = card.entity.asin in checkingAbbAsins,
                        onOpenAudibleSearch = { onOpenAudibleSearch(card) },
                        onOpenAudioBookBay = { onOpenAudioBookBay(card) },
                        onOpenOrCopyMagnet = { onOpenOrCopyMagnet(card) },
                        onCopyRelease = { onCopyRelease(card) },
                        onExportCalendar = { onExportCalendar(card) },
                        onQuickTrack = onQuickTrack,
                        onArchiveTrackedBook = onArchiveTrackedBook,
                        onMuteBook = { onMuteBook(card) },
                        onMuteSeries = { onMuteSeries(card) },
                        onMarkSeriesSeqRead = onMarkSeriesSeqRead,
                        onMarkRead = { onMarkRead(card) },
                        onTogglePin = { onTogglePin(card) },
                        onOpenActionMenu = { onOpenActionMenu(card) },
                        onAutoCheckAbb = { cardItem ->
                            if (!cardItem.entity.abbAvailable && !cardItem.isFutureOrTba) {
                                onAutoCheckAbb(cardItem)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseScorecard(
    card: ReleaseCardUiModel,
    alwaysShowCardActions: Boolean,
    hideCoverArt: Boolean,
    isCheckingAbb: Boolean,
    onOpenAudibleSearch: () -> Unit,
    onOpenAudioBookBay: () -> Unit,
    onOpenOrCopyMagnet: () -> Unit,
    onCopyRelease: () -> Unit,
    onExportCalendar: () -> Unit,
    onQuickTrack: (TrackType, String, String, String?) -> Unit,
    onArchiveTrackedBook: (TrackedItemEntity) -> Unit,
    onMuteBook: () -> Unit,
    onMuteSeries: () -> Unit,
    onMarkSeriesSeqRead: (TrackedItemEntity, Int) -> Unit,
    onMarkRead: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onOpenActionMenu: () -> Unit = {},
    onAutoCheckAbb: (ReleaseCardUiModel) -> Unit = {}
) {
    val palette = LocalPtsPalette.current
    val rel = card.entity
    var isExpanded by remember(rel.asin) { mutableStateOf(false) }
    val abbSpinner = rememberAsciiSpinner(active = isCheckingAbb)

    val urgencyColor = when (card.urgencyTone) {
        0 -> palette.green   // OUT TODAY / ABB
        1 -> palette.orange  // T-1d..7d
        2 -> palette.cyan    // T-8d..30d
        else -> palette.comment // >30d / TBA / past
    }

    val frameBorderColor = when {
        isExpanded -> palette.cyan
        rel.abbAvailable -> palette.green
        card.isPinned -> palette.yellow
        card.hasDateShift && card.isFutureOrTba -> palette.orange
        rel.isNewDiscovery && card.isFutureOrTba -> palette.pink
        else -> urgencyColor
    }

    AsciiBox(
        headerLabel = card.headerLabel,
        trailingHeaderBadge = if (isExpanded) "-" else "+",
        borderColor = frameBorderColor,
        headerColor = urgencyColor,
        animateSizeChange = true,
        onClick = {
            val nextExpanded = !isExpanded
            isExpanded = nextExpanded
            if (nextExpanded && !rel.abbAvailable && !card.isFutureOrTba) {
                onAutoCheckAbb(card)
            }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            if (!hideCoverArt) {
                CoverThumbnail(
                    coverUrl = rel.coverUrl,
                    sizeDp = StandardThumbnailSize,
                    borderColor = urgencyColor
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                MonoText(
                    text = card.upperTitle,
                    color = palette.foreground,
                    fontSizeSp = 14,
                    bold = true
                )
                if (card.seriesValue.isNotEmpty()) {
                    AsciiFieldRow(tag = "SER", value = card.seriesValue, valueColor = palette.purple)
                }
                if (card.sameAuthorAndNarrator) {
                    AsciiFieldRow(tag = "AUT", value = "${rel.authors} (Narr)", valueColor = palette.foreground)
                } else {
                    AsciiFieldRow(tag = "AUT", value = rel.authors, valueColor = palette.foreground)
                    AsciiFieldRow(tag = "NAR", value = rel.narrators, valueColor = palette.yellow)
                }
            }
        }

        if (card.hasSeriesGap && card.matchedSeriesItem != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                MonoText(
                    text = "[ ! GAP: #${card.unreadGapBookNum} Unread (Read: #${card.matchedSeriesItem.lastReadSequence}) ]",
                    color = palette.red,
                    fontSizeSp = 11,
                    bold = true
                )
                BracketButton(
                    label = "[x] Mark #${card.unreadGapBookNum} Read",
                    color = palette.green,
                    onClick = { onMarkSeriesSeqRead(card.matchedSeriesItem, card.unreadGapBookNum) }
                )
            }
        }

        // Collapsed card shows ONLY Runtime; Publisher & Match live in the expanded view
        Spacer(modifier = Modifier.height(4.dp))
        AsciiRule(pattern = "- ")
        Spacer(modifier = Modifier.height(3.dp))
        AsciiLeaderRow(
            key = "Runtime",
            value = card.runtimeFormatted,
            valueColor = palette.cyan,
            fontSizeSp = 11
        )

        // Quick 1-tap Mark Read on collapsed released/past cards
        if (!isExpanded && !alwaysShowCardActions && card.isPastOrToday) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BracketButton(
                    label = "Mark Read",
                    color = palette.green,
                    onClick = onMarkRead
                )
            }
        }

        if (isExpanded) {
            if (rel.previousReleaseDateIso.isNotBlank() && rel.previousReleaseDateIso != rel.releaseDateIso) {
                AsciiLeaderRow(
                    key = "Date Moved",
                    value = "${rel.previousReleaseDateIso} -> ${rel.releaseDateIso}",
                    keyColor = palette.orange,
                    valueColor = palette.orange,
                    fontSizeSp = 11
                )
            }
            if (rel.narratorJustAnnounced) {
                AsciiLeaderRow(
                    key = "Narrator Alert",
                    value = rel.narrators,
                    keyColor = palette.green,
                    valueColor = palette.green,
                    fontSizeSp = 11
                )
            }
            AsciiLeaderRow(
                key = "Publisher",
                value = card.publisherFormatted,
                valueColor = palette.foreground,
                fontSizeSp = 11
            )
            AsciiLeaderRow(
                key = "Match",
                value = card.matchedSourcesFormatted,
                valueColor = palette.purple,
                fontSizeSp = 11
            )

            Spacer(modifier = Modifier.height(4.dp))
            AsciiRule(pattern = "- ")
            Spacer(modifier = Modifier.height(4.dp))

            MonoText(
                text = rel.synopsis.ifBlank { "No publisher summary posted yet for this listing." },
                color = palette.foreground,
                fontSizeSp = 12
            )
        }

        if (isExpanded || alwaysShowCardActions) {
            Spacer(modifier = Modifier.height(4.dp))
            AsciiRule(pattern = "- ")
            Spacer(modifier = Modifier.height(2.dp))

            if (isCheckingAbb) {
                MonoText(
                    text = "[ $abbSpinner CHECKING AUDIOBOOKBAY... ]",
                    color = palette.orange,
                    fontSizeSp = 11,
                    bold = true
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BracketButton(
                    label = "READ",
                    color = palette.green,
                    onClick = onMarkRead
                )
                BracketButton(
                    label = "AUDIBLE",
                    color = palette.cyan,
                    onClick = onOpenAudibleSearch
                )
                BracketButton(
                    label = "ABB",
                    active = rel.abbAvailable,
                    color = if (rel.abbAvailable) palette.green else palette.orange,
                    onClick = onOpenAudioBookBay
                )
                BracketButton(
                    label = "MENU",
                    color = palette.purple,
                    onClick = onOpenActionMenu
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TrackedEntitiesView(
    listState: LazyListState,
    today: LocalDate,
    trackedItems: List<TrackedItemEntity>,
    releases: List<ReleaseEntity>,
    readAsins: Set<String>,
    readGroupKeys: Set<String>,
    localFilterQuery: String,
    sourceFilter: TrackedSourceFilter,
    sortOrder: TrackedSortOrder,
    hideCoverArt: Boolean,
    recentlyDeletedItem: TrackedItemEntity?,
    onAddTracked: (TrackType, String, Int, String, String?) -> Unit,
    onSelectSourceForFilter: (TrackedItemEntity) -> Unit,
    onEditTracked: (TrackedItemEntity, String, Boolean) -> Unit,
    onDeleteTracked: (TrackedItemEntity) -> Unit,
    onUndoDelete: (TrackedItemEntity) -> Unit,
    onUpdateSeriesSeq: (TrackedItemEntity, Int) -> Unit,
    onOpenAbbQuery: (String) -> Unit
) {
    val palette = LocalPtsPalette.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val todayIso = remember(today) { today.toString() }
    val localQ = localFilterQuery.trim()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchingCatalog by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var lastSearchedQuery by remember { mutableStateOf("") }
    var catalogResults by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }

    var useDirectMode by remember { mutableStateOf(false) }
    var directType by remember { mutableStateOf(TrackType.SERIES) }
    var directLastRead by remember { mutableStateOf("0") }

    // Inline in-place edit state for followed sources
    var editingItemId by remember { mutableStateOf<Long?>(null) }
    var editDraft by remember { mutableStateOf("") }

    // Two-tap inline delete guard (`[ x ]` -> `[ del? ]` auto-resetting after 3s)
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingDeleteId) {
        if (pendingDeleteId != null) {
            delay(3000L)
            pendingDeleteId = null
        }
    }

    val searchSpinner = rememberAsciiSpinner(active = isSearchingCatalog)

    val upcomingCountsByItemId = remember(trackedItems, releases, readAsins, readGroupKeys, todayIso) {
        val futureReleases = releases.filter { rel ->
            !ReleaseRepository.isReleaseRead(rel, readAsins, readGroupKeys) &&
                (rel.releaseDateIso == "TBA" || rel.releaseDateIso >= todayIso)
        }
        trackedItems.associate { item ->
            val count = futureReleases.count { rel ->
                ReleaseRepository.releaseMatchesTrackedItem(rel, item)
            }
            item.id to count
        }
    }

    val filteredTrackedItems = remember(trackedItems, sourceFilter, sortOrder, localQ, upcomingCountsByItemId) {
        val byType = if (sourceFilter.trackType == null) {
            trackedItems
        } else {
            trackedItems.filter { it.type == sourceFilter.trackType }
        }
        val base = if (localQ.isBlank()) {
            byType
        } else {
            byType.filter { item ->
                item.queryValue.contains(localQ, ignoreCase = true) ||
                    item.pinnedAuthor.contains(localQ, ignoreCase = true) ||
                    item.type.name.contains(localQ, ignoreCase = true)
            }
        }
        when (sortOrder) {
            TrackedSortOrder.A_TO_Z -> base.sortedWith(
                compareBy<TrackedItemEntity> { it.type.ordinal }
                    .thenBy { it.queryValue.lowercase(Locale.US) }
            )
            TrackedSortOrder.MOST_UPCOMING -> base.sortedWith(
                compareByDescending<TrackedItemEntity> { upcomingCountsByItemId[it.id] ?: 0 }
                    .thenBy { it.queryValue.lowercase(Locale.US) }
            )
        }
    }

    val trackedKeySet = remember(trackedItems) {
        trackedItems.map { "${it.type.name}:${it.queryValue.trim().lowercase(Locale.US)}" }.toSet()
    }

    val performLiveSearch = {
        val trimmed = searchQuery.trim()
        if (trimmed.isNotBlank() && !isSearchingCatalog) {
            focusManager.clearFocus()
            keyboardController?.hide()
            scope.launch {
                isSearchingCatalog = true
                lastSearchedQuery = trimmed
                catalogResults = AudibleApiClient.searchCatalog(
                    titleOrSeries = trimmed,
                    numResults = 15,
                    maxPages = 1,
                    forceRefresh = false
                )
                hasSearched = true
                isSearchingCatalog = false
            }
        }
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 112.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (recentlyDeletedItem != null) {
            item(key = "undo_delete_banner", contentType = "undo_banner") {
                AsciiBox(
                    headerLabel = "SOURCE REMOVED",
                    borderColor = palette.orange,
                    headerColor = palette.orange
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "Removed ${recentlyDeletedItem.type.name}: ${recentlyDeletedItem.queryValue}",
                            color = palette.orange,
                            fontSizeSp = 12,
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = "UNDO",
                            color = palette.green,
                            onClick = { onUndoDelete(recentlyDeletedItem) }
                        )
                    }
                }
            }
        }

        item(key = "search_box", contentType = "search_box") {
            AsciiBox(
                headerLabel = if (useDirectMode) "DIRECT MANUAL ADD" else "LIVE CATALOG SEARCH"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AsciiRadioButton(
                        label = "LIVE SEARCH",
                        selected = !useDirectMode,
                        activeColor = palette.cyan,
                        onClick = { useDirectMode = false }
                    )
                    AsciiRadioButton(
                        label = "DIRECT ADD",
                        selected = useDirectMode,
                        activeColor = palette.green,
                        onClick = { useDirectMode = true }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                if (useDirectMode) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TrackType.values().forEach { t ->
                            AsciiRadioButton(
                                label = t.name,
                                selected = directType == t,
                                activeColor = palette.purple,
                                onClick = { directType = t }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                val placeholderStr = if (useDirectMode) {
                    if (directType == TrackType.BOOK) "Book title or 10-char Audible ASIN..."
                    else "Exact ${directType.name.lowercase().replaceFirstChar { it.uppercase() }} name..."
                } else {
                    "Search book, series, author, or narrator..."
                }

                AsciiTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        if (it.isBlank()) hasSearched = false
                    },
                    placeholder = placeholderStr,
                    imeAction = ImeAction.Search,
                    onImeAction = {
                        if (useDirectMode) {
                            if (searchQuery.isNotBlank()) {
                                onAddTracked(directType, searchQuery, directLastRead.toIntOrNull() ?: 0, "", null)
                                searchQuery = ""
                            }
                        } else {
                            performLiveSearch()
                        }
                    }
                )

                if (useDirectMode && directType == TrackType.SERIES) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MonoText(
                        text = "Last Completed Book # in Series:",
                        color = palette.comment,
                        fontSizeSp = 11
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    AsciiTextField(
                        value = directLastRead,
                        onValueChange = { directLastRead = it.filter { ch -> ch.isDigit() } },
                        placeholder = "0"
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (useDirectMode) {
                        BracketButton(
                            label = "+ ADD DIRECTLY",
                            color = palette.green,
                            onClick = {
                                if (searchQuery.isNotBlank()) {
                                    onAddTracked(
                                        directType,
                                        searchQuery,
                                        directLastRead.toIntOrNull() ?: 0,
                                        "",
                                        null
                                    )
                                    searchQuery = ""
                                }
                            }
                        )
                    } else {
                        BracketButton(
                            label = if (isSearchingCatalog) "$searchSpinner SEARCHING..." else "SEARCH CATALOG",
                            color = palette.cyan,
                            onClick = performLiveSearch
                        )
                    }
                    if (searchQuery.isNotBlank()) {
                        BracketButton(
                            label = "ABB SEARCH",
                            color = palette.orange,
                            onClick = { onOpenAbbQuery(searchQuery) }
                        )
                    }
                    if ((catalogResults.isNotEmpty() || hasSearched) && !useDirectMode) {
                        BracketButton(
                            label = "CLEAR",
                            color = palette.comment,
                            onClick = {
                                searchQuery = ""
                                catalogResults = emptyList()
                                hasSearched = false
                                lastSearchedQuery = ""
                            }
                        )
                    }
                }
            }
        }

        if (hasSearched && !isSearchingCatalog && catalogResults.isEmpty() && !useDirectMode) {
            item(key = "live_empty", contentType = "live_empty") {
                AsciiBox(
                    headerLabel = "NO CATALOG MATCHES",
                    borderColor = palette.orange,
                    headerColor = palette.orange
                ) {
                    MonoText(
                        text = "No Audible catalog matches found for '$lastSearchedQuery'. Try a shorter query or switch to DIRECT ADD.",
                        color = palette.comment,
                        fontSizeSp = 12
                    )
                }
            }
        }

        if (catalogResults.isNotEmpty() && !useDirectMode) {
            stickyHeader(key = "live_hdr") {
                AsciiSectionHeader(
                    title = "LIVE CATALOG RESULTS (${catalogResults.size})",
                    color = palette.cyan
                )
            }
            items(
                items = catalogResults,
                key = { "live_${it.asin}" },
                contentType = { "live_result" }
            ) { prod ->
                val seriesTracked = prod.seriesTitle.isNotBlank() &&
                    "SERIES:${prod.seriesTitle.trim().lowercase(Locale.US)}" in trackedKeySet
                val firstAuthor = prod.authors.firstOrNull()?.trim().orEmpty()
                val authorTracked = firstAuthor.isNotBlank() &&
                    "AUTHOR:${firstAuthor.lowercase(Locale.US)}" in trackedKeySet
                val firstNarrator = prod.narrators.firstOrNull()?.trim().orEmpty()
                val narratorTracked = firstNarrator.isNotBlank() &&
                    "NARRATOR:${firstNarrator.lowercase(Locale.US)}" in trackedKeySet
                val bookTracked = "BOOK:${prod.asin.lowercase(Locale.US)}" in trackedKeySet ||
                    "BOOK:${prod.title.trim().lowercase(Locale.US)}" in trackedKeySet

                val cleanedTitle = prod.title.replace(marketingSubtitleRegex, "").trim().ifBlank { prod.title }
                val authorsStr = prod.authors.joinToString(", ")
                val narratorsStr = prod.narrators.joinToString(", ")
                val sameAuthorAndNarrator = authorsStr.trim().equals(narratorsStr.trim(), ignoreCase = true)

                AsciiBox(
                    headerLabel = "${prod.releaseDateIso} | ${prod.asin}",
                    borderColor = palette.cyan,
                    headerColor = palette.cyan
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (!hideCoverArt) {
                            CoverThumbnail(
                                coverUrl = prod.coverUrl,
                                sizeDp = StandardThumbnailSize,
                                borderColor = palette.cyan
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            MonoText(text = cleanedTitle.uppercase(), bold = true, fontSizeSp = 14)
                            if (prod.seriesTitle.isNotBlank()) {
                                val seqPart = prod.seriesSequence?.let { " #$it" }.orEmpty()
                                AsciiFieldRow("SER", "${prod.seriesTitle}$seqPart", palette.purple)
                            }
                            if (sameAuthorAndNarrator && authorsStr.isNotBlank()) {
                                AsciiFieldRow("AUT", "$authorsStr (Narr)", palette.foreground)
                            } else {
                                AsciiFieldRow("AUT", authorsStr, palette.foreground)
                                AsciiFieldRow("NAR", narratorsStr, palette.yellow)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    AsciiRule(pattern = "- ")
                    Spacer(modifier = Modifier.height(2.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prod.seriesTitle.isNotBlank()) {
                            BracketButton(
                                label = if (seriesTracked) "[x] Series" else "[+ Series]",
                                active = seriesTracked,
                                color = palette.purple,
                                onClick = {
                                    val pinnedSeriesMeta = "${prod.seriesAsin}|$firstAuthor"
                                    onAddTracked(TrackType.SERIES, prod.seriesTitle, 0, prod.coverUrl, pinnedSeriesMeta)
                                }
                            )
                        }
                        if (firstAuthor.isNotBlank()) {
                            BracketButton(
                                label = if (authorTracked) "[x] Author" else "[+ Author]",
                                active = authorTracked,
                                color = palette.yellow,
                                onClick = {
                                    onAddTracked(
                                        TrackType.AUTHOR,
                                        firstAuthor,
                                        0,
                                        prod.coverUrl,
                                        prod.primaryAuthorAsin.ifBlank { null }
                                    )
                                }
                            )
                        }
                        if (firstNarrator.isNotBlank()) {
                            BracketButton(
                                label = if (narratorTracked) "[x] Narrator" else "[+ Narrator]",
                                active = narratorTracked,
                                color = palette.orange,
                                onClick = { onAddTracked(TrackType.NARRATOR, firstNarrator, 0, prod.coverUrl, null) }
                            )
                        }
                        BracketButton(
                            label = if (bookTracked) "[x] Book" else "[+ Book]",
                            active = bookTracked,
                            color = palette.green,
                            onClick = {
                                val pinnedBookMeta = "${prod.asin}|$firstAuthor"
                                onAddTracked(TrackType.BOOK, prod.title, 0, prod.coverUrl, pinnedBookMeta)
                            }
                        )
                        BracketButton(
                            label = "ABB",
                            color = palette.orange,
                            onClick = { onOpenAbbQuery("${prod.title} $firstAuthor") }
                        )
                    }
                }
            }
        }

        stickyHeader(key = "followed_hdr") {
            AsciiSectionHeader(
                title = "FOLLOWED SOURCES (${filteredTrackedItems.size}/${trackedItems.size})",
                color = palette.purple
            )
        }

        items(
            items = filteredTrackedItems,
            key = { it.id },
            contentType = { "tracked_item" }
        ) { item ->
            val upcomingBadgeCount = upcomingCountsByItemId[item.id] ?: 0
            val hasUpcoming = upcomingBadgeCount > 0
            val typeAccent = when (item.type) {
                TrackType.SERIES -> palette.purple
                TrackType.AUTHOR -> palette.yellow
                TrackType.NARRATOR -> palette.orange
                TrackType.BOOK -> palette.green
            }
            val fallbackBadge = when (item.type) {
                TrackType.SERIES -> "[SER]"
                TrackType.AUTHOR -> "[AUT]"
                TrackType.NARRATOR -> "[NAR]"
                TrackType.BOOK -> "[BOK]"
            }

            // De-emphasize 0 UPCOMING rows to comment; highlight 1+ UPCOMING in green/typeAccent
            val headerTint = if (hasUpcoming) palette.green else palette.comment
            val borderTint = if (editingItemId == item.id) palette.cyan else if (hasUpcoming) typeAccent else palette.comment.copy(alpha = 0.6f)
            val isConfirmingDelete = pendingDeleteId == item.id
            val isEditing = editingItemId == item.id

            AsciiBox(
                headerLabel = "${item.type.name} | $upcomingBadgeCount UPCOMING",
                borderColor = borderTint,
                headerColor = headerTint,
                animateSizeChange = true,
                onClick = {
                    if (!isEditing) onSelectSourceForFilter(item)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!hideCoverArt) {
                            CoverThumbnail(
                                coverUrl = item.thumbnailUrl,
                                sizeDp = StandardThumbnailSize,
                                fallbackText = fallbackBadge,
                                borderColor = borderTint
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            MonoText(
                                text = item.queryValue.uppercase(),
                                color = if (hasUpcoming) palette.foreground else palette.foreground.copy(alpha = 0.85f),
                                fontSizeSp = 14,
                                bold = true
                            )
                            if ((item.type == TrackType.SERIES || item.type == TrackType.BOOK) && item.pinnedAuthor.isNotBlank()) {
                                AsciiFieldRow("AUT", item.pinnedAuthor, palette.foreground)
                            }
                            if (item.type == TrackType.SERIES) {
                                AsciiFieldRow("SEQ", "Last Read #${item.lastReadSequence}", palette.purple)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.type == TrackType.SERIES) {
                            BracketButton(
                                label = "-1",
                                color = palette.comment,
                                onClick = {
                                    onUpdateSeriesSeq(item, (item.lastReadSequence - 1).coerceAtLeast(0))
                                }
                            )
                            BracketButton(
                                label = "+1",
                                color = palette.cyan,
                                onClick = { onUpdateSeriesSeq(item, item.lastReadSequence + 1) }
                            )
                        }
                        BracketButton(
                            label = "e",
                            color = if (isEditing) palette.cyan else palette.comment,
                            active = isEditing,
                            onClick = {
                                if (isEditing) {
                                    editingItemId = null
                                } else {
                                    editingItemId = item.id
                                    editDraft = item.queryValue
                                }
                            }
                        )
                        if (isConfirmingDelete) {
                            AsciiInvertButton(
                                label = "del?",
                                active = true,
                                color = palette.red,
                                hapticType = HapticFeedbackType.LongPress,
                                onClick = {
                                    pendingDeleteId = null
                                    if (editingItemId == item.id) editingItemId = null
                                    onDeleteTracked(item)
                                }
                            )
                        } else {
                            BracketButton(
                                label = "x",
                                color = palette.red,
                                onClick = { pendingDeleteId = item.id }
                            )
                        }
                    }
                }
                if (isEditing) {
                    Spacer(modifier = Modifier.height(6.dp))
                    AsciiRule(pattern = "- ")
                    Spacer(modifier = Modifier.height(4.dp))
                    AsciiTextField(
                        value = editDraft,
                        onValueChange = { editDraft = it },
                        placeholder = "Edit ${item.type.name.lowercase()} name...",
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            if (editDraft.isNotBlank()) {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                editingItemId = null
                                onEditTracked(item, editDraft, false)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BracketButton(
                            label = "SAVE",
                            color = palette.green,
                            onClick = {
                                if (editDraft.isNotBlank()) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    editingItemId = null
                                    onEditTracked(item, editDraft, false)
                                }
                            }
                        )
                        BracketButton(
                            label = "REFRESH META",
                            color = palette.cyan,
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                editingItemId = null
                                onEditTracked(item, editDraft.ifBlank { item.queryValue }, true)
                            }
                        )
                        BracketButton(
                            label = "CANCEL",
                            color = palette.comment,
                            onClick = { editingItemId = null }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsAndBackupView(
    listState: LazyListState,
    prefs: FilterPrefs,
    syncTelemetry: SyncTelemetry,
    isSyncing: Boolean,
    isCheckingAbbBatch: Boolean,
    syncProgress: SyncProgressState,
    onRunManualAbbCheck: () -> Unit,
    onUpdatePrefs: (FilterPrefs) -> Unit,
    onPostStatus: (String, Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val palette = LocalPtsPalette.current
    val scope = rememberCoroutineScope()
    val abbSpinner = rememberAsciiSpinner(active = isCheckingAbbBatch)

    var cacheStats by remember { mutableStateOf(CacheStats(0, 0L)) }
    LaunchedEffect(isSyncing) {
        cacheStats = AudibleApiClient.getCacheStats()
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "cfg_options") {
            AsciiBox(headerLabel = "CATALOG & TRACKER OPTIONS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MonoText(text = "Theme", color = palette.comment, fontSizeSp = 12)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThemeMode.values().forEach { mode ->
                            AsciiRadioButton(
                                label = mode.code,
                                selected = prefs.themeMode == mode,
                                activeColor = palette.orange,
                                onClick = {
                                    onUpdatePrefs(prefs.copy(themeModeCode = mode.code))
                                    onPostStatus("THEME -> ${mode.label}", false)
                                }
                            )
                        }
                    }
                }
                AsciiRule(pattern = "- ")
                AsciiToggleRow(
                    label = "English Language Only",
                    checked = prefs.englishOnly,
                    onToggle = { onUpdatePrefs(prefs.copy(englishOnly = !prefs.englishOnly)) }
                )
                AsciiToggleRow(
                    label = "Hide Abridged Editions",
                    checked = prefs.hideAbridged,
                    onToggle = { onUpdatePrefs(prefs.copy(hideAbridged = !prefs.hideAbridged)) }
                )
                AsciiToggleRow(
                    label = "Hide Dramatized / Full-Cast",
                    checked = prefs.hideDramatized,
                    onToggle = { onUpdatePrefs(prefs.copy(hideDramatized = !prefs.hideDramatized)) }
                )
                AsciiToggleRow(
                    label = "Always Show Card Actions",
                    checked = prefs.alwaysShowCardActions,
                    onToggle = { onUpdatePrefs(prefs.copy(alwaysShowCardActions = !prefs.alwaysShowCardActions)) }
                )
                AsciiToggleRow(
                    label = "Hide Cover Art (Pure Text)",
                    checked = prefs.hideCoverArt,
                    onToggle = {
                        val next = !prefs.hideCoverArt
                        onUpdatePrefs(prefs.copy(hideCoverArt = next))
                        onPostStatus(if (next) "PURE TEXT MODE: ON" else "COVER ART: ON", false)
                    }
                )
                AsciiToggleRow(
                    label = "Daily AudioBookBay Check",
                    checked = prefs.checkAbbDaily,
                    onToggle = { onUpdatePrefs(prefs.copy(checkAbbDaily = !prefs.checkAbbDaily)) }
                )
            }
        }

        item(key = "cfg_region") {
            AsciiBox(headerLabel = "AUDIBLE STORE REGION") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudibleMarketplace.values().forEach { mp ->
                        AsciiRadioButton(
                            label = mp.label,
                            selected = prefs.marketplace == mp,
                            activeColor = palette.cyan,
                            onClick = {
                                onUpdatePrefs(prefs.copy(marketplaceCode = mp.code))
                                onPostStatus("REGION -> ${mp.label}", false)
                            }
                        )
                    }
                }
            }
        }

        item(key = "cfg_notif") {
            AsciiBox(headerLabel = "NOTIFICATION SCHEDULE (T-7d, T-0d, ABB)") {
                AsciiLeaderRow(
                    key = "Daily Alert Time",
                    value = "%02d:%02d Local".format(prefs.notifyHour, prefs.notifyMinute),
                    valueColor = palette.cyan,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)
                ) {
                    val shiftAlertMinutes: (Int) -> Unit = { deltaMins ->
                        val total = ((prefs.notifyHour * 60 + prefs.notifyMinute + deltaMins) % 1440 + 1440) % 1440
                        val h = total / 60
                        val m = total % 60
                        onUpdatePrefs(prefs.copy(notifyHour = h, notifyMinute = m))
                        onPostStatus("ALERT TIME -> %02d:%02d".format(h, m), false)
                    }
                    BracketButton(label = "-1h", onClick = { shiftAlertMinutes(-60) })
                    BracketButton(label = "+1h", onClick = { shiftAlertMinutes(60) })
                    BracketButton(label = "-15m", color = palette.comment, onClick = { shiftAlertMinutes(-15) })
                    BracketButton(label = "+15m", color = palette.cyan, onClick = { shiftAlertMinutes(15) })
                }
            }
        }

        item(key = "cfg_cache") {
            AsciiBox(headerLabel = "METADATA, SYNC STATUS & CACHE") {
                if (isSyncing && syncProgress.total > 0) {
                    AsciiLeaderRow(
                        key = "Active Sync",
                        value = asciiProgressBar(syncProgress.done, syncProgress.total),
                        valueColor = palette.green,
                        fontSizeSp = 12
                    )
                }
                AsciiLeaderRow(
                    key = "Last Synced",
                    value = syncTelemetry.formattedTimestamp,
                    valueColor = palette.foreground,
                    fontSizeSp = 12
                )
                AsciiLeaderRow(
                    key = "Sync Telemetry",
                    value = "${syncTelemetry.lastSyncSourcesCount} src | ${syncTelemetry.statusSummary}",
                    valueColor = if (syncTelemetry.lastSyncUsedCachedFallback) palette.orange else palette.green,
                    fontSizeSp = 12
                )
                AsciiLeaderRow(
                    key = "Disk Cache",
                    value = "${cacheStats.fileCount} files (${cacheStats.formattedSize})",
                    valueColor = palette.cyan,
                    fontSizeSp = 12
                )
                AsciiLeaderRow(
                    key = "Retention Policy",
                    value = "30d / 15 MB LRU",
                    valueColor = palette.comment,
                    fontSizeSp = 11
                )
                Spacer(modifier = Modifier.height(6.dp))
                AsciiRule(pattern = "- ")
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    BracketButton(
                        label = if (isCheckingAbbBatch) "$abbSpinner CHECKING ABB" else "CHECK ABB NOW",
                        color = palette.green,
                        onClick = onRunManualAbbCheck
                    )
                    BracketButton(
                        label = "CLEAR CACHE",
                        color = palette.red,
                        hapticType = HapticFeedbackType.LongPress,
                        onClick = {
                            scope.launch {
                                ThumbnailCache.clear()
                                cacheStats = AudibleApiClient.clearAllCaches()
                                onPostStatus("OK: DISK & MEMORY CACHE CLEARED", false)
                            }
                        }
                    )
                }
            }
        }

        item(key = "cfg_backup") {
            AsciiBox(headerLabel = "PLAIN-TEXT JSON BACKUP & RESTORE") {
                AsciiLeaderRow(
                    key = "Backup Format",
                    value = "Schema v9 (.json)",
                    valueColor = palette.comment,
                    fontSizeSp = 11
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BracketButton(
                        label = "EXPORT .JSON",
                        color = palette.green,
                        onClick = onExportBackup
                    )
                    BracketButton(
                        label = "IMPORT .JSON",
                        color = palette.yellow,
                        onClick = onImportBackup
                    )
                }
            }
        }

        item(key = "cfg_about") {
            AsciiBox(headerLabel = "APPLICATION INFO") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "Version ${BuildConfig.VERSION_NAME}, credits & architecture",
                        color = palette.comment,
                        fontSizeSp = 12,
                        modifier = Modifier.weight(1f)
                    )
                    BracketButton(
                        label = "ABOUT ->",
                        color = palette.purple,
                        onClick = onOpenAbout
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutPageView(onBack: () -> Unit) {
    val palette = LocalPtsPalette.current
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            BracketButton(
                label = "< BACK TO SETTINGS",
                color = palette.cyan,
                onClick = onBack
            )
        }

        item {
            AsciiBox(
                headerLabel = "ABOUT APPLICATION",
                borderColor = palette.purple,
                headerColor = palette.purple
            ) {
                MonoText(
                    text = "NOCTURNETXT",
                    color = palette.purple,
                    fontSizeSp = 18,
                    bold = true
                )
                MonoText(
                    text = "Version ${BuildConfig.VERSION_NAME} | Plain-Text Audiobook Release Tracker",
                    color = palette.cyan,
                    fontSizeSp = 12,
                    bold = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                MonoText(
                    text = "NocturneTXT is a lean, native Android tracker for upcoming audiobook releases across Series, Authors, Narrators, and individual Books.",
                    color = palette.foreground,
                    fontSizeSp = 13
                )
            }
        }

        item {
            AsciiBox(headerLabel = "DESIGN & AESTHETIC CREDITS") {
                MonoText(
                    text = "- Layout & Typography: Inspired by PlainTextSports.com, with pure 7-bit ASCII headers (+---[ ... ]---+), column-aligned SER/AUT/NAR fields, countdown bars, dotted-leader rows, and inverse-video terminal controls.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(4.dp))
                MonoText(
                    text = "- Color Palettes: Official Dracula Dark (#282A36) and Alucard Light (#FFFBEB) specifications with System Auto theme synchronization.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
            }
        }

        item {
            AsciiBox(headerLabel = "ENGINE & PRIVACY") {
                MonoText(
                    text = "- Zero-Bloat Native Stack: Built with Kotlin, Jetpack Compose, Room SQLite, WorkManager, and native HttpURLConnection.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(4.dp))
                MonoText(
                    text = "- Share-Sheet Importer & ABB Verification: Share any Audible URL directly into NocturneTXT to track Series/Author/Book, and verify AudioBookBay availability with strict author + date cutoff guards.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
            }
        }
    }
}

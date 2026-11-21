package com.plaintext.audible.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class AudibleMarketplace(val code: String, val domain: String, val label: String) {
    US("US", "audible.com", "US (.com)"),
    IN("IN", "audible.in", "IN (.in)"),
    UK("UK", "audible.co.uk", "UK (.co.uk)"),
    CA("CA", "audible.ca", "CA (.ca)"),
    AU("AU", "audible.com.au", "AU (.com.au)");

    companion object {
        fun fromCode(code: String): AudibleMarketplace =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: US
    }
}

enum class ThemeMode(val code: String, val label: String) {
    SYSTEM("SYSTEM", "SYSTEM AUTO"),
    DARK("DARK", "DRACULA"),
    LIGHT("LIGHT", "ALUCARD");

    companion object {
        fun fromCode(code: String): ThemeMode =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}

@Immutable
data class SyncProgressState(
    val done: Int = 0,
    val total: Int = 0
)

@Immutable
data class SyncTelemetry(
    val lastSyncEpochMs: Long = 0L,
    val lastSyncSourcesCount: Int = 0,
    val lastSyncErrorCount: Int = 0,
    val lastSyncUsedCachedFallback: Boolean = false
) {
    val formattedTimestamp: String
        get() {
            if (lastSyncEpochMs <= 0L) return "Never"
            val dt = Instant.ofEpochMilli(lastSyncEpochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
            return dt.format(fmt)
        }

    val statusSummary: String
        get() = when {
            lastSyncEpochMs <= 0L -> "IDLE"
            lastSyncUsedCachedFallback -> "OFFLINE CACHE ($lastSyncErrorCount ERR)"
            lastSyncErrorCount > 0 -> "$lastSyncErrorCount NET ERR"
            else -> "OK (0 ERR)"
        }
}

@Immutable
data class FilterPrefs(
    val englishOnly: Boolean = true,
    val hideAbridged: Boolean = true,
    val hideDramatized: Boolean = false,
    val alwaysShowCardActions: Boolean = false,
    val checkAbbDaily: Boolean = true,
    val hideCoverArt: Boolean = false,
    val themeModeCode: String = "SYSTEM",
    val notifyHour: Int = 9,
    val notifyMinute: Int = 0,
    val marketplaceCode: String = "US"
) {
    val marketplace: AudibleMarketplace
        get() = AudibleMarketplace.fromCode(marketplaceCode)

    val themeMode: ThemeMode
        get() = ThemeMode.fromCode(themeModeCode)
}

@Immutable
data class MarkReadOutcome(
    val readBook: ReadBookEntity,
    val archivedTrackedBook: TrackedItemEntity? = null,
    val previousSeriesSequences: List<Pair<Long, Int>> = emptyList(),
    val wasPinned: PinnedReleaseEntity? = null
)

class ReleaseRepository(context: Context) {
    private val db = AppDatabase.get(context)
    val dao = db.dao()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pts_audible_prefs", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()
    private val syncSemaphore = Semaphore(4)
    private val abbSemaphore = Semaphore(1)

    private val _syncProgress = MutableStateFlow(SyncProgressState())
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isCheckingAbb = MutableStateFlow(false)
    val isCheckingAbb: StateFlow<Boolean> = _isCheckingAbb.asStateFlow()

    private val _prefsFlow = MutableStateFlow(readPrefs())
    val prefsFlow: StateFlow<FilterPrefs> = _prefsFlow.asStateFlow()

    private val _telemetryFlow = MutableStateFlow(readSyncTelemetry())
    val telemetryFlow: StateFlow<SyncTelemetry> = _telemetryFlow.asStateFlow()

    companion object {
        private const val ABB_DAILY_INTERVAL_MS = 20L * 60L * 60L * 1000L

        fun normalizeTitleForGrouping(rawTitle: String): String {
            val beforeColon = rawTitle.substringBefore(":").trim().ifBlank { rawTitle }
            return beforeColon.lowercase(Locale.US)
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("\\bbook\\s+viii\\b"), "book 8")
                .replace(Regex("\\bbook\\s+vii\\b"), "book 7")
                .replace(Regex("\\bbook\\s+vi\\b"), "book 6")
                .replace(Regex("\\bbook\\s+iv\\b"), "book 4")
                .replace(Regex("\\bbook\\s+v\\b"), "book 5")
                .replace(Regex("\\bbook\\s+iii\\b"), "book 3")
                .replace(Regex("\\bbook\\s+ii\\b"), "book 2")
                .replace(Regex("\\bbook\\s+i\\b"), "book 1")
                .replace(Regex("[^a-z0-9]"), "")
        }

        fun computeGroupKey(title: String, authors: String): String {
            val normT = normalizeTitleForGrouping(title)
            val normA = authors.split(",").firstOrNull()?.lowercase(Locale.US)
                ?.replace(Regex("[^a-z0-9]"), "") ?: ""
            return "${normT}_${normA}"
        }

        fun isReleaseRead(
            rel: ReleaseEntity,
            readAsins: Set<String>,
            readGroupKeys: Set<String>
        ): Boolean {
            if (rel.asin.lowercase(Locale.US) in readAsins) return true
            if (rel.altAsins.isNotBlank()) {
                val anyAlt = rel.altAsins.split(",").any { it.trim().lowercase(Locale.US) in readAsins }
                if (anyAlt) return true
            }
            val gk = computeGroupKey(rel.title, rel.authors)
            return gk.isNotBlank() && gk in readGroupKeys
        }

        fun isReleasePinned(
            rel: ReleaseEntity,
            pinnedAsins: Set<String>,
            pinnedGroupKeys: Set<String>
        ): Boolean {
            if (rel.asin.lowercase(Locale.US) in pinnedAsins) return true
            val gk = computeGroupKey(rel.title, rel.authors)
            return gk.isNotBlank() && gk in pinnedGroupKeys
        }

        fun matchesPersonNameExactOrWordBoundary(candidateName: String, targetQuery: String): Boolean {
            val cand = candidateName.trim()
            val target = targetQuery.trim()
            if (cand.isBlank() || target.isBlank()) return false
            if (cand.equals(target, ignoreCase = true)) return true
            if (AudibleApiClient.isExactAuthorNameMatch(cand, target)) return true
            val escaped = Regex.escape(target)
            return Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE).containsMatchIn(cand)
        }

        fun releaseMatchesTrackedItem(rel: ReleaseEntity, item: TrackedItemEntity): Boolean {
            val q = item.queryValue.trim()
            val pAsin = item.pinnedAsin
            val pAuthor = item.pinnedAuthor
            return when (item.type) {
                TrackType.SERIES -> {
                    val asinMatch = pAsin.isNotBlank() &&
                        rel.seriesAsin.isNotBlank() &&
                        rel.seriesAsin.equals(pAsin, ignoreCase = true)
                    val titleMatch = rel.seriesTitle.isNotBlank() && (
                        rel.seriesTitle.equals(q, ignoreCase = true) ||
                            rel.seriesTitle.equals("$q Series", ignoreCase = true) ||
                            rel.seriesTitle.equals("The $q Series", ignoreCase = true) ||
                            (pAsin.isBlank() && rel.seriesTitle.contains(q, ignoreCase = true))
                        )
                    val authorMatch = pAuthor.isBlank() || rel.authors.split(",").any { a ->
                        matchesPersonNameExactOrWordBoundary(a, pAuthor)
                    }
                    asinMatch || (titleMatch && authorMatch)
                }
                TrackType.AUTHOR -> rel.authors.split(",").take(2).any { a ->
                    matchesPersonNameExactOrWordBoundary(a, q)
                }
                TrackType.NARRATOR -> rel.narrators.split(",").any { n ->
                    matchesPersonNameExactOrWordBoundary(n, q)
                }
                TrackType.BOOK -> {
                    val altAsinMatch = pAsin.isNotBlank() && rel.altAsins.split(",").any {
                        it.trim().equals(pAsin, ignoreCase = true)
                    }
                    (pAsin.isNotBlank() && rel.asin.equals(pAsin, ignoreCase = true)) ||
                        altAsinMatch ||
                        rel.asin.equals(q, ignoreCase = true) ||
                        (rel.title.equals(q, ignoreCase = true) &&
                            (pAuthor.isBlank() || rel.authors.split(",").any { a ->
                                matchesPersonNameExactOrWordBoundary(a, pAuthor)
                            }))
                }
            }
        }
    }

    init {
        AudibleApiClient.initCache(context.applicationContext.cacheDir)
        AudibleApiClient.activeMarketplace = _prefsFlow.value.marketplace
    }

    private fun readPrefs(): FilterPrefs = FilterPrefs(
        englishOnly = prefs.getBoolean("english_only", true),
        hideAbridged = prefs.getBoolean("hide_abridged", true),
        hideDramatized = prefs.getBoolean("hide_dramatized", false),
        alwaysShowCardActions = prefs.getBoolean("always_show_card_actions", false),
        checkAbbDaily = prefs.getBoolean("check_abb_daily", true),
        hideCoverArt = prefs.getBoolean("hide_cover_art", false),
        themeModeCode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
        notifyHour = prefs.getInt("notify_hour", 9).coerceIn(0, 23),
        notifyMinute = prefs.getInt("notify_minute", 0).coerceIn(0, 59),
        marketplaceCode = prefs.getString("marketplace_code", "US") ?: "US"
    )

    fun getPrefs(): FilterPrefs = readPrefs().also { _prefsFlow.value = it }

    fun savePrefs(newPrefs: FilterPrefs) {
        val sanitized = newPrefs.copy(
            notifyHour = newPrefs.notifyHour.coerceIn(0, 23),
            notifyMinute = newPrefs.notifyMinute.coerceIn(0, 59)
        )
        prefs.edit()
            .putBoolean("english_only", sanitized.englishOnly)
            .putBoolean("hide_abridged", sanitized.hideAbridged)
            .putBoolean("hide_dramatized", sanitized.hideDramatized)
            .putBoolean("always_show_card_actions", sanitized.alwaysShowCardActions)
            .putBoolean("check_abb_daily", sanitized.checkAbbDaily)
            .putBoolean("hide_cover_art", sanitized.hideCoverArt)
            .putString("theme_mode", sanitized.themeModeCode)
            .putInt("notify_hour", sanitized.notifyHour)
            .putInt("notify_minute", sanitized.notifyMinute)
            .putString("marketplace_code", sanitized.marketplaceCode)
            .apply()
        AudibleApiClient.activeMarketplace = sanitized.marketplace
        _prefsFlow.value = sanitized
    }

    private fun readSyncTelemetry(): SyncTelemetry = SyncTelemetry(
        lastSyncEpochMs = prefs.getLong("last_sync_epoch_ms", 0L),
        lastSyncSourcesCount = prefs.getInt("last_sync_sources_count", 0),
        lastSyncErrorCount = prefs.getInt("last_sync_error_count", 0),
        lastSyncUsedCachedFallback = prefs.getBoolean("last_sync_used_cached_fallback", false)
    )

    fun getSyncTelemetry(): SyncTelemetry = readSyncTelemetry().also { _telemetryFlow.value = it }

    private fun saveSyncTelemetry(sourcesCount: Int, errorCount: Int, usedCachedFallback: Boolean) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong("last_sync_epoch_ms", now)
            .putInt("last_sync_sources_count", sourcesCount)
            .putInt("last_sync_error_count", errorCount)
            .putBoolean("last_sync_used_cached_fallback", usedCachedFallback)
            .apply()
        _telemetryFlow.value = SyncTelemetry(
            lastSyncEpochMs = now,
            lastSyncSourcesCount = sourcesCount,
            lastSyncErrorCount = errorCount,
            lastSyncUsedCachedFallback = usedCachedFallback
        )
    }

    suspend fun addTrackedItemUnique(
        type: TrackType,
        rawQuery: String,
        lastReadSequence: Int = 0,
        initialThumbnailUrl: String = "",
        externalId: String? = null
    ): Boolean {
        val cleaned = if (type == TrackType.AUTHOR || type == TrackType.NARRATOR || type == TrackType.SERIES) {
            AudibleApiClient.formatCanonicalName(rawQuery)
        } else {
            rawQuery.trim()
        }
        if (cleaned.isBlank()) return false

        val existing = dao.findTrackedItem(type, cleaned)
        if (existing != null) {
            if (type == TrackType.SERIES && lastReadSequence > existing.lastReadSequence) {
                dao.updateSeriesReadSequence(existing.id, lastReadSequence)
            }
            val updatedExt = if (existing.externalId.isNullOrBlank() && !externalId.isNullOrBlank()) {
                externalId
            } else {
                existing.externalId
            }
            val updatedThumb = existing.thumbnailUrl.ifBlank { initialThumbnailUrl }
            if (updatedExt != existing.externalId || updatedThumb != existing.thumbnailUrl) {
                dao.updateTrackedItemDetails(existing.id, existing.queryValue, updatedExt, updatedThumb)
            }
            return false
        }

        val resolvedThumb = when (type) {
            TrackType.AUTHOR -> {
                AudibleApiClient.fetchAudibleAuthorImageUrl(cleaned, externalId?.substringBefore("|")) ?: initialThumbnailUrl
            }
            TrackType.NARRATOR -> {
                AudibleApiClient.fetchNarratorPortraitUrl(cleaned) ?: initialThumbnailUrl
            }
            else -> initialThumbnailUrl
        }

        dao.insertTrackedItem(
            TrackedItemEntity(
                type = type,
                queryValue = cleaned,
                externalId = externalId,
                lastReadSequence = lastReadSequence,
                thumbnailUrl = resolvedThumb
            )
        )
        return true
    }

    private data class EditionGroup(
        var primaryProduct: CatalogProduct,
        val matchedSources: MutableSet<String> = linkedSetOf(),
        val editionAsins: MutableSet<String> = linkedSetOf()
    )

    private fun mergeDuplicateEditions(existing: CatalogProduct, candidate: CatalogProduct): CatalogProduct {
        val chosenCover = when {
            existing.coverUrl.isNotBlank() && candidate.coverUrl.isBlank() -> existing.coverUrl
            existing.coverUrl.isBlank() && candidate.coverUrl.isNotBlank() -> candidate.coverUrl
            else -> existing.coverUrl.ifBlank { candidate.coverUrl }
        }
        val chosenSeriesTitle = existing.seriesTitle.ifBlank { candidate.seriesTitle }
        val chosenSeriesAsin = existing.seriesAsin.ifBlank { candidate.seriesAsin }
        val chosenSeriesSeq = existing.seriesSequence ?: candidate.seriesSequence

        val chosenReleaseDate = when {
            existing.releaseDateIso == "TBA" -> candidate.releaseDateIso
            candidate.releaseDateIso == "TBA" -> existing.releaseDateIso
            candidate.releaseDateIso < existing.releaseDateIso -> candidate.releaseDateIso
            else -> existing.releaseDateIso
        }

        val chosenSynopsis = if (candidate.synopsis.length > existing.synopsis.length) {
            candidate.synopsis
        } else {
            existing.synopsis
        }
        val chosenRuntime = maxOf(existing.runtimeMinutes, candidate.runtimeMinutes)
        val chosenPublisher = existing.publisher.ifBlank { candidate.publisher }
        val chosenNarrators = if (existing.narrators.isNotEmpty()) existing.narrators else candidate.narrators

        val base = if (existing.coverUrl.isBlank() && candidate.coverUrl.isNotBlank()) candidate else existing
        return base.copy(
            coverUrl = chosenCover,
            seriesTitle = chosenSeriesTitle,
            seriesAsin = chosenSeriesAsin,
            seriesSequence = chosenSeriesSeq,
            releaseDateIso = chosenReleaseDate,
            synopsis = chosenSynopsis,
            runtimeMinutes = chosenRuntime,
            publisher = chosenPublisher,
            narrators = chosenNarrators
        )
    }

    suspend fun checkSingleReleaseOnAbb(rel: ReleaseEntity): Boolean {
        return abbSemaphore.withPermit {
            val match = AudibleApiClient.checkAudioBookBayAvailability(
                bookTitle = rel.title,
                allAuthors = rel.authors,
                releaseDateIso = rel.releaseDateIso,
                seriesTitle = rel.seriesTitle,
                seriesSequence = rel.seriesSequence
            )
            if (match.available) {
                dao.updateAbbAvailability(
                    asin = rel.asin,
                    available = true,
                    postUrl = match.postUrl,
                    magnetUri = match.magnetUri
                )
            }
            match.available
        }
    }

    suspend fun checkPendingAbbReleases(forceAbbCheck: Boolean = false): List<ReleaseEntity> {
        val filterPrefs = getPrefs()
        if (!forceAbbCheck && !filterPrefs.checkAbbDaily) return dao.getAllReleases()

        val nowMs = System.currentTimeMillis()
        val lastAbbCheckMs = prefs.getLong("last_abb_check_ms", 0L)
        if (!forceAbbCheck && (nowMs - lastAbbCheckMs) < ABB_DAILY_INTERVAL_MS) {
            return dao.getAllReleases()
        }

        val readBooks = dao.getAllReadBooks()
        val readAsins = readBooks.map { it.asin.lowercase(Locale.US) }.toSet()
        val readGroupKeys = readBooks.mapNotNull { it.groupKey.takeIf { gk -> gk.isNotBlank() } }.toSet()

        val todayIso = LocalDate.now().toString()
        val candidates = dao.getAllReleases()
            .filter { rel ->
                !rel.abbAvailable &&
                    rel.releaseDateIso != "TBA" &&
                    rel.releaseDateIso <= todayIso &&
                    !isReleaseRead(rel, readAsins, readGroupKeys)
            }
            .sortedByDescending { it.releaseDateIso }

        if (candidates.isEmpty()) {
            prefs.edit().putLong("last_abb_check_ms", nowMs).apply()
            return dao.getAllReleases()
        }

        abbSemaphore.withPermit {
            _isCheckingAbb.value = true
            try {
                for ((index, rel) in candidates.take(10).withIndex()) {
                    if (index > 0) {
                        delay(1400L)
                    }
                    val match = AudibleApiClient.checkAudioBookBayAvailability(
                        bookTitle = rel.title,
                        allAuthors = rel.authors,
                        releaseDateIso = rel.releaseDateIso,
                        seriesTitle = rel.seriesTitle,
                        seriesSequence = rel.seriesSequence
                    )
                    if (match.available) {
                        dao.updateAbbAvailability(
                            asin = rel.asin,
                            available = true,
                            postUrl = match.postUrl,
                            magnetUri = match.magnetUri
                        )
                    }
                }
            } finally {
                _isCheckingAbb.value = false
            }
        }

        prefs.edit().putLong("last_abb_check_ms", nowMs).apply()
        return dao.getAllReleases()
    }

    suspend fun runManualAbbCheck(): Int {
        val beforeCount = dao.getAllReleases().count { it.abbAvailable }
        val after = checkPendingAbbReleases(forceAbbCheck = true)
        return (after.count { it.abbAvailable } - beforeCount).coerceAtLeast(0)
    }

    suspend fun syncCatalog(forceRefresh: Boolean = false): List<ReleaseEntity> = syncMutex.withLock {
        _isSyncing.value = true
        try {
            syncCatalogInternal(forceRefresh)
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun syncCatalogInternal(forceRefresh: Boolean): List<ReleaseEntity> {
        val filterPrefs = getPrefs()
        AudibleApiClient.activeMarketplace = filterPrefs.marketplace
        AudibleApiClient.pruneStaleDiskCache()
        AudibleApiClient.syncNetworkErrorCount.set(0)
        AudibleApiClient.syncUsedStaleCacheCount.set(0)

        val tracked = dao.getAllTrackedItems()
        if (tracked.isEmpty()) {
            _syncProgress.value = SyncProgressState(0, 0)
            dao.clearAllReleases()
            saveSyncTelemetry(sourcesCount = 0, errorCount = 0, usedCachedFallback = false)
            return emptyList()
        }

        val completedCounter = AtomicInteger(0)
        _syncProgress.value = SyncProgressState(done = 0, total = tracked.size)

        val dndRules = dao.getAllDndRules()
        val existingReleases = dao.getAllReleases()
        val existingByAsin = existingReleases.associateBy { it.asin }
        val existingByGroupKey = existingReleases.associateBy { rel ->
            val normT = normalizeTitleForGrouping(rel.title)
            val normA = rel.authors.split(",").firstOrNull()?.lowercase(Locale.US)
                ?.replace(Regex("[^a-z0-9]"), "") ?: ""
            "${normT}_${normA}"
        }

        val todayIso = LocalDate.now().toString()
        val recentCutoffIso = LocalDate.now().minusDays(30).toString()

        val dndBooks = dndRules.filter { it.targetType == DndTargetType.BOOK }
            .map { it.targetKey.lowercase(Locale.US) }.toSet()
        val dndSeries = dndRules.filter { it.targetType == DndTargetType.SERIES }
            .map { it.targetKey.lowercase(Locale.US) }.toSet()

        val fetchedPairs = coroutineScope {
            tracked.map { item ->
                async {
                    syncSemaphore.withPermit {
                        val results = when (item.type) {
                            TrackType.AUTHOR -> AudibleApiClient.searchCatalog(
                                author = item.queryValue,
                                maxPages = 3,
                                forceRefresh = forceRefresh
                            )
                            TrackType.NARRATOR -> AudibleApiClient.searchCatalog(
                                narrator = item.queryValue,
                                maxPages = 2,
                                forceRefresh = forceRefresh
                            )
                            TrackType.SERIES -> {
                                val pinnedAuthor = item.pinnedAuthor
                                AudibleApiClient.searchCatalog(
                                    author = pinnedAuthor.ifBlank { null },
                                    titleOrSeries = item.queryValue,
                                    maxPages = 2,
                                    forceRefresh = forceRefresh
                                )
                            }
                            TrackType.BOOK -> AudibleApiClient.searchCatalog(
                                asin = item.pinnedAsin.ifBlank { item.queryValue },
                                titleOrSeries = item.queryValue,
                                maxPages = 1,
                                forceRefresh = forceRefresh
                            )
                        }
                        val doneNow = completedCounter.incrementAndGet()
                        _syncProgress.value = SyncProgressState(done = doneNow, total = tracked.size)
                        item to results
                    }
                }
            }.awaitAll()
        }

        val assignedThumbnails = tracked.mapNotNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } }.toMutableSet()
        val bookCoversSet = fetchedPairs.flatMap { it.second }.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }.toSet()
        val effectiveExternalIds = mutableMapOf<Long, String?>()

        for ((item, results) in fetchedPairs) {
            val canonicalName = when (item.type) {
                TrackType.AUTHOR -> {
                    results.flatMap { it.authors }
                        .firstOrNull { AudibleApiClient.isExactAuthorNameMatch(it, item.queryValue) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.SERIES -> {
                    results.map { it.seriesTitle }
                        .firstOrNull { it.equals(item.queryValue, ignoreCase = true) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.NARRATOR -> {
                    results.flatMap { it.narrators }
                        .firstOrNull { AudibleApiClient.isExactAuthorNameMatch(it, item.queryValue) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.BOOK -> item.queryValue
            }

            // Strictly filter results to products that actually belong to this tracked source
            val matchingProducts = when (item.type) {
                TrackType.AUTHOR -> results.filter { prod ->
                    prod.authors.any { a -> matchesPersonNameExactOrWordBoundary(a, canonicalName) }
                }
                TrackType.NARRATOR -> results.filter { prod ->
                    prod.narrators.any { n -> matchesPersonNameExactOrWordBoundary(n, canonicalName) }
                }
                TrackType.SERIES -> results.filter { prod ->
                    prod.seriesTitle.equals(canonicalName, ignoreCase = true) ||
                        prod.seriesTitle.contains(canonicalName, ignoreCase = true)
                }
                TrackType.BOOK -> results
            }

            val updatedExtId: String? = when (item.type) {
                TrackType.SERIES -> {
                    if (!item.externalId.isNullOrBlank() && item.externalId.contains("|")) {
                        item.externalId
                    } else {
                        val exactSeriesProd = matchingProducts.firstOrNull { prod ->
                            prod.seriesTitle.equals(item.queryValue.trim(), ignoreCase = true) &&
                                prod.authors.isNotEmpty()
                        } ?: matchingProducts.firstOrNull { prod -> prod.authors.isNotEmpty() }
                        if (exactSeriesProd != null) {
                            val sAsin = exactSeriesProd.seriesAsin.ifBlank { item.pinnedAsin }
                            val pAuth = exactSeriesProd.authors.firstOrNull().orEmpty()
                            "$sAsin|$pAuth"
                        } else {
                            item.externalId
                        }
                    }
                }
                TrackType.BOOK -> {
                    if (!item.externalId.isNullOrBlank() && item.externalId.contains("|")) {
                        item.externalId
                    } else {
                        val exactBookProd = matchingProducts.firstOrNull()
                        if (exactBookProd != null) {
                            "${exactBookProd.asin}|${exactBookProd.authors.firstOrNull().orEmpty()}"
                        } else {
                            item.externalId
                        }
                    }
                }
                TrackType.AUTHOR -> {
                    item.externalId?.takeIf { it.isNotBlank() }
                        ?: matchingProducts.firstOrNull { prod ->
                            prod.primaryAuthorAsin.isNotBlank() &&
                                prod.authors.firstOrNull()?.let { a -> AudibleApiClient.isExactAuthorNameMatch(a, canonicalName) } == true
                        }?.primaryAuthorAsin
                }
                TrackType.NARRATOR -> item.externalId
            }

            effectiveExternalIds[item.id] = updatedExtId

            val matchingCovers = matchingProducts.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }
            val isVerifiedPortrait = item.thumbnailUrl.contains("amzn-author-media", ignoreCase = true) ||
                item.thumbnailUrl.contains("wikimedia.org", ignoreCase = true)

            val isAuthorThumbMissingOrStale = item.type == TrackType.AUTHOR && (
                item.thumbnailUrl.isBlank() ||
                    item.thumbnailUrl in bookCoversSet ||
                    (!isVerifiedPortrait && item.thumbnailUrl !in matchingCovers) ||
                    forceRefresh && !isVerifiedPortrait
            )

            var updatedThumb = item.thumbnailUrl
            if (item.thumbnailUrl.isBlank() || isAuthorThumbMissingOrStale) {
                val portrait = when (item.type) {
                    TrackType.AUTHOR -> AudibleApiClient.fetchAudibleAuthorImageUrl(
                        canonicalName,
                        updatedExtId?.substringBefore("|")
                    )
                    TrackType.NARRATOR -> AudibleApiClient.fetchNarratorPortraitUrl(canonicalName)
                    else -> null
                }
                if (!portrait.isNullOrBlank()) {
                    updatedThumb = portrait
                } else if (matchingCovers.isNotEmpty()) {
                    // Only fall back to covers of books ACTUALLY written/narrated by this source
                    updatedThumb = matchingCovers.firstOrNull { it !in assignedThumbnails }
                        ?: matchingCovers.first()
                } else if (isAuthorThumbMissingOrStale && !isVerifiedPortrait) {
                    // Clear stale wrong cover rather than keeping an unrelated author's image
                    updatedThumb = ""
                }
            }

            if (updatedThumb != item.thumbnailUrl || canonicalName != item.queryValue || updatedExtId != item.externalId) {
                if (updatedThumb.isNotBlank()) assignedThumbnails.add(updatedThumb)
                dao.updateTrackedItemDetails(
                    id = item.id,
                    queryValue = canonicalName,
                    extId = updatedExtId,
                    url = updatedThumb
                )
            }
        }

        val trackedSeriesLastRead = tracked.filter { it.type == TrackType.SERIES }
            .associate { it.queryValue.lowercase(Locale.US) to it.lastReadSequence }

        val groupedEditions = linkedMapOf<String, EditionGroup>()

        for ((item, results) in fetchedPairs) {
            val qNorm = item.queryValue.trim()
            val effExt = effectiveExternalIds[item.id] ?: item.externalId
            val pinnedAsin = effExt?.substringBefore("|")?.trim().orEmpty()
            val pinnedAuthor = if (effExt?.contains("|") == true) effExt.substringAfter("|").trim() else ""

            for (prod in results) {
                when (item.type) {
                    TrackType.SERIES -> {
                        val asinExactMatch = pinnedAsin.isNotBlank() &&
                            prod.seriesAsin.isNotBlank() &&
                            prod.seriesAsin.equals(pinnedAsin, ignoreCase = true)

                        val titleMatchesSeries = prod.seriesTitle.isNotBlank() && (
                            prod.seriesTitle.equals(qNorm, ignoreCase = true) ||
                                prod.seriesTitle.equals("$qNorm Series", ignoreCase = true) ||
                                prod.seriesTitle.equals("The $qNorm Series", ignoreCase = true) ||
                                (pinnedAsin.isBlank() && prod.seriesTitle.contains(qNorm, ignoreCase = true))
                        )

                        val authorMatchesPinned = pinnedAuthor.isBlank() || prod.authors.any { a ->
                            matchesPersonNameExactOrWordBoundary(a, pinnedAuthor)
                        }

                        if (!asinExactMatch && !(titleMatchesSeries && authorMatchesPinned)) {
                            continue
                        }
                    }
                    TrackType.AUTHOR -> {
                        val authorMatches = prod.authors.take(2).any { a ->
                            matchesPersonNameExactOrWordBoundary(a, qNorm)
                        }
                        if (!authorMatches) continue
                    }
                    TrackType.NARRATOR -> {
                        val narratorMatches = prod.narrators.any { n ->
                            matchesPersonNameExactOrWordBoundary(n, qNorm)
                        }
                        if (!narratorMatches) continue
                    }
                    TrackType.BOOK -> {
                        val asinMatches = (pinnedAsin.isNotBlank() && prod.asin.equals(pinnedAsin, ignoreCase = true)) ||
                            prod.asin.equals(qNorm, ignoreCase = true)
                        val titleAndAuthorMatches = prod.title.equals(qNorm, ignoreCase = true) &&
                            (pinnedAuthor.isBlank() || prod.authors.any { a -> matchesPersonNameExactOrWordBoundary(a, pinnedAuthor) })
                        if (!asinMatches && !titleAndAuthorMatches) continue
                    }
                }

                if (item.type != TrackType.BOOK &&
                    prod.releaseDateIso != "TBA" &&
                    prod.releaseDateIso < recentCutoffIso
                ) {
                    continue
                }

                if (prod.seriesTitle.isNotBlank() && prod.seriesSequence != null && prod.releaseDateIso > todayIso) {
                    val sKey = prod.seriesTitle.trim().lowercase(Locale.US)
                    val userLastRead = trackedSeriesLastRead.entries
                        .firstOrNull { (k, _) -> sKey.equals(k, ignoreCase = true) || sKey.contains(k) }?.value ?: 0
                    if (userLastRead > 0 && prod.seriesSequence <= userLastRead) {
                        continue
                    }
                }

                if (filterPrefs.englishOnly && !prod.language.equals("english", ignoreCase = true)) {
                    continue
                }
                val isAbridged = prod.formatType.equals("abridged", ignoreCase = true)
                val isDramatized = prod.title.contains("Dramatized Adaptation", ignoreCase = true) ||
                    prod.subtitle.contains("Dramatized Adaptation", ignoreCase = true) ||
                    prod.authors.any { it.contains("Full Cast", ignoreCase = true) }

                if (filterPrefs.hideAbridged && isAbridged) continue
                if (filterPrefs.hideDramatized && isDramatized) continue

                if (prod.asin.lowercase(Locale.US) in dndBooks) continue
                if (prod.seriesTitle.isNotBlank() && prod.seriesTitle.lowercase(Locale.US) in dndSeries) continue
                if (prod.seriesAsin.isNotBlank() && prod.seriesAsin.lowercase(Locale.US) in dndSeries) continue

                val normTitle = normalizeTitleForGrouping(prod.title)
                val normAuthor = prod.authors.firstOrNull()?.lowercase(Locale.US)
                    ?.replace(Regex("[^a-z0-9]"), "") ?: ""
                val groupKey = "${normTitle}_${normAuthor}"

                val existingGroup = groupedEditions[groupKey]
                if (existingGroup == null) {
                    val newGroup = EditionGroup(primaryProduct = prod)
                    newGroup.matchedSources.add(item.type.name)
                    newGroup.editionAsins.add(prod.asin)
                    groupedEditions[groupKey] = newGroup
                } else {
                    existingGroup.primaryProduct = mergeDuplicateEditions(existingGroup.primaryProduct, prod)
                    existingGroup.matchedSources.add(item.type.name)
                    existingGroup.editionAsins.add(prod.asin)
                }
            }
        }

        val finalEntities = groupedEditions.entries.map { (groupKey, group) ->
            val prod = group.primaryProduct
            val prev = existingByAsin[prod.asin] ?: existingByGroupKey[groupKey]
            val isDramatized = prod.title.contains("Dramatized", ignoreCase = true) ||
                prod.subtitle.contains("Dramatized", ignoreCase = true)

            val newNarratorsStr = prod.narrators.joinToString(", ").ifBlank { "TBA" }

            val didDateShiftNow = prev != null &&
                prev.releaseDateIso != prod.releaseDateIso &&
                prev.releaseDateIso.isNotBlank()
            val shiftedFromDate = when {
                prev == null -> ""
                didDateShiftNow -> prev.releaseDateIso
                else -> prev.previousReleaseDateIso
            }

            val didNarratorAnnounceNow = prev != null &&
                prev.narrators.equals("TBA", ignoreCase = true) &&
                !newNarratorsStr.equals("TBA", ignoreCase = true)
            val narratorNewlyAnnounced = when {
                prev == null -> false
                didNarratorAnnounceNow -> true
                else -> prev.narratorJustAnnounced
            }

            ReleaseEntity(
                asin = prod.asin,
                title = prod.title,
                subtitle = prod.subtitle,
                authors = prod.authors.joinToString(", ").ifBlank { "Unknown Author" },
                narrators = newNarratorsStr,
                seriesTitle = prod.seriesTitle,
                seriesAsin = prod.seriesAsin,
                seriesSequence = prod.seriesSequence,
                releaseDateIso = prod.releaseDateIso,
                language = prod.language.replaceFirstChar { it.uppercase() },
                isAbridged = prod.formatType.equals("abridged", ignoreCase = true),
                isDramatized = isDramatized,
                matchedTags = group.matchedSources.joinToString(","),
                isNewDiscovery = prev?.isNewDiscovery ?: true,
                notifiedSevenDay = prev?.notifiedSevenDay ?: false,
                notifiedReleaseDay = prev?.notifiedReleaseDay ?: false,
                firstSeenEpochMs = prev?.firstSeenEpochMs ?: System.currentTimeMillis(),
                coverUrl = prod.coverUrl,
                synopsis = prod.synopsis,
                runtimeMinutes = prod.runtimeMinutes,
                publisher = prod.publisher,
                altAsins = group.editionAsins.joinToString(","),
                previousReleaseDateIso = shiftedFromDate,
                narratorJustAnnounced = narratorNewlyAnnounced,
                abbAvailable = prev?.abbAvailable ?: false,
                abbPostUrl = prev?.abbPostUrl ?: "",
                abbMagnetUri = prev?.abbMagnetUri ?: "",
                notifiedAbbAvailable = prev?.notifiedAbbAvailable ?: false,
                notifiedDateShift = if (didDateShiftNow) false else (prev?.notifiedDateShift ?: false),
                notifiedNarratorAnnounced = if (didNarratorAnnounceNow) false else (prev?.notifiedNarratorAnnounced ?: false)
            )
        }

        val errCount = AudibleApiClient.syncNetworkErrorCount.get()
        val staleCount = AudibleApiClient.syncUsedStaleCacheCount.get()
        if (finalEntities.isEmpty() && existingReleases.isNotEmpty() && errCount > 0) {
            saveSyncTelemetry(
                sourcesCount = tracked.size,
                errorCount = errCount,
                usedCachedFallback = true
            )
            return existingReleases
        }

        dao.replaceSyncedReleases(finalEntities)
        saveSyncTelemetry(
            sourcesCount = tracked.size,
            errorCount = errCount,
            usedCachedFallback = staleCount > 0
        )

        return checkPendingAbbReleases(forceAbbCheck = forceRefresh)
    }

    suspend fun markReleaseRead(rel: ReleaseEntity): MarkReadOutcome {
        val groupKey = computeGroupKey(rel.title, rel.authors)
        val readBook = ReadBookEntity(
            asin = rel.asin,
            groupKey = groupKey,
            title = rel.title,
            authors = rel.authors,
            narrators = rel.narrators,
            seriesTitle = rel.seriesTitle,
            seriesAsin = rel.seriesAsin,
            seriesSequence = rel.seriesSequence,
            runtimeMinutes = rel.runtimeMinutes,
            coverUrl = rel.coverUrl,
            releaseDateIso = rel.releaseDateIso,
            readAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertReadBook(readBook)

        // Advance lastReadSequence on any matching tracked SERIES
        val allTracked = dao.getAllTrackedItems()
        val updatedSeriesPrev = mutableListOf<Pair<Long, Int>>()
        val seq = rel.seriesSequence
        if (rel.seriesTitle.isNotBlank() && seq != null) {
            allTracked.filter { it.type == TrackType.SERIES }.forEach { s ->
                if (releaseMatchesTrackedItem(rel, s) && seq > s.lastReadSequence) {
                    updatedSeriesPrev += (s.id to s.lastReadSequence)
                    dao.updateSeriesReadSequence(s.id, seq)
                }
            }
        }

        // Auto-complete standalone tracked BOOK if matched
        val matchedBook = allTracked.firstOrNull {
            it.type == TrackType.BOOK && releaseMatchesTrackedItem(rel, it)
        }
        if (matchedBook != null) {
            dao.deleteTrackedItem(matchedBook)
        }

        // Clear any pin on this release (store for undo)
        val existingPin = dao.getAllPinnedReleases().firstOrNull {
            it.asin.equals(rel.asin, ignoreCase = true) ||
                (it.groupKey.isNotBlank() && it.groupKey == groupKey)
        }
        if (existingPin != null) {
            dao.deletePinnedReleaseByAsin(existingPin.asin)
            if (groupKey.isNotBlank()) dao.deletePinnedReleaseByGroupKey(groupKey)
        }

        return MarkReadOutcome(
            readBook = readBook,
            archivedTrackedBook = matchedBook,
            previousSeriesSequences = updatedSeriesPrev,
            wasPinned = existingPin
        )
    }

    suspend fun undoMarkRead(outcome: MarkReadOutcome) {
        dao.deleteReadBookByAsin(outcome.readBook.asin)
        if (outcome.readBook.groupKey.isNotBlank()) {
            dao.deleteReadBookByGroupKey(outcome.readBook.groupKey)
        }
        outcome.previousSeriesSequences.forEach { (seriesId, prevSeq) ->
            dao.updateSeriesReadSequence(seriesId, prevSeq)
        }
        if (outcome.archivedTrackedBook != null) {
            dao.insertTrackedItem(outcome.archivedTrackedBook)
        }
        if (outcome.wasPinned != null) {
            dao.upsertPinnedRelease(outcome.wasPinned)
        }
    }

    suspend fun unmarkReadBook(book: ReadBookEntity) {
        dao.deleteReadBookByAsin(book.asin)
        if (book.groupKey.isNotBlank()) {
            dao.deleteReadBookByGroupKey(book.groupKey)
        }
    }

    suspend fun togglePinRelease(rel: ReleaseEntity): Boolean {
        val groupKey = computeGroupKey(rel.title, rel.authors)
        val existingPins = dao.getAllPinnedReleases()
        val matched = existingPins.firstOrNull {
            it.asin.equals(rel.asin, ignoreCase = true) ||
                (it.groupKey.isNotBlank() && it.groupKey == groupKey)
        }
        return if (matched != null) {
            dao.deletePinnedReleaseByAsin(matched.asin)
            if (groupKey.isNotBlank()) dao.deletePinnedReleaseByGroupKey(groupKey)
            false
        } else {
            dao.upsertPinnedRelease(
                PinnedReleaseEntity(
                    asin = rel.asin,
                    groupKey = groupKey,
                    title = rel.title,
                    createdAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    suspend fun editTrackedItemAndRefresh(
        item: TrackedItemEntity,
        rawNewQuery: String,
        refreshMetadata: Boolean
    ): Boolean {
        val cleaned = if (item.type == TrackType.AUTHOR || item.type == TrackType.NARRATOR || item.type == TrackType.SERIES) {
            AudibleApiClient.formatCanonicalName(rawNewQuery)
        } else {
            rawNewQuery.trim()
        }
        if (cleaned.isBlank()) return false

        val nameChanged = !cleaned.equals(item.queryValue.trim(), ignoreCase = true)
        val existingDup = if (nameChanged) dao.findTrackedItem(item.type, cleaned) else null
        if (existingDup != null && existingDup.id != item.id) return false

        val clearMeta = nameChanged || refreshMetadata
        if (clearMeta) {
            AudibleApiClient.evictPortraitCache(item.queryValue)
            AudibleApiClient.evictPortraitCache(cleaned)
        }

        dao.updateTrackedItemDetails(
            id = item.id,
            queryValue = cleaned,
            extId = if (clearMeta) null else item.externalId,
            url = if (clearMeta) "" else item.thumbnailUrl
        )
        return true
    }

    suspend fun muteBook(asin: String, title: String) {
        dao.insertDndRule(
            DndRuleEntity(
                targetKey = asin.lowercase(Locale.US),
                targetType = DndTargetType.BOOK,
                displayTitle = title
            )
        )
        dao.deleteReleaseByAsin(asin)
    }

    suspend fun muteSeries(seriesKey: String, displayTitle: String) {
        if (seriesKey.isBlank()) return
        dao.insertDndRule(
            DndRuleEntity(
                targetKey = seriesKey.lowercase(Locale.US),
                targetType = DndTargetType.SERIES,
                displayTitle = displayTitle
            )
        )
        dao.deleteReleasesBySeries(seriesKey)
    }
}

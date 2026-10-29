package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** How many posters a collapsed section shows (three rows of four), and Home's per-section page. */
internal const val HOME_COLLAPSED_COUNT = 12

/** Page size for a section's "Show all": big pages, since the user asked for everything. */
internal const val HOME_SHOW_ALL_PAGE = 100

/**
 * One section on Home. [WATCHING][LibrarySection.WATCHING] carries the Caught up entries after
 * its own, and its [count] adds both; Caught up never appears as a section of its own.
 *
 * [count] is the server's figure from the library stats, null until they arrive (or when the
 * server reports none). [entries] can run past [collapsedEntries] while collapsed: page one of
 * Watching and of Caught up together, or pages a "Show less" kept rather than throw away.
 *
 * [hasMore] decides whether "Show all" is offered. Collapsed, that is when the section holds more
 * than a collapsed section shows — by [count], or by what is loaded when there is none — or when the
 * network says more pages follow ([nextCursor]; cached sections carry none), which catches a full
 * twelve-row page while the stats are missing or behind. Expanded, only [nextCursor] counts: a
 * reload re-reads an expanded section in one bounded request and may stop short of its end.
 */
data class HomeSection(
    val section: LibrarySection,
    val entries: List<LibraryEntry>,
    val count: Int?,
    val expanded: Boolean,
    val nextCursor: String?,
    val loadingMore: Boolean,
    val error: Throwable? = null,
) {
    val collapsedEntries: List<LibraryEntry> get() = entries.take(HOME_COLLAPSED_COUNT)

    val hasMore: Boolean
        get() =
            if (expanded) {
                nextCursor != null
            } else {
                (count ?: entries.size) > HOME_COLLAPSED_COUNT || nextCursor != null
            }
}

/**
 * The "All shows" view. [Ready.sections] holds only non-empty sections, in
 * [LibrarySection.homeOrder]. [Ready.isStale] is true while the sections come from the Room cache
 * — before the network answers, or after it failed.
 */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Ready(
        val sections: List<HomeSection>,
        val isStale: Boolean,
    ) : HomeUiState

    data class Error(
        val cause: Throwable,
    ) : HomeUiState
}

/**
 * Home's state, owned by `LibraryViewModel` and fed only when the filter is "All shows". Kept
 * apart from the paged single-status list because the two share nothing: Home is six independent
 * first pages plus per-section "Show all" cursors, the single-status view one cursor-paged list.
 *
 * Every mutation runs on [scope]'s (main, single-threaded) dispatcher, so the plain fields below
 * need no locking; [publish] rebuilds [state] from them after each change.
 *
 * **Loads** come in three kinds ([LoadKind]):
 * - *Quiet* — live sections for the current sort are on screen (a resume, a retry): they stay until
 *   the answer replaces them, and a failure is dropped (the same promise the single-status list's
 *   resume reload makes).
 * - *Cold* — nothing live yet (first load, a retry after an error or over the cache): the cache
 *   renders first, marked stale and collapsed, and the network answer replaces it.
 * - *Sort change* — every section collapses and the screen shows Loading until the network
 *   answers. The cache is in Title order, so it is only the fallback when that answer fails.
 *
 * Only the first pages ([LibraryRepository.refreshHome]) decide whether a load failed. **Expanded
 * sections** (quiet or cold, same sort) are then re-read with one request per raw section from its
 * start, sized to what it had loaded (at least a collapsed section's worth, at most
 * [HOME_SHOW_ALL_PAGE]) — so coming back from a show's page neither collapses them nor misses what
 * was added meanwhile, without re-downloading a long section page by page. One that stops short
 * keeps its cursor and offers "Show all" again. Sections expanded over the cache are re-read the
 * same way ([restoring] carries them while the cache shows them collapsed). A failure fetching one
 * section's extra rows marks only that section: it shows collapsed, on its fresh first page, with
 * its error; every other section's fresh rows still land.
 *
 * **"Show all" and loads.** A "Show all" fetches pages after a cursor, which only means something
 * against the pages it read, so it never runs alongside a load. One already in flight is cancelled
 * and, under the same sort, carried into the load as a request ([requested]); one tapped while a
 * load runs is queued the same way. A successful load fetches the requested sections' remaining
 * pages against its own fresh first pages and expands them; a failed quiet load starts them
 * against the pages still on screen. A sort change drops them: the user sees a fresh, collapsed
 * list. Each job re-checks [ensureActive] before writing back.
 */
internal class HomeSectionsController(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
) {
    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    /** Per RAW section (Caught up separate from Watching). */
    private var pages: Map<LibrarySection, SectionPages> = emptyMap()
    private var sort: LibrarySort? = null
    private var live = false
    private var counts: Map<LibrarySection, Int>? = null
    private val expanded = mutableSetOf<LibrarySection>()

    /** Sections whose "Show all" waits on the running load; see the class KDoc. */
    private val requested = mutableSetOf<LibrarySection>()

    /**
     * Per RAW section: the expanded sections the running load re-reads, with how many rows each had
     * loaded. Filled when a same-sort load starts, emptied when it ends.
     */
    private val restoring = mutableMapOf<LibrarySection, Int>()
    private val sectionErrors = mutableMapOf<LibrarySection, Throwable>()
    private var loadJob: Job? = null
    private val expandJobs = mutableMapOf<LibrarySection, Job>()

    /** Reloads under [sort]. A different sort than last time starts over, collapsed, on Loading. */
    fun load(sort: LibrarySort) {
        val previous = this.sort
        val kind =
            when {
                previous != null && sort != previous -> LoadKind.SORT_CHANGE
                live -> LoadKind.QUIET
                else -> LoadKind.COLD
            }
        loadJob?.cancel()
        this.sort = sort
        if (kind == LoadKind.SORT_CHANGE) {
            cancelExpansions()
            requested.clear()
            restoring.clear()
            expanded.clear()
            pages = emptyMap()
            live = false
            mutableState.value = HomeUiState.Loading
        } else {
            requested += expandJobs.keys
            for (raw in expanded.flatMap(::rawSections)) restoring[raw] = pages[raw]?.items?.size ?: 0
            cancelExpansions()
            if (mutableState.value is HomeUiState.Ready) publish()
        }
        loadJob = scope.launch { runLoad(sort, kind) }
    }

    /** The resume path: a reload, unless one is already running (the first resume follows `init`'s). */
    fun refreshOnResume(sort: LibrarySort) {
        if (loadJob?.isActive == true && sort == this.sort) return
        load(sort)
    }

    /** The library stats' `bySection`, for each section's count. */
    fun updateCounts(bySection: Map<LibrarySection, Int>) {
        counts = bySection
        if (mutableState.value is HomeUiState.Ready) publish()
    }

    /**
     * Collapses a fully loaded expanded section (keeping what it loaded), or otherwise fetches
     * whatever the section has not loaded yet and expands it: a collapsed section, or an expanded
     * one a reload left short of its end. A cached section is fetched from the start, since the
     * cache holds no cursor and may be out of date. While a load runs, the fetch waits for it.
     */
    fun toggle(section: LibrarySection) {
        val raws = rawSections(section)
        val complete = raws.none { pages.needsFetch(it, live) }
        val sort = sort
        val handled =
            when {
                expandJobs[section]?.isActive == true || section in requested -> false
                // Expanded over the cache and collapsed by it: the running load brings it back.
                section !in expanded && raws.any { it in restoring } -> false
                sort == null -> false
                section in expanded && complete -> {
                    expanded -= section
                    restoring -= raws
                    true
                }
                else -> {
                    sectionErrors -= section
                    when {
                        complete -> expanded += section
                        loadJob?.isActive == true -> requested += section
                        else -> startExpansion(section, sort)
                    }
                    true
                }
            }
        if (handled) publish()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startExpansion(
        section: LibrarySection,
        sort: LibrarySort,
    ) {
        val raws = rawSections(section)
        expandJobs[section] =
            scope.launch {
                try {
                    var fetched = pages
                    for (raw in raws) fetched = fetched.withRest(repository, raw, sort, live)
                    ensureActive()
                    pages = pages + raws.associateWith { fetched.getValue(it) }
                    expanded += section
                } catch (cancellation: CancellationException) {
                    // A load cancelled this and owns the state now; publishing here would overwrite it.
                    throw cancellation
                } catch (failure: Exception) {
                    sectionErrors[section] = failure
                }
                expandJobs -= section
                publish()
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runLoad(
        sort: LibrarySort,
        kind: LoadKind,
    ) {
        if (kind == LoadKind.COLD) showCache()
        try {
            val firstPages = repository.refreshHome(sort, perSection = HOME_COLLAPSED_COUNT)
            val firstPageMap =
                HOME_RAW_SECTIONS.associateWith { raw ->
                    val page = firstPages[raw] ?: Page(emptyList(), nextCursor = null)
                    SectionPages(page.items.inSection(raw), page.nextCursor, fromStart = true)
                }
            // Fetched before anything is written; a failure marks only its own section.
            val (fresh, failures) = withExtraRows(firstPageMap, sort)
            currentCoroutineContext().ensureActive()
            cancelExpansions()
            pages = fresh
            live = true
            expanded += restoring.keys.map(::displaySection) + requested
            expanded -= failures.keys
            sectionErrors += failures
            requested.clear()
            restoring.clear()
            publish()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            restoring.clear()
            if (kind == LoadKind.QUIET) {
                // The live sections stay; queued "Show all"s run against them now.
                requested.forEach { section -> startExpansion(section, sort) }
                requested.clear()
                publish()
                return
            }
            if (kind == LoadKind.SORT_CHANGE) showCache()
            requested.clear()
            val showing = pages.values.any { it.items.isNotEmpty() }
            if (showing) publish() else mutableState.value = HomeUiState.Error(failure)
        }
    }

    /**
     * [firstPages] with the extra rows the load owes: one bounded re-read of every [restoring]
     * section, then the rest of every requested one. Re-read each round, so a "Show all" tapped
     * meanwhile joins (and a "Show less" cannot break the iteration). A section whose fetch fails
     * keeps its first page and is returned among the failures (by display section).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun withExtraRows(
        firstPages: Map<LibrarySection, SectionPages>,
        sort: LibrarySort,
    ): Pair<Map<LibrarySection, SectionPages>, Map<LibrarySection, Throwable>> {
        var fresh = firstPages
        val failures = mutableMapOf<LibrarySection, Throwable>()
        val restored = mutableSetOf<LibrarySection>()
        val completed = mutableSetOf<LibrarySection>()
        while (true) {
            val toRestore = restoring.keys.map(::displaySection).toSet() - restored - failures.keys
            val toComplete = requested - completed - failures.keys
            val section = (toRestore + toComplete).firstOrNull() ?: break
            val restore = section in toRestore
            val complete = section in toComplete
            try {
                val loaded = if (restore) restoring.toMap() else emptyMap()
                fresh = fresh.withSectionRows(repository, section, sort, loaded, complete)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                failures[section] = failure
                fresh = fresh + rawSections(section).associateWith { firstPages.getValue(it) }
            }
            if (restore) restored += section
            if (complete) completed += section
        }
        return fresh to failures
    }

    /**
     * Renders the cache (at most a collapsed section's worth each, every section collapsed), or
     * Loading when it is empty. Expanded sections collapse over it; [load] already put them in
     * [restoring], so a successful load re-expands them with real pages.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun showCache() {
        val cached =
            try {
                repository.observeHomeCache().first()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyMap()
            }
        currentCoroutineContext().ensureActive()
        cancelExpansions()
        // Expanded sections are in [restoring] already (see [load]); they collapse over the cache.
        expanded.clear()
        pages =
            cached
                .filterKeys { it in HOME_RAW_SECTIONS }
                .mapValues { (_, items) -> SectionPages(items.take(HOME_COLLAPSED_COUNT), null, fromStart = false) }
        live = false
        if (pages.values.any { it.items.isNotEmpty() }) publish() else mutableState.value = HomeUiState.Loading
    }

    private fun publish() {
        val sections =
            LibrarySection.homeOrder.mapNotNull { section ->
                val raws = rawSections(section)
                val entries = raws.flatMap { pages[it]?.items.orEmpty() }
                if (entries.isEmpty()) return@mapNotNull null
                HomeSection(
                    section = section,
                    entries = entries,
                    count = counts?.let { known -> raws.mapNotNull { known[it] }.takeIf { it.isNotEmpty() }?.sum() },
                    expanded = section in expanded,
                    nextCursor = raws.firstNotNullOfOrNull { pages[it]?.cursor },
                    loadingMore =
                        expandJobs[section]?.isActive == true ||
                            section in requested ||
                            (section !in expanded && raws.any { it in restoring }),
                    error = sectionErrors[section],
                )
            }
        mutableState.value = HomeUiState.Ready(sections, isStale = !live)
    }

    private fun cancelExpansions() {
        expandJobs.values.forEach { it.cancel() }
        expandJobs.clear()
        sectionErrors.clear()
    }

    private enum class LoadKind { QUIET, COLD, SORT_CHANGE }

    /**
     * What is loaded for one raw section. [fromStart] is false for cached rows: they carry no
     * cursor, so "Show all" must read that section from its first page.
     */
    private data class SectionPages(
        val items: List<LibraryEntry>,
        val cursor: String?,
        val fromStart: Boolean,
    )

    private companion object {
        /** The server's sections Home reads: its five headings plus Caught up. */
        val HOME_RAW_SECTIONS: List<LibrarySection> = LibrarySection.homeOrder + LibrarySection.CAUGHT_UP

        /**
         * This map with [raw]'s remaining pages appended. A section not yet fetched from the network
         * (cached, or absent from the cache) is read from its first page and replaces what was there.
         */
        suspend fun Map<LibrarySection, SectionPages>.withRest(
            repository: LibraryRepository,
            raw: LibrarySection,
            sort: LibrarySort,
            live: Boolean,
        ): Map<LibrarySection, SectionPages> {
            if (!needsFetch(raw, live)) return this
            val current = this[raw]?.takeIf { it.fromStart }
            val items = current?.items.orEmpty().toMutableList()
            var cursor = current?.cursor
            do {
                val page = repository.sectionPage(raw, sort, cursor, limit = HOME_SHOW_ALL_PAGE)
                items += page.items.inSection(raw)
                cursor = page.nextCursor
            } while (cursor != null)
            return this + (raw to SectionPages(items, cursor = null, fromStart = true))
        }

        /**
         * This map with [section]'s raw sections re-read from the start for those in [loaded]
         * (see [withFirstRows]), then read to the end when [complete]. All or nothing per section:
         * a failure throws before anything is returned.
         */
        suspend fun Map<LibrarySection, SectionPages>.withSectionRows(
            repository: LibraryRepository,
            section: LibrarySection,
            sort: LibrarySort,
            loaded: Map<LibrarySection, Int>,
            complete: Boolean,
        ): Map<LibrarySection, SectionPages> {
            var next = this
            for (raw in rawSections(section)) {
                loaded[raw]?.let { next = next.withFirstRows(repository, raw, sort, it) }
                if (complete) next = next.withRest(repository, raw, sort, live = true)
            }
            return next
        }

        /**
         * This map with [raw] re-read from its start in one request of [loaded] rows (clamped to
         * [HOME_COLLAPSED_COUNT]..[HOME_SHOW_ALL_PAGE]), keeping the cursor if more follow.
         */
        suspend fun Map<LibrarySection, SectionPages>.withFirstRows(
            repository: LibraryRepository,
            raw: LibrarySection,
            sort: LibrarySort,
            loaded: Int,
        ): Map<LibrarySection, SectionPages> {
            val limit = loaded.coerceIn(HOME_COLLAPSED_COUNT, HOME_SHOW_ALL_PAGE)
            val page = repository.sectionPage(raw, sort, cursor = null, limit = limit)
            return this + (raw to SectionPages(page.items.inSection(raw), page.nextCursor, fromStart = true))
        }

        /**
         * Only the entries the server filed under [raw]. A server that predates sections ignores
         * the `section` filter and answers every request with the whole library; without this,
         * every show would appear under every heading.
         */
        fun List<LibraryEntry>.inSection(raw: LibrarySection): List<LibraryEntry> = filter { it.section == raw }

        /** Whether [raw] has pages left to read. An absent section counts only before anything is live. */
        private fun Map<LibrarySection, SectionPages>.needsFetch(
            raw: LibrarySection,
            live: Boolean,
        ): Boolean {
            val current = this[raw] ?: return !live
            return !current.fromStart || current.cursor != null
        }

        /** The Home section a raw section shows under: Caught up sits inside Watching. */
        fun displaySection(raw: LibrarySection): LibrarySection =
            if (raw == LibrarySection.CAUGHT_UP) LibrarySection.WATCHING else raw

        fun rawSections(section: LibrarySection): List<LibrarySection> =
            if (section == LibrarySection.WATCHING) {
                listOf(LibrarySection.WATCHING, LibrarySection.CAUGHT_UP)
            } else {
                listOf(section)
            }
    }
}

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
 * server reports none) — [hasMore] then falls back to what is loaded. [entries] can run past
 * [collapsedEntries] while collapsed: page one of Watching and of Caught up together, or pages a
 * "Show less" kept rather than throw away.
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

    val hasMore: Boolean get() = (count ?: entries.size) > HOME_COLLAPSED_COUNT
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
 *   resume reload makes). Expanded sections are re-expanded as part of it, so coming back from a
 *   show's page neither collapses them nor misses what was added meanwhile. If re-fetching an
 *   expanded section's pages fails, the whole quiet reload is dropped rather than half-applied.
 * - *Cold* — nothing live yet (first load, a retry after an error or over the cache): the cache
 *   renders first, marked stale and collapsed, and the network answer replaces it.
 * - *Sort change* — every section collapses and the screen shows Loading until the network
 *   answers. The cache is in Title order, so it is only the fallback when that answer fails.
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
            expanded.clear()
            pages = emptyMap()
            live = false
            mutableState.value = HomeUiState.Loading
        } else {
            requested += expandJobs.keys
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
     * Collapses an expanded section (keeping what it loaded), or expands a collapsed one — after
     * fetching whatever it has not loaded yet. A cached section is fetched from the start, since
     * the cache holds no cursor and may be out of date. While a load runs, the fetch waits for it.
     */
    fun toggle(section: LibrarySection) {
        if (section in expanded) {
            expanded -= section
            publish()
            return
        }
        if (expandJobs[section]?.isActive == true || section in requested) return
        val sort = sort ?: return
        sectionErrors -= section
        when {
            rawSections(section).none { pages.needsFetch(it, live) } -> expanded += section
            loadJob?.isActive == true -> requested += section
            else -> startExpansion(section, sort)
        }
        publish()
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
                    SectionPages(page.items, page.nextCursor, fromStart = true)
                }
            // Fetched before anything is written, so a failure here leaves the old sections whole.
            val fresh = withExpandedRest(firstPageMap, sort)
            currentCoroutineContext().ensureActive()
            cancelExpansions()
            pages = fresh
            live = true
            expanded += requested
            requested.clear()
            publish()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
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
     * [firstPages] with the rest of every expanded or requested section. Re-read each round: a
     * "Show all" tapped meanwhile joins, and a "Show less" must not break the iteration.
     */
    private suspend fun withExpandedRest(
        firstPages: Map<LibrarySection, SectionPages>,
        sort: LibrarySort,
    ): Map<LibrarySection, SectionPages> {
        var fresh = firstPages
        val done = mutableSetOf<LibrarySection>()
        var todo = expanded + requested
        while (todo.isNotEmpty()) {
            for (raw in todo.flatMap(::rawSections)) fresh = fresh.withRest(repository, raw, sort, live = true)
            done += todo
            todo = (expanded + requested) - done
        }
        return fresh
    }

    /**
     * Renders the cache (at most a collapsed section's worth each, every section collapsed), or
     * Loading when it is empty. Sections expanded over the cache become requests, so a successful
     * load re-expands them with real pages.
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
        requested += expanded
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
                    loadingMore = expandJobs[section]?.isActive == true || section in requested,
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
                items += page.items
                cursor = page.nextCursor
            } while (cursor != null)
            return this + (raw to SectionPages(items, cursor = null, fromStart = true))
        }

        /** Whether [raw] has pages left to read. An absent section counts only before anything is live. */
        private fun Map<LibrarySection, SectionPages>.needsFetch(
            raw: LibrarySection,
            live: Boolean,
        ): Boolean {
            val current = this[raw] ?: return !live
            return !current.fromStart || current.cursor != null
        }

        fun rawSections(section: LibrarySection): List<LibrarySection> =
            if (section == LibrarySection.WATCHING) {
                listOf(LibrarySection.WATCHING, LibrarySection.CAUGHT_UP)
            } else {
                listOf(section)
            }
    }
}

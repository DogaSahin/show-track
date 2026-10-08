package com.anarky.showtrack.core.data.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

const val RECENT_SEARCH_DATASTORE_NAME = "showtrack_recent_searches"

/**
 * The Search screen's recent queries, kept on the phone only: the last [RecentSearches.LIMIT],
 * newest first, a repeated query moved to the top rather than listed twice. Cleared on sign-out
 * and on every sign-in, so one account never sees another's searches on a shared phone.
 */
interface RecentSearchStore {
    val recent: Flow<List<String>>

    suspend fun record(query: String)

    suspend fun clear()
}

/** The list rules, kept pure so they are tested without a DataStore. */
object RecentSearches {
    const val LIMIT = 10

    /**
     * Whitespace collapses to single spaces (which also keeps the newline the store joins on out of
     * any entry); a blank query is not recorded; a repeat matches case-insensitively and takes the
     * new spelling.
     */
    fun add(
        current: List<String>,
        query: String,
    ): List<String> {
        val normalized = query.trim().replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return current
        return (listOf(normalized) + current.filterNot { it.equals(normalized, ignoreCase = true) }).take(LIMIT)
    }

    private val WHITESPACE = Regex("\\s+")
}

// One DataStore per file per process, so a top-level delegate, as ActiveGroupStore does.
private val Context.recentSearchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = RECENT_SEARCH_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class DataStoreRecentSearchStore(
    private val dataStore: DataStore<Preferences>,
) : RecentSearchStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.recentSearchDataStore)

    override val recent: Flow<List<String>> =
        dataStore.data
            // An unreadable file means "no recent searches", never a crash on the Search screen.
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { prefs -> decode(prefs[QUERIES]) }

    override suspend fun record(query: String) {
        dataStore.edit { prefs ->
            prefs[QUERIES] = RecentSearches.add(decode(prefs[QUERIES]), query).joinToString(SEPARATOR)
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(QUERIES) }
    }

    private fun decode(raw: String?): List<String> = raw?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        val QUERIES = stringPreferencesKey("queries")
        const val SEPARATOR = "\n"
    }
}

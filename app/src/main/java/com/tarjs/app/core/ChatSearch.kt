package com.tarjs.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Chat-scoped search with bidirectional paging.
 * Runs all database work off-thread, debounces input and cancels stale searches.
 */
class ChatSearch(private val db: ArchiveDb) {

    data class SearchState(
        val query: String,
        val results: List<Message>,
        val isLoading: Boolean,
        val error: String? = null,
        val hasOlder: Boolean = false,
        val hasNewer: Boolean = false
    )

    @Volatile
    private var activeSearch: Deferred<SearchState>? = null

    suspend fun search(chatId: Long, query: String, scope: CoroutineScope): SearchState {
        val normalized = query.trim()
        activeSearch?.cancel()
        if (normalized.isEmpty()) {
            return SearchState(normalized, emptyList(), isLoading = false)
        }

        val deferred = scope.async {
            delay(DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                runCatching {
                    val results = db.searchMessages(chatId, normalized, SEARCH_LIMIT)
                    val firstDate = results.firstOrNull()?.dateUnix
                    val lastDate = results.lastOrNull()?.dateUnix
                    SearchState(
                        query = normalized,
                        results = results,
                        isLoading = false,
                        hasOlder = firstDate?.let { db.hasOlderMessages(chatId, it) } ?: false,
                        hasNewer = lastDate?.let { db.hasNewerMessages(chatId, it) } ?: false
                    )
                }.getOrElse { error ->
                    SearchState(normalized, emptyList(), isLoading = false, error = error.userFacingMessage("Search failed"))
                }
            }
        }
        activeSearch = deferred
        return try {
            deferred.await()
        } catch (_: CancellationException) {
            SearchState(normalized, emptyList(), isLoading = false)
        } finally {
            if (activeSearch === deferred) activeSearch = null
        }
    }

    suspend fun loadOlder(chatId: Long, beforeDate: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            db.messages(chatId = chatId, olderThanDate = beforeDate, limit = PAGE_SIZE)
        }.await()

    suspend fun loadNewer(chatId: Long, afterDate: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            db.messages(chatId = chatId, newerThanDate = afterDate, limit = PAGE_SIZE)
        }.await()

    suspend fun jumpToMessage(chatId: Long, messageId: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            db.messageWindow(chatId, messageId, before = WINDOW_HALF, after = WINDOW_HALF)
        }.await()

    fun cancel() {
        activeSearch?.cancel()
        activeSearch = null
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        private const val SEARCH_LIMIT = 150
        private const val PAGE_SIZE = 100
        private const val WINDOW_HALF = 50
    }
}

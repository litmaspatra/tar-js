package com.tarjs.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Chat-scoped and archive-wide search with bidirectional paging.
 * Runs all database work off-thread, debounces input and cancels stale searches.
 */
class ChatSearch(private val db: ArchiveDb) {

    data class SearchState(
        val query: String,
        val results: List<Message>,
        val isLoading: Boolean,
        val error: String? = null,
        val hasMore: Boolean = false
    )

    @Volatile
    private var activeSearch: Deferred<SearchState>? = null

    suspend fun search(
        chatId: Long?,
        query: String,
        filter: SearchFilter,
        newestFirst: Boolean,
        scope: CoroutineScope
    ): SearchState {
        val normalized = query.trim()
        activeSearch?.cancel()
        if (normalized.isEmpty()) {
            return SearchState(normalized, emptyList(), isLoading = false)
        }

        val deferred = scope.async {
            delay(DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                runCatching {
                    val results = db.searchMessages(chatId, normalized, filter, newestFirst, SEARCH_LIMIT, 0)
                    SearchState(normalized, results, isLoading = false, hasMore = results.size == SEARCH_LIMIT)
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

    data class SearchPage(val results: List<Message>, val hasMore: Boolean)

    suspend fun searchPage(
        chatId: Long?,
        query: String,
        filter: SearchFilter,
        newestFirst: Boolean,
        offset: Int,
        scope: CoroutineScope
    ): SearchPage = scope.async(Dispatchers.IO) {
        val rows = db.searchMessages(chatId, query.trim(), filter, newestFirst, SEARCH_LIMIT, offset)
        SearchPage(rows, rows.size == SEARCH_LIMIT)
    }.await()

    suspend fun loadOlder(chatId: Long, beforeDate: Long, beforeMessageId: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            db.messages(chatId = chatId, olderThanDate = beforeDate, olderThanMessageId = beforeMessageId, limit = PAGE_SIZE)
        }.await()

    suspend fun loadNewer(chatId: Long, afterDate: Long, afterMessageId: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            db.messages(chatId = chatId, newerThanDate = afterDate, newerThanMessageId = afterMessageId, limit = PAGE_SIZE)
        }.await()

    suspend fun jumpToMessage(chatId: Long, messageId: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            db.messageWindow(chatId, messageId, before = WINDOW_HALF, after = WINDOW_HALF)
        }.await()

    /** Window around the first message on or after [dateUnix]; the latest message when the date is past the end. */
    suspend fun jumpToDate(chatId: Long, dateUnix: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            val target = db.firstMessageOnOrAfter(chatId, dateUnix) ?: db.lastMessage(chatId) ?: return@async emptyList()
            db.messageWindow(chatId, target.id, before = WINDOW_HALF, after = WINDOW_HALF)
        }.await()

    suspend fun jumpToFirst(chatId: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            val target = db.firstMessage(chatId) ?: return@async emptyList()
            db.messageWindow(chatId, target.id, before = 0, after = 2 * WINDOW_HALF)
        }.await()

    suspend fun jumpToLatest(chatId: Long, scope: CoroutineScope): List<Message> =
        scope.async(Dispatchers.IO) {
            val target = db.lastMessage(chatId) ?: return@async emptyList()
            db.messageWindow(chatId, target.id, before = 2 * WINDOW_HALF, after = 0)
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

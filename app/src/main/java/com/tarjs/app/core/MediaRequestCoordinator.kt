package com.tarjs.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaRequestCoordinator<T : Any> {
    private val mutex = Mutex()
    private val cached = mutableMapOf<String, T>()
    private val active = mutableMapOf<String, CompletableDeferred<T?>>()

    suspend fun resolve(key: String, loader: suspend () -> T?): T? {
        var owner = false
        var cachedValue: T? = null
        val request = mutex.withLock {
            cachedValue = cached[key]
            if (cachedValue != null) return@withLock null
            active[key] ?: CompletableDeferred<T?>().also {
                active[key] = it
                owner = true
            }
        }
        cachedValue?.let { return it }
        val deferred = requireNotNull(request)

        if (owner) {
            try {
                val loaded = loader()
                mutex.withLock {
                    if (loaded != null) cached[key] = loaded
                    active.remove(key)
                }
                deferred.complete(loaded)
            } catch (failure: Throwable) {
                mutex.withLock { active.remove(key) }
                deferred.completeExceptionally(failure)
            }
        }
        return deferred.await()
    }
}

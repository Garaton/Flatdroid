package com.garathon.flatdroid.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/**
 * Answers by key that are asked for once and kept ([get]). A second ask for a key while the first
 * is still under way waits for that one's answer (or its failure) instead of asking again: a screen
 * rebuilt by a rotation asks again, and the request already sent isn't stopped. A failure is not
 * kept: the next ask tries again. Thread-safe; [get] blocks, so call it off the main thread.
 */
class OnceAsked<T : Any> {
    private val done = ConcurrentHashMap<String, T>()
    private val asked = HashMap<String, CompletableFuture<T>>()

    /** The answer for [key]: the one kept, the one being asked for, or what [ask] makes of it. */
    fun get(key: String, ask: () -> T): T {
        done[key]?.let { return it }
        val mine = CompletableFuture<T>()
        val theirs = synchronized(asked) { asked[key] ?: run { asked[key] = mine; null } }
        if (theirs != null) {
            return try {
                theirs.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        try {
            val answer = ask()
            done[key] = answer
            mine.complete(answer)
            return answer
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            synchronized(asked) { asked.remove(key) }
        }
    }

    /** Forgets the kept answers (the ones being asked for still arrive). */
    fun clear() = done.clear()
}

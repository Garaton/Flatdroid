package com.garathon.flatdroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OnceAskedTest {
    @Test
    fun keepsTheAnswer() {
        val asked = OnceAsked<String>()
        val calls = AtomicInteger()
        assertEquals("a", asked.get("k") { calls.incrementAndGet(); "a" })
        assertEquals("a", asked.get("k") { calls.incrementAndGet(); "b" })
        assertEquals(1, calls.get())
        asked.clear()
        assertEquals("b", asked.get("k") { "b" })
    }

    @Test
    fun aSecondAskJoinsTheOneUnderWay() {
        val asked = OnceAsked<String>()
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> {
                asked.get("k") { calls.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); "answer" }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<String> { asked.get("k") { calls.incrementAndGet(); "other" } }
            Thread.sleep(100)
            release.countDown()
            assertEquals("answer", first.get(5, TimeUnit.SECONDS))
            assertEquals("answer", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aFailureReachesTheJoinerAndIsNotKept() {
        val asked = OnceAsked<String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val boom = IOException("no network")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Throwable?> {
                try {
                    asked.get("k") { started.countDown(); release.await(5, TimeUnit.SECONDS); throw boom }
                    null
                } catch (e: IOException) {
                    e
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Throwable?> {
                try {
                    asked.get("k") { "never" }
                    null
                } catch (e: IOException) {
                    e
                }
            }
            Thread.sleep(100)
            release.countDown()
            assertSame(boom, first.get(5, TimeUnit.SECONDS))
            assertSame(boom, second.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        // Not kept: asked again, it tries again.
        assertEquals("fine", asked.get("k") { "fine" })
        try {
            OnceAsked<String>().get("x") { throw IOException("a") }
            fail()
        } catch (_: IOException) {
        }
    }
}

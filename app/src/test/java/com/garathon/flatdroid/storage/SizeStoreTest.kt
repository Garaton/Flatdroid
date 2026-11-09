package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SizeStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keys = setOf("app", "home")

    @Test
    fun measuresOnceUntilForgotten() {
        val store = SizeStore(tmp.newFolder())
        var measured = 0
        val measure = { measured++; mapOf("app" to 10L, "home" to 20L) }
        assertEquals(mapOf("app" to 10L, "home" to 20L), store.get("a.App", keys, measure))
        assertEquals(mapOf("app" to 10L, "home" to 20L), store.get("a.App", keys, measure))
        assertEquals(1, measured)
        store.forget("a.App")
        store.get("a.App", keys, measure)
        assertEquals(2, measured)
    }

    @Test
    fun aFileCutOffOrWithOtherKeysIsMeasuredAgain() {
        val dir = tmp.newFolder()
        val store = SizeStore(dir)
        store.get("a.App", keys) { mapOf("app" to 10L, "home" to 20L) }
        val file = File(dir, "a.App.sizes")
        // Cut off before its end line: "home=2" of "home=20" would otherwise read as a size.
        file.writeText("app=10\nhome=2")
        assertEquals(20L, store.get("a.App", keys) { mapOf("app" to 10L, "home" to 20L) }["home"])
        file.writeText("app=10\nend\n")
        assertEquals(20L, store.get("a.App", keys) { mapOf("app" to 10L, "home" to 20L) }["home"])
        file.writeText("app=-1\nhome=20\nend\n")
        assertEquals(10L, store.get("a.App", keys) { mapOf("app" to 10L, "home" to 20L) }["app"])
    }

    @Test
    fun aMeasurementOvertakenByAChangeIsNotSaved() {
        val dir = tmp.newFolder()
        val store = SizeStore(dir)
        store.get("a.App", keys) {
            // The app's files change while it is measured.
            store.forget("a.App")
            mapOf("app" to 1L, "home" to 1L)
        }
        assertFalse(File(dir, "a.App.sizes").exists())
        store.get("b.App", keys) {
            store.forgetAll()
            mapOf("app" to 1L, "home" to 1L)
        }
        assertFalse(File(dir, "b.App.sizes").exists())
    }

    @Test
    fun aSecondCallerWaitsForTheMeasurementInsteadOfMeasuringAgain() {
        val store = SizeStore(tmp.newFolder())
        val measured = AtomicInteger()
        val started = CountDownLatch(1)
        val first = thread {
            store.get("a.App", keys) { started.countDown(); Thread.sleep(200); measured.incrementAndGet(); mapOf("app" to 1L, "home" to 2L) }
        }
        started.await()
        assertEquals(mapOf("app" to 1L, "home" to 2L), store.get("a.App", keys) { measured.incrementAndGet(); mapOf("app" to 9L, "home" to 9L) })
        first.join()
        assertEquals(1, measured.get())
    }

    @Test
    fun forgetAllCanKeepSome() {
        val dir = tmp.newFolder()
        val store = SizeStore(dir)
        store.get("shared", setOf("runtimes")) { mapOf("runtimes" to 5L) }
        store.get("runtimes", setOf("org.gnome.Platform")) { mapOf("org.gnome.Platform" to 5L) }
        store.get("a.App", keys) { mapOf("app" to 1L, "home" to 1L) }
        store.forgetAll(keep = setOf("shared", "runtimes"))
        // The forgotten one is set aside as its last sizes, for showing until measured again.
        assertEquals(setOf("shared.sizes", "runtimes.sizes", "a.App.sizes.last"), dir.list()!!.toSet())
        var measured = false
        store.get("shared", setOf("runtimes")) { measured = true; mapOf("runtimes" to 5L) }
        assertFalse(measured)
    }

    @Test
    fun forgottenSizesStayAsTheLastOnesUntilMeasuredAgain() {
        val dir = tmp.newFolder()
        val store = SizeStore(dir)
        assertEquals(null, store.last("a.App", keys))
        store.get("a.App", keys) { mapOf("app" to 1L, "home" to 2L) }
        store.forget("a.App")
        assertEquals(mapOf("app" to 1L, "home" to 2L), store.last("a.App", keys))
        // Measured again: the new sizes, and nothing set aside any more.
        store.get("a.App", keys) { mapOf("app" to 3L, "home" to 4L) }
        assertEquals(mapOf("app" to 3L, "home" to 4L), store.last("a.App", keys))
        assertEquals(setOf("a.App.sizes"), dir.list()!!.toSet())
    }
}

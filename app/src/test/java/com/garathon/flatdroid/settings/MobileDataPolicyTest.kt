package com.garathon.flatdroid.settings

import com.garathon.flatdroid.flatpak.tasks.FlatpakTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDataPolicyTest {
    @Test
    fun unmeteredNeverWaits() {
        for (setting in MobileData.entries) {
            assertNull(meteredWait(metered = false, setting, allowed = false))
        }
    }

    @Test
    fun meteredFollowsTheSetting() {
        assertNull(meteredWait(metered = true, MobileData.ALWAYS, allowed = false))
        assertEquals(MeteredWait.ASK, meteredWait(metered = true, MobileData.ASK, allowed = false))
        assertEquals(MeteredWait.WIFI, meteredWait(metered = true, MobileData.NEVER, allowed = false))
    }

    @Test
    fun aYesHoldsOnlyWhenAsked() {
        assertNull(meteredWait(metered = true, MobileData.ASK, allowed = true))
        // Off is off: an earlier yes doesn't override it.
        assertEquals(MeteredWait.WIFI, meteredWait(metered = true, MobileData.NEVER, allowed = true))
    }

    @Test
    fun onlyDownloadsAreHeld() {
        assertTrue(FlatpakTask.Install(listOf("org.xfce.mousepad")).downloads)
        assertTrue(FlatpakTask.UpdateAll.downloads)
        assertTrue(FlatpakTask.Reinstall("org.xfce.mousepad").downloads)
        assertTrue(FlatpakTask.ReinstallAll.downloads)
        assertTrue(FlatpakTask.RefreshCatalog.downloads)
        assertFalse(FlatpakTask.Uninstall("org.xfce.mousepad", deleteData = false).downloads)
        assertFalse(FlatpakTask.RemoveRuntime("org.gnome.Platform").downloads)
        assertFalse(FlatpakTask.Repair.downloads)
        assertFalse(FlatpakTask.BaselineAll.downloads)
    }
}

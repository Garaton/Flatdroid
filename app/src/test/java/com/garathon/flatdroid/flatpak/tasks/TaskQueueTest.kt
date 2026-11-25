package com.garathon.flatdroid.flatpak.tasks

import com.garathon.flatdroid.flatpak.tasks.FlatpakTask.AlpineInstall
import com.garathon.flatdroid.flatpak.tasks.FlatpakTask.AlpineRemove
import com.garathon.flatdroid.flatpak.tasks.FlatpakTask.Install
import com.garathon.flatdroid.flatpak.tasks.FlatpakTask.Reinstall
import com.garathon.flatdroid.flatpak.tasks.FlatpakTask.Uninstall
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskQueueTest {
    private fun queue(vararg tasks: FlatpakTask, running: FlatpakTask? = null) =
        tasks.fold(emptyList<FlatpakTask>()) { q, task -> TaskQueue.add(q, task, running) }

    @Test
    fun firstInFirstOutWithOneInstallAndOneUninstall() {
        assertEquals(
            listOf(Install(listOf("org.xfce.mousepad", "org.gnome.Hitori", "org.kde.kcalc")), FlatpakTask.UpdateAll),
            queue(Install(listOf("org.xfce.mousepad")), Install(listOf("org.gnome.Hitori")), FlatpakTask.UpdateAll, Install(listOf("org.kde.kcalc"))),
        )
        // Install A, uninstall B, install C, uninstall D: install A and C, then uninstall B and D.
        assertEquals(
            listOf(
                Install(listOf("com.brave.Browser", "com.github.tchx84.Flatseal")),
                FlatpakTask.Uninstall(listOf(UninstallApp("com.example.Tunneler", deleteData = false), UninstallApp("org.mozilla.firefox", deleteData = true, keepRuntimes = true))),
            ),
            queue(
                Install(listOf("com.brave.Browser")),
                Uninstall("com.example.Tunneler", deleteData = false),
                Install(listOf("com.github.tchx84.Flatseal")),
                Uninstall("org.mozilla.firefox", deleteData = true, keepRuntimes = true),
            ),
        )
    }

    @Test
    fun aCatalogRefreshJoinsARunningOrWaitingUpdate() {
        assertEquals(true, TaskQueue.joinsUpdate(emptyList(), FlatpakTask.RefreshCatalog, running = FlatpakTask.UpdateAll))
        assertEquals(true, TaskQueue.joinsUpdate(listOf(Install(listOf("org.xfce.mousepad")), FlatpakTask.UpdateAll), FlatpakTask.RefreshCatalog, running = null))
        // Without an update it is queued as usual, and nothing else joins an update.
        assertEquals(false, TaskQueue.joinsUpdate(listOf(Install(listOf("org.xfce.mousepad"))), FlatpakTask.RefreshCatalog, running = FlatpakTask.Repair))
        assertEquals(false, TaskQueue.joinsUpdate(emptyList(), Install(listOf("org.xfce.mousepad")), running = FlatpakTask.UpdateAll))
    }

    @Test
    fun duplicatesAreQueuedOnce() {
        assertEquals(
            listOf(FlatpakTask.UpdateAll, Install(listOf("org.xfce.mousepad")), FlatpakTask.RefreshCatalog),
            queue(FlatpakTask.UpdateAll, Install(listOf("org.xfce.mousepad")), FlatpakTask.UpdateAll, FlatpakTask.RefreshCatalog, Install(listOf("org.xfce.mousepad"))),
        )
        // What runs now isn't queued again.
        assertEquals(emptyList<FlatpakTask>(), queue(FlatpakTask.RefreshCatalog, running = FlatpakTask.RefreshCatalog))
        assertEquals(emptyList<FlatpakTask>(), queue(Install(listOf("org.xfce.mousepad")), running = Install(listOf("org.xfce.mousepad"))))
    }

    @Test
    fun anUninstallCancelsAWaitingInstall() {
        assertEquals(
            listOf(Install(listOf("org.gnome.Hitori"))),
            queue(Install(listOf("org.xfce.mousepad", "org.gnome.Hitori")), Uninstall("org.xfce.mousepad", deleteData = false)),
        )
        assertEquals(emptyList<FlatpakTask>(), queue(Install(listOf("org.xfce.mousepad")), Uninstall("org.xfce.mousepad", deleteData = true)))
    }

    @Test
    fun anInstallCancelsAWaitingUninstall() {
        assertEquals(
            listOf(FlatpakTask.UpdateAll),
            queue(Uninstall("org.xfce.mousepad", deleteData = false), FlatpakTask.UpdateAll, Install(listOf("org.xfce.mousepad"))),
        )
    }

    @Test
    fun aSecondUninstallKeepsThePlaceWithTheNewChoices() {
        assertEquals(
            listOf(Uninstall("org.xfce.mousepad", deleteData = true), FlatpakTask.UpdateAll),
            queue(Reinstall("org.xfce.mousepad"), Uninstall("org.xfce.mousepad", deleteData = false), FlatpakTask.UpdateAll, Uninstall("org.xfce.mousepad", deleteData = true)),
        )
    }

    @Test
    fun alpineAddsWaitAsOne() {
        assertEquals(
            listOf(AlpineInstall(listOf("foot", "htop", "mc")), FlatpakTask.UpdateAll),
            queue(AlpineInstall(listOf("foot", "htop")), FlatpakTask.UpdateAll, AlpineInstall(listOf("htop", "mc"))),
        )
        // What is being added now isn't added again.
        assertEquals(listOf(AlpineInstall(listOf("mc"))), queue(AlpineInstall(listOf("htop", "mc")), running = AlpineInstall(listOf("htop"))))
        assertEquals(emptyList<FlatpakTask>(), queue(AlpineInstall(listOf("htop")), running = AlpineInstall(listOf("htop"))))
    }

    @Test
    fun anAlpineAddCancelsTheWaitingRemovalOfThatPackage() {
        assertEquals(
            listOf(AlpineRemove("alpine+mc", "mc", deleteData = false), AlpineInstall(listOf("foot"))),
            queue(AlpineRemove("alpine+htop", "htop", deleteData = true), AlpineRemove("alpine+mc", "mc", deleteData = false), AlpineInstall(listOf("htop", "foot"))),
        )
    }

    @Test
    fun aPackageTakenOutOfTheWaitingAlpineAdd() {
        val q = queue(AlpineInstall(listOf("htop", "mc")), FlatpakTask.UpdateAll)
        assertEquals(listOf(AlpineInstall(listOf("mc")), FlatpakTask.UpdateAll), TaskQueue.withoutAlpinePackage(q, "htop"))
        assertEquals(listOf(FlatpakTask.UpdateAll), TaskQueue.withoutAlpinePackage(TaskQueue.withoutAlpinePackage(q, "htop"), "mc"))
    }

    @Test
    fun anAppTakenOutOfTheWaitingInstalls() {
        val q = queue(Install(listOf("org.xfce.mousepad")), FlatpakTask.UpdateAll)
        assertEquals(listOf(FlatpakTask.UpdateAll), TaskQueue.withoutInstall(q, "org.xfce.mousepad"))
    }

    @Test
    fun anAppTakenOutOfTheWaitingUninstall() {
        val q = queue(Uninstall("org.xfce.mousepad", deleteData = false), Uninstall("org.gnome.Hitori", deleteData = false))
        assertEquals(listOf(Uninstall("org.gnome.Hitori", deleteData = false)), TaskQueue.withoutUninstall(q, "org.xfce.mousepad"))
        // The app being uninstalled now isn't queued again.
        assertEquals(emptyList<FlatpakTask>(), queue(Uninstall("org.xfce.mousepad", deleteData = false), running = Uninstall("org.xfce.mousepad", deleteData = true)))
    }
}

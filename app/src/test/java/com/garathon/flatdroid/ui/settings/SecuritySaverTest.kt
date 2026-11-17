package com.garathon.flatdroid.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import com.garathon.flatdroid.settings.HomeMode
import com.garathon.flatdroid.settings.SecuritySettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

class SecuritySaverTest {
    private fun save(value: SecuritySettings): List<*> =
        with(SecuritySaver) { SaverScope { true }.save(value) } as List<*>

    @Test
    fun everySettingSurvivesTheSaver() {
        val changed = SecuritySettings(
            extraSyscallFilter = false, hideHelperProcesses = false, permissionsFromMetadata = false,
            defaultHomeMode = HomeMode.PRIVATE, limitDevices = true, hideScreen = true, checkFilesBeforeLaunch = true,
            androidFonts = false, shareClipboard = false, shareLocale = false,
        )
        assertEquals(changed, SecuritySaver.restore(save(changed)))
        assertEquals(SecuritySettings(), SecuritySaver.restore(save(SecuritySettings())))
    }

    @Test
    fun theSaverHasAPlaceForEveryField() {
        // A field added to SecuritySettings that the saver misses would come back as its default after a rotation.
        val fields = SecuritySettings::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) }
        assertEquals(fields, save(SecuritySettings()).size)
    }
}

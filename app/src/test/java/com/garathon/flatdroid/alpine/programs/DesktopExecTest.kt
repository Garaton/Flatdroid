package com.garathon.flatdroid.alpine.programs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopExecTest {
    @Test
    fun argumentsWithoutFieldCodes() {
        assertEquals(listOf("mousepad"), DesktopExec.parse("mousepad %U"))
        assertEquals(listOf("gimp-3.0", "--new-instance"), DesktopExec.parse("gimp-3.0 --new-instance %f"))
        assertEquals(listOf("env", "FOO=1", "prog", "--x=100%"), DesktopExec.parse("env FOO=1 prog --x=100%%"))
        assertEquals(listOf("prog", "file-"), DesktopExec.parse("prog file-%u"))
    }

    @Test
    fun quotedArgumentsKeepSpacesAndEscapes() {
        assertEquals(listOf("/usr/bin/my prog", "say \"hi\" \$HOME \\"), DesktopExec.parse("\"/usr/bin/my prog\" \"say \\\"hi\\\" \\\$HOME \\\\\""))
        assertNull(DesktopExec.parse("\"unclosed"))
        assertNull(DesktopExec.parse("  %F "))
    }
}

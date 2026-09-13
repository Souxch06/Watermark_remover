package com.souxch.watermarkremover.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateInstallerTest {

    @Test
    fun `a release version cannot escape the download directory`() {
        // The version comes from the GitHub API: it must not be able to add separators or traversal.
        assertEquals("update", UpdateInstaller.safeFileName("../../evil"))
        assertEquals("1.4.0", UpdateInstaller.safeFileName("1.4.0"))
        assertEquals("1.4.0", UpdateInstaller.safeFileName("v1.4.0"))
        assertEquals("update", UpdateInstaller.safeFileName(""))
        assertEquals(32, UpdateInstaller.safeFileName("9".repeat(80)).length)
    }
}

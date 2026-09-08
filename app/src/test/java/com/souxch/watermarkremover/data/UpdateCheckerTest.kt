package com.souxch.watermarkremover.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `newer versions are detected numerically`() {
        assertTrue(UpdateChecker.isNewer("1.4.0", "1.3.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("v2.0.0", "1.99.0"))
        assertTrue(UpdateChecker.isNewer("1.3.1", "1.3.0-debug"))
    }

    @Test
    fun `same or older versions are not updates`() {
        assertFalse(UpdateChecker.isNewer("1.3.0", "1.3.0"))
        assertFalse(UpdateChecker.isNewer("1.2.9", "1.3.0"))
        assertFalse(UpdateChecker.isNewer("1.3", "1.3.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "1.3.0"))
    }
}

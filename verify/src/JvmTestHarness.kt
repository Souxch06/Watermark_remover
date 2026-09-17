// Local JUnit stand-in so the *real* pure-Kotlin unit tests of the app can be executed on a
// machine without the Android SDK / Gradle (see verify/README.md). The app's Gradle build has the
// real JUnit 4 on its test classpath and never compiles this file: it is only used by the local
// `verify` harness, which compiles the genuine app sources together with the genuine test sources.
// This file must stay a faithful, minimal subset of the JUnit 4 API the tests use (nothing else).

package org.junit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test

object Assert {
    private fun fail(message: String?): Nothing =
        throw AssertionError(message ?: "assertion failed")

    // --- assertTrue / assertFalse ---
    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) fail(null) }
    @JvmStatic fun assertTrue(message: String, condition: Boolean) { if (!condition) fail(message) }
    @JvmStatic fun assertFalse(condition: Boolean) { if (condition) fail(null) }
    @JvmStatic fun assertFalse(message: String, condition: Boolean) { if (condition) fail(message) }

    // --- assertEquals ---
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) fail("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String, expected: Any?, actual: Any?) { if (expected != actual) fail("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: String, actual: String) { if (expected != actual) fail("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String, expected: String, actual: String) { if (expected != actual) fail("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Int, actual: Int) { if (expected != actual) fail("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String, expected: Int, actual: Int) { if (expected != actual) fail("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) { if (expected != actual) fail("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String, expected: Long, actual: Long) { if (expected != actual) fail("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Byte, actual: Byte) { if (expected != actual) fail("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String, expected: Byte, actual: Byte) { if (expected != actual) fail("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) {
        if (!(expected - delta <= actual && actual <= expected + delta)) fail("expected:<$expected> but was:<$actual> (delta $delta)")
    }
    @JvmStatic fun assertEquals(message: String, expected: Float, actual: Float, delta: Float) {
        if (!(expected - delta <= actual && actual <= expected + delta)) fail("$message expected:<$expected> but was:<$actual> (delta $delta)")
    }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (expected.isNaN() || expected - delta > actual || actual > expected + delta) fail("expected:<$expected> but was:<$actual> (delta $delta)")
    }
    @JvmStatic fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) {
        if (expected.isNaN() || expected - delta > actual || actual > expected + delta) fail("$message expected:<$expected> but was:<$actual> (delta $delta)")
    }

    // --- assertNull / assertNotNull ---
    @JvmStatic fun assertNull(actual: Any?) { if (actual != null) fail("expected null but was:<$actual>") }
    @JvmStatic fun assertNull(message: String, actual: Any?) { if (actual != null) fail("$message expected null but was:<$actual>") }
    @JvmStatic fun assertNotNull(actual: Any?) { if (actual == null) fail("expected non-null") }
    @JvmStatic fun assertNotNull(message: String, actual: Any?) { if (actual == null) fail(message) }
}

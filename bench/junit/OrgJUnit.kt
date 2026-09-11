package org.junit

/** JVM-test shim of the JUnit 4 API surface used by the project's tests. */
annotation class Test

object Assert {
    private fun fail(message: String?): Nothing = throw AssertionError(message ?: "assertion failed")

    fun assertTrue(condition: Boolean) { if (!condition) fail("expected true") }
    fun assertTrue(message: String?, condition: Boolean) { if (!condition) fail(message) }
    fun assertFalse(condition: Boolean) { if (condition) fail("expected false") }
    fun assertFalse(message: String?, condition: Boolean) { if (condition) fail(message) }

    fun assertNull(actual: Any?) { if (actual != null) fail("expected null but was <$actual>") }
    fun assertNull(message: String?, actual: Any?) { if (actual != null) fail(message) }
    fun assertNotNull(actual: Any?) { if (actual == null) fail("expected not null") }
    fun assertNotNull(message: String?, actual: Any?) { if (actual == null) fail(message) }

    fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) fail("expected <$expected> but was <$actual>")
    }

    fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) fail("$message (expected <$expected> but was <$actual>)")
    }

    fun assertEquals(expected: Float, actual: Float, delta: Float) {
        if (abs(expected - actual) > delta) fail("expected <$expected> +/-<$delta> but was <$actual>")
    }

    fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
        if (abs(expected - actual) > delta) fail("$message (expected <$expected> +/-<$delta> but was <$actual>)")
    }

    fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (abs(expected - actual) > delta) fail("expected <$expected> +/-<$delta> but was <$actual>")
    }

    fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (abs(expected - actual) > delta) fail("$message (expected <$expected> +/-<$delta> but was <$actual>)")
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v
    private fun abs(v: Double): Double = if (v < 0) -v else v
}

// Minimal stand-in for android.net.Uri, used ONLY by the local `verify` harness so the pure
// `model/VideoInfo.kt` (which stores a Uri but never calls it in the tested code paths) can be
// compiled on a plain JVM. Never part of the Android/Gradle build.

package android.net

class Uri private constructor(private val text: String) {
    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is Uri && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        fun parse(value: String): Uri = Uri(value)
    }
}

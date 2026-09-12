package com.souxch.watermarkremover.processing

import com.souxch.watermarkremover.model.RemovalMethod
import com.souxch.watermarkremover.model.RemovalSettings

/**
 * JVM-test stub of the GLSL holder: only the constants and the method selection used by the
 * pure-Kotlin tests. (The real object lives with the OpenGL pipeline.)
 */
object WatermarkShader {
    const val MAX_ZONES = 6
    const val METHOD_LAYER = 3

    fun methodId(settings: RemovalSettings, layer: WatermarkLayer? = null): Int = when {
        !settings.method.usesShader -> RemovalMethod.INPAINT.shaderId
        settings.method == RemovalMethod.INPAINT && layer != null && layer.hasWatermark -> METHOD_LAYER
        else -> settings.method.shaderId
    }
}

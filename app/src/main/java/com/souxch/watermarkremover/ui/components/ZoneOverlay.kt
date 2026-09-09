package com.souxch.watermarkremover.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.souxch.watermarkremover.model.Corner
import com.souxch.watermarkremover.model.WatermarkZone
import com.souxch.watermarkremover.ui.theme.ZoneColors

/**
 * Draggable / resizable rectangles drawn over the video frame. Must be sized to exactly cover the
 * frame so that normalized (0..1) coordinates map 1:1.
 *
 * Gestures: tap selects a zone, drag inside moves it, drag a handle resizes it, and pressing
 * outside every zone calls [onPeek] (true while held) so the editor can show the original frame.
 */
@Composable
fun ZoneOverlay(
    zones: List<WatermarkZone>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
    onResize: (Int, Corner, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    onPeek: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 14.dp.toPx() }
    val currentZones by rememberUpdatedState(zones)
    val currentSelected by rememberUpdatedState(selectedId)
    val currentOnPeek by rememberUpdatedState(onPeek)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pos ->
                        if (hitTest(currentZones, pos, size.toSizeF(), handleRadiusPx) == null) {
                            currentOnPeek(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                currentOnPeek(false)
                            }
                        }
                    },
                    onTap = { pos ->
                        hitTest(currentZones, pos, size.toSizeF(), handleRadiusPx)?.let { onSelect(it.first) }
                    },
                )
            }
            .pointerInput(Unit) {
                var active: Pair<Int, Corner?>? = null
                detectDragGestures(
                    onDragStart = { pos ->
                        active = hitTest(currentZones, pos, size.toSizeF(), handleRadiusPx, preferId = currentSelected)
                        active?.let { onSelect(it.first) }
                    },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                ) { change, drag ->
                    val a = active ?: return@detectDragGestures
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val corner = a.second
                    if (corner == null) onMove(a.first, drag.x / w, drag.y / h)
                    else onResize(a.first, corner, drag.x / w, drag.y / h)
                }
            },
    ) {
        zones.forEach { zone ->
            val r = zone.rect
            val topLeft = Offset(r.left * size.width, r.top * size.height)
            val rectSize = Size(r.width * size.width, r.height * size.height)
            val selected = zone.id == selectedId
            val color = if (selected) ZoneColors.selected else ZoneColors.idle
            val strokePx = if (selected) 3.dp.toPx() else 2.dp.toPx()
            // Soft tint + dark outline underneath for legibility on any video content.
            val corner = CornerRadius(6.dp.toPx())
            drawRoundRect(color.copy(alpha = if (selected) 0.16f else 0.10f), topLeft, rectSize, corner)
            drawRoundRect(Color.Black.copy(alpha = 0.45f), topLeft, rectSize, corner, style = Stroke(width = strokePx + 2.dp.toPx()))
            drawRoundRect(
                color, topLeft, rectSize, corner,
                style = Stroke(
                    width = strokePx,
                    pathEffect = if (selected) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                ),
            )
            if (selected) {
                // Rule-of-thirds guides help align the box on the watermark edges.
                val guide = Color.White.copy(alpha = 0.25f)
                for (i in 1..2) {
                    val x = topLeft.x + rectSize.width * i / 3f
                    val y = topLeft.y + rectSize.height * i / 3f
                    drawLine(guide, Offset(x, topLeft.y), Offset(x, topLeft.y + rectSize.height), 1f)
                    drawLine(guide, Offset(topLeft.x, y), Offset(topLeft.x + rectSize.width, y), 1f)
                }
                cornersOf(topLeft, rectSize).forEach { (_, c) ->
                    drawCircle(Color.Black.copy(alpha = 0.35f), handleRadiusPx * 0.62f, c)
                    drawCircle(ZoneColors.handleFill, handleRadiusPx * 0.55f, c)
                    drawCircle(color, handleRadiusPx * 0.55f, c, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}

private fun androidx.compose.ui.unit.IntSize.toSizeF() = Size(width.toFloat(), height.toFloat())

private fun cornersOf(topLeft: Offset, size: Size): List<Pair<Corner, Offset>> = listOf(
    Corner.TOP_LEFT to topLeft,
    Corner.TOP_RIGHT to Offset(topLeft.x + size.width, topLeft.y),
    Corner.BOTTOM_LEFT to Offset(topLeft.x, topLeft.y + size.height),
    Corner.BOTTOM_RIGHT to Offset(topLeft.x + size.width, topLeft.y + size.height),
)

/** Returns (zoneId, corner-or-null-for-move) under [pos]; handles of the selected zone win. */
private fun hitTest(
    zones: List<WatermarkZone>,
    pos: Offset,
    canvas: Size,
    handleRadius: Float,
    preferId: Int = -1,
): Pair<Int, Corner?>? {
    if (canvas.width <= 0f || canvas.height <= 0f) return null
    val ordered = zones.sortedByDescending { it.id == preferId }
    for (zone in ordered) {
        val r = zone.rect
        val topLeft = Offset(r.left * canvas.width, r.top * canvas.height)
        val size = Size(r.width * canvas.width, r.height * canvas.height)
        cornersOf(topLeft, size).firstOrNull { (_, c) -> (c - pos).getDistance() <= handleRadius * 1.4f }
            ?.let { return zone.id to it.first }
    }
    for (zone in ordered) {
        if (zone.rect.contains(pos.x / canvas.width, pos.y / canvas.height)) return zone.id to null
    }
    return null
}

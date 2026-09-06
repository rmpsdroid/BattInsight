package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rmpsdroid.battinsight.chart.BatteryChartModel
import com.rmpsdroid.battinsight.chart.GapMarker
import com.rmpsdroid.battinsight.chart.IntervalBar
import com.rmpsdroid.battinsight.chart.LineStrip
import com.rmpsdroid.battinsight.chart.PointMarker
import com.rmpsdroid.battinsight.chart.RefusedInterval
import com.rmpsdroid.battinsight.chart.RenderPlanner
import com.rmpsdroid.battinsight.chart.RenderPrimitive
import com.rmpsdroid.battinsight.chart.CounterChartModel

/**
 * The chart renderer, and it is deliberately stupid.
 *
 * It scales coordinates, strokes paths and draws markers. It decides **nothing**: not whether
 * two observations are comparable, not whether a boot boundary happened, not whether a missing
 * value is zero, not whether a gap should be bridged. Every one of those was settled upstream,
 * and what arrives here is a list of primitives that cannot express the wrong answer.
 *
 * That is why there is no chart library. A library given a list of points connects them,
 * because that is what charting libraries do; the whole point of this phase is that some points
 * must not be connected. Owning ~100 lines of Canvas is cheaper than fighting a dependency's
 * default, and it is the difference between a truthful chart and a plausible one.
 *
 * Height is fixed, not derived from the number of observations: a 300-sample session must not
 * produce a ten-screen graph.
 */
private val CHART_HEIGHT = 180.dp

@Composable
fun BatteryChart(model: BatteryChartModel, modifier: Modifier = Modifier) {
    val primitives = RenderPlanner.battery(model)
    val line = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.primary
    val gapColor = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            // A Canvas is invisible to a screen reader, so the same facts are published as
            // text. This is the accessible rendering, not a courtesy label.
            .semantics { contentDescription = model.accessibilityDescription() },
    ) {
        drawPercentageGuides(grid)
        primitives.forEach { drawBatteryPrimitive(it, line, marker, gapColor) }
    }
}

/** 0 / 50 / 100 guides, so the fixed scale is visible rather than merely asserted. */
private fun DrawScope.drawPercentageGuides(color: Color) {
    listOf(0f, 0.5f, 1f).forEach { fraction ->
        val y = size.height * (1f - fraction)
        drawLine(color.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawBatteryPrimitive(
    primitive: RenderPrimitive,
    lineColor: Color,
    markerColor: Color,
    gapColor: Color,
) {
    when (primitive) {
        is LineStrip -> {
            // One Path per strip. Never one Path for the whole chart: a shared path would need
            // a moveTo at every break, and forgetting one is exactly the bug this guards.
            val path = Path()
            primitive.points.forEachIndexed { index, p ->
                val x = p.x * size.width
                val y = (1f - p.y) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3f))
            primitive.points.forEach {
                drawCircle(markerColor, radius = 3f, center = Offset(it.x * size.width, (1f - it.y) * size.height))
            }
        }

        is PointMarker -> {
            // A lone observation is drawn hollow so it reads as a single reading rather than
            // the end of a line that got cut off.
            val centre = Offset(
                primitive.point.x * size.width,
                (1f - primitive.point.y) * size.height,
            )
            drawCircle(markerColor, radius = 5f, center = centre, style = Stroke(width = 2f))
        }

        is GapMarker -> {
            // Vertical rules at both edges and nothing between them. Deliberately NOT a dashed
            // connecting line: a dash from one side to the other reads as an estimated
            // trajectory, which is precisely the claim there is no evidence for.
            val start = primitive.startX * size.width
            val end = primitive.endX * size.width
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            listOf(start, end).forEach { x ->
                drawLine(
                    gapColor,
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = 2f,
                    pathEffect = dash,
                )
            }
            drawRect(
                color = gapColor.copy(alpha = 0.10f),
                topLeft = Offset(start, 0f),
                size = androidx.compose.ui.geometry.Size((end - start).coerceAtLeast(0f), size.height),
            )
        }

        else -> Unit
    }
}

/** The battery chart in words, computed from the same model the drawing uses. */
private fun BatteryChartModel.accessibilityDescription(): String {
    if (isEmpty) return "No sampled battery history for this period."
    val parts = mutableListOf<String>()
    parts += "${summary.observationCount} battery readings"
    parts += when (summary.connectedSegmentCount) {
        0 -> "no connected runs"
        1 -> "one connected run"
        else -> "${summary.connectedSegmentCount} connected runs"
    }
    if (summary.gapCount > 0) parts += "${summary.gapCount} breaks"
    summary.firstPercent?.let { first ->
        summary.lastPercent?.let { last -> parts += "from $first% to $last%" }
    }
    val head = parts.joinToString(", ") + "."
    return if (summary.gapDescriptions.isEmpty()) head
    else head + " " + summary.gapDescriptions.joinToString(" ")
}

/**
 * Counter activity as interval blocks.
 *
 * Blocks rather than a line, because the evidence is "this much accumulated somewhere between
 * these two captures" and a line would claim to know when within the window.
 */
@Composable
fun CounterIntervalChart(model: CounterChartModel, modifier: Modifier = Modifier) {
    val primitives = RenderPlanner.counters(model)
    val bar = MaterialTheme.colorScheme.tertiary
    val refusedColor = MaterialTheme.colorScheme.outline
    val baseline = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = model.accessibilityDescription() },
    ) {
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        primitives.forEach { primitive ->
            when (primitive) {
                is IntervalBar -> {
                    val left = primitive.startX * size.width
                    val width = ((primitive.endX - primitive.startX) * size.width).coerceAtLeast(2f)
                    if (primitive.isMeasuredZero) {
                        // A measured zero has no height, so it is drawn as a tick on the
                        // baseline. Drawing nothing would be indistinguishable from a refusal,
                        // and those are opposite facts.
                        drawLine(
                            bar,
                            Offset(left, size.height),
                            Offset(left + width, size.height),
                            strokeWidth = 5f,
                        )
                    } else {
                        val height = (primitive.height * size.height * 0.9f).coerceAtLeast(2f)
                        drawRect(
                            color = bar,
                            topLeft = Offset(left, size.height - height),
                            size = androidx.compose.ui.geometry.Size(width, height),
                        )
                    }
                }

                is RefusedInterval -> {
                    // Hatched outline, full height, no magnitude. Shape carries the meaning so
                    // it survives greyscale and colour-blindness; a coloured bar would not.
                    val left = primitive.startX * size.width
                    val width = ((primitive.endX - primitive.startX) * size.width).coerceAtLeast(2f)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    drawRect(
                        color = refusedColor,
                        topLeft = Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(width, size.height),
                        style = Stroke(width = 2f, pathEffect = dash),
                    )
                }

                else -> Unit
            }
        }
    }
}

private fun CounterChartModel.accessibilityDescription(): String {
    if (isEmpty) return "No comparable counter intervals for this period."
    val parts = mutableListOf<String>()
    parts += "${summary.intervalCount} measured intervals"
    if (summary.measuredZeroCount > 0) {
        parts += "${summary.measuredZeroCount} recorded no increase, which is a measurement"
    }
    if (summary.refusedCount > 0) parts += "${summary.refusedCount} could not be compared"
    val head = parts.joinToString(", ") + "."
    return if (summary.refusedDescriptions.isEmpty()) head
    else head + " " + summary.refusedDescriptions.joinToString(" ")
}

/**
 * The text fallback shown beside every chart.
 *
 * Computed from the same presentation model as the drawing, so the words and the picture cannot
 * drift apart. This is also what a mostly-gap session shows instead of an empty frame.
 */
@Composable
fun BatteryChartLegend(model: BatteryChartModel, formatDuration: (Long) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LegendValue("Start", model.summary.firstPercent?.let { "$it%" } ?: "Unavailable")
            LegendValue("Latest", model.summary.lastPercent?.let { "$it%" } ?: "Unavailable")
            LegendValue("Observed", formatDuration(model.summary.observedSpanMillis))
            LegendValue("Breaks", model.summary.gapCount.toString())
        }
        model.gaps.forEach { gap ->
            Text(
                text = "${gap.label} — ${gap.description}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LegendValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

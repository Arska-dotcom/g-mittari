package com.example.gfmeter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class AccelUnit(val label: String) {
    G("g"),
    MPS2("m/s²")
}

private const val GRAVITY_MPS2 = 9.80665f
private const val STATIONARY_THRESHOLD_MPS2 = 0.45f

@Composable
fun GForceMeterScreen(
    telemetry: TelemetryState,
    unit: AccelUnit,
    hasLocationPermission: Boolean,
    onUnitChange: (AccelUnit) -> Unit,
    onCalibrate: () -> Unit,
    onResetMaxG: () -> Unit,
    onResetSprint: () -> Unit,
    onRequestPermission: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Keskialueen kiihtyvyysmittari (Canvas)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f + 18.dp.toPx())
            // Skaalataan kehä niin, että sivuille jää tilaa Max G- ja 0–100-korteille
            val maxRadius = min(size.width * 0.25f, size.height * 0.37f)

            drawReferenceGrid(center, maxRadius)

            val magMps2 = telemetry.magnitudeMps2
            val isStationary = magMps2 < STATIONARY_THRESHOLD_MPS2

            val displayValue = if (isStationary) {
                0f
            } else if (unit == AccelUnit.G) {
                magMps2 / GRAVITY_MPS2
            } else {
                magMps2
            }

            val formattedText = String.format(Locale.US, "%.1f %s", displayValue, unit.label)

            if (isStationary) {
                val armLength = 38.dp.toPx()
                drawFourHeadedArrow(
                    center = center,
                    armLength = armLength,
                    color = Color(0xFF38BDF8)
                )

                val measured = textMeasurer.measure(
                    text = formattedText,
                    style = TextStyle(
                        color = Color(0xFF94A3B8),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = center.x - measured.size.width / 2f,
                        y = center.y + armLength + 10.dp.toPx()
                    )
                )
            } else {
                val normalizedScale = (magMps2 / GRAVITY_MPS2).coerceIn(0.12f, 1.25f)
                val arrowLength = maxRadius * normalizedScale

                val angle = atan2(telemetry.screenAccelY, telemetry.screenAccelX)
                val tip = Offset(
                    x = center.x + cos(angle) * arrowLength,
                    y = center.y + sin(angle) * arrowLength
                )

                val arrowColor = when {
                    magMps2 / GRAVITY_MPS2 > 0.65f -> Color(0xFFEF4444)
                    magMps2 / GRAVITY_MPS2 > 0.35f -> Color(0xFFF59E0B)
                    else -> Color(0xFF22C55E)
                }

                drawResultantArrow(
                    start = center,
                    end = tip,
                    color = arrowColor
                )

                val measured = textMeasurer.measure(
                    text = formattedText,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                )

                val labelOffsetDist = 20.dp.toPx()
                val rawLabelCenterX = tip.x + cos(angle) * (labelOffsetDist + measured.size.width / 2f)
                val rawLabelCenterY = tip.y + sin(angle) * (labelOffsetDist + measured.size.height / 2f)

                val clampedX = (rawLabelCenterX - measured.size.width / 2f)
                    .coerceIn(12f, size.width - measured.size.width - 12f)
                val clampedY = (rawLabelCenterY - measured.size.height / 2f)
                    .coerceIn(64.dp.toPx(), size.height - measured.size.height - 12f)

                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(clampedX, clampedY)
                )
            }
        }

        // YLÄPALKKI: Kalibrointi + GPS-tila vasemmalla | GPS-nopeus keskellä | Asetukset oikealla
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCalibrate,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFE2E8F0)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Kalibroi",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Kalibroi", fontSize = 13.sp)
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF161B26)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (telemetry.hasGpsFix) Color(0xFF22C55E) else Color(0xFFF59E0B),
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = if (telemetry.hasGpsFix) "GPS OK" else "Haetaan GPS...",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // GPS-nopeus ruudun ylälaidassa yhden desimaalin tarkkuudella
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF161B26)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f", telemetry.speedKmh),
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "km/h",
                        color = Color(0xFF94A3B8),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
            }

            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.background(Color(0xFF161B26), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Asetukset",
                    tint = Color.White
                )
            }
        }

        // VASEN KORTTI: Huippuarvo (Max G) + Nollaus
        val maxDisplayVal = if (unit == AccelUnit.G) {
            telemetry.maxMagnitudeMps2 / GRAVITY_MPS2
        } else {
            telemetry.maxMagnitudeMps2
        }

        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(156.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF161B26),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "MAX KIIHTYVYYS",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.US, "%.1f", maxDisplayVal),
                        color = Color(0xFF38BDF8),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit.label,
                        color = Color(0xFF94A3B8),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                OutlinedButton(
                    onClick = onResetMaxG,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Nollaa Max G",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Nollaa", fontSize = 12.sp)
                }
            }
        }

        // OIKEA KORTTI: 0–100 km/h -kiihtyvyysmittari
        val (statusText, statusColor) = when (telemetry.sprintState) {
            SprintState.WAITING_FOR_STOP -> "Pysäytä auto" to Color(0xFF94A3B8)
            SprintState.READY -> "VALMIS" to Color(0xFF22C55E)
            SprintState.RUNNING -> "MITATAAN..." to Color(0xFFF59E0B)
            SprintState.FINISHED -> "TULOS" to Color(0xFF38BDF8)
        }

        val borderColor = when (telemetry.sprintState) {
            SprintState.READY -> Color(0xFF22C55E)
            SprintState.RUNNING -> Color(0xFFF59E0B)
            SprintState.FINISHED -> Color(0xFF38BDF8)
            else -> Color(0xFF1E293B)
        }

        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(168.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF161B26),
            border = BorderStroke(1.5.dp, borderColor)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0–100 km/h",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    val timeText = if (
                        telemetry.sprintState == SprintState.WAITING_FOR_STOP ||
                        telemetry.sprintState == SprintState.READY
                    ) {
                        "--.--"
                    } else {
                        String.format(Locale.US, "%.2f", telemetry.sprintTimeSec)
                    }

                    Text(
                        text = timeText,
                        color = if (telemetry.sprintState == SprintState.FINISHED) {
                            Color(0xFF38BDF8)
                        } else {
                            Color.White
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "s",
                        color = Color(0xFF94A3B8),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                OutlinedButton(
                    onClick = onResetSprint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Nollaa 0–100",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Nollaa", fontSize = 12.sp)
                }
            }
        }

        // Sijaintilupailmoitus
        if (!hasLocationPermission) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF7F1D1D)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "GPS-nopeus ja 0–100-ajastin vaativat sijaintiluvan.",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    TextButton(onClick = onRequestPermission) {
                        Text("Salli", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Asetusdialogi yksikön valintaan
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = Color(0xFF161B26),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFE2E8F0),
            title = { Text("Asetukset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Kiihtyvyyden mittayksikkö:", fontSize = 14.sp, color = Color(0xFF94A3B8))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = unit == AccelUnit.G,
                            onClick = { onUnitChange(AccelUnit.G) }
                        )
                        Text("G-voimat (g)", modifier = Modifier.padding(start = 8.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = unit == AccelUnit.MPS2,
                            onClick = { onUnitChange(AccelUnit.MPS2) }
                        )
                        Text("Metriä sekunnissa toiseen (m/s²)", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("Valmis", color = Color(0xFF38BDF8))
                }
            }
        )
    }
}

private fun DrawScope.drawReferenceGrid(center: Offset, maxRadius: Float) {
    val gridColor = Color(0xFF1E293B)
    val rings = listOf(0.25f, 0.5f, 0.75f, 1.0f)

    rings.forEach { factor ->
        drawCircle(
            color = gridColor,
            radius = maxRadius * factor,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }

    drawLine(
        color = gridColor,
        start = Offset(center.x - maxRadius, center.y),
        end = Offset(center.x + maxRadius, center.y),
        strokeWidth = 1.dp.toPx()
    )
    drawLine(
        color = gridColor,
        start = Offset(center.x, center.y - maxRadius),
        end = Offset(center.x, center.y + maxRadius),
        strokeWidth = 1.dp.toPx()
    )
}

private fun DrawScope.drawFourHeadedArrow(
    center: Offset,
    armLength: Float,
    color: Color
) {
    val strokeW = 4.dp.toPx()
    val headSize = 11.dp.toPx()

    val directions = listOf(
        Offset(0f, -1f),
        Offset(0f, 1f),
        Offset(-1f, 0f),
        Offset(1f, 0f)
    )

    directions.forEach { dir ->
        val tip = Offset(center.x + dir.x * armLength, center.y + dir.y * armLength)
        drawLine(
            color = color,
            start = center,
            end = tip,
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        drawArrowHead(tip = tip, direction = dir, headSize = headSize, color = color)
    }

    drawCircle(
        color = Color.White,
        radius = 5.dp.toPx(),
        center = center
    )
}

private fun DrawScope.drawResultantArrow(
    start: Offset,
    end: Offset,
    color: Color
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1f) return

    val dir = Offset(dx / len, dy / len)

    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = 7.dp.toPx(),
        cap = StrokeCap.Round
    )

    drawArrowHead(
        tip = end,
        direction = dir,
        headSize = 18.dp.toPx(),
        color = color
    )

    drawCircle(
        color = Color.White,
        radius = 5.dp.toPx(),
        center = start
    )
}

private fun DrawScope.drawArrowHead(
    tip: Offset,
    direction: Offset,
    headSize: Float,
    color: Color
) {
    val perp = Offset(-direction.y, direction.x)
    val base = Offset(
        x = tip.x - direction.x * headSize,
        y = tip.y - direction.y * headSize
    )
    val leftWing = Offset(
        x = base.x + perp.x * (headSize * 0.65f),
        y = base.y + perp.y * (headSize * 0.65f)
    )
    val rightWing = Offset(
        x = base.x - perp.x * (headSize * 0.65f),
        y = base.y - perp.y * (headSize * 0.65f)
    )

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(leftWing.x, leftWing.y)
        lineTo(rightWing.x, rightWing.y)
        close()
    }
    drawPath(path = path, color = color)
}

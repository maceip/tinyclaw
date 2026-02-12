package ai.musicconverter.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.musicconverter.data.PlayerState

// ── Three AGSL shader variants ─────────────────────────────────

enum class AluminumVariant { COOL, NEUTRAL, WARM }

@Suppress("ConstPropertyName")
private const val AGSL_ALUMINUM_COOL = """
    uniform float2 size;
    uniform float3 lightPos;
    uniform float ridgeY;

    float hash(float2 p) {
        return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / size;
        float grain = hash(float2(fragCoord.x * 0.03, fragCoord.y * 14.0));
        float distToLight = distance(fragCoord.x, lightPos.x * size.x);
        float sheen = exp(-0.004 * distToLight);

        // Cool blue-silver: lighter above ridge, darker below
        float3 lightAlum = float3(0.78, 0.81, 0.86);
        float3 darkAlum  = float3(0.60, 0.63, 0.68);

        // HARD split at ridgeline with narrow transition
        float blend = smoothstep(ridgeY - 0.01, ridgeY + 0.01, uv.y);
        float3 baseColor = mix(lightAlum, darkAlum, blend);
        float3 finalColor = baseColor + (grain * 0.05) + (sheen * lightPos.z);
        return half4(finalColor, 1.0);
    }
"""

@Suppress("ConstPropertyName")
private const val AGSL_ALUMINUM_NEUTRAL = """
    uniform float2 size;
    uniform float3 lightPos;
    uniform float ridgeY;

    float hash(float2 p) {
        return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / size;
        float grain = hash(float2(fragCoord.x * 0.03, fragCoord.y * 14.0));
        float distToLight = distance(fragCoord.x, lightPos.x * size.x);
        float sheen = exp(-0.004 * distToLight);

        // Pure silver: lighter above, darker below
        float3 lightAlum = float3(0.82, 0.82, 0.83);
        float3 darkAlum  = float3(0.64, 0.64, 0.66);

        float blend = smoothstep(ridgeY - 0.01, ridgeY + 0.01, uv.y);
        float3 baseColor = mix(lightAlum, darkAlum, blend);
        float3 finalColor = baseColor + (grain * 0.05) + (sheen * lightPos.z);
        return half4(finalColor, 1.0);
    }
"""

@Suppress("ConstPropertyName")
private const val AGSL_ALUMINUM_WARM = """
    uniform float2 size;
    uniform float3 lightPos;
    uniform float ridgeY;

    float hash(float2 p) {
        return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / size;
        float grain = hash(float2(fragCoord.x * 0.03, fragCoord.y * 14.0));
        float distToLight = distance(fragCoord.x, lightPos.x * size.x);
        float sheen = exp(-0.004 * distToLight);

        // Warm champagne-silver: lighter above, darker below
        float3 lightAlum = float3(0.84, 0.82, 0.79);
        float3 darkAlum  = float3(0.66, 0.64, 0.61);

        float blend = smoothstep(ridgeY - 0.01, ridgeY + 0.01, uv.y);
        float3 baseColor = mix(lightAlum, darkAlum, blend);
        float3 finalColor = baseColor + (grain * 0.05) + (sheen * lightPos.z);
        return half4(finalColor, 1.0);
    }
"""

// ── Fallback gradients per variant ─────────────────────────────

private fun fallbackGradient(variant: AluminumVariant): Brush {
    val (light, dark) = when (variant) {
        AluminumVariant.COOL -> Color(0xFFCDD2DB) to Color(0xFF9CA1AA)
        AluminumVariant.NEUTRAL -> Color(0xFFD4D4D6) to Color(0xFFA4A4A8)
        AluminumVariant.WARM -> Color(0xFFD8D4CF) to Color(0xFFA8A49F)
    }
    return Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to light,
            0.64f to light,
            0.68f to dark,
            1.0f to dark,
        )
    )
}

// ── Button gradients ───────────────────────────────────────────

private val ButtonGlossGradient = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFF8F8F8), Color(0xFFEEEEEE), Color(0xFFD8D8D8),
        Color(0xFFC0C0C0), Color(0xFFB4B4B4),
    )
)

private val ButtonPressedGradient = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFB0B0B0), Color(0xFFBBBBBB), Color(0xFFC5C5C5), Color(0xFFBEBEBE),
    )
)

private val ChromeBezelBrush = Brush.linearGradient(
    colors = listOf(Color.White, Color.Gray, Color.White),
    start = Offset.Zero,
    end = Offset.Infinite
)

// ── Clear Gel gradients ────────────────────────────────────────
// Classic Mac OS X Aqua-style opaque capsule buttons

enum class AquaStyle { GRAY, BLUE }

private fun aquaGradient(style: AquaStyle, pressed: Boolean): Brush {
    if (pressed) {
        return when (style) {
            AquaStyle.GRAY -> Brush.verticalGradient(listOf(
                Color(0xFFB0B0B4), Color(0xFFBCBCC0), Color(0xFFC6C6CA), Color(0xFFBABABE)
            ))
            AquaStyle.BLUE -> Brush.verticalGradient(listOf(
                Color(0xFF5080C0), Color(0xFF6090D0), Color(0xFF7098D4), Color(0xFF5888C8)
            ))
        }
    }
    return when (style) {
        AquaStyle.GRAY -> Brush.verticalGradient(listOf(
            Color(0xFFF4F4F6),  // top highlight
            Color(0xFFE8E8EC),  // upper body
            Color(0xFFD0D0D6),  // mid
            Color(0xFFBCBCC4),  // lower body
            Color(0xFFD2D2D8),  // bottom highlight bounce
        ))
        AquaStyle.BLUE -> Brush.verticalGradient(listOf(
            Color(0xFFB8D4F0),  // top highlight
            Color(0xFF7BB4E8),  // upper body
            Color(0xFF4A94DC),  // mid
            Color(0xFF3878C8),  // lower body
            Color(0xFF5A90D4),  // bottom highlight bounce
        ))
    }
}

private fun aquaBezel(style: AquaStyle): Brush {
    return when (style) {
        AquaStyle.GRAY -> Brush.linearGradient(
            listOf(Color(0xFFDDDDE0), Color(0xFF999CA0), Color(0xFFBBBCC0), Color(0xFF999CA0), Color(0xFFDDDDE0)),
            start = Offset.Zero, end = Offset.Infinite
        )
        AquaStyle.BLUE -> Brush.linearGradient(
            listOf(Color(0xFF90B8E0), Color(0xFF4878B4), Color(0xFF6898CC), Color(0xFF4878B4), Color(0xFF90B8E0)),
            start = Offset.Zero, end = Offset.Infinite
        )
    }
}

// ── Public Aqua Gel Button ─────────────────────────────────────

/**
 * Classic Mac OS X Aqua-style capsule button. Opaque, rounded pill shape
 * with glossy specular highlight and depth. Supports gray (cancel/secondary)
 * and blue (okay/primary) styles.
 */
@Composable
fun GelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AquaStyle = AquaStyle.GRAY,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val gelShape = RoundedCornerShape(50) // full pill

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isPressed) 1.dp else 4.dp,
                shape = gelShape,
                ambientColor = Color(0x55666666),
                spotColor = Color(0x66444444)
            )
            .clip(gelShape)
            .background(aquaGradient(style, isPressed))
            .border(1.dp, aquaBezel(style), gelShape)
            .drawBehind { drawAquaOverlay(isPressed, style) }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}

private val ProgressBarBg = Color(0xFFFAF6E8)
private const val RIDGE_Y_FRACTION = 0.68f

// ── Reusable aluminum background modifier (for top bar too) ────

@Composable
fun aluminumBackgroundModifier(variant: AluminumVariant = AluminumVariant.NEUTRAL): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shaderSrc = when (variant) {
            AluminumVariant.COOL -> AGSL_ALUMINUM_COOL
            AluminumVariant.NEUTRAL -> AGSL_ALUMINUM_NEUTRAL
            AluminumVariant.WARM -> AGSL_ALUMINUM_WARM
        }
        val shader = remember(variant) { RuntimeShader(shaderSrc) }
        Modifier.drawWithCache {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("lightPos", 0.45f, 0.2f, 0.12f)
            shader.setFloatUniform("ridgeY", 1.0f) // no split for top bar
            val shaderBrush = ShaderBrush(shader)
            onDrawBehind {
                drawRect(brush = shaderBrush)
                drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.5f)
            }
        }
    } else {
        Modifier
            .background(fallbackGradient(variant))
            .drawBehind { drawFallbackTexture() }
    }
}

// ── Notification display text (replaces snackbar/toast) ────────

/**
 * Call this to get the display text for the calculator-style progress bar.
 * When there's a notification, it overrides the elapsed time.
 */

// ── Main Bottom Bar ────────────────────────────────────────────

@Composable
fun BrushedMetalBottomBar(
    isConverting: Boolean,
    conversionProgress: Float,
    elapsedTimeText: String,
    notificationText: String? = null,
    playerState: PlayerState = PlayerState(),
    showMusicRain: Boolean = false,
    onMusicRainComplete: () -> Unit = {},
    onConvertClick: () -> Unit,
    onPlayPauseClick: () -> Unit = {},
    onSearchClick: () -> Unit,
    onSeekTo: (Float) -> Unit = {},
    onVariantChanged: (AluminumVariant) -> Unit = {},
    onPreviousClick: () -> Unit = {},
    onRewindClick: () -> Unit = {},
    onFastForwardClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val barShape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)

    var variantIndex by remember { mutableIntStateOf(1) }
    val currentVariant = AluminumVariant.entries[variantIndex]

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 10.dp, shape = barShape)
            .clip(barShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(twoToneAluminumModifier(currentVariant))
        ) {
            // ── Now-playing track info (only when media loaded) ──
            if (playerState.hasMedia) {
                NowPlayingInfo(
                    title = playerState.currentTitle,
                    artist = playerState.currentArtist,
                    album = playerState.currentAlbum,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }

            // ── Progress / scrubber display (with music rain overlay) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(top = if (playerState.hasMedia) 2.dp else 8.dp, bottom = 4.dp)
            ) {
                CalculatorProgressBar(
                    progress = if (playerState.hasMedia) playerState.progress else conversionProgress,
                    timeText = if (playerState.hasMedia) playerState.displayPosition
                               else notificationText ?: elapsedTimeText,
                    endTimeText = if (playerState.hasMedia) playerState.displayDuration else null,
                    isNotification = notificationText != null && !playerState.hasMedia,
                    isPlayerMode = playerState.hasMedia,
                    onSeek = if (playerState.hasMedia) onSeekTo else null,
                    modifier = Modifier.fillMaxWidth()
                )
                DotMatrixMusicRain(
                    isPlaying = showMusicRain,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(6.dp)),
                    onComplete = onMusicRainComplete
                )
            }

            // ── Mini shelf ──
            MiniShelf(Modifier.fillMaxWidth().padding(horizontal = 6.dp))

            // ── Transport controls ──
            TransportControlsRow(
                isConverting = isConverting,
                isPlaying = playerState.isPlaying,
                hasMedia = playerState.hasMedia,
                onPreviousClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (playerState.hasMedia) {
                        onPreviousClick()
                    } else {
                        variantIndex = 0
                        onVariantChanged(AluminumVariant.COOL)
                        onPreviousClick()
                    }
                },
                onRewindClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onRewindClick()
                },
                onCenterClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    if (playerState.hasMedia) {
                        onPlayPauseClick()
                    } else {
                        variantIndex = 1
                        onVariantChanged(AluminumVariant.NEUTRAL)
                        onConvertClick()
                    }
                },
                onFastForwardClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onFastForwardClick()
                },
                onNextClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (playerState.hasMedia) {
                        onNextClick()
                    } else {
                        variantIndex = 2
                        onVariantChanged(AluminumVariant.WARM)
                        onNextClick()
                    }
                },
                onSearchClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSearchClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 6.dp)
            )

            // ── Ridgeline ──
            Canvas(Modifier.fillMaxWidth().height(8.dp)) { drawRidgeline() }

            // ── Darker zone below ridge ──
            Spacer(Modifier.fillMaxWidth().height(16.dp))
        }
    }
}

// ── Two-tone aluminum modifier ─────────────────────────────────

@Composable
private fun twoToneAluminumModifier(variant: AluminumVariant): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shaderSrc = when (variant) {
            AluminumVariant.COOL -> AGSL_ALUMINUM_COOL
            AluminumVariant.NEUTRAL -> AGSL_ALUMINUM_NEUTRAL
            AluminumVariant.WARM -> AGSL_ALUMINUM_WARM
        }
        val shader = remember(variant) { RuntimeShader(shaderSrc) }
        Modifier.drawWithCache {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("lightPos", 0.45f, 0.2f, 0.12f)
            shader.setFloatUniform("ridgeY", RIDGE_Y_FRACTION)
            val shaderBrush = ShaderBrush(shader)
            onDrawBehind {
                drawRect(brush = shaderBrush)
                drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.5f)
            }
        }
    } else {
        Modifier
            .background(fallbackGradient(variant))
            .drawBehind { drawFallbackTexture() }
    }
}

// ── Mini Shelf ─────────────────────────────────────────────────

@Composable
private fun MiniShelf(modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(5.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(Color.Black.copy(alpha = 0.12f), Offset(8f, 0f), Offset(size.width - 8f, 0f), strokeWidth = 1f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(8f, 2f), Offset(size.width - 8f, 2f), strokeWidth = 1f)
            drawLine(Color.Black.copy(alpha = 0.06f), Offset(8f, 4f), Offset(size.width - 8f, 4f), strokeWidth = 0.5f)
        }
    }
}

private fun DrawScope.drawFallbackTexture() {
    val lineSpacing = 1.5f
    var y = 0f
    while (y < size.height) {
        val alpha = ((y / size.height) * 0.03f + 0.01f).coerceIn(0f, 0.05f)
        drawLine(Color.Black.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
        y += lineSpacing
    }
    drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.5f)
}

// ── Ridgeline ──────────────────────────────────────────────────

private fun DrawScope.drawRidgeline() {
    val shadowPath = Path().apply {
        moveTo(0f, size.height * 0.5f)
        quadraticTo(size.width * 0.5f, -size.height * 1.5f, size.width, size.height * 0.5f)
    }
    drawPath(shadowPath, Color.Black.copy(alpha = 0.18f), style = Stroke(width = 2.5f))
    val highlightPath = Path().apply {
        moveTo(0f, size.height * 0.35f)
        quadraticTo(size.width * 0.5f, -size.height * 2.0f, size.width, size.height * 0.35f)
    }
    drawPath(highlightPath, Color.White.copy(alpha = 0.5f), style = Stroke(width = 1.5f))
}

// ── Calculator-style Progress Bar / Notification Display ───────

@Composable
private fun CalculatorProgressBar(
    progress: Float,
    timeText: String,
    endTimeText: String? = null,
    isNotification: Boolean = false,
    isPlayerMode: Boolean = false,
    onSeek: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(progress) }
    if (!isSeeking) sliderPosition = progress

    Box(
        modifier = modifier
            .height(32.dp)
            .shadow(2.dp, RoundedCornerShape(6.dp), ambientColor = Color(0xFF666666), spotColor = Color(0xFF444444))
            .clip(RoundedCornerShape(6.dp))
            .background(ProgressBarBg)
            .border(1.dp, Brush.linearGradient(listOf(Color(0xFF999999), Color(0xFFBBBBBB), Color(0xFF888888)), Offset.Zero, Offset.Infinite), RoundedCornerShape(6.dp))
            .drawBehind {
                drawLine(Color.Black.copy(alpha = 0.15f), Offset(4f, 1f), Offset(size.width - 4f, 1f), strokeWidth = 1.5f)
                drawLine(Color.White.copy(alpha = 0.3f), Offset(4f, size.height - 2f), Offset(size.width - 4f, size.height - 2f), strokeWidth = 0.5f)
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (isNotification) Color(0xFF8B4513) else Color(0xFF222222),
                    letterSpacing = if (isPlayerMode) 1.sp else 2.sp
                ),
                maxLines = 1
            )

            if (!isNotification) {
                if (!isPlayerMode) {
                    Canvas(modifier = Modifier.size(7.dp)) {
                        val path = Path().apply {
                            moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                        }
                        drawPath(path, Color(0xFF222222))
                    }
                }

                Slider(
                    value = sliderPosition,
                    onValueChange = {
                        isSeeking = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        isSeeking = false
                        onSeek?.invoke(sliderPosition)
                    },
                    modifier = Modifier.weight(1f).height(18.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF555555),
                        activeTrackColor = Color(0xFF777777),
                        inactiveTrackColor = Color(0xFFCCCCCC)
                    )
                )

                if (endTimeText != null) {
                    Text(
                        text = endTimeText,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF222222),
                            letterSpacing = 1.sp
                        ),
                        maxLines = 1
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ── Transport Controls Row ─────────────────────────────────────

@Composable
private fun TransportControlsRow(
    isConverting: Boolean,
    isPlaying: Boolean = false,
    hasMedia: Boolean = false,
    onPreviousClick: () -> Unit,
    onRewindClick: () -> Unit,
    onCenterClick: () -> Unit,
    onFastForwardClick: () -> Unit,
    onNextClick: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMuted by remember { mutableStateOf(false) }
    val view = LocalView.current

    Row(modifier = modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        GlossyButton(onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            isMuted = !isMuted
        }, size = 36) { VolumeIcon(muted = isMuted) }
        Spacer(Modifier.weight(1f))
        GlossyButton(onClick = onPreviousClick, size = 44) { PreviousTrackIcon() }
        Spacer(Modifier.width(8.dp))
        GlossyButton(onClick = onRewindClick, size = 44) { RewindIcon() }
        Spacer(Modifier.width(10.dp))
        CenterButton(isConverting = isConverting, isPlaying = isPlaying, hasMedia = hasMedia, onClick = onCenterClick)
        Spacer(Modifier.width(10.dp))
        GlossyButton(onClick = onFastForwardClick, size = 44) { FastForwardIcon() }
        Spacer(Modifier.width(8.dp))
        GlossyButton(onClick = onNextClick, size = 44) { NextTrackIcon() }
        Spacer(Modifier.weight(1f))
        GlossyButton(onClick = onSearchClick, size = 36) {
            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp), tint = Color(0xFF444444))
        }
    }
}

// ── Glossy 3D Bubble Button ────────────────────────────────────

@Composable
internal fun GlossyButton(onClick: () -> Unit, size: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .size(size.dp)
            .shadow(if (isPressed) 1.dp else 4.dp, CircleShape, ambientColor = Color(0xFF777777), spotColor = Color(0xFF555555))
            .clip(CircleShape)
            .background(if (isPressed) ButtonPressedGradient else ButtonGlossGradient)
            .border(0.5.dp, ChromeBezelBrush, CircleShape)
            .drawBehind { drawGlossyOverlay(isPressed) }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

private fun DrawScope.drawGlossyOverlay(isPressed: Boolean) {
    drawCircle(
        Brush.verticalGradient(listOf(Color(0xFFCCCCCC), Color(0xFF888888), Color(0xFFAAAAAA))),
        radius = size.minDimension / 2f, style = Stroke(width = 1.2f)
    )
    if (!isPressed) {
        val cx = size.width / 2f; val cy = size.height / 2f; val r = size.minDimension / 2f - 3f
        val highlightPath = Path().apply {
            arcTo(Rect(cx - r, cy - r, cx + r, cy + r), 195f, 150f, true)
            quadraticTo(cx, cy - r * 0.2f, cx + r * 0.7f, cy - r * 0.7f)
        }
        drawPath(highlightPath, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.0f)), startY = 0f, endY = size.height * 0.5f))
    }
    val cx = size.width / 2f; val cy = size.height / 2f; val r = size.minDimension / 2f - 2f
    val shadowPath = Path().apply {
        arcTo(Rect(cx - r, cy - r, cx + r, cy + r), 20f, 140f, true)
        quadraticTo(cx, cy + r * 0.5f, cx - r * 0.6f, cy + r * 0.8f)
    }
    drawPath(shadowPath, Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (isPressed) 0.04f else 0.12f)), startY = size.height * 0.55f, endY = size.height))
}

// ── Aqua overlay for capsule buttons ────────────────────────────

private fun DrawScope.drawAquaOverlay(isPressed: Boolean, style: AquaStyle) {
    val w = size.width; val h = size.height

    // Top specular highlight — the classic Aqua glossy white band across upper third
    if (!isPressed) {
        val highlightPath = Path().apply {
            moveTo(h / 2f, 2f)
            lineTo(w - h / 2f, 2f)
            lineTo(w - h / 2f, h * 0.42f)
            quadraticTo(w / 2f, h * 0.32f, h / 2f, h * 0.42f)
            close()
        }
        drawPath(
            highlightPath,
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.15f)),
                startY = 0f, endY = h * 0.45f
            )
        )
    }

    // Bottom inner glow — subtle light bounce at bottom edge
    val bounceAlpha = if (style == AquaStyle.BLUE) 0.2f else 0.3f
    drawLine(
        Color.White.copy(alpha = if (isPressed) bounceAlpha * 0.5f else bounceAlpha),
        Offset(h / 2f, h - 2.5f),
        Offset(w - h / 2f, h - 2.5f),
        strokeWidth = 1f
    )
}

// ── Center Button (play/pause/stop) ──────────────────────────────

@Composable
private fun CenterButton(
    isConverting: Boolean,
    isPlaying: Boolean,
    hasMedia: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(modifier = modifier.size(66.dp), contentAlignment = Alignment.Center) {
        // Outer chrome ring
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(Brush.verticalGradient(listOf(Color(0xFFE0E0E0), Color(0xFF909090), Color(0xFFBBBBBB))), size.minDimension / 2f, style = Stroke(5f))
            drawCircle(Color.White.copy(alpha = 0.35f), size.minDimension / 2f + 1f, style = Stroke(0.5f))
        }
        // Inner glossy button
        Box(
            modifier = Modifier.size(54.dp)
                .shadow(if (isPressed) 1.dp else 5.dp, CircleShape, ambientColor = Color(0xFF777777), spotColor = Color(0xFF555555))
                .clip(CircleShape)
                .background(if (isPressed) ButtonPressedGradient else ButtonGlossGradient)
                .border(0.5.dp, ChromeBezelBrush, CircleShape)
                .drawBehind { drawGlossyOverlay(isPressed) }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(28.dp)) {
                val cx = size.width / 2f; val cy = size.height / 2f
                val c = Color(0xFF2A2A2A)
                when {
                    isConverting -> {
                        // Stop square
                        val s = size.minDimension * 0.32f
                        drawRect(c, Offset(cx - s, cy - s), Size(s * 2, s * 2))
                    }
                    hasMedia && isPlaying -> {
                        // Pause bars
                        val barW = size.minDimension * 0.12f
                        val barH = size.minDimension * 0.6f
                        val gap = size.minDimension * 0.1f
                        drawRect(c, Offset(cx - gap - barW, cy - barH / 2), Size(barW, barH))
                        drawRect(c, Offset(cx + gap, cy - barH / 2), Size(barW, barH))
                    }
                    else -> {
                        // Play triangle
                        val path = Path().apply {
                            moveTo(cx - size.width * 0.22f, cy - size.height * 0.38f)
                            lineTo(cx + size.width * 0.35f, cy)
                            lineTo(cx - size.width * 0.22f, cy + size.height * 0.38f)
                            close()
                        }
                        drawPath(path, c)
                    }
                }
            }
        }
    }
}

// ── Transport Icons ────────────────────────────────────────────

@Composable
internal fun PreviousTrackIcon() {
    Canvas(Modifier.size(18.dp)) { val c = Color(0xFF3A3A3A)
        drawLine(c, Offset(3f, 4f), Offset(3f, size.height - 4f), 3f)
        drawPath(Path().apply { moveTo(size.width - 2f, 4f); lineTo(7f, size.height / 2f); lineTo(size.width - 2f, size.height - 4f); close() }, c)
    }
}

@Composable
internal fun NextTrackIcon() {
    Canvas(Modifier.size(18.dp)) { val c = Color(0xFF3A3A3A)
        drawPath(Path().apply { moveTo(2f, 4f); lineTo(size.width - 7f, size.height / 2f); lineTo(2f, size.height - 4f); close() }, c)
        drawLine(c, Offset(size.width - 3f, 4f), Offset(size.width - 3f, size.height - 4f), 3f)
    }
}

@Composable
private fun RewindIcon() {
    Canvas(Modifier.size(20.dp)) { val c = Color(0xFF3A3A3A)
        drawPath(Path().apply { moveTo(size.width / 2f, 4f); lineTo(2f, size.height / 2f); lineTo(size.width / 2f, size.height - 4f); close() }, c)
        drawPath(Path().apply { moveTo(size.width - 2f, 4f); lineTo(size.width / 2f, size.height / 2f); lineTo(size.width - 2f, size.height - 4f); close() }, c)
    }
}

@Composable
private fun FastForwardIcon() {
    Canvas(Modifier.size(20.dp)) { val c = Color(0xFF3A3A3A)
        drawPath(Path().apply { moveTo(2f, 4f); lineTo(size.width / 2f, size.height / 2f); lineTo(2f, size.height - 4f); close() }, c)
        drawPath(Path().apply { moveTo(size.width / 2f, 4f); lineTo(size.width - 2f, size.height / 2f); lineTo(size.width / 2f, size.height - 4f); close() }, c)
    }
}

@Composable
private fun VolumeIcon(muted: Boolean) {
    Canvas(Modifier.size(16.dp)) {
        val c = Color(0xFF3A3A3A)
        drawRect(c, Offset(1f, size.height * 0.3f), Size(4f, size.height * 0.4f))
        drawPath(Path().apply {
            moveTo(5f, size.height * 0.3f)
            lineTo(10f, 2f)
            lineTo(10f, size.height - 2f)
            lineTo(5f, size.height * 0.7f)
            close()
        }, c)
        if (muted) {
            drawLine(Color(0xFFCC3333), Offset(11f, size.height * 0.3f), Offset(size.width - 1f, size.height * 0.7f), 2f)
            drawLine(Color(0xFFCC3333), Offset(11f, size.height * 0.7f), Offset(size.width - 1f, size.height * 0.3f), 2f)
        } else {
            drawArc(c, -40f, 80f, false, Offset(11f, size.height * 0.2f), Size(6f, size.height * 0.6f), style = Stroke(1.5f))
        }
    }
}

// ── Now Playing Info Display ────────────────────────────────────

@Composable
private fun NowPlayingInfo(
    title: String,
    artist: String,
    album: String,
    modifier: Modifier = Modifier
) {
    val subtitleText = when {
        artist.isNotBlank() && album.isNotBlank() -> "$artist \u2014 $album"
        artist.isNotBlank() -> artist
        album.isNotBlank() -> album
        else -> ""
    }

    Box(
        modifier = modifier
            .height(38.dp)
            .shadow(2.dp, RoundedCornerShape(6.dp), ambientColor = Color(0xFF666666), spotColor = Color(0xFF444444))
            .clip(RoundedCornerShape(6.dp))
            .background(ProgressBarBg)
            .border(1.dp, Brush.linearGradient(listOf(Color(0xFF999999), Color(0xFFBBBBBB), Color(0xFF888888)), Offset.Zero, Offset.Infinite), RoundedCornerShape(6.dp))
            .drawBehind {
                drawLine(Color.Black.copy(alpha = 0.15f), Offset(4f, 1f), Offset(size.width - 4f, 1f), strokeWidth = 1.5f)
                drawLine(Color.White.copy(alpha = 0.3f), Offset(4f, size.height - 2f), Offset(size.width - 4f, size.height - 2f), strokeWidth = 0.5f)
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title.ifBlank { "No track" },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF222222),
                    letterSpacing = 0.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitleText.isNotBlank()) {
                Text(
                    text = subtitleText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFF666666),
                        letterSpacing = 0.5.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

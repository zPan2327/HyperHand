// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.zpan2327.liquidglass

// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

internal class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    // The washes below are painted behind the glass, so on a translucent bar they show through
    // the whole surface. Clipping them to the glass outline keeps the 6% white lift inside the
    // pill instead of spilling onto the page at the rounded ends.
    private val shape: Shape = RectangleShape,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {

    // Critically damped: an underdamped spec (previously 0.5) overshoots below 0 on release,
    // which made the `progress > 0f` guard below snap the whole white wash off for a frame and
    // then flick it back on — visible as a jump in the bar's apparent opacity at the rim.
    private val pressProgressAnimationSpec = spring(1f, 400f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero

    private val shapePath = Path()

    // Smoothstep press spot; without runtime shader support, falls back to a radialGradient
    // approximation (linear instead of S-curve falloff — barely distinguishable).
    private val spotShader: RuntimeShader? =
        if (isRuntimeShaderSupported()) RuntimeShader(SPOT_SHADER) else null

    val modifier: Modifier = Modifier.drawWithContent {
        // Clamped: a spring settling on 0 can dip negative, and a negative alpha is not a
        // meaningful colour to hand to Skia.
        val progress = pressProgressAnimation.value.coerceIn(0f, 1f)
        if (progress > 0f) {
            shapePath.reset()
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rectangle -> shapePath.addRect(outline.rect)
                is Outline.Rounded -> shapePath.addRoundRect(outline.roundRect)
                is Outline.Generic -> shapePath.addPath(outline.path)
            }
            clipPath(shapePath) {
                drawRect(
                    color = Color.White.copy(alpha = 0.06f * progress),
                    blendMode = BlendMode.Plus,
                )
                val pos = position(size, positionAnimation.value)
                val radius = (size.minDimension * 1.2f).coerceAtLeast(1f)
                val center = Offset(
                    x = pos.x.coerceIn(0f, size.width),
                    y = pos.y.coerceIn(0f, size.height),
                )
                val spotColor = Color.White.copy(alpha = 0.12f * progress)
                if (spotShader != null) {
                    spotShader.setColorUniform("color", spotColor)
                    spotShader.setFloatUniform("radius", radius)
                    spotShader.setFloatUniform("position", center.x, center.y)
                    drawRect(
                        brush = spotShader.asBrush(),
                        blendMode = BlendMode.Plus,
                    )
                } else {
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to spotColor,
                                0.5f to spotColor,
                                1.0f to Color.White.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = radius,
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }

    // On release the spot springs back to the press-down point while the press fades out.
    private fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
        }
    }
}

// Press spot: solid within half the radius, smoothstep falloff toward the edge.
private const val SPOT_SHADER = """
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""

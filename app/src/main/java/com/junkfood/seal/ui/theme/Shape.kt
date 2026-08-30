package com.junkfood.seal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Slightly rounder than the M3 defaults. The app is a grid of artwork tiles and cards, and
 * the softer corners read as friendlier without drifting away from Material.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Corner radius for playlist / track artwork. */
val ArtworkShape = RoundedCornerShape(12.dp)

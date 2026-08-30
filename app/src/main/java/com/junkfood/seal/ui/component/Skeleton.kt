package com.junkfood.seal.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.junkfood.seal.ui.theme.ArtworkShape
import com.junkfood.seal.ui.theme.PreviewThemeLight

/**
 * A softly pulsing placeholder block. Shown in the shape of the content that is loading,
 * which reads as progress far better than a lone spinner on an empty screen.
 */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    Spacer(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.25f))
    )
}

/** One placeholder row matching the layout of a real audio/playlist row. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerBlock(modifier = Modifier.size(56.dp), shape = ArtworkShape)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBlock(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(15.dp),
                shape = RoundedCornerShape(4.dp),
            )
            Spacer(Modifier.height(8.dp))
            ShimmerBlock(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp),
                shape = RoundedCornerShape(4.dp),
            )
        }
    }
}

/** A short column of skeleton rows, for a list that is still loading. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 6) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(rows) { SkeletonRow() }
    }
}

@Preview
@Composable
private fun SkeletonPreview() {
    PreviewThemeLight { SkeletonList(modifier = Modifier.padding(16.dp)) }
}

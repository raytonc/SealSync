package com.junkfood.seal.ui.common.motion

import android.graphics.Path
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.Easing

fun PathInterpolator.toEasing(): Easing {
    return Easing { f -> this.getInterpolation(f) }
}

private val path = Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.05F, 0F, 0.133333F, 0.06F, 0.166666F, 0.4F)
    cubicTo(0.208333F, 0.82F, 0.25F, 1F, 1F, 1F)
}

private val emphasizePathInterpolator = PathInterpolator(path)
val emphasizeEasing = emphasizePathInterpolator.toEasing()




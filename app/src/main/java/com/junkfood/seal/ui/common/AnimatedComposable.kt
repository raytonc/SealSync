package com.junkfood.seal.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.junkfood.seal.ui.common.motion.materialSharedAxisXIn
import com.junkfood.seal.ui.common.motion.materialSharedAxisXOut

/** How far a page slides in or out, as a fraction of its width. */
private const val SLIDE_FRACTION = 0.10f

/**
 * A navigation destination that slides along the shared X axis, forward on the way in and
 * backward on the way out.
 */
fun NavGraphBuilder.animatedComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit
) = composable(
    route = route,
    arguments = arguments,
    deepLinks = deepLinks,
    enterTransition = {
        materialSharedAxisXIn(initialOffsetX = { (it * SLIDE_FRACTION).toInt() })
    },
    exitTransition = {
        materialSharedAxisXOut(targetOffsetX = { -(it * SLIDE_FRACTION).toInt() })
    },
    popEnterTransition = {
        materialSharedAxisXIn(initialOffsetX = { -(it * SLIDE_FRACTION).toInt() })
    },
    popExitTransition = {
        materialSharedAxisXOut(targetOffsetX = { (it * SLIDE_FRACTION).toInt() })
    },
    content = content
)

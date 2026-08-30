@file:OptIn(ExperimentalTextApi::class)

package com.junkfood.seal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp


/**
 * Stock M3 metrics with a deliberate weight ramp: headlines and titles carry more weight than
 * the defaults so that a screen of cards has an obvious reading order, while body text stays
 * at Normal for legibility. Tightened letter spacing on the large sizes keeps big headers from
 * looking airy.
 */
val Typography =
    Typography().run {
        copy(
            displayLarge = displayLarge.emphasize(FontWeight.Bold, -0.02f),
            displayMedium = displayMedium.emphasize(FontWeight.Bold, -0.02f),
            displaySmall = displaySmall.emphasize(FontWeight.Bold, -0.015f),
            headlineLarge = headlineLarge.emphasize(FontWeight.Bold, -0.015f),
            headlineMedium = headlineMedium.emphasize(FontWeight.Bold, -0.01f),
            headlineSmall = headlineSmall.emphasize(FontWeight.SemiBold, -0.01f),
            titleLarge = titleLarge.emphasize(FontWeight.Bold, -0.01f),
            titleMedium = titleMedium.emphasize(FontWeight.SemiBold),
            titleSmall = titleSmall.emphasize(FontWeight.SemiBold),
            bodyLarge = bodyLarge.applyLinebreak().applyTextDirection(),
            bodyMedium = bodyMedium.applyLinebreak().applyTextDirection(),
            bodySmall = bodySmall.applyLinebreak().applyTextDirection(),
            labelLarge = labelLarge.emphasize(FontWeight.SemiBold),
            labelMedium = labelMedium.emphasize(FontWeight.Medium),
            labelSmall = labelSmall.emphasize(FontWeight.Medium),
        )
    }

private fun TextStyle.emphasize(weight: FontWeight, letterSpacingEm: Float? = null): TextStyle =
    copy(
        fontWeight = weight,
        letterSpacing = letterSpacingEm?.em ?: letterSpacing,
        textDirection = TextDirection.Content,
    )

private fun TextStyle.applyLinebreak(): TextStyle = this.copy(lineBreak = LineBreak.Paragraph)
private fun TextStyle.applyTextDirection(): TextStyle =
    this.copy(textDirection = TextDirection.Content)


val preferenceTitle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 17.sp, lineHeight = 22.sp,
    lineBreak = LineBreak.Paragraph,
)

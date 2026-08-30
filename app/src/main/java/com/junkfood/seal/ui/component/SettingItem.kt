package com.junkfood.seal.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


/**
 * Header for a group of related preferences. Small and tinted, so a section reads as a
 * label rather than a second heading competing with the app bar's title.
 */
@Composable
fun SettingSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.padding(start = 28.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Wraps a group of preference rows in one rounded tonal container, so settings read as a
 * few grouped decisions instead of one undifferentiated column of text.
 */
@Composable
fun SettingGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = { content() })
    }
}

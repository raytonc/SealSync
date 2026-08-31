package com.junkfood.seal.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.google.android.material.color.DynamicColors
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.isFDroidBuild
import com.junkfood.seal.ui.theme.DEFAULT_SEED_COLOR
import com.kyant.monet.PaletteStyle
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// --- Setup / sync configuration ---
const val SETUP_COMPLETED = "setup_completed"
const val AUDIO_DIRECTORY = "audio_dir"

/** SAF tree URI for the audio folder; the authoritative handle for reads and writes. */
const val AUDIO_DIRECTORY_URI = "audio_dir_uri"
const val YOUTUBE_API_KEY = "youtube_api_key"
const val YOUTUBE_CHANNEL_HANDLE = "youtube_channel_handle"
const val NOTIFICATION = "notification"
const val CELLULAR_DOWNLOAD = "cellular_download"

// --- Scheduled background sync ---
/** Whether the periodic sync is scheduled at all. Off until the user asks for it. */
const val AUTO_SYNC_ENABLED = "auto_sync_enabled"

/** How often the periodic sync runs, in hours. One of [AUTO_SYNC_INTERVALS]. */
const val AUTO_SYNC_INTERVAL_HOURS = "auto_sync_interval_hours"

/** Whether a scheduled sync may run while charging only. */
const val AUTO_SYNC_REQUIRES_CHARGING = "auto_sync_requires_charging"

/**
 * The intervals the settings screen offers, in hours.
 *
 * Nothing shorter than 6h at the low end and nothing beyond a week at the high end.
 * WorkManager clamps a periodic request to a 15-minute floor anyway, but the floor that
 * matters here is a different one: a sync lists every playlist over the network and can
 * spend minutes downloading, so running it many times a day costs real battery to
 * discover, almost always, that nothing changed.
 */
val AUTO_SYNC_INTERVALS = listOf(6, 12, 24, 72, 168)

/** Default cadence: once a day. */
const val AUTO_SYNC_DEFAULT_INTERVAL_HOURS = 24

// --- Updates ---
const val YT_DLP_VERSION = "yt-dlp_init"
const val YT_DLP_AUTO_UPDATE = "yt-dlp_update"
const val YT_DLP_UPDATE_CHANNEL = "yt-dlp_update_channel"
const val YT_DLP_UPDATE_TIME = "yt-dlp_last_update"
const val YT_DLP_UPDATE_INTERVAL = "yt-dlp_update_interval"
const val AUTO_UPDATE = "auto_update"
const val UPDATE_CHANNEL = "update_channel"

// --- Theme ---
private const val DARK_THEME_VALUE = "dark_theme_value"
private const val THEME_COLOR = "theme_color"
private const val PALETTE_STYLE = "palette_style"
private const val DYNAMIC_COLOR = "dynamic_color"
private const val HIGH_CONTRAST = "high_contrast"

/** Update channels. Only the non-default value of each pair is ever tested for. */
const val STABLE = 0
const val YT_DLP_NIGHTLY = 1

private const val INTERVAL_WEEK = 86_400_000L * 7

val paletteStyles = listOf(
    PaletteStyle.TonalSpot,
    PaletteStyle.Spritz,
    PaletteStyle.FruitSalad,
    PaletteStyle.Vibrant,
    PaletteStyle.Monochrome
)

private val StringPreferenceDefaults = mapOf(
    YOUTUBE_API_KEY to "",
    YOUTUBE_CHANNEL_HANDLE to "",
)

private val BooleanPreferenceDefaults = mapOf(
    CELLULAR_DOWNLOAD to false,
    AUTO_SYNC_ENABLED to false,
    AUTO_SYNC_REQUIRES_CHARGING to false,
    YT_DLP_AUTO_UPDATE to true,
    NOTIFICATION to true,
    SETUP_COMPLETED to false,
)

private val IntPreferenceDefaults = mapOf(
    AUTO_SYNC_INTERVAL_HOURS to AUTO_SYNC_DEFAULT_INTERVAL_HOURS,
    UPDATE_CHANNEL to STABLE,
    YT_DLP_UPDATE_CHANNEL to YT_DLP_NIGHTLY,
)

private val LongPreferenceDefaults = mapOf(
    YT_DLP_UPDATE_INTERVAL to INTERVAL_WEEK
)

private val kv: MMKV = MMKV.defaultMMKV()

object PreferenceUtil {
    fun String.getInt(default: Int = IntPreferenceDefaults.getOrElse(this) { 0 }): Int =
        kv.decodeInt(this, default)

    fun String.getString(default: String = StringPreferenceDefaults.getOrElse(this) { "" }): String =
        kv.decodeString(this) ?: default

    fun String.getBoolean(default: Boolean = BooleanPreferenceDefaults.getOrElse(this) { false }): Boolean =
        kv.decodeBool(this, default)

    fun String.getLong(default: Long = LongPreferenceDefaults.getOrElse(this) { 0L }) =
        kv.decodeLong(this, default)

    fun String.updateString(newString: String) = kv.encode(this, newString)

    fun String.updateInt(newValue: Int) = kv.encode(this, newValue)

    fun String.updateLong(newLong: Long) = kv.encode(this, newLong)

    fun String.updateBoolean(newValue: Boolean) = kv.encode(this, newValue)

    fun getValue(key: String): Boolean = key.getBoolean()
    fun encodeString(key: String, string: String) = key.updateString(string)
    fun containsKey(key: String) = kv.containsKey(key)

    fun isNetworkAvailableForDownload() =
        CELLULAR_DOWNLOAD.getBoolean() || !App.connectivityManager.isActiveNetworkMetered

    fun isAutoUpdateEnabled() = AUTO_UPDATE.getBoolean(!isFDroidBuild())

    data class AppSettings(
        val darkTheme: DarkThemePreference = DarkThemePreference(),
        val isDynamicColorEnabled: Boolean = false,
        val seedColor: Int = DEFAULT_SEED_COLOR,
        val paletteStyleIndex: Int = 0
    )

    private val mutableAppSettingsStateFlow = MutableStateFlow(
        AppSettings(
            DarkThemePreference(
                darkThemeValue = kv.decodeInt(
                    DARK_THEME_VALUE, DarkThemePreference.FOLLOW_SYSTEM
                ),
                isHighContrastModeEnabled = kv.decodeBool(HIGH_CONTRAST, false)
            ),
            isDynamicColorEnabled = kv.decodeBool(
                DYNAMIC_COLOR, DynamicColors.isDynamicColorAvailable()
            ),
            seedColor = kv.decodeInt(THEME_COLOR, DEFAULT_SEED_COLOR),
            paletteStyleIndex = kv.decodeInt(PALETTE_STYLE, 0)
        )
    )
    val AppSettingsStateFlow = mutableAppSettingsStateFlow.asStateFlow()
}

data class DarkThemePreference(
    val darkThemeValue: Int = FOLLOW_SYSTEM, val isHighContrastModeEnabled: Boolean = false
) {
    companion object {
        const val FOLLOW_SYSTEM = 1
        const val ON = 2
    }

    @Composable
    fun isDarkTheme(): Boolean {
        return if (darkThemeValue == FOLLOW_SYSTEM) isSystemInDarkTheme()
        else darkThemeValue == ON
    }
}

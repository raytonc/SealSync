package com.junkfood.seal.util

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.App.Companion.applicationScope
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ToastUtil {
    /** Call only from the main thread; use [showToast] from anywhere else. */
    fun makeToast(text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    fun makeToast(stringId: Int) = makeToast(context.getString(stringId))

    /** Hops to the main thread first, so background work can post toasts safely. */
    fun showToast(text: String) {
        applicationScope.launch(Dispatchers.Main) { makeToast(text) }
    }
}

private const val GIGA_BYTES = 1024f * 1024f * 1024f
private const val MEGA_BYTES = 1024f * 1024f

@Composable
fun Number?.toFileSizeText(): String {
    if (this == null) return stringResource(id = R.string.unknown)

    return this.toFloat().run {
        if (this > GIGA_BYTES) stringResource(R.string.filesize_gb).format(this / GIGA_BYTES)
        else stringResource(R.string.filesize_mb).format(this / MEGA_BYTES)
    }
}

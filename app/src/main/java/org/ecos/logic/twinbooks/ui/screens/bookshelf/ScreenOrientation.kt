package org.ecos.logic.twinbooks.ui.screens.bookshelf

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * The app is landscape-only; while this is in the composition the activity uses
 * [orientation] instead (an ActivityInfo.SCREEN_ORIENTATION_* value), restored afterwards.
 */
@Composable
fun OverrideScreenOrientation(orientation: Int) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, orientation) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = orientation
        onDispose {
            if (activity != null && previous != null) activity.requestedOrientation = previous
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

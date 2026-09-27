package org.ecos.logic.twinbooks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.ecos.logic.twinbooks.alignment.repository.ServerSettingsStore
import org.ecos.logic.twinbooks.ui.screens.bookshelf.BookshelfScreen
import org.ecos.logic.twinbooks.ui.screens.reader.ReaderScreen
import org.ecos.logic.twinbooks.ui.theme.TwinBooksTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // EPUB opened from another app (browser, file manager) waiting to be added to the bookshelf
    private var incomingBookUri by mutableStateOf<Uri?>(null)
    // twinbooks://connect link waiting for the user to confirm the server
    private var incomingServerLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            incomingBookUri = intent.epubUri()
            incomingServerLink = intent.serverLink()
        }
        setContent {
            TwinBooksTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        incomingBookUri = incomingBookUri,
                        onIncomingBookConsumed = { incomingBookUri = null },
                        incomingServerLink = incomingServerLink,
                        onIncomingServerLinkConsumed = { incomingServerLink = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.epubUri()?.let { incomingBookUri = it }
        intent.serverLink()?.let { incomingServerLink = it }
    }

    private fun Intent.epubUri(): Uri? =
        if (action == Intent.ACTION_VIEW && !isServerLink()) data else null

    private fun Intent.serverLink(): String? =
        if (action == Intent.ACTION_VIEW && isServerLink()) data?.toString() else null

    private fun Intent.isServerLink() =
        data?.scheme.equals(ServerSettingsStore.CONNECT_LINK_SCHEME, ignoreCase = true)
}

private const val PREFS_NAME = "twinbooks_prefs"
// Session open in the reader when the app was closed (-1 = bookshelf), so the next
// launch goes straight back to the book instead of the bookshelf
private const val KEY_OPEN_SESSION_ID = "open_session_id"
private const val NO_SESSION = -1L

@Composable
fun MainScreen(
    incomingBookUri: Uri? = null,
    onIncomingBookConsumed: () -> Unit = {},
    incomingServerLink: String? = null,
    onIncomingServerLinkConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var openSessionId by rememberSaveable {
        mutableLongStateOf(prefs.getLong(KEY_OPEN_SESSION_ID, NO_SESSION))
    }

    fun openSession(id: Long) {
        openSessionId = id
        prefs.edit { if (id == NO_SESSION) remove(KEY_OPEN_SESSION_ID) else putLong(KEY_OPEN_SESSION_ID, id) }
    }

    // A book or server link opened from another app always lands on the bookshelf (which asks
    // how to read it / whether to use that server)
    val hasIncoming = incomingBookUri != null || incomingServerLink != null
    LaunchedEffect(hasIncoming) {
        if (hasIncoming && openSessionId != NO_SESSION) openSession(NO_SESSION)
    }

    if (openSessionId != NO_SESSION && !hasIncoming) {
        ReaderScreen(
            sessionId = openSessionId,
            onBackToBookshelf = { openSession(NO_SESSION) }
        )
    } else {
        BookshelfScreen(
            onSessionSelected = { session -> openSession(session.id) },
            incomingBookUri = incomingBookUri,
            onIncomingBookConsumed = onIncomingBookConsumed,
            incomingServerLink = incomingServerLink,
            onIncomingServerLinkConsumed = onIncomingServerLinkConsumed,
            onCreateNewPair = {
                // The BookshelfScreen handles new pair creation internally
            }
        )
    }
}

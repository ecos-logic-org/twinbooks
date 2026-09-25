package org.ecos.logic.twinbooks

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.ecos.logic.twinbooks.ui.screens.bookshelf.BookshelfScreen
import org.ecos.logic.twinbooks.ui.screens.reader.ReaderScreen
import org.ecos.logic.twinbooks.ui.theme.TwinBooksTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwinBooksTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

private const val PREFS_NAME = "twinbooks_prefs"
// Session open in the reader when the app was closed (-1 = bookshelf), so the next
// launch goes straight back to the book instead of the bookshelf
private const val KEY_OPEN_SESSION_ID = "open_session_id"
private const val NO_SESSION = -1L

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var openSessionId by rememberSaveable {
        mutableLongStateOf(prefs.getLong(KEY_OPEN_SESSION_ID, NO_SESSION))
    }

    fun openSession(id: Long) {
        openSessionId = id
        prefs.edit { if (id == NO_SESSION) remove(KEY_OPEN_SESSION_ID) else putLong(KEY_OPEN_SESSION_ID, id) }
    }

    if (openSessionId != NO_SESSION) {
        ReaderScreen(
            sessionId = openSessionId,
            onBackToBookshelf = { openSession(NO_SESSION) }
        )
    } else {
        BookshelfScreen(
            onSessionSelected = { session -> openSession(session.id) },
            onCreateNewPair = {
                // The BookshelfScreen handles new pair creation internally
            }
        )
    }
}

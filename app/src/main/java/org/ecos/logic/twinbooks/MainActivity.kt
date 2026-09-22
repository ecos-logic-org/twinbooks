package org.ecos.logic.twinbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.ecos.logic.twinbooks.ui.screens.bookshelf.BookshelfScreen
import org.ecos.logic.twinbooks.ui.screens.reader.ReaderScreen
import org.ecos.logic.twinbooks.ui.theme.TwinBooksTheme
import org.ecos.logic.twinbooks.domain.model.ReadingSession

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

@Composable
fun MainScreen() {
    var currentSession by remember { mutableStateOf<ReadingSession?>(null) }
    var showReader by remember { mutableStateOf(false) }

    if (showReader && currentSession != null) {
        ReaderScreen(sessionId = currentSession!!.id)
    } else {
        BookshelfScreen(
            onSessionSelected = { session ->
                currentSession = session
                showReader = true
            },
            onCreateNewPair = {
                // The BookshelfScreen handles new pair creation internally
            }
        )
    }
}

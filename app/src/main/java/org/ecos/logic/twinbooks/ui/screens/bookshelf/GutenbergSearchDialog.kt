package org.ecos.logic.twinbooks.ui.screens.bookshelf

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ecos.logic.twinbooks.gutenberg.GutenbergBook
import org.ecos.logic.twinbooks.gutenberg.GutenbergLanguage
import org.ecos.logic.twinbooks.ui.viewmodel.DownloadedBook
import org.ecos.logic.twinbooks.ui.viewmodel.GutenbergPreset
import org.ecos.logic.twinbooks.ui.viewmodel.GutenbergUiState
import org.ecos.logic.twinbooks.ui.viewmodel.GutenbergViewModel

/**
 * Full-screen search of Project Gutenberg. A finished download is handed to [onDownloaded].
 * [preset] opens it already filled in (the other half of a pair).
 */
@Composable
fun GutenbergSearchDialog(
    onDownloaded: (DownloadedBook) -> Unit,
    onDismiss: () -> Unit,
    preset: GutenbergPreset? = null,
    viewModel: GutenbergViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(preset) {
        preset?.let(viewModel::applyPreset)
    }

    // The app is landscape-only, but browsing a catalog reads better in portrait: let the
    // device rotate (honouring the user's rotation lock) while the search is open
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose {
            if (activity != null && previous != null) activity.requestedOrientation = previous
        }
    }

    LaunchedEffect(Unit) {
        viewModel.downloaded.collect { onDownloaded(it) }
    }

    Dialog(
        onDismissRequest = {
            viewModel.cancelDownload()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Project Gutenberg", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Más de 70.000 libros de dominio público, gratis",
                        color = Color(0xFF9E9E9E),
                        fontSize = 13.sp
                    )
                }
                IconButton(onClick = {
                    viewModel.cancelDownload()
                    onDismiss()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }
            }

            // Search bar, then the language on its own row (clear in portrait and landscape)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Título o autor") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        viewModel.search()
                    }),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.search()
                    },
                    enabled = state.query.isNotBlank()
                ) {
                    Text("Buscar")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Idioma del libro:", color = Color(0xFFB0B0B0), fontSize = 14.sp)
                LanguageChip("Inglés", GutenbergLanguage.ENGLISH, state.language, viewModel::setLanguage)
                LanguageChip("Castellano", GutenbergLanguage.SPANISH, state.language, viewModel::setLanguage)
            }

            state.error?.let { Text(it, color = Color(0xFFEF5350), fontSize = 14.sp) }

            Results(state, viewModel)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun LanguageChip(
    label: String,
    language: GutenbergLanguage,
    selected: GutenbergLanguage,
    onSelect: (GutenbergLanguage) -> Unit
) {
    FilterChip(
        selected = language == selected,
        onClick = { onSelect(language) },
        label = { Text(label) }
    )
}

@Composable
private fun Results(state: GutenbergUiState, viewModel: GutenbergViewModel) {
    when {
        state.books.isEmpty() && state.isLoading -> CenteredBox { CircularProgressIndicator() }
        state.books.isEmpty() && state.searched && state.error == null -> CenteredBox {
            Text("No hay resultados en este idioma.", color = Color(0xFF9E9E9E))
        }
        state.books.isEmpty() -> CenteredBox {
            Text(
                "Busca un libro por título o autor. Al descargarlo podrás leerlo solo o como parte de un par.",
                color = Color(0xFF9E9E9E)
            )
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.books, key = { it.id }) { book ->
                BookCard(
                    book = book,
                    downloading = state.downloadingId == book.id,
                    progress = state.downloadProgress,
                    enabled = state.downloadingId == null,
                    loadCover = viewModel::cover,
                    onDownload = { viewModel.download(book) }
                )
            }
            if (state.nextStartIndex != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            TextButton(onClick = viewModel::loadMore) { Text("Cargar más") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
private fun BookCard(
    book: GutenbergBook,
    downloading: Boolean,
    progress: Float,
    enabled: Boolean,
    loadCover: suspend (GutenbergBook) -> ByteArray?,
    onDownload: () -> Unit
) {
    val cover by produceState<ImageBitmap?>(initialValue = null, book.id) {
        value = loadCover(book)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Column(
        modifier = Modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onDownload)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            val image = cover
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Color(0xFF616161), modifier = Modifier.size(40.dp))
            }
        }
        Text(
            book.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(book.author, color = Color(0xFF9E9E9E), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (downloading) {
            if (progress >= 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF80CBC4), modifier = Modifier.size(16.dp))
                Text("Descargar", color = Color(0xFF80CBC4), fontSize = 12.sp)
            }
        }
    }
}

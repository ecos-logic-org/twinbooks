package org.ecos.logic.twinbooks.ui.screens.bookshelf

import org.ecos.logic.twinbooks.ui.screens.reader.serverStatusLook
import org.ecos.logic.twinbooks.alignment.model.ServerStatus
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.hilt.navigation.compose.hiltViewModel
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.ui.viewmodel.BookshelfViewModel
import org.ecos.logic.twinbooks.ui.viewmodel.SessionWithCover


/**
 * Decodes a base64 data URI (format: data:mimeType;base64,<data>) to an ImageBitmap.
 * Returns null if decoding fails.
 */
@Composable
private fun rememberCoverBitmap(dataUri: String?): ImageBitmap? {
    val context = LocalContext.current
    return remember(dataUri) {
        dataUri?.let { uri ->
            // Extract base64 part from data URI: "data:mimeType;base64,<base64data>"
            val base64Part = uri.substringAfter("base64,")
                ?: uri.substringAfter(",") // fallback
                ?: return@remember null
            
            try {
                val bytes = Base64.decode(base64Part, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap?.let { androidBitmap ->
                    androidBitmap.asImageBitmap()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}

@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel = hiltViewModel(),
    onSessionSelected: (ReadingSession) -> Unit,
    onCreateNewPair: () -> Unit,
    incomingBookUri: Uri? = null,
    onIncomingBookConsumed: () -> Unit = {}
) {
    val sessions by viewModel.sessions.collectAsState()
    val incomingBook by viewModel.incomingBook.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()

    LaunchedEffect(incomingBookUri) {
        incomingBookUri?.let {
            viewModel.importIncomingBook(it)
            onIncomingBookConsumed()
        }
    }
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<ReadingSession?>(null) }

    var showNewPairDialog by remember { mutableStateOf(false) }
    var leftBookSelected by remember { mutableStateOf(false) }
    var rightBookSelected by remember { mutableStateOf(false) }

    // Launchers for picking EPUB files
    val leftBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setLeftBookUri(it.toString())
        }
    }

    val rightBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setRightBookUri(it.toString())
        }
    }

    val singleBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.createSingleBook(it.toString())
            showNewPairDialog = false
            leftBookSelected = false
            rightBookSelected = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "📚 Estantería",
                    fontSize = 28.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                ServerStatusChip(status = serverStatus, onClick = { viewModel.checkServer() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pair EN/ES button
                Button(onClick = { showNewPairDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Nuevo par EN/ES", tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Par EN/ES", fontSize = 13.sp)
                }
                // Single book button (auto-translation)
                Button(
                    onClick = { singleBookLauncher.launch(arrayOf("application/epub+zip")) },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00695C)
                    )
                ) {
                    Icon(Icons.Default.Translate, contentDescription = "Libro único", tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Libro único", fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sessions list
        if (sessions.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showNewPairDialog = true }
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Empty shelf",
                        tint = Color(0xFF444444),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No hay pares de libros guardados",
                        color = Color(0xFF888888),
                        fontSize = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Toca el botón + para añadir tu primer par",
                        color = Color(0xFF666666),
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            // Grid of up to 3 columns: 3 in landscape, 2 in portrait (cards need ~380dp)
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = (maxWidth / 380.dp).toInt().coerceIn(1, 3)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.session.id }) { sessionWithCover ->
                        SessionCard(
                            sessionWithCover = sessionWithCover,
                            onClick = { onSessionSelected(sessionWithCover.session) },
                            onDelete = {
                                sessionToDelete = sessionWithCover.session
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
        }
    } // Column
} // Box

    // New pair dialog
    if (showNewPairDialog) {
        NewPairDialog(
            onDismiss = { showNewPairDialog = false },
            onLeftBookClick = {
                leftBookLauncher.launch(arrayOf("application/epub+zip"))
                leftBookSelected = true
            },
            onRightBookClick = {
                rightBookLauncher.launch(arrayOf("application/epub+zip"))
                rightBookSelected = true
            },
            leftBookSelected = leftBookSelected,
            rightBookSelected = rightBookSelected,
            onSingleBookClick = {
                singleBookLauncher.launch(arrayOf("application/epub+zip"))
            },
            onConfirm = {
                if (leftBookSelected && rightBookSelected) {
                    viewModel.createNewPair()
                    showNewPairDialog = false
                    leftBookSelected = false
                    rightBookSelected = false
                }
            }
        )
    }

    // EPUB opened from another app: how is it going to be read?
    incomingBook?.let { book ->
        IncomingBookDialog(
            book = book,
            onOpenExisting = { session ->
                viewModel.dismissIncomingBook()
                onSessionSelected(session)
            },
            onSingleBook = {
                viewModel.createSingleBook(book.uri)
                viewModel.dismissIncomingBook()
            },
            onPairLeft = {
                viewModel.setLeftBookUri(book.uri)
                leftBookSelected = true
                showNewPairDialog = true
                viewModel.dismissIncomingBook()
            },
            onPairRight = {
                viewModel.setRightBookUri(book.uri)
                rightBookSelected = true
                showNewPairDialog = true
                viewModel.dismissIncomingBook()
            },
            onDismiss = { viewModel.dismissIncomingBook() }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        sessionToDelete?.let { session ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Eliminar par de libros") },
                text = {
                    Text(
                        "¿Eliminar \"${session.leftTitle}\" / \"${session.rightTitle ?: "Sin libro derecho"}\"?\n\nSe perderá todo el progreso de lectura."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteSession(session.id)
                        }
                    ) {
                        Text("Eliminar", color = Color(0xFFFF5252))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showDeleteDialog = false }
                    ) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}

@Composable
private fun SessionCard(
    sessionWithCover: SessionWithCover,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val session = sessionWithCover.session
    val leftCoverImage = sessionWithCover.leftCoverImage
    val coverBitmap = rememberCoverBitmap(leftCoverImage)
    val leftProgress = (session.leftProgressPercent).toInt()
    val rightProgress = (session.rightProgressPercent).toInt()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Book cover and titles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover image
                if (coverBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(48.dp, 72.dp)
                            .background(Color(0xFF2A2A2A))
                            .border(1.dp, Color(0xFF444444))
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = coverBitmap,
                            contentDescription = session.leftTitle,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // Placeholder for missing cover
                    Box(
                        modifier = Modifier
                            .size(48.dp, 72.dp)
                            .background(Color(0xFF2A2A2A))
                            .border(1.dp, Color(0xFF444444)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = "Book cover",
                            tint = Color(0xFF555555),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Book titles
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.leftTitle,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    (session.rightTitle?.takeIf { it.isNotEmpty() })?.let { rightTitle ->
                        Text(
                            text = rightTitle,
                            color = Color(0xFFB0B0B0),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                // Delete button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF333333), RoundedCornerShape(8.dp))
                        .clickable { onDelete() }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left book progress
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Izquierda: ${session.leftChapterIndex + 1} / ${leftProgress}%",
                        color = Color(0xFF90CAF9),
                        fontSize = 12.sp
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        progress = session.leftProgressPercent / 100f,
                        color = Color(0xFF90CAF9),
                        trackColor = Color(0xFF333333)
                    )
                }

                // Right book progress (if exists)
                if (session.rightBookUri != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Derecha: ${session.rightChapterIndex + 1} / ${rightProgress}%",
                            color = Color(0xFFA5D6A7),
                            fontSize = 12.sp
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            progress = session.rightProgressPercent / 100f,
                            color = Color(0xFFA5D6A7),
                            trackColor = Color(0xFF333333)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPairDialog(
    onDismiss: () -> Unit,
    onLeftBookClick: () -> Unit,
    onRightBookClick: () -> Unit,
    leftBookSelected: Boolean,
    rightBookSelected: Boolean,
    onSingleBookClick: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo par de libros") },
        text = {
            Column(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Selecciona un libro para cada lado:",
                    color = Color(0xFFB0B0B0),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Left book selector
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (leftBookSelected) Color(0xFF1B5E20) else Color(0xFF2A2A2A),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (leftBookSelected) Color(0xFF4CAF50) else Color(0xFF555555),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                        .clickable { onLeftBookClick() },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = "Left book",
                            tint = if (leftBookSelected) Color(0xFF4CAF50) else Color(0xFFB0B0B0),
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Libro de la izquierda (Inglés)",
                                color = if (leftBookSelected) Color(0xFF4CAF50) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (leftBookSelected) "✓ Seleccionado" else "Toca para seleccionar archivo EPUB",
                                color = if (leftBookSelected) Color(0xFF81C784) else Color(0xFF888888),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Right book selector
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (rightBookSelected) Color(0xFF1B5E20) else Color(0xFF2A2A2A),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (rightBookSelected) Color(0xFF4CAF50) else Color(0xFF555555),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp)
                        .clickable { onRightBookClick() },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = "Right book",
                            tint = if (rightBookSelected) Color(0xFF4CAF50) else Color(0xFFB0B0B0),
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Libro de la derecha (Castellano)",
                                color = if (rightBookSelected) Color(0xFF4CAF50) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (rightBookSelected) "✓ Seleccionado" else "Toca para seleccionar archivo EPUB (opcional)",
                                color = if (rightBookSelected) Color(0xFF81C784) else Color(0xFF888888),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "— o bien —",
                    color = Color(0xFF666666),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Single-book selector: creates the session in single-book mode
                // (full screen + on-device automatic translation, no second book)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D2B27), RoundedCornerShape(12.dp))
                        .border(1.5.dp, Color(0xFF26A69A), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                        .clickable { onSingleBookClick() },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF004D40), RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Modo libro único",
                                tint = Color(0xFF80CBC4),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Libro único (Inglés con traducción automática)",
                                color = Color(0xFF80CBC4),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Un solo EPUB a pantalla completa. Traducción al castellano por párrafos con ML Kit on-device. TTS bilingüe frase a frase (EN ↔ ES).",
                                color = Color(0xFFB2DFDB),
                                fontSize = 13.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.NavigateNext,
                            contentDescription = "",
                            tint = Color(0xFF4DB6AC),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = onConfirm,
                enabled = leftBookSelected
            ) {
                Text(
                    text = "Crear",
                    color = if (leftBookSelected) Color(0xFF4CAF50) else Color(0xFF666666)
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

private fun persistUriPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
private fun IncomingBookDialog(
    book: BookshelfViewModel.IncomingBook,
    onOpenExisting: (ReadingSession) -> Unit,
    onSingleBook: () -> Unit,
    onPairLeft: () -> Unit,
    onPairRight: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val existing = book.existingSession
                if (existing != null) {
                    Text("Este libro ya está en tu estantería.", color = Color(0xFFB0B0B0))
                    IncomingBookOption("Abrirlo", "Continuar donde lo dejaste", Icons.Default.MenuBook) {
                        onOpenExisting(existing)
                    }
                } else {
                    Text("¿Cómo quieres leerlo?", color = Color(0xFFB0B0B0))
                    IncomingBookOption(
                        "Libro único",
                        "A pantalla completa, con traducción automática al castellano",
                        Icons.Default.Translate,
                        onSingleBook
                    )
                    IncomingBookOption(
                        "Libro en inglés de un par",
                        "Panel izquierdo; después eliges el libro en castellano",
                        Icons.Default.Add,
                        onPairLeft
                    )
                    IncomingBookOption(
                        "Libro en castellano de un par",
                        "Panel derecho; después eliges el libro en inglés",
                        Icons.Default.Add,
                        onPairRight
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun IncomingBookOption(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF80CBC4), modifier = Modifier.size(24.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 13.sp)
        }
    }
}

/** Alignment server availability; tap to check again. */
@Composable
private fun ServerStatusChip(status: ServerStatus, onClick: () -> Unit) {
    val (icon, tint, label) = serverStatusLook(status)
    Row(
        modifier = Modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text = label, color = tint, fontSize = 12.sp)
    }
}

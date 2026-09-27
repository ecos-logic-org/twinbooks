package org.ecos.logic.twinbooks.ui.screens.bookshelf

import org.ecos.logic.twinbooks.ui.screens.reader.serverStatusLook
import org.ecos.logic.twinbooks.alignment.model.ServerStatus
import org.ecos.logic.twinbooks.alignment.repository.ServerConfig
import org.ecos.logic.twinbooks.alignment.repository.ServerSettingsStore
import org.ecos.logic.twinbooks.gutenberg.GutenbergLanguage
import org.ecos.logic.twinbooks.ui.viewmodel.GutenbergPreset
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
internal fun rememberCoverBitmap(dataUri: String?): ImageBitmap? {
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
    onIncomingBookConsumed: () -> Unit = {},
    incomingServerLink: String? = null,
    onIncomingServerLinkConsumed: () -> Unit = {}
) {
    val sessions by viewModel.sessions.collectAsState()
    val incomingBook by viewModel.incomingBook.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val serverConfig by viewModel.serverConfig.collectAsState()
    val serverTest by viewModel.serverTest.collectAsState()
    var showServerSettings by remember { mutableStateOf(false) }
    var showGutenberg by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    // Gutenberg opened from the new-pair dialog: the side the download goes to (null = free search)
    var gutenbergTarget by remember { mutableStateOf<PairSide?>(null) }
    var gutenbergPreset by remember { mutableStateOf<GutenbergPreset?>(null) }
    // Author of the Gutenberg book that started a pair: seeds the search for the other half
    var pairAuthorHint by remember { mutableStateOf("") }
    // Server from a twinbooks://connect link, waiting for confirmation
    var linkedServer by remember { mutableStateOf<ServerConfig?>(null) }
    var showInvalidLink by remember { mutableStateOf(false) }

    LaunchedEffect(incomingServerLink) {
        incomingServerLink?.let { link ->
            val config = ServerSettingsStore.parseConnectLink(link)
            if (config == null) {
                showInvalidLink = true
            } else {
                linkedServer = config
                viewModel.testServer(config)
            }
            onIncomingServerLinkConsumed()
        }
    }

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
        // Header: add, discover, settings (the server status is just a dot on the gear)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Button(onClick = { showAddMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Añadir", fontSize = 13.sp)
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Par de libros EN/ES") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showNewPairDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Libro único (inglés con traducción)") },
                        leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            singleBookLauncher.launch(arrayOf("application/epub+zip"))
                        }
                    )
                }
            }
            // Search and download public-domain books
            Button(
                onClick = {
                    gutenbergTarget = null
                    gutenbergPreset = null
                    showGutenberg = true
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF37474F)
                )
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = "Buscar en Project Gutenberg", tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Gutenberg", fontSize = 13.sp)
            }
            // Optional TwinBooks server (alignment + translation)
            Box {
                IconButton(onClick = {
                    viewModel.checkServer()
                    showServerSettings = true
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Servidor TwinBooks", tint = Color(0xFFB0B0B0))
                }
                if (serverStatus != ServerStatus.DISABLED) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .size(10.dp)
                            .background(serverStatusLook(serverStatus).second, CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // The books, standing on shelves
        if (sessions.isEmpty()) {
            EmptyShelf(onClick = { showAddMenu = true })
        } else {
            BookShelf(
                sessions = sessions,
                onOpen = onSessionSelected,
                onDelete = { session ->
                    sessionToDelete = session
                    showDeleteDialog = true
                }
            )
        }
    } // Column
} // Box

    // New pair dialog
    linkedServer?.let { config ->
        ConnectServerDialog(
            config = config,
            testResult = serverTest,
            onConnect = {
                viewModel.saveServer(config)
                linkedServer = null
            },
            onDismiss = {
                viewModel.clearServerTest()
                linkedServer = null
            }
        )
    }

    if (showInvalidLink) {
        AlertDialog(
            onDismissRequest = { showInvalidLink = false },
            title = { Text("Enlace no válido") },
            text = { Text("Este enlace de conexión no contiene una dirección de servidor válida (tiene que usar HTTPS).") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showInvalidLink = false }) {
                    Text("Aceptar")
                }
            }
        )
    }

    if (showGutenberg) {
        GutenbergSearchDialog(
            preset = gutenbergPreset,
            onDownloaded = { downloaded ->
                showGutenberg = false
                when (gutenbergTarget) {
                    PairSide.LEFT -> {
                        viewModel.setLeftBookUri(downloaded.uri)
                        leftBookSelected = true
                    }
                    PairSide.RIGHT -> {
                        viewModel.setRightBookUri(downloaded.uri)
                        rightBookSelected = true
                    }
                    null -> {
                        pairAuthorHint = downloaded.author
                        viewModel.offerLocalBook(downloaded.uri, downloaded.language)
                    }
                }
                gutenbergTarget = null
            },
            onDismiss = {
                showGutenberg = false
                gutenbergTarget = null
            }
        )
    }

    if (showServerSettings) {
        ServerSettingsDialog(
            current = serverConfig,
            status = serverStatus,
            testResult = serverTest,
            onTest = viewModel::testServer,
            onEdited = viewModel::clearServerTest,
            onSave = {
                viewModel.saveServer(it)
                showServerSettings = false
            },
            onDismiss = {
                viewModel.clearServerTest()
                showServerSettings = false
            }
        )
    }

    if (showNewPairDialog) {
        NewPairDialog(
            onDismiss = {
                showNewPairDialog = false
                pairAuthorHint = ""
            },
            onLeftGutenberg = {
                gutenbergTarget = PairSide.LEFT
                gutenbergPreset = GutenbergPreset(pairAuthorHint, GutenbergLanguage.ENGLISH)
                showGutenberg = true
            },
            onRightGutenberg = {
                gutenbergTarget = PairSide.RIGHT
                gutenbergPreset = GutenbergPreset(pairAuthorHint, GutenbergLanguage.SPANISH)
                showGutenberg = true
            },
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
                    pairAuthorHint = ""
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
private fun NewPairDialog(
    onDismiss: () -> Unit,
    onLeftGutenberg: () -> Unit,
    onRightGutenberg: () -> Unit,
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

                if (!leftBookSelected) GutenbergSideButton("Buscar en Gutenberg (inglés)", onLeftGutenberg)

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

                if (!rightBookSelected) GutenbergSideButton("Buscar en Gutenberg (castellano)", onRightGutenberg)

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
                                text = "Un solo EPUB a pantalla completa. Traducción al castellano por párrafos, también sin conexión. TTS bilingüe frase a frase (EN ↔ ES).",
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

/** Side of a pair a Gutenberg download goes to */
private enum class PairSide { LEFT, RIGHT }

@Composable
private fun GutenbergSideButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 13.sp)
    }
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
                    // Known language (Gutenberg): the translator is EN→ES, so a Spanish book can
                    // only be the right half of a pair, and an English one never the right half
                    val english = book.language != GutenbergLanguage.SPANISH
                    val spanish = book.language != GutenbergLanguage.ENGLISH
                    if (english) {
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
                    }
                    if (spanish) {
                        IncomingBookOption(
                            "Libro en castellano de un par",
                            "Panel derecho; después eliges el libro en inglés",
                            Icons.Default.Add,
                            onPairRight
                        )
                    }
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

package org.ecos.logic.twinbooks.ui.screens.bookshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.ui.viewmodel.BookshelfViewModel

@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel = hiltViewModel(),
    onSessionSelected: (ReadingSession) -> Unit,
    onCreateNewPair: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<ReadingSession?>(null) }

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

    var showNewPairDialog by remember { mutableStateOf(false) }
    var leftBookSelected by remember { mutableStateOf(false) }
    var rightBookSelected by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📚 Estantería",
                fontSize = 28.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = { showNewPairDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New pair", tint = Color.White)
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
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                sessions.forEach { session ->
                    SessionCard(
                        session = session,
                        onClick = { onSessionSelected(session) },
                        onDelete = {
                            sessionToDelete = session
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

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
    session: ReadingSession,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
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
            // Book titles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = session.leftTitle,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
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
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo par de libros") },
        text = {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
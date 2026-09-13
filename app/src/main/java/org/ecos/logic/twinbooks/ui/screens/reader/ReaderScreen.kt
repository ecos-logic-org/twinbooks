package org.ecos.logic.twinbooks.ui.screens.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.ecos.logic.twinbooks.ui.viewmodel.ReaderViewModel

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var rightSentenceIndex by remember { mutableIntStateOf(-1) }
    var rightSentenceCount by remember { mutableIntStateOf(0) }
    var rightOffset by remember { mutableIntStateOf(0) }
    var scrollToRightIndex by remember { mutableStateOf<Int?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.leftPosition.paragraphText) {
        viewModel.updateTtsSentenceIndex(-1)
        rightSentenceIndex = -1
        rightOffset = 0
    }

    LaunchedEffect(scrollToRightIndex) {
        if (scrollToRightIndex != null) {
            scrollToRightIndex = null
        }
    }

    val leftBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            persistUriPermission(context, it)
            viewModel.loadLeftBook(it)
        }
    }

    val rightBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            persistUriPermission(context, it)
            viewModel.loadRightBook(it)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Left panel
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .background(Color.Black)
            ) {
                if (state.leftBook != null) {
                    BookPanel(
                        book = state.leftBook!!,
                        position = state.leftPosition,
                        fontSize = state.fontSize,
                        currentSentenceIndex = state.leftSentenceIndex,
                        onSentenceCountChanged = { count ->
                            viewModel.updateTtsSentenceCount(count)
                        },
                        onSentenceTextFound = { text ->
                            viewModel.onTtsSentenceTextReceived(text)
                        },
                        onPrevSentence = {
                            rightOffset = rightSentenceIndex - state.leftSentenceIndex
                            val newIndex = (state.leftSentenceIndex - 1).coerceAtLeast(0)
                            viewModel.updateTtsSentenceIndex(newIndex)
                            rightSentenceIndex = (newIndex + rightOffset).coerceAtLeast(0)
                        },
                        onNextSentence = {
                            rightOffset = rightSentenceIndex - state.leftSentenceIndex
                            val newIndex = (state.leftSentenceIndex + 1).coerceAtMost(
                                (state.leftSentenceCount - 1).coerceAtLeast(0)
                            )
                            viewModel.updateTtsSentenceIndex(newIndex)
                            rightSentenceIndex = (newIndex + rightOffset)
                                .coerceAtMost((rightSentenceCount - 1).coerceAtLeast(0))
                        },
                        onPositionChanged = { chapter, offset, paragraphText ->
                            viewModel.updateLeftPosition(chapter, offset, paragraphText)
                        },
                        onChapterSelected = { chapter ->
                            viewModel.navigateToChapter(true, chapter)
                        },
                        onPrevChapter = {
                            val newChapter = (state.leftPosition.chapterIndex - 1).coerceAtLeast(0)
                            viewModel.navigateToChapter(true, newChapter)
                            viewModel.updateTtsSentenceIndex(0)
                        },
                        onNextChapter = {
                            val newChapter = (state.leftPosition.chapterIndex + 1)
                                .coerceAtMost((state.leftBook?.totalChapters ?: 1) - 1)
                            viewModel.navigateToChapter(true, newChapter)
                            viewModel.updateTtsSentenceIndex(0)
                        },
                        isLeft = true,
                        isSynchronized = state.isSynchronized,
                        onParagraphDoubleClicked = { index ->
                            viewModel.onLeftParagraphDoubleClicked(index)
                        },
                        onParagraphIndexChanged = { index ->
                            viewModel.updateLeftParagraphIndex(index)
                            if (state.isSynchronized && index >= 0) {
                                scrollToRightIndex = index + state.syncOffset
                            }
                        },
                        ttsRefreshTrigger = viewModel.ttsRefreshTrigger,
                        ttsScrollToNextParagraphTrigger = state.ttsScrollToNextParagraphTrigger,
                        onReachedEndOfChapter = {
                            viewModel.advanceTtsToNextChapter()
                        }
                    )
                } else {
                    EmptyBookPlaceholder(
                        message = "Select a book",
                        onClick = {
                            leftBookLauncher.launch(arrayOf("application/epub+zip"))
                        }
                    )
                }
            }

            // Divider with font size controls and reset button
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(44.dp)
                    .background(Color(0xFF1A1A1A)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (state.leftBook != null || state.rightBook != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFFB71C1C), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFFD32F2F), RoundedCornerShape(6.dp))
                            .clickable { showResetDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Change Books",
                            tint = Color(0xFFFFFFFF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (state.isSynchronized) Color(0xFF2E7D32) else Color(0xFF2A2A2A),
                                RoundedCornerShape(6.dp)
                            )
                            .border(1.dp, Color(0xFF555555), RoundedCornerShape(6.dp))
                            .clickable { viewModel.toggleSync() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "Toggle synchronization",
                            tint = if (state.isSynchronized) Color(0xFF4CAF50) else Color(0xFFB0B0B0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF555555), RoundedCornerShape(6.dp))
                            .clickable { viewModel.increaseFontSize() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Increase font size",
                            tint = Color(0xFFB0B0B0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "%.0f".format(state.fontSize),
                        color = Color(0xFFB0B0B0),
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF555555), RoundedCornerShape(6.dp))
                            .clickable { viewModel.decreaseFontSize() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Decrease font size",
                            tint = Color(0xFFB0B0B0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (state.isTtsPlaying) Color(0xFF1B5E20) else Color(0xFF2A2A2A),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (state.isTtsPlaying) Color(0xFF4CAF50) else Color(0xFF555555),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.toggleTts() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isTtsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isTtsPlaying) "Pause TTS" else "Play TTS",
                            tint = if (state.isTtsPlaying) Color(0xFF4CAF50) else Color(0xFFB0B0B0),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF555555), RoundedCornerShape(6.dp))
                            .clickable { viewModel.cycleTtsTimeLimit() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.ttsTimeLimitMinutes > 0) "${state.ttsTimeLimitMinutes}" else "∞",
                            color = if (state.ttsTimeLimitMinutes > 0) Color(0xFF4FC3F7) else Color(0xFFB0B0B0),
                            fontSize = 11.sp
                        )
                    }
                    if (state.ttsRemainingSeconds > 0) {
                        val minutes = (state.ttsRemainingSeconds / 60).toInt()
                        val seconds = (state.ttsRemainingSeconds % 60).toInt()
                        Text(
                            text = "%d:%02d".format(minutes, seconds),
                            color = Color(0xFF4FC3F7),
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // Right panel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black)
            ) {
                if (state.rightBook != null) {
                    BookPanel(
                        book = state.rightBook!!,
                        position = state.rightPosition,
                        fontSize = state.fontSize,
                        currentSentenceIndex = rightSentenceIndex,
                        onSentenceCountChanged = { count ->
                            rightSentenceCount = count
                            if (count > 0 && rightSentenceIndex == -1) {
                                rightSentenceIndex = 0
                            }
                        },
                        onPrevSentence = {
                            rightSentenceIndex = (rightSentenceIndex - 1).coerceAtLeast(0)
                        },
                        onNextSentence = {
                            rightSentenceIndex = (rightSentenceIndex + 1).coerceAtMost(
                                (rightSentenceCount - 1).coerceAtLeast(0)
                            )
                        },
                        onPositionChanged = { chapter, offset, paragraphText ->
                            viewModel.updateRightPosition(chapter, offset, paragraphText)
                        },
                        onChapterSelected = { chapter ->
                            viewModel.navigateToChapter(false, chapter)
                        },
                        onPrevChapter = {
                            val newChapter = (state.rightPosition.chapterIndex - 1).coerceAtLeast(0)
                            viewModel.navigateToChapter(false, newChapter)
                            rightSentenceIndex = 0
                        },
                        onNextChapter = {
                            val newChapter = (state.rightPosition.chapterIndex + 1)
                                .coerceAtMost((state.rightBook?.totalChapters ?: 1) - 1)
                            viewModel.navigateToChapter(false, newChapter)
                            rightSentenceIndex = 0
                        },
                        isLeft = false,
                        isSynchronized = state.isSynchronized,
                        onParagraphDoubleClicked = { index ->
                            viewModel.onRightParagraphDoubleClicked(index)
                        },
                        onParagraphIndexChanged = { index ->
                            viewModel.updateRightParagraphIndex(index)
                        },
                        scrollToParagraphIndex = scrollToRightIndex
                    )
                } else {
                    EmptyBookPlaceholder(
                        message = "Tap here to select the second book",
                        onClick = {
                            rightBookLauncher.launch(arrayOf("application/epub+zip"))
                        }
                    )
                }
            }
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset books") },
            text = {
                Text(
                    "This will close both books and reset all reading progress. " +
                            "This action cannot be undone.\n\nDo you want to continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    viewModel.resetBooks()
                }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EmptyBookPlaceholder(
    message: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onClick() }
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color(0xFF888888),
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
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

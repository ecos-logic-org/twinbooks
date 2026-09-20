package org.ecos.logic.twinbooks.ui.screens.reader

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
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.ecos.logic.twinbooks.ui.viewmodel.ReaderViewModel
import org.ecos.logic.twinbooks.domain.model.TtsBilingualMode

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
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                        isLeft = true,
                        isSynchronized = state.isSynchronized,
                        onParagraphDoubleClicked = { index ->
                            viewModel.onLeftParagraphDoubleClicked(index)
                        },
                        onParagraphIndexChanged = { index ->
                            viewModel.updateLeftParagraphIndex(index)
                            if (state.isSynchronized && index >= 0) {
                                // Translation-based sync: find best matching paragraph
                                viewModel.findAndSyncBestMatch(
                                    leftParagraphIndex = index,
                                    onBestMatchFound = { bestIndex ->
                                        scrollToRightIndex = bestIndex
                                    }
                                )
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

            // Divider with TTS play/pause and bottom bar toggle
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(44.dp)
                    .background(Color(0xFF1A1A1A)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (state.leftBook != null || state.rightBook != null) {
                    // TTS Play/Pause button (large)
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
                    if (state.ttsRemainingSeconds > 0) {
                        val minutes = (state.ttsRemainingSeconds / 60).toInt()
                        val seconds = (state.ttsRemainingSeconds % 60).toInt()
                        Text(
                            text = "%d:%02d".format(minutes, seconds),
                            color = Color(0xFF4FC3F7),
                            fontSize = 9.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Sync button
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
                    Spacer(modifier = Modifier.height(12.dp))
                    // Toggle bottom bar button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (state.isBottomBarVisible) Color(0xFF1565C0) else Color(0xFF2A2A2A),
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (state.isBottomBarVisible) Color(0xFF42A5F5) else Color(0xFF555555),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { viewModel.toggleBottomBarVisibility() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ViewColumn,
                            contentDescription = "Toggle bottom bar",
                            tint = if (state.isBottomBarVisible) Color(0xFF42A5F5) else Color(0xFFB0B0B0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // Next Chapter button - advances both books
                    val canAdvanceLeft = state.leftBook != null && 
                        state.leftPosition.chapterIndex < (state.leftBook?.totalChapters ?: 0) - 1
                    val canAdvanceRight = state.rightBook != null && 
                        state.rightPosition.chapterIndex < (state.rightBook?.totalChapters ?: 0) - 1
                    val canAdvance = canAdvanceLeft || canAdvanceRight
                    
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (canAdvance) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (canAdvance) Color(0xFF555555) else Color(0xFF333333),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = canAdvance) { viewModel.advanceBothBooksToNextChapter() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next chapter (both books)",
                            tint = if (canAdvance) Color(0xFFB0B0B0) else Color(0xFF444444),
                            modifier = Modifier.size(18.dp)
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
        }  // End Row
    }  // End Column

    // Unified horizontal bar - overlay positioned between text content and thin progress bar, crosses both panels
    // Same 12dp horizontal padding as BottomInfoBar for visual alignment
    // Respects system insets (notches, navigation bar) like the main content Row
    BottomBar(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(bottom = 66.dp),
        visible = state.isBottomBarVisible,
        fontSize = state.fontSize,
        onIncreaseFontSize = { viewModel.increaseFontSize() },
        onDecreaseFontSize = { viewModel.decreaseFontSize() },
        ttsTimeLimitMinutes = state.ttsTimeLimitMinutes,
        onCycleTtsTimeLimit = { viewModel.cycleTtsTimeLimit() },
        ttsSpeed = state.ttsSpeed,
        onCycleTtsSpeed = { viewModel.cycleTtsSpeed() },
        ttsBilingualMode = state.ttsBilingualMode,
        onCycleBilingualTtsMode = { viewModel.toggleBilingualTtsMode() },
        onResetBooks = { showResetDialog = true },
        onToggleBottomBar = { viewModel.toggleBottomBarVisibility() }
    )

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
}  // End Box

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

@Composable
private fun BottomBar(
    modifier: Modifier = Modifier,
    visible: Boolean,
    fontSize: Float,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    ttsTimeLimitMinutes: Int,
    onCycleTtsTimeLimit: () -> Unit,
    ttsSpeed: Float,
    onCycleTtsSpeed: () -> Unit,
    ttsBilingualMode: TtsBilingualMode,
    onCycleBilingualTtsMode: () -> Unit,
    onResetBooks: () -> Unit,
    onToggleBottomBar: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(if (visible) 56.dp else 0.dp)
            .background(Color(0xFF1A1A1A))
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (visible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Refresh / Reset books button - FAR LEFT
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFFB71C1C), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFFD32F2F), RoundedCornerShape(4.dp))
                        .clickable { onResetBooks() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⟳",
                        color = Color(0xFFFFFFFF),
                        fontSize = 16.sp
                    )
                }

                // Font size controls group - tight container
                Box(
                    modifier = Modifier
                        .background(Color(0xFF232323), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF444444), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                                .clickable { onDecreaseFontSize() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "−", color = Color(0xFFB0B0B0), fontSize = 16.sp)
                        }
                        Text(
                            text = "%.0f".format(fontSize),
                            color = Color(0xFFB0B0B0),
                            fontSize = 12.sp
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                                .clickable { onIncreaseFontSize() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "+", color = Color(0xFFB0B0B0), fontSize = 16.sp)
                        }
                    }
                }

                // TTS controls group (time limit + bilingual)
                Box(
                    modifier = Modifier
                        .background(Color(0xFF232323), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF444444), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // TTS max time limit
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                                .clickable { onCycleTtsTimeLimit() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (ttsTimeLimitMinutes > 0) "${ttsTimeLimitMinutes}'" else "∞",
                                color = if (ttsTimeLimitMinutes > 0) Color(0xFF4FC3F7) else Color(0xFFB0B0B0),
                                fontSize = 10.sp
                            )
                        }
                        // Bilingual TTS mode toggle (wider, with flags)
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .width(68.dp)
                                .background(
                                    if (ttsBilingualMode != TtsBilingualMode.OFF) Color(0xFF4A148C) else Color(0xFF2A2A2A),
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (ttsBilingualMode != TtsBilingualMode.OFF) Color(0xFFAB47BC) else Color(0xFF555555),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onCycleBilingualTtsMode() },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                val isActive = ttsBilingualMode != TtsBilingualMode.OFF
                                val alpha = if (isActive) 1f else 0.4f
                                Text(
                                    text = "\uD83C\uDDEC\uD83C\uDDE7", // 🇬🇧
                                    fontSize = 13.sp,
                                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                                )
                                Text(
                                    text = when (ttsBilingualMode) {
                                        TtsBilingualMode.OFF -> ""
                                        TtsBilingualMode.EN_TO_ES -> "\u2192" // →
                                        TtsBilingualMode.ES_TO_EN -> "\u2190" // ←
                                        TtsBilingualMode.EN_ES_EN -> "\u21C4" // ⇄
                                    },
                                    color = if (isActive) Color(0xFFCE93D8) else Color(0xFF555555),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 1.dp)
                                )
                                Text(
                                    text = "\uD83C\uDDEA\uD83C\uDDF8", // 🇪🇸
                                    fontSize = 13.sp,
                                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        // TTS speed (English only)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                                .clickable { onCycleTtsSpeed() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${ttsSpeed}x",
                                color = Color(0xFFB0B0B0),
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                // Close bar button - FAR RIGHT with grayish background
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF666666), RoundedCornerShape(4.dp))
                        .clickable { onToggleBottomBar() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        color = Color(0xFFB0B0B0),
                        fontSize = 18.sp
                    )
                }
            }
        }
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

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ecos.logic.twinbooks.alignment.model.ServerStatus
import org.ecos.logic.twinbooks.domain.model.ChapterPairingHint
import org.ecos.logic.twinbooks.domain.model.TtsBilingualMode
import org.ecos.logic.twinbooks.ui.viewmodel.ReaderViewModel
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ReaderScreen(
    sessionId: Long,
    onBackToBookshelf: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(
        checkNotNull(
            LocalViewModelStoreOwner.current
        ) {
                "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
            }, null
    )
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var rightSentenceIndex by remember { mutableIntStateOf(-1) }
    var rightSentenceCount by remember { mutableIntStateOf(0) }
    var rightOffset by remember { mutableIntStateOf(0) }
    var rightParagraphIndex by remember { mutableIntStateOf(-1) }
    var scrollToRightIndex by remember { mutableStateOf<Int?>(null) }
    var scrollToLeftIndex by remember { mutableStateOf<Int?>(null) }
    var isSyncScrollingRight by remember { mutableStateOf(false) }
    var rightTtsHighlight by remember { mutableStateOf<TtsSentenceHighlight?>(null) }
    // Right-panel <- / -> jump in flight: true = select the last sentence on arrival, false = first
    var pendingRightSentenceLast by remember { mutableStateOf<Boolean?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    // Load session when screen is created
    LaunchedEffect(sessionId) {
        viewModel.loadSession(sessionId)
    }

    // Set up callback to highlight sentence in right book during TTS
    LaunchedEffect(Unit) {
        viewModel.onHighlightRightSentence = { paragraph, first, last ->
            rightTtsHighlight = TtsSentenceHighlight(
                paragraphIndex = paragraph,
                firstSentence = first,
                lastSentence = last,
                trigger = (rightTtsHighlight?.trigger ?: 0) + 1
            )
        }
    }

    LaunchedEffect(state.leftPosition.paragraphText) {
        // While TTS is playing, the ViewModel owns leftSentenceIndex (it tracks the sentence
        // the WebView must speak next). Resetting it to -1 here raced with the sentence
        // callback: expected=0 vs state=-1 made every callback be ignored forever (TTS deadlock
        // that only recovered when pressing <- manually).
        if (!state.isTtsPlaying) {
            viewModel.updateTtsSentenceIndex(-1)
        }
        rightSentenceIndex = -1
        rightOffset = 0
    }

    LaunchedEffect(scrollToRightIndex) {
        if (scrollToRightIndex != null) {
            isSyncScrollingRight = true
            val targetIndex = scrollToRightIndex
            scrollToRightIndex = null
            // Wait for the programmatic scroll to complete before allowing anchor updates
            // The WebView scroll + JS callback takes some time
            coroutineScope.launch {
                delay(500.milliseconds)
                if (rightParagraphIndex == targetIndex) {
                    isSyncScrollingRight = false
                }
            }
        }
    }

    LaunchedEffect(scrollToLeftIndex) {
        if (scrollToLeftIndex != null) {
            scrollToLeftIndex = null
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

    val singleBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            persistUriPermission(context, it)
            viewModel.loadSingleBook(it)
        }
    }

    val hasPair = state.rightBook != null && !state.isSingleBookMode

    LaunchedEffect(state.sessionNotFound) {
        if (state.sessionNotFound) onBackToBookshelf()
    }

    // A chapter alignment just became available (on open it usually arrives after the
    // first sync, which then used the rough fallback): place the right panel again
    LaunchedEffect(state.resyncRightTrigger) {
        if (state.resyncRightTrigger > 0 && state.isSynchronized && state.leftParagraphIndex >= 0) {
            viewModel.findAndSyncBestMatch(state.leftParagraphIndex) { scrollToRightIndex = it }
        }
    }

    // Tell the user when the alignment server can't be reached (the app keeps working with
    // the local alignment, but the quality is lower)
    val serverStatus by viewModel.serverStatus.collectAsState()
    var lastNotifiedServerStatus by remember { mutableStateOf<ServerStatus?>(null) }
    LaunchedEffect(serverStatus, hasPair) {
        if (!hasPair || serverStatus == lastNotifiedServerStatus) return@LaunchedEffect
        val message = when (serverStatus) {
            ServerStatus.OFFLINE -> "No se puede conectar con el servidor de alineación. Se usa la alineación local."
            ServerStatus.UNAUTHORIZED -> "El servidor rechaza la clave de API (revísala en los ajustes del servidor, en la estantería)."
            ServerStatus.ONLINE -> if (lastNotifiedServerStatus == ServerStatus.OFFLINE ||
                lastNotifiedServerStatus == ServerStatus.UNAUTHORIZED
            ) "Servidor de alineación disponible de nuevo." else null
            else -> return@LaunchedEffect
        }
        lastNotifiedServerStatus = serverStatus
        message?.let { snackbarHostState.showSnackbar(it) }
    }

    // Same for the translation server in single-book mode (fallback: on-device translation)
    val translationLook = translationStatusLook(serverStatus, state.isServerTranslationFailing)
    var lastNotifiedTranslationLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(translationLook.third, state.isSingleBookMode) {
        if (!state.isSingleBookMode || state.leftBook == null) return@LaunchedEffect
        val label = translationLook.third
        if (label == lastNotifiedTranslationLabel) return@LaunchedEffect
        val wasFailing = lastNotifiedTranslationLabel != null &&
            lastNotifiedTranslationLabel != TRANSLATION_OK_LABEL &&
            lastNotifiedTranslationLabel != TRANSLATION_CHECKING_LABEL
        val message = when (label) {
            TRANSLATION_OK_LABEL -> if (wasFailing) "Servidor de traducción disponible de nuevo." else null
            TRANSLATION_CHECKING_LABEL, TRANSLATION_ON_DEVICE_LABEL -> return@LaunchedEffect
            else -> "$label. Se usa la traducción del dispositivo."
        }
        lastNotifiedTranslationLabel = label
        message?.let { snackbarHostState.showSnackbar(it) }
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
            // Left panel (full width in single-book mode, half when paired with a right book)
            Box(
                modifier = Modifier
                    .then(
                        if (state.isSingleBookMode) Modifier.weight(1f)
                        else Modifier.fillMaxWidth(0.5f)
                    )
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
                            val current = viewModel.state.value.leftSentenceIndex
                            rightOffset = rightSentenceIndex - current
                            // First sentence: jumps to the last one of the previous paragraph
                            val targetParagraph = viewModel.previousLeftSentence()
                            if (targetParagraph != null) {
                                scrollToLeftIndex = targetParagraph
                            } else {
                                rightSentenceIndex = (current - 1 + rightOffset).coerceAtLeast(0)
                            }
                        },
                        onNextSentence = {
                            val current = viewModel.state.value.leftSentenceIndex
                            rightOffset = rightSentenceIndex - current
                            // Last sentence: jumps to the first one of the next paragraph
                            val targetParagraph = viewModel.nextLeftSentence()
                            if (targetParagraph != null) {
                                scrollToLeftIndex = targetParagraph
                            } else {
                                rightSentenceIndex = (current + 1 + rightOffset)
                                    .coerceAtMost((rightSentenceCount - 1).coerceAtLeast(0))
                            }
                        },
                        onPrevParagraph = {
                            // Navigate left book to previous paragraph
                            val leftParaIndex = state.leftParagraphIndex
                            if (leftParaIndex > 0) {
                                val newIndex = leftParaIndex - 1
                                viewModel.updateLeftParagraphIndex(newIndex)
                                scrollToLeftIndex = newIndex
                            }
                            // Also navigate right book if synchronized
                            if (state.isSynchronized && rightParagraphIndex > 0) {
                                rightParagraphIndex -= 1
                                viewModel.updateRightParagraphIndex(rightParagraphIndex, isManualScroll = false)
                                scrollToRightIndex = rightParagraphIndex
                            }
                        },
                        onNextParagraph = {
                            // Navigate left book to next paragraph
                            val leftParaIndex = state.leftParagraphIndex
                            val leftChapter = state.leftBook?.chapters?.getOrNull(state.leftPosition.chapterIndex)
                            val leftTotalParagraphs = leftChapter?.htmlContent?.let { 
                                Regex("<p(\\s[^>]*)?>", RegexOption.IGNORE_CASE).findAll(it).count() 
                            } ?: 1
                            if (leftParaIndex < leftTotalParagraphs - 1) {
                                val newIndex = leftParaIndex + 1
                                viewModel.updateLeftParagraphIndex(newIndex)
                                scrollToLeftIndex = newIndex
                            }
                            // Also navigate right book if synchronized
                            if (state.isSynchronized) {
                                val rightChapter = state.rightBook?.chapters?.getOrNull(state.rightPosition.chapterIndex)
                                val rightTotalParagraphs = rightChapter?.htmlContent?.let { 
                                    Regex("<p(\\s[^>]*)?>", RegexOption.IGNORE_CASE).findAll(it).count() 
                                } ?: 1
                                if (rightParagraphIndex < rightTotalParagraphs - 1) {
                                    rightParagraphIndex += 1
                                    viewModel.updateRightParagraphIndex(rightParagraphIndex, isManualScroll = false)
                                    scrollToRightIndex = rightParagraphIndex
                                }
                            }
                        },
                        onPositionChanged = { chapter, offset, paragraphText ->
                            viewModel.updateLeftPosition(chapter, offset, paragraphText)
                        },
                        onChapterSelected = { chapter ->
                            viewModel.navigateToChapter(true, chapter)
                        },
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
                        scrollToParagraphIndex = scrollToLeftIndex,
                        ttsRefreshTrigger = viewModel.ttsRefreshTrigger,
                        ttsScrollToNextParagraphTrigger = state.ttsScrollToNextParagraphTrigger,
                        requestChapterSentencesTrigger = state.requestChapterSentencesTrigger,
                        onChapterSentences = { chapter, json ->
                            viewModel.onLeftChapterSentences(chapter, json)
                        },
                        inlineTranslationTrigger = state.inlineTranslationTrigger,
                        inlineTranslationSentenceIdx = state.inlineTranslationSentenceIdx,
                        inlineTranslationText = state.inlineTranslationText,
                        highlightTranslatedTrigger = state.highlightTranslatedTrigger,
                        highlightTranslatedText = state.highlightTranslatedText,
                        highlightEnglishTrigger = state.highlightEnglishTrigger,
                        highlightEnglishText = state.highlightEnglishText,
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
                    if (state.isSingleBookMode) {
                        // Single-book mode: no sync. Automatic-translation toggle, plus
                        // "back to two books" when a right book is still paired.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.rightBook != null) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                                        .border(1.dp, Color(0xFF555555), RoundedCornerShape(6.dp))
                                        .clickable { viewModel.exitSingleBookMode() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ViewColumn,
                                        contentDescription = "Volver a dos libros",
                                        tint = Color(0xFFB0B0B0),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            // Auto-translation toggle
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        if (state.autoTranslationEnabled) Color(0xFF00695C) else Color(0xFF2A2A2A),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(6.dp))
                                    .clickable { viewModel.toggleAutoTranslation() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = "Toggle automatic translation",
                                    tint = if (state.autoTranslationEnabled) Color(0xFF80CBC4) else Color(0xFFB0B0B0),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            // Translation server status (tap = check again and retry the server); none without a server
                            if (serverStatus != ServerStatus.DISABLED) {
                                val (translationIcon, translationTint, translationLabel) = translationLook
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                                        .border(1.dp, translationTint.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .clickable { viewModel.retryServerTranslation() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.isServerAligning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = translationTint,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = translationIcon,
                                            contentDescription = "$translationLabel. Toca para reintentar",
                                            tint = translationTint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Sync button + Retry button (vertical layout)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                if (state.chapterAlignmentProgress >= 0f) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color(0xFF4CAF50),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = "Toggle synchronization",
                                        tint = if (state.isSynchronized) Color(0xFF4CAF50) else Color(0xFFB0B0B0),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            // Alignment server status (tap = retry the current chapter) - BELOW sync button
                            if (state.rightBook != null && serverStatus != ServerStatus.DISABLED) {
                                val (serverIcon, serverTint, serverLabel) = serverStatusLook(serverStatus)
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                                        .border(1.dp, serverTint.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .clickable { viewModel.retryServerAlignment() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.isServerAligning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = serverTint,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = serverIcon,
                                            contentDescription = "$serverLabel. Toca para reintentar",
                                            tint = serverTint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
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
                }
            }

            // Right panel (hidden in single-book mode: the divider becomes the right edge)
            if (!state.isSingleBookMode) {
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
                            val selectLast = pendingRightSentenceLast
                            if (selectLast != null) {
                                // A <- / -> jump to another paragraph landed: first / last sentence,
                                // after the WebView's own "select sentence 0" (~50 ms)
                                pendingRightSentenceLast = null
                                coroutineScope.launch {
                                    delay(250.milliseconds)
                                    rightSentenceIndex = if (selectLast) (count - 1).coerceAtLeast(0) else 0
                                }
                            } else if (count > 0 && rightSentenceIndex == -1) {
                                rightSentenceIndex = 0
                            }
                        },
                        onPrevSentence = {
                            if (rightSentenceIndex > 0) {
                                rightSentenceIndex -= 1
                            } else {
                                // First sentence: last sentence of the previous paragraph
                                viewModel.adjacentParagraph(isLeft = false, from = rightParagraphIndex, step = -1)?.let {
                                    pendingRightSentenceLast = true
                                    scrollToRightIndex = it
                                }
                            }
                        },
                        onNextSentence = {
                            if (rightSentenceIndex < rightSentenceCount - 1) {
                                rightSentenceIndex += 1
                            } else {
                                // Last sentence: first sentence of the next paragraph
                                viewModel.adjacentParagraph(isLeft = false, from = rightParagraphIndex, step = 1)?.let {
                                    pendingRightSentenceLast = false
                                    scrollToRightIndex = it
                                }
                            }
                        },
                        onPrevParagraph = {
                            // Only navigate right book to previous paragraph
                            if (rightParagraphIndex > 0) {
                                rightParagraphIndex -= 1
                                viewModel.updateRightParagraphIndex(rightParagraphIndex)
                                scrollToRightIndex = rightParagraphIndex
                            }
                        },
                        onNextParagraph = {
                            // Only navigate right book to next paragraph
                            val rightChapter = state.rightBook?.chapters?.getOrNull(state.rightPosition.chapterIndex)
                            val rightTotalParagraphs = rightChapter?.htmlContent?.let {
                                Regex("<p(\\s[^>]*)?>", RegexOption.IGNORE_CASE).findAll(it).count()
                            } ?: 1
                            if (rightParagraphIndex < rightTotalParagraphs - 1) {
                                rightParagraphIndex += 1
                                viewModel.updateRightParagraphIndex(rightParagraphIndex)
                                scrollToRightIndex = rightParagraphIndex
                            }
                        },
                        onPositionChanged = { chapter, offset, paragraphText ->
                            viewModel.updateRightPosition(chapter, offset, paragraphText)
                        },
                        onChapterSelected = { chapter ->
                            viewModel.navigateToChapter(false, chapter)
                        },
                        isSynchronized = state.isSynchronized,
                        onParagraphDoubleClicked = { index ->
                            viewModel.onRightParagraphDoubleClicked(index)
                        },
                        onParagraphIndexChanged = { index ->
                            if (isSyncScrollingRight) {
                                // Suppress anchor update during programmatic sync scroll
                                viewModel.updateRightParagraphIndex(index, isManualScroll = false)
                            } else {
                                viewModel.updateRightParagraphIndex(index, isManualScroll = true)
                            }
                            rightParagraphIndex = index
                        },
                        scrollToParagraphIndex = scrollToRightIndex,
                        ttsSentenceHighlight = rightTtsHighlight,
                        onChapterSentences = { chapter, json ->
                            viewModel.onRightChapterSentences(chapter, json)
                        }
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
        }  // End Row

            // Third loading option: a single book with on-device automatic translation
            if (state.rightBook == null && !state.isSingleBookMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111111))
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { singleBookLauncher.launch(arrayOf("application/epub+zip")) }) {
                        Text(
                            text = "Un solo libro · traducción automática",
                            color = Color(0xFF80CBC4),
                            fontSize = 16.sp
                        )
                    }
                }
            }
    }  // End Column

    // Suggestion when the chapter map says this chapter can't be read side by side (or can again)
    state.chapterPairingHint?.let { hint ->
        ChapterPairingBanner(
            hint = hint,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp),
            onAccept = {
                when (hint) {
                    ChapterPairingHint.UNPAIRED -> viewModel.switchToAutoTranslation()
                    ChapterPairingHint.PAIRED_AGAIN -> viewModel.exitSingleBookMode()
                }
            },
            onDismiss = { viewModel.dismissChapterPairingHint() }
        )
    }

    // Unified horizontal bar - overlay positioned between text content and thin progress bar, crosses both panels
    // Same 12dp horizontal padding as BottomInfoBar for visual alignment
    // Respects system insets (notches, navigation bar) like the main content Row
    val canGoPrevChapter = state.leftBook != null && state.leftPosition.chapterIndex > 0
    val canGoNextChapter = state.leftBook != null && 
        state.leftPosition.chapterIndex < (state.leftBook?.totalChapters ?: 0) - 1
    
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
        onSetTtsTimeLimit = { viewModel.setTtsTimeLimit(it) },
        ttsSpeed = state.ttsSpeed,
        onSetTtsSpeed = { viewModel.setTtsSpeed(it) },
        ttsBilingualMode = state.ttsBilingualMode,
        onSetTtsBilingualMode = { viewModel.setTtsBilingualMode(it) },
        onResetBooks = { showResetDialog = true },
        onToggleBottomBar = { viewModel.toggleBottomBarVisibility() },
        onOpenBookshelf = {
            // Navigate back to bookshelf - stop TTS and notify parent
            viewModel.stopTts()
            onBackToBookshelf()
        },
        onPrevChapter = { viewModel.navigateToPreviousChapter() },
        onNextChapter = { viewModel.advanceBothBooksToNextChapter() },
        canGoPrevChapter = canGoPrevChapter,
        canGoNextChapter = canGoNextChapter
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
    onSetTtsTimeLimit: (Int) -> Unit,
    ttsSpeed: Float,
    onSetTtsSpeed: (Float) -> Unit,
    ttsBilingualMode: TtsBilingualMode,
    onSetTtsBilingualMode: (TtsBilingualMode) -> Unit,
    onResetBooks: () -> Unit,
    onToggleBottomBar: () -> Unit,
    onOpenBookshelf: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    canGoPrevChapter: Boolean,
    canGoNextChapter: Boolean
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
                // Group 1: Reset + Bookshelf
                Box(
                    modifier = Modifier
                        .background(Color(0xFF232323), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF444444), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Refresh / Reset books button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFFB71C1C), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFFD32F2F), RoundedCornerShape(4.dp))
                                .clickable { onResetBooks() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "⟳", color = Color(0xFFFFFFFF), fontSize = 16.sp)
                        }

                        // Bookshelf button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF1565C0), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF42A5F5), RoundedCornerShape(4.dp))
                                .clickable { onOpenBookshelf() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "📚", fontSize = 16.sp)
                        }
                    }
                }

                // Group 2: Chapter navigation (|< and >|)
                Box(
                    modifier = Modifier
                        .background(Color(0xFF232323), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF444444), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Previous chapter (|<)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (canGoPrevChapter) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (canGoPrevChapter) Color(0xFF555555) else Color(0xFF333333),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable(enabled = canGoPrevChapter) { onPrevChapter() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "|<",
                                color = if (canGoPrevChapter) Color(0xFFB0B0B0) else Color(0xFF444444),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Next chapter (>|)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (canGoNextChapter) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (canGoNextChapter) Color(0xFF555555) else Color(0xFF333333),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable(enabled = canGoNextChapter) { onNextChapter() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ">|",
                                color = if (canGoNextChapter) Color(0xFFB0B0B0) else Color(0xFF444444),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
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

                // TTS controls group (time limit + bilingual + speed)
                var showTimeLimitMenu by remember { mutableStateOf(false) }
                var showBilingualMenu by remember { mutableStateOf(false) }
                var showSpeedMenu by remember { mutableStateOf(false) }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // TTS time limit dropdown
                    Box {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                                .clickable { showTimeLimitMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (ttsTimeLimitMinutes > 0) "${ttsTimeLimitMinutes}'" else "∞",
                                color = if (ttsTimeLimitMinutes > 0) Color(0xFF4FC3F7) else Color(0xFFB0B0B0),
                                fontSize = 10.sp
                            )
                        }
                        DropdownMenu(
                            expanded = showTimeLimitMenu,
                            onDismissRequest = { showTimeLimitMenu = false }
                        ) {
                            listOf(0, 1, 15, 30, 45).forEach { minutes ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (minutes > 0) "$minutes min" else "Sin límite",
                                            color = if (minutes == ttsTimeLimitMinutes) Color(0xFF4FC3F7) else Color(0xFFB0B0B0)
                                        )
                                    },
                                    onClick = {
                                        onSetTtsTimeLimit(minutes)
                                        showTimeLimitMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Bilingual mode dropdown
                    Box {
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
                                .clickable { showBilingualMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                val isActive = ttsBilingualMode != TtsBilingualMode.OFF
                                val alpha = if (isActive) 1f else 0.4f
                                val esFirst = ttsBilingualMode == TtsBilingualMode.ES_TO_EN
                                Text(
                                    text = if (esFirst) "\uD83C\uDDEA\uD83C\uDDF8" else "\uD83C\uDDEC\uD83C\uDDE7",
                                    fontSize = 13.sp,
                                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                                )
                                Text(
                                    text = when (ttsBilingualMode) {
                                        TtsBilingualMode.OFF -> ""
                                        TtsBilingualMode.EN_TO_ES -> "\u2192"
                                        TtsBilingualMode.ES_TO_EN -> "\u2192"
                                        TtsBilingualMode.EN_ES_EN -> "\u21C4"
                                    },
                                    color = if (isActive) Color(0xFFCE93D8) else Color(0xFF555555),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 1.dp)
                                )
                                Text(
                                    text = if (esFirst) "\uD83C\uDDEC\uD83C\uDDE7" else "\uD83C\uDDEA\uD83C\uDDF8",
                                    fontSize = 13.sp,
                                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showBilingualMenu,
                            onDismissRequest = { showBilingualMenu = false }
                        ) {
                            TtsBilingualMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (mode != TtsBilingualMode.OFF) {
                                                val esFirst = mode == TtsBilingualMode.ES_TO_EN
                                                Text(text = if (esFirst) "\uD83C\uDDEA\uD83C\uDDF8" else "\uD83C\uDDEC\uD83C\uDDE7", fontSize = 14.sp)
                                                Text(
                                                    text = when (mode) {
                                                        TtsBilingualMode.EN_TO_ES -> " \u2192 "
                                                        TtsBilingualMode.ES_TO_EN -> " \u2192 "
                                                        TtsBilingualMode.EN_ES_EN -> " \u21C4 "
                                                    },
                                                    color = Color(0xFFCE93D8),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(text = if (esFirst) "\uD83C\uDDEC\uD83C\uDDE7" else "\uD83C\uDDEA\uD83C\uDDF8", fontSize = 14.sp)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = mode.label,
                                                color = if (mode == ttsBilingualMode) Color(0xFFCE93D8) else Color(0xFFB0B0B0)
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSetTtsBilingualMode(mode)
                                        showBilingualMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // TTS speed dropdown
                    Box {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                                .clickable { showSpeedMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${ttsSpeed}x",
                                color = Color(0xFFB0B0B0),
                                fontSize = 9.sp
                            )
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false }
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f).forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${speed}x",
                                            color = if (speed == ttsSpeed) Color(0xFF4FC3F7) else Color(0xFFB0B0B0)
                                        )
                                    },
                                    onClick = {
                                        onSetTtsSpeed(speed)
                                        showSpeedMenu = false
                                    }
                                )
                            }
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

@Composable
private fun ChapterPairingBanner(
    hint: ChapterPairingHint,
    modifier: Modifier = Modifier,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val (message, action, icon) = when (hint) {
        ChapterPairingHint.UNPAIRED -> Triple(
            "Este capítulo no tiene equivalente en el libro en castellano.",
            "Usar traducción automática",
            Icons.Default.Translate
        )
        ChapterPairingHint.PAIRED_AGAIN -> Triple(
            "Este capítulo tiene equivalente en el libro en castellano.",
            "Volver a dos libros",
            Icons.Default.ViewColumn
        )
    }
    Row(
        modifier = modifier
            .background(Color(0xF0263238), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF26A69A), RoundedCornerShape(10.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF80CBC4), modifier = Modifier.size(20.dp))
        Text(text = message, color = Color(0xFFE0E0E0), fontSize = 14.sp)
        TextButton(onClick = onAccept) {
            Text(text = action, color = Color(0xFF80CBC4), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onDismiss) {
            Text(text = "Ahora no", color = Color(0xFF9E9E9E), fontSize = 14.sp)
        }
    }
}

private const val TRANSLATION_OK_LABEL = "Servidor de traducción disponible"
private const val TRANSLATION_CHECKING_LABEL = "Comprobando el servidor de traducción"
private const val TRANSLATION_ON_DEVICE_LABEL = "Sin servidor: traducción en el dispositivo"

/**
 * Icon, colour and label for the translation server in single-book mode. [failing]: the
 * server answers the health check but the last translation request failed.
 */
internal fun translationStatusLook(status: ServerStatus, failing: Boolean): Triple<ImageVector, Color, String> = when {
    status == ServerStatus.DISABLED -> Triple(Icons.Default.PhoneAndroid, Color(0xFF9E9E9E), TRANSLATION_ON_DEVICE_LABEL)
    status == ServerStatus.OFFLINE -> Triple(Icons.Default.CloudOff, Color(0xFFEF5350), "Servidor de traducción no disponible")
    status == ServerStatus.UNAUTHORIZED -> Triple(Icons.Default.Key, Color(0xFFFFA726), "Clave de API del servidor no válida")
    failing -> Triple(Icons.Default.Warning, Color(0xFFFFA726), "El servidor de traducción está dando errores")
    status == ServerStatus.ONLINE -> Triple(Icons.Default.Cloud, Color(0xFF66BB6A), TRANSLATION_OK_LABEL)
    else -> Triple(Icons.Default.CloudSync, Color(0xFF9E9E9E), TRANSLATION_CHECKING_LABEL)
}

/** Icon, colour and label for the alignment server status. */
internal fun serverStatusLook(status: ServerStatus): Triple<ImageVector, Color, String> = when (status) {
    ServerStatus.ONLINE -> Triple(Icons.Default.Cloud, Color(0xFF66BB6A), "Servidor de alineación disponible")
    ServerStatus.OFFLINE -> Triple(Icons.Default.CloudOff, Color(0xFFEF5350), "Servidor de alineación no disponible")
    ServerStatus.UNAUTHORIZED -> Triple(Icons.Default.Key, Color(0xFFFFA726), "Clave de API del servidor no válida")
    ServerStatus.CHECKING, ServerStatus.UNKNOWN -> Triple(Icons.Default.CloudSync, Color(0xFF9E9E9E), "Comprobando el servidor de alineación")
    ServerStatus.DISABLED -> Triple(Icons.Default.PhoneAndroid, Color(0xFF9E9E9E), "Sin servidor: alineación en el dispositivo")
}

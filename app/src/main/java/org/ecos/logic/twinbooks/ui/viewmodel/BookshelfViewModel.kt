package org.ecos.logic.twinbooks.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import org.ecos.logic.twinbooks.alignment.model.ServerStatus
import org.ecos.logic.twinbooks.alignment.repository.AlignmentRepository
import org.ecos.logic.twinbooks.alignment.repository.ServerConfig
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.BookRepository
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.gutenberg.GutenbergLanguage
import javax.inject.Inject

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    @ApplicationContext private val context: Context,
    private val alignmentRepository: AlignmentRepository
) : ViewModel() {

    /** Availability of the alignment server, shown in the bookshelf header */
    val serverStatus: StateFlow<ServerStatus> = alignmentRepository.status

    /** Server set up in the settings (disabled = everything on-device) */
    val serverConfig: StateFlow<ServerConfig> = alignmentRepository.config

    private val _serverTest = MutableStateFlow<ServerStatus?>(null)
    /** Result of "Probar conexión" in the settings: null = not tested, CHECKING = running */
    val serverTest = _serverTest.asStateFlow()

    fun checkServer() {
        viewModelScope.launch { alignmentRepository.checkHealth() }
    }

    fun testServer(config: ServerConfig) {
        _serverTest.value = ServerStatus.CHECKING
        viewModelScope.launch { _serverTest.value = alignmentRepository.testConnection(config) }
    }

    /** The settings fields changed: the last test no longer applies */
    fun clearServerTest() {
        _serverTest.value = null
    }

    fun saveServer(config: ServerConfig) {
        alignmentRepository.updateConfig(config)
        _serverTest.value = null
        checkServer()
    }

    /** An EPUB opened from another app, copied into the app's storage, pending the user's choice. */
    data class IncomingBook(
        val uri: String,
        val title: String,
        /** Session that already uses this book, if any */
        val existingSession: ReadingSession?,
        /** Known language (Gutenberg downloads): only the options that make sense are offered */
        val language: GutenbergLanguage? = null
    )

    private val _incomingBook = MutableStateFlow<IncomingBook?>(null)
    val incomingBook = _incomingBook.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionWithCover>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private var pendingLeftBookUri: String? = null
    private var pendingRightBookUri: String? = null

    init {
        loadSessions()
        checkServer()
    }

    fun loadSessions() {
        viewModelScope.launch {
            val sessions = bookRepository.getAllSessions()
            // Covers of both books (a pair stands on the shelf as two books); each one means
            // opening its EPUB, so they load in parallel
            val sessionsWithCover = sessions.map { session ->
                async {
                    val left = async { bookRepository.loadBookFromUri(session.leftBookUri)?.coverImage }
                    val right = async {
                        session.rightBookUri?.takeIf { !session.isSingleBookMode }
                            ?.let { bookRepository.loadBookFromUri(it)?.coverImage }
                    }
                    SessionWithCover(session, left.await(), right.await())
                }
            }.awaitAll()
            _sessions.value = sessionsWithCover
        }
    }

    fun setLeftBookUri(uri: String) {
        pendingLeftBookUri = uri
    }

    fun setRightBookUri(uri: String) {
        pendingRightBookUri = uri
    }

    fun createNewPair() {
        viewModelScope.launch {
            pendingLeftBookUri?.let { leftUri ->
                val leftBook = bookRepository.loadBookFromUri(leftUri)
                val rightBook = pendingRightBookUri?.let { bookRepository.loadBookFromUri(it) }

                if (leftBook != null) {
                    val session = ReadingSession(
                        leftBookUri = leftUri,
                        rightBookUri = pendingRightBookUri,
                        leftTitle = leftBook.title,
                        rightTitle = rightBook?.title,
                        leftChapterIndex = 0,
                        rightChapterIndex = 0,
                        leftScrollOffset = 0,
                        rightScrollOffset = 0,
                        leftProgressPercent = 0f,
                        rightProgressPercent = 0f,
                        leftParagraphText = "",
                        rightParagraphText = "",
                        fontSize = 12f,
                        isSynchronized = false,
                        syncOffset = 0,
                        ttsTimeLimitMinutes = 0,
                        ttsBilingualMode = "OFF",
                        ttsSpeed = 1.0f
                    )
                    bookRepository.saveSession(session)
                    pendingLeftBookUri = null
                    pendingRightBookUri = null
                    loadSessions()
                }
            }
        }
    }

    /**
     * Create a session with a SINGLE book: full-screen view, the Spanish side is an
     * on-device translation instead of a second book.
     */
    fun createSingleBook(uri: String) {
        viewModelScope.launch {
            val book = bookRepository.loadBookFromUri(uri)
            if (book != null) {
                val session = ReadingSession(
                    leftBookUri = uri,
                    rightBookUri = null,
                    leftTitle = book.title,
                    rightTitle = null,
                    fontSize = 12f,
                    ttsBilingualMode = "OFF",
                    isSingleBookMode = true,
                    autoTranslationEnabled = true
                )
                bookRepository.saveSession(session)
                pendingLeftBookUri = null
                pendingRightBookUri = null
                loadSessions()
            }
        }
    }

    /**
     * EPUB received from another app. The read grant of a VIEW intent is temporary and can't
     * be made persistent, so the file is copied to internal storage and the session uses
     * that copy (otherwise the book couldn't be reopened later).
     */
    fun importIncomingBook(uri: Uri) {
        viewModelScope.launch {
            val localUri = withContext(Dispatchers.IO) { copyToLibrary(uri) }
            if (localUri == null) {
                Log.w("BookshelfViewModel", "Could not import $uri")
                return@launch
            }
            offer(localUri)
        }
    }

    /** A book already in the app's storage (e.g. downloaded from Gutenberg): ask how to read it. */
    fun offerLocalBook(localUri: String, language: GutenbergLanguage? = null) {
        viewModelScope.launch { offer(localUri, language) }
    }

    private suspend fun offer(localUri: String, language: GutenbergLanguage? = null) {
        val book = bookRepository.loadBookFromUri(localUri)
        if (book == null) {
            Log.w("BookshelfViewModel", "Imported file is not a readable EPUB: $localUri")
            return
        }
        val existing = bookRepository.getAllSessions()
            .firstOrNull { it.leftBookUri == localUri || it.rightBookUri == localUri }
        _incomingBook.value = IncomingBook(localUri, book.title, existing, language)
    }

    fun dismissIncomingBook() {
        _incomingBook.value = null
    }

    /** Copies [source] to files/books/ and returns its file:// URI (reused if already imported). */
    private fun copyToLibrary(source: Uri): String? = try {
        val name = displayName(source)
            ?.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            ?.let { if (it.endsWith(".epub", ignoreCase = true)) it else "$it.epub" }
            ?: "book_${System.currentTimeMillis()}.epub"
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val size = context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        var target = File(dir, name)
        if (!(target.exists() && size > 0 && target.length() == size)) {
            // Same name but different content: don't overwrite a book already in use
            var n = 1
            while (target.exists()) target = File(dir, name.removeSuffix(".epub") + "_${n++}.epub")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
        }
        Uri.fromFile(target).toString()
    } catch (e: Exception) {
        Log.e("BookshelfViewModel", "Import failed for $source", e)
        null
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            bookRepository.deleteSession(sessionId)
            loadSessions()
        }
    }
}

/**
 * ReadingSession with optional cover image for bookshelf display.
 * Cover is loaded on-demand to avoid database bloat.
 */
data class SessionWithCover(
    val session: ReadingSession,
    val leftCoverImage: String? = null,
    val rightCoverImage: String? = null
)
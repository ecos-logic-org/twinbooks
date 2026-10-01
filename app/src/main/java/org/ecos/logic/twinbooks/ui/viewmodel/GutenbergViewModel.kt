package org.ecos.logic.twinbooks.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ecos.logic.twinbooks.freebooks.PublicDownloads
import org.ecos.logic.twinbooks.gutenberg.GutenbergBook
import org.ecos.logic.twinbooks.gutenberg.GutenbergLanguage
import org.ecos.logic.twinbooks.gutenberg.GutenbergRepository
import java.io.File
import javax.inject.Inject

data class GutenbergUiState(
    val query: String = "",
    val language: GutenbergLanguage = GutenbergLanguage.ENGLISH,
    val books: List<GutenbergBook> = emptyList(),
    /** A search has run (distinguishes "no results" from "nothing searched yet") */
    val searched: Boolean = false,
    val isLoading: Boolean = false,
    val nextStartIndex: Int? = null,
    val error: String? = null,
    /** Book being downloaded and its progress (0..1, -1 = unknown size) */
    val downloadingId: Int? = null,
    val downloadProgress: Float = 0f,
)

/** A finished download: file:// URI in the app's storage, plus what the catalog said about it */
data class DownloadedBook(val uri: String, val language: GutenbergLanguage, val author: String)

/** Opens the search already filled in (e.g. the other half of a pair: same author, other language) */
data class GutenbergPreset(val query: String, val language: GutenbergLanguage)

/** Project Gutenberg search and download, shown from the bookshelf. */
@HiltViewModel
class GutenbergViewModel @Inject constructor(
    private val gutenberg: GutenbergRepository,
    private val publicDownloads: PublicDownloads,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(GutenbergUiState())
    val state = _state.asStateFlow()

    private val _downloaded = Channel<DownloadedBook>(Channel.BUFFERED)
    /** Each finished download, to be offered on the bookshelf or placed in a pair */
    val downloaded = _downloaded.receiveAsFlow()

    private val covers = LruCache<Int, ByteArray>(COVER_CACHE_SIZE)
    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    /** Fills in the search (and runs it when there is something to search for). */
    fun applyPreset(preset: GutenbergPreset) {
        _state.update { it.copy(query = preset.query, language = preset.language) }
        if (preset.query.isNotBlank()) {
            search()
        } else {
            searchJob?.cancel()
            _state.update { it.copy(books = emptyList(), searched = false, nextStartIndex = null, error = null) }
        }
    }

    fun setLanguage(language: GutenbergLanguage) {
        if (language == _state.value.language) return
        _state.update { it.copy(language = language) }
        if (_state.value.searched) search()
    }

    fun search() {
        val s = _state.value
        if (s.query.isBlank()) return
        searchJob?.cancel()
        _state.update { it.copy(books = emptyList(), nextStartIndex = null, searched = true, error = null) }
        load(startIndex = 1)
    }

    fun loadMore() {
        val next = _state.value.nextStartIndex ?: return
        if (_state.value.isLoading) return
        load(next)
    }

    private fun load(startIndex: Int) {
        val s = _state.value
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val page = gutenberg.search(s.query, s.language, startIndex)
                _state.update {
                    it.copy(books = (it.books + page.books).distinctBy { b -> b.id }, nextStartIndex = page.nextStartIndex)
                }
            } catch (e: Exception) {
                Log.w("Gutenberg", "Search failed: ${e.message}")
                _state.update { it.copy(error = "No se puede conectar con Project Gutenberg. Revisa la conexión.") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Cover bytes (cached in memory), or null when the book has none or it can't be fetched. */
    suspend fun cover(book: GutenbergBook): ByteArray? =
        covers.get(book.id) ?: gutenberg.cover(book)?.also { covers.put(book.id, it) }

    fun download(book: GutenbergBook) {
        if (_state.value.downloadingId != null) return
        val language = _state.value.language
        _state.update { it.copy(downloadingId = book.id, downloadProgress = -1f, error = null) }
        downloadJob = viewModelScope.launch {
            val target = File(File(context.filesDir, "books"), "gutenberg_${book.id}.epub")
            try {
                gutenberg.download(book, target) { progress ->
                    _state.update { it.copy(downloadProgress = progress) }
                }
                publicDownloads.save(target, listOf(book.title, book.author).filter { it.isNotBlank() }.joinToString(" - "))
                _downloaded.send(DownloadedBook(Uri.fromFile(target).toString(), language, book.author))
            } catch (e: Exception) {
                Log.w("Gutenberg", "Download of ${book.id} failed: ${e.message}")
                _state.update { it.copy(error = "No se ha podido descargar «${book.title}».") }
            } finally {
                _state.update { it.copy(downloadingId = null) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    private companion object {
        const val COVER_CACHE_SIZE = 100
    }
}

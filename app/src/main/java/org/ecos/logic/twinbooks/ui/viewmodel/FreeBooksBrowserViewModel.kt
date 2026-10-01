package org.ecos.logic.twinbooks.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
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
import org.ecos.logic.twinbooks.freebooks.FreeBooksSite
import org.ecos.logic.twinbooks.freebooks.PublicDownloads
import org.ecos.logic.twinbooks.freebooks.WebBookDownloader
import java.io.File
import javax.inject.Inject

data class FreeBooksDownloadState(
    val isDownloading: Boolean = false,
    /** 0..1, -1 = unknown size */
    val progress: Float = 0f,
    val message: String? = null,
)

/** EPUB downloads started from a page of the in-app browser (Standard Ebooks, Elejandría). */
@HiltViewModel
class FreeBooksBrowserViewModel @Inject constructor(
    private val downloader: WebBookDownloader,
    private val publicDownloads: PublicDownloads,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(FreeBooksDownloadState())
    val state = _state.asStateFlow()

    private val _downloaded = Channel<DownloadedBook>(Channel.BUFFERED)
    val downloaded = _downloaded.receiveAsFlow()

    private var job: Job? = null

    fun download(
        site: FreeBooksSite,
        url: String,
        fileName: String,
        userAgent: String?,
        cookies: String?,
        referer: String?,
    ) {
        if (_state.value.isDownloading) return
        _state.update { FreeBooksDownloadState(isDownloading = true, progress = -1f) }
        job = viewModelScope.launch {
            val target = File(File(context.filesDir, "books"), safeName(fileName))
            try {
                downloader.download(url, userAgent, cookies, referer, target) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                publicDownloads.save(target, target.name)
                _state.update { FreeBooksDownloadState() }
                _downloaded.send(DownloadedBook(Uri.fromFile(target).toString(), site.language, author = ""))
            } catch (e: Exception) {
                Log.w("FreeBooks", "Download from ${site.title} failed: ${e.message}")
                _state.update { FreeBooksDownloadState(message = "No se ha podido descargar el libro.") }
            }
        }
    }

    /** A download that isn't an EPUB (PDF, Kindle…): TwinBooks can't read it. */
    fun notEpub() {
        _state.update { it.copy(message = "TwinBooks solo lee libros EPUB. Elige la descarga en formato EPUB.") }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun cancel() {
        job?.cancel()
        _state.value = FreeBooksDownloadState()
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "libro" }
        return if (cleaned.endsWith(".epub", ignoreCase = true)) cleaned else "$cleaned.epub"
    }
}

package org.ecos.logic.twinbooks.ui.screens.bookshelf

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ecos.logic.twinbooks.freebooks.FreeBooksSite
import org.ecos.logic.twinbooks.freebooks.WebBookDownloader
import org.ecos.logic.twinbooks.ui.viewmodel.DownloadedBook
import org.ecos.logic.twinbooks.ui.viewmodel.FreeBooksBrowserViewModel

/**
 * [site] browsed inside the app. Its pages stay here, links elsewhere open in the system
 * browser, and an EPUB download goes straight to the bookshelf through [onDownloaded].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FreeBooksBrowserDialog(
    site: FreeBooksSite,
    onDownloaded: (DownloadedBook) -> Unit,
    onDismiss: () -> Unit,
    viewModel: FreeBooksBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf(site.title) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.downloaded.collect { onDownloaded(it) }
    }

    // These websites are designed for phones: show them in portrait
    OverrideScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)

    fun close() {
        viewModel.cancel()
        onDismiss()
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)
    ) {
        // Back walks the site's history first, like a browser
        BackHandler {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else close()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Atrás",
                        tint = if (canGoBack) Color.White else Color(0xFF555555)
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(site.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        pageTitle,
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "Descarga el libro en EPUB",
                    color = Color(0xFF80CBC4),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(onClick = ::close) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }
            }
            if (state.isDownloading) {
                if (state.progress >= 0f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (loadProgress < 1f) {
                LinearProgressIndicator(
                    progress = { loadProgress },
                    color = Color(0xFF555555),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val uri = request.url
                                    if (uri.scheme == "https" || uri.scheme == "http") {
                                        if (site.owns(uri.host)) return false
                                        // Other sites (social networks, ads…) in the system browser
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                        return true
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    canGoBack = view.canGoBack()
                                    view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    loadProgress = newProgress / 100f
                                }
                            }
                            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                                val fileName = WebBookDownloader.fileNameFromDisposition(contentDisposition)
                                    ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
                                if (url.startsWith("http") && WebBookDownloader.looksLikeEpub(mimeType, fileName)) {
                                    viewModel.download(
                                        site = site,
                                        url = url,
                                        fileName = fileName,
                                        userAgent = userAgent,
                                        cookies = CookieManager.getInstance().getCookie(url),
                                        referer = this.url,
                                    )
                                } else {
                                    viewModel.notEpub()
                                }
                            }
                            loadUrl(site.startUrl)
                            webView = this
                        }
                    }
                )

                state.message?.let { message ->
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(message, color = Color.White, fontSize = 14.sp, modifier = Modifier.widthIn(max = 420.dp))
                        TextButton(onClick = viewModel::clearMessage) { Text("Vale") }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}

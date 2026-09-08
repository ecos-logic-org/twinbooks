package org.ecos.logic.twinbooks.ui.screens.reader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.ReadingPosition

internal const val READING_ZONE_Y_DP = 80f
private const val PARAGRAPH_TEXT_MAX_LENGTH = 100

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BookPanel(
    book: BookContent,
    position: ReadingPosition,
    onPositionChanged: (chapterIndex: Int, scrollOffset: Int, paragraphText: String) -> Unit,
    onChapterSelected: (Int) -> Unit,
    isLeft: Boolean
) {
    var showToc by remember { mutableStateOf(false) }
    val currentChapter = book.chapters.getOrNull(position.chapterIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // Book content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (currentChapter != null) {
                ChapterWebView(
                    htmlContent = currentChapter.htmlContent,
                    scrollOffset = position.scrollOffset,
                    restoreParagraphText = position.paragraphText,
                    onScrollChanged = { offset ->
                        onPositionChanged(position.chapterIndex, offset, position.paragraphText)
                    },
                    onParagraphHighlighted = { text ->
                        onPositionChanged(position.chapterIndex, position.scrollOffset, text)
                    }
                )
            }
        }

        // Reading position indicator bar
        ReadingIndicatorBar(
            progress = position.progressPercent,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF424242))
        )

        // Bottom bar with chapter info, progress, and TOC button
        BottomInfoBar(
            chapterTitle = currentChapter?.title ?: "",
            chapterIndex = position.chapterIndex,
            totalChapters = book.totalChapters,
            progressPercent = position.progressPercent,
            onTocClick = { showToc = true }
        )
    }

    if (showToc) {
        TocDrawer(
            chapters = book.chapters.map { it.title },
            currentIndex = position.chapterIndex,
            onChapterSelected = { index ->
                onChapterSelected(index)
                showToc = false
            },
            onDismiss = { showToc = false }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChapterWebView(
    htmlContent: String,
    scrollOffset: Int,
    restoreParagraphText: String,
    onScrollChanged: (Int) -> Unit,
    onParagraphHighlighted: (String) -> Unit
) {
    val readingZoneY = READING_ZONE_Y_DP.toInt()
    val currentOnParagraphHighlighted = remember { mutableStateOf(onParagraphHighlighted) }
    currentOnParagraphHighlighted.value = onParagraphHighlighted
    val currentOnScrollChanged = remember { mutableStateOf(onScrollChanged) }
    currentOnScrollChanged.value = onScrollChanged

    val darkStyledHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                body {
                    background-color: #000000 !important;
                    color: #FFFFFF !important;
                    font-family: serif;
                    font-size: 18px;
                    line-height: 1.8;
                    padding: 16px;
                    margin: 0;
                }
                * {
                    color: #FFFFFF !important;
                    background-color: transparent !important;
                    border-color: #333333 !important;
                }
                img {
                    max-width: 100%;
                    height: auto;
                }
                a {
                    color: #90CAF9 !important;
                }
                p {
                    cursor: pointer;
                }
                .reading-zone-highlight {
                    border-left: 3px solid #42A5F5 !important;
                    background-color: rgba(255, 255, 255, 0.12) !important;
                }
            </style>
        </head>
        <body>
            $htmlContent
            <script>
            function scrollToParagraph(text) {
                if (!text) return false;
                var elements = document.querySelectorAll('p');
                for (var i = 0; i < elements.length; i++) {
                    var elText = elements[i].textContent.trim();
                    if (elText.substring(0, 100) === text.substring(0, 100)) {
                        elements[i].scrollIntoView({ behavior: 'instant', block: 'start' });
                        return true;
                    }
                }
                return false;
            }

            (function() {
                var READING_ZONE_Y = $readingZoneY;
                var highlighted = null;
                var highlightTimer = null;

                function highlightParagraph() {
                    if (highlighted) {
                        highlighted.classList.remove('reading-zone-highlight');
                        highlighted = null;
                    }

                    var elements = document.querySelectorAll('p');
                    if (elements.length === 0) return;

                    for (var i = 0; i < elements.length; i++) {
                        var rect = elements[i].getBoundingClientRect();
                        if (rect.bottom < READING_ZONE_Y - 20) continue;
                        if (rect.top > window.innerHeight) break;

                        elements[i].classList.add('reading-zone-highlight');
                        highlighted = elements[i];

                        if (window.ParagraphBridge) {
                            var text = elements[i].textContent.trim().substring(0, 100);
                            window.ParagraphBridge.onParagraphFound(text);
                        }
                        break;
                    }
                }

                function debouncedHighlight() {
                    if (highlightTimer) clearTimeout(highlightTimer);
                    highlightTimer = setTimeout(highlightParagraph, 80);
                }

                var paragraphs = document.querySelectorAll('p');
                for (var i = 0; i < paragraphs.length; i++) {
                    paragraphs[i].addEventListener('click', function(e) {
                        e.preventDefault();
                        this.scrollIntoView({ behavior: 'smooth', block: 'start' });
                        setTimeout(highlightParagraph, 300);
                    });
                }

                window.addEventListener('scroll', debouncedHighlight);
                setTimeout(highlightParagraph, 300);
                setTimeout(highlightParagraph, 600);
                setTimeout(highlightParagraph, 1200);
            })();
            </script>
        </body>
        </html>
    """.trimIndent()

    AndroidView(
        factory = { context ->
            val jsInterface = object : Any() {
                @JavascriptInterface
                fun onParagraphFound(text: String) {
                    currentOnParagraphHighlighted.value(text)
                }
            }
            WebView(context).apply {
                addJavascriptInterface(jsInterface, "ParagraphBridge")
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = false
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    defaultTextEncodingName = "UTF-8"
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
                isScrollbarFadingEnabled = true
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            if (webView.tag != htmlContent.hashCode()) {
                webView.tag = htmlContent.hashCode()
                webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    currentOnScrollChanged.value(scrollY)
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val v = view ?: return

                        if (restoreParagraphText.isNotEmpty()) {
                            val escapedText = restoreParagraphText
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", " ")
                                .replace("\r", "")
                            v.postDelayed({
                                v.evaluateJavascript("scrollToParagraph('$escapedText')") { result ->
                                    val found = result?.contains("true") == true
                                    if (!found) {
                                        v.postDelayed({
                                            v.evaluateJavascript("window.scrollTo(0, $scrollOffset);", null)
                                        }, 100)
                                    }
                                }
                            }, 100)
                        } else {
                            v.postDelayed({
                                v.evaluateJavascript("window.scrollTo(0, $scrollOffset);", null)
                            }, 100)
                            v.postDelayed({
                                v.evaluateJavascript("window.scrollTo(0, $scrollOffset);", null)
                            }, 400)
                            v.postDelayed({
                                v.evaluateJavascript("window.scrollTo(0, $scrollOffset);", null)
                            }, 900)
                        }
                    }
                }
                webView.loadDataWithBaseURL(
                    null,
                    darkStyledHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )
}

@Composable
private fun ReadingIndicatorBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress / 100f)
                .fillMaxSize()
                .background(Color(0xFF616161))
        )
    }
}

@Composable
private fun BottomInfoBar(
    chapterTitle: String,
    chapterIndex: Int,
    totalChapters: Int,
    progressPercent: Float,
    onTocClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Ch. ${chapterIndex + 1}/$totalChapters",
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = String.format("%.0f%%", progressPercent),
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        IconButton(onClick = onTocClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.List,
                contentDescription = "Table of Contents",
                tint = Color(0xFFB0B0B0)
            )
        }
    }
}

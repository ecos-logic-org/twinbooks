package org.ecos.logic.twinbooks.ui.screens.reader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    fontSize: Float = 12f,
    currentSentenceIndex: Int = -1,
    onSentenceCountChanged: (Int) -> Unit = {},
    onPrevSentence: () -> Unit = {},
    onNextSentence: () -> Unit = {},
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
    ) {
        // Book content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 30.dp)
        ) {
            if (currentChapter != null) {
                ChapterWebView(
                    htmlContent = currentChapter.htmlContent,
                    fontSize = fontSize.toInt(),
                    scrollOffset = position.scrollOffset,
                    restoreParagraphText = position.paragraphText,
                    onScrollChanged = { offset ->
                        onPositionChanged(position.chapterIndex, offset, position.paragraphText)
                    },
                    onParagraphHighlighted = { text ->
                        onPositionChanged(position.chapterIndex, position.scrollOffset, text)
                    },
                    highlightSentenceIndex = currentSentenceIndex,
                    onSentenceCountChanged = onSentenceCountChanged
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
            onTocClick = { showToc = true },
            isLeftBook = isLeft,
            onPrevSentence = onPrevSentence,
            onNextSentence = onNextSentence
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
    fontSize: Int = 12,
    scrollOffset: Int,
    restoreParagraphText: String,
    onScrollChanged: (Int) -> Unit,
    onParagraphHighlighted: (String) -> Unit,
    highlightSentenceIndex: Int = -1,
    onSentenceCountChanged: (Int) -> Unit
) {
    val readingZoneY = READING_ZONE_Y_DP.toInt()
    val currentOnParagraphHighlighted = remember { mutableStateOf(onParagraphHighlighted) }
    currentOnParagraphHighlighted.value = onParagraphHighlighted
    val currentOnScrollChanged = remember { mutableStateOf(onScrollChanged) }
    currentOnScrollChanged.value = onScrollChanged
    val currentOnSentenceCountChanged = remember { mutableStateOf(onSentenceCountChanged) }
    currentOnSentenceCountChanged.value = onSentenceCountChanged
    var lastAppliedSentenceIndex by remember { mutableStateOf(-1) }
    var lastAppliedFontSize by remember { mutableStateOf(fontSize) }

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
                    font-size: ${fontSize}px;
                    line-height: 1.2;
                    text-align: justify;
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
                .sentence-highlight {
                    text-decoration: underline !important;
                    background-color: rgba(66, 165, 245, 0.2) !important;
                    border-radius: 2px;
                    padding: 0 2px;
                }
                ::selection {
                    background-color: rgba(66, 165, 245, 0.4) !important;
                    color: #FFFFFF !important;
                }
            </style>
        </head>
        <body>
            $htmlContent
            <div id="scroll-spacer" style="height: 70vh;"></div>
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

            var currentHighlightSpan = null;

            function clearSentenceHighlight() {
                window.getSelection().removeAllRanges();
                if (currentHighlightSpan) {
                    var parent = currentHighlightSpan.parentNode;
                    while (currentHighlightSpan.firstChild) {
                        parent.insertBefore(currentHighlightSpan.firstChild, currentHighlightSpan);
                    }
                    parent.removeChild(currentHighlightSpan);
                    parent.normalize();
                    currentHighlightSpan = null;
                }
            }

            function getSentences(text) {
                var abbreviations = ['num', 'núm', 'rte', 'dr', 'dra', 'sr', 'sra', 'srta', 'prof', 'etc', 'vs', 'av', 'cfr', 'apdo', 'art', 'pág', 'pág', 'núm', 'núm'];
                var placeholder = '\x00';
                var processed = text;
                
                abbreviations.forEach(function(abbr) {
                    var regex = new RegExp('\\b' + abbr + '\\.', 'gi');
                    processed = processed.replace(regex, function(match) {
                        return match.replace('.', placeholder);
                    });
                });
                
                var raw = processed.split(/([.!?])/);
                var sentences = [];
                for (var i = 0; i < raw.length - 1; i += 2) {
                    var sentence = (raw[i] + raw[i + 1]).replace(new RegExp(placeholder, 'g'), '.');
                    if (sentence.trim().length > 0) {
                        sentences.push(sentence);
                    }
                }
                return sentences.length > 0 ? sentences : [text];
            }

            function highlightSentence(index) {
                clearSentenceHighlight();
                var paragraph = document.querySelector('.reading-zone-highlight');
                if (!paragraph) return -1;

                var text = paragraph.textContent;
                var sentences = getSentences(text);

                if (index < 0 || index >= sentences.length) return sentences.length;

                var start = 0;
                for (var i = 0; i < index; i++) start += sentences[i].length;
                var end = start + sentences[index].length;

                var walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT);
                var charCount = 0, startNode = null, startOffsetInNode = 0;
                var endNode = null, endOffsetInNode = 0;

                while (walker.nextNode()) {
                    var node = walker.currentNode;
                    var len = node.textContent.length;
                    if (!startNode && charCount + len > start) {
                        startNode = node;
                        startOffsetInNode = start - charCount;
                    }
                    if (charCount + len >= end) {
                        endNode = node;
                        endOffsetInNode = end - charCount;
                        break;
                    }
                    charCount += len;
                }

                if (startNode && endNode) {
                    try {
                        var range = document.createRange();
                        range.setStart(startNode, startOffsetInNode);
                        range.setEnd(endNode, endOffsetInNode);
                        var sel = window.getSelection();
                        sel.removeAllRanges();
                        sel.addRange(range);
                        range.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    } catch(e) {}
                }
                return sentences.length;
            }

            function getSentenceCount() {
                var paragraph = document.querySelector('.reading-zone-highlight');
                if (!paragraph) return 0;
                var sentences = getSentences(paragraph.textContent);
                return sentences.length;
            }

            (function() {
                var READING_ZONE_Y = $readingZoneY;
                var highlighted = null;
                var highlightTimer = null;

                function highlightParagraph() {
                    var elements = document.querySelectorAll('p');
                    if (elements.length === 0) return;

                    var newHighlighted = null;
                    var newHighlightedIndex = -1;
                    var scrollAtBottom = (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 10);
                    
                    for (var i = 0; i < elements.length; i++) {
                        var rect = elements[i].getBoundingClientRect();
                        var isLast = (i === elements.length - 1);

                        if (isLast && scrollAtBottom) {
                            newHighlighted = elements[i];
                            newHighlightedIndex = i;
                            break;
                        }

                        if (rect.bottom < READING_ZONE_Y - 20) continue;
                        if (rect.top > window.innerHeight) break;

                        newHighlighted = elements[i];
                        newHighlightedIndex = i;
                        break;
                    }

                    if (newHighlighted && newHighlighted !== highlighted) {
                        if (highlighted) {
                            highlighted.classList.remove('reading-zone-highlight');
                        }
                        clearSentenceHighlight();
                        highlighted = newHighlighted;
                        highlighted.classList.add('reading-zone-highlight');

                        if (window.ParagraphBridge) {
                            var text = highlighted.textContent.trim().substring(0, 100);
                            window.ParagraphBridge.onParagraphFound(text);
                            var allSentences = getSentences(highlighted.textContent);
                            window.ParagraphBridge.onSentenceCountFound(
                                allSentences.length
                            );
                            setTimeout(function() { highlightSentence(0); }, 50);
                        }
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
                        var clickedElement = this;

                        if (highlighted) {
                            highlighted.classList.remove('reading-zone-highlight');
                        }
                        clearSentenceHighlight();
                        highlighted = clickedElement;
                        highlighted.classList.add('reading-zone-highlight');

                        if (window.ParagraphBridge) {
                            var text = clickedElement.textContent.trim().substring(0, 100);
                            window.ParagraphBridge.onParagraphFound(text);
                            var allSentences = getSentences(clickedElement.textContent);
                            window.ParagraphBridge.onSentenceCountFound(allSentences.length);
                            setTimeout(function() { highlightSentence(0); }, 50);
                        }

                        clickedElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
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

                @JavascriptInterface
                fun onSentenceCountFound(count: Int) {
                    currentOnSentenceCountChanged.value(count)
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

            if (highlightSentenceIndex != lastAppliedSentenceIndex) {
                lastAppliedSentenceIndex = highlightSentenceIndex
                if (highlightSentenceIndex >= 0) {
                    webView.evaluateJavascript(
                        "highlightSentence($highlightSentenceIndex)"
                    ) { result ->
                        val count = result?.replace("\"", "")?.toIntOrNull()
                        if (count != null && count > 0) {
                            currentOnSentenceCountChanged.value(count)
                        }
                    }
                } else {
                    webView.evaluateJavascript("clearSentenceHighlight()", null)
                }
            }

            if (fontSize != lastAppliedFontSize) {
                lastAppliedFontSize = fontSize
                webView.evaluateJavascript(
                    "document.body.style.fontSize = '${fontSize}px'", null
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
    onTocClick: () -> Unit,
    isLeftBook: Boolean = false,
    onPrevSentence: () -> Unit = {},
    onNextSentence: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Ch. ${chapterIndex + 1}/$totalChapters",
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )

        if (isLeftBook) {
            IconButton(onClick = onPrevSentence) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                    contentDescription = "Previous sentence",
                    tint = Color(0xFFB0B0B0)
                )
            }
            IconButton(onClick = onNextSentence) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = "Next sentence",
                    tint = Color(0xFFB0B0B0)
                )
            }
        }

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

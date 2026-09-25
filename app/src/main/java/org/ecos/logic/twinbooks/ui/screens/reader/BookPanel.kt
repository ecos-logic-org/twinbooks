package org.ecos.logic.twinbooks.ui.screens.reader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.ecos.logic.twinbooks.R
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.ReadingPosition

internal const val READING_ZONE_Y_DP = 80f

// FontFamily con ligaduras para flechas (Fira Code)
val arrowFontFamily = FontFamily(
    Font(R.font.fira_code_regular, FontWeight.Normal, FontStyle.Normal)
)

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
    onPrevParagraph: () -> Unit = {},
    onNextParagraph: () -> Unit = {},
    onPositionChanged: (chapterIndex: Int, scrollOffset: Int, paragraphText: String) -> Unit,
    onChapterSelected: (Int) -> Unit,
    isSynchronized: Boolean = false,
    onParagraphDoubleClicked: (Int) -> Unit = {},
    onParagraphIndexChanged: (Int) -> Unit = {},
    scrollToParagraphIndex: Int? = null,
    onSentenceTextFound: (String) -> Unit = {},
    onReachedEndOfChapter: () -> Unit = {},
    ttsRefreshTrigger: Int = 0,
    ttsScrollToNextParagraphTrigger: Int = 0,
    highlightSentenceIndex: Int = -1, // For right book: highlight specific sentence during TTS
    onChapterSentences: (chapterIndex: Int, sentencesJson: String) -> Unit = { _, _ -> },
    inlineTranslationTrigger: Int = 0,
    inlineTranslationSentenceIdx: Int = -1,
    inlineTranslationText: String = ""
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
                    onParagraphHighlighted = { text, index ->
                        onPositionChanged(position.chapterIndex, position.scrollOffset, text)
                        onParagraphIndexChanged(index)
                    },
                    highlightSentenceIndex = currentSentenceIndex,
                    onSentenceCountChanged = onSentenceCountChanged,
                    onSentenceTextFound = onSentenceTextFound,
                    isSynchronized = isSynchronized,
                    onParagraphDoubleClicked = onParagraphDoubleClicked,
                    onReachedEndOfChapter = onReachedEndOfChapter,
                    scrollToParagraphIndex = scrollToParagraphIndex,
                    ttsRefreshTrigger = ttsRefreshTrigger,
                    ttsScrollToNextParagraphTrigger = ttsScrollToNextParagraphTrigger,
                    // Bind the sentence list push to this panel's current chapter
                    onChapterSentences = { json -> onChapterSentences(position.chapterIndex, json) },
                    inlineTranslationTrigger = inlineTranslationTrigger,
                    inlineTranslationSentenceIdx = inlineTranslationSentenceIdx,
                    inlineTranslationText = inlineTranslationText
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
            chapterIndex = position.chapterIndex,
            totalChapters = book.totalChapters,
            progressPercent = position.progressPercent,
            onTocClick = { showToc = true },
            onPrevSentence = onPrevSentence,
            onNextSentence = onNextSentence,
            onPrevParagraph = onPrevParagraph,
            onNextParagraph = onNextParagraph
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
    onParagraphHighlighted: (String, Int) -> Unit,
    highlightSentenceIndex: Int = -1,
    onSentenceCountChanged: (Int) -> Unit,
    onSentenceTextFound: (String) -> Unit = {},
    isSynchronized: Boolean = false,
    onParagraphDoubleClicked: (Int) -> Unit = {},
    onReachedEndOfChapter: () -> Unit = {},
    scrollToParagraphIndex: Int? = null,
    ttsRefreshTrigger: Int = 0,
    ttsScrollToNextParagraphTrigger: Int = 0,
    onChapterSentences: (sentencesJson: String) -> Unit = {},
    inlineTranslationTrigger: Int = 0,
    inlineTranslationSentenceIdx: Int = -1,
    inlineTranslationText: String = ""
) {
    val readingZoneY = READING_ZONE_Y_DP.toInt()
    val currentOnParagraphHighlighted = remember { mutableStateOf(onParagraphHighlighted) }
    currentOnParagraphHighlighted.value = onParagraphHighlighted
    val currentOnScrollChanged = remember { mutableStateOf(onScrollChanged) }
    currentOnScrollChanged.value = onScrollChanged
    val currentOnSentenceCountChanged = remember { mutableStateOf(onSentenceCountChanged) }
    currentOnSentenceCountChanged.value = onSentenceCountChanged
    val currentOnSentenceTextFound = remember { mutableStateOf(onSentenceTextFound) }
    currentOnSentenceTextFound.value = onSentenceTextFound
    val currentOnParagraphDoubleClicked = remember { mutableStateOf(onParagraphDoubleClicked) }
    currentOnParagraphDoubleClicked.value = onParagraphDoubleClicked
    val currentOnReachedEndOfChapter = remember { mutableStateOf(onReachedEndOfChapter) }
    currentOnReachedEndOfChapter.value = onReachedEndOfChapter
    val currentOnChapterSentences = remember { mutableStateOf(onChapterSentences) }
    currentOnChapterSentences.value = onChapterSentences
    var lastAppliedSentenceIndex by remember { mutableIntStateOf(-1) }
    var lastAppliedFontSize by remember { mutableIntStateOf(fontSize) }
    var lastAppliedScrollToIndex by remember { mutableStateOf<Int?>(null) }
    var lastAppliedSyncedState by remember { mutableStateOf(isSynchronized) }
    var lastAppliedTtsRefresh by remember { mutableIntStateOf(0) }
    var lastAppliedTtsScrollToNext by remember { mutableIntStateOf(0) }
    var lastAppliedHighlightSentenceIndex by remember { mutableIntStateOf(-1) }
    var lastAppliedInlineTranslation by remember { mutableIntStateOf(0) }

    val darkStyledHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                html, body {
                    background-color: #000000 !important;
                    color: #FFFFFF !important;
                    font-family: serif;
                    font-size: ${fontSize}px;
                    line-height: 1.2;
                    text-align: justify;
                    padding: 0 !important;
                    margin: 0 !important;
                    width: 100% !important;
                    overflow-x: hidden;
                }
                * {
                    color: #FFFFFF !important;
                    background-color: transparent !important;
                    border-color: #333333 !important;
                }
                div, section, article, header, footer, nav, main, aside, blockquote, figure, figcaption {
                    max-width: 100% !important;
                    width: 100% !important;
                    margin-left: 0 !important;
                    margin-right: 0 !important;
                    padding-left: 0 !important;
                    padding-right: 0 !important;
                    box-sizing: border-box !important;
                }
                p {
                    margin-top: 0 !important;
                    margin-bottom: 0.5em !important;
                    padding: 0 !important;
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
                .reading-zone-highlight-synced {
                    border-left: 3px solid #4CAF50 !important;
                    background-color: rgba(255, 255, 255, 0.12) !important;
                }
                .sentence-highlight {
                    text-decoration: underline !important;
                    background-color: rgba(66, 165, 245, 0.2) !important;
                    border-radius: 2px;
                    padding: 0 2px;
                }
                .auto-translation {
                    display: block;
                    color: #80CBC4 !important;
                    font-size: 0.92em;
                    font-style: italic;
                    margin: 2px 0 6px 0;
                    padding-left: 10px;
                    border-left: 2px solid #26A69A !important;
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
            <script src="file:///android_asset/compromise.js"></script>
            <script>
            function scrollToParagraph(text) {
                if (!text) return false;
                var elements = document.querySelectorAll('p');
                for (var i = 0; i < elements.length; i++) {
                    var elText = elements[i].textContent.trim();
                    if (elText.substring(0, 100) === text.substring(0, 100)) {
                        // Immediately highlight the target paragraph
                        if (window.highlighted) {
                            window.highlighted.classList.remove('reading-zone-highlight', 'reading-zone-highlight-synced');
                        }
                        window.clearSentenceHighlight();
                        window.highlighted = elements[i];
                        window.highlighted.classList.add('reading-zone-highlight');
                        
                        if (window.ParagraphBridge) {
                            var text = getParagraphText(window.highlighted).trim().substring(0, 100);
                            window.ParagraphBridge.onParagraphFound(text, i);
                            var allSentences = getParagraphSentences(window.highlighted);
                            window.ParagraphBridge.onSentenceCountFound(allSentences.length);
                            setTimeout(function() { window.highlightSentence(0); }, 50);
                        }
                        
                        // Scroll so the paragraph's CENTER aligns with reading zone (80dp from top)
                        var target = elements[i];
                        var rect = target.getBoundingClientRect();
                        var targetCenter = rect.top + rect.height / 2;
                        var readingZoneY = 80;
                        var scrollOffset = targetCenter - readingZoneY;
                        
                        window.scrollBy({ top: scrollOffset, behavior: 'instant' });
                        return true;
                    }
                }
                return false;
            }

            function getParagraphIndex() {
                var elements = document.querySelectorAll('p');
                var paragraph = document.querySelector('.reading-zone-highlight') || document.querySelector('.reading-zone-highlight-synced');
                if (!paragraph) return -1;
                for (var i = 0; i < elements.length; i++) {
                    if (elements[i] === paragraph) return i;
                }
                return -1;
            }

            function scrollToParagraphByIndex(index) {
                var elements = document.querySelectorAll('p');
                if (index < 0 || index >= elements.length) return false;
                
                // Immediately highlight the target paragraph
                if (window.highlighted) {
                    window.highlighted.classList.remove('reading-zone-highlight', 'reading-zone-highlight-synced');
                }
                window.clearSentenceHighlight();
                window.highlighted = elements[index];
                window.highlighted.classList.add('reading-zone-highlight');
                
                if (window.ParagraphBridge) {
                    var text = getParagraphText(window.highlighted).trim().substring(0, 100);
                    window.ParagraphBridge.onParagraphFound(text, index);
                    var allSentences = getParagraphSentences(window.highlighted);
                    window.ParagraphBridge.onSentenceCountFound(allSentences.length);
                    setTimeout(function() { window.highlightSentence(0); }, 50);
                }
                
                // Scroll so the paragraph's CENTER aligns with reading zone (80dp from top)
                // This ensures small paragraphs are properly highlighted
                var target = elements[index];
                var rect = target.getBoundingClientRect();
                var targetCenter = rect.top + rect.height / 2;
                var readingZoneY = 80; // Must match READING_ZONE_Y in highlightParagraph
                var scrollOffset = targetCenter - readingZoneY;
                
                window.scrollBy({ top: scrollOffset, behavior: 'smooth' });
                
                // Verify highlight after scroll completes
                setTimeout(window.highlightParagraph, 350);
                return true;
            }

            function setHighlightSynced(synced) {
                var el = document.querySelector('.reading-zone-highlight') || document.querySelector('.reading-zone-highlight-synced');
                if (el) {
                    el.classList.remove('reading-zone-highlight', 'reading-zone-highlight-synced');
                    el.classList.add(synced ? 'reading-zone-highlight-synced' : 'reading-zone-highlight');
                }
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
                if (typeof nlp !== 'undefined') {
                    try {
                        var doc = nlp(text);
                        var sentences = doc.sentences().out('array');
                        if (sentences.length > 0) {
                            return sentences;
                        }
                    } catch(e) {}
                }
                return [text];
            }

            // --- Inline translations (single-book mode) --------------------------------
            // Translations are inserted INSIDE the paragraph DOM, so every path that splits
            // sentences or reports paragraph text must exclude them: work on a clean clone.
            function getCleanParagraphClone(paragraph) {
                var clone = paragraph.cloneNode(true);
                var trs = clone.querySelectorAll('.auto-translation');
                for (var i = 0; i < trs.length; i++) {
                    if (trs[i].parentNode) trs[i].parentNode.removeChild(trs[i]);
                }
                return clone;
            }

            function getParagraphText(paragraph) {
                return getCleanParagraphClone(paragraph).textContent;
            }

            function getParagraphSentences(paragraph) {
                return getSentences(getParagraphText(paragraph));
            }

            // Locate the DOM range of sentences[index] inside paragraph.
            // The TreeWalker skips .auto-translation nodes so character offsets
            // (computed on the clean text) stay aligned with the real text nodes.
            function getSentenceRange(paragraph, sentences, index) {
                var text = getParagraphText(paragraph);
                var searchFrom = 0;
                var start = -1;
                for (var i = 0; i <= index; i++) {
                    var pos = text.indexOf(sentences[i], searchFrom);
                    if (pos === -1) {
                        pos = text.indexOf(sentences[i].trim(), searchFrom);
                    }
                    if (i === index) {
                        start = pos !== -1 ? pos : searchFrom;
                    }
                    searchFrom = pos !== -1 ? pos + sentences[i].length : searchFrom + sentences[i].length;
                }
                if (start === -1) return null;
                var end = start + sentences[index].length;

                var walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        var p = node.parentNode;
                        while (p && p !== paragraph) {
                            if (p.nodeType === 1 && p.classList && p.classList.contains('auto-translation')) {
                                return NodeFilter.FILTER_REJECT;
                            }
                            p = p.parentNode;
                        }
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });
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
                if (!startNode || !endNode) return null;
                return { startNode: startNode, startOffset: startOffsetInNode, endNode: endNode, endOffset: endOffsetInNode };
            }

            // Insert the automatic translation of the CURRENT paragraph right below it
            // (single-book mode). The node is a SIBLING of the <p>, so paragraph text,
            // sentence splitting and paragraph indexes are never polluted. Replaces a
            // previous translation of the same paragraph if present.
            function insertParagraphTranslation(text) {
                var paragraph = document.querySelector('.reading-zone-highlight') || document.querySelector('.reading-zone-highlight-synced');
                if (!paragraph || !paragraph.parentNode) return false;

                var prev = paragraph.nextElementSibling;
                if (prev && prev.classList && prev.classList.contains('auto-translation-para')) {
                    prev.parentNode.removeChild(prev);
                }

                var div = document.createElement('div');
                div.className = 'auto-translation auto-translation-para';
                div.textContent = text;
                paragraph.parentNode.insertBefore(div, paragraph.nextSibling);
                return true;
            }

            // Collect the compromise.js sentence list for every paragraph of this chapter
            // and push it to Kotlin. This is the SINGLE source of truth for sentence
            // splitting: TTS indices, Spanish candidates, server alignment and offsets
            // are all derived from this list.
            function collectChapterSentences() {
                var out = [];
                var paras = document.querySelectorAll('p');
                for (var i = 0; i < paras.length; i++) {
                    out.push(getParagraphSentences(paras[i]));
                }
                return out;
            }

            function sendAllSentences() {
                if (window.ParagraphBridge) {
                    try {
                        window.ParagraphBridge.onChapterSentencesFound(JSON.stringify(collectChapterSentences()));
                    } catch (e) {}
                }
            }

            function highlightSentence(index) {
                clearSentenceHighlight();
                var paragraph = document.querySelector('.reading-zone-highlight') || document.querySelector('.reading-zone-highlight-synced');
                if (!paragraph) return -1;

                var sentences = getParagraphSentences(paragraph);

                if (index < 0 || index >= sentences.length) return sentences.length;

                var r = getSentenceRange(paragraph, sentences, index);
                if (r) {
                    try {
                        var range = document.createRange();
                        range.setStart(r.startNode, r.startOffset);
                        range.setEnd(r.endNode, r.endOffset);
                        var sel = window.getSelection();
                        sel.removeAllRanges();
                        sel.addRange(range);
                        range.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    } catch(e) {}
                }

                if (window.ParagraphBridge) {
                    window.ParagraphBridge.onSentenceTextFound(sentences[index].trim());
                }

                return sentences.length;
            }

            function getSentenceCount() {
                var paragraph = document.querySelector('.reading-zone-highlight') || document.querySelector('.reading-zone-highlight-synced');
                if (!paragraph) return 0;
                return getParagraphSentences(paragraph).length;
            }

            function getSentenceText(index) {
                var paragraph = document.querySelector('.reading-zone-highlight') || document.querySelector('.reading-zone-highlight-synced');
                if (!paragraph) return '';
                var sentences = getParagraphSentences(paragraph);
                if (index < 0 || index >= sentences.length) return '';
                return sentences[index].trim();
            }

            function scrollToNextParagraph() {
                var paragraph = document.querySelector('.reading-zone-highlight') || document.querySelector('.reading-zone-highlight-synced');
                if (!paragraph) {
                    if (window.ParagraphBridge) {
                        window.ParagraphBridge.onReachedEndOfChapter();
                    }
                    return false;
                }
                var next = paragraph.nextElementSibling;
                while (next && next.tagName !== 'P') {
                    next = next.nextElementSibling;
                }
                if (next) {
                    // Immediately highlight the next paragraph
                    if (window.highlighted) {
                        window.highlighted.classList.remove('reading-zone-highlight', 'reading-zone-highlight-synced');
                    }
                    window.clearSentenceHighlight();
                    window.highlighted = next;
                    window.highlighted.classList.add('reading-zone-highlight');
                    
                    if (window.ParagraphBridge) {
                        var text = getParagraphText(window.highlighted).trim().substring(0, 100);
                        var index = window.getParagraphIndex();
                        window.ParagraphBridge.onParagraphFound(text, index);
                        var allSentences = getParagraphSentences(window.highlighted);
                        window.ParagraphBridge.onSentenceCountFound(allSentences.length);
                        setTimeout(function() { window.highlightSentence(0); }, 50);
                    }
                    
                    // Scroll so the paragraph's CENTER aligns with reading zone (80dp from top)
                    var rect = next.getBoundingClientRect();
                    var targetCenter = rect.top + rect.height / 2;
                    var readingZoneY = 80;
                    var scrollOffset = targetCenter - readingZoneY;
                    
                    window.scrollBy({ top: scrollOffset, behavior: 'smooth' });
                    
                    // Verify highlight after scroll completes
                    setTimeout(window.highlightParagraph, 350);
                    return true;
                }
                if (window.ParagraphBridge) {
                    window.ParagraphBridge.onReachedEndOfChapter();
                }
                return false;
            }

            (function() {
                var READING_ZONE_Y = $readingZoneY;
                // Use global window.highlighted for cross-function access
                window.highlighted = window.highlighted || null;
                var highlightTimer = null;
                window.isClickScrolling = window.isClickScrolling || false;

                function highlightParagraph() {
                    var elements = document.querySelectorAll('p');
                    if (elements.length === 0) return;

                    var newHighlighted = null;
                    var newHighlightedIndex = -1;
                    var scrollAtBottom = (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 10);
                    var bestDist = Infinity;

                    // Handle last paragraph at scroll bottom
                    if (scrollAtBottom && elements.length > 0) {
                        var lastEl = elements[elements.length - 1];
                        newHighlighted = lastEl;
                        newHighlightedIndex = elements.length - 1;
                    } else {
                        // Find the paragraph CLOSEST to the reading zone
                        for (var i = 0; i < elements.length; i++) {
                            var rect = elements[i].getBoundingClientRect();

                            // Skip paragraphs completely above reading zone
                            if (rect.bottom < READING_ZONE_Y - 20) continue;
                            // Stop at paragraphs completely below viewport
                            if (rect.top > window.innerHeight) break;

                            // Calculate distance from paragraph center to reading zone
                            var center = (rect.top + rect.bottom) / 2;
                            var dist = Math.abs(center - READING_ZONE_Y);

                            if (dist < bestDist) {
                                bestDist = dist;
                                newHighlighted = elements[i];
                                newHighlightedIndex = i;
                            }
                        }
                    }

                    if (newHighlighted && newHighlighted !== window.highlighted) {
                        if (window.highlighted) {
                            window.highlighted.classList.remove('reading-zone-highlight', 'reading-zone-highlight-synced');
                        }
                        clearSentenceHighlight();
                        window.highlighted = newHighlighted;
                        window.highlighted.classList.add('reading-zone-highlight');

                        if (window.ParagraphBridge) {
                            var text = getParagraphText(window.highlighted).trim().substring(0, 100);
                            var index = getParagraphIndex();
                            window.ParagraphBridge.onParagraphFound(text, index);
                            var allSentences = getParagraphSentences(window.highlighted);
                            window.ParagraphBridge.onSentenceCountFound(
                                allSentences.length
                            );
                            setTimeout(function() { highlightSentence(0); }, 50);
                        }
                    }
                }

                function debouncedHighlight() {
                    if (window.isClickScrolling) return;
                    if (highlightTimer) clearTimeout(highlightTimer);
                    highlightTimer = setTimeout(highlightParagraph, 80);
                }

                var paragraphs = document.querySelectorAll('p');
                for (var i = 0; i < paragraphs.length; i++) {
                    paragraphs[i].addEventListener('click', function(e) {
                        e.preventDefault();
                        var clickedElement = this;

                        if (window.highlighted) {
                            window.highlighted.classList.remove('reading-zone-highlight', 'reading-zone-highlight-synced');
                        }
                        clearSentenceHighlight();
                        window.highlighted = clickedElement;
                        window.highlighted.classList.add('reading-zone-highlight');

                        if (window.ParagraphBridge) {
                            var text = getParagraphText(clickedElement).trim().substring(0, 100);
                            var index = getParagraphIndex();
                            window.ParagraphBridge.onParagraphFound(text, index);
                            var allSentences = getParagraphSentences(clickedElement);
                            window.ParagraphBridge.onSentenceCountFound(allSentences.length);
                            setTimeout(function() { highlightSentence(0); }, 50);
                        }

                        window.isClickScrolling = true;
                        clickedElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
                        setTimeout(function() { window.isClickScrolling = false; }, 1500);
                    });

                    paragraphs[i].addEventListener('dblclick', function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        if (window.ParagraphBridge) {
                            var index = getParagraphIndex();
                            window.ParagraphBridge.onParagraphDoubleClicked(index);
                        }
                    });
                }

                window.addEventListener('scroll', debouncedHighlight);
                setTimeout(highlightParagraph, 300);
                setTimeout(highlightParagraph, 600);
                setTimeout(highlightParagraph, 1200);
            
            // Expose to global scope for programmatic navigation
            window.highlightParagraph = highlightParagraph;
            window.clearSentenceHighlight = clearSentenceHighlight;
            window.getSentences = getSentences;
            window.getParagraphIndex = getParagraphIndex;
            window.highlightSentence = highlightSentence;
        })();
        window.sendAllSentences = sendAllSentences;
        // Push this chapter's compromise.js sentence list to Kotlin once the DOM is ready
        setTimeout(sendAllSentences, 500);
            </script>
        </body>
        </html>
    """.trimIndent()

    @Suppress("unused")
    AndroidView(
        factory = { context ->
            val jsInterface = object : Any() {
                @JavascriptInterface
                fun onParagraphFound(text: String, index: Int) {
                    currentOnParagraphHighlighted.value(text, index)
                }

                @JavascriptInterface
                fun onSentenceCountFound(count: Int) {
                    currentOnSentenceCountChanged.value(count)
                }

                @JavascriptInterface
                fun onSentenceTextFound(text: String) {
                    currentOnSentenceTextFound.value(text)
                }

                @JavascriptInterface
                fun onParagraphDoubleClicked(index: Int) {
                    currentOnParagraphDoubleClicked.value(index)
                }

                @JavascriptInterface
                fun onReachedEndOfChapter() {
                    currentOnReachedEndOfChapter.value()
                }

                @JavascriptInterface
                fun onChapterSentencesFound(json: String) {
                    currentOnChapterSentences.value(json)
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
                    loadWithOverviewMode = true
                    useWideViewPort = false
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

                        // Safety net: push the chapter sentence list again once the page
                        // fully loaded (covers the restore-scroll path)
                        v.postDelayed({
                            v.evaluateJavascript("window.sendAllSentences && window.sendAllSentences()", null)
                        }, 1500)

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
                                            v.evaluateJavascript(
                                                "window.scrollTo(0, $scrollOffset);",
                                                null
                                            )
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

            if (scrollToParagraphIndex != null && scrollToParagraphIndex != lastAppliedScrollToIndex) {
                lastAppliedScrollToIndex = scrollToParagraphIndex
                webView.evaluateJavascript(
                    "scrollToParagraphByIndex($scrollToParagraphIndex)", null
                )
            }

            if (isSynchronized != lastAppliedSyncedState) {
                lastAppliedSyncedState = isSynchronized
                webView.evaluateJavascript(
                    "setHighlightSynced($isSynchronized)", null
                )
            }

            if (ttsRefreshTrigger != lastAppliedTtsRefresh && highlightSentenceIndex >= 0) {
                lastAppliedTtsRefresh = ttsRefreshTrigger
                webView.evaluateJavascript(
                    "highlightSentence($highlightSentenceIndex)"
                ) { result ->
                    val count = result?.replace("\"", "")?.toIntOrNull()
                    if (count != null && count > 0) {
                        currentOnSentenceCountChanged.value(count)
                    }
                }
            }

            // Highlight specific sentence in right book during TTS
            if (highlightSentenceIndex != lastAppliedHighlightSentenceIndex && highlightSentenceIndex >= 0) {
                lastAppliedHighlightSentenceIndex = highlightSentenceIndex
                webView.evaluateJavascript(
                    "highlightSentence($highlightSentenceIndex)"
                ) { result ->
                    val count = result?.replace("\"", "")?.toIntOrNull()
                    if (count != null && count > 0) {
                        currentOnSentenceCountChanged.value(count)
                    }
                }
            }

            // Insert an inline translation below the current paragraph (single-book mode).
            // Must run BEFORE the scrollToNextParagraph block so the insert targets the
            // paragraph that is still highlighted.
            if (inlineTranslationTrigger != lastAppliedInlineTranslation && inlineTranslationText.isNotBlank()) {
                lastAppliedInlineTranslation = inlineTranslationTrigger
                val escapedTranslation = inlineTranslationText
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", " ")
                    .replace("\r", "")
                webView.evaluateJavascript(
                    "insertParagraphTranslation('$escapedTranslation')", null
                )
            }

            if (ttsScrollToNextParagraphTrigger != lastAppliedTtsScrollToNext) {
                lastAppliedTtsScrollToNext = ttsScrollToNextParagraphTrigger
                webView.evaluateJavascript("scrollToNextParagraph()", null)
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

@SuppressLint("DefaultLocale")
@Composable
private fun BottomInfoBar(
    chapterIndex: Int,
    totalChapters: Int,
    progressPercent: Float,
    onTocClick: () -> Unit,
    onPrevSentence: () -> Unit = {},
    onNextSentence: () -> Unit = {},
    onPrevParagraph: () -> Unit = {},
    onNextParagraph: () -> Unit = {}
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

        Box(
            modifier = Modifier
                .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                .background(Color(0x5500FF00), RoundedCornerShape(8.dp))
                .width(56.dp)
                .height(36.dp),
        ) {
            IconButton(
                onClick = onPrevSentence,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    fontFamily = arrowFontFamily,
                    text = "<-",
                    color = Color(0xFFB0B0B0),
                    fontSize = 24.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                .background(Color(0x5500FF00), RoundedCornerShape(8.dp))
                .width(56.dp)
                .height(36.dp)
        ) {
            IconButton(
                onClick = onNextSentence,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    fontFamily = arrowFontFamily,
                    text = "->",
                    color = Color(0xFFB0B0B0),
                    fontSize = 24.sp
                )
            }
        }

        if(false) {
            // Paragraph navigation buttons
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                    .size(36.dp),
            ) {
                IconButton(
                    onClick = onPrevParagraph,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        fontFamily = arrowFontFamily,
                        text = "<|",
                        color = Color(0xFFB0B0B0),
                        fontSize = 20.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                    .size(36.dp)
            ) {
                IconButton(
                    onClick = onNextParagraph,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        fontFamily = arrowFontFamily,
                        text = "|>",
                        color = Color(0xFFB0B0B0),
                        fontSize = 20.sp
                    )
                }
            }
        }

        Text(
            text = String.format("%.0f%%", progressPercent),
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
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

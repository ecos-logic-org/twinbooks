package org.ecos.logic.twinbooks.ui.screens.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.ui.viewmodel.SessionWithCover
import kotlin.math.abs

private val SLOT_WIDTH = 150.dp
private val SLOT_GAP = 28.dp
private val SHELF_PADDING = 24.dp
private val COVER_WIDTH = 118.dp
private val COVER_HEIGHT = 177.dp
// The Spanish book of a pair stands behind the English one, shifted right and up
private val BACK_BOOK_SHIFT = 26.dp
private val BACK_BOOK_RISE = 10.dp

private val PLANK_TOP = Color(0xFF6D4C41)
private val PLANK_BOTTOM = Color(0xFF3E2723)
private val ENGLISH_PROGRESS = Color(0xFF90CAF9)
private val SINGLE_PROGRESS = Color(0xFF80CBC4)

/**
 * The user's books standing on wooden shelves: a pair is the English book in front of the
 * Spanish one, a single book stands alone. Tap opens it, long press shows details and
 * "Eliminar".
 */
@Composable
fun BookShelf(
    sessions: List<SessionWithCover>,
    onOpen: (ReadingSession) -> Unit,
    onDelete: (ReadingSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val perShelf = ((maxWidth - SHELF_PADDING * 2 + SLOT_GAP) / (SLOT_WIDTH + SLOT_GAP))
            .toInt().coerceAtLeast(1)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(sessions.chunked(perShelf), key = { row -> row.first().session.id }) { row ->
                ShelfRow(row, onOpen, onDelete)
            }
        }
    }
}

/** An empty shelf with an invitation to add the first book. */
@Composable
fun EmptyShelf(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Tu estantería está vacía",
            color = Color(0xFF9E9E9E),
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        ShelfPlank()
        Text(
            "Añade libros que ya tengas con «Añadir», o consigue libros gratis con «Descargar»",
            color = Color(0xFF666666),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 12.dp)
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun ShelfRow(
    books: List<SessionWithCover>,
    onOpen: (ReadingSession) -> Unit,
    onDelete: (ReadingSession) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.padding(horizontal = SHELF_PADDING),
            horizontalArrangement = Arrangement.spacedBy(SLOT_GAP),
            verticalAlignment = Alignment.Bottom
        ) {
            books.forEach { book ->
                BookOnShelf(book, onOpen = { onOpen(book.session) }, onDelete = { onDelete(book.session) })
            }
        }
        ShelfPlank()
        Row(
            modifier = Modifier.padding(horizontal = SHELF_PADDING, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(SLOT_GAP)
        ) {
            books.forEach { BookLabel(it.session) }
        }
    }
}

@Composable
private fun ShelfPlank() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .shadow(6.dp, RoundedCornerShape(2.dp))
            .background(Brush.verticalGradient(listOf(PLANK_TOP, PLANK_BOTTOM)), RoundedCornerShape(2.dp))
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookOnShelf(book: SessionWithCover, onOpen: () -> Unit, onDelete: () -> Unit) {
    val session = book.session
    val isPair = session.rightBookUri != null && !session.isSingleBookMode
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .width(SLOT_WIDTH)
            .height(COVER_HEIGHT + BACK_BOOK_RISE)
            .combinedClickable(onClick = onOpen, onLongClick = { showMenu = true }),
        contentAlignment = if (isPair) Alignment.BottomStart else Alignment.BottomCenter
    ) {
        if (isPair) {
            BookCover(
                dataUri = book.rightCoverImage,
                title = session.rightTitle.orEmpty(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = BACK_BOOK_SHIFT)
            )
        }
        BookCover(dataUri = book.leftCoverImage, title = session.leftTitle)

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(progressDetail("Inglés", session.leftChapterIndex, session.leftProgressPercent)) },
                onClick = {},
                enabled = false
            )
            if (isPair) {
                DropdownMenuItem(
                    text = { Text(progressDetail("Castellano", session.rightChapterIndex, session.rightProgressPercent)) },
                    onClick = {},
                    enabled = false
                )
            }
            DropdownMenuItem(
                text = { Text("Eliminar", color = Color(0xFFFF5252)) },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}

private fun progressDetail(language: String, chapterIndex: Int, percent: Float) =
    "$language: capítulo ${chapterIndex + 1} · ${percent.toInt()} %"

/** A standing book: its cover (or a coloured placeholder with the title) with a spine shade. */
@Composable
private fun BookCover(dataUri: String?, title: String, modifier: Modifier = Modifier) {
    val bitmap = rememberCoverBitmap(dataUri)
    Box(
        modifier = modifier
            .width(COVER_WIDTH)
            .height(COVER_HEIGHT)
            .shadow(8.dp, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
            .background(placeholderColor(title))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = 14.dp, end = 8.dp)
            )
        }
        // Spine: a darker strip on the left edge so it reads as a book, not a picture
        Box(
            modifier = Modifier
                .width(8.dp)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color(0x66000000), Color.Transparent)))
        )
    }
}

/** Muted colour derived from the title, so placeholder covers are told apart. */
private fun placeholderColor(title: String): Color {
    val hue = abs(title.hashCode() % 360).toFloat()
    return Color.hsl(hue, saturation = 0.35f, lightness = 0.28f)
}

@Composable
private fun BookLabel(session: ReadingSession) {
    Column(modifier = Modifier.width(SLOT_WIDTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            session.leftTitle,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        LinearProgressIndicator(
            progress = { (session.leftProgressPercent / 100f).coerceIn(0f, 1f) },
            color = if (session.isSingleBookMode) SINGLE_PROGRESS else ENGLISH_PROGRESS,
            trackColor = Color(0xFF333333),
            drawStopIndicator = {},
            modifier = Modifier
                .width(COVER_WIDTH)
                .height(3.dp)
        )
    }
}

package snd.komelia.ui.book

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.filter
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.offline.sync.model.DownloadEvent
import snd.komelia.ui.common.BookReadButton
import snd.komelia.ui.common.readIsSupported
import snd.komelia.ui.dialogs.ConfirmationDialog
import snd.komelia.ui.dialogs.permissions.DownloadNotificationRequestDialog
import snd.komelia.ui.library.SeriesScreenFilter
import snd.komelia.ui.platform.VerticalScrollbar
import snd.komelia.ui.platform.WindowSizeClass.*
import snd.komelia.ui.common.components.ExpandableText
import snd.komelia.ui.common.images.BookThumbnail
import snd.komelia.ui.common.menus.BookActionsMenu
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komelia.client.library.KomgaLibrary
import snd.komelia.client.readlist.KomgaReadList
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookScreenContent(
    library: KomgaLibrary?,
    book: KomeliaBook?,
    bookMenuActions: BookMenuActions,
    onBookReadPress: (markReadProgress: Boolean) -> Unit,
    onBookDownload: () -> Unit,
    onBookDownloadDelete: () -> Unit,
    readLists: Map<KomgaReadList, List<KomeliaBook>>,
    onReadListClick: (KomgaReadList) -> Unit,
    onReadListBookPress: (KomeliaBook, KomgaReadList) -> Unit,
    onParentSeriesPress: () -> Unit,
    onFilterClick: (SeriesScreenFilter) -> Unit,
    cardWidth: Dp
) {
    val scrollState: ScrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        if (book == null || library == null) return
        BookToolBar(book = book, bookMenuActions = bookMenuActions)

        val contentPadding = when (LocalWindowWidth.current) {
            COMPACT, MEDIUM -> Modifier.padding(5.dp)
            EXPANDED -> Modifier.padding(start = 20.dp, end = 20.dp)
            FULL -> Modifier.padding(start = 30.dp, end = 30.dp)
        }

        Box {
            Column(
                modifier = contentPadding
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    BookThumbnail(
                        book.id,
                        modifier = Modifier
                            .heightIn(min = 100.dp, max = 400.dp)
                            .widthIn(min = 300.dp, max = 500.dp)
                    )
                    BookMainInfo(
                        book = book,
                        library = library,
                        onBookReadPress = onBookReadPress,
                        onSeriesParentSeriesPress = onParentSeriesPress,
                        onDownload = onBookDownload,
                        onDownloadDelete = onBookDownloadDelete
                    )
                }
            }
            VerticalScrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
fun BookToolBar(book: KomeliaBook, bookMenuActions: BookMenuActions) {
    Row(
        modifier = Modifier.padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            book.metadata.title,
            maxLines = 2,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f, false)
        )
        ToolbarBookActions(book, bookMenuActions)
    }
}

@Composable
private fun ToolbarBookActions(book: KomeliaBook, bookMenuActions: BookMenuActions) {
    Row {
        var expandActions by remember { mutableStateOf(false) }
        IconButton(onClick = { expandActions = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        BookActionsMenu(
            book = book,
            actions = bookMenuActions,
            expanded = expandActions,
            showEditOption = false,
            showDownloadOption = false,
            onDismissRequest = { expandActions = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.BookMainInfo(
    book: KomeliaBook,
    library: KomgaLibrary,
    onBookReadPress: (markReadProgress: Boolean) -> Unit,
    onSeriesParentSeriesPress: () -> Unit,
    onDownload: () -> Unit,
    onDownloadDelete: () -> Unit
) {
    val maxWidth = when (LocalWindowWidth.current) {
        FULL -> 1200.dp
        else -> Dp.Unspecified
    }

    val context = LocalContext.current

    Column(
        modifier = Modifier.weight(1f, false).widthIn(min = 350.dp, max = maxWidth),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row {
            if (!book.deleted && !library.unavailable) {
                if (readIsSupported(book)) {
                    BookReadButton(
                        onRead = { openInReadest(context, book) },
                        onIncognitoRead = { openInReadest(context, book) }
                    )
                }
                if (!book.downloaded || book.isLocalFileOutdated) {
                    DownloadButton(book, onDownload)
                }
            }
            if (book.downloaded) {
                ElevatedButton(
                    onClick = onDownloadDelete,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text("Delete downloaded")
                }
            }
        }
        ExpandableText(
            text = book.metadata.summary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// 🔹 Duplicate-safe, Scoped Storage–safe Readest opener
fun openInReadest(context: Context, book: KomeliaBook) {
    try {
        if (!book.downloaded) {
            Toast.makeText(context, "Book not downloaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = context.getSharedPreferences("komelia_prefs", Context.MODE_PRIVATE)
        val downloadRootPath = prefs.getString("download_path", null)
        val rootDir = if (downloadRootPath != null) File(downloadRootPath) else context.getExternalFilesDir("Komelia") ?: context.filesDir

        val sanitizedBookName = book.name.replace(Regex("[^A-Za-z0-9 _\\-]"), "").replace(" ", "_")
        val matchingFiles = rootDir.listFiles()?.filter {
            it.nameWithoutExtension.equals(sanitizedBookName, ignoreCase = true)
        } ?: emptyList()

        if (matchingFiles.isEmpty()) {
            Toast.makeText(context, "Downloaded file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val file = matchingFiles.maxByOrNull { it.lastModified() }!!
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

        val mimeType = when (file.extension.lowercase()) {
            "epub" -> "application/epub+zip"
            "cbz" -> "application/vnd.comicbook+zip"
            "pdf" -> "application/pdf"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.bilingify.readest")
        }

        context.startActivity(intent)

    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Readest not installed", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Failed to open book", Toast.LENGTH_SHORT).show()
    }
}

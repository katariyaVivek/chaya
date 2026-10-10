package com.chaya.app.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaya.app.ChayaApplication
import com.chaya.app.database.BookmarkEntity
import kotlinx.coroutines.launch

/** The pages the person starred, newest first; tap one to open it, × to remove it. */
@Composable
fun BookmarksScreen(onNavigateBack: () -> Unit, onOpen: (String) -> Unit) {
    val record = (LocalContext.current.applicationContext as ChayaApplication).browsing
    val rows by remember(record) { record.bookmarkRows }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    BookmarksContent(
        rows = rows,
        // The navigation host goes back to the browser once a page is picked.
        onOpen = onOpen,
        onRemove = { url -> scope.launch { record.removeBookmark(url) } },
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksContent(
    rows: List<BookmarkEntity>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = rows.filter { query.isBlank() || it.url.contains(query.trim(), true) || it.title.contains(query.trim(), true) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (rows.isNotEmpty()) item(key = "search") { SearchField(query, onChange = { query = it }, placeholder = "Search bookmarks") }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Empty(
                        if (rows.isEmpty()) "Tap the star beside a page's address to keep it here."
                        else "No bookmark matches “${query.trim()}”",
                    )
                }
            }
            items(shown, key = { it.url }) { bookmark ->
                PageRow(
                    url = bookmark.url,
                    title = bookmark.title,
                    detail = "",
                    onOpen = { onOpen(bookmark.url) },
                    onRemove = { onRemove(bookmark.url) },
                    removeLabel = "Remove ${bookmark.title.ifBlank { bookmark.url }} from bookmarks",
                )
            }
        }
    }
}

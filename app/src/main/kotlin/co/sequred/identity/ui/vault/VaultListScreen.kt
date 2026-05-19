package co.sequred.identity.ui.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.sequred.identity.data.VaultCategory
import co.sequred.identity.data.VaultEntry
import co.sequred.identity.data.VaultSession
import co.sequred.identity.data.VaultUuid
import co.sequred.identity.ui.theme.Brand
import co.sequred.identity.ui.theme.BrandType
import co.sequred.identity.ui.theme.LocalWindowSize
import co.sequred.identity.ui.theme.SeQuredHeader
import co.sequred.identity.ui.theme.SiteLogo
import co.sequred.identity.ui.theme.WindowSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    session: VaultSession,
    onAddEntry: () -> Unit,
    onOpenEntry: (VaultUuid) -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val pendingSaves by session.pendingSaves.collectAsStateWithLifecycle()
    val unlocked = state as? VaultSession.State.Unlocked ?: return

    var selectedCategory by remember { mutableStateOf(VaultCategory.None) }
    var query by remember { mutableStateOf("") }
    val windowSize = LocalWindowSize.current
    val gutter = when (windowSize) {
        WindowSize.Compact -> 16.dp
        WindowSize.Medium -> 24.dp
        WindowSize.Expanded -> 40.dp
    }
    val contentMaxWidth = when (windowSize) {
        WindowSize.Expanded -> 720.dp
        else -> 4096.dp
    }
    val haptic = LocalHapticFeedback.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val all = unlocked.payload.entries
    val visible = all
        .filter { selectedCategory == VaultCategory.None || it.category == selectedCategory }
        .filter {
            query.isBlank() ||
                it.site.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true) ||
                (it.email?.contains(query, ignoreCase = true) == true)
        }
        .sortedBy { it.site.lowercase() }
    val recent = all.sortedByDescending { it.updatedAt.unixSeconds }.take(3)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddEntry()
                },
                containerColor = Brand.Capri,
                contentColor = Color.Black,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add entry")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = contentMaxWidth).fillMaxSize()) {

                val baseTag = if (all.isEmpty()) "Your passwords don't exist until you need them."
                              else "${all.size} ${if (all.size == 1) "credential" else "credentials"} secured · derived on demand"
                SeQuredHeader(
                    modifier = Modifier.padding(horizontal = gutter, vertical = 12.dp),
                    tagline = if (pendingSaves > 0) "$baseTag · saving…" else baseTag,
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search site or username", color = Brand.TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Brand.InputBg,
                        unfocusedContainerColor = Brand.InputBg,
                        focusedBorderColor = Brand.Capri,
                        unfocusedBorderColor = Brand.Border,
                        cursorColor = Brand.Capri,
                        focusedTextColor = Brand.TextPrimary,
                        unfocusedTextColor = Brand.TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp),
                )

                CategoryStrip(selectedCategory, { selectedCategory = it }, gutter)

                if (visible.isEmpty()) {
                    EmptyState(
                        title = if (all.isEmpty()) "No entries yet" else "No entries match",
                        body = if (all.isEmpty())
                            "Tap + to add your first credential. Passwords are derived on demand and never stored."
                        else "Try a different category or clear your search.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = gutter - 4.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Recently used pinned at top when not filtering.
                        if (query.isBlank() && selectedCategory == VaultCategory.None && recent.isNotEmpty() && all.size > recent.size) {
                            item("recent-label") {
                                Text(
                                    "RECENT",
                                    style = BrandType.sectionLabel(),
                                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(recent, key = { "recent-${it.id.value}" }) { e ->
                                SwipeableEntryRow(
                                    entry = e,
                                    onClick = { onOpenEntry(e.id) },
                                    onDelete = {
                                        session.deleteEntry(e.id)
                                        scope.launch {
                                            val r = snackbarHost.showSnackbar(
                                                "Deleted ${e.site}", actionLabel = "Undo", withDismissAction = true,
                                            )
                                            if (r == SnackbarResult.ActionPerformed) session.upsertEntry(e)
                                        }
                                    },
                                )
                            }
                            item("all-label") {
                                Text(
                                    "ALL ENTRIES",
                                    style = BrandType.sectionLabel(),
                                    modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                        }
                        items(visible, key = { it.id.value }) { e ->
                            SwipeableEntryRow(
                                entry = e,
                                onClick = { onOpenEntry(e.id) },
                                onDelete = {
                                    session.deleteEntry(e.id)
                                    scope.launch {
                                        val r = snackbarHost.showSnackbar(
                                            "Deleted ${e.site}", actionLabel = "Undo", withDismissAction = true,
                                        )
                                        if (r == SnackbarResult.ActionPerformed) session.upsertEntry(e)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryStrip(selected: VaultCategory, onSelect: (VaultCategory) -> Unit, gutter: androidx.compose.ui.unit.Dp) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Filter by category", color = Brand.TextSecondary) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Brand.InputBg,
                unfocusedContainerColor = Brand.InputBg,
                focusedBorderColor = Brand.Capri,
                unfocusedBorderColor = Brand.Border,
                focusedTextColor = Brand.TextPrimary,
                unfocusedTextColor = Brand.TextPrimary,
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Brand.Surface,
        ) {
            VaultCategory.values().forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label, color = if (c == selected) Brand.Capri else Brand.TextPrimary) },
                    onClick = { onSelect(c); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEntryRow(
    entry: VaultEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete(); true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    copyToClipboard(ctx, entry.username)
                    false   // keep the row, just bounce back
                }
                else -> false
            }
        },
        positionalThreshold = { d -> d * 0.35f },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground(dismissState.targetValue) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        EntryCard(entry = entry, onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        })
    }
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) dismissState.reset()
    }
}

@Composable
private fun SwipeBackground(target: SwipeToDismissBoxValue) {
    val (bg, icon, text, contentColor) = when (target) {
        SwipeToDismissBoxValue.EndToStart -> {
            Quad(Brand.Danger, Icons.Filled.Delete, "Delete", Color.White)
        }
        SwipeToDismissBoxValue.StartToEnd -> {
            Quad(Brand.Capri, Icons.Filled.ContentCopy, "Copy username", Color.Black)
        }
        else -> Quad(Color.Transparent, Icons.Filled.ContentCopy, "", Color.Transparent)
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 20.dp),
        contentAlignment = if (target == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Spacer(Modifier.width(8.dp))
            Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun EntryCard(entry: VaultEntry, onClick: () -> Unit) {
    Surface(
        color = Brand.Surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteLogo(site = entry.site, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.site,
                    color = Brand.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.displayId,
                    color = Brand.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.category != VaultCategory.None) {
                AssistChip(
                    onClick = onClick,
                    label = { Text(entry.category.label) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Brand.Panel,
                        labelColor = Brand.TextSecondary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Brand.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun copyToClipboard(ctx: Context?, text: String) {
    co.sequred.identity.data.ClipboardGuard.copySensitive(ctx ?: return, "identity", text)
}

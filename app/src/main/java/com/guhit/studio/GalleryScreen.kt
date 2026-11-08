package com.guhit.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guhit.studio.engine.CanvasLimits
import java.text.DateFormat
import java.util.Date

@Composable
fun GalleryScreen(
    items: List<Artwork>,
    loading: Boolean,
    onOpen: (Artwork) -> Unit,
    onCreate: (name: String, width: Int, height: Int) -> Unit,
    onRename: (Artwork, String) -> Unit,
    onDuplicate: (Artwork) -> Unit,
    onDelete: (Artwork) -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Artwork?>(null) }
    var deleteTarget by remember { mutableStateOf<Artwork?>(null) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp),
            )
            Text(
                stringResource(R.string.gallery_subtitle),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.gallery_empty),
                        modifier = Modifier.padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(items, key = { it.id }) { art ->
                        ArtworkCard(
                            art = art,
                            onOpen = { onOpen(art) },
                            onRename = { renameTarget = art },
                            onDuplicate = { onDuplicate(art) },
                            onDelete = { deleteTarget = art },
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Text(stringResource(R.string.new_canvas), modifier = Modifier.padding(horizontal = 8.dp))
        }
    }

    if (showNew) {
        NewCanvasDialog(
            onDismiss = { showNew = false },
            onCreate = { n, w, h ->
                showNew = false
                onCreate(n, w, h)
            },
        )
    }

    renameTarget?.let { art ->
        var text by remember(art.id) { mutableStateOf(art.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(art, text)
                    renameTarget = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deleteTarget?.let { art ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm, art.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(art)
                    deleteTarget = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ArtworkCard(
    art: Artwork,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = art.thumb
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = art.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Blank canvas that has not been saved yet
                    Box(
                        Modifier
                            .fillMaxSize()
                            .aspectRatio(art.width.toFloat() / art.height)
                            .background(Color.White),
                    )
                }
            }
            Row(
                Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(art.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        "${art.width}×${art.height} · " +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(art.modifiedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text(stringResource(R.string.menu_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.duplicate)) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

private data class SizePreset(val label: String, val w: Int, val h: Int)

@Composable
private fun NewCanvasDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, width: Int, height: Int) -> Unit,
) {
    val dm = LocalContext.current.resources.displayMetrics
    val presets = remember {
        listOf(
            SizePreset("Parisukat", 2048, 2048),
            SizePreset("Screen ng device", minOf(dm.widthPixels, 4096), minOf(dm.heightPixels, 4096)),
            SizePreset("Full HD (landscape)", 1920, 1080),
            SizePreset("Instagram portrait", 1080, 1350),
            SizePreset("A4 (150 dpi)", 1240, 1754),
            SizePreset("Poster", 3000, 4000),
            SizePreset("4K", 3840, 2160),
        )
    }
    var name by remember { mutableStateOf("") }
    var w by remember { mutableStateOf("2048") }
    var h by remember { mutableStateOf("2048") }
    val valid = CanvasLimits.isValid(w.toIntOrNull(), h.toIntOrNull())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_canvas)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.canvas_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                presets.forEach { p ->
                    val selected = w == p.w.toString() && h == p.h.toString()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { w = p.w.toString(); h = p.h.toString() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { w = p.w.toString(); h = p.h.toString() })
                        Text("${p.label}  (${p.w}×${p.h})", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { w = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.width_px)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = h,
                        onValueChange = { h = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.height_px)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.canvas_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onCreate(name, w.toInt(), h.toInt()) },
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

package com.guhit.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guhit.studio.engine.BlendMode
import com.guhit.studio.engine.Layer
import kotlin.math.roundToInt

/** Layer list (top layer first) + options for the selected layer. Meant to live in a BottomSheetPanel(scroll = false). */
@Composable
fun LayersPanel(
    layers: List<Layer>,
    activeId: Int,
    maxLayers: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onUpdate: (Int, (Layer) -> Layer) -> Unit,
    onDuplicate: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMergeDown: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val sel = layers.firstOrNull { it.id == activeId }
    val selIndex = layers.indexOfFirst { it.id == activeId }

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMerge by remember { mutableStateOf(false) }
    var blendMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.layers_title, layers.size, maxLayers),
                modifier = Modifier.weight(1f).padding(start = 6.dp),
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onAdd) { Text(stringResource(R.string.new_layer)) }
        }

        layers.asReversed().forEach { l ->
            val selected = l.id == activeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(l.id) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onUpdate(l.id) { it.copy(visible = !it.visible) } }) {
                    Icon(
                        painterResource(if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off),
                        contentDescription = null,
                        tint = if (l.visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        l.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    val tags = layerTags(l)
                    if (tags.isNotEmpty()) {
                        Text(tags, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (sel != null) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Text(
                stringResource(R.string.layer_opacity, (sel.opacity * 100).roundToInt()),
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = sel.opacity,
                onValueChange = { v -> onUpdate(sel.id) { it.copy(opacity = v) } },
                valueRange = 0f..1f,
                modifier = Modifier.padding(horizontal = 6.dp),
            )

            Box2 {
                TextButton(onClick = { blendMenu = true }) {
                    Text(stringResource(R.string.layer_blend, sel.blend.label))
                }
                DropdownMenu(expanded = blendMenu, onDismissRequest = { blendMenu = false }) {
                    BlendMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label, fontWeight = if (m == sel.blend) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                blendMenu = false
                                onUpdate(sel.id) { it.copy(blend = m) }
                            },
                        )
                    }
                }
            }

            SwitchRow(stringResource(R.string.alpha_lock), sel.alphaLock) { v -> onUpdate(sel.id) { it.copy(alphaLock = v) } }
            SwitchRow(stringResource(R.string.clipping_mask), sel.clip) { v -> onUpdate(sel.id) { it.copy(clip = v) } }
            SwitchRow(stringResource(R.string.lock_layer), sel.locked) { v -> onUpdate(sel.id) { it.copy(locked = v) } }

            Row {
                TextButton(onClick = { onMove(sel.id, +1) }, enabled = selIndex < layers.size - 1) {
                    Text(stringResource(R.string.move_up))
                }
                TextButton(onClick = { onMove(sel.id, -1) }, enabled = selIndex > 0) {
                    Text(stringResource(R.string.move_down))
                }
            }
            Row {
                TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.rename)) }
                TextButton(onClick = { onDuplicate(sel.id) }) { Text(stringResource(R.string.duplicate)) }
            }
            Row {
                TextButton(onClick = { confirmMerge = true }, enabled = selIndex > 0) {
                    Text(stringResource(R.string.merge_down))
                }
                TextButton(onClick = { confirmDelete = true }, enabled = layers.size > 1) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }

    if (renaming && sel != null) {
        var text by remember(sel.id) { mutableStateOf(sel.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val n = text.trim()
                    if (n.isNotEmpty()) onUpdate(sel.id) { it.copy(name = n) }
                    renaming = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete && sel != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_layer_confirm, sel.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(sel.id)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmMerge && sel != null) {
        AlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text(stringResource(R.string.merge_down)) },
            text = { Text(stringResource(R.string.merge_confirm, sel.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmMerge = false
                    onMergeDown(sel.id)
                }) { Text(stringResource(R.string.merge_down)) }
            },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun layerTags(l: Layer): String {
    val parts = ArrayList<String>()
    if (l.blend != BlendMode.NORMAL) parts.add(l.blend.label)
    if (l.opacity < 0.995f) parts.add("${(l.opacity * 100).roundToInt()}%")
    if (l.alphaLock) parts.add(stringResource(R.string.alpha_lock))
    if (l.clip) parts.add(stringResource(R.string.clipping_mask))
    if (l.locked) parts.add(stringResource(R.string.lock_layer))
    return parts.joinToString(" · ")
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** Box without importing foundation's Box just to anchor a DropdownMenu. */
@Composable
private fun Box2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box { content() }
}

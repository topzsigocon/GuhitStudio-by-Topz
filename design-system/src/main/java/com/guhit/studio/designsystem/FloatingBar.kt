package com.guhit.studio.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Top bar: back / undo / redo / ... / menu. Full width, sits at the very top. */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GuhitColors.Navy900.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Row(
                modifier = Modifier.statusBarsPadding().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
            // thin gold hairline under the bar
            Box(Modifier.fillMaxWidth().height(1.dp).background(GuhitColors.Gold.copy(alpha = 0.35f)))
        }
    }
}

/** Bottom tool row: equal-width tool buttons. */
@Composable
fun BottomToolbar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GuhitColors.Navy900.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(GuhitColors.Gold.copy(alpha = 0.35f)))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Icon (slot) over a small label. `selected` = gold. */
@Composable
fun RowScope.ToolbarButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    val tint = if (selected) GuhitColors.Gold else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .weight(1f)
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) GuhitColors.Gold.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { icon(tint) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/**
 * Bottom sheet that slides above the toolbar. Content scrolls inside unless the content manages its
 * own scrolling (pass scroll = false).
 */
@Composable
fun BottomSheetPanel(
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.58f).dp
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = GuhitColors.Navy900,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.border(1.dp, GuhitColors.Gold.copy(alpha = 0.25f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))) {
            // grabber
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GuhitColors.Gold.copy(alpha = 0.5f)),
            )
            val inner = Modifier.heightIn(max = maxHeight).padding(horizontal = 14.dp, vertical = 8.dp)
            Column(
                modifier = if (scroll) inner.verticalScroll(rememberScrollState()) else inner,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

/** Pill slider with a gold fill, a label on the left and a value on the right. value in 0..1. */
@Composable
fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val shape = RoundedCornerShape(18.dp)
    val textStyle = MaterialTheme.typography.labelMedium.copy(
        color = Color.White,
        shadow = Shadow(Color.Black, Offset(0f, 1f), 4f),
    )
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(GuhitColors.Navy800)
            .border(1.dp, GuhitColors.Navy700, shape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (change.pressed) {
                            currentOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0.03f, 1f))
                .background(Brush.horizontalGradient(listOf(GuhitColors.GoldDark, GuhitColors.Gold))),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = textStyle)
            Text(valueText, style = textStyle)
        }
    }
}

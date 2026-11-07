package com.guhit.studio

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.guhit.studio.designsystem.FloatingBar
import com.guhit.studio.engine.CanvasSurfaceView

@Composable
fun CanvasScreen() {
    val context = LocalContext.current
    val canvas = remember { CanvasSurfaceView(context) }
    var barVisible by remember { mutableStateOf(true) }

    DisposableEffect(canvas) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> canvas.onPause()
                Lifecycle.Event.ON_RESUME -> canvas.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { canvas }, modifier = Modifier.fillMaxSize())

        // Collapsible top panel: the canvas stays the star.
        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = barVisible) {
                FloatingBar {
                    TextButton(onClick = canvas::resetView) {
                        Text(stringResource(R.string.reset_view))
                    }
                }
            }
            Surface(
                onClick = { barVisible = !barVisible },
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = if (barVisible) "\u25B2" else "\u25BC",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

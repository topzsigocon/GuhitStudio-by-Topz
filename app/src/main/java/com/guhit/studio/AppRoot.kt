package com.guhit.studio

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private sealed interface Screen {
    data object Gallery : Screen
    data class Editor(val art: Artwork) : Screen
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Gallery) }
    var items by remember { mutableStateOf<List<Artwork>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun refresh() {
        Thread {
            val list = ArtworkRepository.list(context)
            main.post {
                items = list
                loading = false
            }
        }.start()
    }

    LaunchedEffect(Unit) { refresh() }

    when (val s = screen) {
        Screen.Gallery -> GalleryScreen(
            items = items,
            loading = loading,
            onOpen = { screen = Screen.Editor(it) },
            onCreate = { name, w, h ->
                screen = Screen.Editor(ArtworkRepository.create(context, name, w, h))
            },
            onRename = { art, name ->
                ArtworkRepository.rename(context, art, name)
                refresh()
            },
            onDuplicate = { art ->
                Thread {
                    ArtworkRepository.duplicate(context, art)
                    main.post { refresh() }
                }.start()
            },
            onDelete = { art ->
                ArtworkRepository.delete(context, art)
                refresh()
            },
        )
        is Screen.Editor -> CanvasScreen(
            artwork = s.art,
            onClose = {
                screen = Screen.Gallery
                refresh()
            },
        )
    }
}

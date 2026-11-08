package com.guhit.studio

import androidx.compose.ui.graphics.ImageBitmap

data class Artwork(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val thumb: ImageBitmap? = null,
)

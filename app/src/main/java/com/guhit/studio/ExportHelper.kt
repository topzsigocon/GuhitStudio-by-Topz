package com.guhit.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.nio.ByteBuffer

enum class ExportFormat(val mime: String) {
    PNG_TRANSPARENT("image/png"),
    PNG_WHITE("image/png"),
    JPEG("image/jpeg"),
}

object ExportHelper {
    /** Encodes premultiplied RGBA canvas pixels and writes them to [uri]. Call off the UI thread. */
    fun save(context: Context, uri: Uri, pixels: ByteArray, width: Int, height: Int, format: ExportFormat): Boolean {
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var flat: Bitmap? = null
        return try {
            src.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            val out = if (format == ExportFormat.PNG_TRANSPARENT) {
                src
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { b ->
                    flat = b
                    val c = Canvas(b)
                    c.drawColor(Color.WHITE)
                    c.drawBitmap(src, 0f, 0f, null)
                }
            }
            val compressFormat = if (format == ExportFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                out.compress(compressFormat, 95, stream)
            } ?: false
        } catch (e: Exception) {
            false
        } finally {
            src.recycle()
            flat?.recycle()
        }
    }
}

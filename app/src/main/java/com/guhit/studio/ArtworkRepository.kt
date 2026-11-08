package com.guhit.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offline storage. One folder per artwork under filesDir/artworks/<id>/:
 *   meta.json      name, size, dates (fast gallery listing)
 *   canvas.guhit   ZIP: manifest.json + layer0.rgba (premultiplied RGBA8, lossless)
 *   canvas.guhit.bak  previous good save
 *   thumb.png      gallery thumbnail
 * All methods except [create]/[rename]/[delete] may be slow: call them off the UI thread.
 */
object ArtworkRepository {
    private const val CANVAS_FILE = "canvas.guhit"
    private const val BACKUP_FILE = "canvas.guhit.bak"
    private const val LAYER_ENTRY = "layer0.rgba"

    private fun root(c: Context) = File(c.filesDir, "artworks").also { it.mkdirs() }
    private fun dir(c: Context, id: String) = File(root(c), id)

    fun list(c: Context): List<Artwork> =
        (root(c).listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { readArtwork(it) }
            .sortedByDescending { it.modifiedAt }

    fun create(c: Context, name: String, width: Int, height: Int): Artwork {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifEmpty { "Walang Pamagat ${list(c).size + 1}" }
        val d = dir(c, id)
        d.mkdirs()
        writeMeta(d, finalName, width, height, now, now)
        return Artwork(id, finalName, width, height, now, now)
    }

    fun rename(c: Context, art: Artwork, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        writeMeta(dir(c, art.id), name, art.width, art.height, art.createdAt, System.currentTimeMillis())
    }

    fun duplicate(c: Context, art: Artwork) {
        val newId = UUID.randomUUID().toString()
        val dst = dir(c, newId)
        dir(c, art.id).copyRecursively(dst, overwrite = true)
        val now = System.currentTimeMillis()
        writeMeta(dst, art.name + " (kopya)", art.width, art.height, now, now)
    }

    fun delete(c: Context, art: Artwork) {
        dir(c, art.id).deleteRecursively()
    }

    /** Saves premultiplied RGBA pixels (width*height*4) plus thumbnail. */
    fun save(c: Context, art: Artwork, pixels: ByteArray) {
        val d = dir(c, art.id)
        d.mkdirs()
        val tmp = File(d, "$CANVAS_FILE.tmp")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { z ->
            z.setLevel(1) // fast: autosave must not hog the CPU
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifest(art).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry(LAYER_ENTRY))
            z.write(pixels)
            z.closeEntry()
        }
        val dest = File(d, CANVAS_FILE)
        if (dest.exists()) dest.renameTo(File(d, BACKUP_FILE))
        tmp.renameTo(dest)

        val thumb = makeThumb(pixels, art.width, art.height)
        val thumbTmp = File(d, "thumb.png.tmp")
        FileOutputStream(thumbTmp).use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        thumb.recycle()
        thumbTmp.renameTo(File(d, "thumb.png"))

        writeMeta(d, art.name, art.width, art.height, art.createdAt, System.currentTimeMillis())
    }

    /** Null = blank/new artwork (or unreadable files). */
    fun loadPixels(c: Context, art: Artwork): ByteArray? {
        val d = dir(c, art.id)
        return readLayer(File(d, CANVAS_FILE), art) ?: readLayer(File(d, BACKUP_FILE), art)
    }

    // ------------------------------------------------------------------ internals

    private fun manifest(art: Artwork): String = JSONObject().apply {
        put("format", "guhit")
        put("version", 1)
        put("width", art.width)
        put("height", art.height)
        put("colorSpace", "sRGB")
        put("pixelFormat", "RGBA8_PREMULTIPLIED")
        put("layers", JSONArray().put(JSONObject().put("file", LAYER_ENTRY).put("name", "Layer 1")))
    }.toString()

    private fun readLayer(f: File, art: Artwork): ByteArray? {
        if (!f.exists()) return null
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { z ->
                var out: ByteArray? = null
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name != LAYER_ENTRY) continue
                    val n = art.width * art.height * 4
                    val buf = ByteArray(n)
                    var off = 0
                    while (off < n) {
                        val r = z.read(buf, off, n - off)
                        if (r < 0) break
                        off += r
                    }
                    if (off == n) out = buf
                }
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(d: File, name: String, w: Int, h: Int, created: Long, modified: Long) {
        val json = JSONObject()
            .put("name", name).put("width", w).put("height", h)
            .put("createdAt", created).put("modifiedAt", modified)
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(d, "meta.json"))
    }

    private fun readArtwork(d: File): Artwork? = try {
        val m = JSONObject(File(d, "meta.json").readText())
        val tf = File(d, "thumb.png")
        val thumb = if (tf.exists()) BitmapFactory.decodeFile(tf.path)?.asImageBitmap() else null
        Artwork(
            d.name, m.getString("name"), m.getInt("width"), m.getInt("height"),
            m.getLong("createdAt"), m.getLong("modifiedAt"), thumb,
        )
    } catch (e: Exception) {
        null
    }

    private fun makeThumb(px: ByteArray, w: Int, h: Int): Bitmap {
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(ByteBuffer.wrap(px))
        val scale = 320f / max(w, h)
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())
        var cur = full
        while (cur.width > tw * 2 && cur.height > th * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== full) cur.recycle()
            cur = next
        }
        val scaled = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== full && cur !== scaled) cur.recycle()
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== full) scaled.recycle()
        full.recycle()
        return out
    }
}

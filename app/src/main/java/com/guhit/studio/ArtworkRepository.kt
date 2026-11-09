package com.guhit.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.guhit.studio.engine.BlendMode
import com.guhit.studio.engine.Layer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
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
 *   meta.json         name, size, dates (fast gallery listing)
 *   canvas.guhit      ZIP (format v2): manifest.json first, then layer<ID>.rgba per layer
 *   canvas.guhit.bak  previous good save
 *   thumb.png         gallery thumbnail
 * Layers are streamed one at a time (load and save) so memory stays at ~1 layer, not N.
 * Files written by v0.3 (format v1: single layer0.rgba) are still readable.
 */
object ArtworkRepository {
    private const val CANVAS_FILE = "canvas.guhit"
    private const val BACKUP_FILE = "canvas.guhit.bak"

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

    // ------------------------------------------------------------------ loading

    /** Streams a saved artwork. Null = nothing saved yet (new artwork) or unreadable files. */
    fun openLoad(c: Context, art: Artwork): LoadSession? {
        val d = dir(c, art.id)
        return tryOpen(File(d, CANVAS_FILE), art) ?: tryOpen(File(d, BACKUP_FILE), art)
    }

    class LoadSession internal constructor(
        private val zip: ZipInputStream,
        val width: Int,
        val height: Int,
        val layers: List<Layer>,
        val activeId: Int,
        private val entryToId: Map<String, Int>,
    ) : Closeable {
        /** Next layer's pixels, or null at the end. A truncated layer comes back as a blank one. */
        fun next(): Pair<Int, ByteArray>? {
            while (true) {
                val e = zip.nextEntry ?: return null
                val id = entryToId[e.name] ?: continue
                val n = width * height * 4
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = zip.read(buf, off, n - off)
                    if (r < 0) break
                    off += r
                }
                return id to buf
            }
        }

        override fun close() = zip.close()
    }

    private fun tryOpen(f: File, art: Artwork): LoadSession? {
        if (!f.exists()) return null
        var zip: ZipInputStream? = null
        return try {
            zip = ZipInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16))
            val first = zip.nextEntry
            if (first == null || first.name != "manifest.json") {
                zip.close()
                return null
            }
            val m = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
            val arr = m.getJSONArray("layers")
            val layers = ArrayList<Layer>()
            val map = HashMap<String, Int>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id", i + 1)
                layers.add(
                    Layer(
                        id = id,
                        name = o.optString("name", "Layer ${i + 1}"),
                        visible = o.optBoolean("visible", true),
                        opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                        blend = BlendMode.fromId(o.optInt("blend", 0)),
                        alphaLock = o.optBoolean("alphaLock", false),
                        clip = o.optBoolean("clip", false),
                        locked = o.optBoolean("locked", false),
                    ),
                )
                map[o.getString("file")] = id
            }
            if (layers.isEmpty()) {
                zip.close()
                return null
            }
            LoadSession(zip, art.width, art.height, layers, m.optInt("activeLayer", layers.last().id), map)
        } catch (e: Exception) {
            try { zip?.close() } catch (_: Exception) {}
            null
        }
    }

    // ------------------------------------------------------------------ saving

    /** Opens a new save. Writes the manifest immediately; then call [SaveSession.writeLayer] per layer. */
    fun beginSave(c: Context, art: Artwork, layers: List<Layer>, activeId: Int): SaveSession =
        SaveSession(c, art, layers, activeId)

    class SaveSession internal constructor(
        private val c: Context,
        private val art: Artwork,
        private val layers: List<Layer>,
        activeId: Int,
    ) {
        private val d = File(c.filesDir, "artworks/${art.id}").also { it.mkdirs() }
        private val tmp = File(d, "$CANVAS_FILE.tmp")
        private val zip = ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16))

        init {
            zip.setLevel(1) // fast: autosave must not hog the CPU
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest(art, layers, activeId).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        fun writeLayer(layer: Layer, pixels: ByteArray) {
            zip.putNextEntry(ZipEntry(entryName(layer.id)))
            zip.write(pixels)
            zip.closeEntry()
        }

        /** Finishes the zip, rotates files (previous save becomes .bak), refreshes thumbnail + meta. */
        fun commit(flat: ByteArray?) {
            zip.close()
            val dest = File(d, CANVAS_FILE)
            if (dest.exists()) dest.renameTo(File(d, BACKUP_FILE))
            tmp.renameTo(dest)
            if (flat != null) {
                val thumb = makeThumb(flat, art.width, art.height)
                val thumbTmp = File(d, "thumb.png.tmp")
                FileOutputStream(thumbTmp).use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
                thumb.recycle()
                thumbTmp.renameTo(File(d, "thumb.png"))
            }
            writeMeta(d, art.name, art.width, art.height, art.createdAt, System.currentTimeMillis())
        }

        fun abort() {
            try { zip.close() } catch (_: Exception) {}
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun entryName(id: Int) = "layer$id.rgba"

    private fun manifest(art: Artwork, layers: List<Layer>, activeId: Int): String = JSONObject().apply {
        put("format", "guhit")
        put("version", 2)
        put("width", art.width)
        put("height", art.height)
        put("colorSpace", "sRGB")
        put("pixelFormat", "RGBA8_PREMULTIPLIED")
        put("activeLayer", activeId)
        put(
            "layers",
            JSONArray().also { arr ->
                layers.forEach { l ->
                    arr.put(
                        JSONObject()
                            .put("id", l.id).put("file", entryName(l.id)).put("name", l.name)
                            .put("visible", l.visible).put("opacity", l.opacity.toDouble())
                            .put("blend", l.blend.id).put("alphaLock", l.alphaLock)
                            .put("clip", l.clip).put("locked", l.locked),
                    )
                }
            },
        )
    }.toString()

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

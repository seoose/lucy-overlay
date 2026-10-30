package com.syseo.lucyoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect

/**
 * Codex pet v2 spritesheet: 8 cols × 11 rows.
 * Loaded downsampled to keep memory stable when many pets are active.
 */
class SpriteSheet private constructor(
    private val sheet: Bitmap,
    val cellW: Int,
    val cellH: Int
) {
    enum class Motion(val row: Int, val frames: Int) {
        IDLE(0, 6),
        RUN_RIGHT(1, 8),
        RUN_LEFT(2, 8),
        WAVE(3, 4),
        JUMP(4, 5),
        FAILED(5, 8),
        WAITING(6, 6),
        WORK(7, 6),
        REVIEW(8, 6)
    }

    private val frameCache = HashMap<Long, Bitmap>()
    @Volatile private var recycled = false

    fun frame(motion: Motion, index: Int): Bitmap {
        check(!recycled) { "SpriteSheet recycled" }
        val i = index.mod(motion.frames)
        val key = (motion.row.toLong() shl 32) or i.toLong()
        return frameCache.getOrPut(key) {
            val src = Rect(i * cellW, motion.row * cellH, (i + 1) * cellW, (motion.row + 1) * cellH)
            val out = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(sheet, src, Rect(0, 0, cellW, cellH), null)
            out
        }
    }

    fun recycle() {
        if (recycled) return
        recycled = true
        frameCache.values.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        frameCache.clear()
        if (!sheet.isRecycled) sheet.recycle()
    }

    companion object {
        private const val COLS = 8
        private const val ROWS = 11

        fun load(context: Context, petId: String): SpriteSheet {
            context.assets.open("pets/$petId/spritesheet.png").use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    // Full sheet is 1536×2288; half-res is enough for overlay size.
                    inSampleSize = 2
                }
                val bmp = BitmapFactory.decodeStream(stream, null, opts)
                    ?: error("Failed to decode spritesheet for $petId")
                val cellW = bmp.width / COLS
                val cellH = bmp.height / ROWS
                require(cellW > 0 && cellH > 0) { "Bad spritesheet size for $petId: ${bmp.width}x${bmp.height}" }
                return SpriteSheet(bmp, cellW, cellH)
            }
        }
    }
}

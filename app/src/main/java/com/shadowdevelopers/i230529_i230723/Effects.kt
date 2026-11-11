package com.shadowdevelopers.i230529_i230723

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Photo effects (filters) for the Camera and the Story editor. Each effect is a colour matrix:
 * it can be shown live on an ImageView (colorFilter) and is baked into the picture before it is uploaded.
 */
object Effects {
    const val NORMAL = "Normal"
    val NAMES = listOf(NORMAL, "Black & white", "Sepia", "Warm", "Cool", "Vivid", "Fade")

    fun matrix(name: String?): ColorMatrix? = when (name) {
        "Black & white" -> ColorMatrix().apply { setSaturation(0f) }
        "Sepia" -> ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        "Warm" -> ColorMatrix(floatArrayOf(
            1.1f, 0f, 0f, 0f, 12f,
            0f, 1.0f, 0f, 0f, 4f,
            0f, 0f, 0.85f, 0f, -8f,
            0f, 0f, 0f, 1f, 0f))
        "Cool" -> ColorMatrix(floatArrayOf(
            0.88f, 0f, 0f, 0f, -6f,
            0f, 1.0f, 0f, 0f, 2f,
            0f, 0f, 1.15f, 0f, 14f,
            0f, 0f, 0f, 1f, 0f))
        "Vivid" -> ColorMatrix().apply { setSaturation(1.6f) }
        "Fade" -> ColorMatrix(floatArrayOf(
            0.8f, 0f, 0f, 0f, 40f,
            0f, 0.8f, 0f, 0f, 40f,
            0f, 0f, 0.8f, 0f, 40f,
            0f, 0f, 0f, 1f, 0f))
        else -> null
    }

    fun colorFilter(name: String?): ColorMatrixColorFilter? = matrix(name)?.let { ColorMatrixColorFilter(it) }

    /**
     * Returns a new JPEG file with the effect applied (long edge at most 1600 px), or null if nothing is needed
     * or the picture could not be read. The original is not changed.
     */
    fun bake(context: android.content.Context, source: Uri, name: String?, cacheDir: File): File? {
        val matrix = matrix(name) ?: return null
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            val decoded = resolver.openInputStream(source).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val rotation = resolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val upright = if (rotation != 0) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true) else decoded
            val output = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(upright, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
            val file = File.createTempFile("kinnect_fx_", ".jpg", cacheDir)
            FileOutputStream(file).use { output.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file
        } catch (_: Exception) {
            null
        }
    }
}

package com.shadowdevelopers.i230529_i230723

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.auth.FirebaseAuth
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Saves one photo or short video in the Realtime Database as base64 text, in its own node:
 *   media/{mediaId} = { type: "image" | "video", data: "<base64>", thumb: "<base64 JPEG, videos only>", owner, createdAt }
 * and gives back the reference "db:{mediaId}". Posts, stories, messages and profiles store only that short
 * reference, so their lists stay light; MediaLoader fetches the media node when a picture is actually shown.
 * No other account or service is needed. Only this file and MediaLoader know where media lives.
 *
 * Photos are shrunk (long edge 1024 px, JPEG q70 or lower) to roughly 100-300 KB. Videos are limited to
 * 3 MB (about 5-10 seconds) and audio to 2 MB, because the database is not made for large files.
 */
object MediaUploader {
    const val IMAGE = "image"
    const val VIDEO = "video"
    /** Voice messages and story music (small audio clips, up to 2 MB). */
    const val AUDIO = "audio"
    private const val MAX_IMAGE_EDGE = 1024
    private const val MAX_IMAGE_BYTES = 900 * 1024
    private const val MAX_VIDEO_BYTES = 3L * 1024 * 1024
    private const val MAX_VIDEO_MS = 31_000L
    private const val MAX_AUDIO_BYTES = 2L * 1024 * 1024

    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Always true now: media is stored in Firebase itself, so there is nothing to configure. */
    fun isConfigured() = true

    /** Encodes on a background thread; every callback is delivered on the main thread. */
    fun upload(
        context: Context,
        uri: Uri,
        kind: String,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me == null) {
            onError("Please log in again")
            return
        }
        val appContext = context.applicationContext
        worker.execute {
            try {
                val node = mutableMapOf<String, Any>("type" to kind, "owner" to me, "createdAt" to mapOf(".sv" to "timestamp"))
                if (kind == VIDEO) {
                    checkVideo(appContext, uri)
                    node["data"] = Base64.encodeToString(readVideo(appContext, uri), Base64.NO_WRAP)
                    videoThumbnail(appContext, uri)?.let { node["thumb"] = it }
                } else if (kind == AUDIO) {
                    node["data"] = Base64.encodeToString(readAudio(appContext, uri), Base64.NO_WRAP)
                } else {
                    node["data"] = Base64.encodeToString(compressImage(appContext, uri), Base64.NO_WRAP)
                }
                val reference = FirebaseData.database.getReference("media").push()
                val id = reference.key ?: throw IllegalStateException("Could not create an id for the upload")
                main.post {
                    onProgress(50)
                    reference.setValue(node)
                        .addOnSuccessListener { onProgress(100); onSuccess(MediaLoader.refFor(id)) }
                        .addOnFailureListener { onError(Forms.message(it, "Upload failed. Check your connection and try again.")) }
                }
            } catch (error: OutOfMemoryError) {
                main.post { onError("That file is too large to upload") }
            } catch (error: Exception) {
                main.post { onError(error.message ?: "Upload failed") }
            }
        }
    }

    /** Decodes a small enough bitmap, fixes the EXIF rotation, scales the long edge to 1024 px, then JPEG. */
    private fun compressImage(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file is not an image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        var bitmap = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Could not read the image")

        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (rotation != 0 || scale < 1f) {
            val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        // Lower the quality step by step if a photo is still too big (the database is not meant for big files).
        var quality = 70
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 15
        } while (bytes.size > MAX_IMAGE_BYTES && quality >= 25)
        return bytes
    }

    private fun checkVideo(context: Context, uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(duration <= MAX_VIDEO_MS) { "Videos can be at most 30 seconds long" }
        } finally {
            retriever.release()
        }
        val size = fileSize(context, uri)
        require(size <= MAX_VIDEO_BYTES) { "That video is too big to save. Choose a short clip (about 5-10 seconds, under 3 MB)." }
    }

    private fun readVideo(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Could not read the video")
        require(bytes.size <= MAX_VIDEO_BYTES) { "That video is too big to save. Choose a short clip (about 5-10 seconds, under 3 MB)." }
        return bytes
    }

    private fun readAudio(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Could not read the audio")
        require(bytes.size <= MAX_AUDIO_BYTES) { "That audio is too big (max 2 MB). Choose a shorter clip." }
        return bytes
    }

    /** First frame of a video as a small base64 JPEG (for the story tile on Home). */
    private fun videoThumbnail(context: Context, uri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0) ?: return null
            val scale = 320f / maxOf(frame.width, frame.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
            val out = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun fileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }
}

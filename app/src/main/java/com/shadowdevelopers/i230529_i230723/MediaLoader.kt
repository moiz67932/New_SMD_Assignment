package com.shadowdevelopers.i230529_i230723

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import com.bumptech.glide.Glide
import java.io.File
import java.util.concurrent.Executors

/**
 * Shows stored media. A media reference is either
 *   "db:{mediaId}"  -> the base64 text in media/{mediaId} (see MediaUploader), or
 *   anything else   -> a normal URL / content URI, loaded by Glide as before.
 * Pictures read from the database are decoded once and kept in a small memory cache.
 */
object MediaLoader {
    private const val PREFIX = "db:"
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    /** Callers waiting for a download that is already running (main thread only). */
    private val waiting = mutableMapOf<String, MutableList<(ByteArray?) -> Unit>>()

    fun refFor(mediaId: String) = PREFIX + mediaId
    fun isDb(ref: String) = ref.startsWith(PREFIX)
    private fun idOf(ref: String) = ref.removePrefix(PREFIX)

    fun load(view: ImageView, ref: String?) = show(view, ref, "data")

    /** Story tile for a video: its saved first frame. */
    fun loadVideoThumbnail(view: ImageView, ref: String?) = show(view, ref, "thumb")

    /** Legacy: servers that turn video URLs into pictures by changing the extension. */
    private fun videoThumbnail(ref: String): String = ref.substringBeforeLast('.') + ".jpg"

    private fun show(view: ImageView, ref: String?, field: String) {
        val token = if (ref.isNullOrBlank()) null else "$ref#$field"
        view.setTag(R.id.mediaRefTag, token) // lets a recycled row ignore an answer meant for its previous content
        if (ref.isNullOrBlank()) {
            runCatching { Glide.with(view).clear(view) }
            view.setImageResource(R.drawable.media_placeholder)
            return
        }
        if (!isDb(ref)) {
            runCatching {
                Glide.with(view).load(if (field == "thumb") videoThumbnail(ref) else ref)
                    .placeholder(R.drawable.media_placeholder).error(R.drawable.media_error).centerCrop().into(view)
            }
            return
        }
        runCatching { Glide.with(view).clear(view) }
        view.setImageResource(R.drawable.media_placeholder)
        fetch(ref, field) { bytes ->
            if (view.getTag(R.id.mediaRefTag) != token) return@fetch
            if (bytes == null) {
                view.setImageResource(R.drawable.media_error)
                return@fetch
            }
            runCatching {
                Glide.with(view).load(bytes).placeholder(R.drawable.media_placeholder).error(R.drawable.media_error).centerCrop().into(view)
            }
        }
    }

    /** Gives Glide something it can load: the bytes for a database picture, otherwise the ref itself (URL / URI). */
    fun resolveModel(ref: String, field: String = "data", onReady: (Any?) -> Unit) {
        if (!isDb(ref)) onReady(ref) else fetch(ref, field) { onReady(it) }
    }

    /** A playable address for a video: a local cache file for a database video, otherwise the ref itself. */
    fun videoUri(context: Context, ref: String, onReady: (Uri?) -> Unit) = fileUri(context, ref, ".mp4", onReady)

    /** Same for audio (voice messages, story music). */
    fun audioUri(context: Context, ref: String, onReady: (Uri?) -> Unit) = fileUri(context, ref, ".m4a", onReady)

    /** Database media is written once to a cache file so MediaPlayer / VideoView can play it. */
    private fun fileUri(context: Context, ref: String, extension: String, onReady: (Uri?) -> Unit) {
        if (!isDb(ref)) {
            onReady(Uri.parse(ref))
            return
        }
        val folder = File(context.applicationContext.cacheDir, "media_files").also { it.mkdirs() }
        val file = File(folder, idOf(ref) + extension)
        if (file.exists() && file.length() > 0) {
            onReady(Uri.fromFile(file))
            return
        }
        fetch(ref, "data", useCache = false) { bytes ->
            if (bytes == null) {
                onReady(null)
                return@fetch
            }
            decoder.execute {
                runCatching { file.writeBytes(bytes) }
                main.post { onReady(if (file.exists() && file.length() > 0) Uri.fromFile(file) else null) }
            }
        }
    }

    /** Reads media/{id}/{field}, decodes the base64 off the main thread, and answers on the main thread. */
    private fun fetch(ref: String, field: String, useCache: Boolean = true, onDone: (ByteArray?) -> Unit) {
        val key = "$ref#$field"
        if (useCache) cache.get(key)?.let { onDone(it); return }
        val callbacks = waiting.getOrPut(key) { mutableListOf() }
        callbacks.add(onDone)
        if (callbacks.size > 1) return // the same download is already running
        FirebaseData.database.getReference("media").child(idOf(ref)).child(field).get().addOnCompleteListener { task ->
            val text = if (task.isSuccessful) task.result.getValue(String::class.java) else null
            decoder.execute {
                val bytes = text?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                main.post {
                    if (bytes != null && useCache) cache.put(key, bytes)
                    waiting.remove(key)?.forEach { it(bytes) }
                }
            }
        }
    }
}

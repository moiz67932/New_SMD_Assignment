package com.shadowdevelopers.i230529_i230723

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Camera (09): a live camera inside Kinnect (CameraX).
 *  - tap the shutter for a photo, hold it for a short video (about 8 s, so it fits in the database)
 *  - flash (torch), front/back switch, self-timer, grid lines, filters (photos), gallery
 *  - modes: STORY (to the Story editor), POST (photo for a post), TEXT (a story made of words on a colour),
 *    LIVE (start a live video), BOOMERANG (a 2 s clip that loops in the story)
 * When another screen opens this one with "for_post" (Create post, Chat), the picture is returned to it.
 */
class CameraActivity : AppCompatActivity() {
    private enum class Mode { STORY, POST, TEXT, LIVE, BOOMERANG }

    private val forPost by lazy { intent.getBooleanExtra("for_post", false) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var mode = Mode.STORY
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordStartedAt = 0L
    private var front = false
    private var torch = false
    private var timerSeconds = 0
    private var gridOn = false
    private var effect = Effects.NORMAL
    private var busy = false
    private var holdActive = false
    private var textColor = Color.parseColor("#0B5F63")
    private lateinit var preview: PreviewView
    private val holdToRecord = Runnable { holdActive = true; startRecording(boomerang = false) }
    private val recordClock = object : Runnable {
        override fun run() {
            if (recording == null) return
            val seconds = ((System.currentTimeMillis() - recordStartedAt) / 1000).toInt()
            findViewById<TextView>(R.id.cameraRecordBadge).text = "● REC ${TimeFormat.duration(seconds)}"
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        preview = findViewById(R.id.cameraPreview)
        // COMPATIBLE lets a colour filter be shown live on the picture (it uses a TextureView).
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        setupShutter()
        findViewById<View>(R.id.galleryButton).setOnClickListener {
            startActivityForResult(Intent(this, PhotoPickerActivity::class.java)
                .putExtra("single", !(forPost || mode == Mode.POST))
                .putExtra("images_only", forPost || mode == Mode.POST), REQUEST_GALLERY)
        }
        findViewById<View>(R.id.cameraFlashButton).setOnClickListener { toggleTorch() }
        findViewById<View>(R.id.cameraSwitchButton).setOnClickListener {
            if (recording != null) return@setOnClickListener
            front = !front
            torch = false
            styleToggles()
            bindCamera()
        }
        findViewById<View>(R.id.cameraEffectsButton).setOnClickListener { chooseEffect() }
        findViewById<View>(R.id.cameraSettingsButton).setOnClickListener { showSettings() }
        mapOf(R.id.modeStory to Mode.STORY, R.id.modePost to Mode.POST, R.id.modeText to Mode.TEXT, R.id.modeLive to Mode.LIVE, R.id.modeBoomerang to Mode.BOOMERANG)
            .forEach { (id, value) ->
                findViewById<View>(id).setOnClickListener {
                    if (recording != null) return@setOnClickListener
                    if (forPost && value != Mode.POST) {
                        Toast.makeText(this, "Take a photo for your post here", Toast.LENGTH_SHORT).show()
                    } else setMode(value)
                }
            }
        setupTextComposer()
        setMode(if (forPost) Mode.POST else Mode.STORY)
        showLatestPhoto()
        if (hasCamera()) startCamera() else requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSIONS)
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMicrophone() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasCamera()) startCamera()
        else showHint("Allow the camera in Settings to use it here, or use the gallery button", permanent = true)
    }

    // ----- Camera -----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bindCamera()
            } catch (error: Exception) {
                Toast.makeText(this, "The camera could not start. Use the gallery button instead.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Shows the live picture and prepares photo + video capture (falls back to photo only on older phones). */
    private fun bindCamera() {
        val cameraProvider = provider ?: return
        cameraProvider.unbindAll()
        camera = null
        if (mode == Mode.TEXT || !hasCamera()) return
        val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val video = VideoCapture.withOutput(recorder)
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            camera = cameraProvider.bindToLifecycle(this, selector, previewUseCase, photo, video)
            imageCapture = photo
            videoCapture = video
        } catch (_: Exception) {
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, selector, previewUseCase, photo)
                imageCapture = photo
                videoCapture = null
            } catch (error: Exception) {
                Toast.makeText(this, "The camera could not start. Use the gallery button instead.", Toast.LENGTH_LONG).show()
                return
            }
        }
        camera?.cameraControl?.enableTorch(torch)
        applyPreviewFilter()
    }

    private fun toggleTorch() {
        val current = camera
        if (current == null) {
            Toast.makeText(this, "The camera is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (!current.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "This camera has no flash", Toast.LENGTH_SHORT).show()
            return
        }
        torch = !torch
        current.cameraControl.enableTorch(torch)
        styleToggles()
    }

    /** Flash and effects buttons turn white when they are on. */
    private fun styleToggles() {
        listOf(R.id.cameraFlashButton to torch, R.id.cameraEffectsButton to (effect != Effects.NORMAL)).forEach { (id, on) ->
            findViewById<ImageView>(id).apply {
                setBackgroundResource(if (on) R.drawable.circle_white else R.drawable.circle_dark)
                setColorFilter(if (on) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun chooseEffect() {
        AlertDialog.Builder(this).setTitle("Filter for photos")
            .setItems(Effects.NAMES.toTypedArray()) { _, which ->
                effect = Effects.NAMES[which]
                styleToggles()
                applyPreviewFilter()
            }.show()
    }

    /** Shows the chosen filter live on the camera picture. */
    private fun applyPreviewFilter() {
        val filter = Effects.colorFilter(effect)
        if (filter == null) preview.setLayerType(View.LAYER_TYPE_NONE, null)
        else preview.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = filter })
    }

    private fun showSettings() {
        val grid = if (gridOn) "Grid lines: on (tap to turn off)" else "Grid lines: off (tap to turn on)"
        val choices = arrayOf(
            (if (timerSeconds == 0) "● " else "") + "Self-timer: off",
            (if (timerSeconds == 3) "● " else "") + "Self-timer: 3 seconds",
            (if (timerSeconds == 10) "● " else "") + "Self-timer: 10 seconds",
            grid
        )
        AlertDialog.Builder(this).setTitle("Camera settings").setItems(choices) { _, which ->
            when (which) {
                0 -> timerSeconds = 0
                1 -> timerSeconds = 3
                2 -> timerSeconds = 10
                else -> {
                    gridOn = !gridOn
                    val overlay = findViewById<FrameLayout>(R.id.cameraOverlay)
                    overlay.removeAllViews()
                    if (gridOn) overlay.addView(GridLines(this))
                }
            }
        }.show()
    }

    /** Rule-of-thirds lines over the camera picture. */
    private class GridLines(context: Context) : View(context) {
        private val paint = Paint().apply { color = 0x66FFFFFF; strokeWidth = 2f }
        override fun onDraw(canvas: Canvas) {
            val w = width / 3f
            val h = height / 3f
            for (i in 1..2) {
                canvas.drawLine(w * i, 0f, w * i, height.toFloat(), paint)
                canvas.drawLine(0f, h * i, width.toFloat(), h * i, paint)
            }
        }
    }

    // ----- Shutter: tap = photo (or the mode's action), hold = video -----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        findViewById<View>(R.id.shutterButton).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    holdActive = false
                    if (mode == Mode.STORY && recording == null && !busy) main.postDelayed(holdToRecord, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdToRecord)
                    if (holdActive || recording != null) {
                        holdActive = false
                        stopRecording()
                    } else if (event.action == MotionEvent.ACTION_UP) onShutterTap()
                }
            }
            true
        }
    }

    private fun onShutterTap() {
        if (busy) return
        when (mode) {
            Mode.STORY, Mode.POST -> withTimer { takePhoto() }
            Mode.BOOMERANG -> withTimer { startRecording(boomerang = true) }
            Mode.LIVE -> startActivity(Intent(this, LiveActivity::class.java).putExtra("host", true))
            Mode.TEXT -> Unit
        }
    }

    private fun withTimer(action: () -> Unit) {
        if (timerSeconds == 0) {
            action()
            return
        }
        busy = true
        var left = timerSeconds
        val countdown = findViewById<TextView>(R.id.cameraCountdown)
        val tick = object : Runnable {
            override fun run() {
                if (left == 0) {
                    countdown.visibility = View.GONE
                    busy = false
                    action()
                } else {
                    countdown.visibility = View.VISIBLE
                    countdown.text = left.toString()
                    left--
                    main.postDelayed(this, 1000)
                }
            }
        }
        main.post(tick)
    }

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "The camera is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        val file = File.createTempFile("kinnect_photo_", ".jpg", cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = front })
            .build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // Bake the filter into the picture off the main thread, then continue.
                worker.execute {
                    val result = Effects.bake(applicationContext, Uri.fromFile(file), effect, cacheDir) ?: file
                    main.post { busy = false; deliver(Uri.fromFile(result), MediaUploader.IMAGE, boomerang = false) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                busy = false
                Toast.makeText(this@CameraActivity, "Could not take the photo. Try again.", Toast.LENGTH_LONG).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(boomerang: Boolean) {
        val capture = videoCapture
        if (capture == null) {
            Toast.makeText(this, "This phone cannot record video here. Use the gallery button.", Toast.LENGTH_LONG).show()
            return
        }
        if (recording != null) return
        if (forPost) {
            Toast.makeText(this, "Posts can only have photos", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File.createTempFile("kinnect_video_", ".mp4", cacheDir)
        // Short clips only: videos are stored in the database, so they are capped at about 2.9 MB.
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(if (boomerang) 2000L else 8000L)
            .setFileSizeLimit(2_900_000L)
            .build()
        var pending = capture.output.prepareRecording(this, options)
        if (hasMicrophone() && !boomerang) pending = pending.withAudioEnabled()
        recordStartedAt = System.currentTimeMillis()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) onRecordingFinished(event, file, boomerang)
        }
        showRecording(true)
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun onRecordingFinished(event: VideoRecordEvent.Finalize, file: File, boomerang: Boolean) {
        recording = null
        showRecording(false)
        val fine = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
            event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        if (!fine || !file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Could not record. Hold the shutter a little longer.", Toast.LENGTH_SHORT).show()
            return
        }
        deliver(Uri.fromFile(file), MediaUploader.VIDEO, boomerang)
    }

    private fun showRecording(active: Boolean) {
        findViewById<View>(R.id.cameraRecordBadge).visibility = if (active) View.VISIBLE else View.GONE
        findViewById<View>(R.id.shutterInner).setBackgroundResource(if (active || mode == Mode.LIVE) R.drawable.circle_red else R.drawable.circle_white)
        if (active) main.post(recordClock)
    }

    // ----- Modes -----

    private fun setMode(value: Mode) {
        mode = value
        val tabs = listOf(
            Triple(R.id.modeTextLabel, R.id.modeTextLine, Mode.TEXT), Triple(R.id.modeLiveLabel, R.id.modeLiveLine, Mode.LIVE),
            Triple(R.id.modeStoryLabel, R.id.modeStoryLine, Mode.STORY), Triple(R.id.modePostLabel, R.id.modePostLine, Mode.POST),
            Triple(R.id.modeBoomerangLabel, R.id.modeBoomerangLine, Mode.BOOMERANG)
        )
        tabs.forEach { (labelId, lineId, tab) ->
            val selected = tab == value
            findViewById<TextView>(labelId).apply {
                setTextColor(if (selected) Color.WHITE else 0xCCFFFFFF.toInt())
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
            findViewById<View>(lineId).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
        findViewById<View>(R.id.textComposer).visibility = if (value == Mode.TEXT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.shutterInner).setBackgroundResource(if (value == Mode.LIVE) R.drawable.circle_red else R.drawable.circle_white)
        showHint(
            when (value) {
                Mode.STORY -> "Tap the shutter for a photo\nHold it for a short video"
                Mode.POST -> "Tap the shutter to take a photo for your post"
                Mode.TEXT -> ""
                Mode.LIVE -> "Tap the shutter to go live to your friends"
                Mode.BOOMERANG -> "Tap the shutter for a 2 second looping clip"
            }, permanent = false
        )
        if (provider != null) bindCamera()
    }

    private fun showHint(text: String, permanent: Boolean) {
        val hint = findViewById<TextView>(R.id.cameraHint)
        hint.text = text
        hint.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        main.removeCallbacksAndMessages(HINT_TOKEN)
        if (!permanent && text.isNotBlank()) main.postAtTime({ hint.visibility = View.GONE }, HINT_TOKEN, SystemClock.uptimeMillis() + 3000)
    }

    // ----- TEXT mode: words on a colour become a story picture -----

    private fun setupTextComposer() {
        val colors = listOf("#0B5F63", "#C44C29", "#4E5898", "#2F7359", "#D18D08", "#765184", "#202124")
        val row = findViewById<LinearLayout>(R.id.textComposerColors)
        val density = resources.displayMetrics.density
        colors.forEach { hex ->
            val chip = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex)); setStroke((2 * density).toInt(), Color.WHITE) }
                layoutParams = LinearLayout.LayoutParams((34 * density).toInt(), (34 * density).toInt()).apply { marginStart = (6 * density).toInt(); marginEnd = (6 * density).toInt() }
                setOnClickListener {
                    textColor = Color.parseColor(hex)
                    findViewById<View>(R.id.textComposer).setBackgroundColor(textColor)
                }
            }
            row.addView(chip)
        }
        val input = findViewById<EditText>(R.id.textComposerInput)
        findViewById<View>(R.id.textComposerNext).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isBlank()) {
                Forms.error(input, "Type something for your story")
                return@setOnClickListener
            }
            Forms.hideKeyboard(this)
            deliver(Uri.fromFile(renderTextStory(text)), MediaUploader.IMAGE, boomerang = false)
        }
        Forms.onKeyboardAction(input) { Forms.hideKeyboard(this) }
    }

    /** Draws the words, centred, on a 1080x1920 picture in the chosen colour. */
    private fun renderTextStory(text: String): File {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(textColor)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 84f; typeface = Typeface.DEFAULT_BOLD }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 160).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        canvas.save()
        canvas.translate(80f, (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        val file = File.createTempFile("kinnect_text_", ".jpg", cacheDir)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    // ----- Gallery thumbnail and results -----

    /** The gallery button shows the newest photo on the phone, when gallery permission was already given. */
    private fun showLatestPhoto() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return
        try {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val button = findViewById<ImageView>(R.id.galleryButton)
                    button.setPadding(0, 0, 0, 0)
                    button.imageTintList = null
                    Glide.with(this).load(uri).circleCrop().into(button)
                }
            }
        } catch (_: Exception) {
            // Keep the plain gallery icon.
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_GALLERY || resultCode != RESULT_OK) return
        val uris = data?.getStringArrayListExtra("media_uris").orEmpty()
        if (uris.isEmpty()) return
        val types = data?.getStringArrayListExtra("media_types").orEmpty()
        if (forPost || mode == Mode.POST) deliverToPost(uris)
        else deliver(Uri.parse(uris.first()), types.firstOrNull() ?: MediaUploader.IMAGE, boomerang = false)
    }

    /** Sends the picture or clip on: back to Create post / Chat, or to Create post (POST mode), or to the Story editor. */
    private fun deliver(uri: Uri, type: String, boomerang: Boolean) {
        if (forPost || mode == Mode.POST) {
            deliverToPost(listOf(uri.toString()))
            return
        }
        startActivity(Intent(this, StoryEditorActivity::class.java)
            .putExtra("media_uri", uri.toString())
            .putExtra("media_type", type)
            .putExtra("boomerang", boomerang))
    }

    private fun deliverToPost(uris: List<String>) {
        if (forPost) {
            setResult(RESULT_OK, Intent().putExtra("media_uri", uris.first()).putStringArrayListExtra("media_uris", ArrayList(uris)))
        } else {
            startActivity(Intent(this, CreatePostActivity::class.java).putStringArrayListExtra("media_uris", ArrayList(uris)))
        }
        finish()
    }

    override fun onStop() {
        main.removeCallbacks(holdToRecord)
        if (recording != null) stopRecording()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 817
        private const val REQUEST_GALLERY = 812
        private val HINT_TOKEN = Any()
    }
}

package com.shadowdevelopers.i230529_i230723

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Chat (21) with one person. chats/{chatId} holds members, messages, lastMessage, unread/{uid},
 * lastRead/{uid} (for the "seen" dot) and typing/{uid}. Hearts live in messageReactions/{chatId}/{messageId}/{uid}.
 * Edit and delete are offered only on my own messages for 5 minutes after sending (server-adjusted time);
 * the database rules enforce the same 5 minutes on the server.
 */
class ChatActivity : AppCompatActivity() {
    private val currentUser by lazy { FirebaseAuth.getInstance().currentUser }
    private val me by lazy { currentUser?.uid.orEmpty() }
    private var otherUid = ""
    private var personName = "Kinnect member"
    private var chatId = ""
    private var chatReady = false
    private var started = false
    private var visible = false
    private val listeners = ListenerBag()
    private var replyingTo: ChatMessage? = null
    private var messages: List<ChatMessage> = emptyList()
    private var hearts: Map<String, Set<String>> = emptyMap()
    private var otherLastRead = 0L
    private var otherTyping = false
    private var isFriend = false
    private val handler = Handler(Looper.getMainLooper())
    private val stopTyping = Runnable { setTyping(false) }
    private lateinit var replyLabel: TextView
    private lateinit var input: EditText
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var recycler: RecyclerView
    private val screenshotDetector by lazy { ScreenshotDetector(this) { reportScreenshot() } }
    private var recorder: android.media.MediaRecorder? = null
    private var voiceFile: java.io.File? = null
    private var voiceStartedAt = 0L
    private val voiceClock = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val seconds = ((System.currentTimeMillis() - voiceStartedAt) / 1000).toInt()
            findViewById<TextView>(R.id.chatRecordTime).text = "● Recording ${TimeFormat.duration(seconds)} · tap the mic to send"
            handler.postDelayed(this, 500)
        }
    }

    private val chat get() = FirebaseData.database.getReference("chats").child(chatId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        personName = intent.getStringExtra("person_name") ?: "Kinnect member"
        otherUid = intent.getStringExtra("person_uid").orEmpty()
        replyLabel = findViewById(R.id.chatReplyLabel)
        input = findViewById(R.id.messageInput)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        if (otherUid.isBlank() || me.isBlank() || otherUid == me) {
            Toast.makeText(this, "Choose a real user profile to start a chat", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<View>(R.id.callButton).setOnClickListener { startCall(false) }
        findViewById<View>(R.id.videoCallButton).setOnClickListener { startCall(true) }
        findViewById<View>(R.id.sendMessageButton).setOnClickListener { sendMessage() }
        findViewById<View>(R.id.chatReplyCancel).setOnClickListener { clearReply() }
        findViewById<View>(R.id.chatInfoButton).setOnClickListener {
            startActivity(Intent(this, OtherProfileActivity::class.java).putExtra("person_uid", otherUid))
        }
        // + and the gallery icon open the picker (photos and short videos); the camera icon takes a photo.
        listOf(R.id.chatAttachmentButton, R.id.chatImageButton).forEach { viewId ->
            findViewById<View>(viewId).setOnClickListener {
                startActivityForResult(Intent(this, PhotoPickerActivity::class.java).putExtra("single", true), PICK_IMAGE)
            }
        }
        findViewById<View>(R.id.chatCameraButton).setOnClickListener {
            startActivityForResult(Intent(this, CameraActivity::class.java).putExtra("for_post", true), TAKE_PHOTO)
        }
        // Voice message: tap the mic to start, tap it again to send (the x cancels).
        findViewById<View>(R.id.chatVoiceButton).setOnClickListener { if (recorder == null) startVoice() else sendVoice() }
        findViewById<View>(R.id.chatRecordCancel).setOnClickListener { cancelVoice() }
        findViewById<View>(R.id.chatEmojiButton).setOnClickListener {
            EmojiPicker.show(this) { emoji -> input.append(emoji) }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Empty input shows the thumbs-up (sends 👍), otherwise the send arrow.
                findViewById<ImageView>(R.id.sendMessageButton).setImageResource(if (s.isNullOrBlank()) R.drawable.ic_thumb_up else R.drawable.ic_send)
                if (!s.isNullOrBlank()) {
                    setTyping(true)
                    handler.removeCallbacks(stopTyping)
                    handler.postDelayed(stopTyping, 4000)
                }
            }
        })

        messageAdapter = MessageAdapter(me, onLongPress = ::showMessageOptions, onDoubleTap = ::toggleHeart)
        recycler = findViewById(R.id.messagesRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = messageAdapter
        messageAdapter.other = UserProfile(otherUid, personName)
        render()

        chatId = ChatSender.chatId(me, otherUid)
        ChatSender.ensureChat(me, otherUid, onReady = {
            chatReady = true
            askScreenshotPermissionOnce()
            if (started) attach()
        }, onError = { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        })
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (chatReady) attach()
        screenshotDetector.start()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        openChatWith = otherUid
        markRead()
    }

    override fun onPause() {
        visible = false
        if (openChatWith == otherUid) openChatWith = null
        super.onPause()
    }

    override fun onStop() {
        cancelVoice()
        AudioPlayer.stop()
        started = false
        setTyping(false)
        listeners.removeAll()
        screenshotDetector.stop()
        super.onStop()
    }

    /** All live data for this conversation; removed again in onStop. */
    private fun attach() {
        listeners.removeAll()
        listeners.value(FirebaseData.profiles.child(otherUid)) { snapshot ->
            val profile = ProfileCache.from(snapshot)
            ProfileCache.put(profile)
            personName = profile.name
            findViewById<TextView>(R.id.chatToolbarName).text = profile.name
            AvatarBinder.bind(findViewById(R.id.chatToolbarAvatar), profile.name, profile.photo)
            messageAdapter.other = profile
            render()
        }
        listeners.value(FirebaseData.database.getReference("presence").child(otherUid)) { snapshot ->
            val online = snapshot.child("online").getValue(Boolean::class.java) == true
            findViewById<View>(R.id.chatToolbarOnline).visibility = if (online) View.VISIBLE else View.GONE
            val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
            findViewById<TextView>(R.id.chatToolbarStatus).text = KinnectApplication.presenceLabel(online, lastSeen)
        }
        listeners.value(FirebaseData.friends.child(me).child(otherUid)) { snapshot ->
            isFriend = snapshot.getValue(Boolean::class.java) == true
            render()
        }
        listeners.value(chat.child("messages").orderByChild("timestamp").limitToLast(200), onError = {
            Toast.makeText(this, Forms.message(it), Toast.LENGTH_LONG).show()
        }) { snapshot ->
            messages = snapshot.children.mapNotNull { parse(it) }
            render()
            markRead()
        }
        listeners.value(FirebaseData.messageReactions.child(chatId)) { snapshot ->
            hearts = snapshot.children.associate { message -> message.key.orEmpty() to message.children.filter { it.getValue(String::class.java) == "heart" }.mapNotNull { it.key }.toSet() }
            render()
        }
        listeners.value(chat.child("lastRead").child(otherUid)) { snapshot ->
            otherLastRead = snapshot.getValue(Long::class.java) ?: 0L
            render()
        }
        listeners.value(chat.child("typing").child(otherUid)) { snapshot ->
            otherTyping = snapshot.getValue(Boolean::class.java) == true
            render()
        }
    }

    private fun parse(item: DataSnapshot): ChatMessage? {
        val senderId = item.child("senderId").getValue(String::class.java) ?: return null
        val text = { key: String -> item.child(key).getValue(String::class.java).orEmpty() }
        val type = item.child("type").getValue(String::class.java) ?: "text"
        return ChatMessage(
            id = item.key.orEmpty(),
            senderId = senderId,
            text = text("text"),
            timestamp = item.child("timestamp").getValue(Long::class.java) ?: 0L,
            edited = item.child("edited").getValue(Boolean::class.java) == true,
            replyToText = text("replyToText"),
            system = type == "system",
            type = type,
            imageRef = text("imageRef"),
            videoRef = text("videoRef"),
            audioRef = text("audioRef"),
            durationSec = item.child("durationSec").getValue(Int::class.java) ?: 0,
            sharedPostId = text("sharedPostId"),
            sharedAuthorName = text("sharedAuthorName"),
            sharedSnippet = text("sharedSnippet"),
            sharedImage = text("sharedImage"),
            replyToSenderId = text("replyToSenderId")
        )
    }

    /** Reset my unread counter and record when I last read, but only while this chat is on screen. */
    private fun markRead() {
        if (!visible || !chatReady) return
        chat.child("unread").child(me).setValue(0)
        chat.child("lastRead").child(me).setValue(mapOf(".sv" to "timestamp"))
    }

    private fun setTyping(typing: Boolean) {
        if (!chatReady) return
        val reference = chat.child("typing").child(me)
        if (typing) {
            reference.onDisconnect().removeValue()
            reference.setValue(true)
        } else {
            handler.removeCallbacks(stopTyping)
            reference.removeValue()
        }
    }

    /** Builds the list: intro card, messages with date breaks / grouping / hearts / seen dot, typing bubble. */
    private fun render() {
        val rows = mutableListOf<ChatRow>(ChatRow.Intro)
        val myLastSeen = messages.lastOrNull { it.senderId == me && it.timestamp in 1..otherLastRead && !it.system }?.id
        messages.forEachIndexed { index, message ->
            val previous = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            val newDay = previous == null || !sameDay(previous.timestamp, message.timestamp) || message.timestamp - previous.timestamp > 60 * 60 * 1000
            val nextBreaks = next == null || next.senderId != message.senderId || next.system || !sameDay(next.timestamp, message.timestamp) ||
                next.timestamp - message.timestamp > 60 * 60 * 1000
            val replyHeader = when {
                message.replyToText.isBlank() -> ""
                message.senderId == me -> "You replied to ${if (message.replyToSenderId == me) "yourself" else personName.substringBefore(" ")}"
                else -> "${personName.substringBefore(" ")} replied to ${if (message.replyToSenderId == otherUid) "themself" else "you"}"
            }
            val reacted = hearts[message.id].orEmpty()
            rows.add(ChatRow.Message(
                message = message,
                dateLabel = if (newDay) dateLabel(message.timestamp) else null,
                showAvatar = message.senderId != me && nextBreaks,
                replyHeader = replyHeader,
                heartMine = me in reacted,
                heartOther = otherUid in reacted,
                seen = message.id == myLastSeen,
                showTime = next == null
            ))
        }
        if (otherTyping) rows.add(ChatRow.Typing)
        messageAdapter.introSubtitle = if (isFriend) "You're friends on Kinnect" else "You're not friends on Kinnect yet"
        val atBottom = !recycler.canScrollVertically(1)
        messageAdapter.setRows(rows)
        if (atBottom || rows.size <= 2) recycler.scrollToPosition(rows.lastIndex)
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a }
        val y = Calendar.getInstance().apply { timeInMillis = b }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    /** "TODAY 4:49 PM", "YESTERDAY 9:02 AM", "12 OCT 4:49 PM" */
    private fun dateLabel(time: Long): String {
        if (time <= 0) return ""
        val now = FirebaseData.now()
        val day = when {
            sameDay(time, now) -> "Today"
            sameDay(time, now - 86_400_000) -> "Yesterday"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))
        }
        return "$day ${TimeFormat.clock(time)}"
    }

    private fun sendMessage() {
        val text = input.text.toString().trim().ifBlank { "👍" }
        val reply = replyingTo
        ChatSender.send(this, me, otherUid, type = "text", text = text, preview = text, fields = replyFields(reply))
        input.text.clear()
        clearReply()
        setTyping(false)
    }

    private fun replyFields(reply: ChatMessage?) = mapOf<String, Any>(
        "replyToMessageId" to (reply?.id ?: ""),
        "replyToSenderId" to (reply?.senderId ?: ""),
        "replyToText" to (reply?.let { previewOf(it) } ?: "")
    )

    /** How a message reads in the Chats list and in reply quotes. */
    private fun previewOf(message: ChatMessage) = when (message.type) {
        "image" -> "Photo"
        "video" -> "Video"
        "audio" -> "Voice message"
        "post" -> "Shared a post"
        else -> message.text
    }

    private fun startReply(message: ChatMessage) {
        replyingTo = message
        findViewById<View>(R.id.chatReplyBar).visibility = View.VISIBLE
        val who = if (message.senderId == me) "yourself" else personName.substringBefore(" ")
        replyLabel.text = "Replying to $who: ${previewOf(message).take(48)}"
        input.requestFocus()
    }

    private fun clearReply() {
        replyingTo = null
        findViewById<View>(R.id.chatReplyBar).visibility = View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            PICK_IMAGE -> {
                val uri = data.getStringArrayListExtra("media_uris")?.firstOrNull() ?: return
                val kind = data.getStringArrayListExtra("media_types")?.firstOrNull() ?: MediaUploader.IMAGE
                sendMedia(android.net.Uri.parse(uri), kind)
            }
            TAKE_PHOTO -> data.getStringExtra("media_uri")?.let { sendMedia(android.net.Uri.parse(it), MediaUploader.IMAGE) }
        }
    }

    /** Uploads a photo or short video (progress above the input), then sends it as an "image" / "video" message. */
    private fun sendMedia(uri: android.net.Uri, kind: String) {
        val status = findViewById<TextView>(R.id.chatUploadStatus)
        val label = if (kind == MediaUploader.VIDEO) "video" else "photo"
        status.visibility = View.VISIBLE
        status.text = "Uploading $label…"
        val reply = replyingTo
        MediaUploader.upload(this, uri, kind,
            onProgress = { status.text = "Uploading $label · $it%" },
            onSuccess = { ref ->
                status.visibility = View.GONE
                val video = kind == MediaUploader.VIDEO
                ChatSender.send(this, me, otherUid, type = if (video) "video" else "image", text = "", preview = if (video) "Video" else "Photo",
                    fields = replyFields(reply) + ((if (video) "videoRef" else "imageRef") to ref))
                clearReply()
            },
            onError = { message ->
                status.visibility = View.GONE
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            })
    }

    // ----- Voice messages -----

    private fun startVoice() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        val file = java.io.File.createTempFile("kinnect_voice_", ".m4a", cacheDir)
        val rec = if (Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else @Suppress("DEPRECATION") android.media.MediaRecorder()
        try {
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(48_000)
            rec.setAudioSamplingRate(32_000)
            rec.setMaxDuration(60_000) // a minute fits easily in the database (about 360 KB)
            rec.setOutputFile(file.absolutePath)
            rec.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) runOnUiThread { sendVoice() }
            }
            rec.prepare()
            rec.start()
        } catch (error: Exception) {
            runCatching { rec.release() }
            Toast.makeText(this, "Could not start recording. Is another app using the microphone?", Toast.LENGTH_LONG).show()
            return
        }
        recorder = rec
        voiceFile = file
        voiceStartedAt = System.currentTimeMillis()
        findViewById<View>(R.id.chatRecordBar).visibility = View.VISIBLE
        findViewById<ImageView>(R.id.chatVoiceButton).setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.red))
        handler.post(voiceClock)
    }

    /** Stops the recorder; returns the recording file, or null if it failed or was too short. */
    private fun stopVoice(): java.io.File? {
        val rec = recorder ?: return null
        recorder = null
        handler.removeCallbacks(voiceClock)
        findViewById<View>(R.id.chatRecordBar).visibility = View.GONE
        findViewById<ImageView>(R.id.chatVoiceButton).setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.primary))
        val file = voiceFile
        voiceFile = null
        val stopped = runCatching { rec.stop() }.isSuccess // stop() fails when the recording is far too short
        runCatching { rec.release() }
        return if (stopped && file != null && file.length() > 0) file else null
    }

    private fun cancelVoice() {
        stopVoice()?.delete()
    }

    private fun sendVoice() {
        val seconds = ((System.currentTimeMillis() - voiceStartedAt) / 1000).toInt()
        val file = stopVoice()
        if (file == null || seconds < 1) {
            file?.delete()
            Toast.makeText(this, "Recording was too short. Hold on a little longer.", Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.chatUploadStatus)
        status.visibility = View.VISIBLE
        status.text = "Sending voice message…"
        val reply = replyingTo
        MediaUploader.upload(this, android.net.Uri.fromFile(file), MediaUploader.AUDIO,
            onProgress = { status.text = "Sending voice message · $it%" },
            onSuccess = { ref ->
                status.visibility = View.GONE
                ChatSender.send(this, me, otherUid, type = "audio", text = "", preview = "Voice message",
                    fields = replyFields(reply) + mapOf("audioRef" to ref, "durationSec" to seconds))
                clearReply()
                file.delete()
            },
            onError = { message ->
                status.visibility = View.GONE
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            })
    }

    private fun toggleHeart(message: ChatMessage) {
        if (message.system) return
        val reference = FirebaseData.messageReactions.child(chatId).child(message.id).child(me)
        val task = if (me in hearts[message.id].orEmpty()) reference.removeValue() else reference.setValue("heart")
        task.addOnFailureListener { Toast.makeText(this, it.localizedMessage ?: "Could not update the heart", Toast.LENGTH_SHORT).show() }
    }

    private fun canChange(message: ChatMessage) = message.senderId == me && !message.system && FirebaseData.now() - message.timestamp < EDIT_WINDOW_MS

    /** Reply / heart for any message; Edit (text only) and Delete only for my own message within 5 minutes. */
    private fun showMessageOptions(message: ChatMessage) {
        if (message.system) return
        val options = mutableListOf("Reply", if (me in hearts[message.id].orEmpty()) "Remove heart" else "Heart")
        if (canChange(message)) {
            if (message.type == "text") options.add("Edit")
            options.add("Delete")
        }
        AlertDialog.Builder(this).setItems(options.toTypedArray()) { _, picked ->
            when (options[picked]) {
                "Reply" -> startReply(message)
                "Heart", "Remove heart" -> toggleHeart(message)
                "Edit" -> if (canChange(message)) editMessage(message) else tooLate()
                "Delete" -> if (canChange(message)) deleteMessage(message) else tooLate()
            }
        }.show()
    }

    private fun tooLate() = Toast.makeText(this, "Messages can only be changed within 5 minutes", Toast.LENGTH_SHORT).show()

    private fun editMessage(message: ChatMessage) {
        val editor = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(message.text)
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("Edit message").setView(editor)
            .setPositiveButton("Save") { _, _ ->
                val text = editor.text.toString().trim()
                if (text.isBlank() || !canChange(message)) return@setPositiveButton tooLate()
                chat.child("messages").child(message.id).updateChildren(mapOf("text" to text, "edited" to true))
                    .addOnSuccessListener {
                        // Keep the Chats preview right when the last message was edited.
                        chat.child("lastMessage").child("messageId").get().addOnSuccessListener { last ->
                            if (last.getValue(String::class.java) == message.id) chat.child("lastMessage").child("text").setValue(text)
                        }
                    }
                    .addOnFailureListener { Toast.makeText(this, it.localizedMessage ?: "Could not edit (over 5 minutes?)", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteMessage(message: ChatMessage) {
        AlertDialog.Builder(this).setMessage("Delete this message?")
            .setPositiveButton("Delete") { _, _ ->
                chat.child("messages").child(message.id).removeValue()
                    .addOnSuccessListener { refreshLastMessage(message.id) }
                    .addOnFailureListener { Toast.makeText(this, it.localizedMessage ?: "Could not delete (over 5 minutes?)", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** If the deleted message was the Chats preview, show the newest remaining message instead (or nothing). */
    private fun refreshLastMessage(deletedId: String) {
        chat.child("lastMessage").child("messageId").get().addOnSuccessListener { last ->
            val pointedAt = last.getValue(String::class.java)
            if (pointedAt != null && pointedAt != deletedId) return@addOnSuccessListener
            chat.child("messages").orderByChild("timestamp").limitToLast(1).get().addOnSuccessListener { snapshot ->
                val newest = snapshot.children.firstOrNull()?.let { parse(it) }
                if (newest == null) chat.child("lastMessage").removeValue()
                else chat.child("lastMessage").setValue(mapOf("text" to previewOf(newest), "senderId" to newest.senderId, "timestamp" to newest.timestamp, "messageId" to newest.id))
            }
        }
    }

    /**
     * One code path for both detection methods: a system message "{Name} took a screenshot" in the conversation,
     * a notification record for the other person, and a push request, all written together.
     */
    private fun reportScreenshot() {
        if (!chatReady) return
        ProfileCache.get(me) { profile ->
            val text = "${profile.name.substringBefore(" ").ifBlank { "Someone" }} took a screenshot"
            val messageId = chat.child("messages").push().key ?: return@get
            val notificationId = FirebaseData.database.getReference("notifications").child(otherUid).push().key ?: return@get
            val now = mapOf(".sv" to "timestamp")
            val updates = mutableMapOf<String, Any>(
                "chats/$chatId/messages/$messageId" to mapOf("messageId" to messageId, "senderId" to me, "text" to text, "type" to "system", "timestamp" to now, "edited" to false),
                "notifications/$otherUid/$notificationId" to mapOf("senderId" to me, "type" to "screenshot", "chatId" to chatId, "body" to text, "createdAt" to now, "read" to false)
            )
            updates += PushQueue.entry(otherUid, me, "screenshot", "Kinnect", "$text of your chat", mapOf("chatId" to chatId))
            FirebaseData.database.reference.updateChildren(updates)
                .addOnFailureListener { Toast.makeText(this, it.localizedMessage ?: "Could not report the screenshot", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Below Android 14 the detector needs gallery access; ask once, with a reason. */
    private fun askScreenshotPermissionOnce() {
        if (!screenshotDetector.needsPermission()) return
        val prefs = getSharedPreferences("kinnect", MODE_PRIVATE)
        if (prefs.getBoolean("asked_screenshot_permission", false)) return
        prefs.edit().putBoolean("asked_screenshot_permission", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Screenshot alerts")
            .setMessage("Kinnect tells people when someone takes a screenshot of their chat. On this Android version that needs access to your photos.")
            .setPositiveButton("Allow") { _, _ -> requestPermissions(arrayOf(screenshotDetector.permission()), REQUEST_MEDIA) }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
        else if (requestCode == REQUEST_MIC) Toast.makeText(this, "Allow the microphone to send voice messages", Toast.LENGTH_LONG).show()
        if (requestCode == REQUEST_MEDIA && started) {
            screenshotDetector.stop()
            screenshotDetector.start()
        }
    }

    /** Phone / video icons: the call screen asks for permissions, then rings the other person. */
    private fun startCall(video: Boolean) {
        startActivity(Intent(this, VoiceCallActivity::class.java).putExtra("other_uid", otherUid).putExtra("video", video))
    }

    companion object {
        /** The person whose chat is on screen right now (no push notification is shown for them). */
        @Volatile var openChatWith: String? = null
        private const val PICK_IMAGE = 840
        private const val TAKE_PHOTO = 841
        private const val REQUEST_MEDIA = 842
        private const val REQUEST_MIC = 843
        private const val EDIT_WINDOW_MS = 5 * 60 * 1000L
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopTyping)
        super.onDestroy()
    }
}

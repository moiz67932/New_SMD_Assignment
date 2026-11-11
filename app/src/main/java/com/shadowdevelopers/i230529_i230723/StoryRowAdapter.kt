package com.shadowdevelopers.i230529_i230723

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Everything one person has active right now, for the Home stories row. */
data class StoryOwner(val uid: String, val stories: List<Story>, val allSeen: Boolean) {
    val latest get() = stories.maxByOrNull { it.createdAt }
}

/** Home stories row (04): position 0 is always "Create story", then friends who are live now, then one tile per person. */
class StoryRowAdapter(
    private val me: String,
    private val onCreate: () -> Unit,
    private val onOpen: (StoryOwner) -> Unit,
    private val onLive: (String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val owners = mutableListOf<StoryOwner>()
    private val liveHosts = mutableListOf<String>()

    fun setLive(hosts: List<String>) {
        liveHosts.clear()
        liveHosts.addAll(hosts)
        notifyDataSetChanged()
    }

    fun setOwners(items: List<StoryOwner>) {
        owners.clear()
        owners.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when {
        position == 0 -> TYPE_CREATE
        position <= liveHosts.size -> TYPE_LIVE
        else -> TYPE_STORY
    }
    override fun getItemCount() = liveHosts.size + owners.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CREATE -> CreateHolder(inflater.inflate(R.layout.item_story_create, parent, false))
            TYPE_LIVE -> LiveHolder(inflater.inflate(R.layout.item_story_tile, parent, false))
            else -> TileHolder(inflater.inflate(R.layout.item_story_tile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CreateHolder -> holder.bind()
            is LiveHolder -> holder.bind(liveHosts[position - 1])
            else -> (holder as TileHolder).bind(owners[position - 1 - liveHosts.size])
        }
    }

    inner class CreateHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            val avatar = itemView.findViewById<TextView>(R.id.createStoryAvatar)
            ProfileCache.get(me) { AvatarBinder.bind(avatar, it.name, it.photo, cornerRadiusDp = 0f) }
            itemView.setOnClickListener { onCreate() }
        }
    }

    inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image = view.findViewById<ImageView>(R.id.storyTileImage)
        private val ring = view.findViewById<View>(R.id.storyTileRing)
        private val avatar = view.findViewById<TextView>(R.id.storyTileAvatar)
        private val name = view.findViewById<TextView>(R.id.storyTileName)

        fun bind(owner: StoryOwner) {
            itemView.tag = owner.uid
            itemView.findViewById<TextView>(R.id.storyTileName).setBackgroundColor(0x88000000.toInt())
            val latest = owner.latest
            if (latest?.type == MediaUploader.VIDEO) MediaLoader.loadVideoThumbnail(image, latest.mediaRef) else MediaLoader.load(image, latest?.mediaRef)
            ring.setBackgroundResource(if (owner.allSeen || owner.uid == me) R.drawable.story_ring_seen else R.drawable.story_ring_unseen)
            name.text = if (owner.uid == me) "Your story" else ""
            ProfileCache.get(owner.uid) { profile ->
                if (itemView.tag != owner.uid) return@get
                AvatarBinder.bind(avatar, profile.name, profile.photo)
                if (owner.uid != me) name.text = profile.name
            }
            itemView.setOnClickListener { onOpen(owner) }
        }
    }

    /** A friend who is live right now: dark tile, red ring, "LIVE" label; opens the live video. */
    inner class LiveHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(hostUid: String) {
            itemView.tag = hostUid
            itemView.findViewById<ImageView>(R.id.storyTileImage).setImageDrawable(null)
            itemView.findViewById<View>(R.id.storyTileRing).setBackgroundResource(R.drawable.story_ring_live)
            val name = itemView.findViewById<TextView>(R.id.storyTileName)
            name.text = "LIVE"
            name.setBackgroundColor(itemView.context.getColor(R.color.red))
            ProfileCache.get(hostUid) { profile ->
                if (itemView.tag != hostUid) return@get
                AvatarBinder.bind(itemView.findViewById(R.id.storyTileAvatar), profile.name, profile.photo)
                name.text = "LIVE · ${profile.name.substringBefore(" ")}"
            }
            itemView.setOnClickListener { onLive(hostUid) }
        }
    }

    companion object {
        private const val TYPE_CREATE = 0
        private const val TYPE_STORY = 1
        private const val TYPE_LIVE = 2
    }
}

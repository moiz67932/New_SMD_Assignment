package com.shadowdevelopers.i230529_i230723

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One row = a title and a smaller line under it. Used for groups, saved posts, events, memories and search results. */
data class TextRow(val id: String, val title: String, val subtitle: String = "", val extra: String = "")

class TextRowAdapter(private val onClick: (TextRow) -> Unit) : RecyclerView.Adapter<TextRowAdapter.Holder>() {
    private val rows = mutableListOf<TextRow>()

    fun set(items: List<TextRow>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val title = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subtitle = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        box.addView(title)
        box.addView(subtitle)
        return Holder(box, title, subtitle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(row) }
    }

    class Holder(view: View, val title: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(view)
}

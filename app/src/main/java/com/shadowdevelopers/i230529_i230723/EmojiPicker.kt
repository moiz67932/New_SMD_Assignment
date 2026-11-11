package com.shadowdevelopers.i230529_i230723

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** A simple emoji grid in a dialog (chat input, comments, story stickers). */
object EmojiPicker {
    val EMOJIS = ("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😎 🤩 🥳 😏 😢 😭 😡 🤯 😱 🤔 🙄 😴 👍 👎 👏 🙌 🙏 💪 👋 ✌️ " +
        "❤️ 🧡 💛 💚 💙 💜 🖤 💔 💕 🔥 ✨ ⭐ 🎉 🎂 🎁 🌹 🌞 🌈 ☕ 🍕 🍔 🍟 🍫 ⚽ 🏏 🎮 🎧 📸 ✈️ 🚗 🏖️ 📚 💻 📱").split(" ")

    fun show(activity: Activity, onPick: (String) -> Unit) {
        val grid = GridView(activity).apply {
            numColumns = 6
            verticalSpacing = 4
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(activity).setView(grid).create()
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = EMOJIS.size
            override fun getItem(position: Int) = EMOJIS[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val view = (convertView as? TextView) ?: TextView(activity).apply {
                    textSize = 26f
                    gravity = Gravity.CENTER
                    val height = (46 * resources.displayMetrics.density).toInt()
                    layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                }
                view.text = EMOJIS[position]
                return view
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            onPick(EMOJIS[position])
            dialog.dismiss()
        }
        dialog.show()
    }
}

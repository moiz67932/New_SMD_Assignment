package com.shadowdevelopers.i230529_i230723

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * Visible press feedback for every tappable element (HCI: "visibility of system status": the user must see
 * that a tap registered). Most Kinnect buttons are LinearLayouts/TextViews/ImageViews with plain shape
 * backgrounds, which give no feedback by default. This adds a ripple *foreground* to each clickable view:
 *  - shaped like the view's own background (rounded pill, circle, rectangle),
 *  - white on dark/teal backgrounds, dark on light ones,
 *  - as a foreground, so it survives code that later changes the background (chip highlight, mute button).
 * KinnectApplication installs it for every Activity; new RecyclerView rows are picked up on the next layout.
 */
object TouchFeedback {
    fun install(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.getTag(R.id.touchFeedbackInstalled) == true) return
        root.setTag(R.id.touchFeedbackInstalled, true)
        var scheduled = false
        val scan = Runnable { scheduled = false; apply(root) }
        // Re-scan after layouts (new list rows, views shown later), at most once per frame.
        root.viewTreeObserver.addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener {
            if (!scheduled) {
                scheduled = true
                root.post(scan)
            }
        })
        apply(root)
    }

    private fun apply(view: View) {
        if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
        if (!view.isClickable || view is EditText || view is AdapterView<*> || view is RecyclerView) return
        if (view.getTag(R.id.touchFeedbackApplied) == true) return
        if (!view.isLaidOut) return // measured on the next pass, so the ripple gets the right shape
        if (view.background is RippleDrawable || view.foreground != null) {
            view.setTag(R.id.touchFeedbackApplied, true)
            return
        }
        view.setTag(R.id.touchFeedbackApplied, true)
        view.foreground = RippleDrawable(ColorStateList.valueOf(rippleColor(view)), null, maskFor(view))
    }

    /** The ripple keeps to the view's shape: its background shape, a circle for bare icons, else a rectangle. */
    private fun maskFor(view: View): Drawable {
        val background = view.background
        if (background is GradientDrawable) background.constantState?.newDrawable()?.mutate()?.let { return it }
        if (background == null && view is ImageView && view.width > 0 && view.width <= dp(view, 56)) return ShapeDrawable(OvalShape())
        return ColorDrawable(Color.WHITE)
    }

    /** Dark ripple on light surfaces, light ripple on dark ones (teal buttons, camera, call and story screens). */
    private fun rippleColor(view: View): Int {
        var current: View? = view
        while (current != null) {
            backgroundColor(current.background)?.let { return if (isDark(it)) 0x40FFFFFF else 0x1F000000 }
            current = current.parent as? View
        }
        return 0x1F000000
    }

    private fun backgroundColor(drawable: Drawable?): Int? = when (drawable) {
        is ColorDrawable -> drawable.color.takeIf { Color.alpha(it) > 40 }
        is GradientDrawable -> drawable.color?.defaultColor?.takeIf { Color.alpha(it) > 40 }
        else -> null
    }

    private fun isDark(color: Int) = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255 < 0.55

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
}

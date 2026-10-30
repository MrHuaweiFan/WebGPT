package org.webgpt.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Hides the black flash that appears when the app returns from the task
 * manager, while the GPU surface is recreated.
 *
 * When the window loses focus, a picture of the whole window is copied into an
 * ImageView on top of everything. After the window regains focus, the picture
 * stays for [FADE_OUT_DELAY_MS] so the WebView can repaint, then is removed.
 *
 * The overlay lives in the DecorView, not in the content layout. PixelCopy
 * captures the whole window, including the status-bar strip, so the overlay
 * must cover the same area. Placed in the content layout, the picture was
 * squeezed by the status-bar height and drew a second black strip for 300 ms.
 *
 * The window background colors set in [MainActivity] are the reliable first
 * defense. This overlay is the stronger mask when PixelCopy finishes in time.
 */
internal class ResumeSnapshot private constructor(
    private val activity: Activity,
    private val view: ImageView,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null

    private val fadeOut = Runnable { clear() }

    /** Copies the current window onto the overlay. Call it while the surface is still alive. */
    fun capture() {
        val decor = activity.window?.decorView ?: return
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return

        // A fade-out scheduled by an earlier focus gain must not hide the new picture.
        handler.removeCallbacks(fadeOut)

        // Hide the overlay while copying so it does not capture itself.
        val previousVisibility = view.visibility
        view.visibility = View.GONE

        try {
            val copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                copyWithPixelCopy(copy, previousVisibility)
            } else {
                // Below API 26 there is no PixelCopy. A software draw is less
                // reliable for GPU content but better than nothing.
                decor.draw(Canvas(copy))
                show(copy)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "snapshot capture failed", t)
            view.visibility = previousVisibility
        }
    }

    /**
     * Removes the overlay after [delayMs]. A newer call replaces a pending one.
     * Does nothing while the overlay is hidden.
     */
    fun scheduleFadeOut(delayMs: Long) {
        if (view.visibility != View.VISIBLE) return
        handler.removeCallbacks(fadeOut)
        handler.postDelayed(fadeOut, delayMs)
    }

    private fun copyWithPixelCopy(target: Bitmap, previousVisibility: Int) {
        // PixelCopy is asynchronous. It only accepts a Surface, SurfaceView or
        // Window, so the whole window is captured. The callback runs on the main thread.
        PixelCopy.request(activity.window, target, { result ->
            if (result == PixelCopy.SUCCESS && !activity.isDestroyed) {
                show(target)
                // The copy can finish after focus was regained. The focus callback has
                // already come and gone then, so the fade-out must be scheduled here.
                if (activity.hasWindowFocus()) scheduleFadeOut(FADE_OUT_DELAY_MS)
            } else {
                Log.w(TAG, "snapshot PixelCopy failed, code=$result")
                target.recycle()
                view.visibility = previousVisibility
            }
        }, handler)
    }

    private fun show(newBitmap: Bitmap) {
        releaseBitmap()
        bitmap = newBitmap
        view.setImageBitmap(newBitmap)
        view.visibility = View.VISIBLE
    }

    private fun clear() {
        if (view.visibility != View.VISIBLE) return
        view.visibility = View.GONE
        releaseBitmap()
    }

    /** Detaches the bitmap from the view before recycling, so the view never draws a recycled bitmap. */
    private fun releaseBitmap() {
        view.setImageBitmap(null)
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        private const val TAG = "WebGPTApp"

        /** Time the WebView gets to repaint after the window regains focus. */
        const val FADE_OUT_DELAY_MS = 300L

        /**
         * Adds the overlay on top of the window. Returns null if that fails, which
         * disables the mask; an overlay problem must never take the app down.
         */
        fun attachTo(activity: Activity): ResumeSnapshot? = try {
            val view = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                visibility = View.GONE
            }
            (activity.window.decorView as ViewGroup).addView(view)
            ResumeSnapshot(activity, view)
        } catch (t: Throwable) {
            Log.e(TAG, "snapshot overlay attach failed", t)
            null
        }
    }
}

package org.webgpt.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * The full-screen overlay (spinning logo plus an optional progress bar) shown
 * until the site has really rendered. Its background is the theme's window
 * background, so it blends with the system splash screen and avoids a white
 * flash while the page loads its own theme.
 */
internal class LoadingScreen(private val activity: MainActivity) {

    val overlay: View = activity.findViewById(R.id.loading_overlay)
    private val logo: ImageView = activity.findViewById(R.id.loading_logo)
    val progressBar: LinearProgressIndicator = activity.findViewById(R.id.loading_progress_bar)

    private var logoAnimation: AnimatorSet? = null

    /** Black logo on the light background, white logo on the dark one. */
    fun updateLogoForTheme() {
        logo.setImageResource(if (activity.isDarkMode) R.drawable.logo_white else R.drawable.logo_black)
    }

    /**
     * Re-syncs the optional progress bar with the Settings toggle ("Show page
     * loading progress", off by default) and empties it for a fresh load. Call
     * it whenever the overlay appears so the bar starts empty and in step with
     * the logo. The preference is read each time, so toggling it applies on the
     * next load without a restart.
     */
    fun syncProgressBar() {
        val show = runCatching { activity.appPrefs.getBoolean(App.PREF_LOADING_PROGRESS, false) }
            .getOrDefault(false)
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) progressBar.setProgressCompat(0, false)
    }

    /**
     * Reports page progress to the bar. chatgpt.com reports 100% before its
     * single-page app has hydrated, so the raw value is mapped onto 0 to 90%.
     * Only [hideNow] completes the bar, from the same "really ready" signal
     * that dismisses the overlay.
     */
    fun onPageProgress(rawPercent: Int) {
        if (progressBar.visibility == View.VISIBLE && !activity.initialLoadComplete) {
            progressBar.setProgressCompat(rawPercent * MAX_RAW_PROGRESS / 100, true)
        }
    }

    /**
     * Spin and pulse use property animators on a hardware layer. The older view
     * animation stuttered while the WebView kept the UI thread busy loading the
     * page. With the logo pinned in a GPU texture, the render thread applies the
     * transforms. One revolution per 2 s (linear), alpha 0.3 to 1 to 0.3 per 2 s.
     */
    fun startLogoAnimation() {
        stopLogoAnimation()
        // Hardware layer only while animating, so the texture is released afterwards.
        logo.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val spin = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, 360f).apply {
            duration = SPIN_PERIOD_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        val pulse = ObjectAnimator.ofFloat(logo, View.ALPHA, 0.3f, 1f).apply {
            duration = PULSE_HALF_PERIOD_MS
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        logoAnimation = AnimatorSet().apply {
            playTogether(spin, pulse)
            start()
        }
    }

    /** Cancels the logo animation and releases its hardware layer. */
    fun stopLogoAnimation() {
        logoAnimation?.cancel()
        logoAnimation = null
        logo.clearAnimation()
        logo.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    /** The site's DOM-ready signal fired: dismiss the overlay and start any queued share. */
    fun onDomReady() {
        if (activity.isFinishing || activity.initialLoadComplete) return
        hideNow()
        // The composer is interactive now, so a share that arrived during boot can start
        // immediately instead of waiting for onPageFinished.
        activity.share.kickPendingSharePipelines()
    }

    /**
     * Marks the initial load complete and fades the overlay out. The flag is
     * set before the animation starts: an onPageStarted during the fade (an SPA
     * navigation) must see it and not bring the overlay back.
     */
    fun hideNow() {
        if (activity.initialLoadComplete) return
        activity.initialLoadComplete = true
        // "Bar full" and "overlay gone" are the same event.
        if (progressBar.visibility == View.VISIBLE) progressBar.setProgressCompat(100, true)

        val fadeOut = AnimationUtils.loadAnimation(activity, R.anim.fade_out)
        fadeOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) = Unit

            override fun onAnimationEnd(animation: Animation) {
                overlay.visibility = View.GONE
                stopLogoAnimation()
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })
        overlay.startAnimation(fadeOut)
    }

    private companion object {
        const val MAX_RAW_PROGRESS = 90
        const val SPIN_PERIOD_MS = 2000L
        const val PULSE_HALF_PERIOD_MS = 1000L
    }
}

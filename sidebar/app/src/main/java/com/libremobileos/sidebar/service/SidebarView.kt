package com.libremobileos.sidebar.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.UserHandle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.animation.PathInterpolator
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger
import kotlin.math.roundToInt

/**
 * @author KindBrave
 * @since 2023/9/26
 */
class SidebarView(
    private val context: Context,
    private val viewModel: ServiceViewModel,
    private val callback: Callback
) : SavedStateRegistryOwner {

    private var lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var composeView: View
        private var sidebarPositionX = 0
        private var sidebarPositionY = 0
        private var isShowing = false
        private var isInitialized = false
        private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val layoutParams = LayoutParams()
        private val logger = Logger(TAG)
        private val handler = Handler()

        // "HyperOS" style interpolator: Fast entry, smooth deceleration.
        // Equivalent to CSS cubic-bezier(0.19, 1.0, 0.22, 1.0) or Qt OutExpo
        private val morphInterpolator = PathInterpolator(0.19f, 1f, 0.22f, 1f)

        private val sharedPrefs by lazy {
            context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
        }

        companion object {
            private const val OFFSET_X = 90
            private const val WINDOWING_MODE_AVIUM_FREEFORM = 102
            private const val TAG = "SidebarView"
        }

        init {
            savedStateRegistryController.performRestore(null)
        }

        private fun launchAppInFreeform(appInfo: AppInfo) {
            val activityOptions = ActivityOptions.makeBasic().apply {
                setLaunchWindowingMode(WINDOWING_MODE_AVIUM_FREEFORM)
            }
            val intent = Intent().apply {
                setClassName(appInfo.packageName, appInfo.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            try {
                context.startActivityAsUser(intent, activityOptions.toBundle(), UserHandle.of(appInfo.userId))
            } catch (e: Exception) {
                logger.e("Failed to launch ${appInfo.packageName} in freeform: $e")
            }
            animateCollapse()
        }

        /**
         * Prepares the view and adds it to WindowManager, but does NOT fully animate it yet.
         * @param initialY The raw Y coordinate of the touch, used to set the pivot point.
         */
        @SuppressLint("ClickableViewAccessibility")
        fun showView(initialY: Float = -1f, onReady: (() -> Unit)? = null) {
            if (isShowing) return

                if (!isInitialized) {
                    if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                        lifecycleRegistry = LifecycleRegistry(this)
                    }

                    initComposeView()

                    layoutParams.apply {
                        type = LayoutParams.TYPE_APPLICATION_OVERLAY
                        flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        LayoutParams.FLAG_HARDWARE_ACCELERATED
                        privateFlags = LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                        LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY
                        format = PixelFormat.TRANSLUCENT
                            windowAnimations = 0
                            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                    }

                    updateSidebarPosition()

                    val isRightSide = sidebarPositionX > 0
                    composeView.pivotX = if (isRightSide) (composeView.width.takeIf { it > 0 }?.toFloat() ?: 200f) else 0f
                    composeView.pivotY = layoutParams.height / 2f
                    composeView.alpha = 0f
                    composeView.scaleX = 0f
                    composeView.scaleY = 0f
                    composeView.translationX = if (isRightSide) 150f else -150f

                    composeView.setOnTouchListener { _, event ->
                        logger.d("composeView: $event")
                        if (event.action == MotionEvent.ACTION_UP) {
                            animateCollapse()
                            true
                        } else false
                    }

                    handler.post {
                        runCatching {
                            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                            windowManager.addView(composeView, layoutParams)
                            isShowing = true
                            isInitialized = true
                            onReady?.invoke()
                        }.onFailure {
                            logger.e("failed to add sidebar view: ", it)
                        }
                    }
                } else {
                    updateSidebarPosition()
                    val isRightSide = sidebarPositionX > 0
                    composeView.animate().cancel()
                    composeView.alpha = 0f
                    composeView.scaleX = 0f
                    composeView.scaleY = 0f
                    composeView.translationX = if (isRightSide) 150f else -150f

                    // Restore interactive flags
                    layoutParams.flags = (layoutParams.flags
                    and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    and LayoutParams.FLAG_NOT_FOCUSABLE.inv())
                    windowManager.updateViewLayout(composeView, layoutParams)

                    isShowing = true
                    onReady?.invoke()
                }
        }

        /**
         * Smoothly expands the sidebar (Scale 0->1, Alpha 0->1)
         * Safe to call multiple times (interruptible).
         */
        fun animateExpand() {
            if (composeView.visibility != View.VISIBLE) composeView.visibility = View.VISIBLE

                composeView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .setDuration(600)
                .setInterpolator(morphInterpolator)
                .start()
        }

        /**
         * Smoothly collapses the sidebar and removes it from WindowManager when done.
         * Safe to call multiple times (interruptible).
         */
        fun animateCollapse() {
            if (!isShowing) return

                val isRightSide = sidebarPositionX > 0
                val endOffset = if (isRightSide) 150f else -150f

                composeView.animate()
                .alpha(0f)
                .scaleX(0.0f)
                .scaleY(0.0f)
                .translationX(endOffset)
                .setDuration(600)
                .setInterpolator(morphInterpolator)
                .withEndAction {
                    removeViewInternal()
                }
                .start()
        }

        private fun removeViewInternal() {
            handler.post {
                runCatching {
                    composeView.animate().cancel()
                    val isRightSide = sidebarPositionX > 0
                    composeView.translationX = if (isRightSide) 150f else -150f
                    composeView.alpha = 0f
                    composeView.scaleX = 0f
                    composeView.scaleY = 0f

                    // Make window non-interactive while parked
                    layoutParams.flags = layoutParams.flags or LayoutParams.FLAG_NOT_TOUCHABLE or
                    LayoutParams.FLAG_NOT_FOCUSABLE
                    windowManager.updateViewLayout(composeView, layoutParams)

                    isShowing = false
                    callback.onRemove()
                }.onFailure {
                    logger.e("failed to park sidebar view: ", it)
                }
            }
        }

        fun removeView(force: Boolean = false) {
            if (!isInitialized) return
                composeView.animate().cancel()
                handler.post {
                    runCatching {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                        windowManager.removeViewImmediate(composeView)
                        isShowing = false
                        isInitialized = false
                    }.onFailure {
                        logger.e("failed to remove sidebar view: ", it)
                    }
                }
        }

        fun updateSidebarPosition() {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val screenHeight = context.resources.displayMetrics.heightPixels
            val sidebarHeight = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                screenHeight / 3
            } else {
                (screenHeight * 0.8f).roundToInt()
            }

            sidebarPositionX = sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_X, 1)
            sidebarPositionY = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_Y_PORTRAIT, -screenHeight / 6)
            } else {
                0
            }

            layoutParams.apply {
                width = LayoutParams.WRAP_CONTENT
                height = sidebarHeight
                x = sidebarPositionX * (screenWidth / 2 - OFFSET_X)
                y = sidebarPositionY
            }

            logger.d("updateSidebarPosition: posX=$sidebarPositionX posY=$sidebarPositionY" +
            " lp.x=${layoutParams.x} lp.y=${layoutParams.y} height=${layoutParams.height}")

            if (isShowing) {
                handler.post {
                    runCatching {
                        windowManager.updateViewLayout(composeView, layoutParams)
                        // Reset pivot if position changed, though tricky during active usage
                        composeView.translationX = 0f
                    }.onFailure { e ->
                        logger.e("failed to updateViewLayout: ", e)
                    }
                }
            }
        }

        private fun initComposeView() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            composeView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@SidebarView)
                setViewTreeSavedStateRegistryOwner(this@SidebarView)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SidebarTheme {
                        SidebarComposeView(
                            viewModel = viewModel,
                            launchApp = { launchAppInFreeform(it) },
                                           closeSidebar = { animateCollapse() }, // Use animation
                                           modifier = Modifier
                                           .fillMaxHeight()
                                           .wrapContentWidth()
                        )
                    }
                }
            }
        }

    interface Callback {
        fun onRemove()
    }

    fun preload() {
        if (isInitialized) return
            showView(onReady = {
                // Immediately park it — just needed Compose to inflate
                removeViewInternal()
            })
    }
}

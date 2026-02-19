package com.libremobileos.sidebar.service

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.libremobileos.sidebar.utils.Logger
import kotlin.math.abs

/**
 * @author KindBrave
 * @since 2023/9/27
 */
class GestureListener(private val callback: Callback) : MGestureManager.MGestureListener {
    private val logger = Logger(TAG)
    private var initialTouchX = 0.0f
    private var initialTouchY = 0.0f

    // Logic flags
    private var isDragging = false
    private var hasTriggeredExpand = false

    // Long press logic (Legacy)
    private var isLongPress = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        isLongPress = true
        // Only trigger long press if we aren't already dragging the sidebar open
        if (!isDragging) {
            callback.beginMoveSideline()
        }
    }

    companion object {
        private const val TAG = "GestureListener"
        // Distance in pixels to trigger the expansion
        private const val TRIGGER_THRESHOLD = 80f
        // Minimum distance to consider it a swipe intent (ignore jitter)
        private const val TOUCH_SLOP = 20f
    }

    override fun singleFingerSlipAction(
        gestureEvent: MGestureManager.GestureEvent?,
        startEvent: MotionEvent?,
        endEvent: MotionEvent?,
        velocity: Float
    ): Boolean {
        // We no longer use velocity/fling to trigger the sidebar.
        // Returning false allows the system to process it if needed,
        // but since we handle logic in onTouchEvent, this is mostly unused.
        return false
    }

    override fun onTouchEvent(event: MotionEvent) {
        logger.d("onTouchEvent action=${event.action} x=${event.rawX} y=${event.rawY}")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                hasTriggeredExpand = false

                isLongPress = false
                longPressHandler.postDelayed(longPressRunnable, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val absDeltaX = abs(deltaX)
                val absDeltaY = abs(event.rawY - initialTouchY)

                // 1. Detect Drag Intent
                // Check if moved horizontally enough to be a drag, and more horizontal than vertical
                if (!isDragging && !isLongPress && absDeltaX > TOUCH_SLOP && absDeltaX > absDeltaY) {
                    isDragging = true
                    longPressHandler.removeCallbacks(longPressRunnable)
                }

                // 2. Handle Threshold Logic
                if (isDragging) {
                    // Trigger Expand: Crossed threshold
                    if (!hasTriggeredExpand && absDeltaX > TRIGGER_THRESHOLD) {
                        hasTriggeredExpand = true
                        // Pass rawY to set the animation pivot point
                        callback.onExpandTriggered(event.rawY)
                    }
                    // Trigger Collapse: User regretted and moved finger back
                    else if (hasTriggeredExpand && absDeltaX < TRIGGER_THRESHOLD) {
                        hasTriggeredExpand = false
                        callback.onCollapseTriggered()
                    }
                }
                // 3. Handle Long Press Move (Existing logic)
                else if (isLongPress) {
                    callback.moveSideline(
                        (event.rawX - initialTouchX).toInt(),
                                          (event.rawY - initialTouchY).toInt(),
                                          event.rawX.toInt(),
                                          event.rawY.toInt()
                    )
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)

                // If user lifts finger, finalize the state
                if (isDragging) {
                    if (!hasTriggeredExpand) {
                        // Dragged a bit but not enough, or dragged back
                        callback.onCollapseTriggered()
                    }
                    // If hasTriggeredExpand is true, we leave it open.
                    // The service/view doesn't need an explicit "End" call
                    // because the animation handles the visual state.
                } else if (isLongPress) {
                    callback.endMoveSideline()
                }

                // Reset
                isDragging = false
                hasTriggeredExpand = false
                isLongPress = false
            }
        }
    }

    interface Callback {
        // New methods for Threshold Swipe
        fun onExpandTriggered(touchY: Float)
        fun onCollapseTriggered()

        // Existing methods for Handle Movement
        fun showSidebar() // Kept for legacy/debug, though mostly replaced
        fun beginMoveSideline()
        fun moveSideline(xChanged: Int, yChanged: Int, touchX: Int, touchY: Int)
        fun endMoveSideline()
    }
}

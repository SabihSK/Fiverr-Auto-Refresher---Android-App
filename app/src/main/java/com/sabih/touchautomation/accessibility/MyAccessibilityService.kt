package com.sabih.touchautomation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected() // Keep base service setup intact
        instance = this // Store a reference so other classes can check status
        Log.d(TAG, "Service connected") // Confirm the service is ready
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // This is where you can react to events like clicks or screen changes
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted") // Called if the system pauses or stops the service
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null // Clear the reference because the service is shutting down
        Log.d(TAG, "Service destroyed") // Helpful log for lifecycle tracking
    }

    fun tap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y) // Set the tap location on screen
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L, // Start immediately with no delay
            TAP_DURATION_MS // Keep the tap pressed for 100 ms
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke) // Add our tap stroke to the gesture
            .build()

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Tap completed") // The system finished the tap
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Tap cancelled") // The system could not finish the tap
                }
            },
            null // No handler needed for callbacks on the main thread
        )
    }

    fun isPackageOnTop(targetPackage: String): Boolean {
        val currentPackage = rootInActiveWindow?.packageName?.toString() ?: return false
        return currentPackage.equals(targetPackage, ignoreCase = true)
    }

    fun dragTopToBottom(startX: Float, startY: Float, endY: Float) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY) // Draw straight line downwards
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L, // Start immediately
            DRAG_DURATION_MS
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Drag completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Drag cancelled")
                }
            },
            null
        )
    }

    companion object {
        private const val TAG = "MyAccessibilityService"
        private const val TAP_DURATION_MS = 100L
        private const val DRAG_DURATION_MS = 500L

        @Volatile
        var instance: MyAccessibilityService? = null
            private set // Only this service should update the reference

        fun isServiceRunning(): Boolean {
            return instance != null // True when the service is connected and ready
        }
    }
}

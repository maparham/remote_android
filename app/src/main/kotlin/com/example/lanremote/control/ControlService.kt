package com.example.lanremote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.lanremote.video.DisplayInfo

class ControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private fun gesture(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * Maps normalized viewer coordinates onto the real screen in its current rotation. The
     * stream may be scaled down or rotated, so its size must never be used here.
     */
    private fun currentMapper(): CoordinateMapper {
        val size = DisplayInfo.real(this)
        return CoordinateMapper(size.width, size.height)
    }

    private fun handle(event: ControlEvent) {
        val mapper = currentMapper()
        when (event) {
            is ControlEvent.Tap -> {
                val (x, y) = mapper.toPixels(event.x, event.y)
                gesture(
                    Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), (y + 1).toFloat()) },
                    50
                )
            }
            is ControlEvent.LongPress -> {
                val (x, y) = mapper.toPixels(event.x, event.y)
                gesture(
                    Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), (y + 1).toFloat()) },
                    event.durationMs
                )
            }
            is ControlEvent.Swipe -> {
                val (x1, y1) = mapper.toPixels(event.x1, event.y1)
                val (x2, y2) = mapper.toPixels(event.x2, event.y2)
                gesture(
                    Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) },
                    event.durationMs
                )
            }
            is ControlEvent.Key -> performGlobalAction(
                when (event.action) {
                    KeyAction.BACK -> GLOBAL_ACTION_BACK
                    KeyAction.HOME -> GLOBAL_ACTION_HOME
                    KeyAction.RECENTS -> GLOBAL_ACTION_RECENTS
                }
            )
            is ControlEvent.Text -> {
                val node = findFocusedEditable() ?: return
                val existing = node.text?.toString() ?: ""
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        existing + event.value
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            // Stream settings are handled by the server, not by input injection.
            is ControlEvent.Quality -> Unit
        }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
    }

    companion object {
        @Volatile
        var instance: ControlService? = null

        fun dispatch(event: ControlEvent) {
            instance?.handle(event)
        }
    }
}

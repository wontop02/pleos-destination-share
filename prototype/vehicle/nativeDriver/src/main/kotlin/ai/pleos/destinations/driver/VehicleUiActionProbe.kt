package ai.pleos.destinations.driver

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Self-targeted public accessibility gestures on a bounded allowlist of visible product buttons only. */
class VehicleUiActionProbe : Instrumentation() {
    private var arguments = Bundle()
    private var startedAt = 0L
    override fun onCreate(arguments: Bundle?) {
        startedAt = SystemClock.elapsedRealtime()
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }
    override fun onStart() {
        val requested = arguments.getString("resource") ?: ""
        val operation = arguments.getString("operation") ?: "inspect"
        val allowed = requested in listOf("voice_start", "voice_stop", "voice_cancel", "nav_home", "nav_settings",
            "change_destination", "start_convoy", "stop_convoy", "search_native_places",
            "preview_changed_destination", "send_destination_change", "accept_change_request", "reject_change_request",
            "create_room_entry", "join_room_entry", "submit_room")
        val result = Bundle().apply {
            putString("inputSource", "public_ui_button")
            putString("resource", if (allowed) requested else "")
            putBoolean("actionAccepted", false)
            putBoolean("enabled", false)
            putBoolean("visible", false)
            putString("bounds", "")
        }
        try {
            if (allowed && operation in listOf("click", "inspect")) {
                val root = uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString() == "ai.pleos.destinations.vehicle") {
                    val id = "ai.pleos.destinations.vehicle:id/$requested"
                    val node = root.findAccessibilityNodeInfosByViewId(id).singleOrNull()
                    if (node != null && node.refresh() && node.packageName?.toString() == "ai.pleos.destinations.vehicle" &&
                        node.className?.toString() == "android.widget.Button") {
                        result.putBoolean("enabled", node.isEnabled)
                        result.putBoolean("visible", node.isVisibleToUser)
                        val bounds = Rect(); node.getBoundsInScreen(bounds)
                        result.putString("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                        if (operation == "click" && node.isEnabled && node.isVisibleToUser && node.isClickable && !bounds.isEmpty)
                            result.putBoolean("actionAccepted", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                }
            }
        } catch (_: Exception) {
            // No SDK calls or text output; an unavailable surface leaves actionAccepted=false.
        }
        result.putLong("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
        finish(0, result)
    }
}

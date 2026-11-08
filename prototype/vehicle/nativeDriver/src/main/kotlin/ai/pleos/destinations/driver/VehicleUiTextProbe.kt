package ai.pleos.destinations.driver

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Self-targeted UIAutomator text editing of visible product fields, never an STT injection. */
class VehicleUiTextProbe : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        val field = arguments.getString("field") ?: "voice_text"
        val key = arguments.getString("textKey") ?: ""
        val text = when (key) {
            "cafe" -> "카페 들르는 거 어때?"
            "fuel" -> "여기서 주유하고 가자"
            "restaurant" -> "식당에 들르자"
            "saved-cafe" -> "가든서비스에 들르는 거 어때?"
            "negated" -> "카페 가지 말자"
            "long" -> if (field == "voice_text") "가든서비스에 들르는 거 어때? ".repeat(30).take(400) else "함께 들러 쉬고 가요. ".repeat(30).take(200)
            "clear" -> ""
            else -> null
        }
        val result = Bundle().apply {
            putString("inputSource", "ui_edit_not_speech_recognition")
            putString("field", field); putString("textKey", key); putBoolean("success", false)
        }
        if (field !in listOf("voice_text", "proposal_reason_input") || text == null ||
            (field == "proposal_reason_input" && text.length > 200)) {
            result.putString("failure", "unsupported_fixed_input"); finish(0, result); return
        }
        try {
            val root = uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() != "ai.pleos.destinations.vehicle") {
                result.putString("failure", "product_not_active"); finish(0, result); return
            }
            val id = "ai.pleos.destinations.vehicle:id/$field"
            val node = root.findAccessibilityNodeInfosByViewId(id).singleOrNull()
            if (node == null || !node.isVisibleToUser || !node.isEnabled || !node.isEditable ||
                node.className?.toString() != "android.widget.EditText") {
                result.putString("failure", "visible_editable_field_required"); finish(0, result); return
            }
            val input = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val acted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input)
            uiAutomation.waitForIdle(100, 2_000)
            node.refresh()
            val actual = node.text?.toString() ?: ""
            // Empty EditText may expose its hint via accessibility; only nonempty readback is proof of equality.
            val equal = text.isNotEmpty() && actual == text
            result.putBoolean("actionAccepted", acted); result.putBoolean("readbackEquals", equal)
            result.putInt("requestedCharacters", text.length); result.putInt("readbackCharacters", actual.length)
            result.putBoolean("emptyOrShowingHint", text.isEmpty() && (actual.isEmpty() || node.isShowingHintText))
            result.putBoolean("success", acted && (equal || (text.isEmpty() && (actual.isEmpty() || node.isShowingHintText))))
        } catch (_: Exception) { result.putString("failure", "ui_edit_failed") }
        finish(0, result)
    }
}

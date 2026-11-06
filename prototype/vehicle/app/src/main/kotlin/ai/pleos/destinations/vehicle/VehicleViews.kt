package ai.pleos.destinations.vehicle

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Native view primitives share the vehicle tokens, focus treatment and touch targets. */
internal class VehicleViews(private val context: Context) {
    enum class Role { PRIMARY, SECONDARY, DANGER }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun dimension(id: Int) = context.resources.getDimensionPixelSize(id)
    fun color(id: Int) = context.getColor(id)
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(value: String = "", title: Boolean = false, id: Int = View.NO_ID) = TextView(context).apply {
        this.id = id; text = value
        setTextColor(color(R.color.ink))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(if (title) R.dimen.title_size else R.dimen.body_size))
        setLineSpacing(dp(3).toFloat(), 1f)
        if (title) { setTypeface(typeface, Typeface.BOLD); isAccessibilityHeading = true }
    }
    fun rounded(fill: Int, radius: Int = R.dimen.card_radius, stroke: Int? = null, width: Int = 1) = GradientDrawable().apply {
        setColor(color(fill)); cornerRadius = context.resources.getDimension(radius)
        stroke?.let { setStroke(dp(width), color(it)) }
    }
    private fun controlBackground(role: Role): StateListDrawable {
        val fill = when (role) { Role.PRIMARY -> R.color.primary; Role.SECONDARY -> R.color.surface; Role.DANGER -> R.color.danger_tint }
        val edge = if (role == Role.DANGER) R.color.danger else R.color.border
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(R.color.disabled, R.dimen.control_radius))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(if (role == Role.DANGER) R.color.danger_tint else R.color.primary_tint, R.dimen.control_radius, R.color.primary, 3))
            addState(intArrayOf(android.R.attr.state_focused), rounded(fill, R.dimen.control_radius, R.color.ink, 3))
            addState(intArrayOf(), rounded(fill, R.dimen.control_radius, if (role == Role.PRIMARY) null else edge))
        }
    }
    fun button(label: String, id: Int = View.NO_ID, role: Role = Role.SECONDARY, action: () -> Unit) = Button(context).apply {
        this.id = id; text = label; isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.body_size))
        minHeight = dimension(R.dimen.button_height); minimumHeight = minHeight; minWidth = 0; minimumWidth = 0
        setPadding(dp(24), dp(12), dp(24), dp(12)); gravity = Gravity.CENTER
        background = controlBackground(role); stateListAnimator = null
        val foreground = when (role) { Role.PRIMARY -> R.color.surface; Role.SECONDARY -> R.color.ink; Role.DANGER -> R.color.danger }
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(color(R.color.disabled_ink), color(if (role == Role.DANGER) R.color.danger else R.color.ink), color(foreground))))
        setOnClickListener { action() }
    }
    fun field(parent: LinearLayout, label: String, id: Int, initial: String = "", password: Boolean = false): EditText {
        val labelView = text(label); parent.addView(labelView); labelView.labelFor = id
        return EditText(context).apply {
            this.id = id; setText(initial); setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.body_size))
            setTextColor(color(R.color.ink)); setHintTextColor(color(R.color.muted))
            minHeight = dimension(R.dimen.button_height); setPadding(dp(16), dp(12), dp(16), dp(12))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(R.color.surface, R.dimen.control_radius, R.color.primary, 2))
                addState(intArrayOf(), rounded(R.color.surface, R.dimen.control_radius, R.color.border))
            }
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(20) })
        }
    }
    fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    fun pair(parent: LinearLayout, first: View, second: View, top: Int = 0) {
        val row = row()
        row.addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        row.addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        add(parent, row, top)
    }
    fun card(parent: LinearLayout): LinearLayout = column().also {
        val padding = dimension(R.dimen.card_padding)
        it.setPadding(padding, padding, padding, padding)
        it.background = rounded(R.color.surface, stroke = R.color.border)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
    }
}

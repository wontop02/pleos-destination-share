package ai.pleos.destinations.vehicle

/** A late Android permission result never restores a departed foreground intent. */
internal class VoicePermissionIntent {
    private var scope: VoiceInputScope? = null
    val waiting get() = scope != null
    fun begin(value: VoiceInputScope) { scope = value }
    fun cancel() { scope = null }
    fun consume(current: VoiceInputScope?, granted: Boolean): Boolean {
        val expected = scope; scope = null
        return granted && expected != null && expected == current
    }
}

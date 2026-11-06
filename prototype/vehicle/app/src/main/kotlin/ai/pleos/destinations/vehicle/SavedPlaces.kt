package ai.pleos.destinations.vehicle

import ai.pleos.destinations.core.Destination
import ai.pleos.destinations.core.DestinationKind
import ai.pleos.destinations.core.PlaceSource
import ai.pleos.destinations.core.PleosMetadata
import ai.pleos.playground.navi.data.BookmarkInfo
import java.util.Collections

/** Shares the production helper/listener; it never connects or requests a route itself. */
interface SavedPlacesGateway {
    interface Listener {
        fun nativeSession(epoch: Long, connected: Boolean)
        fun bookmarks(epoch: Long, places: List<Destination>?)
        fun bookmarkFailure(epoch: Long)
    }
    fun savedPlacesListener(listener: Listener)
    fun readSavedPlaces()
}

internal fun savedPlaces(bookmarks: List<BookmarkInfo>): List<Destination>? = runCatching {
    require(bookmarks.size <= 200)
    val valid = bookmarks.mapNotNull { value ->
        runCatching {
            Destination(value.name, value.latitude, value.longitude,
                PleosMetadata(value.poiId, value.poiSubId, value.address, value.poiProvider,
                    value.displayLatitude, value.displayLongitude), DestinationKind.PLEOS_PLACE, PlaceSource.PLEOS_BOOKMARK)
        }.getOrNull()
    }
    require(bookmarks.isEmpty() || valid.isNotEmpty())
    valid
}.getOrNull()

enum class SavedPlacesPhase { IDLE, LOADING, READY, EMPTY, INVALID, FAILED, RECONNECT_REQUIRED }
data class PlaceScope(val room: String, val member: String, val roomEpoch: Long)
data class SavedPlacesState(val phase: SavedPlacesPhase = SavedPlacesPhase.IDLE,
    val snapshot: Long = 0, val choices: List<Destination> = emptyList(), val selected: Destination? = null,
    val message: String = "순정 내비에서 장소를 ☆로 저장한 뒤 불러와 주세요.")

/** One read per native epoch: callbacks have no request ID, including delayed duplicates. */
class SavedPlacesController(private val gateway: SavedPlacesGateway, private val clock: () -> Long,
    private val changed: () -> Unit) : SavedPlacesGateway.Listener {
    var state = SavedPlacesState(); private set
    private var scope: PlaceScope? = null
    private var epoch = -1L
    private var connected = false
    private var usedEpoch = false
    private var deadline = 0L
    private var serial = 0L
    init { gateway.savedPlacesListener(this) }
    val canLoad: Boolean get() = connected && scope != null && state.phase != SavedPlacesPhase.LOADING &&
        (!usedEpoch || state.phase == SavedPlacesPhase.READY)
    private fun update(value: SavedPlacesState) { state = value; changed() }
    fun scope(value: PlaceScope?) {
        if (scope == value) return
        val retiredRead = usedEpoch && connected
        scope = value; deadline = 0
        state = SavedPlacesState(snapshot = ++serial, phase = if (retiredRead) SavedPlacesPhase.RECONNECT_REQUIRED else SavedPlacesPhase.IDLE,
            message = if (retiredRead) "공유방이 바뀌었습니다. 순정 내비를 다시 연결한 뒤 불러와 주세요." else "순정 내비에서 장소를 ☆로 저장한 뒤 불러와 주세요.")
        // The caller synchronizes scope while emitting its own room state.
    }
    override fun nativeSession(epoch: Long, connected: Boolean) {
        if (this.epoch == epoch && this.connected == connected) return
        this.epoch = epoch; this.connected = connected; usedEpoch = false; deadline = 0
        update(SavedPlacesState(snapshot = ++serial,
            message = if (connected) "저장한 장소를 불러와 선택해 주세요." else "순정 내비에 연결한 뒤 저장한 장소를 불러와 주세요."))
    }
    fun load(): Boolean {
        if (!canLoad) return false
        if (state.phase == SavedPlacesPhase.READY) {
            changed() // Reopen this same immutable snapshot without another SDK request.
            return true
        }
        usedEpoch = true; deadline = clock() + 8_000
        update(SavedPlacesState(SavedPlacesPhase.LOADING, ++serial, message = "저장한 장소 불러오는 중 · 현재 안내 유지"))
        try { gateway.readSavedPlaces() } catch (_: Exception) { bookmarkFailure(epoch) }
        return true
    }
    override fun bookmarks(epoch: Long, places: List<Destination>?) {
        if (epoch != this.epoch || !connected || scope == null || state.phase != SavedPlacesPhase.LOADING) return
        if (clock() >= deadline) { timeout(); return }
        deadline = 0
        if (places == null || places.size > 200 || places.any { it.kind != DestinationKind.PLEOS_PLACE || it.source != PlaceSource.PLEOS_BOOKMARK }) {
            update(SavedPlacesState(SavedPlacesPhase.INVALID, ++serial,
                message = "장소 정보가 올바르지 않아 선택하지 않았습니다. 순정 내비를 다시 연결한 뒤 불러와 주세요.")); return
        }
        val immutable = Collections.unmodifiableList(ArrayList(places))
        update(SavedPlacesState(if (immutable.isEmpty()) SavedPlacesPhase.EMPTY else SavedPlacesPhase.READY,
            ++serial, immutable, message = if (immutable.isEmpty()) "저장한 장소가 없습니다. 순정 내비에서 ☆로 저장한 뒤 다시 연결해 주세요."
                else "불러온 목록에서 이름과 주소를 확인해 선택해 주세요. 새로 저장한 장소는 순정 내비를 다시 연결한 뒤 불러올 수 있습니다."))
    }
    override fun bookmarkFailure(epoch: Long) {
        if (epoch != this.epoch || state.phase != SavedPlacesPhase.LOADING) return
        deadline = 0
        update(SavedPlacesState(SavedPlacesPhase.FAILED, ++serial,
            message = "저장한 장소를 불러오지 못했습니다. 순정 내비를 다시 연결한 뒤 불러와 주세요."))
    }
    fun select(snapshot: Long, value: Destination): Boolean {
        if (!connected || scope == null || state.phase != SavedPlacesPhase.READY || snapshot != state.snapshot || value !in state.choices) return false
        update(state.copy(selected = value, message = "선택한 장소 ${value.name} · 변경 제안을 눌러 전송해 주세요. 새로 저장한 장소는 순정 내비를 다시 연결한 뒤 불러와 주세요."))
        return true
    }
    fun cancel(snapshot: Long? = null): Boolean {
        if (snapshot != null && snapshot != state.snapshot) return false
        deadline = 0
        update(SavedPlacesState(if (usedEpoch && connected) SavedPlacesPhase.RECONNECT_REQUIRED else SavedPlacesPhase.IDLE,
            ++serial, message = if (usedEpoch && connected) "선택 취소 · 순정 내비를 다시 연결한 뒤 불러와 주세요."
                else "선택 취소 · 현재 안내 유지"))
        return true
    }
    fun tick() { if (state.phase == SavedPlacesPhase.LOADING && clock() >= deadline) timeout() }
    private fun timeout() {
        deadline = 0
        update(SavedPlacesState(SavedPlacesPhase.RECONNECT_REQUIRED, ++serial,
            message = "8초 동안 응답이 없습니다. 순정 내비를 다시 연결한 뒤 불러와 주세요."))
    }
}

package ai.pleos.destinations.vehicle

import android.app.Activity
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper

/** Opens the public native search UI; no invented result callback or implicit route request. */
class NativePlaceSearch(private val activity: Activity, private val active: () -> Boolean,
    private val changed: (String) -> Unit, private val trace: (String) -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var helper: NaviHelper? = null
    var busy = false; private set
    fun open(keyword: String) {
        val text = keyword.trim()
        if (!active() || busy || text.isBlank() || text.length > 100 || text.any { it.isISOControl() }) return
        cancel(); busy = true
        val epoch = generation
        val sdk = NaviHelper(activity); helper = sdk
        changed("검색 화면을 준비하고 있어요.")
        fun current() = epoch == generation && helper === sdk && active()
        try {
            sdk.initialize(onConnected = {
                main.post {
                    if (!current() || !busy) return@post
                    busy = false
                    try {
                        trace("request:searchScreen")
                        sdk.requestNavigationScreen(NavigationScreenRequest.SearchPlace(text))
                        changed("검색 결과에서 새 목적지를 설정한 뒤 길맞춤으로 돌아와 주세요.")
                    } catch (_: Exception) { cancel(); changed("검색 화면을 열지 못했어요. 다시 시도해 주세요.") }
                }
            }, onFailed = {
                main.post { if (current()) { cancel(); changed("내비 검색에 연결하지 못했어요. 다시 시도해 주세요.") } }
            })
            main.postDelayed({ if (current() && busy) { cancel(); changed("검색 연결 시간이 지났어요. 다시 시도해 주세요.") } }, 15_000)
        } catch (_: Exception) { cancel(); changed("내비 검색을 시작하지 못했어요. 다시 시도해 주세요.") }
    }
    fun cancel() {
        generation += 1; busy = false; main.removeCallbacksAndMessages(null)
        val previous = helper; helper = null; if (previous != null) runCatching { previous.release() }
    }
}

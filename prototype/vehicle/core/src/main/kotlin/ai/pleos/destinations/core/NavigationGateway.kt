package ai.pleos.destinations.core

/** All methods and callbacks are serialized by the application onto its main thread. */
interface NavigationGateway {
    interface Listener {
        fun connected()
        fun failed()
        fun destinationChanged()
        fun destinationInfo(destination: Destination)
        fun destinationAbsent()
        fun invalidDestination()
        fun routeStarted()
        fun routingCompleted()
        fun routeCancelled()
        fun error(code: String)
    }

    fun connect(listener: Listener)
    fun readDestination()
    fun requestRoute(destination: Destination)
    fun release()
}

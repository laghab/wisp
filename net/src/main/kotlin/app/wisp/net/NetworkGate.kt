package app.wisp.net

import java.io.IOException

/** The network master switch. Everything in this module asks it before it opens a connection or reads another chunk. */
fun interface NetworkGate {
    fun isAllowed(): Boolean
}

class NetworkOffException : IOException("The network is turned off in Wisp's settings")

internal fun NetworkGate.require() { if (!isAllowed()) throw NetworkOffException() }

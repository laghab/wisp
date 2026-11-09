package app.wisp.keyboard.downloads

import android.content.Context
import app.wisp.keyboard.settings.WispSettings
import app.wisp.net.NetworkGate

/** The network master switch, as the settings file stores it. Off until the user turns it on; nothing in :net runs while it is off. */
object AppNetwork {
    const val KEY = "network_enabled"
    fun isOn(context: Context) = WispSettings.get(context).getBoolean(KEY, false)
    fun set(context: Context, on: Boolean) = WispSettings.get(context).putBoolean(KEY, on)
    fun gate(context: Context) = NetworkGate { isOn(context) }
}

package app.wisp.keyboard.rewrite

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.wisp.keyboard.downloads.AppNetwork
import app.wisp.keyboard.kbd.Toolbar
import app.wisp.keyboard.settings.WispSettings
import app.wisp.net.NetworkGate
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Rewrite is off until the user turns it on and gives their own key for a provider. While it is off there is no rewrite button and
 * nothing is sent. The key is kept encrypted with a key that never leaves the phone's Keystore, in a file of its own so that a backup
 * of the settings never carries it.
 */
object RewriteSettings {
    const val ON = "rewrite_on"
    const val DEFAULT_ADDRESS = "https://openrouter.ai/api/v1"
    const val DEFAULT_MODEL = "anthropic/claude-haiku-5.5"

    fun isOn(context: Context) = WispSettings.get(context).getBoolean(ON, false)

    /** Turning it on also turns on Wisp's internet switch and puts the button on the toolbar. */
    fun setOn(context: Context, on: Boolean) {
        val s = WispSettings.get(context)
        s.putBoolean(ON, on)
        if (on) { AppNetwork.set(context, true); Toolbar.add(s, app.wisp.keyboard.kbd.SuggestionBarView.Tool.REWRITE) }
    }

    /** Rewrite may connect only while both its own switch and the internet switch are on. */
    fun gate(context: Context) = NetworkGate { isOn(context) && AppNetwork.isOn(context) }

    fun address(context: Context) = WispSettings.get(context).getString("rewrite_address")?.takeIf { it.isNotBlank() } ?: DEFAULT_ADDRESS
    fun setAddress(context: Context, v: String) = WispSettings.get(context).putString("rewrite_address", v.trim().ifEmpty { null })
    fun model(context: Context) = WispSettings.get(context).getString("rewrite_provider_model")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun setModel(context: Context, v: String) = WispSettings.get(context).putString("rewrite_provider_model", v.trim().ifEmpty { null })

    /** The host the text is sent to, for the panel and the settings to name. */
    fun host(context: Context): String = runCatching { java.net.URI(address(context)).host }.getOrNull() ?: address(context)

    private fun keyFile(context: Context) = File(context.applicationContext.filesDir, "rewrite.key")
    fun hasKey(context: Context) = keyFile(context).length() > IV

    /** Saves the API key, or forgets it when [key] is blank. */
    fun setKey(context: Context, key: String) {
        val f = keyFile(context)
        if (key.isBlank()) { f.delete(); return }
        val c = Cipher.getInstance(AES).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        f.writeBytes(c.iv + c.doFinal(key.trim().toByteArray()))
    }

    /** The API key, or null when none is saved or it can no longer be read (e.g. after the app's data moved to another phone). */
    fun key(context: Context): String? = runCatching {
        val b = keyFile(context).readBytes()
        val c = Cipher.getInstance(AES).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b, 0, IV)) }
        String(c.doFinal(b, IV, b.size - IV))
    }.getOrNull()

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private const val ALIAS = "wisp_rewrite"
    private const val AES = "AES/GCM/NoPadding"
    private const val IV = 12
}

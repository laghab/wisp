package app.wisp.keyboard.voice.model

import android.content.Context
import java.io.File

/** Where speech models live on the phone and which ones are complete. (Downloading and importing are task group 7.) */
class ModelStore(private val context: Context) {
    private val prefs get() = app.wisp.keyboard.settings.WispSettings.get(context)

    /** Never backed up to the cloud and never counted as user data. */
    fun dirFor(spec: ModelSpec) = File(context.noBackupFilesDir, "models/${spec.id}")

    fun isInstalled(spec: ModelSpec) = spec.requiredFiles.isNotEmpty() && spec.requiredFiles.all { File(dirFor(spec), it).isFile }

    fun installed(): List<ModelSpec> = ModelCatalog.speech.filter { isInstalled(it) }

    var selectedId: String?
        get() = prefs.getString("voice_model")
        set(v) { prefs.putString("voice_model", v) }

    /** The model the user picked if it is installed, otherwise any installed one. */
    fun installedRewrite(): List<ModelSpec> = ModelCatalog.rewrite.filter { isInstalled(it) }

    /** The writing-assistant model the user chose; null when rewrite is off or the chosen model is not installed. "off" turns it off. */
    var rewriteId: String?
        get() = prefs.getString("rewrite_model")
        set(v) { prefs.putString("rewrite_model", v) }

    fun activeRewrite(): ModelSpec? = if (rewriteId == "off") null else ModelCatalog.byId(rewriteId)?.takeIf { isInstalled(it) } ?: installedRewrite().firstOrNull()

    fun active(): ModelSpec? = ModelCatalog.byId(selectedId)?.takeIf { isInstalled(it) } ?: installed().firstOrNull()
}

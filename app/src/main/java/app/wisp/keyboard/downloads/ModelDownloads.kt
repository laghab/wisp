package app.wisp.keyboard.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.wisp.keyboard.BuildConfig
import app.wisp.keyboard.settings.WispSettings
import app.wisp.keyboard.voice.model.ModelCatalog
import app.wisp.keyboard.voice.model.ModelInstaller
import app.wisp.keyboard.voice.model.ModelSource
import app.wisp.keyboard.voice.model.ModelSpec
import app.wisp.keyboard.voice.model.ModelStore
import app.wisp.net.Artifact
import app.wisp.net.CancelledException
import app.wisp.net.ChecksumException
import app.wisp.net.Downloader
import app.wisp.net.InsufficientSpaceException
import app.wisp.net.NetworkOffException
import app.wisp.net.UpdateManifest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface Transfer {
    data object Idle : Transfer
    data class Downloading(val done: Long, val total: Long) : Transfer
    data class Installing(val fraction: Float) : Transfer
    data class Failed(val message: String) : Transfer
}

/**
 * Everything about getting speech models onto the phone and keeping Wisp and the models up to date: downloading, importing from a file,
 * removing, checking for updates, and the state the screens show. It lives in the settings process. Every network use goes through
 * the `:net` module and is blocked while the network switch is off.
 */
class ModelDownloads private constructor(private val appContext: Context) {
    private val settings get() = WispSettings.get(appContext)
    val store = ModelStore(appContext)
    private val downloader get() = Downloader(AppNetwork.gate(appContext), "Wisp/${BuildConfig.VERSION_NAME}")
    private val executor = Executors.newFixedThreadPool(2)
    private val cancels = HashMap<String, AtomicBoolean>()

    /** Per model id. */
    val transfers = mutableStateMapOf<String, Transfer>()
    /** Bumped whenever something is installed or removed, so screens re-read what is on disk. */
    var revision by mutableIntStateOf(0); private set
    val modelUpdates = mutableStateMapOf<String, Artifact>()
    var wispUpdate by mutableStateOf<Artifact?>(null); private set
    var updateMessage by mutableStateOf<String?>(null); private set
    var checking by mutableStateOf(false); private set
    var wispTransfer by mutableStateOf<Transfer>(Transfer.Idle); private set

    var manifestUrl: String
        get() = settings.getString("update_manifest_url", "") ?: ""
        set(v) { settings.putString("update_manifest_url", v.trim()) }

    val activeCount get() = transfers.values.count { it is Transfer.Downloading || it is Transfer.Installing } + if (wispTransfer is Transfer.Downloading) 1 else 0

    // ------------------------------------------------------------------ models

    fun isBusy(spec: ModelSpec) = transfers[spec.id].let { it is Transfer.Downloading || it is Transfer.Installing }

    fun download(spec: ModelSpec, urlOverride: String? = null, sha256: String? = null, versionCode: Int = 1) {
        if (isBusy(spec)) return
        if (!AppNetwork.isOn(appContext)) { transfers[spec.id] = Transfer.Failed("Turn the network switch on first (it is off by default)."); return }
        val cancel = AtomicBoolean(false); cancels[spec.id] = cancel
        transfers[spec.id] = Transfer.Downloading(0, spec.source.downloadBytes)
        DownloadService.start(appContext)
        executor.execute {
            try {
                val target = store.dirFor(spec)
                val free = { StatFs(appContext.noBackupFilesDir.apply { mkdirs() }.path).availableBytes }
                when (val src = spec.source) {
                    is ModelSource.Archive -> {
                        val file = File(appContext.cacheDir, "downloads/${spec.id}.tar.bz2")
                        downloader.download(Downloader.Request(urlOverride ?: src.url, file, src.downloadBytes, sha256), free, cancel) { d, t -> transfers[spec.id] = Transfer.Downloading(d, t) }
                        transfers[spec.id] = Transfer.Installing(0f)
                        file.inputStream().use { ModelInstaller.installFromArchive(spec, it, target) { n -> transfers[spec.id] = Transfer.Installing((n.toFloat() / file.length().coerceAtLeast(1)).coerceIn(0f, 1f)) } }
                        file.delete()
                    }
                    is ModelSource.Files -> {
                        val staging = File(target.parentFile, target.name + ".installing").also { it.deleteRecursively(); it.mkdirs() }
                        var done = 0L; val total = src.downloadBytes
                        for (f in src.files) {
                            val base = done
                            downloader.download(Downloader.Request(f.url, File(staging, f.name), f.sizeBytes, null), free, cancel) { d, _ -> transfers[spec.id] = Transfer.Downloading(base + d, total) }
                            done += f.sizeBytes
                        }
                        target.deleteRecursively(); staging.renameTo(target)
                    }
                }
                settings.putInt("modelvercode_${spec.id}", versionCode)
                if (store.selectedId == null || store.active() == null || store.active()?.id == spec.id) store.selectedId = spec.id
                transfers.remove(spec.id); modelUpdates.remove(spec.id)
                revision++
            } catch (t: Throwable) {
                transfers[spec.id] = Transfer.Failed(friendly(t))
            } finally { cancels.remove(spec.id) }
        }
    }

    fun cancel(spec: ModelSpec) { cancels[spec.id]?.set(true) }

    /** Installs a model from an archive the user picked (no network involved). */
    fun importArchive(spec: ModelSpec, uri: Uri) {
        if (isBusy(spec)) return
        transfers[spec.id] = Transfer.Installing(0f)
        DownloadService.start(appContext)
        executor.execute {
            try {
                appContext.contentResolver.openInputStream(uri)!!.use { ModelInstaller.installFromArchive(spec, it, store.dirFor(spec)) }
                settings.putInt("modelvercode_${spec.id}", 1)
                if (store.active() == null || store.active()?.id == spec.id) store.selectedId = spec.id
                transfers.remove(spec.id); revision++
            } catch (t: Throwable) { transfers[spec.id] = Transfer.Failed(friendly(t)) }
        }
    }

    fun remove(spec: ModelSpec) {
        ModelInstaller.remove(store.dirFor(spec))
        transfers.remove(spec.id)
        if (store.selectedId == spec.id) store.selectedId = null
        revision++
    }

    fun select(spec: ModelSpec) { store.selectedId = spec.id; revision++ }

    fun dismiss(spec: ModelSpec) { if (transfers[spec.id] is Transfer.Failed) transfers.remove(spec.id) }

    // ------------------------------------------------------------------ updates

    /** Asks the update server what is new. Only when the user taps the button, and only with the network switch on. */
    fun checkForUpdates() {
        if (checking) return
        val url = manifestUrl
        if (!AppNetwork.isOn(appContext)) { updateMessage = "Turn the network switch on to check for updates."; return }
        if (url.isEmpty()) { updateMessage = "No update address is set. Add one below to check for updates."; return }
        checking = true; updateMessage = null
        executor.execute {
            try {
                val m = UpdateManifest.fetch(AppNetwork.gate(appContext), url)
                modelUpdates.clear()
                for ((id, a) in m.models) if (store.isInstalled(ModelCatalog.byId(id) ?: continue) && a.versionCode > settings.getInt("modelvercode_$id", 1)) modelUpdates[id] = a
                wispUpdate = m.wisp?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                updateMessage = if (modelUpdates.isEmpty() && wispUpdate == null) "Everything is up to date." else null
            } catch (t: Throwable) { updateMessage = friendly(t) } finally { checking = false }
        }
    }

    fun updateModel(spec: ModelSpec) { val a = modelUpdates[spec.id] ?: return; download(spec, a.url, a.sha256, a.versionCode) }

    /** Downloads the new Wisp and hands it to Android's installer; the user confirms there. */
    fun updateWisp() {
        val a = wispUpdate ?: return
        if (wispTransfer is Transfer.Downloading) return
        if (!AppNetwork.isOn(appContext)) { wispTransfer = Transfer.Failed("Turn the network switch on first."); return }
        wispTransfer = Transfer.Downloading(0, a.sizeBytes)
        DownloadService.start(appContext)
        executor.execute {
            try {
                val file = File(appContext.cacheDir, "updates/wisp-${a.versionCode}.apk")
                downloader.download(Downloader.Request(a.url, file, a.sizeBytes.takeIf { it > 0 }, a.sha256), { StatFs(appContext.cacheDir.path).availableBytes }) { d, t -> wispTransfer = Transfer.Downloading(d, t) }
                wispTransfer = Transfer.Idle
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
                appContext.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) { wispTransfer = Transfer.Failed(friendly(t)) }
        }
    }

    /** The hosts a model download would contact, shown so the user knows where their phone is going. */
    fun hostsFor(spec: ModelSpec): Set<String> = when (val s = spec.source) {
        is ModelSource.Archive -> setOf(Uri.parse(s.url).host ?: "")
        is ModelSource.Files -> s.files.mapNotNull { Uri.parse(it.url).host }.toSet()
    }

    private fun friendly(t: Throwable): String = when (t) {
        is NetworkOffException -> "The network switch was turned off."
        is InsufficientSpaceException -> "Not enough free space: about ${t.neededBytes / 1_048_576} MB needed."
        is ChecksumException -> "The download was damaged. Try again."
        is CancelledException -> "Cancelled."
        is ModelInstaller.BadArchiveException -> t.message ?: "That file is not this model."
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Could not reach the server. Check your connection."
        else -> t.message ?: "Something went wrong."
    }

    companion object {
        @Volatile private var instance: ModelDownloads? = null
        fun get(context: Context): ModelDownloads = instance ?: synchronized(this) { instance ?: ModelDownloads(context.applicationContext).also { instance = it } }
    }
}

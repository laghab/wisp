package app.wisp.keyboard.voice

import android.Manifest
import android.app.Activity
import android.os.Bundle

/** A keyboard cannot ask for a permission itself, so this invisible screen does it. Opened the first time the mic is used. */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}

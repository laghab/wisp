package app.wisp.keyboard

import android.app.Activity
import android.os.Bundle
import android.widget.EditText

/** Debug test field. Extras: inputType (int), imeOptions (int). */
class SpikeFieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(EditText(this).apply {
            if (intent.hasExtra("inputType")) inputType = intent.getIntExtra("inputType", inputType)
            if (intent.hasExtra("imeOptions")) imeOptions = intent.getIntExtra("imeOptions", imeOptions)
            requestFocus()
        })
    }
}

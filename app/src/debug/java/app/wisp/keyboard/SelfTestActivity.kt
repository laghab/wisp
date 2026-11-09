package app.wisp.keyboard

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/** Runs [SelfTests] on a worker thread and logs "SELFTEST <name> PASS|FAIL <detail>" lines, then "SELFTEST DONE". */
class SelfTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val only = intent.getStringExtra("only")
        thread(name = "selftest") {
            var failed = 0
            for ((name, body) in SelfTests.all(this)) {
                if (only != null && !name.startsWith(only)) continue
                val result = try { body() } catch (t: Throwable) { "FAIL threw ${t}" }
                if (result.startsWith("FAIL")) failed++
                Log.i("SELFTEST", "$name $result")
            }
            Log.i("SELFTEST", "DONE failed=$failed")
        }
    }
}

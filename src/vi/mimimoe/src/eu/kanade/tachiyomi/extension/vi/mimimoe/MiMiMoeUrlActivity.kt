package eu.kanade.tachiyomi.extension.vi.mimimoe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class MiMiMoeUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent.data
        if (data != null && data.pathSegments.size > 1) {
            val id = data.pathSegments[1]
            val mainIntent = Intent("eu.kanade.tachiyomi.SEARCH")
            mainIntent.putExtra("query", MiMiMoe.PREFIX_ID_SEARCH + id)
            mainIntent.putExtra("filter", packageName)
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                startActivity(mainIntent)
            } catch (e: Exception) {
                Log.e("MiMiUrlActivity", "Error: " + e.message)
            }
        } else {
            Log.e("MiMiUrlActivity", "Unable to parse URI: $data")
        }

        finish()
        exitProcess(0)
    }
}

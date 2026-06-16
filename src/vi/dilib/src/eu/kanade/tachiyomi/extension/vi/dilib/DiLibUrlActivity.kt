package eu.kanade.tachiyomi.extension.vi.dilib

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class DiLibUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent.data
        if (data != null) {
            val mainIntent = Intent("eu.kanade.tachiyomi.SEARCH")
            mainIntent.putExtra("query", data.toString())
            mainIntent.putExtra("filter", packageName)
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                startActivity(mainIntent)
            } catch (e: Exception) {
                Log.e("DiLibUrlActivity", "Error: " + e.message)
            }
        }

        finish()
        exitProcess(0)
    }
}

package com.radialtype.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone settings host. Registered in the manifest so it can be
 * opened from the system IME settings or app shortcuts, not just the
 * launcher activity.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }
}

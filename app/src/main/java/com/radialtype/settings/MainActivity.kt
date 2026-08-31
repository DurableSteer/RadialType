package com.radialtype.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.radialtype.R

/**
 * Landing page shown when the user launches RadialType from the app drawer.
 * Hosts [SettingsFragment] directly so all configuration lives in one place.
 * A standalone [SettingsActivity] wraps the same fragment for deep links
 * from the system IME settings.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}

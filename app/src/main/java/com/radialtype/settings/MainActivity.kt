package com.radialtype.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.radialtype.R

/**
 * Landing page shown when the user launches RadialType from the app drawer.
 *
 * Standard IME activation flow:
 *   1. User enables the IME in system Settings → Languages & Input →
 *      Manage keyboards (ACTION_INPUT_METHOD_SETTINGS).
 *   2. User selects RadialType as the active input method via the system
 *      input method picker (InputMethodManager.showInputMethodPicker).
 *
 * This activity provides buttons for both steps since there is no way for
 * an IME app to programmatically enable or select itself — it must be done
 * by the user through system UI for security reasons.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Step 1: Open system keyboard management settings so the user
        // can toggle RadialType on.
        findViewById<android.widget.Button>(R.id.btnEnable).setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        // Step 2: Show the system input method picker so the user can
        // select RadialType as the active keyboard.
        findViewById<android.widget.Button>(R.id.btnSwitch).setOnClickListener {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }
    }
}

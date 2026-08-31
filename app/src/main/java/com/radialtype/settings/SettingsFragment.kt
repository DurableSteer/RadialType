package com.radialtype.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.radialtype.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>(SettingsManager.KEY_ENABLE_KEYBOARD_BUTTON)
            ?.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                true
            }

        findPreference<Preference>(SettingsManager.KEY_OPEN_LAYOUT_EDITOR)
            ?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LayoutEditorActivity::class.java))
                true
            }

        findPreference<Preference>(SettingsManager.KEY_OVERLAY_PERMISSION)?.let { pref ->
            pref.setOnPreferenceClickListener {
                if (!Settings.canDrawOverlays(requireContext())) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                }
                true
            }
            pref.isVisible = !Settings.canDrawOverlays(requireContext())
        }
    }
}

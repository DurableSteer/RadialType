package com.radialtype.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.radialtype.R
import com.radialtype.text.LanguagePack
import com.radialtype.text.LayoutArranger

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

        findPreference<Preference>(SettingsManager.KEY_REGENERATE_LAYOUT)
            ?.setOnPreferenceClickListener {
                regenerateLayout()
                true
            }
    }

    /**
     * Module 15 — explicit, user-triggered layout generation. Loads the
     * selected language pack(s) from assets, blends with the configured
     * ratio, and freezes the result into customLayoutJson. Nothing here
     * reorders anything at runtime; the output is an immutable artifact
     * until the user regenerates deliberately.
     *
     * If the secondary pack is missing or blank, the layout falls back to
     * single-language mode (packB = null) — the safe default, not an error.
     */
    private fun regenerateLayout() {
        val ctx = requireContext()

        val primary = loadPack(ctx, SettingsManager.languagePrimary)
        if (primary == null) {
            Toast.makeText(
                ctx,
                getString(R.string.layout_gen_error_missing_pack, SettingsManager.languagePrimary),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val secondaryCode = SettingsManager.languageSecondary
            .takeUnless { it.isBlank() || it == SettingsManager.languagePrimary }
        val packB = secondaryCode?.let { loadPack(ctx, it) }
        // If secondaryCode was set but the pack file is missing, fall back
        // silently to single-language rather than producing a broken layout.
        val effectiveSecondary = if (packB != null) secondaryCode else null

        val weight = SettingsManager.languageMixRatio.toDouble()
        val blended = LayoutArranger.blend(primary, packB, weight)

        val tag = effectiveSecondary?.let { "${primary.lang}+$it" } ?: primary.lang
        val layout = LayoutArranger.generate(blended, tag, weight)

        SettingsManager.customLayoutJson = layout.toJson()
        Toast.makeText(ctx, R.string.layout_regenerated, Toast.LENGTH_SHORT).show()
    }

    private fun loadPack(ctx: Context, code: String): LanguagePack? {
        if (code.isBlank()) return null
        return runCatching {
            LanguagePack.fromJson(
                ctx.assets.open("langs/$code.json").bufferedReader().use { it.readText() }
            )
        }.getOrNull()
    }
}

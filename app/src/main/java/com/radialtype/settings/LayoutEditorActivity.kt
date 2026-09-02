package com.radialtype.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.radialtype.R
import com.radialtype.text.CharacterMap
import org.json.JSONObject

/**
 * Plain-text JSON layout editor.
 *
 * Format (8 slots per ring, segment 0 = east, clockwise):
 * ```json
 * { "inner": ["T","N","S","R","H","L","SHIFT","C"],
 *   "outer": ["A","E","I","O","U","W","DEL","G"] }
 * ```
 * Function tokens: DEL, SHIFT, SPACE.
 */
class LayoutEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_editor)

        val editor = findViewById<EditText>(R.id.layoutJson)

        val stored = SettingsManager.customLayoutJson
        if (stored.isNotEmpty()) {
            val pretty = runCatching { JSONObject(stored).toString(2) }.getOrNull()
            editor.setText(pretty ?: stored)
        } else {
            editor.setText(CharacterMap.DEFAULT_JSON)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            SettingsManager.customLayoutJson = editor.text.toString()
            Toast.makeText(this, R.string.layout_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnApplySample).setOnClickListener {
            editor.setText(
                "{\n" +
                "  \"digits\": { \"inner\": [\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\"], \"outer\": [\"9\",\"0\",\")\",\"(\",\"/\",\"-\",\"*\",\"+\"] },\n" +
                "  \"symbols\": { \"inner\": [\"ENTER\",\"\\\"\",\"SPACE\",\"@\",\"?\",\",\",\"SHIFT\",\".\"], \"outer\": [\"$\",\"{\",\"&\",\"=\",\"}\",\";\",\":\",\"!\"] }\n" +
                "}"
            )
        }
    }
}

package com.radialtype

import android.inputmethodservice.InputMethodService
import android.view.View
import com.radialtype.ui.RadialOverlayController

class RadialTypeIME : InputMethodService() {

    private var overlayController: RadialOverlayController? = null

    override fun onCreate() {
        super.onCreate()
        // Pass a lazy supplier: currentInputConnection is only valid during
        // an active input session, so InputDispatcher resolves it at commit time.
        overlayController = RadialOverlayController(this) { currentInputConnection }
    }

    override fun onCreateInputView(): View {
        return View(this).apply {
            minimumWidth = 1
            minimumHeight = 1
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(
        info: android.view.inputmethod.EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)
        window?.window?.setLayout(1, 1)
        overlayController?.setEditorInfo(info)
        overlayController?.show()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        overlayController?.hide()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        overlayController?.release()
        overlayController = null
        super.onDestroy()
    }
}

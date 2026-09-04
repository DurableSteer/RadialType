package com.radialtype

import android.inputmethodservice.InputMethodService
import android.view.View
import com.radialtype.ui.RadialOverlayController

class RadialTypeIME : InputMethodService() {

    private var overlayController: RadialOverlayController? = null
    private var inputView: View? = null

    override fun onCreate() {
        super.onCreate()
        // Pass a lazy supplier: currentInputConnection is only valid during
        // an active input session, so InputDispatcher resolves it at commit time.
        overlayController = RadialOverlayController(this, { currentInputConnection }) {
            // Window token of the IME's input view — valid once the view
            // is attached, so resolved lazily on every show().
            inputView?.windowToken
        }
    }

    override fun onCreateInputView(): View {
        return View(this).apply {
            minimumWidth = 1
            minimumHeight = 1
        }.also { inputView = it }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(
        info: android.view.inputmethod.EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)
        window?.window?.setLayout(1, 1)
        overlayController?.setAnchorToken(inputView?.windowToken)
        overlayController?.setEditorInfo(info)
        overlayController?.show()
    }
    
    override fun onWindowShown() {
        super.onWindowShown()
        // onStartInputView can fire before the input view is attached to a
        // window (first-show-after-IME-picker-switch path), leaving
        // windowToken null and show() dead. This hook runs after the window
        // is up, closing the race; show() no-ops if already visible.
        overlayController?.setAnchorToken(inputView?.windowToken)
        overlayController?.show()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        overlayController?.hide()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        overlayController?.hide()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        // Covers cases where the window hides without the input view
        // finishing (e.g. user pulls down the notification shade).
        overlayController?.hide()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        overlayController?.release()
        overlayController = null
        super.onDestroy()
    }
}

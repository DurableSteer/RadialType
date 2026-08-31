package com.radialtype

import android.inputmethodservice.InputMethodService
import android.view.View
import com.radialtype.ui.RadialOverlayController

/**
 * Core IME entry point.
 *
 * The IME window itself shrinks to an invisible 1×1 placeholder — it
 * exists only to keep the input session (and its InputConnection)
 * alive. All real interaction happens in the overlay windows owned by
 * [RadialOverlayController]:
 *
 *  - a small touchable pad at the ergonomic first-touch ovoid, and
 *  - a fullscreen, non-touchable rendering layer for the menus.
 *
 * Text is committed through [currentInputConnection], which stays valid
 * as long as the IME session lives.
 */
class RadialTypeIME : InputMethodService() {

    private var overlayController: RadialOverlayController? = null

      override fun onCreate() {
        super.onCreate()
        // Text commitment is intentionally NOT wired up yet — typing is
        // out of scope for this milestone. The controller only renders.
        overlayController = RadialOverlayController(this)
      }
    

    /**
     * Returns a 1×1 invisible placeholder. The system requires an input
     * view to keep the IME session active, but we never show a real
     * keyboard surface here — the overlays handle everything.
     */
    override fun onCreateInputView(): View {
        return View(this).apply {
            minimumWidth = 1
            minimumHeight = 1
        }
    }

    /** Never let the system switch to fullscreen extract mode. */
    override fun onEvaluateFullscreenMode(): Boolean = false

        override fun onStartInputView(
        info: android.view.inputmethod.EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)
        // Shrink the IME's own window to almost nothing so it can never
        // sit above the app intercepting touches.
        window?.window?.setLayout(1, 1)
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

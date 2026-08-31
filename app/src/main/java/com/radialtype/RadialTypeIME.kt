package com.radialtype

import android.inputmethodservice.InputMethodService
import android.view.View
import com.radialtype.ui.RadialKeyboardView

/**
 * Core IME entry point.
 *
 * InputMethodService is the Android framework base class for building
 * custom keyboards. The system calls [onCreateInputView] whenever the
 * keyboard is about to become visible. The View returned here becomes
 * the keyboard surface — it receives all touch input and is drawn
 * on top of the host app's content.
 *
 * For RadialType, this view is fully transparent. There is no traditional
 * key grid; all input is driven by radial gestures captured in
 * [RadialKeyboardView].
 */
class RadialTypeIME : InputMethodService() {

    private lateinit var keyboardView: RadialKeyboardView

    /**
     * Called by the framework when the keyboard window needs to be created.
     * We instantiate our custom transparent view and return it. The
     * framework handles measuring and positioning it above the on-screen
     * content area.
     */
    override fun onCreateInputView(): View {
        keyboardView = RadialKeyboardView(this)
        return keyboardView
    }
}

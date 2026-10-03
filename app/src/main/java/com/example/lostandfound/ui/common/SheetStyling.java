package com.example.lostandfound.ui.common;

import android.app.Dialog;
import android.view.View;
import android.widget.FrameLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

/** Shared behaviour for the app's BottomSheetDialog sheets. */
public final class SheetStyling {

    private static final int SHEET_ID = com.google.android.material.R.id.design_bottom_sheet;

    private SheetStyling() {}

    /**
     * Holds the sheet fully expanded so the IME cannot cover the action button.
     *
     * <p>A BottomSheetDialog's window does not resize for the keyboard unless the
     * theme asks it to (see {@code windowSoftInputMode} on
     * {@code ThemeOverlay.Foundit.Spotify.BottomSheetDialog}). Even once it does,
     * the sheet still starts half-collapsed, which puts the button below the fold.
     * Expanding it here means the full content is laid out inside the shortened
     * window, and the sheet rides up with the keyboard.
     */
    public static void expandForKeyboard(Dialog dialog) {
        if (dialog == null) return;
        View sheet = dialog.findViewById(SHEET_ID);
        if (!(sheet instanceof FrameLayout)) return;
        BottomSheetBehavior<FrameLayout> behavior =
                BottomSheetBehavior.from((FrameLayout) sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }
}
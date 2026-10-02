package com.example.lostandfound.ui.common;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import com.example.lostandfound.R;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/** Shared surface styling for the app's exposed dropdowns. */
public final class DropdownStyling {

    private DropdownStyling() {}

    /**
     * Replaces a dropdown's popup surface with a rounded dark one.
     *
     * <p>{@code android:popupBackground} in a layout has no effect here:
     * MaterialAutoCompleteTextView installs its own
     * {@code mtrl_popupmenu_background}, which is a 4dp-cornered shape filled
     * with {@code ?attr/colorSurface} — light under this app's Light parent
     * theme. {@code setDropDownBackgroundDrawable} is the supported way to swap
     * that out, and the tint must be cleared afterwards or the theme colour is
     * tinted back over the new drawable.
     */
    public static void applyRoundedPopup(MaterialAutoCompleteTextView dropdown) {
        Drawable background = ContextCompat.getDrawable(dropdown.getContext(), R.drawable.bg_dropdown_popup);
        if (background != null) {
            dropdown.setDropDownBackgroundDrawable(background);
        }
        dropdown.setDropDownBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
    }
}
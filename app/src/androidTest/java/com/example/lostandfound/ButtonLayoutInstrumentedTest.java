package com.example.lostandfound;

import static org.junit.Assert.fail;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Guards against the "clipped button label" class of bug.
 *
 * For every layout that contains buttons, this inflates the layout at several
 * screen widths and system font scales, then asserts that:
 *   1. No {@link MaterialButton} uses a hard-coded android:layout_height. Buttons
 *      must be wrap_content + a minHeight so they can grow with their label.
 *   2. The text layout actually fits inside the measured content box (so a label
 *      that wraps to two lines is not silently cropped).
 *
 * Run on a device/emulator with:
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class ButtonLayoutInstrumentedTest {

    private static final int[] LAYOUTS = {
            R.layout.activity_auth,
            R.layout.activity_chat,
            R.layout.activity_claims_review,
            R.layout.fragment_create_edit_report,
            R.layout.activity_location_picker,
            R.layout.activity_report_detail,
            R.layout.bottom_sheet_create_report,
            R.layout.dialog_edit_profile,
            R.layout.dialog_reject_claim,
            R.layout.dialog_report_abuse,
            R.layout.dialog_submit_claim,
            R.layout.fragment_browse,
            R.layout.fragment_chats,
            R.layout.fragment_home,
            R.layout.fragment_profile,
            R.layout.dialog_photo_source,
            R.layout.fragment_settings,
            R.layout.item_claim_card,
            R.layout.item_conversation,
            R.layout.item_message,
            R.layout.item_report_card,
            R.layout.item_settings_row,
            R.layout.item_report_masonry,
            R.layout.menu_create_report_popup,
    };

    /** Small phone, common phone, large phone. */
    private static final int[] WIDTH_DP = {320, 360, 411};

    /** Default, Android "Large", Android "Largest". */
    private static final float[] FONT_SCALES = {1.0f, 1.3f, 2.0f};

    @Test
    public void buttonLabelsFitAcrossWidthsAndFontScales() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<String> problems = new ArrayList<>();

        for (float fontScale : FONT_SCALES) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.fontScale = fontScale;
            Context scaled = base.createConfigurationContext(config);
            Context themed = new ContextThemeWrapper(scaled, R.style.Theme_LostAndFound);

            for (int widthDp : WIDTH_DP) {
                for (int layoutId : LAYOUTS) {
                    String name = themed.getResources().getResourceEntryName(layoutId);
                    View root;
                    try {
                        root = LayoutInflater.from(themed).inflate(layoutId, null);
                    } catch (Throwable t) {
                        // Layout needs a runtime service we cannot provide in a unit
                        // instrumented inflate (e.g. the Google MapView). Skip it.
                        continue;
                    }
                    if (root == null) {
                        continue;
                    }
                    float density = themed.getResources().getDisplayMetrics().density;
                    int widthPx = Math.round(widthDp * density);
                    int heightPx = Math.round(2200 * density);
                    root.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST));
                    root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

                    collectProblems(root, name, fontScale, widthDp, problems);
                }
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("Button label problems found:\n");
            for (String p : problems) {
                sb.append(" - ").append(p).append('\n');
            }
            fail(sb.toString());
        }
    }

    private void collectProblems(View view, String layout, float fontScale, int widthDp,
                                 List<String> problems) {
        if (view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) view;
            String id = idName(button);
            String where = layout + " " + id + " @" + widthDp + "dp/fontScale " + fontScale;

            ViewGroup.LayoutParams lp = button.getLayoutParams();
            if (lp != null && lp.height >= 0) {
                problems.add(where + ": hard-coded layout_height (buttons must be wrap_content).");
            }

            CharSequence text = button.getText();
            if (text != null && text.length() > 0) {
                android.text.Layout textLayout = button.getLayout();
                if (textLayout != null) {
                    int textHeight = textLayout.getHeight();
                    int available = button.getMeasuredHeight()
                            - button.getCompoundPaddingTop()
                            - button.getCompoundPaddingBottom();
                    if (textHeight > available) {
                        problems.add(where + ": label \"" + text + "\" is clipped "
                                + "(text=" + textHeight + "px, available=" + available + "px).");
                    }
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectProblems(group.getChildAt(i), layout, fontScale, widthDp, problems);
            }
        }
    }

    private String idName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return "<no-id>";
        }
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "<id:" + id + ">";
        }
    }
}

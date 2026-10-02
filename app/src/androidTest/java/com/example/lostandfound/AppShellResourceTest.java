package com.example.lostandfound;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Guards the app shell against resource-level startup crashes. Every drawable
 * referenced by the bottom navigation menu and the main layout is loaded
 * directly, and activity_main is inflated, so a malformed vector or a bad menu
 * reference fails here instead of at launch. Layout-inflating problems are not
 * swallowed, unlike the button-fit test.
 */
@RunWith(AndroidJUnit4.class)
public class AppShellResourceTest {

    private static final int[] NAV_AND_SHELL_DRAWABLES = {
            R.drawable.ic_home,
            R.drawable.ic_search,
            R.drawable.ic_add,
            R.drawable.ic_chat,
            R.drawable.ic_settings_gear,
            R.drawable.ic_person,
    };

    @Test
    public void shellDrawablesLoad() {
        Resources res = InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        for (int id : NAV_AND_SHELL_DRAWABLES) {
            String name = res.getResourceEntryName(id);
            try {
                Drawable d = res.getDrawable(id);
                assertNotNull("drawable/" + name + " resolved to null", d);
            } catch (Throwable t) {
                fail("drawable/" + name + " failed to load: " + chain(t));
            }
        }
    }

    /** Full cause chain: the top-level message hides the real parser error. */
    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (t != null && depth++ < 8) {
            sb.append("\n  caused by[").append(depth).append("]: ")
              .append(t.getClass().getName()).append(": ").append(t.getMessage());
            t = t.getCause();
        }
        return sb.toString();
    }

    @Test
    public void mainActivityLayoutInflates() {
        assertInflates(R.layout.activity_main);
    }

    /** The 1-on-1 chat screen and every row layout it can inflate. */
    @Test
    public void chatLayoutsInflate() {
        assertInflates(R.layout.activity_chat);
        assertInflates(R.layout.item_message);
        assertInflates(R.layout.item_chat_proposal);
        assertInflates(R.layout.item_chat_event);
        assertInflates(R.layout.view_chat_quote);
    }

    private void assertInflates(int layoutId) {
        android.content.Context themed = new androidx.appcompat.view.ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_LostAndFound);
        View root;
        try {
            root = LayoutInflater.from(themed).inflate(layoutId, null);
        } catch (Throwable t) {
            fail("layout " + themed.getResources().getResourceEntryName(layoutId)
                    + " failed to inflate: " + chain(t));
            return;
        }
        assertNotNull(root);
    }
}
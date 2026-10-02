package com.example.lostandfound;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.repository.UnreadRepository;
import com.example.lostandfound.ui.common.UnreadFormatter;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.databinding.ActivityMainBinding;
import com.example.lostandfound.ui.auth.AuthActivity;
import com.example.lostandfound.ui.browse.BrowseFragment;
import com.example.lostandfound.ui.chats.ChatsFragment;
import com.example.lostandfound.ui.home.HomeFragment;
import com.example.lostandfound.ui.profile.ProfileFragment;
import com.example.lostandfound.ui.report.CreateEditReportFragment;
import com.example.lostandfound.ui.settings.SettingsFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Single navigation shell for the five main tabs. Fragments are looked up by
 * tag through the FragmentManager rather than held as fields, so a rotation
 * keeps one live instance per tab and an in-progress draft survives.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG_HOME = "tab_home";
    private static final String TAG_BROWSE = "tab_browse";
    private static final String TAG_CREATE = "tab_create";
    private static final String TAG_CHATS = "tab_chats";
    private static final String TAG_PROFILE = "tab_profile";
    private static final String TAG_SETTINGS = "tab_settings";

    /** The five bottom-bar tabs. Profile is not among them: it is opened from
        the avatar on Home and hidden along with the rest. */
    private static final int[] ALL_TABS = {
            R.id.nav_home, R.id.nav_browse, R.id.nav_create, R.id.nav_chats, R.id.nav_settings
    };

    private ActivityMainBinding binding;
    private SessionManager sessionManager;
    /**
     * The one app-wide unread source. Started when the app comes to the
     * foreground and stopped when it leaves, so there is a single refresh loop
     * rather than one per screen, and nothing runs while the app is backgrounded.
     */
    private UnreadRepository unreadRepository;

    private int selectedTabId = R.id.nav_home;
    /** True while Profile is shown on top of a tab (no nav item is selected). */
    private boolean profileVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Lock to light mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        sessionManager = SessionManager.getInstance(this);

        if (!sessionManager.isLoggedIn()) {
            startActivity(new Intent(this, AuthActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        selectedTabId = savedInstanceState != null
                ? savedInstanceState.getInt("state_selected_tab", R.id.nav_home)
                : R.id.nav_home;

        setupNavigation();
        setupBottomBarInsets();
        setupBackHandling();
        setupUnreadBadges();

        if (savedInstanceState == null) {
            showTab(R.id.nav_home);
        } else {
            // Fragments and the bar's checked item were restored by the
            // framework; just re-assert the tab we were on.
            profileVisible = savedInstanceState.getBoolean("state_profile_visible", false);
            if (isMenuTab(selectedTabId)) {
                binding.bottomBarInclude.bottomNavigation.setSelectedItemId(selectedTabId);
            }
        }
    }

    /**
     * Wires the shared unread source to the Chats tab's badge.
     *
     * The badge is Material's own BadgeDrawable on the nav item, so it sits in
     * the item's existing geometry and does not disturb icon/label alignment or
     * the full-height touch target. setText is used rather than setNumber so the
     * "99+" cap is ours, not the library's truncation.
     */
    private void setupUnreadBadges() {
        unreadRepository = UnreadRepository.getInstance(this);
        unreadRepository.addListener(this::renderUnreadBadges);
        renderUnreadBadges(unreadRepository.counts(), unreadRepository.total());
    }

    private void renderUnreadBadges(java.util.Map<String, Integer> counts, int total) {
        if (binding == null) return;
        com.google.android.material.badge.BadgeDrawable badge =
                binding.bottomBarInclude.bottomNavigation.getOrCreateBadge(R.id.nav_chats);
        String text = UnreadFormatter.badge(total);
        if (text.isEmpty()) {
            // removeBadge clears the dot entirely rather than leaving a 0.
            binding.bottomBarInclude.bottomNavigation.removeBadge(R.id.nav_chats);
            return;
        }
        badge.setVisible(true);
        badge.setText(text);

        // The badge itself is not focusable, so without this a screen reader
        // would just announce "Chats". The label goes on the nav item's own view
        // (menu items are inflated with their id), which keeps the count in the
        // same announcement as the tab name and leaves the touch target alone.
        View itemView = binding.bottomBarInclude.bottomNavigation.findViewById(R.id.nav_chats);
        if (itemView != null) {
            itemView.setContentDescription(getString(R.string.chats_nav_unread_cd, text));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (unreadRepository != null) unreadRepository.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (unreadRepository != null) unreadRepository.stop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("state_selected_tab", selectedTabId);
        outState.putBoolean("state_profile_visible", profileVisible);
    }

    private void setupBottomBarInsets() {
        // Gesture pill / 3-button bar must pad BELOW the nav, never between
        // icons and labels. Padding the wrapper keeps item geometry fixed and
        // each item keeps its full-height (>=48dp) touch target.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBarInclude.bottomBar, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });
    }

    /** Switches to a main tab by menu id; unknown ids fall back to Home. */
    public void selectTabById(int tabId) {
        if (binding == null) return;
        if (!isTab(tabId)) {
            tabId = R.id.nav_home;
        }
        if (tabId == selectedTabId && findTab(tabId) != null) {
            return; // Already here; never rebuilds the fragment.
        }
        if (isMenuTab(tabId)) {
            // The listener re-enters here, so do not call the setter directly.
            binding.bottomBarInclude.bottomNavigation.setSelectedItemId(tabId);
        } else {
            showTab(tabId);
        }
    }

    /** Profile's own back control: return to Home. */
    public void closeProfile() {
        profileVisible = false;
        showTab(R.id.nav_home);
    }

    /**
     * Opens Profile, reached from the avatar on Home's top bar. The bottom bar
     * keeps Home highlighted because that is where the user tapped.
     */
    public void openProfile() {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 0) fm.popBackStackImmediate();

        Fragment target = findTab(R.id.nav_profile_tab);
        FragmentTransaction tx = fm.beginTransaction();
        if (target == null) {
            target = new ProfileFragment();
            tx.add(R.id.fragment_container, target, TAG_PROFILE);
        }
        for (int id : ALL_TABS) {
            Fragment other = findTab(id);
            if (other != null && !other.isHidden()) tx.hide(other);
        }
        tx.show(target);
        tx.commit();
        profileVisible = true;
        // The bar keeps Home lit: that is where the avatar was tapped.
        if (selectedTabId != R.id.nav_home) {
            selectedTabId = R.id.nav_home;
            binding.bottomBarInclude.bottomNavigation.setSelectedItemId(R.id.nav_home);
        }
    }

    private void setupNavigation() {
        binding.bottomBarInclude.bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            // selectedTabId is set *before* the bar is re-synced, so this also
            // breaks the setSelectedItemId -> listener -> showTab recursion.
            //
            // profileVisible is the one case where the selected tab is not what
            // the user is looking at: Profile is pushed on top of Home while the
            // bar keeps Home lit. So a tap must still switch, which is what makes
            // a nav tap show exactly the page that was tapped. Deliberately not
            // expressed as a fragment-visibility check: showTab() commits
            // asynchronously, so isHidden() still reports the previous state
            // inside the re-entrant call and would recurse.
            if (id == selectedTabId && !profileVisible) return true;
            return requestTabSwitch(id);
        });
    }

    /** Guards a tab change against silently discarding an in-progress draft. */
    private boolean requestTabSwitch(int id) {
        Fragment leaving = findTab(selectedTabId);
        if (leaving instanceof CreateEditReportFragment
                && ((CreateEditReportFragment) leaving).isDirty()) {
            confirmDiscard(((CreateEditReportFragment) leaving), () -> showTab(id));
            return false; // Keep the current tab checked
        }
        showTab(id);
        return true;
    }

    private void confirmDiscard(CreateEditReportFragment form, Runnable onDiscard) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Discard changes?")
                .setMessage("You have unsaved changes. Leaving now will discard them.")
                .setPositiveButton("Discard", (d, w) -> {
                    form.resetForm();
                    onDiscard.run();
                })
                .setNegativeButton("Keep editing", null)
                .show()
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.spotify_error));
    }

    /**
     * Makes {@code tabId} the visible tab. Uses show/hide on one added set of
     * fragments so each tab keeps its state and repeated taps never duplicate.
     */
    private void showTab(int tabId) {
        FragmentManager fm = getSupportFragmentManager();

        // Inner screens pushed from a tab (e.g. Settings → My Reports) must not
        // survive a tab switch, or Back would return into another tab.
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate();
        }

        Fragment target = findTab(tabId);
        FragmentTransaction tx = fm.beginTransaction();
        if (target == null) {
            target = createTab(tabId);
            tx.add(R.id.fragment_container, target, tagFor(tabId));
        }
        for (int id : ALL_TABS) {
            if (id == tabId) continue;
            Fragment other = findTab(id);
            if (other != null && !other.isHidden()) tx.hide(other);
        }
        // Profile is not a nav tab, so it must be hidden explicitly or it
        // would stay stacked on top of whatever tab is selected.
        Fragment profile = findTab(R.id.nav_profile_tab);
        if (profile != null && !profile.isHidden()) tx.hide(profile);
        tx.show(target);
        tx.commit();

        selectedTabId = tabId;
        profileVisible = false;
        if (isMenuTab(tabId)
                && binding.bottomBarInclude.bottomNavigation.getSelectedItemId() != tabId) {
            binding.bottomBarInclude.bottomNavigation.setSelectedItemId(tabId);
        }
    }

    /** Profile is not a menu item, so the bar must not be asked to select it. */
    private static boolean isMenuTab(int tabId) {
        return tabId != R.id.nav_profile_tab;
    }

    /** System Back leaves the current tab (and any pushed screen) rather than
        dropping the user straight out of the app. */
    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                FragmentManager fm = getSupportFragmentManager();
                if (fm.getBackStackEntryCount() > 0) {
                    fm.popBackStack();
                    return;
                }
                // Profile is a sub-destination of Home, so Back returns there.
                if (profileVisible) {
                    profileVisible = false;
                    showTab(R.id.nav_home);
                    return;
                }
                if (selectedTabId == R.id.nav_home) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                // Dirty Create form: requestTabSwitch confirms, or keeps us here.
                requestTabSwitch(R.id.nav_home);
            }
        });
    }

    /** Single entry point for opening a report form; used by the central
        Create action and the home hero actions. */
    public void openCreateReport(String reportType) {
        CreateEditReportFragment form = (CreateEditReportFragment) findTab(R.id.nav_create);
        if (form != null) {
            form.setInitialType(reportType);
        } else {
            pendingCreateType = reportType;
        }
        selectTabById(R.id.nav_create);
    }

    /** Type to apply when the Create tab's first instance is built. */
    private String pendingCreateType = null;

    public void selectBrowseTab(String initialSearch) {
        BrowseFragment browse = (BrowseFragment) findTab(R.id.nav_browse);
        if (browse != null && initialSearch != null && !initialSearch.isEmpty()) {
            browse.setSearchQuery(initialSearch);
        }
        selectTabById(R.id.nav_browse);
    }

    private Fragment createTab(int tabId) {
        if (tabId == R.id.nav_home) return new HomeFragment();
        if (tabId == R.id.nav_browse) return new BrowseFragment();
        if (tabId == R.id.nav_create) {
            CreateEditReportFragment form = new CreateEditReportFragment();
            if (pendingCreateType != null) {
                form.setInitialType(pendingCreateType);
                pendingCreateType = null;
            }
            return form;
        }
        if (tabId == R.id.nav_chats) return new ChatsFragment();
        if (tabId == R.id.nav_settings) return new SettingsFragment();
        return new HomeFragment();
    }

    private Fragment findTab(int tabId) {
        return getSupportFragmentManager().findFragmentByTag(tagFor(tabId));
    }

    private static String tagFor(int tabId) {
        if (tabId == R.id.nav_browse) return TAG_BROWSE;
        if (tabId == R.id.nav_create) return TAG_CREATE;
        if (tabId == R.id.nav_chats) return TAG_CHATS;
        if (tabId == R.id.nav_settings) return TAG_SETTINGS;
        if (tabId == R.id.nav_profile_tab) return TAG_PROFILE;
        return TAG_HOME;
    }

    private static boolean isTab(int tabId) {
        return tabId == R.id.nav_home || tabId == R.id.nav_browse || tabId == R.id.nav_create
                || tabId == R.id.nav_chats || tabId == R.id.nav_settings
                || tabId == R.id.nav_profile_tab;
    }
}

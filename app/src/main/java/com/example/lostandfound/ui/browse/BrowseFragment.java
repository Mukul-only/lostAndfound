package com.example.lostandfound.ui.browse;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.Configuration;
import android.os.Parcelable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.repository.ReportRepository;
import com.example.lostandfound.databinding.FragmentBrowseBinding;
import java.util.List;

public class BrowseFragment extends Fragment {
    private FragmentBrowseBinding binding;
    private ReportRepository reportRepository;
    private MasonryReportAdapter adapter;

    private String selectedType = "ALL";
    private String selectedCategory = "ALL";
    private String selectedLocation = "ALL";
    private String searchKeyword = "";

    // Query handed over from the Home search bar. May arrive before this
    // fragment's view exists (fragment transactions via commit() are async),
    // so it is buffered and applied in onViewCreated().
    private String pendingHomeSearchQuery = null;

    // Scroll-restore state, only reused when the filter signature is unchanged
    // (e.g. returning from Report Details), never across filter changes.
    private Parcelable pendingScrollState = null;
    private String pendingScrollSignature = null;

    private static final String[] CATEGORY_KEYS = {
            "ALL", "ELECTRONICS", "CARDS_ID", "KEYS", "BAGS_WALLETS", "CLOTHING", "BOOKS_STATIONERY", "OTHER"
    };
    private static final String[] CATEGORY_LABELS = {
            "All Categories", "Electronics", "Cards & Student ID", "Keys", "Bags & Wallets", "Clothing", "Books", "Other"
    };

    private static final String[] LOCATION_ITEMS = {
            "All Campus", "Library", "Student Center", "Science Hall", "Dining Hall", "Sports Complex", "Engineering Quad", "Dormitories", "Other"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentBrowseBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    private boolean panelOpen = false;
    private boolean suppressFilterCallbacks = false;

    private static final String STATE_TYPE = "browse_type";
    private static final String STATE_CATEGORY = "browse_category";
    private static final String STATE_LOCATION = "browse_location";
    private static final String STATE_SEARCH = "browse_search";
    private static final String STATE_PANEL = "browse_panel_open";

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        reportRepository = new ReportRepository(requireContext());

        if (savedInstanceState != null) {
            selectedType = savedInstanceState.getString(STATE_TYPE, "ALL");
            selectedCategory = savedInstanceState.getString(STATE_CATEGORY, "ALL");
            selectedLocation = savedInstanceState.getString(STATE_LOCATION, "ALL");
            searchKeyword = savedInstanceState.getString(STATE_SEARCH, "");
            panelOpen = savedInstanceState.getBoolean(STATE_PANEL, false);
        }

        // Apply a search handed over from the Home search bar before the view
        // existed, so loadReports() at the end of this method already runs with
        // the right keyword. Runs after state restoration so a fresh search wins.
        if (pendingHomeSearchQuery != null) {
            searchKeyword = pendingHomeSearchQuery;
            pendingHomeSearchQuery = null;
        }

        suppressFilterCallbacks = true;
        setupRecyclerView();
        setupFilterPanel();
        setupSearch();
        syncFilterUi();
        suppressFilterCallbacks = false;
        setupStatusBarInset();
        loadReports();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TYPE, selectedType);
        outState.putString(STATE_CATEGORY, selectedCategory);
        outState.putString(STATE_LOCATION, selectedLocation);
        outState.putString(STATE_SEARCH, searchKeyword);
        outState.putBoolean(STATE_PANEL, panelOpen);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadReports();
    }

    public void setSearchQuery(String query) {
        String trimmed = query != null ? query.trim() : "";
        if (binding == null) {
            // View not created yet (or already destroyed): buffer the query;
            // onViewCreated()/setupSearch() will apply it.
            pendingHomeSearchQuery = trimmed;
            return;
        }
        if (trimmed.equals(searchKeyword)) {
            // Nothing changed; still make sure the text field reflects it.
            binding.etBrowseSearch.setText(searchKeyword);
            return;
        }
        searchKeyword = trimmed;
        binding.etBrowseSearch.setText(searchKeyword);
    }

    /**
     * Column-count rule (documented):
     * - Tablets (smallest width >= 720dp): 4 columns; >= 600dp: 3 columns.
     * - Phones: effective width (widthDp / fontScale) >= 360dp: 3 columns,
     *   otherwise 2 columns (narrow screens or large accessibility text).
     */
    private int computeSpanCount() {
        Configuration config = getResources().getConfiguration();
        if (config.smallestScreenWidthDp >= 720) return 4;
        if (config.smallestScreenWidthDp >= 600) return 3;
        float effectiveWidth = config.screenWidthDp / Math.max(1f, config.fontScale);
        return effectiveWidth >= 360 ? 3 : 2;
    }

    private void setupRecyclerView() {
        adapter = new MasonryReportAdapter();
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(computeSpanCount(), StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        binding.rvBrowseReports.setLayoutManager(layoutManager);
        binding.rvBrowseReports.setAdapter(adapter);
        if (binding.rvBrowseReports.getItemDecorationCount() == 0) {
            binding.rvBrowseReports.addItemDecoration(new MasonrySpacingDecoration(requireContext(), 8));
        }

        binding.swipeRefreshBrowse.setOnRefreshListener(this::loadReports);
        binding.swipeRefreshBrowse.setColorSchemeColors(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.spotify_green));
        // The spinner's disc is white by default. SwipeRefreshLayout 1.1.0
        // declares an XML attr for this but never reads it, so it has to be set
        // in code or the circle renders light on the dark feed.
        binding.swipeRefreshBrowse.setProgressBackgroundColorSchemeColor(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.spotify_surface2));
        binding.btnBrowseRetry.setOnClickListener(v -> loadReports());
    }

    @Override
    public void onPause() {
        super.onPause();
        if (binding != null && binding.rvBrowseReports.getLayoutManager() != null) {
            pendingScrollState = binding.rvBrowseReports.getLayoutManager().onSaveInstanceState();
            pendingScrollSignature = currentFilterSignature();
        }
    }

    private String currentFilterSignature() {
        return selectedType + "|" + selectedCategory + "|" + selectedLocation + "|" + searchKeyword;
    }

    private void setupFilterPanel() {
        binding.btnFilterToggle.setOnClickListener(v -> setFilterPanelOpen(!panelOpen));

        // Mutually exclusive report-type options (same values/behavior as the old tabs)
        binding.chipGroupBrowseType.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (suppressFilterCallbacks || checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chipTypeLost) {
                selectedType = Report.TYPE_LOST;
            } else if (checkedId == R.id.chipTypeFound) {
                selectedType = Report.TYPE_FOUND;
            } else {
                selectedType = "ALL";
            }
            updateFilterIndicators();
            loadReports();
        });

        binding.btnCategoryFilter.setOnClickListener(v -> showCategoryDialog());
        binding.btnCampusFilter.setOnClickListener(v -> showCampusDialog());
    }

    private void setFilterPanelOpen(boolean open) {
        panelOpen = open;
        binding.layoutFilterPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        binding.btnFilterToggle.setContentDescription(
                getString(open ? R.string.browse_hide_filters : R.string.browse_show_filters));
    }

    private void showCategoryDialog() {
        int checked = 0;
        for (int i = 0; i < CATEGORY_KEYS.length; i++) {
            if (CATEGORY_KEYS[i].equals(selectedCategory)) {
                checked = i;
                break;
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.browse_title_category)
                .setSingleChoiceItems(CATEGORY_LABELS, checked, (dialog, which) -> {
                    selectedCategory = CATEGORY_KEYS[which];
                    updateFilterButtonLabels();
                    updateFilterIndicators();
                    loadReports();
                    dialog.dismiss();
                })
                .show();
    }

    private void showCampusDialog() {
        int checked = 0;
        for (int i = 0; i < LOCATION_ITEMS.length; i++) {
            if ((i == 0 && "ALL".equals(selectedLocation)) || LOCATION_ITEMS[i].equals(selectedLocation)) {
                checked = i;
                break;
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.browse_title_campus)
                .setSingleChoiceItems(LOCATION_ITEMS, checked, (dialog, which) -> {
                    selectedLocation = which == 0 ? "ALL" : LOCATION_ITEMS[which];
                    updateFilterButtonLabels();
                    updateFilterIndicators();
                    loadReports();
                    dialog.dismiss();
                })
                .show();
    }

    private String categoryLabel() {
        for (int i = 0; i < CATEGORY_KEYS.length; i++) {
            if (CATEGORY_KEYS[i].equals(selectedCategory)) return CATEGORY_LABELS[i];
        }
        return getString(R.string.browse_all_categories);
    }

    private void updateFilterButtonLabels() {
        binding.btnCategoryFilter.setText(categoryLabel());
        binding.btnCampusFilter.setText(
                "ALL".equals(selectedLocation) ? getString(R.string.browse_all_campus) : selectedLocation);
        styleSelectorButton(binding.btnCategoryFilter, !"ALL".equals(selectedCategory));
        styleSelectorButton(binding.btnCampusFilter, !"ALL".equals(selectedLocation));
    }

    private void styleSelectorButton(com.google.android.material.button.MaterialButton button, boolean active) {
        if (active) {
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.spotify_green));
            button.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.spotify_green)));
        } else {
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.spotify_text));
            button.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.spotify_border)));
        }
    }

    /** Reflects current filter state in chips, labels, dot, and panel visibility. */
    private void syncFilterUi() {
        if (Report.TYPE_LOST.equals(selectedType)) {
            binding.chipTypeLost.setChecked(true);
        } else if (Report.TYPE_FOUND.equals(selectedType)) {
            binding.chipTypeFound.setChecked(true);
        } else {
            binding.chipTypeAll.setChecked(true);
        }
        updateFilterButtonLabels();
        updateFilterIndicators();
        setFilterPanelOpen(panelOpen);
        if (!searchKeyword.isEmpty()) {
            binding.etBrowseSearch.setText(searchKeyword);
        }
    }

    /** Dot on the filter button whenever any non-default filter is active. */
    private void updateFilterIndicators() {
        boolean filtered = !"ALL".equals(selectedType)
                || !"ALL".equals(selectedCategory)
                || !"ALL".equals(selectedLocation);
        binding.viewFilterDot.setVisibility(filtered ? View.VISIBLE : View.GONE);
    }

    private void setupSearch() {
        binding.etBrowseSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchKeyword = s != null ? s.toString().trim() : "";
                binding.btnClearSearch.setVisibility(searchKeyword.isEmpty() ? View.GONE : View.VISIBLE);
                if (!suppressFilterCallbacks) {
                    loadReports();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        binding.btnClearSearch.setOnClickListener(v -> {
            binding.etBrowseSearch.setText("");
        });
    }

    private void loadReports() {
        if (binding == null) return; // Guard: view may be gone if a callback fires late
        binding.progressBrowse.setVisibility(View.VISIBLE);
        binding.layoutBrowseEmpty.setVisibility(View.GONE);

        reportRepository.getReports(selectedType, selectedCategory, selectedLocation, searchKeyword, new ReportRepository.DataCallback<List<Report>>() {
            @Override
            public void onSuccess(List<Report> reports) {
                if (!isAdded()) return;
                binding.progressBrowse.setVisibility(View.GONE);
                binding.swipeRefreshBrowse.setRefreshing(false);

                if (reports == null || reports.isEmpty()) {
                    binding.tvBrowseEmpty.setText("No reports found matching your filters.");
                    binding.layoutBrowseEmpty.setVisibility(View.VISIBLE);
                    adapter.setReports(null);
                } else {
                    binding.layoutBrowseEmpty.setVisibility(View.GONE);
                    adapter.setReports(reports);
                    restoreScrollIfFiltersUnchanged();
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                binding.progressBrowse.setVisibility(View.GONE);
                binding.swipeRefreshBrowse.setRefreshing(false);
                binding.tvBrowseEmpty.setText(message);
                binding.layoutBrowseEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void restoreScrollIfFiltersUnchanged() {
        if (pendingScrollState != null && pendingScrollSignature != null
                && pendingScrollSignature.equals(currentFilterSignature())
                && binding.rvBrowseReports.getLayoutManager() != null) {
            final android.os.Parcelable state = pendingScrollState;
            pendingScrollState = null;
            pendingScrollSignature = null;
            binding.rvBrowseReports.post(() -> {
                if (binding != null && binding.rvBrowseReports.getLayoutManager() != null) {
                    binding.rvBrowseReports.getLayoutManager().onRestoreInstanceState(state);
                }
            });
        } else {
            pendingScrollState = null;
            pendingScrollSignature = null;
        }
    }

    /** Push the top search/filter bar below the status bar on Android 15+ edge-to-edge. */
    private void setupStatusBarInset() {
        if (binding == null) return;
        final int basePaddingTop = binding.layoutBrowseTopBar.getPaddingTop();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                binding.layoutBrowseTopBar, (v, insets) -> {
            int top = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), basePaddingTop + top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

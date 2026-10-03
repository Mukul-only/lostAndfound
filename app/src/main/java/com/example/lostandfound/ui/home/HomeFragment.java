package com.example.lostandfound.ui.home;

import android.content.res.ColorStateList;
import com.bumptech.glide.Glide;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.util.ProfileUtils;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.lostandfound.MainActivity;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.repository.AuthRepository;
import com.example.lostandfound.data.repository.ReportRepository;
import com.example.lostandfound.databinding.FragmentHomeBinding;
import com.example.lostandfound.ui.auth.AuthActivity;
import com.example.lostandfound.ui.common.ReloadTracker;
import com.example.lostandfound.ui.report.ReportDetailActivity;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class HomeFragment extends Fragment {
    private FragmentHomeBinding binding;
    private ReportRepository reportRepository;
    private AuthRepository authRepository;
    private final ReloadTracker reloadTracker = new ReloadTracker();
    private HomeReportAdapter adapter;
    private CategoryChipAdapter categoryAdapter;

    // Filter state
    private String currentFilter = "ALL"; // ALL, FOUND_RECENTLY, STILL_LOST
    private String selectedCategory = "ALL";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        reportRepository = new ReportRepository(requireContext());
        authRepository = new AuthRepository(requireContext());

        setupInsets();
        setupRecyclerView();
        setupCategories();
        setupReportFilters();
        setupSearch();
        setupAvatarClick();
        setupReportActions();
        setupRetry();
        setupReload();
        loadReports();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyDarkStatusBar();
        refreshAvatar();
        loadReports();
    }

    /**
     * MainActivity switches tabs with hide()/show(), which do NOT drive the
     * fragment lifecycle, so onResume fires only once for the life of the
     * fragment and the header avatar stayed stale after a profile edit. This is
     * the documented companion to hide()/show() and fires on every show().
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            applyDarkStatusBar();
            refreshAvatar();
            loadReports();
        }
    }

    @Override
    public void onPause() {
        restoreLightStatusBar();
        super.onPause();
    }

    /** Global theme keeps a white status bar for light screens; Home paints it near-black while visible. */
    private void applyDarkStatusBar() {
        if (!isAdded()) return;
        android.view.Window window = requireActivity().getWindow();
        window.setStatusBarColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.spotify_bg));
        new androidx.core.view.WindowInsetsControllerCompat(window, window.getDecorView())
                .setAppearanceLightStatusBars(false);
    }

    /** Every screen is dark now, so the bar stays near-black with light icons. */
    private void restoreLightStatusBar() {
        if (!isAdded() || getActivity() == null) return;
        android.view.Window window = getActivity().getWindow();
        window.setStatusBarColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.spotify_bg));
        new androidx.core.view.WindowInsetsControllerCompat(window, window.getDecorView())
                .setAppearanceLightStatusBars(false);
    }

    /** Status-bar inset lands on the header; bottom nav inset is covered by list bottom padding. */
    private void setupInsets() {
        final int baseTop = binding.layoutHomeHeader.getPaddingTop();
        final int baseBottom = binding.layoutHomeHeader.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutHomeHeader, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), baseTop + top, v.getPaddingRight(), baseBottom);
            return insets;
        });
    }

    private void setupRecyclerView() {
        adapter = new HomeReportAdapter(report -> {
            Intent intent = new Intent(requireContext(), ReportDetailActivity.class);
            intent.putExtra("report_id", report.getId());
            startActivity(intent);
        });
        binding.rvRecentReports.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvRecentReports.setAdapter(adapter);
    }

    private void setupCategories() {
        categoryAdapter = new CategoryChipAdapter(new CategoryChipAdapter.OnCategoryClickListener() {
            @Override
            public void onCategoryClick(String categoryKey, String categoryLabel) {
                selectedCategory = categoryKey;
                binding.tvClearCategoryFilter.setVisibility(View.VISIBLE);
                loadReportsForNewFilters();
            }

            @Override
            public void onClearFilter() {
                selectedCategory = "ALL";
                binding.tvClearCategoryFilter.setVisibility(View.GONE);
                loadReportsForNewFilters();
            }
        });

        binding.rvCategories.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvCategories.setAdapter(categoryAdapter);

        binding.tvClearCategoryFilter.setOnClickListener(v -> categoryAdapter.clearSelection());
    }

    private void setupReportFilters() {
        binding.chipGroupReportFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chipFilterAll) {
                currentFilter = "ALL";
            } else if (checkedId == R.id.chipFilterFoundRecently) {
                currentFilter = "FOUND_RECENTLY";
            } else if (checkedId == R.id.chipFilterStillLost) {
                currentFilter = "STILL_LOST";
            }
            loadReportsForNewFilters();
        });
    }

    private void setupSearch() {
        binding.etHomeSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String query = binding.etHomeSearch.getText() != null ? binding.etHomeSearch.getText().toString().trim() : "";
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).selectBrowseTab(query);
                }
                return true;
            }
            return false;
        });
    }

    /** The top-bar avatar is the profile entry point: photo, else a neutral
        person glyph. No initials. Tapping it opens Profile. */
    private void setupAvatarClick() {
        binding.btnAvatar.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openProfile();
            }
        });
    }

    /** Refreshed on every resume so a photo edited in Settings shows up. */
    private void refreshAvatar() {
        if (binding == null || !isAdded()) return;
        SessionManager session = SessionManager.getInstance(requireContext());
        String url = SupabaseConfig.getPublicAvatarUrl(session.getAvatarPath());
        if (url != null) {
            Glide.with(this)
                    .load(SupabaseConfig.getGlideUrl(url))
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(binding.btnAvatar);
            binding.btnAvatar.setImageTintList(null);
        } else {
            Glide.with(this).clear(binding.btnAvatar);
            binding.btnAvatar.setImageResource(R.drawable.ic_person);
            binding.btnAvatar.setImageTintList(ColorStateList.valueOf(
                    requireContext().getColor(R.color.spotify_text2)));
            int pad = (int) (14 * getResources().getDisplayMetrics().density);
            binding.btnAvatar.setPadding(pad, pad, pad, pad);
        }
        binding.btnAvatar.setContentDescription(getString(R.string.nav_profile));
    }

    private void setupReportActions() {
        binding.btnLost.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openCreateReport(Report.TYPE_LOST);
            }
        });
        binding.btnFound.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openCreateReport(Report.TYPE_FOUND);
            }
        });
    }

    private void setupRetry() {
        binding.btnHomeRetry.setOnClickListener(v -> loadReports());
    }

    private void setupReload() {
        binding.btnReloadReports.setOnClickListener(v -> reloadReports());
    }

    private void loadReports() {
        fetchReports(false, false);
    }

    /**
     * Manual reload from the heading button. A tap while a load is in flight
     * is dropped, never queued behind it.
     */
    private void reloadReports() {
        fetchReports(true, false);
    }

    /**
     * Reload after the filters changed. The parameters changed, so any
     * in-flight response is obsolete and is superseded rather than awaited.
     */
    private void loadReportsForNewFilters() {
        fetchReports(false, true);
    }

    private void fetchReports(boolean manual, boolean force) {
        if (binding == null) return;
        if (force) {
            reloadTracker.forceStart();
        } else if (!reloadTracker.tryStart()) {
            return;
        }
        final int requestId = reloadTracker.currentId();

        if (manual) {
            // The list stays exactly as it is until the response lands; only
            // the heading spinner signals the reload.
            binding.progressReload.setVisibility(View.VISIBLE);
            binding.btnReloadReports.setVisibility(View.GONE);
            binding.layoutHomeError.setVisibility(View.GONE);
        } else {
            binding.progressHome.setVisibility(View.VISIBLE);
            binding.layoutHomeEmpty.setVisibility(View.GONE);
            binding.layoutHomeError.setVisibility(View.GONE);
        }

        String type = "ALL";
        String category = selectedCategory.equals("ALL") ? "ALL" : selectedCategory;

        if (currentFilter.equals("FOUND_RECENTLY")) {
            type = Report.TYPE_FOUND;
        } else if (currentFilter.equals("STILL_LOST")) {
            type = Report.TYPE_LOST;
        }

        reportRepository.getReports(type, category, "ALL", null, new ReportRepository.DataCallback<List<Report>>() {
            @Override
            public void onSuccess(List<Report> reports) {
                if (!isAdded() || binding == null || !reloadTracker.isCurrent(requestId)) return;
                reloadTracker.finish();
                binding.progressHome.setVisibility(View.GONE);
                binding.progressReload.setVisibility(View.GONE);
                binding.btnReloadReports.setVisibility(View.VISIBLE);

                List<Report> filteredReports = applyClientFilter(reports, currentFilter, new Date());
                if (filteredReports.isEmpty()) {
                    binding.layoutHomeEmpty.setVisibility(View.VISIBLE);
                    adapter.setReports(null);
                } else {
                    binding.layoutHomeEmpty.setVisibility(View.GONE);
                    adapter.setReports(filteredReports);
                    refreshPosterProfiles(filteredReports);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded() || binding == null || !reloadTracker.isCurrent(requestId)) return;
                reloadTracker.finish();
                binding.progressHome.setVisibility(View.GONE);
                binding.progressReload.setVisibility(View.GONE);
                binding.btnReloadReports.setVisibility(View.VISIBLE);
                binding.tvHomeError.setText(message != null && !message.trim().isEmpty()
                        ? message : getString(R.string.home_error_default));
                binding.layoutHomeError.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAuthFailure() {
                if (!isAdded() || binding == null || !reloadTracker.isCurrent(requestId)) return;
                reloadTracker.finish();
                binding.progressHome.setVisibility(View.GONE);
                binding.progressReload.setVisibility(View.GONE);
                binding.btnReloadReports.setVisibility(View.VISIBLE);
                // Same session flow as sign-out: wipe account state, then land
                // on AuthActivity with a cleared task.
                authRepository.logout(() -> {
                    if (!isAdded()) return;
                    Intent intent = new Intent(requireContext(), AuthActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    requireActivity().finish();
                });
            }
        });
    }

    /**
     * Client-side recency window for the FOUND_RECENTLY filter: found reports
     * from the last 7 days. Every other filter passes the server response
     * through untouched. Takes the clock explicitly so the window is testable;
     * callers pass the current time. Unparseable dates fail open to the list
     * rather than silently dropping reports.
     */
    static List<Report> applyClientFilter(List<Report> reports, String filter, Date now) {
        List<Report> filtered = new ArrayList<>();
        if (reports == null) {
            return filtered;
        }
        if (!"FOUND_RECENTLY".equals(filter)) {
            filtered.addAll(reports);
            return filtered;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.DAY_OF_YEAR, -7);
        Date cutoff = cal.getTime();
        for (Report r : reports) {
            if (r != null && r.isFound()) {
                try {
                    Date incidentDate = com.example.lostandfound.util.DateUtils.parseIsoDate(r.getIncidentDate());
                    if (incidentDate == null || incidentDate.after(cutoff)) {
                        filtered.add(r);
                    }
                } catch (Exception e) {
                    filtered.add(r);
                }
            }
        }
        return filtered;
    }

    private void refreshPosterProfiles(List<Report> reports) {
        if (!isAdded() || reports == null || reports.isEmpty()) return;
        List<String> ownerIds = new ArrayList<>();
        for (Report report : reports) {
            if (report != null && report.getOwnerId() != null) {
                ownerIds.add(report.getOwnerId());
            }
        }
        com.example.lostandfound.data.repository.ProfileDirectory.getInstance()
                .prefetch(requireContext(), ownerIds, () -> {
                    if (isAdded() && binding != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

package com.example.lostandfound.ui.profile;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.bumptech.glide.Glide;
import com.example.lostandfound.MainActivity;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.data.repository.ClaimRepository;
import com.example.lostandfound.data.repository.ProfileDirectory;
import com.example.lostandfound.data.repository.ProfileRepository;
import com.example.lostandfound.data.repository.ReportRepository;
import com.example.lostandfound.databinding.FragmentProfileBinding;
import com.example.lostandfound.ui.browse.MasonryReportAdapter;
import com.example.lostandfound.ui.browse.MasonrySpacingDecoration;
import com.example.lostandfound.ui.chat.ChatActivity;
import com.example.lostandfound.ui.claims.ClaimAdapter;
import com.example.lostandfound.ui.common.FeedDividerDecoration;
import com.example.lostandfound.ui.home.HomeReportAdapter;
import com.example.lostandfound.ui.report.ReportDetailActivity;
import com.example.lostandfound.util.ProfileUtils;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Profile: the signed-in user's identity header plus MY POSTS and
 * MY CLAIMS/RESPONSES. A main tab in MainActivity, so it owns no bottom bar
 * and no Back handling.
 *
 * Report presentations are borrowed from Browse (masonry grid) and Home
 * Recent Reports (list) rather than reimplemented, so cards stay identical
 * across the app.
 */
public class ProfileFragment extends Fragment {
    private static final int TAB_POSTS = 0;
    private static final int TAB_CLAIMS = 1;
    private static final boolean VIEW_GRID = true;

    private FragmentProfileBinding binding;
    private SessionManager sessionManager;
    private ReportRepository reportRepository;
    private ClaimRepository claimRepository;
    private ProfileRepository profileRepository;

    private MasonryReportAdapter masonryAdapter;
    private HomeReportAdapter listAdapter;
    private ClaimAdapter claimAdapter;

    /** Full claims view: a list tab also shows pending/accepted/rejected. */
    private RecyclerView.Adapter<?> claimsAdapter;

    /** Cached so a grid/list switch does not refetch. */
    private List<Report> myReports = null;
    private List<Claim> myClaims = null;

    private int currentTab = TAB_POSTS;
    private boolean gridView = VIEW_GRID;
    /** Guards a duplicate load when a tab/refresh event re-fires. */
    private boolean postsLoaded = false;
    private boolean claimsLoaded = false;
    private boolean postsLoading = false;
    private boolean claimsLoading = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sessionManager = SessionManager.getInstance(requireContext());
        reportRepository = new ReportRepository(requireContext());
        claimRepository = new ClaimRepository(requireContext());
        profileRepository = new ProfileRepository(requireContext());

        restoreState(savedInstanceState);

        displayUserInfo();
        setupAdapters();
        setupTabs();
        setupActions();
        refreshProfileFromServer();

        applyTab(currentTab);
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        currentTab = savedInstanceState.getInt("state_tab", TAB_POSTS);
        gridView = savedInstanceState.getBoolean("state_grid", VIEW_GRID);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("state_tab", currentTab);
        outState.putBoolean("state_grid", gridView);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) return;
        displayUserInfo();
        // Coming back from Report Details or a claim chat can change state.
        if (currentTab == TAB_POSTS) {
            if (!postsLoading) loadPosts();
        } else if (!claimsLoading) {
            loadClaims();
        }
    }

    private void displayUserInfo() {
        binding.tvProfileName.setText(sessionManager.getDisplayName());
        binding.tvProfileEmail.setText(sessionManager.getUserEmail());
        renderAvatar(sessionManager.getDisplayName(), sessionManager.getAvatarPath());
    }

    /** Photo, else the default glyph. Mirrors Settings so the two never
        disagree about the signed-in user. No initials: the default avatar is a
        neutral person glyph, since a letter is a decorative use of the name. */
    private void renderAvatar(String displayName, String avatarPath) {
        String url = SupabaseConfig.getPublicAvatarUrl(avatarPath);
        if (url != null) {
            binding.ivProfilePhoto.setVisibility(View.VISIBLE);
            binding.ivProfilePhoto.setImageTintList(null);
            binding.ivProfilePlaceholderIcon.setVisibility(View.GONE);
            Glide.with(this)
                    .load(SupabaseConfig.getGlideUrl(url))
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(binding.ivProfilePhoto);
        } else {
            Glide.with(this).clear(binding.ivProfilePhoto);
            binding.ivProfilePhoto.setVisibility(View.GONE);
            binding.ivProfilePlaceholderIcon.setVisibility(View.VISIBLE);
            binding.ivProfilePlaceholderIcon.setImageTintList(
                    ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
        }
    }

    private void refreshProfileFromServer() {
        profileRepository.fetchMyProfile(new ProfileRepository.DataCallback<Profile>() {
            @Override
            public void onSuccess(Profile profile) {
                if (binding == null || profile == null) return;
                // Cache for Settings and the rest of the shell.
                sessionManager.saveProfile(profile);
                displayUserInfo();
            }

            @Override
            public void onError(String message) {
                // Best-effort: the locally cached profile stays visible.
            }
        });
    }

    private void setupAdapters() {
        masonryAdapter = new MasonryReportAdapter();
        listAdapter = new HomeReportAdapter(report -> openReportDetail(report.getId()));
        claimAdapter = new ClaimAdapter(false, new ClaimAdapter.OnClaimActionListener() {
            @Override
            public void onAccept(Claim claim) {}

            @Override
            public void onReject(Claim claim) {}

            @Override
            public void onOpenChat(Claim claim) {
                if (!isAdded() || claim == null) return;
                Intent intent = new Intent(requireContext(), ChatActivity.class);
                intent.putExtra("claim_id", claim.getId());
                intent.putExtra("report_id", claim.getReportId());
                startActivity(intent);
            }
        });
        claimsAdapter = claimAdapter;
    }

    private void setupTabs() {
        binding.tabLayoutProfile.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                applyTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        if (currentTab != binding.tabLayoutProfile.getSelectedTabPosition()) {
            binding.tabLayoutProfile.getTabAt(currentTab).select();
        }
    }

    /** Applies tab selection and the matching adapter without refetching. */
    private void applyTab(int position) {
        currentTab = position;
        // The view switch belongs to My Posts only.
        binding.layoutPostsHeader.setVisibility(position == TAB_POSTS ? View.VISIBLE : View.GONE);

        if (position == TAB_POSTS) {
            binding.rvProfileContent.setLayoutManager(gridView
                    ? new StaggeredGridLayoutManager(computeSpanCount(), StaggeredGridLayoutManager.VERTICAL)
                    : new LinearLayoutManager(requireContext()));
            binding.rvProfileContent.setAdapter(gridView ? masonryAdapter : listAdapter);
            if (binding.rvProfileContent.getItemDecorationCount() == 0) {
                binding.rvProfileContent.addItemDecoration(gridView
                        ? new MasonrySpacingDecoration(requireContext(), 8)
                        : new FeedDividerDecoration(requireContext()));
            }
            updateToggleIcon();
            if (!postsLoaded) {
                loadPosts();
            } else {
                renderPostsState();
            }
        } else {
            binding.rvProfileContent.setLayoutManager(new LinearLayoutManager(requireContext()));
            binding.rvProfileContent.setAdapter(claimsAdapter);
            // Claim cards carry their own vertical margins, so a divider would
            // only double the separation. Clearing (rather than conditionally
            // adding) also drops whatever decoration the posts tab installed,
            // which otherwise leaks across tab switches.
            for (int i = binding.rvProfileContent.getItemDecorationCount() - 1; i >= 0; i--) {
                binding.rvProfileContent.removeItemDecoration(
                        binding.rvProfileContent.getItemDecorationAt(i));
            }
            if (!claimsLoaded) {
                loadClaims();
            } else {
                renderClaimsState();
            }
        }
    }

    private void setupActions() {
        binding.btnProfileMenu.setOnClickListener(v -> openSettings());
        binding.btnProfileBack.setOnClickListener(v -> closeProfile());

        // One switch for the whole list, not a control on every card.
        binding.btnViewToggle.setOnClickListener(v -> {
            if (currentTab != TAB_POSTS) return;
            gridView = !gridView;
            applyTab(TAB_POSTS);
            binding.rvProfileContent.scrollToPosition(0);
        });

        binding.swipeRefreshProfile.setOnRefreshListener(() -> {
            if (currentTab == TAB_POSTS) {
                postsLoaded = false;
                loadPosts();
            } else {
                claimsLoaded = false;
                loadClaims();
            }
        });
        binding.swipeRefreshProfile.setColorSchemeColors(
                requireContext().getColor(R.color.spotify_green));
        // White by default; the XML attr is never read by the library, so set it here.
        binding.swipeRefreshProfile.setProgressBackgroundColorSchemeColor(
                requireContext().getColor(R.color.spotify_surface2));

        binding.btnProfileRetry.setOnClickListener(v -> {
            if (currentTab == TAB_POSTS) {
                postsLoaded = false;
                loadPosts();
            } else {
                claimsLoaded = false;
                loadClaims();
            }
        });
    }

    /** Profile sits on top of Home, so Back returns there. */
    private void closeProfile() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).closeProfile();
        }
    }

    /** Settings is a bottom-bar tab, so this just switches to it. */
    private void openSettings() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).selectTabById(R.id.nav_settings);
        }
    }

    private void openReportDetail(String reportId) {
        if (!isAdded() || reportId == null) return;
        Intent intent = new Intent(requireContext(), ReportDetailActivity.class);
        intent.putExtra("report_id", reportId);
        startActivity(intent);
    }

    private int computeSpanCount() {
        Configuration config = getResources().getConfiguration();
        if (config.smallestScreenWidthDp >= 720) return 4;
        if (config.smallestScreenWidthDp >= 600) return 3;
        float effectiveWidth = config.screenWidthDp / Math.max(1f, config.fontScale);
        return effectiveWidth >= 360 ? 3 : 2;
    }

    private void updateToggleIcon() {
        // Icon shows the layout you will switch TO.
        ImageView toggle = binding.btnViewToggle;
        toggle.setImageResource(gridView ? R.drawable.ic_list : R.drawable.ic_grid);
        toggle.setContentDescription(getString(
                gridView ? R.string.profile_view_list : R.string.profile_view_grid));
    }

    private void loadPosts() {
        if (postsLoading || !isAdded()) return;
        postsLoading = true;
        binding.progressProfile.setVisibility(View.VISIBLE);
        binding.layoutProfileError.setVisibility(View.GONE);
        binding.tvProfileEmpty.setVisibility(View.GONE);

        // Scoped to the signed-in user by RLS on reports.owner_id.
        reportRepository.getMyReports(new ReportRepository.DataCallback<List<Report>>() {
            @Override
            public void onSuccess(List<Report> reports) {
                if (binding == null) return;
                postsLoading = false;
                postsLoaded = true;
                myReports = reports;
                masonryAdapter.setReports(reports);
                listAdapter.setReports(reports);
                renderPostsState();
                refreshPosterProfiles(reports);
            }

            @Override
            public void onError(String message) {
                if (binding == null) return;
                postsLoading = false;
                binding.swipeRefreshProfile.setRefreshing(false);
                binding.progressProfile.setVisibility(View.GONE);
                binding.tvProfileError.setText(message);
                binding.layoutProfileError.setVisibility(View.VISIBLE);
            }
        });
    }


    private void renderPostsState() {
        binding.swipeRefreshProfile.setRefreshing(false);
        binding.progressProfile.setVisibility(View.GONE);
        binding.layoutProfileError.setVisibility(View.GONE);
        boolean empty = myReports == null || myReports.isEmpty();
        binding.tvProfileEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            binding.tvProfileEmpty.setText(R.string.profile_empty_posts);
        }
    }

    private void loadClaims() {
        if (claimsLoading || !isAdded()) return;
        claimsLoading = true;
        binding.progressProfile.setVisibility(View.VISIBLE);
        binding.layoutProfileError.setVisibility(View.GONE);
        binding.tvProfileEmpty.setVisibility(View.GONE);

        claimRepository.getMyClaims(new ClaimRepository.DataCallback<List<Claim>>() {
            @Override
            public void onSuccess(List<Claim> claims) {
                if (binding == null) return;
                claimsLoading = false;
                claimsLoaded = true;
                myClaims = claims;
                claimAdapter.setClaims(claims);
                renderClaimsState();
                refreshClaimantProfiles(claims);
            }

            @Override
            public void onError(String message) {
                if (binding == null) return;
                claimsLoading = false;
                binding.swipeRefreshProfile.setRefreshing(false);
                binding.progressProfile.setVisibility(View.GONE);
                binding.tvProfileError.setText(message);
                binding.layoutProfileError.setVisibility(View.VISIBLE);
            }
        });
    }

    private void renderClaimsState() {
        binding.swipeRefreshProfile.setRefreshing(false);
        binding.progressProfile.setVisibility(View.GONE);
        binding.layoutProfileError.setVisibility(View.GONE);
        boolean empty = myClaims == null || myClaims.isEmpty();
        binding.tvProfileEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            binding.tvProfileEmpty.setText(R.string.profile_empty_claims);
        }
    }

    private void refreshPosterProfiles(List<Report> reports) {
        if (!isAdded() || reports == null || reports.isEmpty()) return;
        List<String> ownerIds = new ArrayList<>();
        for (Report report : reports) {
            if (report != null && report.getOwnerId() != null) {
                ownerIds.add(report.getOwnerId());
            }
        }
        ProfileDirectory.getInstance().prefetch(requireContext(), ownerIds, () -> {
            if (isAdded() && binding != null) {
                masonryAdapter.notifyDataSetChanged();
                listAdapter.notifyDataSetChanged();
            }
        });
    }

    private void refreshClaimantProfiles(List<Claim> claims) {
        if (!isAdded() || claims == null || claims.isEmpty()) return;
        List<String> claimantIds = new ArrayList<>();
        for (Claim claim : claims) {
            if (claim != null && claim.getClaimantId() != null) {
                claimantIds.add(claim.getClaimantId());
            }
        }
        ProfileDirectory.getInstance().prefetch(requireContext(), claimantIds, () -> {
            if (isAdded() && binding != null) {
                claimAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
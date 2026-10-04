package com.example.lostandfound.ui.report;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import android.graphics.drawable.Drawable;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.lostandfound.BuildConfig;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.data.model.ReportPrivateDetail;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.data.repository.ClaimRepository;
import com.example.lostandfound.data.repository.ReportRepository;
import com.example.lostandfound.databinding.ActivityReportDetailBinding;
import com.example.lostandfound.ui.chat.ChatActivity;
import com.example.lostandfound.ui.claims.ClaimsReviewActivity;
import com.example.lostandfound.ui.claims.SubmitClaimBottomSheet;
import com.example.lostandfound.ui.dialogs.ReportAbuseDialog;
import com.example.lostandfound.ui.map.LocationPickerActivity;
import com.example.lostandfound.util.DateUtils;
import com.example.lostandfound.util.StatusUtils;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import java.util.Locale;

public class ReportDetailActivity extends AppCompatActivity {
    private ActivityReportDetailBinding binding;
    private ReportRepository reportRepository;
    private ClaimRepository claimRepository;
    private SessionManager sessionManager;
    private String reportId;
    private Report currentReport;
    /** ViewBinding does not emit a field for a <fragment> tag, so the map is
     *  resolved by id after setContentView, the same way LocationPickerActivity
     *  does it. */
    private SupportMapFragment mapFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityReportDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        reportRepository = new ReportRepository(this);
        claimRepository = new ClaimRepository(this);
        sessionManager = SessionManager.getInstance(this);

        reportId = getIntent().getStringExtra("report_id");
        if (reportId == null) {
            Toast.makeText(this, R.string.detail_invalid_id, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        binding.btnDetailBack.setOnClickListener(v -> finish());
        binding.btnReportPost.setOnClickListener(v -> {
            ReportAbuseDialog dialog = ReportAbuseDialog.newInstance(reportId);
            dialog.show(getSupportFragmentManager(), "report_abuse");
        });

        applyStatusBarInset();

        mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mapDetailView);
        if (mapFragment != null) mapFragment.onCreate(savedInstanceState);
        loadReportDetails();
    }

    /** Push the top toolbar below the status bar on Android 15+ edge-to-edge. */
    private void applyStatusBarInset() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                binding.layoutDetailToolbar, (v, insets) -> {
            int top = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapFragment != null) mapFragment.onResume();
        loadReportDetails();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapFragment != null) mapFragment.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapFragment != null) mapFragment.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapFragment != null) mapFragment.onLowMemory();
    }

    private void loadReportDetails() {
        binding.progressDetail.setVisibility(View.VISIBLE);
        binding.layoutDetailContent.setVisibility(View.GONE);

        reportRepository.getReportById(reportId, new ReportRepository.DataCallback<Report>() {
            @Override
            public void onSuccess(Report report) {
                binding.progressDetail.setVisibility(View.GONE);
                binding.layoutDetailContent.setVisibility(View.VISIBLE);
                currentReport = report;
                populateViews(report);
            }

            @Override
            public void onError(String message) {
                binding.progressDetail.setVisibility(View.GONE);
                Toast.makeText(ReportDetailActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void populateViews(Report report) {
        binding.tvDetailTitle.setText(report.getTitle());

        // Category, location and date are separate rows now, so the icons carry
        // the meaning and each value can wrap on its own instead of being
        // concatenated into one clipping line.
        binding.ivDetailCategoryIcon.setImageResource(
                StatusUtils.getCategoryIconRes(report.getCategory()));
        binding.tvDetailCategory.setText(StatusUtils.getCategoryLabel(report.getCategory()));
        binding.tvDetailLocation.setText(report.getCampusLocation());

        String timePart = report.getIncidentTimeApprox() != null && !report.getIncidentTimeApprox().trim().isEmpty()
                ? " · " + DateUtils.formatDisplayTimeFromStorage(this, report.getIncidentTimeApprox())
                : "";
        binding.tvDetailDate.setText(
                DateUtils.formatDisplayDate(report.getIncidentDate()) + timePart);

        String liveOwnerName = com.example.lostandfound.data.repository.ProfileDirectory.getInstance()
                .getDisplayName(report.getOwnerId(), report.getOwnerName());
        binding.tvDetailOwner.setText(getString(R.string.detail_reported_by_value, liveOwnerName));
        if (report.getOwnerId() != null) {
            java.util.List<String> ownerIds = new java.util.ArrayList<>();
            ownerIds.add(report.getOwnerId());
            final String detailReportId = report.getId();
            com.example.lostandfound.data.repository.ProfileDirectory.getInstance()
                    .prefetch(this, ownerIds, () -> {
                        if (currentReport != null && detailReportId != null
                                && detailReportId.equals(currentReport.getId())) {
                            String refreshedName = com.example.lostandfound.data.repository.ProfileDirectory
                                    .getInstance().getDisplayName(
                                            currentReport.getOwnerId(), currentReport.getOwnerName());
                            binding.tvDetailOwner.setText(
                                    getString(R.string.detail_reported_by_value, refreshedName));
                        }
                    });
        }
        binding.tvDetailDescription.setText(report.getDescription());

        // Type Badge
        if (report.isLost()) {
            binding.tvDetailTypeBadge.setText(R.string.detail_lost_item);
            binding.tvDetailTypeBadge.setTextColor(getColor(R.color.spotify_error));
            binding.tvDetailTypeBadge.setBackgroundResource(R.drawable.bg_spotify_badge_lost);
        } else {
            binding.tvDetailTypeBadge.setText(R.string.detail_found_item);
            binding.tvDetailTypeBadge.setTextColor(getColor(R.color.spotify_on_green));
            binding.tvDetailTypeBadge.setBackgroundResource(R.drawable.bg_spotify_badge_found);
        }

        // Status Badge
        binding.tvDetailStatusBadge.setText(StatusUtils.getStatusLabel(report.getStatus()));
        binding.tvDetailStatusBadge.setTextColor(StatusUtils.getStatusColor(this, report.getStatus()));

        // Public Image handling
        String imageUrl = SupabaseConfig.getPublicImageUrl(report.getImageUrl());
        if (BuildConfig.DEBUG) {
            Log.d("ReportDetailActivity", "Detail report " + report.getId() + " - raw image_url: " + report.getImageUrl() + " -> resolved: " + imageUrl);
        }

        if (imageUrl != null && !imageUrl.isEmpty()) {
            binding.cardDetailPhoto.setVisibility(View.VISIBLE);
            binding.layoutNoPhoto.setVisibility(View.GONE);
            binding.ivDetailPhoto.setVisibility(View.VISIBLE);
            binding.ivDetailPhoto.setImageTintList(null);

            Glide.with(this)
                    .load(SupabaseConfig.getGlideUrl(imageUrl))
                    .placeholder(R.drawable.ic_photo)
                    .error(R.drawable.ic_photo)
                    .centerCrop()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            if (BuildConfig.DEBUG) {
                                Log.w("ReportDetailActivity", "Glide failed to load detail photo: " + imageUrl, e);
                            }
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            if (BuildConfig.DEBUG) {
                                Log.d("ReportDetailActivity", "Glide loaded detail photo from " + dataSource + " for " + imageUrl);
                            }
                            binding.ivDetailPhoto.setImageTintList(null);
                            return false;
                        }
                    })
                    .into(binding.ivDetailPhoto);
            final String fullPhotoPath = report.getImageUrl();
            binding.ivDetailPhoto.setOnClickListener(v -> {
                Intent viewerIntent = new Intent(ReportDetailActivity.this, ImageViewerActivity.class);
                viewerIntent.putExtra(ImageViewerActivity.EXTRA_STORAGE_PATH, fullPhotoPath);
                startActivity(viewerIntent);
            });
        } else {
            Glide.with(this).clear(binding.ivDetailPhoto);
            binding.ivDetailPhoto.setVisibility(View.GONE);
            binding.ivDetailPhoto.setOnClickListener(null);
            binding.cardDetailPhoto.setVisibility(View.GONE);
            binding.layoutNoPhoto.setVisibility(View.VISIBLE);
        }

        // Public verification question (for FOUND reports)
        if (report.isFound() && report.getPublicVerificationQuestion() != null && !report.getPublicVerificationQuestion().trim().isEmpty()) {
            binding.cardPublicQuestion.setVisibility(View.VISIBLE);
            binding.tvPublicQuestionText.setText(report.getPublicVerificationQuestion());
        } else {
            binding.cardPublicQuestion.setVisibility(View.GONE);
        }

        // Map Preview (Location pin)
        if (report.hasCoordinates()) {
            binding.cardFoundMapLocation.setVisibility(View.VISIBLE);
            String pinLabel = getString(report.isLost()
                    ? R.string.detail_map_last_seen : R.string.detail_map_found);
            String pinNote = getString(report.isLost()
                    ? R.string.detail_pin_note_last_seen : R.string.detail_pin_note_found);
            binding.tvMapCardTitle.setText(getString(report.isLost()
                    ? R.string.detail_map_last_seen_title : R.string.detail_map_found_title));
            binding.tvDetailPinCoordinates.setText(String.format(Locale.US,
                    getString(R.string.detail_pin_coordinates),
                    report.getLatitude(), report.getLongitude(), pinNote));

            if (mapFragment == null) return;
            mapFragment.getMapAsync(googleMap -> {
                LatLng pos = new LatLng(report.getLatitude(), report.getLongitude());
                googleMap.clear();
                googleMap.addMarker(new MarkerOptions()
                        .position(pos)
                        .title(pinLabel)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15.5f));
                googleMap.getUiSettings().setMapToolbarEnabled(false);
                googleMap.setOnMapClickListener(latLng -> openFullscreenMap(report));
            });

            binding.cardFoundMapLocation.setOnClickListener(v -> openFullscreenMap(report));
            binding.tvOpenMapFullscreen.setOnClickListener(v -> openFullscreenMap(report));
        } else {
            // Backward compatibility: hide map completely for reports without coordinates
            binding.cardFoundMapLocation.setVisibility(View.GONE);
        }

        // Owner vs Visitor logic
        boolean isOwner = sessionManager.getUserId() != null && sessionManager.getUserId().equals(report.getOwnerId());

        if (isOwner) {
            binding.layoutOwnerActions.setVisibility(View.VISIBLE);
            binding.layoutVisitorActions.setVisibility(View.GONE);
            binding.btnReportPost.setVisibility(View.GONE);

            // Fetch finder's secret identifying detail (Owner only). Reset first
            // so a reload that no longer returns a note cannot leave the previous
            // one on screen.
            binding.cardPrivateSecretNote.setVisibility(View.GONE);
            if (report.isFound()) {
                reportRepository.getPrivateDetails(report.getId(), new ReportRepository.DataCallback<ReportPrivateDetail>() {
                    @Override
                    public void onSuccess(ReportPrivateDetail detail) {
                        if (detail != null && detail.getFinderPrivateNotes() != null) {
                            binding.cardPrivateSecretNote.setVisibility(View.VISIBLE);
                            binding.tvPrivateNoteText.setText(detail.getFinderPrivateNotes());
                        }
                    }

                    @Override
                    public void onError(String message) {}
                });
            }

            setupOwnerActions(report);
        } else {
            binding.layoutOwnerActions.setVisibility(View.GONE);
            binding.layoutVisitorActions.setVisibility(View.VISIBLE);
            binding.btnReportPost.setVisibility(View.VISIBLE);
            binding.cardPrivateSecretNote.setVisibility(View.GONE);

            setupVisitorActions(report);
        }
    }

    private void setupOwnerActions(Report report) {
        binding.btnReviewClaims.setOnClickListener(v -> {
            Intent intent = new Intent(this, ClaimsReviewActivity.class);
            intent.putExtra("report_id", report.getId());
            intent.putExtra("report_title", report.getTitle());
            startActivity(intent);
        });

        // Hide mark returned or close if already in terminal state
        if (report.isReturned() || report.isClosed()) {
            binding.btnMarkReturned.setEnabled(false);
            binding.btnCloseReport.setEnabled(false);
        } else {
            binding.btnMarkReturned.setEnabled(true);
            binding.btnCloseReport.setEnabled(true);

            binding.btnMarkReturned.setOnClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.detail_mark_returned_title)
                        .setMessage(R.string.detail_mark_returned_message)
                        .setPositiveButton(R.string.detail_mark_returned_confirm, (dialog, which) -> {
                            reportRepository.markReportReturned(report.getId(), new ReportRepository.DataCallback<RpcResponse>() {
                                @Override
                                public void onSuccess(RpcResponse data) {
                                    Toast.makeText(ReportDetailActivity.this, R.string.detail_mark_returned_done, Toast.LENGTH_SHORT).show();
                                    loadReportDetails();
                                }

                                @Override
                                public void onError(String message) {
                                    Toast.makeText(ReportDetailActivity.this, message, Toast.LENGTH_SHORT).show();
                                }
                            });
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            binding.btnCloseReport.setOnClickListener(v -> {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.detail_close_title)
                        .setMessage(R.string.detail_close_message)
                        .setPositiveButton(R.string.detail_close_confirm, (dialog, which) -> {
                            reportRepository.closeReport(report.getId(), new ReportRepository.DataCallback<RpcResponse>() {
                                @Override
                                public void onSuccess(RpcResponse data) {
                                    Toast.makeText(ReportDetailActivity.this, R.string.detail_close_done, Toast.LENGTH_SHORT).show();
                                    loadReportDetails();
                                }

                                @Override
                                public void onError(String message) {
                                    Toast.makeText(ReportDetailActivity.this, message, Toast.LENGTH_SHORT).show();
                                }
                            });
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }
    }

    private void setupVisitorActions(Report report) {
        // Initial state while checking claim
        if (!report.isOpen()) {
            binding.btnRespondOrClaim.setVisibility(View.GONE);
            binding.cardClaimStatusPending.setVisibility(View.GONE);
            binding.cardClaimStatusAccepted.setVisibility(View.GONE);
            binding.cardClaimStatusRejected.setVisibility(View.GONE);
            binding.tvNotOpenNotice.setVisibility(View.VISIBLE);
        } else {
            binding.tvNotOpenNotice.setVisibility(View.GONE);
        }

        // Configure default button text if allowed to claim
        binding.btnRespondOrClaim.setText(report.isFound()
                ? R.string.detail_claim_item : R.string.detail_claim_found);

        binding.btnRespondOrClaim.setOnClickListener(v -> {
            SubmitClaimBottomSheet sheet = SubmitClaimBottomSheet.newInstance(report);
            sheet.setOnClaimSubmittedListener(this::loadReportDetails);
            sheet.show(getSupportFragmentManager(), "submit_claim");
        });

        // Query the current signed-in user's claim for this report
        claimRepository.getMyClaimForReport(report.getId(), new ClaimRepository.DataCallback<Claim>() {
            @Override
            public void onSuccess(Claim claim) {
                if (claim == null) {
                    // No claim submitted yet
                    binding.cardClaimStatusPending.setVisibility(View.GONE);
                    binding.cardClaimStatusAccepted.setVisibility(View.GONE);
                    binding.cardClaimStatusRejected.setVisibility(View.GONE);

                    if (report.isOpen()) {
                        binding.btnRespondOrClaim.setVisibility(View.VISIBLE);
                        binding.tvNotOpenNotice.setVisibility(View.GONE);
                    } else {
                        binding.btnRespondOrClaim.setVisibility(View.GONE);
                        binding.tvNotOpenNotice.setVisibility(View.VISIBLE);
                    }
                    return;
                }

                // User has already submitted a claim: hide the default claim button
                binding.btnRespondOrClaim.setVisibility(View.GONE);
                binding.tvNotOpenNotice.setVisibility(View.GONE);

                if (claim.isRejected()) {
                    binding.cardClaimStatusPending.setVisibility(View.GONE);
                    binding.cardClaimStatusAccepted.setVisibility(View.GONE);
                    binding.cardClaimStatusRejected.setVisibility(View.VISIBLE);

                    if (claim.getRejectionMessage() != null && !claim.getRejectionMessage().trim().isEmpty()) {
                        binding.tvClaimStatusRejectedNote.setText(
                                getString(R.string.detail_claim_rejected_note, claim.getRejectionMessage().trim()));
                        binding.tvClaimStatusRejectedNote.setVisibility(View.VISIBLE);
                    } else {
                        binding.tvClaimStatusRejectedNote.setVisibility(View.GONE);
                    }
                } else if (claim.isAccepted()) {
                    binding.cardClaimStatusPending.setVisibility(View.GONE);
                    binding.cardClaimStatusRejected.setVisibility(View.GONE);
                    binding.cardClaimStatusAccepted.setVisibility(View.VISIBLE);

                    binding.btnOpenChatAccepted.setOnClickListener(v -> {
                        Intent intent = new Intent(ReportDetailActivity.this, ChatActivity.class);
                        intent.putExtra("claim_id", claim.getId());
                        intent.putExtra("report_id", report.getId());
                        intent.putExtra("report_title", report.getTitle());
                        startActivity(intent);
                    });
                } else {
                    // PENDING claim
                    binding.cardClaimStatusAccepted.setVisibility(View.GONE);
                    binding.cardClaimStatusRejected.setVisibility(View.GONE);
                    binding.cardClaimStatusPending.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(String message) {
                // If query fails, maintain default fallback
                if (report.isOpen()) {
                    binding.btnRespondOrClaim.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void openFullscreenMap(Report report) {
        if (report == null || !report.hasCoordinates()) {
            Toast.makeText(this, R.string.detail_location_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, LocationPickerActivity.class);
        intent.putExtra(LocationPickerActivity.EXTRA_MODE, LocationPickerActivity.MODE_VIEW_LOCATION);
        intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LAT, report.getLatitude());
        intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LNG, report.getLongitude());
        String pinTitle = report.isLost() ? "Last seen: " : "Found: ";
        intent.putExtra(LocationPickerActivity.EXTRA_TITLE, pinTitle + report.getTitle());
        intent.putExtra(LocationPickerActivity.EXTRA_NOTE,
                getString(R.string.detail_location) + ": " + report.getCampusLocation());
        startActivity(intent);
    }
}

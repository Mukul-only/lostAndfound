package com.example.lostandfound.ui.claims;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import android.widget.EditText;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.repository.ClaimRepository;
import com.example.lostandfound.databinding.ActivityClaimsReviewBinding;
import com.example.lostandfound.ui.chat.ChatActivity;
import java.util.List;

public class ClaimsReviewActivity extends AppCompatActivity {
    private ActivityClaimsReviewBinding binding;
    private ClaimRepository claimRepository;
    private ClaimAdapter adapter;
    private String reportId;
    private String reportTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityClaimsReviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        claimRepository = new ClaimRepository(this);

        reportId = getIntent().getStringExtra("report_id");
        reportTitle = getIntent().getStringExtra("report_title");

        binding.btnClaimsBack.setOnClickListener(v -> finish());

        setupRecyclerView();
        loadClaims();
    }

    private void setupRecyclerView() {
        adapter = new ClaimAdapter(true, new ClaimAdapter.OnClaimActionListener() {
            @Override
            public void onAccept(Claim claim) {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ClaimsReviewActivity.this)
                        .setTitle("Accept Claim")
                        .setMessage("Accepting this claim will mark the item as 'Handover Arranged', decline other pending responses, and open a private 1-on-1 handover chat with " + claim.getClaimantName() + ".\n\nProceed?")
                        .setPositiveButton("Accept & Open Chat", (dialog, which) -> {
                            performAccept(claim);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }

            @Override
            public void onReject(Claim claim) {
                showRejectDialog(claim);
            }

            @Override
            public void onOpenChat(Claim claim) {
                Intent intent = new Intent(ClaimsReviewActivity.this, ChatActivity.class);
                intent.putExtra("claim_id", claim.getId());
                intent.putExtra("report_id", reportId);
                intent.putExtra("report_title", reportTitle);
                startActivity(intent);
            }
        });

        binding.rvReviewClaims.setLayoutManager(new LinearLayoutManager(this));
        binding.rvReviewClaims.setAdapter(adapter);
        if (binding.rvReviewClaims.getItemDecorationCount() == 0) {
            binding.rvReviewClaims.addItemDecoration(
                    new com.example.lostandfound.ui.common.FeedDividerDecoration(this));
        }

        binding.swipeRefreshClaims.setOnRefreshListener(this::loadClaims);
        binding.swipeRefreshClaims.setColorSchemeColors(getColor(R.color.spotify_green));
        // White by default; the XML attr is never read by the library, so set it here.
        binding.swipeRefreshClaims.setProgressBackgroundColorSchemeColor(getColor(R.color.spotify_surface2));
    }

    private void loadClaims() {
        if (reportId == null) return;

        binding.progressReviewClaims.setVisibility(View.VISIBLE);
        binding.tvReviewClaimsEmpty.setVisibility(View.GONE);

        claimRepository.getClaimsForReport(reportId, new ClaimRepository.DataCallback<List<Claim>>() {
            @Override
            public void onSuccess(List<Claim> claims) {
                binding.progressReviewClaims.setVisibility(View.GONE);
                binding.swipeRefreshClaims.setRefreshing(false);

                if (claims == null || claims.isEmpty()) {
                    binding.tvReviewClaimsEmpty.setVisibility(View.VISIBLE);
                    adapter.setClaims(null);
                } else {
                    binding.tvReviewClaimsEmpty.setVisibility(View.GONE);
                    adapter.setClaims(claims);
                    java.util.List<String> claimantIds = new java.util.ArrayList<>();
                    for (Claim claim : claims) {
                        if (claim != null && claim.getClaimantId() != null) {
                            claimantIds.add(claim.getClaimantId());
                        }
                    }
                    com.example.lostandfound.data.repository.ProfileDirectory.getInstance()
                            .prefetch(ClaimsReviewActivity.this, claimantIds,
                                    () -> adapter.notifyDataSetChanged());
                }
            }

            @Override
            public void onError(String message) {
                binding.progressReviewClaims.setVisibility(View.GONE);
                binding.swipeRefreshClaims.setRefreshing(false);
                binding.tvReviewClaimsEmpty.setText(message);
                binding.tvReviewClaimsEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void performAccept(Claim claim) {
        binding.progressReviewClaims.setVisibility(View.VISIBLE);

        claimRepository.acceptClaim(claim.getId(), new ClaimRepository.DataCallback<RpcResponse>() {
            @Override
            public void onSuccess(RpcResponse response) {
                binding.progressReviewClaims.setVisibility(View.GONE);
                Toast.makeText(ClaimsReviewActivity.this, "Claim accepted! Opening chat...", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent(ClaimsReviewActivity.this, ChatActivity.class);
                intent.putExtra("conversation_id", response.getConversationId());
                intent.putExtra("claim_id", claim.getId());
                intent.putExtra("report_id", reportId);
                intent.putExtra("report_title", reportTitle);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(String message) {
                binding.progressReviewClaims.setVisibility(View.GONE);
                Toast.makeText(ClaimsReviewActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showRejectDialog(Claim claim) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_reject_claim, null);
        EditText etRejectionMessage = dialogView.findViewById(R.id.etRejectionMessage);

        AlertDialog declineDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Decline Claim")
                .setMessage("Are you sure you want to decline this claim from " + claim.getClaimantName() + "? You can optionally provide a reason for the claimant.")
                .setView(dialogView)
                .setPositiveButton("Decline Claim", (dialog, which) -> {
                    String msg = etRejectionMessage.getText() != null ? etRejectionMessage.getText().toString().trim() : null;
                    performReject(claim, msg);
                })
                .setNegativeButton("Cancel", null)
                .show();
        // Destructive action keeps its red voice under the green dialog theme.
        declineDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.spotify_error));
    }

    private void performReject(Claim claim, String optionalMessage) {
        binding.progressReviewClaims.setVisibility(View.VISIBLE);

        claimRepository.rejectClaim(claim.getId(), optionalMessage, new ClaimRepository.DataCallback<RpcResponse>() {
            @Override
            public void onSuccess(RpcResponse response) {
                binding.progressReviewClaims.setVisibility(View.GONE);
                Toast.makeText(ClaimsReviewActivity.this, "Claim declined.", Toast.LENGTH_SHORT).show();
                loadClaims();
            }

            @Override
            public void onError(String message) {
                binding.progressReviewClaims.setVisibility(View.GONE);
                Toast.makeText(ClaimsReviewActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}

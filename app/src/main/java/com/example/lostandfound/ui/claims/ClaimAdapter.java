package com.example.lostandfound.ui.claims;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.databinding.ItemClaimCardBinding;
import com.example.lostandfound.util.DateUtils;
import com.example.lostandfound.util.StatusUtils;
import java.util.ArrayList;
import java.util.List;

public class ClaimAdapter extends RecyclerView.Adapter<ClaimAdapter.ClaimViewHolder> {
    public interface OnClaimActionListener {
        void onAccept(Claim claim);
        void onReject(Claim claim);
        void onOpenChat(Claim claim);
    }

    private final List<Claim> claims = new ArrayList<>();
    private final boolean isOwnerReview;
    private final OnClaimActionListener listener;

    public ClaimAdapter(boolean isOwnerReview, OnClaimActionListener listener) {
        this.isOwnerReview = isOwnerReview;
        this.listener = listener;
    }

    public void setClaims(List<Claim> newClaims) {
        claims.clear();
        if (newClaims != null) {
            claims.addAll(newClaims);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ClaimViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemClaimCardBinding binding = ItemClaimCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ClaimViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ClaimViewHolder holder, int position) {
        holder.bind(claims.get(position));
    }

    @Override
    public int getItemCount() {
        return claims.size();
    }

    class ClaimViewHolder extends RecyclerView.ViewHolder {
        private final ItemClaimCardBinding binding;

        ClaimViewHolder(ItemClaimCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Claim claim) {
            Context context = itemView.getContext();
            com.example.lostandfound.data.repository.ProfileDirectory directory =
                    com.example.lostandfound.data.repository.ProfileDirectory.getInstance();
            binding.tvClaimantName.setText(
                    directory.getDisplayName(claim.getClaimantId(), claim.getClaimantName()));
            String avatarUrl = com.example.lostandfound.data.remote.SupabaseConfig
                    .getPublicAvatarUrl(directory.getAvatarPath(claim.getClaimantId()));
            if (avatarUrl != null) {
                com.bumptech.glide.Glide.with(context).clear(binding.ivClaimantAvatar);
                binding.ivClaimantAvatar.setImageTintList(null);
                binding.ivClaimantAvatar.setPadding(0, 0, 0, 0);
                com.bumptech.glide.Glide.with(context)
                        .load(com.example.lostandfound.data.remote.SupabaseConfig.getGlideUrl(avatarUrl))
                        .circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .error(R.drawable.ic_person)
                        .into(binding.ivClaimantAvatar);
            } else {
                com.bumptech.glide.Glide.with(context).clear(binding.ivClaimantAvatar);
                binding.ivClaimantAvatar.setImageResource(R.drawable.ic_person);
                binding.ivClaimantAvatar.setImageTintList(
                        android.content.res.ColorStateList.valueOf(
                                androidx.core.content.ContextCompat.getColor(context, R.color.spotify_text2)));
                int avatarPadding = (int) (8 * context.getResources().getDisplayMetrics().density);
                binding.ivClaimantAvatar.setPadding(avatarPadding, avatarPadding, avatarPadding, avatarPadding);
            }
            binding.tvClaimDate.setText(context.getString(R.string.claim_submitted_on,
                    DateUtils.formatDisplayDate(claim.getCreatedAt())));

            // Q/A pair. Each block hides itself when it has nothing to show, so
            // a recycled row can never keep the other row's text.
            String question = ClaimCardContent.question(claim.getVerificationQuestion());
            if (question == null) {
                binding.layoutClaimQuestion.setVisibility(View.GONE);
            } else {
                binding.tvClaimQuestion.setText(question);
                binding.layoutClaimQuestion.setVisibility(View.VISIBLE);
            }

            String answer = ClaimCardContent.answer(claim.getNoteOrEvidence());
            if (answer == null) {
                binding.layoutClaimAnswer.setVisibility(View.GONE);
            } else {
                binding.tvClaimEvidence.setText(answer);
                binding.layoutClaimAnswer.setVisibility(View.VISIBLE);
            }

            // One neutral pill for every state; only the text colour carries the
            // meaning. Previously PENDING wore the green-stroked "found" badge,
            // which read as accepted.
            String status = claim.getStatus();
            binding.tvClaimStatusBadge.setText(StatusUtils.getClaimStatusLabel(status));
            binding.tvClaimStatusBadge.setTextColor(StatusUtils.getClaimStatusColor(context, status));

            if (claim.isRejected() && claim.getRejectionMessage() != null && !claim.getRejectionMessage().trim().isEmpty()) {
                binding.tvClaimRejectionReason.setText(context.getString(R.string.claim_owner_note,
                        claim.getRejectionMessage().trim()));
                binding.tvClaimRejectionReason.setVisibility(View.VISIBLE);
            } else {
                binding.tvClaimRejectionReason.setVisibility(View.GONE);
            }

            // Reset action-row state first: recycled views must never keep a
            // previous row's hidden Decline button or "Open Chat" label.
            binding.btnRejectClaim.setVisibility(View.VISIBLE);
            binding.btnAcceptClaim.setText(R.string.claim_accept_and_chat);
            binding.btnAcceptClaim.setOnClickListener(null);
            binding.btnRejectClaim.setOnClickListener(null);

            // Only show action buttons to the owner on PENDING claims
            if (isOwnerReview && claim.isPending()) {
                binding.layoutClaimActions.setVisibility(View.VISIBLE);
                binding.btnAcceptClaim.setOnClickListener(v -> {
                    if (listener != null) listener.onAccept(claim);
                });
                binding.btnRejectClaim.setOnClickListener(v -> {
                    if (listener != null) listener.onReject(claim);
                });
            } else if (claim.isAccepted() && listener != null) {
                // If claim is accepted, let participants jump straight to chat
                binding.layoutClaimActions.setVisibility(View.VISIBLE);
                binding.btnRejectClaim.setVisibility(View.GONE);
                binding.btnAcceptClaim.setText(R.string.claim_open_chat);
                binding.btnAcceptClaim.setOnClickListener(v -> listener.onOpenChat(claim));
            } else {
                binding.layoutClaimActions.setVisibility(View.GONE);
            }
        }
    }
}

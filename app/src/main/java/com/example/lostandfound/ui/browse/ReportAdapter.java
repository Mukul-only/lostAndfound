package com.example.lostandfound.ui.browse;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.lostandfound.BuildConfig;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.databinding.ItemReportCardBinding;
import com.example.lostandfound.util.DateUtils;
import com.example.lostandfound.util.StatusUtils;
import java.util.ArrayList;
import java.util.List;

public class ReportAdapter extends RecyclerView.Adapter<ReportAdapter.ReportViewHolder> {
    public interface OnItemClickListener {
        void onItemClick(Report report);
    }

    private final List<Report> reports = new ArrayList<>();
    private final OnItemClickListener listener;

    public ReportAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setReports(List<Report> newReports) {
        reports.clear();
        if (newReports != null) {
            reports.addAll(newReports);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ReportViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemReportCardBinding binding = ItemReportCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ReportViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ReportViewHolder holder, int position) {
        holder.bind(reports.get(position));
    }

    @Override
    public int getItemCount() {
        return reports.size();
    }

    class ReportViewHolder extends RecyclerView.ViewHolder {
        private final ItemReportCardBinding binding;

        ReportViewHolder(ItemReportCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        // Image display rule: the full photo is shown uncropped (fitCenter +
        // adjustViewBounds, so portrait and landscape keep their aspect ratio
        // with no stretch). minHeight reserves space while loading so rows do
        // not jump; a fixed compact placeholder when there is no photo.
        private static final int PLACEHOLDER_HEIGHT_DP = 160;

        private void setFullPhotoMode() {
            binding.ivItemImage.setAdjustViewBounds(true);
            binding.ivItemImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            android.view.ViewGroup.LayoutParams params = binding.ivItemImage.getLayoutParams();
            if (params.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                binding.ivItemImage.setLayoutParams(params);
            }
        }

        private void setPlaceholderMode() {
            binding.ivItemImage.setAdjustViewBounds(false);
            binding.ivItemImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            android.view.ViewGroup.LayoutParams params = binding.ivItemImage.getLayoutParams();
            int heightPx = (int) (PLACEHOLDER_HEIGHT_DP * itemView.getResources().getDisplayMetrics().density);
            if (params.height != heightPx) {
                params.height = heightPx;
                binding.ivItemImage.setLayoutParams(params);
            }
        }

        void bind(Report report) {
            Context context = itemView.getContext();

            // Poster name + photo: prefer the live profile so renames and new
            // avatars appear on older posts; fall back to the stored copies.
            com.example.lostandfound.data.repository.ProfileDirectory directory =
                    com.example.lostandfound.data.repository.ProfileDirectory.getInstance();
            binding.tvPosterName.setText(directory.getDisplayName(
                    report.getOwnerId(), report.getOwnerName()));
            String posterAvatarUrl = com.example.lostandfound.data.remote.SupabaseConfig
                    .getPublicAvatarUrl(directory.getAvatarPath(report.getOwnerId()));
            if (posterAvatarUrl != null) {
                Glide.with(context).clear(binding.ivPosterAvatar);
                binding.ivPosterAvatar.setImageTintList(null);
                binding.ivPosterAvatar.setPadding(0, 0, 0, 0);
                Glide.with(context)
                        .load(com.example.lostandfound.data.remote.SupabaseConfig.getGlideUrl(posterAvatarUrl))
                        .circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .error(R.drawable.ic_person)
                        .into(binding.ivPosterAvatar);
            } else {
                Glide.with(context).clear(binding.ivPosterAvatar);
                binding.ivPosterAvatar.setImageResource(R.drawable.ic_person);
                binding.ivPosterAvatar.setImageTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(context, R.color.spotify_text2)));
                int avatarPadding = (int) (8 * context.getResources().getDisplayMetrics().density);
                binding.ivPosterAvatar.setPadding(avatarPadding, avatarPadding, avatarPadding, avatarPadding);
            }

            // Approximate posted time (createdAt); hidden when unavailable
            String postedTime = DateUtils.formatDisplayDate(report.getCreatedAt());
            if (postedTime == null || postedTime.trim().isEmpty()) {
                binding.tvPostedTime.setVisibility(View.GONE);
            } else {
                binding.tvPostedTime.setVisibility(View.VISIBLE);
                binding.tvPostedTime.setText(postedTime);
            }

            // Item Title
            binding.tvTitle.setText(report.getTitle());

            // Category chip
            binding.tvCategory.setText(StatusUtils.getCategoryLabel(report.getCategory()));
            binding.ivCategoryIcon.setImageResource(StatusUtils.getCategoryIconRes(report.getCategory()));

            // Location
            binding.tvLocation.setText(report.getCampusLocation());

            // Description preview (truncated)
            String description = report.getDescription();
            if (description != null && description.length() > 120) {
                description = description.substring(0, 117) + "...";
            }
            binding.tvDescriptionPreview.setText(description);

            // Type badge
            if (report.isLost()) {
                binding.tvTypeBadge.setText("LOST");
                binding.tvTypeBadge.setTextColor(context.getColor(R.color.spotify_error));
                binding.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_lost);
            } else {
                binding.tvTypeBadge.setText("FOUND");
                binding.tvTypeBadge.setTextColor(context.getColor(R.color.spotify_green));
                binding.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_found);
            }

            // Status label (preserves existing status behavior in the feed)
            binding.tvStatusBadge.setText(StatusUtils.getStatusLabel(report.getStatus()));
            binding.tvStatusBadge.setTextColor(StatusUtils.getStatusColor(context, report.getStatus()));

            // Image handling with safe recycling: always clear first so a recycled
            // view never shows a previous report's photo.
            String imageUrl = SupabaseConfig.getPublicImageUrl(report.getImageUrl());
            if (BuildConfig.DEBUG) {
                Log.d("ReportAdapter", "Binding item " + report.getId() + " - raw image_url: " + report.getImageUrl() + " -> resolved: " + imageUrl);
            }

            if (imageUrl != null && !imageUrl.isEmpty()) {
                setFullPhotoMode();
                Glide.with(context).clear(binding.ivItemImage);
                binding.ivItemImage.setImageTintList(null);
                binding.ivItemImage.setVisibility(View.VISIBLE);

                // Cap decode size: uploads are <=1024px and the card never
                // renders wider than ~1080px, so decoding originals wastes memory.
                Glide.with(context)
                        .load(SupabaseConfig.getGlideUrl(imageUrl))
                        .placeholder(R.drawable.ic_photo)
                        .error(R.drawable.ic_photo)
                        .override(1080, 1080)
                        .fitCenter()
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                                if (BuildConfig.DEBUG) {
                                    Log.w("ReportAdapter", "Glide load failed for " + imageUrl + ": " + (e != null ? e.getMessage() : "unknown"));
                                }
                                binding.ivItemImage.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.spotify_text2)));
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                if (BuildConfig.DEBUG) {
                                    Log.d("ReportAdapter", "Glide loaded successfully for " + imageUrl + " from " + dataSource);
                                }
                                binding.ivItemImage.setImageTintList(null);
                                return false;
                            }
                        })
                        .into(binding.ivItemImage);
            } else {
                setPlaceholderMode();
                Glide.with(context).clear(binding.ivItemImage);
                binding.ivItemImage.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.spotify_text2)));
                binding.ivItemImage.setImageResource(R.drawable.ic_photo);
                binding.ivItemImage.setVisibility(View.VISIBLE);
            }

            binding.tvViewDetails.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(report);
                }
            });

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(report);
                }
            });
        }
    }
}
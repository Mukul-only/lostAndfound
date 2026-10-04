package com.example.lostandfound.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.databinding.ItemHomeReportBinding;
import com.example.lostandfound.util.DateUtils;
import com.example.lostandfound.util.StatusUtils;
import java.util.ArrayList;
import java.util.List;

/** Home-only feed adapter (Spotify dark cards). Browse/My Reports keep ReportAdapter untouched. */
public class HomeReportAdapter extends RecyclerView.Adapter<HomeReportAdapter.ReportViewHolder> {
    public interface OnItemClickListener {
        void onItemClick(Report report);
    }

    private final List<Report> reports = new ArrayList<>();

    private final OnItemClickListener listener;

    public HomeReportAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setReports(List<Report> newReports) {
        reports.clear();
        if (newReports != null) reports.addAll(newReports);
        notifyDataSetChanged();
    }


    /** Renders the question you asked and whether anyone answered it. Both
     *  halves hide themselves when they have nothing to show, so a recycled row
     *  can never keep the previous report's text. */
    @NonNull
    @Override
    public ReportViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHomeReportBinding binding = ItemHomeReportBinding.inflate(
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
        private final ItemHomeReportBinding binding;

        ReportViewHolder(ItemHomeReportBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        private static final int PLACEHOLDER_HEIGHT_DP = 160;

        private void setFullPhotoMode() {
            binding.ivItemImage.setAdjustViewBounds(true);
            binding.ivItemImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            ViewGroup.LayoutParams params = binding.ivItemImage.getLayoutParams();
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                binding.ivItemImage.setLayoutParams(params);
            }
        }


        private void setPlaceholderMode() {
            binding.ivItemImage.setAdjustViewBounds(false);
            binding.ivItemImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            ViewGroup.LayoutParams params = binding.ivItemImage.getLayoutParams();
            int heightPx = (int) (PLACEHOLDER_HEIGHT_DP * itemView.getResources().getDisplayMetrics().density);
            if (params.height != heightPx) {
                params.height = heightPx;
                binding.ivItemImage.setLayoutParams(params);
            }
        }

        /** Status colors remapped for #181818 cards (light-theme status_* tokens fail contrast on dark). */
        private int statusColor(Context context, String status) {
            if (Report.STATUS_HANDOVER_ARRANGED.equals(status)) {
                return ContextCompat.getColor(context, R.color.spotify_info);
            } else if (Report.STATUS_RETURNED.equals(status)) {
                return ContextCompat.getColor(context, R.color.spotify_info);
            } else if (Report.STATUS_CLOSED.equals(status)) {
                return ContextCompat.getColor(context, R.color.spotify_text2);
            }
            return ContextCompat.getColor(context, R.color.spotify_green);
        }

        void bind(Report report) {
            Context context = itemView.getContext();

            com.example.lostandfound.data.repository.ProfileDirectory directory =
                    com.example.lostandfound.data.repository.ProfileDirectory.getInstance();
            binding.tvPosterName.setText(directory.getDisplayName(
                    report.getOwnerId(), report.getOwnerName()));
            String posterAvatarUrl = SupabaseConfig.getPublicAvatarUrl(directory.getAvatarPath(report.getOwnerId()));
            if (posterAvatarUrl != null) {
                Glide.with(context).clear(binding.ivPosterAvatar);
                binding.ivPosterAvatar.setImageTintList(null);
                binding.ivPosterAvatar.setPadding(0, 0, 0, 0);
                Glide.with(context)
                        .load(SupabaseConfig.getGlideUrl(posterAvatarUrl))
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

            String postedTime = DateUtils.formatDisplayDate(report.getCreatedAt());
            if (postedTime == null || postedTime.trim().isEmpty()) {
                binding.tvPostedTime.setVisibility(View.GONE);
            } else {
                binding.tvPostedTime.setVisibility(View.VISIBLE);
                binding.tvPostedTime.setText(postedTime);
            }

            binding.tvTitle.setText(report.getTitle());
            binding.tvCategory.setText(StatusUtils.getCategoryLabel(report.getCategory()));
            binding.ivCategoryIcon.setImageResource(StatusUtils.getCategoryIconRes(report.getCategory()));
            binding.tvLocation.setText(report.getCampusLocation());            String description = report.getDescription();
            if (description != null && description.length() > 120) {
                description = description.substring(0, 117) + "...";
            }
            binding.tvDescriptionPreview.setText(description);

            if (report.isLost()) {
                binding.tvTypeBadge.setText("LOST");
                binding.tvTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.spotify_error));
                binding.tvTypeBadge.setBackgroundResource(R.drawable.bg_spotify_badge_lost);
            } else {
                binding.tvTypeBadge.setText("FOUND");
                binding.tvTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.spotify_on_green));
                binding.tvTypeBadge.setBackgroundResource(R.drawable.bg_spotify_badge_found);
            }

            binding.tvStatusBadge.setText(StatusUtils.getStatusLabel(report.getStatus()));
            binding.tvStatusBadge.setTextColor(statusColor(context, report.getStatus()));

            String imageUrl = SupabaseConfig.getPublicImageUrl(report.getImageUrl());
            // Always clear first so a recycled ViewHolder never shows a stale
            // image from a previous report while the new load is in flight.
            Glide.with(context).clear(binding.ivItemImage);
            if (imageUrl != null && !imageUrl.isEmpty()) {
                setFullPhotoMode();
                binding.ivItemImage.setImageTintList(null);
                binding.ivItemImage.setVisibility(View.VISIBLE);
                // Keying the cache on updated_at busts the disk cache when the
                // report row changes (e.g. photo removed / replaced), so a
                // deleted photo stops appearing even without an app restart.
                String cacheKey = report.getUpdatedAt() != null
                        ? report.getUpdatedAt() : report.getId();
                Glide.with(context)
                        .load(SupabaseConfig.getGlideUrl(imageUrl))
                        .placeholder(R.drawable.ic_photo)
                        .error(R.drawable.ic_photo)
                        .signature(new com.bumptech.glide.signature.ObjectKey(cacheKey))
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .override(1080, 1080)
                        .fitCenter()
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                                // File was deleted from storage — fall back to
                                // the same placeholder layout as no-image reports.
                                setPlaceholderMode();
                                binding.ivItemImage.setImageTintList(ColorStateList.valueOf(
                                        ContextCompat.getColor(context, R.color.spotify_text2)));
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                binding.ivItemImage.setImageTintList(null);
                                return false;
                            }
                        })
                        .into(binding.ivItemImage);
            } else {
                // Missing-image state: fixed compact tile, silver glyph on #1f1f1f.
                setPlaceholderMode();
                binding.ivItemImage.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.spotify_text2)));
                binding.ivItemImage.setImageResource(R.drawable.ic_photo);
                binding.ivItemImage.setVisibility(View.VISIBLE);
            }

            binding.tvViewDetails.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(report);
            });
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(report);
            });
        }
    }
}

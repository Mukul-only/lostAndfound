package com.example.lostandfound.ui.browse;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
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
import com.example.lostandfound.BuildConfig;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.util.StatusUtils;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.databinding.ItemReportMasonryBinding;
import com.example.lostandfound.ui.report.ReportDetailActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * Image-first masonry adapter for Browse: each tile shows ONLY the report
 * title above its photo. Tapping a tile opens Report Details.
 *
 * Recycling safety: every bind clears any in-flight Glide request, resets
 * the placeholder immediately, and fixes the scale type, so async loads can
 * never leave a stale photo or a wrong tile height behind.
 */
public class MasonryReportAdapter extends RecyclerView.Adapter<MasonryReportAdapter.MasonryViewHolder> {

    private final List<Report> reports = new ArrayList<>();

    public void setReports(List<Report> newReports) {
        reports.clear();
        if (newReports != null) {
            reports.addAll(newReports);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MasonryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemReportMasonryBinding binding = ItemReportMasonryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new MasonryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MasonryViewHolder holder, int position) {
        holder.bind(reports.get(position));
    }

    @Override
    public int getItemCount() {
        return reports.size();
    }

    @Override
    public void onViewRecycled(@NonNull MasonryViewHolder holder) {
        Glide.with(holder.itemView.getContext()).clear(holder.binding.ivMasonryImage);
        super.onViewRecycled(holder);
    }

    class MasonryViewHolder extends RecyclerView.ViewHolder {
        private final ItemReportMasonryBinding binding;

        MasonryViewHolder(ItemReportMasonryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Report report) {
            Context context = itemView.getContext();

            binding.tvMasonryTitle.setText(report.getTitle());

            // Grid tiles carry no other metadata, so without this the tile view
            // cannot tell an open report from a resolved one.
            binding.tvMasonryStatus.setText(StatusUtils.getStatusLabel(report.getStatus()));
            binding.tvMasonryStatus.setTextColor(StatusUtils.getStatusColor(context, report.getStatus()));

            // Reset image state first: placeholder + fit sizing, so recycled
            // tiles never flash a previous report's photo or height.
            Glide.with(context).clear(binding.ivMasonryImage);
            binding.ivMasonryImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            binding.ivMasonryImage.setImageTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.spotify_text2)));
            binding.ivMasonryImage.setImageResource(R.drawable.ic_photo);

            String imageUrl = SupabaseConfig.getPublicImageUrl(report.getImageUrl());
            if (BuildConfig.DEBUG) {
                Log.d("MasonryAdapter", "Binding item " + report.getId() + " -> " + imageUrl);
            }

            if (imageUrl != null && !imageUrl.isEmpty()) {
                // Masonry tiles render at ~1/3 screen width; 720px decode cap
                // keeps three columns smooth without visible quality loss.
                // Signature busts the disk cache when the report's updated_at
                // changes (e.g. photo removed), so stale images don't persist.
                String cacheKey = report.getUpdatedAt() != null
                        ? report.getUpdatedAt() : report.getId();
                Glide.with(context)
                        .load(SupabaseConfig.getGlideUrl(imageUrl))
                        .placeholder(R.drawable.ic_photo)
                        .error(R.drawable.ic_photo)
                        .signature(new com.bumptech.glide.signature.ObjectKey(cacheKey))
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .override(720, 720)
                        .fitCenter()
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                        Target<Drawable> target, boolean isFirstResource) {
                                binding.ivMasonryImage.setImageTintList(
                                        ColorStateList.valueOf(ContextCompat.getColor(context, R.color.spotify_text2)));
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                           DataSource dataSource, boolean isFirstResource) {
                                binding.ivMasonryImage.setImageTintList(null);
                                return false;
                            }
                        })
                        .into(binding.ivMasonryImage);
            }

            itemView.setOnClickListener(v -> {
                Context ctx = v.getContext();
                Intent intent = new Intent(ctx, ReportDetailActivity.class);
                intent.putExtra("report_id", report.getId());
                ctx.startActivity(intent);
            });

            // Full tile is the touch target; keep it accessible without clutter.
            itemView.setContentDescription(report.getTitle());
        }
    }
}

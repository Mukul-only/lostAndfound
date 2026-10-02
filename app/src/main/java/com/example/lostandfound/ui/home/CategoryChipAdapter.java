package com.example.lostandfound.ui.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.example.lostandfound.R;
import com.example.lostandfound.databinding.ItemHomeCategoryBinding;
import java.util.ArrayList;
import java.util.List;

/** Home-only category discs (Spotify dark). Selection ring is functional green. */
public class CategoryChipAdapter extends RecyclerView.Adapter<CategoryChipAdapter.CategoryViewHolder> {

    public interface OnCategoryClickListener {
        void onCategoryClick(String categoryKey, String categoryLabel);
        void onClearFilter();
    }

    private static class CategoryItem {
        final String key;
        final String label;
        final int iconResId;

        CategoryItem(String key, String label, int iconResId) {
            this.key = key;
            this.label = label;
            this.iconResId = iconResId;
        }
    }

    private final List<CategoryItem> categories = new ArrayList<>();
    private final OnCategoryClickListener listener;
    private String selectedCategory = "ALL";

    public CategoryChipAdapter(OnCategoryClickListener listener) {
        this.listener = listener;
        initCategories();
    }

    private void initCategories() {
        // Circular #1f1f1f discs; icons stay white/silver (album art carries color).
        categories.add(new CategoryItem("ELECTRONICS", "Electronics", R.drawable.ic_cat_electronics));
        categories.add(new CategoryItem("CARDS_ID", "Cards & ID", R.drawable.ic_cat_cards_id));
        categories.add(new CategoryItem("KEYS", "Keys", R.drawable.ic_cat_keys));
        categories.add(new CategoryItem("BAGS_WALLETS", "Bags & Wallets", R.drawable.ic_cat_bags_wallets));
        categories.add(new CategoryItem("CLOTHING", "Clothing", R.drawable.ic_cat_clothing));
        categories.add(new CategoryItem("BOOKS_STATIONERY", "Books", R.drawable.ic_cat_books_stationery));
        categories.add(new CategoryItem("OTHER", "Other", R.drawable.ic_cat_other));
    }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHomeCategoryBinding binding = ItemHomeCategoryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new CategoryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        CategoryItem item = categories.get(position);
        holder.bind(item, item.key.equals(selectedCategory));
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    class CategoryViewHolder extends RecyclerView.ViewHolder {
        private final ItemHomeCategoryBinding binding;

        CategoryViewHolder(ItemHomeCategoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(CategoryItem item, boolean isSelected) {
            Context context = itemView.getContext();

            binding.tvLabel.setText(item.label);
            binding.ivIcon.setImageResource(item.iconResId);
            if (isSelected) {
                binding.ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.spotify_green)));
                binding.tileCard.setStrokeColor(ContextCompat.getColor(context, R.color.spotify_green));
                binding.tileCard.setStrokeWidth((int) (2 * context.getResources().getDisplayMetrics().density));
                binding.tvLabel.setTextColor(ContextCompat.getColor(context, R.color.spotify_text));
            } else {
                binding.ivIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.spotify_text)));
                binding.tileCard.setStrokeColor(ContextCompat.getColor(context, R.color.spotify_border));
                binding.tileCard.setStrokeWidth((int) (1 * context.getResources().getDisplayMetrics().density));
                binding.tvLabel.setTextColor(ContextCompat.getColor(context, R.color.spotify_text2));
            }

            binding.getRoot().setOnClickListener(v -> {
                selectedCategory = item.key;
                notifyDataSetChanged();
                if (listener != null) {
                    listener.onCategoryClick(item.key, item.label);
                }
            });
        }
    }

    public void setSelectedCategory(String categoryKey) {
        if (!categoryKey.equals(selectedCategory)) {
            selectedCategory = categoryKey;
            notifyDataSetChanged();
        }
    }

    public String getSelectedCategory() {
        return selectedCategory;
    }

    public void clearSelection() {
        if (!"ALL".equals(selectedCategory)) {
            selectedCategory = "ALL";
            notifyDataSetChanged();
            if (listener != null) {
                listener.onClearFilter();
            }
        }
    }
}

package com.example.lostandfound.ui.browse;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

/**
 * Uniform gutters for the masonry grid: half-spacing on every side of each
 * tile produces even gaps between tiles and columns, with a half-gutter at
 * the feed edges. Full-span items (none currently) get full side spacing.
 */
public class MasonrySpacingDecoration extends RecyclerView.ItemDecoration {
    private final int halfSpacingPx;

    public MasonrySpacingDecoration(Context context, int spacingDp) {
        float density = context.getResources().getDisplayMetrics().density;
        this.halfSpacingPx = Math.max(1, (int) (spacingDp * density / 2f));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) view.getLayoutParams();
        boolean fullSpan = params instanceof StaggeredGridLayoutManager.LayoutParams
                && ((StaggeredGridLayoutManager.LayoutParams) params).isFullSpan();
        if (fullSpan) {
            outRect.set(halfSpacingPx * 2, halfSpacingPx, halfSpacingPx * 2, halfSpacingPx);
        } else {
            outRect.set(halfSpacingPx, halfSpacingPx, halfSpacingPx, halfSpacingPx);
        }
    }
}

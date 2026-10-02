package com.example.lostandfound.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.example.lostandfound.R;

/**
 * Shared single-divider strategy for FOUNDIT's flat feed lists (report
 * posts, claims/responses, conversations): exactly ONE subtle full-width
 * horizontal line between consecutive rows, never after the last row.
 * Same color ({@code spotify_border}), thickness (1dp), and spacing everywhere.
 */
public class FeedDividerDecoration extends RecyclerView.ItemDecoration {
    private final Paint paint;
    private final int dividerHeightPx;

    public FeedDividerDecoration(Context context) {
        paint = new Paint();
        paint.setColor(ContextCompat.getColor(context, R.color.spotify_border));
        paint.setStyle(Paint.Style.FILL);
        dividerHeightPx = Math.max(1,
                (int) (context.getResources().getDisplayMetrics().density));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter != null && position != RecyclerView.NO_POSITION
                && position < adapter.getItemCount() - 1) {
            outRect.bottom = dividerHeightPx;
        } else {
            outRect.bottom = 0;
        }
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                       @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null) return;

        int left = parent.getPaddingLeft();
        int right = parent.getWidth() - parent.getPaddingRight();

        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION
                    || position >= adapter.getItemCount() - 1) {
                continue; // No divider after the last row
            }
            int top = child.getBottom() + ((RecyclerView.LayoutParams) child.getLayoutParams()).bottomMargin;
            canvas.drawRect(left, top, right, top + dividerHeightPx, paint);
        }
    }
}

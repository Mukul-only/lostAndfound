package com.example.lostandfound.ui.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.example.lostandfound.R;

/**
 * Swipe to reply, WhatsApp-style, with the direction the reader expects:
 * <b>your own messages slide right, the other person's slide left</b>. Both
 * select the row as the reply target and both return to rest.
 *
 * Instant by design:
 *  - the Reply action fires the moment the row crosses the threshold, not on
 *    release, so the composer preview appears while the finger is still down;
 *  - long-press drag is switched off by the host, so a press-and-hold can never
 *    be swallowed into a drag instead of a swipe.
 *
 * The row is never dismissed or reordered - {@link #onMove} always returns false
 * and only one direction is ever registered per row, chosen by ownership.
 *
 * Only horizontal movement is claimed, so vertical scrolling stays the list's
 * primary gesture.
 *
 * Accessibility: a swipe is a drag, and WCAG 2.2 AA requires a single-pointer
 * alternative, so every replyable row also carries a custom "Reply" accessibility
 * action - see {@link MessageAdapter}.
 */
class ReplySwipeCallback extends ItemTouchHelper.SimpleCallback {
    /** Fraction of the row width that arms the reply. */
    private static final float SWIPE_THRESHOLD = 0.28f;
    /** How far the row may travel, so the movement reads as a peek not a removal. */
    private static final float MAX_TRAVEL_FRACTION = 0.42f;
    private static final int INDICATOR_SIZE_DP = 22;
    private static final int INDICATOR_MARGIN_DP = 20;
    private static final int INDICATOR_CORNER_DP = 8;

    /** Which way a row may be swiped, and whether it is the reader's own. */
    interface RowInfo {
        boolean isReplyable(int adapterPosition);
        boolean isOutgoing(int adapterPosition);
    }

    interface ReplyTargetListener {
        void onSwipeToReply(int adapterPosition);
    }

    private final ReplyTargetListener listener;
    private final RowInfo rows;
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Armed on the first frame past the threshold so one swipe fires once. */
    private boolean firedThisGesture;

    ReplySwipeCallback(ReplyTargetListener listener, RowInfo rows) {
        super(ItemTouchHelper.LEFT, ItemTouchHelper.RIGHT);
        this.listener = listener;
        this.rows = rows;
    }

    /**
     * A press-and-hold must never be swallowed into a drag. The gesture here is a
     * swipe and nothing else, so long-press drag is off and the reader never has
     * to hold still before a swipe will start.
     */
    @Override
    public boolean isLongPressDragEnabled() {
        return false;
    }

    @Override
    public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                @NonNull RecyclerView.ViewHolder viewHolder) {
        int position = viewHolder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || !rows.isReplyable(position)) {
            return makeMovementFlags(0, 0);   // centered event rows never move
        }
        // Own messages go right, the other person's go left.
        int direction = rows.isOutgoing(position)
                ? ItemTouchHelper.RIGHT
                : ItemTouchHelper.LEFT;
        return makeMovementFlags(0, direction);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder,
                          @NonNull RecyclerView.ViewHolder target) {
        return false;   // rows are never reordered
    }

    /**
     * Fires the moment the row is dragged past the threshold, rather than waiting
     * for release, so selecting feels instant. Guarded to once per gesture.
     */
    @Override
    public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder,
                            float dX, float dY, int actionState, boolean isCurrentlyActive) {
        int position = viewHolder.getBindingAdapterPosition();
        boolean eligible = position != RecyclerView.NO_POSITION && rows.isReplyable(position);
        int allowed = getMovementFlags(recyclerView, viewHolder);
        boolean correctWay = allowed == 0
                || (((allowed & ItemTouchHelper.LEFT) != 0) && dX < 0)
                || (((allowed & ItemTouchHelper.RIGHT) != 0) && dX > 0);

        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && eligible && correctWay) {
            View row = viewHolder.itemView;
            float travel = Math.min(Math.abs(dX), row.getWidth() * MAX_TRAVEL_FRACTION);
            row.setTranslationX(dX < 0 ? -travel : travel);
            drawIndicator(canvas, row, dX < 0, travel);

            if (isCurrentlyActive && !firedThisGesture
                    && Math.abs(dX) >= row.getWidth() * SWIPE_THRESHOLD) {
                firedThisGesture = true;
                listener.onSwipeToReply(position);
            }
        } else {
            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        // The reply was already raised in onChildDraw at the threshold crossing.
        // This only has to hand the row back.
        settleBack(viewHolder.itemView);
        int position = viewHolder.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION && viewHolder.getBindingAdapter() != null) {
            // Tells ItemTouchHelper the row was not consumed, so it stops treating
            // it as a pending swipe.
            viewHolder.getBindingAdapter().notifyItemChanged(position);
        }
    }

    /** Returns the row to rest with a short ease-out, so it never snaps. */
    private void settleBack(final View row) {
        row.animate()
                .translationX(0f)
                .setDuration(160L)
                .withEndAction(() -> {
                    row.setTranslationX(0f);
                    row.setAlpha(1f);
                })
                .start();
    }

    /**
     * The revealed strip: a #1F1F1F rounded surface with a green reply glyph,
     * anchored to whichever edge the row moved away from. Green is functional
     * here - it marks the action being performed - and matches the quote spine
     * already used on reply previews.
     */
    private void drawIndicator(Canvas canvas, View row, boolean toLeft, float travel) {
        Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int size = (int) (INDICATOR_SIZE_DP * density);
        int margin = (int) (INDICATOR_MARGIN_DP * density);
        int corner = (int) (INDICATOR_CORNER_DP * density);

        int top = row.getTop();
        int bottom = row.getBottom();
        backgroundPaint.setColor(ContextCompat.getColor(context, R.color.spotify_surface2));

        float stripLeft = toLeft ? row.getRight() - travel : row.getLeft();
        float stripRight = toLeft ? row.getRight() : row.getLeft() + travel;
        canvas.drawRoundRect(stripLeft, top + 4, stripRight, bottom - 4,
                corner, corner, backgroundPaint);

        if (travel < margin) return;   // nothing to aim at yet

        Drawable icon = ContextCompat.getDrawable(context, R.drawable.ic_reply);
        if (icon == null) return;
        icon = DrawableCompat.wrap(icon.mutate());
        icon.setTint(ContextCompat.getColor(context, R.color.spotify_green));

        int iconLeft = (int) (toLeft
                ? row.getRight() - margin * 0.55f - size
                : margin * 0.55f);
        icon.setBounds(iconLeft,
                top + ((bottom - top) - size) / 2,
                iconLeft + size,
                top + ((bottom - top) + size) / 2);
        icon.draw(canvas);
    }

    @Override
    public void clearView(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder) {
        super.clearView(recyclerView, viewHolder);
        // Any residual translation would otherwise be inherited by whatever row
        // this holder is bound to next.
        viewHolder.itemView.setTranslationX(0f);
        firedThisGesture = false;   // re-arm for the next swipe
    }
}

package com.example.lostandfound.ui.chat;

/**
 * The two rules that decide whether a poll may pull the conversation down to the
 * newest message. Pulled out of the Activity because they are the part that has
 * to be right, and because they are pure arithmetic - which means a plain JVM
 * test can cover them instead of a phone.
 *
 * The bug this exists to prevent: "am I at the bottom?" was answered with a
 * ROW count. A meeting proposal card is about 200dp tall, so an accepted
 * proposal - which sits directly above the newest message - was within two rows
 * of the end. Tapping a quote that pointed at it looked like reaching the
 * bottom, and the very next poll dragged the reader away from the row they had
 * just asked to see, once every poll interval. Measuring in pixels fixes it: one
 * tall row can no longer masquerade as several short ones.
 */
public final class ChatScrollPolicy {

    private ChatScrollPolicy() {}

    /**
     * True when the last visible row's bottom edge is within {@code thresholdPx}
     * of the end of the list.
     *
     * @param listHeight    height of the RecyclerView
     * @param lastRowBottom bottom edge of the last visible row, in the list's
     *                      coordinate space
     * @param bottomPadding the list's own bottom padding
     */
    public static boolean isNearBottom(int listHeight, int lastRowBottom,
                                       int bottomPadding, int thresholdPx) {
        if (listHeight <= 0) return true;   // not measured yet: assume the end
        // Fully scrolled, the last row's bottom edge sits against the inner
        // bottom edge, i.e. at (listHeight - bottomPadding). Anything lower
        // means the list can still scroll.
        int scrolledToEnd = (listHeight - bottomPadding) - lastRowBottom;
        return scrolledToEnd <= thresholdPx;
    }

    /**
     * Whether a poll should follow the conversation to the newest message.
     *
     * An initial load always does. Afterwards only when the reader is at the end
     * AND not parked somewhere they asked to be: jumping to a quoted original or
     * to a meeting spot pins the view, so the next few polls leave them where
     * they are instead of yanking them to the bottom on every tick.
     */
    public static boolean shouldFollow(boolean initialLoad, boolean isPinned,
                                       boolean isNearBottom) {
        if (initialLoad) return true;
        return !isPinned && isNearBottom;
    }
}

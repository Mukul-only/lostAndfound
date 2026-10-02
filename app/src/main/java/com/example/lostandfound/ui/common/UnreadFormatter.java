package com.example.lostandfound.ui.common;

import java.util.Map;

/**
 * The unread-badge vocabulary, kept as pure functions so the rules are testable
 * without a device.
 *
 * Every count shown in the app goes through here, so the bottom-nav badge and the
 * per-conversation badge can never disagree about how a number is rendered or
 * what "none" looks like.
 */
public final class UnreadFormatter {

    /** Above this, the badge shows a compact cap rather than the real total. */
    public static final int BADGE_CAP = 99;

    private UnreadFormatter() {}

    /**
     * Text for a badge showing {@code count}.
     *
     * @return an empty string when there is nothing unread, which is what makes
     *         the badge disappear rather than sit there showing a zero
     */
    public static String badge(int count) {
        if (count <= 0) return "";
        if (count > BADGE_CAP) return BADGE_CAP + "+";
        return String.valueOf(count);
    }

    /** True when a badge should be shown at all. */
    public static boolean shouldShow(int count) {
        return count > 0;
    }

    /**
     * The nav total: the sum across every conversation with something unread.
     * Counts are absent rather than zero for read conversations, so summing the
     * values present is the same as summing all of them.
     */
    public static int total(Map<String, Integer> countsByConversation) {
        if (countsByConversation == null || countsByConversation.isEmpty()) return 0;
        int total = 0;
        for (Integer value : countsByConversation.values()) {
            if (value != null && value > 0) total += value;
        }
        return total;
    }

    /** Count for one conversation, treating "absent" as read. */
    public static int countFor(Map<String, Integer> countsByConversation, String conversationId) {
        if (countsByConversation == null || conversationId == null) return 0;
        Integer value = countsByConversation.get(conversationId);
        return value == null ? 0 : value;
    }
}
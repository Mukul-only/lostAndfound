package com.example.lostandfound.data.model;

/**
 * The quote shown above a reply: who said it and a short excerpt. Built
 * client-side from the loaded timeline, so a quote can never reference something
 * the reader cannot see.
 */
public class ReplyPreview {
    /** Fallback used when the quoted item is not in the loaded timeline. */
    public static final String UNKNOWN_SENDER = "Original";

    private final String senderName;
    private final String excerpt;
    private final boolean meetingSpot;

    public ReplyPreview(String senderName, String excerpt, boolean meetingSpot) {
        this.senderName = senderName == null || senderName.trim().isEmpty()
                ? UNKNOWN_SENDER : senderName.trim();
        this.excerpt = excerpt;
        this.meetingSpot = meetingSpot;
    }

    public String getSenderName() {
        return senderName;
    }

    /** A text excerpt, or the standing "Meeting spot" summary for a card. */
    public String getExcerpt() {
        return excerpt;
    }

    public boolean isMeetingSpot() {
        return meetingSpot;
    }
}

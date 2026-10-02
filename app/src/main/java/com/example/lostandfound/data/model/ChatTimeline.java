package com.example.lostandfound.data.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the three conversation sources (text messages, meeting proposals,
 * meeting response events) into one chronological, de-duplicated timeline.
 *
 * Everything here is pure and android-free so the ordering, de-duplication and
 * quote-resolution rules are covered by plain JVM unit tests.
 */
public final class ChatTimeline {

    /** Resolves a user id to a display name; may return null while profiles load. */
    public interface NameResolver {
        String nameOf(String userId);
    }

    private ChatTimeline() {}

    public static List<ChatTimelineItem> merge(List<Message> messages,
                                               List<MeetingLocation> proposals,
                                               List<MeetingResponseEvent> events) {
        return merge(messages, proposals, events, null);
    }

    /**
     * @param names optional; when given, every quoted item is resolved to a
     *              sender name and excerpt. Callers re-merge once profiles have
     *              arrived so a late name fills in without a network round trip.
     */
    public static List<ChatTimelineItem> merge(List<Message> messages,
                                               List<MeetingLocation> proposals,
                                               List<MeetingResponseEvent> events,
                                               NameResolver names) {
        // Last write wins, keyed by kind+id: a row that arrives twice (optimistic
        // append followed by a poll) collapses to one entry instead of two.
        Map<String, ChatTimelineItem> byKey = new HashMap<>();
        if (messages != null) {
            for (Message m : messages) {
                if (m != null && m.getId() != null) byKey.put(keyOf(m.getId(), ChatTimelineItem.KEY_PREFIX_TEXT), ChatTimelineItem.text(m));
            }
        }
        if (proposals != null) {
            for (MeetingLocation p : proposals) {
                if (p != null && p.getId() != null) byKey.put(keyOf(p.getId(), ChatTimelineItem.KEY_PREFIX_PROPOSAL), ChatTimelineItem.proposal(p));
            }
        }
        if (events != null) {
            for (MeetingResponseEvent e : events) {
                if (e != null && e.getId() != null) byKey.put(keyOf(e.getId(), ChatTimelineItem.KEY_PREFIX_EVENT), ChatTimelineItem.event(e));
            }
        }

        List<ChatTimelineItem> items = new ArrayList<>(byKey.values());
        // Chronological, with a key tie-break so rows created in the same
        // instant (a proposal and the event answering it) order deterministically
        // instead of depending on map iteration order.
        items.sort(Comparator
                .comparing((ChatTimelineItem item) -> sortKey(item.getCreatedAt()))
                .thenComparing(ChatTimelineItem::getKey));

        Map<String, ChatTimelineItem> targets = indexByKey(items);
        for (ChatTimelineItem item : items) {
            String targetKey = item.getReplyTargetKey();
            if (targetKey == null) continue;
            item.setReplyPreview(previewOf(targets.get(targetKey), names));
        }
        return items;
    }

    /**
     * Indexed by the same kind-prefixed key that getReplyTargetKey() produces.
     * Keying by the bare row id would be wrong: a message id and a proposal id
     * are both UUIDs, and a reply to a proposal must never resolve to a message
     * that happens to share its id.
     */
    private static Map<String, ChatTimelineItem> indexByKey(List<ChatTimelineItem> items) {
        Map<String, ChatTimelineItem> byKey = new HashMap<>();
        for (ChatTimelineItem item : items) {
            byKey.put(item.getKey(), item);
        }
        return byKey;
    }

    /**
     * Quote for a reply target. A proposal contributes its note as the excerpt
     * (the UI labels it "Meeting spot"), a message contributes its text. A target
     * that is not in the loaded timeline still yields a preview so the quote
     * never collapses to a bare chip.
     */
    private static ReplyPreview previewOf(ChatTimelineItem target, NameResolver names) {
        if (target == null) return new ReplyPreview(null, null, false);
        if (target.getKind() == ChatTimelineItem.KIND_PROPOSAL) {
            MeetingLocation p = target.getProposal();
            return new ReplyPreview(name(names, p.getProposerId()), p.getLocationNote(), true);
        }
        Message m = target.getMessage();
        return new ReplyPreview(name(names, m.getSenderId()), m.getContent(), false);
    }

    private static String name(NameResolver names, String userId) {
        return names == null ? null : names.nameOf(userId);
    }

    private static String keyOf(String rawId, String prefix) {
        return prefix + rawId;
    }

    /**
     * Normalises a Postgres timestamptz literal so plain string comparison is a
     * correct chronological comparison: fractional seconds are padded or
     * truncated to a fixed width and the offset is dropped, because every row in
     * this schema is written in UTC. Cheaper and less fragile than a date parse,
     * and a value we do not recognise falls through unchanged rather than
     * throwing.
     */
    public static String sortKey(String createdAt) {
        if (createdAt == null) return "";
        String value = createdAt.trim();
        int offsetAt = indexOfOffset(value);
        if (offsetAt >= 0) value = value.substring(0, offsetAt);
        int dot = value.indexOf('.');
        if (dot < 0) return value + ".000000";
        String head = value.substring(0, dot + 1);
        String fraction = value.substring(dot + 1);
        if (fraction.length() >= 6) return head + fraction.substring(0, 6);
        // Pad on the RIGHT: ".1" means 100ms, not 1 microsecond.
        StringBuilder sb = new StringBuilder(head).append(fraction);
        for (int i = fraction.length(); i < 6; i++) sb.append('0');
        return sb.toString();
    }

    private static int indexOfOffset(String value) {
        int plus = value.indexOf('+', 10);
        int zulu = value.endsWith("Z") ? value.length() - 1 : -1;
        return Math.max(plus, zulu);
    }
}

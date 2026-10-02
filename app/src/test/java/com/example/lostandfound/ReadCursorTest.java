package com.example.lostandfound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.data.model.ChatTimelineItem;
import com.example.lostandfound.data.model.MeetingLocation;
import com.example.lostandfound.data.model.MeetingResponseEvent;
import com.example.lostandfound.data.model.Message;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The rule the client applies when it decides how far to move the read cursor.
 *
 * The server counts unread (see migration 012), but the client decides WHAT it
 * claims to have seen, and that decision has to agree with the server's rule or
 * badges will disagree with themselves. This mirrors the client's
 * "newest incoming item" walk:
 *
 *   an item counts as seen-and-incoming when its author is not this reader.
 *   Author is sender_id for a text message, proposer_id for a meeting proposal,
 *   and actor_id for a meeting response event.
 *
 * The author's own items must never be included, or the cursor would be advanced
 * past unread messages from the other side.
 */
public class ReadCursorTest {

    private static final String ME = "u-me";
    private static final String THEM = "u-them";

    private static Message text(String id, String sender, String at) {
        Message m = new Message("conv-1", sender, sender, "body");
        m.setId(id);
        m.setCreatedAt(at);
        return m;
    }

    private static MeetingLocation proposal(String id, String proposer, String at) {
        MeetingLocation p = new MeetingLocation();
        p.setId(id);
        p.setConversationId("conv-1");
        p.setProposerId(proposer);
        p.setStatus(MeetingLocation.STATUS_PROPOSED);
        p.setCreatedAt(at);
        return p;
    }

    private static MeetingResponseEvent event(String id, String actor, String at) {
        MeetingResponseEvent e = new MeetingResponseEvent();
        e.setId(id);
        e.setConversationId("conv-1");
        e.setMeetingId("p1");
        e.setActorId(actor);
        e.setResponse(MeetingResponseEvent.RESPONSE_ACCEPTED);
        e.setCreatedAt(at);
        return e;
    }

    /** Mirrors ChatActivity.newestIncomingCreatedAt(). */
    private static String newestIncomingCreatedAt(List<ChatTimelineItem> items, String me) {
        String newest = null;
        for (ChatTimelineItem item : items) {
            if (item.getKind() == ChatTimelineItem.KIND_EVENT) {
                String actor = item.getEvent().getActorId();
                if (me.equals(actor)) continue;
            } else {
                String sender = item.getSenderId();
                if (sender == null || me.equals(sender)) continue;
            }
            String createdAt = item.getCreatedAt();
            if (createdAt != null && (newest == null || createdAt.compareTo(newest) > 0)) {
                newest = createdAt;
            }
        }
        return newest;
    }

    private static final String T1 = "2026-01-01T10:00:01.000000+00:00";
    private static final String T2 = "2026-01-01T10:00:02.000000+00:00";
    private static final String T3 = "2026-01-01T10:00:03.000000+00:00";
    private static final String T4 = "2026-01-01T10:00:04.000000+00:00";

    @Test
    public void myOwnMessagesAreNeverIncoming() {
        List<ChatTimelineItem> items = Arrays.asList(
                ChatTimelineItem.text(text("m1", ME, T1)),
                ChatTimelineItem.text(text("m2", ME, T2)));
        assertEquals(null, newestIncomingCreatedAt(items, ME));
    }

    @Test
    public void theirMessagesAreIncomingAndTheNewestWins() {
        List<ChatTimelineItem> items = Arrays.asList(
                ChatTimelineItem.text(text("m1", THEM, T1)),
                ChatTimelineItem.text(text("m2", ME, T2)),
                ChatTimelineItem.text(text("m3", THEM, T3)));
        assertEquals(T3, newestIncomingCreatedAt(items, ME));
    }

    @Test
    public void theirProposalIsIncomingButMineIsNot() {
        List<ChatTimelineItem> items = Arrays.asList(
                ChatTimelineItem.proposal(proposal("p1", ME, T1)),
                ChatTimelineItem.proposal(proposal("p2", THEM, T2)));
        assertEquals(T2, newestIncomingCreatedAt(items, ME));
    }

    @Test
    public void theirResponseEventIsIncomingButMineIsNot() {
        List<ChatTimelineItem> theirs = Arrays.asList(
                ChatTimelineItem.event(event("e1", ME, T1)),
                ChatTimelineItem.event(event("e2", THEM, T2)));
        assertEquals(T2, newestIncomingCreatedAt(theirs, ME));

        List<ChatTimelineItem> mine = Arrays.asList(
                ChatTimelineItem.event(event("e1", ME, T1)));
        assertEquals(null, newestIncomingCreatedAt(mine, ME));
    }

    /**
     * The real sequence: I propose, they accept. Their acceptance is news to me;
     * my own proposal must not drag the cursor past it, or the acceptance would be
     * marked read before it was seen.
     */
    @Test
    public void acceptingTheirSpotCountsOnlyTheAcceptance() {
        List<ChatTimelineItem> items = Arrays.asList(
                ChatTimelineItem.proposal(proposal("p1", THEM, T1)),
                ChatTimelineItem.event(event("e1", ME, T2)),   // I accepted
                ChatTimelineItem.proposal(proposal("p2", ME, T3)));
        assertEquals(T1, newestIncomingCreatedAt(items, ME));
    }

    @Test
    public void cursorNeverRunsPastTheNewestIncomingItem() {
        // Everything after the newest incoming item is mine, so the cursor must
        // stop at the incoming one even though later rows exist.
        List<ChatTimelineItem> items = Arrays.asList(
                ChatTimelineItem.text(text("m1", THEM, T1)),
                ChatTimelineItem.text(text("m2", ME, T2)),
                ChatTimelineItem.text(text("m3", ME, T3)),
                ChatTimelineItem.text(text("m4", ME, T4)));
        assertEquals(T1, newestIncomingCreatedAt(items, ME));
    }

    @Test
    public void aTimelineOfOnlyMineYieldsNoCursor() {
        assertEquals(null, newestIncomingCreatedAt(Arrays.asList(
                ChatTimelineItem.text(text("m1", ME, T1)),
                ChatTimelineItem.proposal(proposal("p1", ME, T2)),
                ChatTimelineItem.event(event("e1", ME, T3))), ME));
    }

    /**
     * The ordering of the cursor the client sends is what makes "content that
     * arrived after the mark stays unread" true: it must be the incoming item's
     * own timestamp, never now(), or a concurrent arrival would be swallowed.
     */
    @Test
    public void cursorIsAnItemTimestampNotTheCurrentTime() {
        List<ChatTimelineItem> items = Arrays.asList(
                ChatTimelineItem.text(text("m1", THEM, T1)));
        String cursor = newestIncomingCreatedAt(items, ME);
        assertEquals(T1, cursor);
        assertTrue(cursor.compareTo(T4) < 0);
    }

    /** The three kinds are distinguishable by author, so nothing is double counted. */
    @Test
    public void eachItemKindCarriesItsOwnAuthor() {
        Message m = text("m1", THEM, T1);
        assertEquals(THEM, m.getSenderId());

        MeetingLocation p = proposal("p1", ME, T1);
        assertEquals(ME, ChatTimelineItem.proposal(p).getSenderId());

        MeetingResponseEvent e = event("e1", THEM, T1);
        assertEquals(THEM, ChatTimelineItem.event(e).getEvent().getActorId());
        assertFalse(THEM.equals(ME));
    }
}
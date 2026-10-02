package com.example.lostandfound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.data.model.ChatTimeline;
import com.example.lostandfound.data.model.ChatTimelineItem;
import com.example.lostandfound.data.model.MeetingLocation;
import com.example.lostandfound.data.model.MeetingResponseEvent;
import com.example.lostandfound.data.model.Message;
import com.example.lostandfound.data.model.ReplyPreview;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers the timeline rules that are easy to break and expensive to notice on a
 * phone: chronological merge of three sources, de-duplication by id, the
 * distinction between "previously accepted" and "current agreed spot", and quote
 * resolution for both kinds of reply target.
 *
 * These are the rules that make a poll safe to run every few seconds.
 */
public class ChatTimelineTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Message message(String id, String sender, String text, String at) {
        Message m = new Message("conv-1", sender, sender, text);
        m.setId(id);
        m.setCreatedAt(at);
        return m;
    }

    private static MeetingLocation proposal(String id, String proposer, String status,
                                            boolean isCurrent, String at) {
        MeetingLocation p = new MeetingLocation();
        p.setId(id);
        p.setConversationId("conv-1");
        p.setProposerId(proposer);
        p.setLatitude(10.7610);
        p.setLongitude(78.8220);
        p.setLocationNote("Octagon");
        p.setStatus(status);
        p.setIsCurrent(isCurrent);
        p.setCreatedAt(at);
        return p;
    }

    private static MeetingResponseEvent event(String id, String meetingId, String actor,
                                              String response, String at) {
        MeetingResponseEvent e = new MeetingResponseEvent();
        e.setId(id);
        e.setConversationId("conv-1");
        e.setMeetingId(meetingId);
        e.setActorId(actor);
        e.setResponse(response);
        e.setCreatedAt(at);
        return e;
    }

    private static List<String> keys(List<ChatTimelineItem> items) {
        List<String> out = new ArrayList<>();
        for (ChatTimelineItem item : items) out.add(item.getKey());
        return out;
    }

    private static ChatTimeline.NameResolver aliceIsAlex() {
        return userId -> {
            if ("u-alice".equals(userId)) return "Alex";
            if ("u-bob".equals(userId)) return "Robin";
            return null;
        };
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    @Test
    public void mergesAllThreeSourcesInChronologicalOrder() {
        List<Message> messages = Arrays.asList(
                message("m1", "u-bob", "first", "2026-01-01T10:00:00.000000+00:00"),
                message("m2", "u-alice", "third", "2026-01-01T10:00:02.000000+00:00"));
        List<MeetingLocation> proposals = Collections.singletonList(
                proposal("p1", "u-alice", "PROPOSED", false, "2026-01-01T10:00:01.000000+00:00"));
        List<MeetingResponseEvent> events = Collections.singletonList(
                event("e1", "p1", "u-bob", "ACCEPTED", "2026-01-01T10:00:03.000000+00:00"));

        List<ChatTimelineItem> merged = ChatTimeline.merge(messages, proposals, events);

        assertEquals(Arrays.asList("t:m1", "p:p1", "t:m2", "e:e1"), keys(merged));
    }

    /** Sub-second ordering must not collapse to a same-second tie. */
    @Test
    public void ordersWithinTheSameSecondByFraction() {
        List<Message> messages = Arrays.asList(
                message("m-late", "u-bob", "later", "2026-01-01T10:00:00.900000+00:00"),
                message("m-early", "u-bob", "earlier", "2026-01-01T10:00:00.100000+00:00"));
        assertEquals(Arrays.asList("t:m-early", "t:m-late"),
                keys(ChatTimeline.merge(messages, null, null)));
    }

    /** A row created in the same instant as its own event still orders stably. */
    @Test
    public void sameInstantRowsOrderDeterministically() {
        String at = "2026-01-01T10:00:00.000000+00:00";
        List<ChatTimelineItem> first = ChatTimeline.merge(
                Collections.singletonList(message("m1", "u-bob", "x", at)),
                Collections.singletonList(proposal("p1", "u-alice", "PROPOSED", false, at)),
                Collections.singletonList(event("e1", "p1", "u-bob", "ACCEPTED", at)));
        List<ChatTimelineItem> second = ChatTimeline.merge(
                Collections.singletonList(message("m1", "u-bob", "x", at)),
                Collections.singletonList(proposal("p1", "u-alice", "PROPOSED", false, at)),
                Collections.singletonList(event("e1", "p1", "u-bob", "ACCEPTED", at)));
        assertEquals(keys(first), keys(second));
        assertEquals(3, first.size());
    }

    /** Mixed offsets and a missing fraction must not break the comparison. */
    @Test
    public void sortKeyNormalisesFractionsAndOffsets() {
        assertEquals("2026-01-01T10:00:00.100000",
                ChatTimeline.sortKey("2026-01-01T10:00:00.1+00:00"));
        assertEquals("2026-01-01T10:00:00.000000",
                ChatTimeline.sortKey("2026-01-01T10:00:00Z"));
        assertEquals("2026-01-01T10:00:00.000000",
                ChatTimeline.sortKey("2026-01-01T10:00:00+05:30"));
        assertEquals("2026-01-01T10:00:00.123456",
                ChatTimeline.sortKey("2026-01-01T10:00:00.123456789+00:00"));
        assertEquals("", ChatTimeline.sortKey(null));
        assertTrue(ChatTimeline.sortKey("2026-01-01T10:00:00.2+00:00")
                .compareTo(ChatTimeline.sortKey("2026-01-01T10:00:00.10+00:00")) > 0);
    }

    // ------------------------------------------------------------------
    // De-duplication
    // ------------------------------------------------------------------

    /**
     * An optimistic own-send followed by a poll can hand the same row in twice.
     * It must collapse to one, and the copy from the poll wins because it carries
     * the server's timestamp and id.
     */
    @Test
    public void deduplicatesById() {
        Message optimistic = message("m1", "u-alice", "hi", null);
        Message fromServer = message("m1", "u-alice", "hi", "2026-01-01T10:00:00+00:00");

        List<ChatTimelineItem> merged = ChatTimeline.merge(
                Arrays.asList(optimistic, fromServer), null, null);

        assertEquals(1, merged.size());
        assertNotNull(merged.get(0).getCreatedAt());
    }

    /** A message and a proposal can never be the same row, even on shared id text. */
    @Test
    public void doesNotConflateAcrossKinds() {
        List<ChatTimelineItem> merged = ChatTimeline.merge(
                Collections.singletonList(message("same", "u-bob", "text", "2026-01-01T10:00:00+00:00")),
                Collections.singletonList(proposal("same", "u-alice", "PROPOSED", false,
                        "2026-01-01T10:00:01+00:00")),
                Collections.singletonList(event("same", "same", "u-bob", "ACCEPTED",
                        "2026-01-01T10:00:02+00:00")));
        assertEquals(Arrays.asList("t:same", "p:same", "e:same"), keys(merged));
    }

    @Test
    public void ignoresNullAndIdlessRows() {
        List<ChatTimelineItem> merged = ChatTimeline.merge(
                Arrays.asList(null, new Message(), message("m1", "u-bob", "x", "2026-01-01T10:00:00+00:00")),
                Arrays.asList((MeetingLocation) null), null);
        assertEquals(1, merged.size());
    }

    // ------------------------------------------------------------------
    // Proposal history
    // ------------------------------------------------------------------

    /**
     * The core requirement: several proposals coexist, and each keeps its own
     * outcome. Only one is the current agreed spot, and an older acceptance is
     * still readable as "previously accepted" rather than being flattened.
     */
    @Test
    public void severalProposalsCoexistWithDistinctOutcomes() {
        List<MeetingLocation> proposals = Arrays.asList(
                proposal("p1", "u-alice", "REJECTED", false, "2026-01-01T10:00:00+00:00"),
                proposal("p2", "u-bob", "ACCEPTED", false, "2026-01-01T10:05:00+00:00"),
                proposal("p3", "u-alice", "ACCEPTED", true, "2026-01-01T10:10:00+00:00"),
                proposal("p4", "u-bob", "PROPOSED", false, "2026-01-01T10:15:00+00:00"));

        List<ChatTimelineItem> merged = ChatTimeline.merge(null, proposals, null);

        assertTrue("no proposal may be dropped or overwritten", merged.size() == 4);
        assertEquals(Arrays.asList("p:p1", "p:p2", "p:p3", "p:p4"), keys(merged));

        assertTrue(merged.get(0).getProposal().isRejected());
        assertFalse(merged.get(0).getProposal().isCurrent());

        // Previously accepted: accepted, but not the spot you are meeting at.
        assertTrue(merged.get(1).getProposal().isAccepted());
        assertFalse(merged.get(1).getProposal().isCurrent());

        // Current agreed spot.
        assertTrue(merged.get(2).getProposal().isAccepted());
        assertTrue(merged.get(2).getProposal().isCurrent());

        // Unanswered and still pending.
        assertTrue(merged.get(3).getProposal().isProposed());
    }

    @Test
    public void proposalStateHelpersAreDistinct() {
        MeetingLocation pending = proposal("p", "u", MeetingLocation.STATUS_PROPOSED, false, null);
        MeetingLocation accepted = proposal("p", "u", MeetingLocation.STATUS_ACCEPTED, false, null);
        MeetingLocation rejected = proposal("p", "u", MeetingLocation.STATUS_REJECTED, false, null);
        MeetingLocation legacy = proposal("p", "u", MeetingLocation.STATUS_SUPERSEDED, false, null);

        assertTrue(pending.isProposed());
        assertFalse(pending.isAnswered());
        assertTrue(accepted.isAccepted());
        assertTrue(accepted.isAnswered());
        assertTrue(rejected.isRejected());
        assertTrue(rejected.isAnswered());
        // Legacy rows still validate, and must not be mistaken for a live state.
        assertTrue(legacy.isSuperseded());
        assertFalse(legacy.isAnswered());
        assertFalse(legacy.isProposed());
    }

    // ------------------------------------------------------------------
    // Quoted replies
    // ------------------------------------------------------------------

    @Test
    public void resolvesQuoteToATextMessage() {
        Message original = message("m1", "u-bob", "meet at the octagon", "2026-01-01T10:00:00+00:00");
        Message reply = message("m2", "u-alice", "which side?", "2026-01-01T10:01:00+00:00");
        reply.withReplyToMessage("m1");

        List<ChatTimelineItem> merged = ChatTimeline.merge(
                Arrays.asList(original, reply), null, null, aliceIsAlex());

        ReplyPreview preview = merged.get(1).getReplyPreview();
        assertNotNull(preview);
        assertEquals("Robin", preview.getSenderName());
        assertEquals("meet at the octagon", preview.getExcerpt());
        assertFalse(preview.isMeetingSpot());
        assertEquals("t:m1", merged.get(1).getReplyTargetKey());
    }

    @Test
    public void resolvesQuoteToAMeetingProposalCard() {
        MeetingLocation spot = proposal("p1", "u-alice", "PROPOSED", false, "2026-01-01T10:00:00+00:00");
        Message reply = message("m1", "u-bob", "works for me", "2026-01-01T10:01:00+00:00");
        reply.withReplyToMeeting("p1");

        List<ChatTimelineItem> merged = ChatTimeline.merge(
                Collections.singletonList(reply), Collections.singletonList(spot), null, aliceIsAlex());

        ReplyPreview preview = merged.get(1).getReplyPreview();
        assertNotNull(preview);
        assertTrue(preview.isMeetingSpot());
        assertEquals("Alex", preview.getSenderName());
        // The note becomes the excerpt; the UI labels it "Meeting spot".
        assertEquals("Octagon", preview.getExcerpt());
        assertEquals("p:p1", merged.get(1).getReplyTargetKey());
    }

    /** A quote target that is not loaded still yields a preview, never null. */
    @Test
    public void unresolvedQuoteStillRendersSomething() {
        Message reply = message("m1", "u-alice", "ok", "2026-01-01T10:00:00+00:00");
        reply.withReplyToMessage("m-not-loaded");

        ReplyPreview preview = ChatTimeline.merge(
                Collections.singletonList(reply), null, null, aliceIsAlex())
                .get(0).getReplyPreview();

        assertNotNull(preview);
        assertEquals(ReplyPreview.UNKNOWN_SENDER, preview.getSenderName());
    }

    @Test
    public void messageWithoutAQuoteHasNoPreview() {
        assertNull(ChatTimeline.merge(
                Collections.singletonList(message("m1", "u-bob", "plain", "2026-01-01T10:00:00+00:00")),
                null, null).get(0).getReplyPreview());
    }

    /** Quote resolution must be stable across a re-merge (profile names arriving). */
    @Test
    public void quotePreviewIsRepopulatedOnReMerge() {
        Message original = message("m1", "u-bob", "text", "2026-01-01T10:00:00+00:00");
        Message reply = message("m2", "u-alice", "reply", "2026-01-01T10:01:00+00:00");
        reply.withReplyToMessage("m1");

        List<Message> messages = Arrays.asList(original, reply);
        ReplyPreview before = ChatTimeline.merge(messages, null, null, null).get(1).getReplyPreview();
        ReplyPreview after = ChatTimeline.merge(messages, null, null, aliceIsAlex()).get(1).getReplyPreview();

        assertEquals(ReplyPreview.UNKNOWN_SENDER, before.getSenderName());
        assertEquals("Robin", after.getSenderName());
        assertEquals(before.getExcerpt(), after.getExcerpt());
    }

    // ------------------------------------------------------------------
    // Diff support
    // ------------------------------------------------------------------

    /** A state change must change the signature; an identical row must not. */
    @Test
    public void contentSignatureTracksRenderedState() {
        ChatTimelineItem before = ChatTimelineItem.proposal(
                proposal("p1", "u-alice", "PROPOSED", false, "2026-01-01T10:00:00+00:00"));
        ChatTimelineItem sameAgain = ChatTimelineItem.proposal(
                proposal("p1", "u-alice", "PROPOSED", false, "2026-01-01T10:00:00+00:00"));
        ChatTimelineItem accepted = ChatTimelineItem.proposal(
                proposal("p1", "u-alice", "ACCEPTED", true, "2026-01-01T10:00:00+00:00"));

        assertEquals(before.contentSignature(), sameAgain.contentSignature());
        assertFalse(before.contentSignature().equals(accepted.contentSignature()));
    }

    @Test
    public void resolvedQuoteChangesTheSignatureSoTheRowRebinds() {
        Message reply = message("m1", "u-alice", "ok", "2026-01-01T10:00:00+00:00");
        reply.withReplyToMessage("m1");
        ChatTimelineItem item = ChatTimelineItem.text(reply);
        String unresolved = item.contentSignature();

        item.setReplyPreview(new ReplyPreview("Robin", "original", false));
        assertFalse(unresolved.equals(item.contentSignature()));
    }

    // ------------------------------------------------------------------
    // Row affordances
    // ------------------------------------------------------------------

    @Test
    public void onlyTextAndProposalsAreReplyable() {
        assertTrue(ChatTimelineItem.text(new Message()).isReplyable());
        assertTrue(ChatTimelineItem.proposal(new MeetingLocation()).isReplyable());
        assertFalse(ChatTimelineItem.event(new MeetingResponseEvent()).isReplyable());
    }

    /**
     * The swipe gesture consults this per frame, so the rule has to be a property
     * of the item rather than of where it happens to sit in the list: sent and
     * received messages and proposal cards are targets, centered response events
     * never are - including an accepted one, which is the row a reader is most
     * likely to swipe at.
     */
    @Test
    public void responseEventsAreNeverReplyableRegardlessOfOutcome() {
        for (String response : new String[]{"ACCEPTED", "REJECTED"}) {
            MeetingResponseEvent e = event("e1", "p1", "u-bob", response,
                    "2026-01-01T10:00:00+00:00");
            ChatTimelineItem item = ChatTimelineItem.event(e);
            assertFalse(response + " event must not be a reply target", item.isReplyable());
        }
    }

    /** Both message directions and both proposal states stay swipable. */
    @Test
    public void sentReceivedAndEveryProposalStateAreReplyable() {
        Message mine = message("m1", "u-alice", "hi", "2026-01-01T10:00:00+00:00");
        Message theirs = message("m2", "u-bob", "hello", "2026-01-01T10:00:01+00:00");
        assertTrue(ChatTimelineItem.text(mine).isReplyable());
        assertTrue(ChatTimelineItem.text(theirs).isReplyable());

        for (String status : new String[]{
                MeetingLocation.STATUS_PROPOSED, MeetingLocation.STATUS_ACCEPTED,
                MeetingLocation.STATUS_REJECTED, MeetingLocation.STATUS_SUPERSEDED}) {
            assertTrue(status + " proposal should still be replyable",
                    ChatTimelineItem.proposal(
                            proposal("p", "u-alice", status, false, null)).isReplyable());
        }
    }

    @Test
    public void eventCarriesTheExactProposalItAnswers() {
        MeetingResponseEvent e = event("e1", "p42", "u-bob", "ACCEPTED", "2026-01-01T10:00:00+00:00");
        assertEquals("p42", e.getMeetingId());
        assertTrue(e.isAccepted());
        assertFalse(e.isRejected());
    }
}

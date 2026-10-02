package com.example.lostandfound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.ui.chat.ChatScrollPolicy;

import org.junit.Test;

/**
 * Regression cover for the "the chat keeps scrolling down by itself" report.
 *
 * A meeting spot is accepted, a message is sent below it, and the reader taps
 * the quote on that message to jump to the spot. The accepted proposal sits one
 * row above the newest message, so a row-count notion of "at the bottom" called
 * it the bottom - and the next poll, 3.5 seconds later, pulled the reader away
 * from the row they had just asked to see, over and over.
 */
public class ChatScrollPolicyTest {

    private static final int LIST_HEIGHT = 2000;
    private static final int BOTTOM_PADDING = 8;
    private static final int THRESHOLD = 160;

    /** Distance from the true end of the list, given the last row's bottom edge. */
    private static int scrolledToEnd(int lastRowBottom) {
        return (LIST_HEIGHT - BOTTOM_PADDING) - lastRowBottom;
    }

    private static boolean nearBottom(int lastRowBottom) {
        return ChatScrollPolicy.isNearBottom(
                LIST_HEIGHT, lastRowBottom, BOTTOM_PADDING, THRESHOLD);
    }

    // ------------------------------------------------------------------
    // isNearBottom, in pixels
    // ------------------------------------------------------------------

    @Test
    public void scrolledToTheEndIsNearBottom() {
        assertTrue(nearBottom(LIST_HEIGHT - BOTTOM_PADDING));
    }

    /** A partially-visible final row still counts as being at the end. */
    @Test
    public void lastRowJustBelowTheFoldIsNearBottom() {
        assertTrue(nearBottom(LIST_HEIGHT - BOTTOM_PADDING - THRESHOLD));
        assertTrue(nearBottom(LIST_HEIGHT - BOTTOM_PADDING - THRESHOLD + 1));
    }

    @Test
    public void comfortablyAboveTheEndIsNotNearBottom() {
        assertFalse(nearBottom(LIST_HEIGHT - BOTTOM_PADDING - THRESHOLD - 1));
        assertFalse(nearBottom(LIST_HEIGHT / 2));
    }

    /**
     * The regression itself. The reader is looking at a ~200dp proposal card
     * that sits just above the newest message, with 240px of conversation still
     * below it. A row-count rule called this "the bottom" (only one short row
     * follows the card) and the next poll dragged the reader away from the row
     * they had just asked to see. Pixels do not.
     */
    @Test
    public void oneTallRowAboveTheEndIsNotNearBottom() {
        int proposalCardHeightPx = 560;        // ~200dp at 2.75x density
        int roomBelowPx = 240;
        int lastRowBottom = (LIST_HEIGHT - BOTTOM_PADDING) - roomBelowPx;

        assertTrue("precondition: the card is tall", proposalCardHeightPx > roomBelowPx);
        assertEquals(roomBelowPx, scrolledToEnd(lastRowBottom));
        assertFalse("a tall card must not read as the bottom", nearBottom(lastRowBottom));
    }

    /** Many short rows above the end read the same way - it is distance, not count. */
    @Test
    public void manyShortRowsAboveTheEndAreAlsoNotNearBottom() {
        int roomBelowPx = 600;
        int lastRowBottom = (LIST_HEIGHT - BOTTOM_PADDING) - roomBelowPx;
        assertEquals(roomBelowPx, scrolledToEnd(lastRowBottom));
        assertFalse(nearBottom(lastRowBottom));
    }

    /** An unmeasured list must not read as "not at the bottom" and strand the view. */
    @Test
    public void unmeasuredListIsTreatedAsTheEnd() {
        assertTrue(ChatScrollPolicy.isNearBottom(0, 0, 0, THRESHOLD));
        assertTrue(ChatScrollPolicy.isNearBottom(-1, 0, 0, THRESHOLD));
    }

    // ------------------------------------------------------------------
    // shouldFollow
    // ------------------------------------------------------------------

    @Test
    public void initialLoadAlwaysFollows() {
        assertTrue(ChatScrollPolicy.shouldFollow(true, false, false));
        assertTrue(ChatScrollPolicy.shouldFollow(true, true, false));
    }

    @Test
    public void atTheBottomAndUnpinnedFollows() {
        assertTrue(ChatScrollPolicy.shouldFollow(false, false, true));
    }

    @Test
    public void awayFromTheBottomDoesNotFollow() {
        assertFalse(ChatScrollPolicy.shouldFollow(false, false, false));
    }

    /**
     * The regression, at the decision level: after an explicit jump the view is
     * pinned, so no amount of "being at the bottom" lets a poll drag the reader
     * away from the row they asked to see.
     */
    @Test
    public void pinnedNeverFollows() {
        assertFalse(ChatScrollPolicy.shouldFollow(false, true, true));
        assertFalse(ChatScrollPolicy.shouldFollow(false, true, false));
    }

    /** Releasing the pin on a user drag restores normal follow behaviour. */
    @Test
    public void releasedPinFollowsAgain() {
        boolean pinnedAfterJump = true;
        boolean dragged = true;                   // SCROLL_STATE_DRAGGING
        pinnedAfterJump = !dragged;               // the listener clears it
        assertTrue(ChatScrollPolicy.shouldFollow(false, pinnedAfterJump, true));
    }
}

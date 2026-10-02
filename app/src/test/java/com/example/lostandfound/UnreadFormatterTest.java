package com.example.lostandfound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.ui.common.UnreadFormatter;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The badge vocabulary. These are the rules a reader sees the number through, so
 * they are pinned here rather than only on a screen.
 */
public class UnreadFormatterTest {

    @Test
    public void zeroAndNegativeShowNoBadge() {
        // An empty string is what makes the badge disappear entirely instead of
        // sitting there showing a zero.
        assertEquals("", UnreadFormatter.badge(0));
        assertEquals("", UnreadFormatter.badge(-3));
        assertFalse(UnreadFormatter.shouldShow(0));
        assertFalse(UnreadFormatter.shouldShow(-1));
    }

    @Test
    public void smallCountsShowPlainly() {
        assertEquals("1", UnreadFormatter.badge(1));
        assertEquals("9", UnreadFormatter.badge(9));
        assertEquals("42", UnreadFormatter.badge(42));
        assertEquals("99", UnreadFormatter.badge(99));
        assertTrue(UnreadFormatter.shouldShow(1));
    }

    @Test
    public void countsAboveTheCapAreCapped() {
        assertEquals("99+", UnreadFormatter.badge(100));
        assertEquals("99+", UnreadFormatter.badge(1000));
        assertEquals("99+", UnreadFormatter.badge(Integer.MAX_VALUE));
    }

    @Test
    public void capBoundaryIsCorrect() {
        assertEquals("99", UnreadFormatter.badge(UnreadFormatter.BADGE_CAP));
        assertEquals("99+", UnreadFormatter.badge(UnreadFormatter.BADGE_CAP + 1));
    }

    @Test
    public void navTotalSumsEveryUnreadConversation() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("c1", 3);
        counts.put("c2", 7);
        counts.put("c3", 1);
        assertEquals(11, UnreadFormatter.total(counts));
        assertEquals("11", UnreadFormatter.badge(UnreadFormatter.total(counts)));
    }

    @Test
    public void navTotalIsCappedEvenThoughItIsASum() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("c1", 80);
        counts.put("c2", 60);
        assertEquals(140, UnreadFormatter.total(counts));
        assertEquals("99+", UnreadFormatter.badge(UnreadFormatter.total(counts)));
    }

    @Test
    public void emptyOrNullTotalsAreZero() {
        assertEquals(0, UnreadFormatter.total(null));
        assertEquals(0, UnreadFormatter.total(new HashMap<>()));
        assertEquals("", UnreadFormatter.badge(UnreadFormatter.total(null)));
    }

    /** Read conversations are absent from the result, not present as zero. */
    @Test
    public void absentConversationCountsAsRead() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("c1", 2);
        assertEquals(2, UnreadFormatter.countFor(counts, "c1"));
        assertEquals(0, UnreadFormatter.countFor(counts, "c2"));
        assertEquals(0, UnreadFormatter.countFor(counts, null));
        assertEquals(0, UnreadFormatter.countFor(null, "c1"));
    }

    @Test
    public void totalIgnoresNonPositiveAndNullEntries() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("c1", 0);
        counts.put("c2", null);
        counts.put("c3", 5);
        assertEquals(5, UnreadFormatter.total(counts));
    }

    /**
     * Per-conversation counts are independent: clearing one conversation must not
     * disturb another's.
     */
    @Test
    public void countsAreTrackedPerConversation() {
        Map<String, Integer> before = new HashMap<>();
        before.put("c1", 4);
        before.put("c2", 2);

        Map<String, Integer> afterRead = new HashMap<>(before);
        afterRead.remove("c1");   // the server omits conversations with nothing unread

        assertEquals(0, UnreadFormatter.countFor(afterRead, "c1"));
        assertEquals(2, UnreadFormatter.countFor(afterRead, "c2"));
        assertEquals(2, UnreadFormatter.total(afterRead));
        assertEquals("2", UnreadFormatter.badge(UnreadFormatter.total(afterRead)));
    }
}
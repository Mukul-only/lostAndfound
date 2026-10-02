package com.example.lostandfound;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.util.DateUtils;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Guards the "incident time cannot be in the future" rule on create report.
 *
 * <p>The comparison is against the wall clock, so cases are expressed as offsets
 * from today rather than fixed dates.
 */
public class IncidentTimeFutureTest {

    private static Calendar now() {
        return Calendar.getInstance();
    }

    private static String isoDate(int dayOffset) {
        Calendar c = now();
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    /** HH:mm shifted by {@code minuteOffset} from the current wall clock. */
    private static String time(int minuteOffset) {
        Calendar c = now();
        c.add(Calendar.MINUTE, minuteOffset);
        return String.format(Locale.US, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    @Test
    public void timeLaterToday_isFuture() {
        assertTrue("a time later today must be rejected",
                DateUtils.isFutureDateTime(isoDate(0), time(120)));
    }

    @Test
    public void yesterdayWithLateTime_isNotFuture() {
        assertFalse(DateUtils.isFutureDateTime(isoDate(-1), "23:59"));
    }

    @Test
    public void futureDate_isRejectedEvenWithEarliestTime() {
        // The date picker blocks future dates, but a future date restored from
        // saved state must not slip through paired with an early time.
        assertTrue(DateUtils.isFutureDateTime(isoDate(3), "00:01"));
    }

    @Test
    public void currentMinute_isNotFuture() {
        // Seconds are dropped when the picker stores HH:mm, so the current
        // minute must stay valid: only strictly later moments are rejected.
        assertFalse(DateUtils.isFutureDateTime(isoDate(0), time(0)));
    }

    @Test
    public void unparseableInput_doesNotBlockTheReport() {
        assertFalse(DateUtils.isFutureDateTime(isoDate(0), null));
        assertFalse(DateUtils.isFutureDateTime(isoDate(0), ""));
        assertFalse(DateUtils.isFutureDateTime(isoDate(0), "Around 2:00 PM"));
        assertFalse(DateUtils.isFutureDateTime(isoDate(0), "25:00"));
        assertFalse(DateUtils.isFutureDateTime("not-a-date", "23:59"));
        assertFalse(DateUtils.isFutureDateTime(null, "23:59"));
    }
}
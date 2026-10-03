package com.example.lostandfound.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.data.model.Report;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pins the client-side half of a reload response: which rows survive the
 * FOUND_RECENTLY 7-day window. What happens with the surviving rows —
 * replacing the adapter, clearing it on empty, keeping it on failure — lives
 * in the fragment's view-bound branches and needs a device; the mapping from
 * server rows to display rows is decided here and asserted here.
 */
public class ReportClientFilterTest {

    private static String iso(int dayOffset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private static Report report(String type, String incidentDate) {
        Report report = new Report();
        report.setType(type);
        report.setIncidentDate(incidentDate);
        return report;
    }

    private static Date now() {
        return Calendar.getInstance().getTime();
    }

    @Test
    public void allFilter_passesEverythingThrough() {
        List<Report> rows = new ArrayList<>();
        rows.add(report(Report.TYPE_FOUND, iso(-30)));
        rows.add(report(Report.TYPE_LOST, iso(-1)));
        assertEquals(2, HomeFragment.applyClientFilter(rows, "ALL", now()).size());
    }

    @Test
    public void stillLostFilter_passesEverythingThrough() {
        // The server already filtered by type; the client adds nothing.
        List<Report> rows = new ArrayList<>();
        rows.add(report(Report.TYPE_LOST, iso(-400)));
        assertEquals(1, HomeFragment.applyClientFilter(rows, "STILL_LOST", now()).size());
    }

    @Test
    public void foundRecently_keepsOnlyRecentFound() {
        List<Report> rows = new ArrayList<>();
        rows.add(report(Report.TYPE_FOUND, iso(-1)));
        rows.add(report(Report.TYPE_FOUND, iso(-30)));
        rows.add(report(Report.TYPE_LOST, iso(-1)));
        List<Report> kept = HomeFragment.applyClientFilter(rows, "FOUND_RECENTLY", now());
        assertEquals(1, kept.size());
        assertTrue(kept.get(0).isFound());
    }

    @Test
    public void foundRecently_unparseableDateFailsOpen() {
        Report broken = report(Report.TYPE_FOUND, "not-a-date");
        List<Report> rows = new ArrayList<>();
        rows.add(broken);
        List<Report> kept = HomeFragment.applyClientFilter(rows, "FOUND_RECENTLY", now());
        assertEquals("a bad date must not silently drop the report",
                1, kept.size());
    }

    @Test
    public void nullResponse_mapsToEmpty() {
        assertTrue(HomeFragment.applyClientFilter(null, "ALL", now()).isEmpty());
    }

    @Test
    public void emptyResponse_mapsToEmpty() {
        assertTrue(HomeFragment.applyClientFilter(new ArrayList<Report>(), "ALL", now()).isEmpty());
    }
}
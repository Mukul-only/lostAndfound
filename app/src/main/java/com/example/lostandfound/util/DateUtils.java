package com.example.lostandfound.util;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DateUtils {
    private static final SimpleDateFormat ISO_FORMAT;
    private static final SimpleDateFormat DISPLAY_DATE_FORMAT;
    private static final SimpleDateFormat DISPLAY_TIME_FORMAT;
    private static final Pattern HH_MM_PATTERN = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");

    static {
        ISO_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));

        DISPLAY_DATE_FORMAT = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        DISPLAY_TIME_FORMAT = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    }

    public static String formatDisplayDate(String isoString) {
        if (isoString == null || isoString.isEmpty()) return "";
        try {
            String clean = isoString;
            int dotIndex = clean.indexOf('.');
            if (dotIndex > 0) {
                clean = clean.substring(0, dotIndex);
            }
            int plusIndex = clean.indexOf('+');
            if (plusIndex > 0) {
                clean = clean.substring(0, plusIndex);
            }
            // Full ISO timestamp first, then plain yyyy-MM-dd (incident dates are
            // stored without a time component and would fail the ISO parse).
            Date date = null;
            try {
                date = ISO_FORMAT.parse(clean);
            } catch (Exception ignored) {}
            if (date == null) {
                date = parseDateOnly(clean);
            }
            if (date != null) {
                return DISPLAY_DATE_FORMAT.format(date);
            }
        } catch (Exception ignored) {}
        return isoString;
    }

    public static String formatDisplayTime(String isoString) {
        if (isoString == null || isoString.isEmpty()) return "";
        try {
            String clean = isoString;
            int dotIndex = clean.indexOf('.');
            if (dotIndex > 0) {
                clean = clean.substring(0, dotIndex);
            }
            int plusIndex = clean.indexOf('+');
            if (plusIndex > 0) {
                clean = clean.substring(0, plusIndex);
            }
            Date date = ISO_FORMAT.parse(clean);
            if (date != null) {
                return DISPLAY_TIME_FORMAT.format(date);
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static String getTodayIsoDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new Date());
    }

    /**
     * Formats an hour and minute into the canonical 24-hour storage format (HH:mm).
     */
    public static String formatTimeForStorage(int hourOfDay, int minute) {
        return String.format(Locale.US, "%02d:%02d", hourOfDay, minute);
    }

    /**
     * Formats an hour and minute for user display, respecting the device's 12-hour/24-hour preference.
     */
    public static String formatTimeForDisplay(Context context, int hourOfDay, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
        cal.set(Calendar.MINUTE, minute);

        if (context != null) {
            try {
                java.text.DateFormat df = android.text.format.DateFormat.getTimeFormat(context);
                return df.format(cal.getTime());
            } catch (Exception ignored) {}
        }

        // Fallback if context is null or running in standard JVM unit tests
        return DISPLAY_TIME_FORMAT.format(cal.getTime());
    }

    /**
     * Formats a stored time string for display.
     * If the time string is structured (HH:mm), it formats it respecting 12h/24h device preference.
     * If it is a legacy/older free-text value (e.g. "Around 2:00 PM"), it safely returns the raw
     * string so older reports never crash or lose their data.
     */
    public static String formatDisplayTimeFromStorage(Context context, String rawTime) {
        if (rawTime == null || rawTime.trim().isEmpty()) {
            return "";
        }
        String trimmed = rawTime.trim();
        Matcher matcher = HH_MM_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            try {
                int hour = Integer.parseInt(matcher.group(1));
                int minute = Integer.parseInt(matcher.group(2));
                return formatTimeForDisplay(context, hour, minute);
            } catch (Exception ignored) {}
        }
        // Legacy free-text format: preserve raw string safely
        return trimmed;
    }

    public static boolean isStructuredTime(String timeStr) {
        if (timeStr == null) return false;
        return HH_MM_PATTERN.matcher(timeStr.trim()).matches();
    }

    /**
     * True when the incident date (yyyy-MM-dd) plus time (HH:mm) fall after the
     * current moment, i.e. the report would claim to have happened in the
     * future.
     *
     * <p>Both parts are needed: the date picker already forbids future dates, so
     * in practice only "today + a later time" can be future. Checking the pair
     * rather than the time alone still catches a future date restored from saved
     * state.
     *
     * <p>Unparseable input returns false, so a formatting quirk never blocks an
     * otherwise valid report.
     */
    public static boolean isFutureDateTime(String isoDate, String timeHHmm) {
        Date day = parseDateOnly(isoDate);
        if (day == null) return false;

        Matcher matcher = HH_MM_PATTERN.matcher(timeHHmm == null ? "" : timeHHmm.trim());
        if (!matcher.matches()) return false;

        Calendar calendar = Calendar.getInstance();
        calendar.setTime(day);
        calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(matcher.group(1)));
        calendar.set(Calendar.MINUTE, Integer.parseInt(matcher.group(2)));
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime().after(new Date());
    }

    /**
     * Parses a plain yyyy-MM-dd string keeping the calendar date as-is (device
     * timezone), so display never shifts the date by a day. Returns null on failure.
     */
    private static Date parseDateOnly(String isoDate) {
        if (isoDate == null) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getDefault());
            return sdf.parse(isoDate.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Formats a stored yyyy-MM-dd date string for display using the device locale
     * (e.g. "Sep 29, 2026"). Returns the raw string if parsing fails, so legacy
     * values are never lost.
     */
    public static String formatDisplayDateFromIso(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        Date date = parseDateOnly(isoDate);
        if (date != null) {
            return DISPLAY_DATE_FORMAT.format(date);
        }
        return isoDate;
    }

    /**
     * Parses an ISO date string (yyyy-MM-dd) into a Date object.
     * Returns null if parsing fails.
     */
    public static Date parseIsoDate(String isoString) {
        if (isoString == null || isoString.isEmpty()) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(isoString);
        } catch (Exception e) {
            return null;
        }
    }
}

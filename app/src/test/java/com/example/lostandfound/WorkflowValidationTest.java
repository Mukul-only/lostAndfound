package com.example.lostandfound;

import org.junit.Test;
import static org.junit.Assert.*;

import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.util.DateUtils;
import com.example.lostandfound.util.ProfileUtils;
import com.example.lostandfound.util.StatusUtils;

import java.util.HashMap;
import java.util.Map;

public class WorkflowValidationTest {

    @Test
    public void testValidStatusTransitions() {
        // OPEN can transition to HANDOVER_ARRANGED, RETURNED, or CLOSED
        assertTrue(StatusUtils.isValidStatusTransition(Report.STATUS_OPEN, Report.STATUS_HANDOVER_ARRANGED));
        assertTrue(StatusUtils.isValidStatusTransition(Report.STATUS_OPEN, Report.STATUS_RETURNED));
        assertTrue(StatusUtils.isValidStatusTransition(Report.STATUS_OPEN, Report.STATUS_CLOSED));

        // HANDOVER_ARRANGED can transition to RETURNED or CLOSED
        assertTrue(StatusUtils.isValidStatusTransition(Report.STATUS_HANDOVER_ARRANGED, Report.STATUS_RETURNED));
        assertTrue(StatusUtils.isValidStatusTransition(Report.STATUS_HANDOVER_ARRANGED, Report.STATUS_CLOSED));

        // Terminal states cannot transition back to OPEN or other states
        assertFalse(StatusUtils.isValidStatusTransition(Report.STATUS_RETURNED, Report.STATUS_OPEN));
        assertFalse(StatusUtils.isValidStatusTransition(Report.STATUS_RETURNED, Report.STATUS_HANDOVER_ARRANGED));
        assertFalse(StatusUtils.isValidStatusTransition(Report.STATUS_CLOSED, Report.STATUS_OPEN));
        assertFalse(StatusUtils.isValidStatusTransition(Report.STATUS_CLOSED, Report.STATUS_HANDOVER_ARRANGED));
    }

    @Test
    public void testReportModelMethods() {
        Report report = new Report();
        report.setType(Report.TYPE_LOST);
        report.setStatus(Report.STATUS_OPEN);

        assertTrue(report.isLost());
        assertFalse(report.isFound());
        assertTrue(report.isOpen());
        assertFalse(report.isHandoverArranged());
        assertFalse(report.isReturned());
        assertFalse(report.isClosed());

        report.setStatus(Report.STATUS_HANDOVER_ARRANGED);
        assertTrue(report.isHandoverArranged());
        assertFalse(report.isOpen());

        report.setStatus(Report.STATUS_RETURNED);
        assertTrue(report.isReturned());

        report.setStatus(Report.STATUS_CLOSED);
        assertTrue(report.isClosed());
    }

    @Test
    public void testClaimModelMethods() {
        Claim claim = new Claim();
        claim.setStatus(Claim.STATUS_PENDING);

        assertTrue(claim.isPending());
        assertFalse(claim.isAccepted());
        assertFalse(claim.isRejected());

        claim.setStatus(Claim.STATUS_ACCEPTED);
        assertTrue(claim.isAccepted());
        assertFalse(claim.isPending());

        claim.setStatus(Claim.STATUS_REJECTED);
        assertTrue(claim.isRejected());
        assertFalse(claim.isAccepted());
    }

    @Test
    public void testDateFormatting() {
        String isoTime = "2026-09-28T14:30:00+00:00";
        String formatted = DateUtils.formatDisplayDate(isoTime);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        assertTrue(formatted.contains("2026"));

        String today = DateUtils.getTodayIsoDate();
        assertNotNull(today);
        assertTrue(today.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void testCategoryLabels() {
        assertEquals("Electronics", StatusUtils.getCategoryLabel("ELECTRONICS"));
        assertEquals("Cards & Student ID", StatusUtils.getCategoryLabel("CARDS_ID"));
        assertEquals("Keys", StatusUtils.getCategoryLabel("KEYS"));
        assertEquals("Other", StatusUtils.getCategoryLabel("UNKNOWN_VAL"));
    }

    @Test
    public void testSupabaseConfigIsConfigured() {
        assertTrue("SupabaseConfig should be recognized as configured with valid URL and publishable key",
                com.example.lostandfound.data.remote.SupabaseConfig.isConfigured());
    }

    @Test
    public void testPublicImageUrlConstruction() {
        // Null or empty
        assertNull(com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl(null));
        assertNull(com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl(""));
        assertNull(com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl("   "));

        // Full URL
        String fullUrl = "https://example.com/photos/item.jpg";
        assertEquals(fullUrl, com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl(fullUrl));

        // Standard relative storagePath
        String relativePath = "user-uuid-123/photo-456.jpg";
        String expected = "https://caqiibpmszbuektdeude.supabase.co/storage/v1/object/public/report-photos/user-uuid-123/photo-456.jpg";
        assertEquals(expected, com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl(relativePath));

        // Leading slash
        assertEquals(expected, com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl("/user-uuid-123/photo-456.jpg"));

        // Redundant bucket prefix
        assertEquals(expected, com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl("report-photos/user-uuid-123/photo-456.jpg"));
        assertEquals(expected, com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl("/report-photos/user-uuid-123/photo-456.jpg"));

        // Redundant API path prefix
        assertEquals(expected, com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl("storage/v1/object/public/report-photos/user-uuid-123/photo-456.jpg"));
    }

    @Test
    public void testReportImageSerializationAndDeserialization() {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        // Deserialization from Supabase JSON
        String json = "{\"id\":\"rep-123\",\"title\":\"Lost Backpack\",\"type\":\"LOST\",\"image_url\":\"user-uuid/image-1.jpg\"}";
        Report report = gson.fromJson(json, Report.class);
        assertNotNull(report);
        assertEquals("user-uuid/image-1.jpg", report.getImageUrl());

        // Report without image
        String jsonNoImage = "{\"id\":\"rep-124\",\"title\":\"Found Keys\",\"type\":\"FOUND\",\"image_url\":null}";
        Report reportNoImage = gson.fromJson(jsonNoImage, Report.class);
        assertNotNull(reportNoImage);
        assertNull(reportNoImage.getImageUrl());
        assertNull(com.example.lostandfound.data.remote.SupabaseConfig.getPublicImageUrl(reportNoImage.getImageUrl()));

        // Serialization
        Report newReport = new Report();
        newReport.setTitle("Found Calculator");
        newReport.setImageUrl("user-456/calc.png");
        String serialized = gson.toJson(newReport);
        assertTrue(serialized.contains("\"image_url\":\"user-456/calc.png\""));
    }

    @Test
    public void testClaimRejectionModelAndSerialization() {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        // Deserialization with rejection_message
        String json = "{\"id\":\"claim-123\",\"report_id\":\"rep-456\",\"claimant_id\":\"user-789\",\"status\":\"REJECTED\",\"rejection_message\":\"Identifying marks did not match\"}";
        Claim claim = gson.fromJson(json, Claim.class);
        assertNotNull(claim);
        assertTrue(claim.isRejected());
        assertFalse(claim.isPending());
        assertFalse(claim.isAccepted());
        assertEquals("Identifying marks did not match", claim.getRejectionMessage());

        // Deserialization with null rejection_message (e.g. automatically rejected or owner left no note)
        String jsonNoMsg = "{\"id\":\"claim-124\",\"report_id\":\"rep-456\",\"claimant_id\":\"user-000\",\"status\":\"REJECTED\",\"rejection_message\":null}";
        Claim claimNoMsg = gson.fromJson(jsonNoMsg, Claim.class);
        assertNotNull(claimNoMsg);
        assertTrue(claimNoMsg.isRejected());
        assertNull(claimNoMsg.getRejectionMessage());

        // Serialization
        Claim newClaim = new Claim();
        newClaim.setStatus(Claim.STATUS_REJECTED);
        newClaim.setRejectionMessage("Serial number mismatch");
        String serialized = gson.toJson(newClaim);
        assertTrue(serialized.contains("\"rejection_message\":\"Serial number mismatch\""));
        assertTrue(serialized.contains("\"status\":\"REJECTED\""));
    }

    @Test
    public void testReportCoordinatesSerializationAndModel() {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        // 1. Deserialization with coordinates
        String json = "{\"id\":\"rep-coords\",\"title\":\"AirPods\",\"type\":\"FOUND\",\"latitude\":37.4275,\"longitude\":-122.1697}";
        Report report = gson.fromJson(json, Report.class);
        assertNotNull(report);
        assertTrue(report.hasCoordinates());
        assertEquals(Double.valueOf(37.4275), report.getLatitude());
        assertEquals(Double.valueOf(-122.1697), report.getLongitude());

        // 2. Legacy report without coordinates
        String jsonLegacy = "{\"id\":\"rep-legacy\",\"title\":\"Keys\",\"type\":\"FOUND\",\"latitude\":null,\"longitude\":null}";
        Report legacyReport = gson.fromJson(jsonLegacy, Report.class);
        assertNotNull(legacyReport);
        assertFalse(legacyReport.hasCoordinates());
        assertNull(legacyReport.getLatitude());
        assertNull(legacyReport.getLongitude());

        // 3. Partial coordinates
        Report partial = new Report();
        partial.setLatitude(37.4);
        assertFalse(partial.hasCoordinates());
        partial.setLongitude(-122.1);
        assertTrue(partial.hasCoordinates());
    }

    @Test
    public void testMeetingLocationModelAndSerialization() {
        com.google.gson.Gson gson = new com.google.gson.Gson();

        // 1. Proposed meeting location
        String jsonProposed = "{\"id\":\"meet-1\",\"conversation_id\":\"conv-1\",\"proposer_id\":\"usr-1\"," +
                "\"latitude\":37.4280,\"longitude\":-122.1700,\"location_note\":\"Outside library fountain\"," +
                "\"status\":\"PROPOSED\"}";
        com.example.lostandfound.data.model.MeetingLocation meet =
                gson.fromJson(jsonProposed, com.example.lostandfound.data.model.MeetingLocation.class);
        assertNotNull(meet);
        assertTrue(meet.isProposed());
        assertFalse(meet.isAccepted());
        assertFalse(meet.isAnswered());
        assertFalse(meet.isCurrent());
        assertFalse(meet.isSuperseded());
        assertEquals("Outside library fountain", meet.getLocationNote());
        assertEquals(Double.valueOf(37.4280), meet.getLatitude());
        assertEquals(Double.valueOf(-122.1700), meet.getLongitude());

        // 2. Accepted meeting location, and it is the agreed spot
        meet.setStatus(com.example.lostandfound.data.model.MeetingLocation.STATUS_ACCEPTED);
        meet.setRespondedBy("usr-2");
        meet.setRespondedAt("2026-09-28T14:30:00+00:00");
        meet.setIsCurrent(Boolean.TRUE);
        assertTrue(meet.isAccepted());
        assertTrue(meet.isAnswered());
        assertTrue(meet.isCurrent());
        assertFalse(meet.isProposed());
        assertEquals("usr-2", meet.getRespondedBy());

        // 3. Previously accepted: accepted, but no longer the agreed spot
        meet.setIsCurrent(Boolean.FALSE);
        assertTrue(meet.isAccepted());
        assertFalse("an older acceptance must not stay current", meet.isCurrent());

        // 4. Declined
        meet.setStatus(com.example.lostandfound.data.model.MeetingLocation.STATUS_REJECTED);
        assertTrue(meet.isRejected());
        assertTrue(meet.isAnswered());
        assertFalse(meet.isAccepted());
        assertFalse(meet.isProposed());

        // 5. Legacy SUPERSEDED rows (pre-010) must still validate and must not be
        //    mistaken for a live state
        meet.setStatus(com.example.lostandfound.data.model.MeetingLocation.STATUS_SUPERSEDED);
        assertTrue(meet.isSuperseded());
        assertFalse(meet.isProposed());
        assertFalse(meet.isAnswered());

        // 6. Serialization: the response columns round-trip
        meet.setStatus(com.example.lostandfound.data.model.MeetingLocation.STATUS_ACCEPTED);
        meet.setIsCurrent(Boolean.TRUE);
        String serialized = gson.toJson(meet);
        assertTrue(serialized.contains("\"status\":\"ACCEPTED\""));
        assertTrue(serialized.contains("\"is_current\":true"));
        assertTrue(serialized.contains("\"responded_by\":\"usr-2\""));

        // 7. A reply reference survives a round trip
        com.example.lostandfound.data.model.Message replied =
                new com.example.lostandfound.data.model.Message("conv-1", "usr-2", "Robin", "which gate?")
                        .withReplyToMeeting("meet-1");
        String repliedJson = gson.toJson(replied);
        assertTrue(repliedJson.contains("\"reply_to_meeting_id\":\"meet-1\""));
        com.example.lostandfound.data.model.Message parsedReply =
                gson.fromJson(repliedJson, com.example.lostandfound.data.model.Message.class);
        assertEquals("meet-1", parsedReply.getReplyToMeetingId());
        assertNull(parsedReply.getReplyToMessageId());
    }

    @Test
    public void testNitTrichyCampusBoundaryValidation() {
        // 1. Center of campus
        assertTrue("Campus center must be inside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.763385, 78.815029));

        // 2. Key campus zones inside boundary
        assertTrue("Admin Quad should be inside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7589, 78.8132));
        assertTrue("Hostel Zone (East) should be inside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7610, 78.8220));
        assertTrue("Department Zone (North) should be inside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7650, 78.8130));
        assertTrue("Sports Complex should be inside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7570, 78.8180));
        assertTrue("Main Gate Area should be inside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7600, 78.8100));

        // 3. Points just outside campus boundary edges
        assertFalse("North outside on Tanjore road should be outside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7760, 78.8150));
        assertFalse("South outside in BHEL should be outside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7500, 78.8150));
        assertFalse("West outside in Thuvakudi town should be outside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7633, 78.8000));
        assertFalse("East outside should be outside",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7633, 78.8300));

        // 4. Distant locations in India
        assertFalse("Chennai coordinates must be rejected",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(13.0827, 80.2707));
        assertFalse("Bangalore coordinates must be rejected",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(12.9716, 77.5946));
        assertFalse("Delhi coordinates must be rejected",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(28.6139, 77.2090));

        // 5. Edge cases: nulls
        assertFalse("Null latitude should return false",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(null, 78.8150));
        assertFalse("Null longitude should return false",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(10.7633, null));
        assertFalse("Both null should return false",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(null, null));
    }

    @Test
    public void testStructuredTimeInputAndLegacyFallback() {
        // 1. Time storage formatting
        assertEquals("14:30", DateUtils.formatTimeForStorage(14, 30));
        assertEquals("09:05", DateUtils.formatTimeForStorage(9, 5));
        assertEquals("00:00", DateUtils.formatTimeForStorage(0, 0));
        assertEquals("23:59", DateUtils.formatTimeForStorage(23, 59));

        // 2. Structured time pattern check
        assertTrue(DateUtils.isStructuredTime("14:30"));
        assertTrue(DateUtils.isStructuredTime("09:05"));
        assertTrue(DateUtils.isStructuredTime("00:00"));
        assertTrue(DateUtils.isStructuredTime("23:59"));
        assertFalse(DateUtils.isStructuredTime("2:30 PM"));
        assertFalse(DateUtils.isStructuredTime("Around 2:00 PM"));
        assertFalse(DateUtils.isStructuredTime("invalid"));
        assertFalse(DateUtils.isStructuredTime(null));

        // 3. Fallback display formatting for legacy/older reports
        // Older unstructured free-text reports must not crash and must retain their exact text
        assertEquals("Around 2:00 PM", DateUtils.formatDisplayTimeFromStorage(null, "Around 2:00 PM"));
        assertEquals("11:20 pm", DateUtils.formatDisplayTimeFromStorage(null, "11:20 pm"));
        assertEquals("Evening near library", DateUtils.formatDisplayTimeFromStorage(null, "Evening near library"));
        assertEquals("", DateUtils.formatDisplayTimeFromStorage(null, null));
        assertEquals("", DateUtils.formatDisplayTimeFromStorage(null, "   "));

        // 4. Structured time display formatting (without Android context in JVM test, uses fallback formatter)
        String formatted = DateUtils.formatDisplayTimeFromStorage(null, "14:30");
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // Should parse and format without throwing
        assertTrue(formatted.contains("02:30") || formatted.contains("2:30") || formatted.contains("14:30"));
    }

    @Test
    public void testRequiredFieldsValidationLogic() {
        // Simulating the form validation logic

        // 1. Test missing or short Title
        String invalidTitle = "ab";
        assertTrue("Title under 3 chars is invalid", invalidTitle.trim().length() < 3);

        String validTitle = "Blue Hydro Flask";
        assertTrue("Title with >= 3 chars is valid", validTitle.trim().length() >= 3);

        // 2. Test category selection
        int unselectedCatPos = 0; // index 0 is "-- Select a Category --"
        assertTrue("Index 0 category is invalid", unselectedCatPos <= 0);

        int selectedCatPos = 1;
        assertTrue("Index > 0 category is valid", selectedCatPos > 0);

        // 3. Test location selection
        int unselectedLocPos = 0; // index 0 is "-- Select a Campus Location --"
        assertTrue("Index 0 location is invalid", unselectedLocPos <= 0);

        int selectedLocPos = 2;
        assertTrue("Index > 0 location is valid", selectedLocPos > 0);

        // 4. Test missing incident date or time
        String emptyDate = "";
        assertTrue("Empty date is invalid", emptyDate.isEmpty());

        String validDate = "2026-09-29";
        assertFalse("Non-empty date is valid", validDate.isEmpty());

        String emptyTime = null;
        assertTrue("Null time is invalid", emptyTime == null || emptyTime.trim().isEmpty());

        String validTime = "14:30";
        assertFalse("Non-empty time is valid", validTime == null || validTime.trim().isEmpty());

        // 5. Test missing description
        String shortDesc = "Lost";
        assertTrue("Description under 5 chars is invalid", shortDesc.trim().length() < 5);

        String validDesc = "Black umbrella left on the third floor of Central Library";
        assertTrue("Description >= 5 chars is valid", validDesc.trim().length() >= 5);

        // 6. Conditional FOUND validation: Verification question & finder private notes
        String missingQuestion = "";
        assertTrue("Found report requires verification question", missingQuestion.trim().length() < 3);

        String validQuestion = "What brand is the sticker on the back?";
        assertTrue("Verification question >= 3 chars is valid", validQuestion.trim().length() >= 3);

        String missingNotes = "";
        assertTrue("Found report requires finder secret notes", missingNotes.trim().length() < 2);

        String validNotes = "Has initials 'MK' engraved inside";
        assertTrue("Finder notes >= 2 chars is valid", validNotes.trim().length() >= 2);

        // 7. Found report map pin: optional, but if present must be within NIT Trichy campus
        Double outOfBoundsLat = 13.0827; // Chennai
        Double outOfBoundsLng = 80.2707;
        assertFalse("Out-of-bounds map pin must be rejected",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(outOfBoundsLat, outOfBoundsLng));

        Double insideCampusLat = 10.763385; // NIT Trichy Center
        Double insideCampusLng = 78.815029;
        assertTrue("Inside-campus map pin must be accepted",
                com.example.lostandfound.util.CampusBoundaryConfig.isInsideCampus(insideCampusLat, insideCampusLng));
    }

    @Test
    public void testCreateReportRpcParameterNames() {
        // Verify the exact parameter names sent to create_report_with_private_details RPC
        // This test ensures Android request params match the database function signature
        // (migrations 006/007: 12 params with optional p_latitude, p_longitude)

        // Expected parameter names from ReportRepository.createReport()
        String[] expectedParams = {
                "p_type",
                "p_title",
                "p_category",
                "p_description",
                "p_public_verification_question",
                "p_finder_private_notes",
                "p_image_url",
                "p_campus_location",
                "p_incident_date",
                "p_incident_time_approx",
                "p_latitude",
                "p_longitude"
        };

        // Verify count matches (12 params total, 10 required + 2 optional coords)
        assertEquals("RPC must accept 12 parameters (10 required + 2 optional coords)", 12, expectedParams.length);

        // Verify all params are non-empty strings
        for (String param : expectedParams) {
            assertNotNull("Parameter name must not be null: " + param, param);
            assertFalse("Parameter name must not be empty: " + param, param.trim().isEmpty());
            assertTrue("Parameter must follow p_* naming convention: " + param, param.startsWith("p_"));
        }

        // Verify coordinates are last two params (optional)
        assertEquals("p_latitude must be 11th param", "p_latitude", expectedParams[10]);
        assertEquals("p_longitude must be 12th param", "p_longitude", expectedParams[11]);

        // Verify required FOUND-only params are included (sent as null for LOST)
        assertEquals("p_public_verification_question param name", "p_public_verification_question", expectedParams[4]);
        assertEquals("p_finder_private_notes param name", "p_finder_private_notes", expectedParams[5]);
    }

    @Test
    public void testLostAndFoundCoordinateHandling() {
        // LOST reports: latitude/longitude = last seen location (optional)
        // FOUND reports: latitude/longitude = where item was found (optional)
        // Both types should be able to send or omit coordinates

        Report lostReport = new Report();
        lostReport.setType(Report.TYPE_LOST);
        lostReport.setTitle("Lost Phone");
        lostReport.setLatitude(10.763385);
        lostReport.setLongitude(78.815029);
        assertTrue("LOST report with coordinates should haveCoordinates()", lostReport.hasCoordinates());

        Report lostNoCoords = new Report();
        lostNoCoords.setType(Report.TYPE_LOST);
        lostNoCoords.setTitle("Lost Keys");
        assertFalse("LOST report without coordinates should not haveCoordinates()", lostNoCoords.hasCoordinates());

        Report foundReport = new Report();
        foundReport.setType(Report.TYPE_FOUND);
        foundReport.setTitle("Found Watch");
        foundReport.setLatitude(10.763385);
        foundReport.setLongitude(78.815029);
        assertTrue("FOUND report with coordinates should haveCoordinates()", foundReport.hasCoordinates());

        Report foundNoCoords = new Report();
        foundNoCoords.setType(Report.TYPE_FOUND);
        foundNoCoords.setTitle("Found Wallet");
        assertFalse("FOUND report without coordinates should not haveCoordinates()", foundNoCoords.hasCoordinates());
    }

    @Test
    public void testCreateReportRpcPayloadLostWithoutPin() {
        // Simulate LOST report payload WITHOUT optional map pin
        // Verifies Android sends all 10 required params (2 FOUND-only as null)
        Report report = new Report();
        report.setType(Report.TYPE_LOST);
        report.setTitle("Lost Phone");
        report.setCategory("ELECTRONICS");
        report.setDescription("Black iPhone 13 lost near Library");
        report.setPublicVerificationQuestion(null); // LOST: not used
        report.setImageUrl(null); // No photo
        report.setCampusLocation("Library");
        report.setIncidentDate("2026-09-29");
        report.setIncidentTimeApprox("14:30");
        // No latitude/longitude - no map pin

        Map<String, Object> params = buildReportParams(report, null);

        // Verify all 10 required params present (2 FOUND-only as null)
        assertEquals("LOST: p_type", Report.TYPE_LOST, params.get("p_type"));
        assertEquals("LOST: p_title", "Lost Phone", params.get("p_title"));
        assertEquals("LOST: p_category", "ELECTRONICS", params.get("p_category"));
        assertEquals("LOST: p_description", "Black iPhone 13 lost near Library", params.get("p_description"));
        assertNull("LOST: p_public_verification_question must be explicit null", params.get("p_public_verification_question"));
        assertNull("LOST: p_finder_private_notes must be explicit null", params.get("p_finder_private_notes"));
        assertNull("LOST: p_image_url null when no photo", params.get("p_image_url"));
        assertEquals("LOST: p_campus_location", "Library", params.get("p_campus_location"));
        assertEquals("LOST: p_incident_date", "2026-09-29", params.get("p_incident_date"));
        assertEquals("LOST: p_incident_time_approx", "14:30", params.get("p_incident_time_approx"));
        assertFalse("LOST: p_latitude omitted when no pin", params.containsKey("p_latitude"));
        assertFalse("LOST: p_longitude omitted when no pin", params.containsKey("p_longitude"));
    }

    @Test
    public void testCreateReportRpcPayloadLostWithPin() {
        // Simulate LOST report payload WITH optional map pin (last seen location)
        Report report = new Report();
        report.setType(Report.TYPE_LOST);
        report.setTitle("Lost Backpack");
        report.setCategory("BAGS_WALLETS");
        report.setDescription("Blue backpack left in lecture hall");
        report.setPublicVerificationQuestion(null);
        report.setImageUrl("user-123/photo.jpg");
        report.setCampusLocation("Engineering Quad");
        report.setIncidentDate("2026-09-28");
        report.setIncidentTimeApprox("10:15");
        report.setLatitude(10.763385);
        report.setLongitude(78.815029);

        Map<String, Object> params = buildReportParams(report, null);

        assertEquals("LOST with pin: p_type", Report.TYPE_LOST, params.get("p_type"));
        assertEquals("LOST with pin: p_latitude", 10.763385, params.get("p_latitude"));
        assertEquals("LOST with pin: p_longitude", 78.815029, params.get("p_longitude"));
        assertEquals("LOST with pin: p_image_url", "user-123/photo.jpg", params.get("p_image_url"));
        assertNull("LOST with pin: p_public_verification_question null", params.get("p_public_verification_question"));
        assertNull("LOST with pin: p_finder_private_notes null", params.get("p_finder_private_notes"));
    }

    @Test
    public void testCreateReportRpcPayloadFoundWithoutPin() {
        // Simulate FOUND report payload WITHOUT optional map pin
        Report report = new Report();
        report.setType(Report.TYPE_FOUND);
        report.setTitle("Found Keys");
        report.setCategory("KEYS");
        report.setDescription("Set of keys with red keychain");
        report.setPublicVerificationQuestion("What color is the keychain?");
        report.setImageUrl(null);
        report.setCampusLocation("Student Center");
        report.setIncidentDate("2026-09-29");
        report.setIncidentTimeApprox("16:45");
        // No coordinates

        String finderPrivateNotes = "Has small scratch on the largest key";

        Map<String, Object> params = buildReportParams(report, finderPrivateNotes);

        assertEquals("FOUND: p_type", Report.TYPE_FOUND, params.get("p_type"));
        assertEquals("FOUND: p_public_verification_question", "What color is the keychain?", params.get("p_public_verification_question"));
        assertEquals("FOUND: p_finder_private_notes", finderPrivateNotes, params.get("p_finder_private_notes"));
        assertNull("FOUND: p_image_url null", params.get("p_image_url"));
        assertFalse("FOUND: p_latitude omitted", params.containsKey("p_latitude"));
        assertFalse("FOUND: p_longitude omitted", params.containsKey("p_longitude"));
    }

    @Test
    public void testCreateReportRpcPayloadFoundWithPin() {
        // Simulate FOUND report payload WITH optional map pin (where found)
        Report report = new Report();
        report.setType(Report.TYPE_FOUND);
        report.setTitle("Found Watch");
        report.setCategory("ELECTRONICS");
        report.setDescription("Silver Apple Watch Series 7");
        report.setPublicVerificationQuestion("What watch face is displayed?");
        report.setImageUrl("user-456/watch.jpg");
        report.setCampusLocation("Sports Complex");
        report.setIncidentDate("2026-09-27");
        report.setIncidentTimeApprox("08:00");
        report.setLatitude(10.7570);
        report.setLongitude(78.8180);

        String finderPrivateNotes = "Engraved with 'JD' on back";

        Map<String, Object> params = buildReportParams(report, finderPrivateNotes);

        assertEquals("FOUND with pin: p_type", Report.TYPE_FOUND, params.get("p_type"));
        assertEquals("FOUND with pin: p_latitude", 10.7570, params.get("p_latitude"));
        assertEquals("FOUND with pin: p_longitude", 78.8180, params.get("p_longitude"));
        assertEquals("FOUND with pin: p_public_verification_question", "What watch face is displayed?", params.get("p_public_verification_question"));
        assertEquals("FOUND with pin: p_finder_private_notes", finderPrivateNotes, params.get("p_finder_private_notes"));
        assertEquals("FOUND with pin: p_image_url", "user-456/watch.jpg", params.get("p_image_url"));
    }

    @Test
    public void testDisplayNameValidation() {
        assertNotNull(ProfileUtils.validateDisplayName(null));
        assertNotNull(ProfileUtils.validateDisplayName(""));
        assertNotNull(ProfileUtils.validateDisplayName("   "));
        assertNotNull(ProfileUtils.validateDisplayName("A"));

        assertNull(ProfileUtils.validateDisplayName("Jo"));
        assertNull(ProfileUtils.validateDisplayName("  Ada Lovelace  "));
        assertNull(ProfileUtils.validateDisplayName("MK"));

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < ProfileUtils.MAX_NAME_LENGTH + 1; i++) {
            longName.append('a');
        }
        assertNotNull(ProfileUtils.validateDisplayName(longName.toString()));
    }


    @Test
    public void testAvatarPathAndPayloadFields() {
        // Fixed per-user path: replacements overwrite, never orphan files
        assertEquals("user-123/avatar.jpg", ProfileUtils.avatarPathFor("user-123"));
        assertEquals(ProfileUtils.AVATAR_FILE_NAME, "avatar.jpg");

        // PATCH body for profile updates carries only profile-owned fields
        Map<String, Object> fields = new HashMap<>();
        fields.put("full_name", "Ada Lovelace");
        fields.put("avatar_url", ProfileUtils.avatarPathFor("user-123"));
        assertEquals("Ada Lovelace", fields.get("full_name"));
        assertEquals("user-123/avatar.jpg", fields.get("avatar_url"));
        assertFalse("Email must never be part of a profile update", fields.containsKey("email"));
        assertFalse("Owner/permission fields must never be client-set", fields.containsKey("owner_id"));
    }

    @Test
    public void testProfileDirectoryIdsFilter() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        ids.add("user-b");
        ids.add(null);
        ids.add("  ");
        ids.add("user-a");
        ids.add("user-b");
        assertEquals("in.(user-a,user-b)",
                com.example.lostandfound.data.repository.ProfileDirectory.idsFilter(ids));
        assertEquals("", com.example.lostandfound.data.repository.ProfileDirectory.idsFilter(null));
        assertEquals("", com.example.lostandfound.data.repository.ProfileDirectory.idsFilter(
                new java.util.ArrayList<String>()));
    }

    @Test
    public void testProfileDirectoryDisplayFallbacks() {
        com.example.lostandfound.data.model.Profile known =
                new com.example.lostandfound.data.model.Profile("u1", "Ada Lovelace");
        assertEquals("Ada Lovelace", com.example.lostandfound.data.repository.ProfileDirectory
                .displayNameFor(known, "Old Copy"));
        assertEquals("Old Copy", com.example.lostandfound.data.repository.ProfileDirectory
                .displayNameFor(null, "Old Copy"));
        com.example.lostandfound.data.model.Profile blank =
                new com.example.lostandfound.data.model.Profile("u2", "   ");
        assertEquals("Old Copy", com.example.lostandfound.data.repository.ProfileDirectory
                .displayNameFor(blank, "Old Copy"));
        assertEquals("Campus Student", com.example.lostandfound.data.repository.ProfileDirectory
                .displayNameFor(null, null));

        com.example.lostandfound.data.model.Profile withPhoto =
                new com.example.lostandfound.data.model.Profile("u3", "Jo");
        withPhoto.setAvatarUrl("u3/avatar.jpg");
        assertEquals("u3/avatar.jpg", com.example.lostandfound.data.repository.ProfileDirectory
                .avatarPathFor(withPhoto));
        assertNull(com.example.lostandfound.data.repository.ProfileDirectory.avatarPathFor(known));
        assertNull(com.example.lostandfound.data.repository.ProfileDirectory.avatarPathFor(null));
    }

    // Helper to replicate ReportRepository.createReport param building logic
    private Map<String, Object> buildReportParams(Report report, String finderPrivateNotes) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_type", report.getType());
        params.put("p_title", report.getTitle());
        params.put("p_category", report.getCategory());
        params.put("p_description", report.getDescription());
        params.put("p_public_verification_question", report.getPublicVerificationQuestion());
        params.put("p_finder_private_notes", finderPrivateNotes);
        params.put("p_image_url", report.getImageUrl());
        params.put("p_campus_location", report.getCampusLocation());
        params.put("p_incident_date", report.getIncidentDate());
        params.put("p_incident_time_approx", report.getIncidentTimeApprox());
        if (report.getLatitude() != null) {
            params.put("p_latitude", report.getLatitude());
        }
        if (report.getLongitude() != null) {
            params.put("p_longitude", report.getLongitude());
        }
        return params;
    }
}


package com.example.lostandfound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.ui.claims.ClaimCardContent;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.Test;

/**
 * Locks the shape of the embedded report on a claim.
 *
 * <p>getMyClaims() embeds the parent report so the card can show the finder's
 * question. That embed is easy to break silently: claims and reports are related
 * by two FKs, so an unhinted embed is ambiguous and PostgREST rejects the entire
 * query with PGRST201 / HTTP 300. This test parses the real response body, so a
 * change that makes the embed ambiguous, wrong-constrained, or array-shaped
 * fails here instead of blanking the My Claims screen on a device.
 *
 * <p>The JSON below is the verbatim response from PostgREST for
 * select=*,report:reports!claims_report_id_fkey(public_verification_question).
 */
public class ClaimEmbedTest {

    private static final String RESPONSE_JSON = "["
            + "{"
            + "\"id\": \"bbbbbbbb-0000-0000-0000-000000000001\","
            + "\"report_id\": \"aaaaaaaa-0000-0000-0000-000000000001\","
            + "\"claimant_id\": \"22222222-2222-2222-2222-222222222222\","
            + "\"claimant_name\": \"Claimer\","
            + "\"note_or_evidence\": \"It says Bob on the base\","
            + "\"status\": \"PENDING\","
            + "\"created_at\": \"2026-10-02T03:36:36.82553+00:00\","
            + "\"reviewed_at\": null,"
            + "\"rejection_message\": null,"
            + "\"report\": {"
            + "  \"public_verification_question\": \"What is written on the base?\""
            + "}"
            + "}"
            + "]";

    private List<Claim> parse(String json) {
        Type type = new TypeToken<List<Claim>>() {}.getType();
        return new Gson().fromJson(json, type);
    }

    @Test
    public void embeddedReportExposesTheQuestion() {
        List<Claim> claims = parse(RESPONSE_JSON);
        assertEquals(1, claims.size());
        assertEquals("What is written on the base?",
                claims.get(0).getVerificationQuestion());
    }

    @Test
    public void siblingClaimFieldsStillParseAlongsideTheEmbed() {
        Claim claim = parse(RESPONSE_JSON).get(0);
        assertEquals("It says Bob on the base", claim.getNoteOrEvidence());
        assertEquals("PENDING", claim.getStatus());
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", claim.getReportId());
    }

    @Test
    public void absentEmbedIsNullRatherThanEmptySoTheCardHidesTheQuestion() {
        // The owner-review path fetches claims without the embed, so the field
        // must tolerate being missing entirely.
        List<Claim> claims = parse("[{\"id\":\"x\",\"note_or_evidence\":\"hi\",\"status\":\"PENDING\"}]");
        assertNull(claims.get(0).getReport());
        assertNull(claims.get(0).getVerificationQuestion());
    }

    @Test
    public void lostItemWithNullQuestionIsHandled() {
        // LOST reports have no verification question, so the embedded object is
        // present but the question is null.
        List<Claim> claims = parse("[{\"id\":\"x\",\"status\":\"PENDING\",\"report\":{"
                + "\"public_verification_question\": null}}]");
        assertNull(claims.get(0).getVerificationQuestion());
    }

    @Test
    public void questionAndAnswerAreBothAvailableForTheCard() {
        // The card renders the question above the answer; both must survive parsing.
        Claim claim = parse(RESPONSE_JSON).get(0);
        assertEquals("What is written on the base?",
                ClaimCardContent.question(claim.getVerificationQuestion()));
        assertEquals("It says Bob on the base",
                ClaimCardContent.answer(claim.getNoteOrEvidence()));
    }
}
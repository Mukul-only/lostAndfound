package com.example.lostandfound.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.repository.ProfileRepository.AuthGate;
import com.example.lostandfound.data.repository.ProfileRepository.IdentityVerdict;
import com.example.lostandfound.data.repository.ProfileRepository.PatchOutcome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Pins the save-flow decision table. Every rule here exists because the live
 * server answers "nothing visible" with HTTP 200, so the repository must
 * decide from shape, not status: a missing session must never reach the
 * network as an anonymous request, a 2xx PATCH without a representation row
 * must never be reported as saved, and a repair must only run for a gated
 * session whose stored id the backend confirms.
 */
public class ProfileSaveDecisionTest {

    private static Profile row(String name, String avatar) {
        Profile profile = new Profile("u1", name);
        profile.setAvatarUrl(avatar);
        return profile;
    }

    private static Response<List<Profile>> error(int code) {
        return Response.error(code,
                ResponseBody.create("{}", MediaType.get("application/json")));
    }

    // Missing session: the gate stops anonymous profile requests ----------

    @Test
    public void nullUserId_isSignInRequired() {
        assertEquals(AuthGate.SIGN_IN_REQUIRED,
                ProfileRepository.gateSession(null, "token"));
    }

    @Test
    public void blankUserId_isSignInRequired() {
        assertEquals(AuthGate.SIGN_IN_REQUIRED,
                ProfileRepository.gateSession("   ", "token"));
    }

    @Test
    public void missingToken_isSignInRequired() {
        assertEquals(AuthGate.SIGN_IN_REQUIRED,
                ProfileRepository.gateSession("u1", null));
        assertEquals(AuthGate.SIGN_IN_REQUIRED,
                ProfileRepository.gateSession("u1", "  "));
    }

    @Test
    public void presentIdAndToken_mayProceed() {
        assertEquals(AuthGate.PROCEED,
                ProfileRepository.gateSession("u1", "token"));
    }

    // Refresh failure: a 401 survived transparent refresh, so the session is
    // dead; anything else keeps its own status code -------------------------

    @Test
    public void final401_isExplicitSessionExpiry() {
        assertEquals("Session expired. Please sign in again.",
                ProfileRepository.expiredOrCoded("Failed to save profile. (HTTP 401)", 401));
    }

    @Test
    public void non401_keepsItsOwnStatusCode() {
        assertEquals("Failed to save profile. (HTTP 403)",
                ProfileRepository.expiredOrCoded("Failed to save profile. (HTTP 403)", 403));
        assertEquals("Could not load profile. Code: 500",
                ProfileRepository.expiredOrCoded("Could not load profile. Code: 500", 500));
    }

    // Stale stored id: only two equal non-blank ids match ------------------

    @Test
    public void equalIds_match() {
        assertEquals(IdentityVerdict.MATCH,
                ProfileRepository.checkIdentity("u1", "u1"));
    }

    @Test
    public void differentIds_mismatch() {
        assertEquals(IdentityVerdict.MISMATCH,
                ProfileRepository.checkIdentity("u1", "u2"));
    }

    @Test
    public void nullEitherSide_mismatch() {
        assertEquals(IdentityVerdict.MISMATCH,
                ProfileRepository.checkIdentity(null, "u2"));
        assertEquals(IdentityVerdict.MISMATCH,
                ProfileRepository.checkIdentity("u1", null));
        assertEquals(IdentityVerdict.MISMATCH,
                ProfileRepository.checkIdentity(null, null));
    }

    // Missing profile: repair only for a confirmed identity ---------------

    @Test
    public void repair_onlyForGatedMatchingSession() {
        assertTrue(ProfileRepository.shouldAttemptRepair(
                AuthGate.PROCEED, IdentityVerdict.MATCH));
        assertFalse(ProfileRepository.shouldAttemptRepair(
                AuthGate.SIGN_IN_REQUIRED, IdentityVerdict.MATCH));
        assertFalse(ProfileRepository.shouldAttemptRepair(
                AuthGate.PROCEED, IdentityVerdict.MISMATCH));
        assertFalse(ProfileRepository.shouldAttemptRepair(
                AuthGate.SIGN_IN_REQUIRED, IdentityVerdict.MISMATCH));
    }

    // Zero-row PATCH: 2xx without a row is indeterminate, never success ----

    @Test
    public void patchWithRepresentation_isConfirmed() {
        Response<List<Profile>> response =
                Response.success(Collections.singletonList(row("Ada", "a.jpg")));
        assertEquals(PatchOutcome.CONFIRMED,
                ProfileRepository.classifyPatchOutcome(response));
    }

    @Test
    public void patchWithoutRepresentation_needsRead() {
        assertEquals(PatchOutcome.NEEDS_READ, ProfileRepository.classifyPatchOutcome(
                Response.success(new ArrayList<Profile>())));
        assertEquals(PatchOutcome.NEEDS_READ, ProfileRepository.classifyPatchOutcome(
                Response.success((List<Profile>) null)));
    }

    @Test
    public void failedPatch_isFailed() {
        assertEquals(PatchOutcome.FAILED,
                ProfileRepository.classifyPatchOutcome(error(400)));
        assertEquals(PatchOutcome.FAILED,
                ProfileRepository.classifyPatchOutcome(error(401)));
    }

    // Follow-up read: the row must carry the edit --------------------------

    @Test
    public void rowWithEditValues_reflectsEdit() {
        assertTrue(ProfileRepository.rowReflectsEdit(row("Ada", "a.jpg"), "Ada", "a.jpg"));
    }

    @Test
    public void rowWithOldValues_doesNotReflectEdit() {
        assertFalse(ProfileRepository.rowReflectsEdit(row("Ada", "old.jpg"), "Ada", "new.jpg"));
        assertFalse(ProfileRepository.rowReflectsEdit(row("Old", "a.jpg"), "Ada", "a.jpg"));
    }

    @Test
    public void unsentAvatar_isNotCompared() {
        assertTrue(ProfileRepository.rowReflectsEdit(row("Ada", "whatever.jpg"), "Ada", null));
    }

    @Test
    public void nullRow_neverReflectsEdit() {
        assertFalse(ProfileRepository.rowReflectsEdit(null, "Ada", "a.jpg"));
    }

    @Test
    public void emptyStringAvatar_mustMatchExactly() {
        // removeAvatar writes avatar_url="": a null coming back is not the edit.
        assertTrue(ProfileRepository.rowReflectsEdit(row("Ada", ""), null, ""));
        assertFalse(ProfileRepository.rowReflectsEdit(row("Ada", null), null, ""));
    }

    // Prescribed wording stays exact ---------------------------------------

    @Test
    public void reloadFailureMessage_isVerbatim() {
        assertEquals("Profile saved, but could not reload it",
                ProfileRepository.MSG_SAVED_NOT_RELOADED);
    }
}
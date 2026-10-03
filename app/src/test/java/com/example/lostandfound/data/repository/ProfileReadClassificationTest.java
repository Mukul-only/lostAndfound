package com.example.lostandfound.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.repository.ProfileRepository.ProfileReadOutcome;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * A 200 is not a loaded profile. PostgREST answers a SELECT that matches
 * nothing visible with {@code 200} and an empty array, so these tests pin the
 * three-way distinction the repository relies on: an actual row, HTTP success
 * with no row, and HTTP failure. They run against canned Retrofit responses,
 * no network involved.
 */
public class ProfileReadClassificationTest {

    private static Response<List<Profile>> error(int code) {
        return Response.error(code,
                ResponseBody.create("{}", MediaType.get("application/json")));
    }

    @Test
    public void populatedArray_isLoaded() {
        Response<List<Profile>> response =
                Response.success(Collections.singletonList(new Profile("u1", "Ada")));
        assertEquals(ProfileReadOutcome.LOADED,
                ProfileRepository.classifyProfileRead(response));
    }

    @Test
    public void emptyArray_isNoVisibleProfile() {
        Response<List<Profile>> response = Response.success(new ArrayList<Profile>());
        assertEquals(ProfileReadOutcome.NO_VISIBLE_PROFILE,
                ProfileRepository.classifyProfileRead(response));
    }

    @Test
    public void absentBody_isNoVisibleProfile() {
        Response<List<Profile>> response = Response.success((List<Profile>) null);
        assertEquals(ProfileReadOutcome.NO_VISIBLE_PROFILE,
                ProfileRepository.classifyProfileRead(response));
    }

    @Test
    public void nullRow_isNoVisibleProfile() {
        Response<List<Profile>> response =
                Response.success(Collections.singletonList((Profile) null));
        assertEquals(ProfileReadOutcome.NO_VISIBLE_PROFILE,
                ProfileRepository.classifyProfileRead(response));
    }

    @Test
    public void http404_isHttpError() {
        assertEquals(ProfileReadOutcome.HTTP_ERROR,
                ProfileRepository.classifyProfileRead(error(404)));
    }

    @Test
    public void http401_isHttpError() {
        assertEquals(ProfileReadOutcome.HTTP_ERROR,
                ProfileRepository.classifyProfileRead(error(401)));
    }

    @Test
    public void ioException_isNetworkError() {
        assertTrue(ProfileRepository.readFailureMessage(new IOException("timeout"))
                .startsWith("Network error"));
    }

    @Test
    public void conversionException_isNotNetworkError() {
        // Gson surfaces model mismatches as runtime exceptions, never as
        // IOExceptions, so they must not be reported as network trouble.
        String message = ProfileRepository.readFailureMessage(
                new IllegalStateException("Expected BEGIN_OBJECT"));
        assertTrue(message.startsWith("Unexpected error"));
    }
}
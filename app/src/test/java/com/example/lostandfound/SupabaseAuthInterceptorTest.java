package com.example.lostandfound;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.example.lostandfound.data.remote.ISessionManager;
import com.example.lostandfound.data.remote.ITokenRefresher;
import com.example.lostandfound.data.remote.SupabaseAuthInterceptor;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.data.remote.TokenAuthenticator;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class SupabaseAuthInterceptorTest {

    private TestSessionManager testSession;
    private TestTokenRefresher testRefresher;

    @Before
    public void setUp() {
        testSession = new TestSessionManager();
        testRefresher = new TestTokenRefresher(testSession);
    }

    @Test
    public void testRequestWithNoSession_SendsApikeyOnly_NoAuthorizationHeader() throws IOException {
        testSession.setLoggedIn(false);
        testSession.setAccessToken(null);

        SupabaseAuthInterceptor interceptor = new SupabaseAuthInterceptor(testSession, testRefresher);

        Request initialRequest = new Request.Builder()
                .url("https://example.supabase.co/rest/v1/reports")
                .build();

        CapturedChain chain = new CapturedChain(initialRequest);
        interceptor.intercept(chain);

        Request processed = chain.getCapturedRequest();
        assertNotNull(processed);
        // apikey header must be present
        assertEquals(SupabaseConfig.SUPABASE_PUBLISHABLE_KEY, processed.header("apikey"));
        // Authorization header must NOT be present
        assertNull("Authorization header must be null when no user session exists", processed.header("Authorization"));
    }

    @Test
    public void testRequestWithNoSession_NeverSendsPublishableKeyAsBearer() throws IOException {
        testSession.setLoggedIn(false);
        // Even if an invalid state had publishable key in access token:
        testSession.setAccessToken("sb_publishable_test_dummy_key");

        SupabaseAuthInterceptor interceptor = new SupabaseAuthInterceptor(testSession, testRefresher);

        Request initialRequest = new Request.Builder()
                .url("https://example.supabase.co/rest/v1/reports")
                .build();

        CapturedChain chain = new CapturedChain(initialRequest);
        interceptor.intercept(chain);

        Request processed = chain.getCapturedRequest();
        assertNull("Publishable key must never be sent as a Bearer token", processed.header("Authorization"));
    }

    @Test
    public void testRequestWithValidSession_SendsUserBearerTokenAndApikey() throws IOException {
        testSession.setLoggedIn(true);
        testSession.setTokenExpired(false);
        testSession.setAccessToken("valid_user_jwt_token_123");

        SupabaseAuthInterceptor interceptor = new SupabaseAuthInterceptor(testSession, testRefresher);

        Request initialRequest = new Request.Builder()
                .url("https://example.supabase.co/rest/v1/reports")
                .build();

        CapturedChain chain = new CapturedChain(initialRequest);
        interceptor.intercept(chain);

        Request processed = chain.getCapturedRequest();
        assertEquals(SupabaseConfig.SUPABASE_PUBLISHABLE_KEY, processed.header("apikey"));
        assertEquals("Bearer valid_user_jwt_token_123", processed.header("Authorization"));
    }

    @Test
    public void testRequestWithExpiredSession_ProactivelyRefreshesToken() throws IOException {
        testSession.setLoggedIn(true);
        testSession.setTokenExpired(true);
        testSession.setAccessToken("expired_jwt_old");
        testSession.setRefreshToken("valid_refresh_token");

        testRefresher.setNextRefreshedToken("fresh_refreshed_jwt_456");

        SupabaseAuthInterceptor interceptor = new SupabaseAuthInterceptor(testSession, testRefresher);

        Request initialRequest = new Request.Builder()
                .url("https://example.supabase.co/rest/v1/reports")
                .build();

        CapturedChain chain = new CapturedChain(initialRequest);
        interceptor.intercept(chain);

        Request processed = chain.getCapturedRequest();
        // Token refresher was invoked
        assertEquals(1, testRefresher.getCallCount());
        // Fresh token is attached as Authorization Bearer
        assertEquals("Bearer fresh_refreshed_jwt_456", processed.header("Authorization"));
    }

    @Test
    public void testRequestWithExpiredSession_RefreshFails_OmitsAuthorization() throws IOException {
        testSession.setLoggedIn(true);
        testSession.setTokenExpired(true);
        testSession.setAccessToken("expired_jwt_old");

        // Refresher fails (returns null)
        testRefresher.setNextRefreshedToken(null);
        testRefresher.setOnRefreshAction(testSession::clearSession);

        SupabaseAuthInterceptor interceptor = new SupabaseAuthInterceptor(testSession, testRefresher);

        Request initialRequest = new Request.Builder()
                .url("https://example.supabase.co/rest/v1/reports")
                .build();

        CapturedChain chain = new CapturedChain(initialRequest);
        interceptor.intercept(chain);

        Request processed = chain.getCapturedRequest();
        assertEquals(1, testRefresher.getCallCount());
        assertNull("When refresh fails and session is cleared, Authorization header must be omitted",
                processed.header("Authorization"));
    }

    @Test
    public void testAuthEndpoints_NeverSendBearerTokenEvenIfLoggedIn() throws IOException {
        testSession.setLoggedIn(true);
        testSession.setAccessToken("valid_user_jwt_token_123");

        SupabaseAuthInterceptor interceptor = new SupabaseAuthInterceptor(testSession, testRefresher);

        // Sign-in endpoint
        Request signInRequest = new Request.Builder()
                .url("https://example.supabase.co/auth/v1/token?grant_type=password")
                .build();

        CapturedChain chain1 = new CapturedChain(signInRequest);
        interceptor.intercept(chain1);
        assertNull("Auth token endpoint must not receive Authorization header", chain1.getCapturedRequest().header("Authorization"));

        // Sign-up endpoint
        Request signUpRequest = new Request.Builder()
                .url("https://example.supabase.co/auth/v1/signup")
                .build();

        CapturedChain chain2 = new CapturedChain(signUpRequest);
        interceptor.intercept(chain2);
        assertNull("Signup endpoint must not receive Authorization header", chain2.getCapturedRequest().header("Authorization"));
    }

    @Test
    public void testTokenAuthenticator_RetriesOnceOn401WithNewToken() throws IOException {
        testSession.setLoggedIn(true);
        testSession.setAccessToken("old_expired_token");
        testRefresher.setNextRefreshedToken("brand_new_token_789");

        TokenAuthenticator authenticator = new TokenAuthenticator(testSession, testRefresher);

        Request initialRequest = new Request.Builder()
                .url("https://example.supabase.co/rest/v1/reports")
                .header("Authorization", "Bearer old_expired_token")
                .build();

        Response response401 = new Response.Builder()
                .request(initialRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(ResponseBody.create(null, "{\"message\":\"JWT expired\"}"))
                .build();

        Request retryRequest = authenticator.authenticate(null, response401);
        assertNotNull("Authenticator should retry with new token on initial 401", retryRequest);
        assertEquals("Bearer brand_new_token_789", retryRequest.header("Authorization"));
    }

    @Test
    public void testTokenAuthenticator_PreventsLoopsOnRepeated401s() throws IOException {
        testSession.setLoggedIn(true);
        testSession.setAccessToken("token_attempt_1");
        testRefresher.setNextRefreshedToken("token_attempt_2");

        TokenAuthenticator authenticator = new TokenAuthenticator(testSession, testRefresher);

        Request req1 = new Request.Builder().url("https://example.supabase.co/rest/v1/reports").build();
        Response res1 = new Response.Builder()
                .request(req1)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .build();

        Request req2 = new Request.Builder().url("https://example.supabase.co/rest/v1/reports").build();
        Response res2 = new Response.Builder()
                .request(req2)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .priorResponse(res1) // This is already a retry!
                .build();

        Request retryAttempt = authenticator.authenticate(null, res2);
        assertNull("Authenticator must return null when response count >= 2 to prevent refresh loops", retryAttempt);
    }

    // ====================================================================
    // TEST HELPER DOUBLES
    // ====================================================================

    private static class CapturedChain implements Interceptor.Chain {
        private final Request initialRequest;
        private Request capturedRequest;

        CapturedChain(Request initialRequest) {
            this.initialRequest = initialRequest;
        }

        @Override
        public Request request() {
            return initialRequest;
        }

        @Override
        public Response proceed(Request request) {
            this.capturedRequest = request;
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(null, "[]"))
                    .build();
        }

        Request getCapturedRequest() {
            return capturedRequest;
        }

        @Override public okhttp3.Connection connection() { return null; }
        @Override public okhttp3.Call call() { return null; }
        @Override public int connectTimeoutMillis() { return 10000; }
        @Override public Interceptor.Chain withConnectTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
        @Override public int readTimeoutMillis() { return 10000; }
        @Override public Interceptor.Chain withReadTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
        @Override public int writeTimeoutMillis() { return 10000; }
        @Override public Interceptor.Chain withWriteTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
    }

    private static class TestSessionManager implements ISessionManager {
        private boolean loggedIn = false;
        private boolean tokenExpired = false;
        private String accessToken = null;
        private String refreshToken = null;
        private long expiresAt = 0;

        @Override public boolean isLoggedIn() { return loggedIn; }
        @Override public boolean isTokenExpired() { return tokenExpired; }
        @Override public String getAccessToken() { return accessToken; }
        @Override public String getRefreshToken() { return refreshToken; }
        @Override public String getUserId() { return "test-user-id"; }
        @Override public String getUserEmail() { return "test@campus.edu"; }
        @Override public String getDisplayName() { return "Test Student"; }
        @Override public long getExpiresAt() { return expiresAt; }

        @Override
        public void updateTokens(String accessToken, String refreshToken, Long expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.tokenExpired = false;
        }

        @Override
        public void clearSession() {
            this.loggedIn = false;
            this.accessToken = null;
            this.refreshToken = null;
            this.tokenExpired = false;
        }

        void setLoggedIn(boolean loggedIn) { this.loggedIn = loggedIn; }
        void setTokenExpired(boolean tokenExpired) { this.tokenExpired = tokenExpired; }
        void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }

    private static class TestTokenRefresher implements ITokenRefresher {
        private final ISessionManager sessionManager;
        private String nextRefreshedToken = "fresh_token";
        private final AtomicInteger callCount = new AtomicInteger(0);
        private Runnable onRefreshAction = null;

        TestTokenRefresher(ISessionManager sessionManager) {
            this.sessionManager = sessionManager;
        }

        @Override
        public String refreshTokenSync() {
            callCount.incrementAndGet();
            if (onRefreshAction != null) {
                onRefreshAction.run();
            } else if (nextRefreshedToken != null && sessionManager != null) {
                sessionManager.updateTokens(nextRefreshedToken, "refresh_token_abc", 3600L);
            }
            return nextRefreshedToken;
        }

        void setNextRefreshedToken(String token) { this.nextRefreshedToken = token; }
        void setOnRefreshAction(Runnable action) { this.onRefreshAction = action; }
        int getCallCount() { return callCount.get(); }
    }
}

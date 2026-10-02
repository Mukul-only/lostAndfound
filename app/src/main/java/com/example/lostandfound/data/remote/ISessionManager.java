package com.example.lostandfound.data.remote;

public interface ISessionManager {
    boolean isLoggedIn();
    boolean isTokenExpired();
    String getAccessToken();
    String getRefreshToken();
    String getUserId();
    String getUserEmail();
    String getDisplayName();
    long getExpiresAt();
    void updateTokens(String accessToken, String refreshToken, Long expiresIn);
    void clearSession();
}

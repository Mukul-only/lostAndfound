package com.example.lostandfound.data.remote;

import android.content.Context;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class SupabaseClient {
    private static SupabaseClient instance;
    private final SupabaseAuthService authService;
    private final SupabaseRestService restService;
    private final SupabaseStorageService storageService;
    private final TokenRefresher tokenRefresher;

    private SupabaseClient(Context context) {
        SessionManager sessionManager = SessionManager.getInstance(context);
        this.tokenRefresher = new TokenRefresher(sessionManager);

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        // Redact sensitive credentials to ensure publishable keys and JWT tokens are NEVER printed in logs
        loggingInterceptor.redactHeader("apikey");
        loggingInterceptor.redactHeader("Authorization");

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(new SupabaseAuthInterceptor(sessionManager, tokenRefresher))
                .authenticator(new TokenAuthenticator(sessionManager, tokenRefresher))
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        String baseUrl = SupabaseConfig.SUPABASE_URL;
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        this.authService = retrofit.create(SupabaseAuthService.class);
        this.restService = retrofit.create(SupabaseRestService.class);
        this.storageService = retrofit.create(SupabaseStorageService.class);
    }

    public static synchronized SupabaseClient getInstance(Context context) {
        if (instance == null) {
            instance = new SupabaseClient(context.getApplicationContext());
        }
        return instance;
    }

    public SupabaseAuthService getAuthService() {
        return authService;
    }

    public SupabaseRestService getRestService() {
        return restService;
    }

    public SupabaseStorageService getStorageService() {
        return storageService;
    }

    public TokenRefresher getTokenRefresher() {
        return tokenRefresher;
    }
}

package com.example.lostandfound.data.remote;

import com.example.lostandfound.data.model.AuthResponse;
import com.example.lostandfound.data.model.AuthUser;
import java.util.Map;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface SupabaseAuthService {
    @POST("auth/v1/signup")
    Call<AuthResponse> signUp(@Body Map<String, Object> body);

    @POST("auth/v1/token?grant_type=password")
    Call<AuthResponse> signIn(@Body Map<String, String> body);

    @POST("auth/v1/token?grant_type=refresh_token")
    Call<AuthResponse> refreshToken(@Body Map<String, String> body);

    @GET("auth/v1/user")
    Call<AuthUser> getCurrentUser();

    @POST("auth/v1/logout")
    Call<ResponseBody> logout();
}

package com.example.lostandfound.data.remote;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface SupabaseStorageService {
    @POST("storage/v1/object/report-photos/{wildcard}")
    Call<ResponseBody> uploadPhoto(
            @Path(value = "wildcard", encoded = true) String path,
            @Header("Content-Type") String contentType,
            @Body RequestBody imageBytes
    );

    @POST("storage/v1/object/avatars/{wildcard}")
    Call<ResponseBody> uploadAvatar(
            @Path(value = "wildcard", encoded = true) String path,
            @Header("Content-Type") String contentType,
            @Header("x-upsert") String upsert,
            @Body RequestBody imageBytes
    );
}

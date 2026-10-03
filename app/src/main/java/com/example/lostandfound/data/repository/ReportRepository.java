package com.example.lostandfound.data.repository;

import android.content.Context;
import android.util.Log;
import com.example.lostandfound.BuildConfig;
import com.example.lostandfound.data.model.AbuseReport;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.model.ReportPrivateDetail;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ReportRepository {
    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String message);
        /**
         * A 401 that reaches a callback survived transparent token refresh, so
         * the session is dead. Defaults to the generic error path so existing
         * callers keep their behaviour unless they override this.
         */
        default void onAuthFailure() {
            onError("Session expired. Please sign in again.");
        }
    }

    private final SupabaseClient client;
    private final SessionManager sessionManager;

    public ReportRepository(Context context) {
        this.client = SupabaseClient.getInstance(context);
        this.sessionManager = SessionManager.getInstance(context);
    }

    public void getReports(String type, String category, String location, String search, DataCallback<List<Report>> callback) {
        Map<String, String> filters = new HashMap<>();
        if (type != null && !type.trim().isEmpty() && !type.equalsIgnoreCase("ALL")) {
            filters.put("type", "eq." + type);
        }
        if (category != null && !category.trim().isEmpty() && !category.equalsIgnoreCase("ALL")) {
            filters.put("category", "eq." + category);
        }
        if (location != null && !location.trim().isEmpty() && !location.equalsIgnoreCase("ALL")) {
            filters.put("campus_location", "eq." + location);
        }
        if (search != null && !search.trim().isEmpty()) {
            filters.put("title", "ilike.*" + search.trim() + "*");
        }

        client.getRestService().getReports("*", "created_at.desc", filters).enqueue(new Callback<List<Report>>() {
            @Override
            public void onResponse(Call<List<Report>> call, Response<List<Report>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else if (response.code() == 401) {
                    callback.onAuthFailure();
                } else {
                    callback.onError("Failed to load reports. Code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<List<Report>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void getMyReports(DataCallback<List<Report>> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("User not signed in");
            return;
        }

        Map<String, String> filters = new HashMap<>();
        filters.put("owner_id", "eq." + userId);

        client.getRestService().getReports("*", "created_at.desc", filters).enqueue(new Callback<List<Report>>() {
            @Override
            public void onResponse(Call<List<Report>> call, Response<List<Report>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError("Failed to load your reports. Code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<List<Report>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void getReportById(String reportId, DataCallback<Report> callback) {
        client.getRestService().getReportById("*", "eq." + reportId).enqueue(new Callback<List<Report>>() {
            @Override
            public void onResponse(Call<List<Report>> call, Response<List<Report>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    callback.onSuccess(response.body().get(0));
                } else {
                    callback.onError("Report not found");
                }
            }

            @Override
            public void onFailure(Call<List<Report>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void getPrivateDetails(String reportId, DataCallback<ReportPrivateDetail> callback) {
        client.getRestService().getReportPrivateDetails("*", "eq." + reportId).enqueue(new Callback<List<ReportPrivateDetail>>() {
            @Override
            public void onResponse(Call<List<ReportPrivateDetail>> call, Response<List<ReportPrivateDetail>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    callback.onSuccess(response.body().get(0));
                } else {
                    callback.onSuccess(null); // No private details or not owner
                }
            }

            @Override
            public void onFailure(Call<List<ReportPrivateDetail>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void createReport(Report report, String finderPrivateNotes, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_type", report.getType());
        params.put("p_title", report.getTitle());
        params.put("p_category", report.getCategory());
        params.put("p_description", report.getDescription());
        // Always send FOUND-only params explicitly (null for LOST) so PostgREST matches function signature
        params.put("p_public_verification_question", report.getPublicVerificationQuestion());
        params.put("p_finder_private_notes", finderPrivateNotes);
        // The RPC declares p_image_url without a default, so the key must be
        // present or PostgREST cannot match the signature. Gson omits null map
        // values, which silently dropped it for photo-less reports (PGRST202).
        // An empty string is stored instead of NULL; getPublicImageUrl maps ""
        // back to null, so every caller already treats the two alike.
        params.put("p_image_url", report.getImageUrl() == null ? "" : report.getImageUrl());
        params.put("p_campus_location", report.getCampusLocation());
        params.put("p_incident_date", report.getIncidentDate());
        params.put("p_incident_time_approx", report.getIncidentTimeApprox());
        // Optional coordinates: include only when set (omitted = null via SQL default)
        if (report.getLatitude() != null) {
            params.put("p_latitude", report.getLatitude());
        }
        if (report.getLongitude() != null) {
            params.put("p_longitude", report.getLongitude());
        }

        if (BuildConfig.DEBUG) {
            Log.d("ReportRepository", "createReport called: title='" + report.getTitle() + "', image_url='" + report.getImageUrl() + "'");
        }

        client.getRestService().createReportWithPrivateDetails(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (BuildConfig.DEBUG) {
                    Log.d("ReportRepository", "createReport HTTP response code: " + response.code());
                }
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    callback.onSuccess(response.body());
                } else {
                    String error = "Failed to create report.";
                    try {
                        if (response.errorBody() != null) {
                            error = response.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    if (BuildConfig.DEBUG) {
                        Log.w("ReportRepository", "createReport failed: " + error);
                    }
                    callback.onError(error);
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                if (BuildConfig.DEBUG) {
                    Log.w("ReportRepository", "createReport network failure: " + t.getMessage());
                }
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void uploadReportPhoto(byte[] imageBytes, String fileExtension, DataCallback<String> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("Must be signed in to upload photos");
            return;
        }

        String filename = UUID.randomUUID().toString() + "." + fileExtension;
        String storagePath = userId + "/" + filename;

        String contentType = "image/jpeg";
        if ("png".equalsIgnoreCase(fileExtension)) {
            contentType = "image/png";
        } else if ("webp".equalsIgnoreCase(fileExtension)) {
            contentType = "image/webp";
        }

        RequestBody body = RequestBody.create(MediaType.parse(contentType), imageBytes);

        if (BuildConfig.DEBUG) {
            Log.d("ReportRepository", "Uploading photo: storagePath=" + storagePath + ", size=" + imageBytes.length + " bytes");
        }

        client.getStorageService().uploadPhoto(storagePath, contentType, body).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (BuildConfig.DEBUG) {
                    Log.d("ReportRepository", "uploadPhoto HTTP response code: " + response.code());
                }
                if (response.isSuccessful()) {
                    callback.onSuccess(storagePath);
                } else {
                    String error = "Photo upload failed: " + response.code();
                    try {
                        if (response.errorBody() != null) {
                            error = response.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    if (BuildConfig.DEBUG) {
                        Log.w("ReportRepository", "uploadPhoto error body: " + error);
                    }
                    callback.onError(error);
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                if (BuildConfig.DEBUG) {
                    Log.w("ReportRepository", "uploadPhoto network failure: " + t.getMessage());
                }
                callback.onError("Photo upload failed: " + t.getLocalizedMessage());
            }
        });
    }

    public void markReportReturned(String reportId, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_report_id", reportId);

        client.getRestService().markReportReturned(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError("Failed to mark returned. Status code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void closeReport(String reportId, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_report_id", reportId);

        client.getRestService().closeReport(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError("Failed to close report. Status code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void submitAbuseReport(String targetReportId, String reason, String details, DataCallback<Boolean> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("Must be signed in to submit reports");
            return;
        }

        AbuseReport abuseReport = new AbuseReport(userId, targetReportId, null, reason, details);
        client.getRestService().submitAbuseReport("return=minimal", abuseReport).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    callback.onSuccess(true);
                } else {
                    callback.onError("Failed to submit abuse report.");
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }
}

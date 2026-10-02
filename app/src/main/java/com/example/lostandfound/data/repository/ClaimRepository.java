package com.example.lostandfound.data.repository;

import android.content.Context;
import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ClaimRepository {
    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String message);
    }

    private final SupabaseClient client;
    private final SessionManager sessionManager;

    public ClaimRepository(Context context) {
        this.client = SupabaseClient.getInstance(context);
        this.sessionManager = SessionManager.getInstance(context);
    }

    public void submitClaim(String reportId, String noteOrEvidence, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_report_id", reportId);
        params.put("p_note_or_evidence", noteOrEvidence);

        client.getRestService().submitClaim(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    callback.onSuccess(response.body());
                } else {
                    String error = "Failed to submit claim/response.";
                    try {
                        if (response.errorBody() != null) {
                            error = response.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    callback.onError(error);
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void getClaimsForReport(String reportId, DataCallback<List<Claim>> callback) {
        client.getRestService().getClaimsForReport("*", "eq." + reportId, "created_at.desc").enqueue(new Callback<List<Claim>>() {
            @Override
            public void onResponse(Call<List<Claim>> call, Response<List<Claim>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError("Failed to load claims. Code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<List<Claim>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }


    public void getMyClaims(DataCallback<List<Claim>> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("User not signed in");
            return;
        }

        // Embed the parent report's public verification question so the card can
        // show what the finder asked next to what the claimant answered. Safe to
        // read: public_verification_question is a column on public.reports, which
        // every authenticated student can select.
        //
        // The constraint hint is REQUIRED, not decoration: claims and reports are
        // related by two FKs (claims.report_id, and reports.accepted_claim_id
        // pointing back at claims), so an unhinted embed is ambiguous and
        // PostgREST rejects the whole query with PGRST201 / HTTP 300.
// Embed the parent report's public verification question so the card can
        // show what the finder asked next to what the claimant answered. Safe to
        // read: public_verification_question is a column on public.reports, which
        // every authenticated student can select.
        //
        // The constraint hint is REQUIRED, not decoration: claims and reports are
        // related by two FKs (claims.report_id, and reports.accepted_claim_id
        // pointing back at claims), so an unhinted embed is ambiguous and
        // PostgREST rejects the whole query with PGRST201 / HTTP 300.
        client.getRestService().getMyClaims(
                "*,report:reports!claims_report_id_fkey(public_verification_question)",
                "eq." + userId, "created_at.desc").enqueue(new Callback<List<Claim>>() {
            @Override
            public void onResponse(Call<List<Claim>> call, Response<List<Claim>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError("Failed to load your responses. Code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<List<Claim>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void acceptClaim(String claimId, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_claim_id", claimId);

        client.getRestService().acceptClaim(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    callback.onSuccess(response.body());
                } else {
                    String error = "Failed to accept claim.";
                    try {
                        if (response.errorBody() != null) {
                            error = response.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    callback.onError(error);
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void rejectClaim(String claimId, DataCallback<RpcResponse> callback) {
        rejectClaim(claimId, null, callback);
    }

    public void rejectClaim(String claimId, String rejectionMessage, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_claim_id", claimId);
        if (rejectionMessage != null && !rejectionMessage.trim().isEmpty()) {
            params.put("p_rejection_message", rejectionMessage.trim());
        } else {
            params.put("p_rejection_message", null);
        }

        client.getRestService().rejectClaim(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    callback.onSuccess(response.body());
                } else {
                    String error = "Failed to reject claim.";
                    try {
                        if (response.errorBody() != null) {
                            error = response.errorBody().string();
                        }
                    } catch (Exception ignored) {}
                    callback.onError(error);
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void getMyClaimForReport(String reportId, DataCallback<Claim> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onSuccess(null);
            return;
        }

        client.getRestService().getMyClaimForReport("*", "eq." + reportId, "eq." + userId).enqueue(new Callback<List<Claim>>() {
            @Override
            public void onResponse(Call<List<Claim>> call, Response<List<Claim>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    callback.onSuccess(response.body().get(0));
                } else {
                    callback.onSuccess(null);
                }
            }

            @Override
            public void onFailure(Call<List<Claim>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }
}

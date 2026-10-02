package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;

public class RpcResponse {
    @SerializedName("success")
    private boolean success;

    @SerializedName("report_id")
    private String reportId;

    @SerializedName("claim_id")
    private String claimId;

    @SerializedName("conversation_id")
    private String conversationId;

    @SerializedName("status")
    private String status;

    @SerializedName("message")
    private String message;

    public boolean isSuccess() {
        return success;
    }

    public String getReportId() {
        return reportId;
    }

    public String getClaimId() {
        return claimId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}

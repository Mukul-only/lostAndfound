package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;

public class AbuseReport {
    public static final String REASON_SPAM = "SPAM";
    public static final String REASON_SCAM_FRAUD = "SCAM_FRAUD";
    public static final String REASON_HARASSMENT = "HARASSMENT";
    public static final String REASON_INAPPROPRIATE_CONTENT = "INAPPROPRIATE_CONTENT";
    public static final String REASON_OTHER = "OTHER";

    @SerializedName("id")
    private String id;

    @SerializedName("reporter_id")
    private String reporterId;

    @SerializedName("target_report_id")
    private String targetReportId;

    @SerializedName("target_user_id")
    private String targetUserId;

    @SerializedName("reason")
    private String reason;

    @SerializedName("details")
    private String details;

    @SerializedName("created_at")
    private String createdAt;

    public AbuseReport() {}

    public AbuseReport(String reporterId, String targetReportId, String targetUserId, String reason, String details) {
        this.reporterId = reporterId;
        this.targetReportId = targetReportId;
        this.targetUserId = targetUserId;
        this.reason = reason;
        this.details = details;
    }

    public String getId() {
        return id;
    }

    public String getReporterId() {
        return reporterId;
    }

    public String getTargetReportId() {
        return targetReportId;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public String getReason() {
        return reason;
    }

    public String getDetails() {
        return details;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}

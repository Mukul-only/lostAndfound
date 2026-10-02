package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;

public class ReportPrivateDetail {
    @SerializedName("report_id")
    private String reportId;

    @SerializedName("finder_private_notes")
    private String finderPrivateNotes;

    @SerializedName("created_at")
    private String createdAt;

    public ReportPrivateDetail() {}

    public ReportPrivateDetail(String reportId, String finderPrivateNotes) {
        this.reportId = reportId;
        this.finderPrivateNotes = finderPrivateNotes;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getFinderPrivateNotes() {
        return finderPrivateNotes;
    }

    public void setFinderPrivateNotes(String finderPrivateNotes) {
        this.finderPrivateNotes = finderPrivateNotes;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}

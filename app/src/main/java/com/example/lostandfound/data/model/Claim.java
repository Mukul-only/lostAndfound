package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class Claim implements Serializable {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";

    @SerializedName("id")
    private String id;

    @SerializedName("report_id")
    private String reportId;

    @SerializedName("claimant_id")
    private String claimantId;

    @SerializedName("claimant_name")
    private String claimantName;

    @SerializedName("note_or_evidence")
    private String noteOrEvidence;

    @SerializedName("status")
    private String status;

    @SerializedName("created_at")
    private String createdAt;

    @SerializedName("reviewed_at")
    private String reviewedAt;

    @SerializedName("rejection_message")
    private String rejectionMessage;

    /** Parent report, present only when the query embedded it (e.g.
     *  select=*,report:reports(public_verification_question)). Carries the
     *  finder's public verification question. Null when the caller did not
     *  embed it, so every consumer must null-check. */
    @SerializedName("report")
    private Report report;

    public Claim() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getClaimantId() {
        return claimantId;
    }

    public void setClaimantId(String claimantId) {
        this.claimantId = claimantId;
    }

    public String getClaimantName() {
        return claimantName;
    }

    public void setClaimantName(String claimantName) {
        this.claimantName = claimantName;
    }

    public String getNoteOrEvidence() {
        return noteOrEvidence;
    }

    public void setNoteOrEvidence(String noteOrEvidence) {
        this.noteOrEvidence = noteOrEvidence;
    }

    public String getStatus() {
        return status != null ? status : STATUS_PENDING;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(String reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public boolean isPending() {
        return STATUS_PENDING.equalsIgnoreCase(status);
    }

    public boolean isAccepted() {
        return STATUS_ACCEPTED.equalsIgnoreCase(status);
    }

    public boolean isRejected() {
        return STATUS_REJECTED.equalsIgnoreCase(status);
    }

    public String getRejectionMessage() {
        return rejectionMessage;
    }

    public void setRejectionMessage(String rejectionMessage) {
        this.rejectionMessage = rejectionMessage;
    }

    public Report getReport() {
        return report;
    }

    public void setReport(Report report) {
        this.report = report;
    }

    /** The finder's verification question, or null when it was not embedded or
     *  the report is a LOST item (only FOUND items carry a question). */
    public String getVerificationQuestion() {
        return report == null ? null : report.getPublicVerificationQuestion();
    }
}

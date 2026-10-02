package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class MeetingLocation implements Serializable {
    /** Awaiting the other participant's answer. */
    public static final String STATUS_PROPOSED = "PROPOSED";
    /** The other participant accepted this spot. Final. */
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    /** The other participant declined this spot. Final. */
    public static final String STATUS_REJECTED = "REJECTED";
    /**
     * Legacy only. Written by the pre-010 propose_meeting_location, which
     * overwrote history. Kept so those rows still satisfy the status CHECK;
     * nothing writes it now.
     */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @SerializedName("id")
    private String id;

    @SerializedName("conversation_id")
    private String conversationId;

    @SerializedName("proposer_id")
    private String proposerId;

    @SerializedName("latitude")
    private Double latitude;

    @SerializedName("longitude")
    private Double longitude;

    @SerializedName("location_note")
    private String locationNote;

    @SerializedName("status")
    private String status;

    /**
     * True for the single agreed spot of the conversation. An older accepted
     * proposal keeps status ACCEPTED with is_current false, which is how
     * "previously accepted" is told apart from "the spot you are meeting at now".
     */
    @SerializedName("is_current")
    private Boolean isCurrent;

    @SerializedName("responded_by")
    private String respondedBy;

    @SerializedName("responded_at")
    private String respondedAt;

    @SerializedName("confirmed_by")
    private String confirmedBy;

    @SerializedName("confirmed_at")
    private String confirmedAt;

    @SerializedName("created_at")
    private String createdAt;

    @SerializedName("updated_at")
    private String updatedAt;

    public MeetingLocation() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getProposerId() {
        return proposerId;
    }

    public void setProposerId(String proposerId) {
        this.proposerId = proposerId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getLocationNote() {
        return locationNote;
    }

    public void setLocationNote(String locationNote) {
        this.locationNote = locationNote;
    }

    public String getStatus() {
        return status != null ? status : STATUS_PROPOSED;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public String getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(String confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isProposed() {
        return STATUS_PROPOSED.equalsIgnoreCase(status);
    }

    public boolean isAccepted() {
        return STATUS_ACCEPTED.equalsIgnoreCase(status);
    }

    public boolean isRejected() {
        return STATUS_REJECTED.equalsIgnoreCase(status);
    }

    public boolean isSuperseded() {
        return STATUS_SUPERSEDED.equalsIgnoreCase(status);
    }

    public boolean isAnswered() {
        return isAccepted() || isRejected();
    }

    /** The one spot both sides have agreed to meet at, if there is one. */
    public boolean isCurrent() {
        return Boolean.TRUE.equals(isCurrent);
    }

    public void setRespondedBy(String respondedBy) {
        this.respondedBy = respondedBy;
    }

    public void setRespondedAt(String respondedAt) {
        this.respondedAt = respondedAt;
    }

    public String getRespondedBy() {
        return respondedBy;
    }

    public String getRespondedAt() {
        return respondedAt;
    }

    public void setIsCurrent(Boolean isCurrent) {
        this.isCurrent = isCurrent;
    }
}

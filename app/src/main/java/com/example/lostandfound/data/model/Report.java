package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class Report implements Serializable {
    public static final String TYPE_LOST = "LOST";
    public static final String TYPE_FOUND = "FOUND";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_HANDOVER_ARRANGED = "HANDOVER_ARRANGED";
    public static final String STATUS_RETURNED = "RETURNED";
    public static final String STATUS_CLOSED = "CLOSED";

    @SerializedName("id")
    private String id;

    @SerializedName("type")
    private String type;

    @SerializedName("title")
    private String title;

    @SerializedName("category")
    private String category;

    @SerializedName("description")
    private String description;

    @SerializedName("public_verification_question")
    private String publicVerificationQuestion;

    @SerializedName("image_url")
    private String imageUrl;

    @SerializedName("campus_location")
    private String campusLocation;

    @SerializedName("latitude")
    private Double latitude;

    @SerializedName("longitude")
    private Double longitude;

    @SerializedName("incident_date")
    private String incidentDate;

    @SerializedName("incident_time_approx")
    private String incidentTimeApprox;

    @SerializedName("status")
    private String status;

    @SerializedName("owner_id")
    private String ownerId;

    @SerializedName("owner_name")
    private String ownerName;

    @SerializedName("accepted_claim_id")
    private String acceptedClaimId;

    @SerializedName("accepted_claimant_id")
    private String acceptedClaimantId;

    @SerializedName("created_at")
    private String createdAt;

    @SerializedName("updated_at")
    private String updatedAt;

    public Report() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPublicVerificationQuestion() {
        return publicVerificationQuestion;
    }

    public void setPublicVerificationQuestion(String publicVerificationQuestion) {
        this.publicVerificationQuestion = publicVerificationQuestion;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getCampusLocation() {
        return campusLocation;
    }

    public void setCampusLocation(String campusLocation) {
        this.campusLocation = campusLocation;
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

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    public String getIncidentDate() {
        return incidentDate;
    }

    public void setIncidentDate(String incidentDate) {
        this.incidentDate = incidentDate;
    }

    public String getIncidentTimeApprox() {
        return incidentTimeApprox;
    }

    public void setIncidentTimeApprox(String incidentTimeApprox) {
        this.incidentTimeApprox = incidentTimeApprox;
    }

    public String getStatus() {
        return status != null ? status : STATUS_OPEN;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getAcceptedClaimId() {
        return acceptedClaimId;
    }

    public void setAcceptedClaimId(String acceptedClaimId) {
        this.acceptedClaimId = acceptedClaimId;
    }

    public String getAcceptedClaimantId() {
        return acceptedClaimantId;
    }

    public void setAcceptedClaimantId(String acceptedClaimantId) {
        this.acceptedClaimantId = acceptedClaimantId;
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

    public boolean isLost() {
        return TYPE_LOST.equalsIgnoreCase(type);
    }

    public boolean isFound() {
        return TYPE_FOUND.equalsIgnoreCase(type);
    }

    public boolean isOpen() {
        return STATUS_OPEN.equalsIgnoreCase(status);
    }

    public boolean isHandoverArranged() {
        return STATUS_HANDOVER_ARRANGED.equalsIgnoreCase(status);
    }

    public boolean isReturned() {
        return STATUS_RETURNED.equalsIgnoreCase(status);
    }

    public boolean isClosed() {
        return STATUS_CLOSED.equalsIgnoreCase(status);
    }
}

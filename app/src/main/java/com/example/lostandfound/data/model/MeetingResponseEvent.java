package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * A server-authorized accept/decline of a {@link MeetingLocation}, rendered as a
 * centered row in the chat timeline ("Alex accepted the meeting spot.").
 *
 * One row exists per proposal and is written in the same transaction as the
 * proposal's outcome, so a retry cannot add a second one and a client cannot
 * author one.
 */
public class MeetingResponseEvent {
    public static final String RESPONSE_ACCEPTED = "ACCEPTED";
    public static final String RESPONSE_REJECTED = "REJECTED";

    @SerializedName("id")
    private String id;

    @SerializedName("conversation_id")
    private String conversationId;

    /** The exact proposal this event answers. Lets the reader jump to it. */
    @SerializedName("meeting_id")
    private String meetingId;

    @SerializedName("actor_id")
    private String actorId;

    @SerializedName("response")
    private String response;

    @SerializedName("created_at")
    private String createdAt;

    public MeetingResponseEvent() {}

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

    public String getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(String meetingId) {
        this.meetingId = meetingId;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getResponse() {
        return response != null ? response : RESPONSE_ACCEPTED;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isAccepted() {
        return RESPONSE_ACCEPTED.equalsIgnoreCase(getResponse());
    }

    public boolean isRejected() {
        return RESPONSE_REJECTED.equalsIgnoreCase(getResponse());
    }
}

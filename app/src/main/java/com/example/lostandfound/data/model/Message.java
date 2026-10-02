package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;

public class Message {
    @SerializedName("id")
    private String id;

    @SerializedName("conversation_id")
    private String conversationId;

    @SerializedName("sender_id")
    private String senderId;

    @SerializedName("sender_name")
    private String senderName;

    @SerializedName("content")
    private String content;

    @SerializedName("created_at")
    private String createdAt;

    /**
     * Optional quoted-reply targets, exactly one of which may be set. Both are
     * validated server-side to belong to this message's own conversation, so a
     * client can neither quote across threads nor invent a reference.
     */
    @SerializedName("reply_to_message_id")
    private String replyToMessageId;

    @SerializedName("reply_to_meeting_id")
    private String replyToMeetingId;

    public Message() {}

    public Message(String conversationId, String senderId, String senderName, String content) {
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.content = content;
    }

    /** Returns this, so a send call can chain the reply target in one expression. */
    public Message withReplyToMessage(String messageId) {
        this.replyToMessageId = messageId;
        return this;
    }

    public Message withReplyToMeeting(String meetingId) {
        this.replyToMeetingId = meetingId;
        return this;
    }

    public String getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(String replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    public String getReplyToMeetingId() {
        return replyToMeetingId;
    }

    public void setReplyToMeetingId(String replyToMeetingId) {
        this.replyToMeetingId = replyToMeetingId;
    }

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

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}

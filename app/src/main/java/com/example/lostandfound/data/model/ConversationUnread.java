package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * One row of {@code get_conversation_unread_counts()}: a conversation the
 * current user is in that has something unread. Conversations with nothing
 * unread are simply absent from the result, which is what removes the badge
 * rather than showing a zero.
 */
public class ConversationUnread {
    @SerializedName("conversation_id")
    private String conversationId;

    @SerializedName("unread_count")
    private int unreadCount;

    public ConversationUnread() {}

    public ConversationUnread(String conversationId, int unreadCount) {
        this.conversationId = conversationId;
        this.unreadCount = unreadCount;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }
}
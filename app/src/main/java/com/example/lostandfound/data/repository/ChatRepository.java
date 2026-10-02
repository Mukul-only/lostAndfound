package com.example.lostandfound.data.repository;

import android.content.Context;
import com.example.lostandfound.data.model.Conversation;
import com.example.lostandfound.data.model.ConversationUnread;
import com.example.lostandfound.data.model.MeetingLocation;
import com.example.lostandfound.data.model.MeetingResponseEvent;
import com.example.lostandfound.data.model.Message;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChatRepository {
    public interface DataCallback<T> {
        void onSuccess(T data);
        void onError(String message);
    }

    private final SupabaseClient client;
    private final SessionManager sessionManager;

    public ChatRepository(Context context) {
        this.client = SupabaseClient.getInstance(context);
        this.sessionManager = SessionManager.getInstance(context);
    }

    public void getMyConversations(DataCallback<List<Conversation>> callback) {
        String userId = sessionManager.getUserId();
        if (userId == null) {
            callback.onError("User not signed in");
            return;
        }

        String filter = "(owner_id.eq." + userId + ",claimant_id.eq." + userId + ")";
        client.getRestService().getConversations("*", filter, "last_message_at.desc").enqueue(new Callback<List<Conversation>>() {
            @Override
            public void onResponse(Call<List<Conversation>> call, Response<List<Conversation>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError("Failed to load conversations. Code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<List<Conversation>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void getConversationById(String conversationId, DataCallback<Conversation> callback) {
        client.getRestService().getConversationById("*", "eq." + conversationId).enqueue(new Callback<List<Conversation>>() {
            @Override
            public void onResponse(Call<List<Conversation>> call, Response<List<Conversation>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    callback.onSuccess(response.body().get(0));
                } else {
                    callback.onError("Conversation not found");
                }
            }

            @Override
            public void onFailure(Call<List<Conversation>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void getMessages(String conversationId, DataCallback<List<Message>> callback) {
        client.getRestService().getMessages("*", "eq." + conversationId, "created_at.asc").enqueue(new Callback<List<Message>>() {
            @Override
            public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError("Failed to load messages. Code: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<List<Message>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    public void sendMessage(String conversationId, String content, DataCallback<Message> callback) {
        sendMessage(conversationId, content, null, null, callback);
    }

    /**
     * @param replyToMessageId quoted target, an earlier message in this same
     *                         conversation, or null
     * @param replyToMeetingId quoted target, a meeting proposal in this same
     *                          conversation, or null
     */
    public void sendMessage(String conversationId, String content,
                            String replyToMessageId, String replyToMeetingId,
                            DataCallback<Message> callback) {
        String userId = sessionManager.getUserId();
        String userName = sessionManager.getDisplayName();
        if (userId == null) {
            callback.onError("User not signed in");
            return;
        }

        Message msg = new Message(conversationId, userId, userName, content.trim());
        if (replyToMessageId != null) msg.withReplyToMessage(replyToMessageId);
        if (replyToMeetingId != null) msg.withReplyToMeeting(replyToMeetingId);

        client.getRestService().sendMessage("return=representation", msg).enqueue(new Callback<List<Message>>() {
            @Override
            public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    callback.onSuccess(response.body().get(0));
                } else {
                    callback.onError(readError(response, "Failed to send message."));
                }
            }

            @Override
            public void onFailure(Call<List<Message>> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    /**
     * Unread counts for the caller's own conversations. Conversations with
     * nothing unread are absent from the result, which is what makes a badge
     * disappear instead of showing a zero.
     */
    public void getConversationUnreadCounts(DataCallback<List<ConversationUnread>> callback) {
        Map<String, Object> params = new HashMap<>();
        client.getRestService()
                .getConversationUnreadCounts("return=representation", params)
                .enqueue(new Callback<List<ConversationUnread>>() {
                    @Override
                    public void onResponse(Call<List<ConversationUnread>> call,
                                           Response<List<ConversationUnread>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            callback.onSuccess(response.body());
                        } else {
                            callback.onError(readError(response, "Failed to load unread counts."));
                        }
                    }

                    @Override
                    public void onFailure(Call<List<ConversationUnread>> call, Throwable t) {
                        callback.onError("Network error: " + t.getLocalizedMessage());
                    }
                });
    }

    /**
     * Moves this user's read cursor for one conversation to the newest item they
     * have actually seen. The server stores the greater of the stored and
     * supplied timestamps, so a replay or a stale value can neither rewind the
     * cursor nor mark something unseen.
     *
     * @param readAtIso ISO timestamp of the newest item rendered
     */
    public void markConversationRead(String conversationId, String readAtIso,
                                     DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_conversation_id", conversationId);
        params.put("p_read_at", readAtIso);
        client.getRestService().markConversationRead(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().isSuccess()) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError(readError(response, "Failed to update read state."));
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    /**
     * Every meeting proposal in the conversation, not just the latest one. The
     * timeline needs all of them: earlier cards keep their own identity and
     * their own outcome.
     */
    public void getMeetingProposals(String conversationId, DataCallback<List<MeetingLocation>> callback) {
        client.getRestService().getMeetingProposals("eq." + conversationId, "created_at.asc")
                .enqueue(new Callback<List<MeetingLocation>>() {
                    @Override
                    public void onResponse(Call<List<MeetingLocation>> call, Response<List<MeetingLocation>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            callback.onSuccess(response.body());
                        } else {
                            callback.onError("Failed to load handover locations");
                        }
                    }

                    @Override
                    public void onFailure(Call<List<MeetingLocation>> call, Throwable t) {
                        callback.onError("Network error: " + t.getLocalizedMessage());
                    }
                });
    }

    /** The centered accept/decline rows, server-authorized and one per proposal. */
    public void getMeetingResponseEvents(String conversationId, DataCallback<List<MeetingResponseEvent>> callback) {
        client.getRestService().getMeetingResponseEvents("eq." + conversationId, "created_at.asc")
                .enqueue(new Callback<List<MeetingResponseEvent>>() {
                    @Override
                    public void onResponse(Call<List<MeetingResponseEvent>> call, Response<List<MeetingResponseEvent>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            callback.onSuccess(response.body());
                        } else {
                            callback.onError("Failed to load meeting updates");
                        }
                    }

                    @Override
                    public void onFailure(Call<List<MeetingResponseEvent>> call, Throwable t) {
                        callback.onError("Network error: " + t.getLocalizedMessage());
                    }
                });
    }

    public void proposeMeetingLocation(String conversationId, double latitude, double longitude, String note, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_conversation_id", conversationId);
        params.put("p_latitude", latitude);
        params.put("p_longitude", longitude);
        if (note != null && !note.trim().isEmpty()) {
            params.put("p_location_note", note.trim());
        }

        client.getRestService().proposeMeetingLocation(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError(readError(response, "Failed to propose meeting location."));
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    /**
     * Accept or decline a pending proposal. The server writes the outcome and the
     * centered timeline event in one transaction, decides who is allowed to
     * respond, and rejects a replayed or concurrent second response.
     *
     * @param response "ACCEPTED" or "REJECTED"
     */
    public void respondToMeetingProposal(String meetingId, String response, DataCallback<RpcResponse> callback) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_meeting_id", meetingId);
        params.put("p_response", response);

        client.getRestService().respondToMeetingProposal(params).enqueue(new Callback<RpcResponse>() {
            @Override
            public void onResponse(Call<RpcResponse> call, Response<RpcResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    callback.onSuccess(response.body());
                } else {
                    callback.onError(readError(response, "Failed to update the handover location."));
                }
            }

            @Override
            public void onFailure(Call<RpcResponse> call, Throwable t) {
                callback.onError("Network error: " + t.getLocalizedMessage());
            }
        });
    }

    /**
     * Supabase reports constraint and RPC failures as a JSON body; surfacing the
     * raw text is what lets the UI say "already answered" instead of a bare
     * "something went wrong".
     */
    private static String readError(Response<?> response, String fallback) {
        try {
            if (response.errorBody() != null) {
                String body = response.errorBody().string();
                if (body != null && !body.trim().isEmpty()) return body.trim();
            }
        } catch (Exception ignored) {
        }
        return fallback + " (code " + response.code() + ")";
    }
}

package com.example.lostandfound.data.remote;

import com.example.lostandfound.data.model.AbuseReport;
import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.data.model.Conversation;
import com.example.lostandfound.data.model.ConversationUnread;
import com.example.lostandfound.data.model.MeetingLocation;
import com.example.lostandfound.data.model.MeetingResponseEvent;
import com.example.lostandfound.data.model.Message;
import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.model.ReportPrivateDetail;
import com.example.lostandfound.data.model.RpcResponse;
import java.util.List;
import java.util.Map;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

public interface SupabaseRestService {

    // PROFILES
    @GET("rest/v1/profiles")
    Call<List<Profile>> getProfiles(
            @Query("select") String select,
            @Query("id") String idFilter
    );

    @POST("rest/v1/profiles")
    Call<List<Profile>> upsertProfile(
            @Header("Prefer") String prefer,
            @Body Profile profile
    );

    @PATCH("rest/v1/profiles")
    Call<List<Profile>> updateProfile(
            @Query("id") String idFilter,
            @Header("Prefer") String prefer,
            @Body Map<String, Object> fields
    );

    // REPORTS
    @GET("rest/v1/reports")
    Call<List<Report>> getReports(
            @Query("select") String select,
            @Query("order") String order,
            @QueryMap Map<String, String> filters
    );

    @GET("rest/v1/reports")
    Call<List<Report>> getReportById(
            @Query("select") String select,
            @Query("id") String idFilter
    );

    @PATCH("rest/v1/reports")
    Call<List<Report>> updateReport(
            @Query("id") String idFilter,
            @Header("Prefer") String prefer,
            @Body Map<String, Object> fields
    );

    @DELETE("rest/v1/reports")
    Call<ResponseBody> deleteReport(@Query("id") String idFilter);

    // REPORT PRIVATE DETAILS (Owner only)
    @GET("rest/v1/report_private_details")
    Call<List<ReportPrivateDetail>> getReportPrivateDetails(
            @Query("select") String select,
            @Query("report_id") String reportIdFilter
    );

    // CLAIMS
    @GET("rest/v1/claims")
    Call<List<Claim>> getClaimsForReport(
            @Query("select") String select,
            @Query("report_id") String reportIdFilter,
            @Query("order") String order
    );

    @GET("rest/v1/claims")
    Call<List<Claim>> getMyClaims(
            @Query("select") String select,
            @Query("claimant_id") String claimantIdFilter,
            @Query("order") String order
    );

    @GET("rest/v1/claims")
    Call<List<Claim>> getMyClaimForReport(
            @Query("select") String select,
            @Query("report_id") String reportIdFilter,
            @Query("claimant_id") String claimantIdFilter
    );

    // CONVERSATIONS
    @GET("rest/v1/conversations")
    Call<List<Conversation>> getConversations(
            @Query("select") String select,
            @Query("or") String participantFilter,
            @Query("order") String order
    );

    @GET("rest/v1/conversations")
    Call<List<Conversation>> getConversationById(
            @Query("select") String select,
            @Query("id") String idFilter
    );

    // MESSAGES
    @GET("rest/v1/messages")
    Call<List<Message>> getMessages(
            @Query("select") String select,
            @Query("conversation_id") String conversationIdFilter,
            @Query("order") String order
    );

    @POST("rest/v1/messages")
    Call<List<Message>> sendMessage(
            @Header("Prefer") String prefer,
            @Body Message message
    );

    // ABUSE REPORTS
    @POST("rest/v1/abuse_reports")
    Call<ResponseBody> submitAbuseReport(
            @Header("Prefer") String prefer,
            @Body AbuseReport report
    );

    // RPC FUNCTIONS
    @POST("rest/v1/rpc/create_report_with_private_details")
    Call<RpcResponse> createReportWithPrivateDetails(@Body Map<String, Object> params);

    @POST("rest/v1/rpc/submit_claim")
    Call<RpcResponse> submitClaim(@Body Map<String, Object> params);

    @POST("rest/v1/rpc/accept_claim")
    Call<RpcResponse> acceptClaim(@Body Map<String, Object> params);

    @POST("rest/v1/rpc/reject_claim")
    Call<RpcResponse> rejectClaim(@Body Map<String, Object> params);

    // MEETING LOCATIONS
    /** Every proposal in the conversation, newest last. History is never trimmed. */
    @GET("rest/v1/conversation_meeting_locations")
    Call<List<MeetingLocation>> getMeetingProposals(
            @Query("conversation_id") String conversationIdFilter,
            @Query("order") String order
    );

    /** Server-authorized accept/decline rows, rendered as centered timeline rows. */
    @GET("rest/v1/meeting_response_events")
    Call<List<MeetingResponseEvent>> getMeetingResponseEvents(
            @Query("conversation_id") String conversationIdFilter,
            @Query("order") String order
    );

    // UNREAD COUNTS (migration 012). Both are SECURITY DEFINER and scoped to the
    // caller, so a client cannot ask about another user's conversations.
    @POST("rest/v1/rpc/get_conversation_unread_counts")
    Call<List<ConversationUnread>> getConversationUnreadCounts(
            @Header("Prefer") String prefer,
            @Body Map<String, Object> params);

    /** Advances this user's read cursor for one conversation. Monotonic. */
    @POST("rest/v1/rpc/mark_conversation_read")
    Call<RpcResponse> markConversationRead(@Body Map<String, Object> params);

    @POST("rest/v1/rpc/propose_meeting_location")
    Call<RpcResponse> proposeMeetingLocation(@Body Map<String, Object> params);

    /** Accepts or declines. Writes the outcome and its timeline event atomically. */
    @POST("rest/v1/rpc/respond_to_meeting_proposal")
    Call<RpcResponse> respondToMeetingProposal(@Body Map<String, Object> params);

    @POST("rest/v1/rpc/mark_report_returned")
    Call<RpcResponse> markReportReturned(@Body Map<String, Object> params);

    @POST("rest/v1/rpc/close_report")
    Call<RpcResponse> closeReport(@Body Map<String, Object> params);
}

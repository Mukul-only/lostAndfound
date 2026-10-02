package com.example.lostandfound.data.model;

/**
 * One row in the conversation timeline. The three kinds - text, meeting
 * proposal, meeting response event - are separate concepts with separate
 * lifecycles but the same rendering surface (a chronological list), so they are
 * one type with a {@link #kind} discriminator instead of a hierarchy.
 *
 * Exactly one of {@link #getMessage()}, {@link #getProposal()} or
 * {@link #getEvent()} is non-null, matching {@link #getKind()}.
 *
 * Deliberately free of android.* imports so {@link ChatTimeline} and its tests
 * run on a plain JVM.
 */
public class ChatTimelineItem {
    public static final int KIND_TEXT = 0;
    public static final int KIND_PROPOSAL = 1;
    public static final int KIND_EVENT = 2;

    /** Key prefixes. Public because the timeline merge and the jump-to lookup
        both build keys, and a row's key must be identical in all three places. */
    public static final String KEY_PREFIX_TEXT = "t:";
    public static final String KEY_PREFIX_PROPOSAL = "p:";
    public static final String KEY_PREFIX_EVENT = "e:";

    private final int kind;
    private final Message message;
    private final MeetingLocation proposal;
    private final MeetingResponseEvent event;
    private ReplyPreview replyPreview;

    private ChatTimelineItem(int kind, Message message, MeetingLocation proposal,
                             MeetingResponseEvent event) {
        this.kind = kind;
        this.message = message;
        this.proposal = proposal;
        this.event = event;
    }

    public static ChatTimelineItem text(Message message) {
        return new ChatTimelineItem(KIND_TEXT, message, null, null);
    }

    public static ChatTimelineItem proposal(MeetingLocation proposal) {
        return new ChatTimelineItem(KIND_PROPOSAL, null, proposal, null);
    }

    public static ChatTimelineItem event(MeetingResponseEvent event) {
        return new ChatTimelineItem(KIND_EVENT, null, null, event);
    }

    public int getKind() {
        return kind;
    }

    public Message getMessage() {
        return message;
    }

    public MeetingLocation getProposal() {
        return proposal;
    }

    public MeetingResponseEvent getEvent() {
        return event;
    }

    public ReplyPreview getReplyPreview() {
        return replyPreview;
    }

    public void setReplyPreview(ReplyPreview replyPreview) {
        this.replyPreview = replyPreview;
    }

    /**
     * Stable, collision-free identity for DiffUtil and RecyclerView. The kind
     * prefix matters: a message id and a proposal id are both UUIDs, and the
     * timeline must never conflate them.
     */
    public String getKey() {
        switch (kind) {
            case KIND_TEXT:
                return KEY_PREFIX_TEXT + rawId();
            case KIND_PROPOSAL:
                return KEY_PREFIX_PROPOSAL + rawId();
            default:
                return KEY_PREFIX_EVENT + rawId();
        }
    }

    /** The underlying row id, used to resolve reply targets. */
    public String getRawId() {
        return rawId();
    }

    private String rawId() {
        if (message != null) return message.getId();
        if (proposal != null) return proposal.getId();
        return event != null ? event.getId() : null;
    }

    public String getCreatedAt() {
        if (message != null) return message.getCreatedAt();
        if (proposal != null) return proposal.getCreatedAt();
        return event != null ? event.getCreatedAt() : null;
    }

    /** Who this row is attributed to. Null for a response event's subject. */
    public String getSenderId() {
        if (message != null) return message.getSenderId();
        return proposal != null ? proposal.getProposerId() : null;
    }

    public String getContent() {
        return message != null ? message.getContent() : null;
    }

    /** The quoted item this text message replies to, if any. */
    public String getReplyTargetKey() {
        if (message == null) return null;
        if (message.getReplyToMessageId() != null) {
            return KEY_PREFIX_TEXT + message.getReplyToMessageId();
        }
        if (message.getReplyToMeetingId() != null) {
            return KEY_PREFIX_PROPOSAL + message.getReplyToMeetingId();
        }
        return null;
    }

    /** Proposals and text messages can be replied to; response events cannot. */
    public boolean isReplyable() {
        return kind == KIND_TEXT || kind == KIND_PROPOSAL;
    }

    /**
     * Everything the row renders, flattened. DiffUtil compares these strings,
     * so a changed status, a newly-resolved reply preview, or edited text all
     * produce exactly one rebind of that row and nothing else.
     */
    public String contentSignature() {
        StringBuilder sb = new StringBuilder(getKey());
        if (message != null) {
            sb.append("|t:").append(message.getSenderId())
              .append('|').append(message.getContent());
        } else if (proposal != null) {
            sb.append("|p:").append(proposal.getStatus())
              .append("|cur=").append(proposal.isCurrent())
              .append("|by=").append(proposal.getRespondedBy())
              .append("|").append(proposal.getLocationNote());
        } else if (event != null) {
            sb.append("|e:").append(event.getResponse())
              .append('|').append(event.getMeetingId());
        }
        if (replyPreview != null) {
            sb.append("|q:").append(replyPreview.getSenderName())
              .append('|').append(replyPreview.getExcerpt())
              .append('|').append(replyPreview.isMeetingSpot());
        }
        return sb.toString();
    }
}

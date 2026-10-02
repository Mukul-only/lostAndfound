package com.example.lostandfound.ui.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.ChatTimelineItem;
import com.example.lostandfound.data.model.MeetingLocation;
import com.example.lostandfound.data.model.Message;
import com.example.lostandfound.data.model.ReplyPreview;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.data.repository.ProfileDirectory;
import com.example.lostandfound.databinding.ItemChatEventBinding;
import com.example.lostandfound.databinding.ItemChatProposalBinding;
import com.example.lostandfound.databinding.ItemMessageBinding;
import com.example.lostandfound.databinding.ViewChatQuoteBinding;
import com.example.lostandfound.util.DateUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the conversation as one chronological list of three row types: text
 * messages, meeting proposal cards, and centered accept/decline events.
 *
 * Every update goes through {@link #submit} so a poll rebinds only the rows whose
 * content actually changed. A full {@code notifyDataSetChanged} on each tick
 * would restart item animations and drop the reader's place, which is exactly
 * what polling every few seconds must not do.
 *
 * Replies: a right swipe (see {@link ReplySwipeCallback}) selects the row as
 * the reply target. Because a swipe is a drag gesture and WCAG 2.2 AA requires
 * a single-pointer alternative, every replyable row also exposes a custom
 * "Reply" accessibility action ({@link #bindReplyAction}) - reachable from the
 * screen reader's actions menu, with no gesture at all. The row itself is never
 * removed, dismissed or reordered; it only returns to rest.
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    /** Fraction of the list width a bubble may occupy, leaving a readable gutter. */
    private static final float BUBBLE_MAX_FRACTION = 0.78f;
    private static final float QUOTE_MAX_FRACTION = 0.60f;

    public interface Listener {
        void onReplySelected(ChatTimelineItem target);
        void onJumpToItem(String key);
        void onViewMap(MeetingLocation proposal);
        void onRespond(MeetingLocation proposal, boolean accept);
        /** Ask the host for a display name; may return null while profiles load. */
        String displayNameOf(String userId, String fallback);
    }

    private final List<ChatTimelineItem> items = new ArrayList<>();
    /** RecyclerView stable ids, assigned once per row so they can never collide. */
    private final Map<String, Long> stableIds = new HashMap<>();
    private final String currentUserId;
    private final Listener listener;
    private long nextStableId = 1L;

    public MessageAdapter(String currentUserId, Listener listener) {
        this.currentUserId = currentUserId;
        this.listener = listener;
        setHasStableIds(true);
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    /**
     * Diffs against the current list: only rows whose content changed rebind.
     *
     * @return true if anything actually changed. The caller must not touch the
     *         scroll when this returns false - see the note in
     *         {@code ChatActivity.submitTimeline}. Most polls change nothing,
     *         and re-anchoring on an unchanged diff is what made the list twitch
     *         on every tick.
     */
    public boolean submit(List<ChatTimelineItem> newItems) {
        final List<ChatTimelineItem> next =
                newItems == null ? new ArrayList<ChatTimelineItem>() : newItems;

        if (!hasContentChanged(next)) return false;

        final List<ChatTimelineItem> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return old.get(oldPos).getKey().equals(next.get(newPos).getKey());
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return old.get(oldPos).contentSignature()
                        .equals(next.get(newPos).contentSignature());
            }
        });

        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
        return true;
    }

    /**
     * Cheap value comparison so a poll that changed nothing skips DiffUtil and,
     * more importantly, reports "unchanged" so the caller leaves the list alone.
     * Keys are unique, so an equal-length list in the same order with equal
     * signatures is the same list.
     */
    private boolean hasContentChanged(List<ChatTimelineItem> next) {
        if (items.size() != next.size()) return true;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).contentSignature().equals(next.get(i).contentSignature())) {
                return true;
            }
        }
        return false;
    }

    public int indexOf(String key) {
        if (key == null) return RecyclerView.NO_POSITION;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getKey().equals(key)) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    /** Null for an out-of-range position, which is what a stale swipe can hand us. */
    public ChatTimelineItem getItem(int position) {
        return position < 0 || position >= items.size() ? null : items.get(position);
    }

    @Override
    public long getItemId(int position) {
        String key = items.get(position).getKey();
        Long id = stableIds.get(key);
        if (id == null) {
            id = nextStableId++;
            stableIds.put(key, id);
        }
        return id;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).getKind();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case ChatTimelineItem.KIND_PROPOSAL:
                return new ProposalHolder(ItemChatProposalBinding.inflate(inflater, parent, false));
            case ChatTimelineItem.KIND_EVENT:
                return new EventHolder(ItemChatEventBinding.inflate(inflater, parent, false));
            default:
                return new MessageHolder(ItemMessageBinding.inflate(inflater, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatTimelineItem item = items.get(position);
        if (holder instanceof ProposalHolder) {
            ((ProposalHolder) holder).bind(item);
        } else if (holder instanceof EventHolder) {
            ((EventHolder) holder).bind(item);
        } else {
            ((MessageHolder) holder).bind(item, position);
        }
    }

    /**
     * Exposes "Reply" as a custom accessibility action on the row.
     *
     * This replaces the long-press menu that used to sit here. A swipe is a drag
     * gesture, and WCAG 2.2 AA requires a single-pointer alternative for
     * author-controlled drag operations; an action in the screen reader's actions
     * menu is reachable without any gesture, which a long-press popup is not
     * (it also competes with the long-press gesture for touch exploration).
     *
     * Attached to the row root, so it is offered for both sent and received
     * messages and for meeting-proposal cards, and omitted entirely for centered
     * response-event rows, which are not replyable.
     */
    private void bindReplyAction(@NonNull View row, final ChatTimelineItem item) {
        if (!item.isReplyable()) {
            ViewCompat.setAccessibilityDelegate(row, null);
            return;
        }
        ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.chat_action_reply,
                        host.getContext().getString(R.string.chat_reply_action)));
            }

            @Override
            public boolean performAccessibilityAction(@NonNull View host, int action,
                                                      @Nullable android.os.Bundle args) {
                if (action == R.id.chat_action_reply) {
                    listener.onReplySelected(item);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    /**
     * The view a "jump to this message" flash should outline.
     *
     * Deliberately the bubble, not the row root: the row also contains the
     * avatar, the sender name and the timestamp, and flashing those makes the
     * highlight read as "this whole message item lit up" rather than "this is the
     * message you quoted".
     */
    public View highlightTargetOf(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof MessageHolder) {
            return ((MessageHolder) holder).bubble();
        }
        if (holder instanceof ProposalHolder) {
            // A proposal card has no bubble; the card itself is the message.
            return holder.itemView;
        }
        // Response events are not a jump target, but fall back to the row so a
        // flash can never be requested on a view that cannot take one.
        return holder.itemView;
    }

    /** True when the holder shows the reader's own message. */
    public boolean isOutgoingHolder(@NonNull RecyclerView.ViewHolder holder) {
        if (!(holder instanceof MessageHolder)) return false;
        return ((MessageHolder) holder).isOutgoing();
    }

    /**
     * Rows that a swipe may target. Read by {@link ReplySwipeCallback} on every
     * gesture frame, so it stays a cheap lookup against the list the adapter is
     * actually rendering. Centered response events are never targets.
     */
    public boolean isReplyableAt(int position) {
        return position >= 0 && position < items.size() && items.get(position).isReplyable();
    }

    /**
     * Whether the row at this position is the reader's own message. The gesture
     * reads it to pick a direction: own messages slide right, the other person's
     * slide left, which is what a reader's hand expects.
     */
    public boolean isOutgoingAt(int position) {
        if (position < 0 || position >= items.size()) return false;
        String senderId = items.get(position).getSenderId();
        return senderId != null && senderId.equals(currentUserId);
    }

    /**
     * A row handed back to the pool must carry no gesture residue. Without this
     * a partially-swiped row's translation is inherited by whatever message is
     * bound next, which looks like a row that has mysteriously slid sideways.
     */
    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.itemView.setTranslationX(0f);
        holder.itemView.setAlpha(1f);
        holder.itemView.animate().cancel();
    }

    // ------------------------------------------------------------------
    // Text messages
    // ------------------------------------------------------------------

    class MessageHolder extends RecyclerView.ViewHolder {
        private final ItemMessageBinding binding;
        private boolean outgoing;

        MessageHolder(ItemMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /** The bubble only - never the row, so the time is left unlit. */
        View bubble() {
            return outgoing ? binding.bubbleOut : binding.bubbleIn;
        }

        boolean isOutgoing() {
            return outgoing;
        }

        void bind(ChatTimelineItem item, int position) {
            Message message = item.getMessage();
            boolean isMe = currentUserId != null && currentUserId.equals(message.getSenderId());
            outgoing = isMe;
            String time = DateUtils.formatDisplayTime(message.getCreatedAt());
            boolean grouped = position > 0 && isSameSenderAsPrevious(position);

            // Tighter spacing inside a run so a group reads as one block.
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) binding.getRoot().getLayoutParams();
            if (lp != null) {
                int gap = grouped ? 2 : 8;
                lp.topMargin = gap;
                lp.bottomMargin = gap;
                binding.getRoot().setLayoutParams(lp);
            }

            int bubbleMax = widthOf(binding.getRoot(), BUBBLE_MAX_FRACTION);
            int quoteMax = widthOf(binding.getRoot(), QUOTE_MAX_FRACTION);

            if (isMe) {
                binding.layoutOutMessage.setVisibility(View.VISIBLE);
                binding.layoutInMessage.setVisibility(View.GONE);
                binding.tvOutContent.setText(message.getContent());
                binding.tvOutContent.setMaxWidth(bubbleMax);
                binding.tvOutTime.setText(time);
                binding.ivOutAvatar.setVisibility(grouped ? View.INVISIBLE : View.VISIBLE);
                bindAvatar(binding.ivOutAvatar, message.getSenderId());
                bindQuote(binding.quoteOut, item, quoteMax);
            } else {
                binding.layoutInMessage.setVisibility(View.VISIBLE);
                binding.layoutOutMessage.setVisibility(View.GONE);
                binding.tvInSenderName.setText(
                        listener.displayNameOf(message.getSenderId(), message.getSenderName()));
                binding.tvInSenderName.setVisibility(grouped ? View.GONE : View.VISIBLE);
                binding.tvInContent.setText(message.getContent());
                binding.tvInContent.setMaxWidth(bubbleMax);
                binding.tvInTime.setText(time);
                binding.ivInAvatar.setVisibility(grouped ? View.INVISIBLE : View.VISIBLE);
                bindAvatar(binding.ivInAvatar, message.getSenderId());
                bindQuote(binding.quoteIn, item, quoteMax);
            }

            bindReplyAction(binding.getRoot(), item);
        }

        /** Quoted preview above the new text; tapping it jumps to the original. */
        private void bindQuote(ViewChatQuoteBinding quote, ChatTimelineItem item, int maxWidth) {
            ReplyPreview preview = item.getReplyPreview();
            if (preview == null) {
                quote.getRoot().setVisibility(View.GONE);
                quote.getRoot().setOnClickListener(null);
                return;
            }
            quote.getRoot().setVisibility(View.VISIBLE);
            quote.tvQuoteSender.setText(preview.getSenderName());
            quote.tvQuoteSender.setMaxWidth(maxWidth);
            String excerpt = excerptOf(preview);
            quote.tvQuoteExcerpt.setText(excerpt);
            quote.tvQuoteExcerpt.setMaxWidth(maxWidth);

            String target = item.getReplyTargetKey();
            quote.getRoot().setOnClickListener(v -> {
                if (target != null) listener.onJumpToItem(target);
            });
            quote.getRoot().setContentDescription(itemView.getContext().getString(
                    R.string.chat_quote_cd, preview.getSenderName(), excerpt));
        }

        private String excerptOf(ReplyPreview preview) {
            Context context = itemView.getContext();
            if (!preview.isMeetingSpot()) return trim(preview.getExcerpt());
            String note = trim(preview.getExcerpt());
            return note.isEmpty()
                    ? context.getString(R.string.chat_reply_meeting_brief)
                    : context.getString(R.string.chat_reply_summary, note);
        }

        /** A proposal card or an event row between two messages ends the run. */
        private boolean isSameSenderAsPrevious(int position) {
            ChatTimelineItem prev = items.get(position - 1);
            ChatTimelineItem cur = items.get(position);
            if (prev == null || cur == null
                    || prev.getKind() != ChatTimelineItem.KIND_TEXT
                    || cur.getKind() != ChatTimelineItem.KIND_TEXT) {
                return false;
            }
            String prevSender = prev.getSenderId();
            return prevSender != null && prevSender.equals(cur.getSenderId());
        }

        private void bindAvatar(ImageView avatarView, String senderId) {
            Context context = itemView.getContext();
            String url = SupabaseConfig.getPublicAvatarUrl(
                    ProfileDirectory.getInstance().getAvatarPath(senderId));
            if (url != null) {
                Glide.with(context).clear(avatarView);
                avatarView.setImageTintList(null);
                avatarView.setPadding(0, 0, 0, 0);
                Glide.with(context)
                        .load(SupabaseConfig.getGlideUrl(url))
                        .circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .error(R.drawable.ic_person)
                        .into(avatarView);
            } else {
                Glide.with(context).clear(avatarView);
                avatarView.setImageResource(R.drawable.ic_person);
                avatarView.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.spotify_text2)));
                int padding = (int) (5 * context.getResources().getDisplayMetrics().density);
                avatarView.setPadding(padding, padding, padding, padding);
            }
        }
    }

    // ------------------------------------------------------------------
    // Meeting proposals
    // ------------------------------------------------------------------

    class ProposalHolder extends RecyclerView.ViewHolder {
        private final ItemChatProposalBinding binding;

        ProposalHolder(ItemChatProposalBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ChatTimelineItem item) {
            MeetingLocation p = item.getProposal();
            Context context = itemView.getContext();
            String who = orEmpty(listener.displayNameOf(p.getProposerId(), null));
            boolean isMine = currentUserId != null && currentUserId.equals(p.getProposerId());
            // The person who ANSWERED, which is never the proposer - the server
            // rejects a self-response. Naming the proposer here would be wrong.
            String responder = orEmpty(listener.displayNameOf(p.getRespondedBy(), null));

            binding.tvProposalBy.setText(context.getString(R.string.chat_proposal_proposed_by,
                    who.isEmpty() ? context.getString(R.string.chat_proposal_title) : who,
                    DateUtils.formatDisplayTime(p.getCreatedAt())));

            String note = trim(p.getLocationNote());
            binding.tvProposalNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
            binding.tvProposalNote.setText(note);
            binding.tvProposalCoordinates.setText(String.format(Locale.US,
                    "%.5f, %.5f", p.getLatitude(), p.getLongitude()));

            bindState(p, who, responder, isMine);

            binding.btnProposalViewMap.setOnClickListener(v -> listener.onViewMap(p));
            binding.btnProposalAccept.setOnClickListener(v -> listener.onRespond(p, true));
            binding.btnProposalReject.setOnClickListener(v -> listener.onRespond(p, false));
            bindReplyAction(binding.getRoot(), item);
        }

        /**
         * The state is stated in words before anything is stated in colour, so it
         * survives colour-blindness, greyscale and TalkBack. Green is spent only
         * on the one spot both sides agreed to meet at.
         */
        private void bindState(MeetingLocation p, String who, String responder, boolean isMine) {
            Context context = itemView.getContext();
            int stateColor = R.color.spotify_text2;
            int stateIcon = R.drawable.ic_map_pin;
            String stateLabel;
            String stateSentence;

            if (p.isCurrent()) {
                stateLabel = context.getString(R.string.chat_proposal_state_accepted_yours);
                stateColor = R.color.spotify_green;
                stateIcon = R.drawable.ic_check_circle;
                stateSentence = responder.isEmpty()
                        ? context.getString(R.string.chat_proposal_agreed_cd_plain)
                        : context.getString(R.string.chat_proposal_agreed_cd, responder);
            } else if (p.isAccepted()) {
                stateLabel = context.getString(R.string.chat_proposal_state_previous);
                stateSentence = responder.isEmpty()
                        ? context.getString(R.string.chat_proposal_state_accepted_plain)
                        : context.getString(R.string.chat_proposal_state_accepted_by, responder);
            } else if (p.isRejected()) {
                stateLabel = context.getString(R.string.chat_proposal_state_declined);
                stateColor = R.color.spotify_error;
                stateIcon = R.drawable.ic_chat_event_decline;
                stateSentence = responder.isEmpty()
                        ? context.getString(R.string.chat_proposal_state_declined_plain)
                        : context.getString(R.string.chat_proposal_state_declined_by, responder);
            } else if (p.isSuperseded()) {
                stateLabel = context.getString(R.string.chat_proposal_state_closed);
                stateSentence = context.getString(R.string.chat_proposal_state_closed_by);
            } else {
                stateLabel = context.getString(R.string.chat_proposal_state_pending);
                stateSentence = context.getString(isMine
                        ? R.string.chat_proposal_state_pending_you
                        : R.string.chat_proposal_state_pending_them);
            }

            binding.tvProposalState.setText(stateLabel);
            binding.tvProposalState.setTextColor(ContextCompat.getColor(context, stateColor));
            binding.ivProposalState.setImageResource(stateIcon);
            binding.ivProposalState.setImageTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(context, stateColor)));
            // One spoken sentence for the whole state, so it is not read as
            // "Meeting spot ... ACCEPTED ..." with the meaning left to be inferred.
            binding.layoutProposalState.setContentDescription(stateSentence);
            binding.tvProposalCurrent.setVisibility(p.isCurrent() ? View.VISIBLE : View.GONE);

            // Accept / Decline belong to the OTHER participant, and only while
            // the proposal is unanswered. The server enforces the same rule, so
            // this is the honest reflection of it rather than the only guard.
            boolean canRespond = p.isProposed() && !isMine;
            binding.btnProposalAccept.setVisibility(canRespond ? View.VISIBLE : View.GONE);
            binding.btnProposalReject.setVisibility(canRespond ? View.VISIBLE : View.GONE);
            setWeight(binding.btnProposalViewMap, canRespond ? 1f : 2f);
        }
    }

    // ------------------------------------------------------------------
    // Centered response events
    // ------------------------------------------------------------------

    class EventHolder extends RecyclerView.ViewHolder {
        private final ItemChatEventBinding binding;

        EventHolder(ItemChatEventBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ChatTimelineItem item) {
            Context context = itemView.getContext();
            String actor = orEmpty(listener.displayNameOf(item.getEvent().getActorId(), null));
            boolean accepted = item.getEvent().isAccepted();

            String sentence = context.getString(accepted
                    ? R.string.chat_event_accepted
                    : R.string.chat_event_rejected, actor);

            binding.tvEventText.setText(sentence);
            binding.ivEventIcon.setImageResource(accepted
                    ? R.drawable.ic_chat_event_accept
                    : R.drawable.ic_chat_event_decline);
            binding.ivEventIcon.setImageTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context,
                            accepted ? R.color.spotify_green : R.color.spotify_error)));

            // The event is tied to one exact proposal, and the reader can go
            // straight there.
            String meetingKey = item.getEvent().getMeetingId() != null
                    ? "p:" + item.getEvent().getMeetingId() : null;
            binding.layoutEventPill.setOnClickListener(v -> {
                if (meetingKey != null) listener.onJumpToItem(meetingKey);
            });
            binding.layoutEventPill.setContentDescription(
                    context.getString(R.string.chat_event_cd, sentence));

            // A centered system event is not a reply target: no Reply action, and
            // no swipe (ReplySwipeCallback asks isReplyableAt, which is false for
            // this kind). Stated explicitly so recycling this row can never
            // inherit the previous item's action.
            bindReplyAction(binding.getRoot(), item);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Width budget for a bubble or a quote. Falls back to the screen width before
     * the first layout pass, otherwise the first bind would clamp everything to
     * zero and the text would be invisible.
     */
    private int widthOf(View view, float fraction) {
        int width = view.getWidth();
        if (width <= 0) {
            width = view.getResources().getDisplayMetrics().widthPixels;
        }
        return (int) (width * fraction);
    }

    private static void setWeight(View view, float weight) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            lp.width = 0;
            ((LinearLayout.LayoutParams) lp).weight = weight;
            view.setLayoutParams(lp);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}

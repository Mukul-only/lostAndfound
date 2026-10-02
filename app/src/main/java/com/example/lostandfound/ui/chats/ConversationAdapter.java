package com.example.lostandfound.ui.chats;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Conversation;
import com.example.lostandfound.databinding.ItemConversationBinding;
import com.example.lostandfound.ui.common.UnreadFormatter;
import com.example.lostandfound.util.DateUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The conversation list.
 *
 * Unread emphasis is carried by TWO things at once, never by colour alone:
 * a count badge, and a stronger last-message preview (white and semibold instead
 * of silver). A reader who cannot see the green pill still sees the row change.
 *
 * Updates go through {@link #apply}, which diffs by conversation id. A five-second
 * background tick must not clear and rebuild the list: rows keep their position
 * and only the ones that actually changed rebind, so the list does not flicker or
 * throw away the reader's scroll while they are moving through it.
 */
public class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder> {

    public interface OnConversationClickListener {
        void onConversationClick(Conversation conversation);
    }

    private static final String EMPTY_PREVIEW = "Tap to coordinate safe handover...";

    private final List<Conversation> conversations = new ArrayList<>();
    private final Map<String, Integer> unreadByConversation = new HashMap<>();
    private final OnConversationClickListener listener;

    public ConversationAdapter(OnConversationClickListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    /** Replaces the whole list. Kept for pull-to-refresh and first load. */
    public void setConversations(List<Conversation> newConversations) {
        apply(newConversations, unreadByConversation);
    }

    /**
     * Applies conversations and counts together and diffs by id.
     *
     * Both are needed at once: a background tick that only changed counts must
     * still rebind, and a refresh that reordered rows must keep the counts on
     * the right rows.
     */
    public void apply(List<Conversation> newConversations, Map<String, Integer> newUnread) {
        final List<Conversation> old = new ArrayList<>(conversations);
        final Map<String, Integer> oldUnread = new HashMap<>(unreadByConversation);
        final List<Conversation> next = newConversations == null
                ? new ArrayList<Conversation>() : newConversations;
        final Map<String, Integer> counts = newUnread == null
                ? new HashMap<String, Integer>() : new HashMap<>(newUnread);

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
                return old.get(oldPos).getId() != null
                        && old.get(oldPos).getId().equals(next.get(newPos).getId());
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                Conversation a = old.get(oldPos);
                Conversation b = next.get(newPos);
                if (a == null || b == null) return false;
                return equal(a.getLastMessage(), b.getLastMessage())
                        && equal(a.getLastMessageAt(), b.getLastMessageAt())
                        && countOf(oldUnread, a.getId()) == countOf(counts, b.getId());
            }
        });

        conversations.clear();
        conversations.addAll(next);
        unreadByConversation.clear();
        unreadByConversation.putAll(counts);
        diff.dispatchUpdatesTo(this);
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int countOf(Map<String, Integer> counts, String conversationId) {
        return UnreadFormatter.countFor(counts, conversationId);
    }

    /** Convenience for the badge only. */
    private int unreadAt(int position) {
        return countOf(unreadByConversation, conversations.get(position).getId());
    }

    @Override
    public long getItemId(int position) {
        String id = conversations.get(position).getId();
        return id == null ? RecyclerView.NO_ID : id.hashCode();
    }

    @NonNull
    @Override
    public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemConversationBinding binding = ItemConversationBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ConversationViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ConversationViewHolder holder, int position) {
        holder.bind(conversations.get(position), unreadAt(position));
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    class ConversationViewHolder extends RecyclerView.ViewHolder {
        private final ItemConversationBinding binding;

        ConversationViewHolder(ItemConversationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Conversation conversation, int unread) {
            binding.tvConvTitle.setText(conversation.getReportTitle());
            binding.tvConvTime.setText(DateUtils.formatDisplayTime(conversation.getLastMessageAt()));

            String lastMsg = conversation.getLastMessage();
            binding.tvConvLastMessage.setText(
                    (lastMsg != null && !lastMsg.trim().isEmpty()) ? lastMsg : EMPTY_PREVIEW);

            boolean hasUnread = UnreadFormatter.shouldShow(unread);
            if (hasUnread) {
                binding.tvConvUnreadBadge.setVisibility(View.VISIBLE);
                binding.tvConvUnreadBadge.setText(UnreadFormatter.badge(unread));
            } else {
                binding.tvConvUnreadBadge.setVisibility(View.GONE);
            }

            // Emphasis: white + semibold preview instead of silver + regular.
            // Independent of the badge, so the state is legible without colour.
            android.graphics.Typeface previewTypeface = hasUnread
                    ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT;
            binding.tvConvLastMessage.setTypeface(previewTypeface);
            binding.tvConvLastMessage.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    hasUnread ? R.color.spotify_text : R.color.spotify_text2));

            // One spoken summary for the row, so a screen reader announces the
            // count once instead of reading the pill and the preview separately.
            binding.getRoot().setContentDescription(hasUnread
                    ? itemView.getContext().getString(R.string.chats_row_cd_unread,
                            conversation.getReportTitle(), unread)
                    : itemView.getContext().getString(R.string.chats_row_cd_read,
                            conversation.getReportTitle()));

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onConversationClick(conversation);
                }
            });
        }
    }
}
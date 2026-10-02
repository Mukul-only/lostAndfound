package com.example.lostandfound.ui.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.lostandfound.R;
import com.example.lostandfound.data.model.ChatTimeline;
import com.example.lostandfound.data.model.ChatTimelineItem;
import com.example.lostandfound.data.model.Conversation;
import com.example.lostandfound.data.model.MeetingLocation;
import com.example.lostandfound.data.model.MeetingResponseEvent;
import com.example.lostandfound.data.model.Message;
import com.example.lostandfound.data.model.ReplyPreview;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.repository.ChatRepository;
import com.example.lostandfound.data.repository.ProfileDirectory;
import com.example.lostandfound.data.repository.UnreadRepository;
import com.example.lostandfound.databinding.ActivityChatBinding;
import com.example.lostandfound.databinding.ViewChatQuoteBinding;
import com.example.lostandfound.ui.map.LocationPickerActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ChatActivity: the 1-on-1 handover conversation.
 *
 * The whole conversation is one chronological timeline of three row types (text,
 * meeting proposal, response event), so nothing about a meeting lives in a
 * header panel any more - proposing, accepting and declining all happen in place.
 *
 * REFRESH STRATEGY (the part that used to be fragile):
 *  - foreground-only polling between onResume and onPause;
 *  - a poll never overlaps the previous one, and a response is discarded if a
 *    newer poll started in the meantime, so a slow reply can never revert newer
 *    state;
 *  - rows are keyed by id and diffed, so a tick rebinds only what changed;
 *  - the reading position is held by item id + offset, not by index, so history
 *    loading above or below never yanks the view;
 *  - new content is followed only when the reader was already at the end.
 */
public class ChatActivity extends AppCompatActivity implements MessageAdapter.Listener {

    private static final long FOREGROUND_POLL_INTERVAL_MS = 3500;
    /**
     * How close to the true bottom of the list counts as "at the bottom", in
     * pixels. Deliberately NOT a row count: a meeting proposal card is ~200dp
     * tall, so a two-row window puts an accepted proposal - which sits directly
     * above the newest message - within reach of the last row, and the poll then
     * yanks the reader to the end the moment they jump to it.
     */
    private static final int BOTTOM_THRESHOLD_PX = 160;
    private static final long HIGHLIGHT_MS = 1100L;

    private static final String STATE_DRAFT = "state_draft";
    private static final String STATE_REPLY_KEY = "state_reply_key";

    private ActivityChatBinding binding;
    private ChatRepository chatRepository;
    private SessionManager sessionManager;
    private MessageAdapter adapter;
    private LinearLayoutManager layoutManager;

    private String conversationId;
    private String reportTitle;
    private String claimId;

    /** The row the composer is replying to. Null when sending a plain message. */
    private ChatTimelineItem replyTarget;
    /** Where the reader was, by row id, so diffs can restore the exact position. */
    private String anchorKey;
    private int anchorOffset;
    /** A reply target restored from saved state, applied once the rows land. */
    private String pendingReplyKeyAfterLoad;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean isPollingActive;
    private boolean isSending;
    private boolean isLoading;
    /**
     * A load was asked for while one was in flight. Without this, acting on a
     * proposal or sending a message during a poll would be invisible until the
     * next tick, which is exactly the action the reader wants to see confirmed.
     */
    private boolean pendingReload;
    private boolean hasLoadedOnce;
    /**
     * Set when the reader is parked somewhere they asked to be (a jump to a
     * quoted original, or a meeting spot) and must not be dragged to the end by
     * a poll until they scroll again. Cleared on the first real user drag.
     */
    private boolean isPinnedToPosition;
    /** Last instant handed to mark_conversation_read, so a poll does not resend it. */
    private String lastMarkedReadAt;
    private UnreadRepository unreadRepository;
    /** True while a finger is actually dragging the list, not on a tap. */
    private boolean isUserDragging;
    /** Pixels travelled by that finger, so a tap cannot count as a scroll. */
    private int userDragDistance;
    /**
     * Set while the list is moving because WE asked it to (a jump, an anchor
     * restore, a follow). Scrolls we cause must never be mistaken for the reader
     * choosing to go somewhere.
     */
    private boolean isProgrammaticScroll;
    /** Incremented per poll; a response from an older poll is thrown away. */
    private long pollSeq;
    /** Proposals with a response in flight, so repeated taps cannot double-send. */
    private final Set<String> respondingMeetings = new HashSet<>();
    private List<ChatTimelineItem> timeline = Collections.emptyList();

    private final ActivityResultLauncher<Intent> meetingPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null && conversationId != null) {
                    proposeMeetingSpot(
                            data.getDoubleExtra(LocationPickerActivity.RESULT_LATITUDE, 0.0),
                            data.getDoubleExtra(LocationPickerActivity.RESULT_LONGITUDE, 0.0),
                            data.getStringExtra(LocationPickerActivity.RESULT_NOTE));
                }
            });

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPollingActive && conversationId != null) {
                loadTimeline(false);
                refreshHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MS);
            }
        }
    };

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        chatRepository = new ChatRepository(this);
        sessionManager = SessionManager.getInstance(this);
        unreadRepository = UnreadRepository.getInstance(this);

        conversationId = getIntent().getStringExtra("conversation_id");
        reportTitle = getIntent().getStringExtra("report_title");
        claimId = getIntent().getStringExtra("claim_id");

        setupInsets();
        setupRecyclerView();
        setupComposer();
        binding.btnChatBack.setOnClickListener(v -> finish());

        if (reportTitle != null) {
            binding.tvChatTitle.setText(reportTitle);
        }

        // Unsent text and the reply target both survive recreation, so rotating
        // or a process restart cannot silently discard a half-typed reply.
        if (savedInstanceState != null) {
            binding.etMessageInput.setText(savedInstanceState.getString(STATE_DRAFT, ""));
            pendingReplyKeyAfterLoad = savedInstanceState.getString(STATE_REPLY_KEY, null);
        }

        if (conversationId == null && claimId != null) {
            resolveConversationByClaimId();
        } else if (conversationId != null) {
            loadTimeline(true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        CharSequence draft = binding.etMessageInput.getText();
        outState.putString(STATE_DRAFT, draft == null ? "" : draft.toString());
        outState.putString(STATE_REPLY_KEY, replyTarget == null ? null : replyTarget.getKey());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (conversationId != null) {
            loadTimeline(false);
            startForegroundPolling();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopForegroundPolling();
        // Re-evaluate read state on the way back in rather than trusting a
        // cursor position captured before the screen went away.
        lastMarkedReadAt = null;
        // Invalidate anything still in flight so a late reply cannot touch a
        // paused screen.
        pollSeq++;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacksAndMessages(null);
    }

    private void startForegroundPolling() {
        if (!isPollingActive && conversationId != null) {
            isPollingActive = true;
            refreshHandler.postDelayed(pollRunnable, FOREGROUND_POLL_INTERVAL_MS);
        }
    }

    private void stopForegroundPolling() {
        isPollingActive = false;
        refreshHandler.removeCallbacks(pollRunnable);
    }

    /** Keeps the composer clear of the gesture bar / IME. */
    private void setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutComposer, (v, insets) -> {
            int bottom = Math.max(
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom + dp(8));
            return insets;
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Recycler view
    // ------------------------------------------------------------------

    private void setupRecyclerView() {
        adapter = new MessageAdapter(sessionManager.getUserId(), this);
        layoutManager = new LinearLayoutManager(this);
        // NOT using stackFromEnd: it causes every DiffUtil layout pass to
        // re-anchor from the bottom, overriding any manual scroll position.
        // scrollToEnd() on the initial load handles starting at the bottom.
        binding.rvMessages.setLayoutManager(layoutManager);
        binding.rvMessages.setAdapter(adapter);

        // Release the pin only when the reader deliberately scrolls back to the
        // bottom. Both halves of that matter:
        //  - onScrollStateChanged/DRAGGING alone is not enough. RecyclerView
        //    reports DRAGGING from onInterceptTouchEvent for a plain TAP on a
        //    child, so tapping the quote to jump to a message cleared the pin
        //    before the jump had even settled.
        //  - onScrolled alone is not enough either. It fires for our OWN
        //    programmatic scrolls, and the jump target is an accepted proposal
        //    sitting one row above the newest message - so "near bottom" was
        //    true, the listener cleared the pin the instant the jump landed, and
        //    the next poll took the follow path and dragged the reader back down.
        //    Once per tick, forever.
        // So: require a real user drag AND distance travelled, and ignore the
        // scrolls we caused ourselves.
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserDragging = true;
                    userDragDistance = 0;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    isUserDragging = false;
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (isProgrammaticScroll) return;      // our own jump / restore
                if (isUserDragging) {
                    userDragDistance += Math.abs(dy);
                    if (userDragDistance < dp(24)) return;   // a tap or a nudge
                } else {
                    return;                              // not a finger on the glass
                }
                if (isNearBottom()) {
                    if (isPinnedToPosition) {
                        Log.d("ChatScroll", "user dragged to bottom — releasing pin");
                    }
                    isPinnedToPosition = false;
                    hideNewMessagesIndicator();
                }
            }
        });

        binding.btnNewMessages.setOnClickListener(v -> {
            isPinnedToPosition = false;
            hideNewMessagesIndicator();
            scrollToEnd();
        });

        ReplySwipeCallback swipe = new ReplySwipeCallback(
                position -> onReplySelected(adapter.getItem(position)),
                new ReplySwipeCallback.RowInfo() {
                    @Override
                    public boolean isReplyable(int position) {
                        return adapter.isReplyableAt(position);
                    }

                    @Override
                    public boolean isOutgoing(int position) {
                        return adapter.isOutgoingAt(position);
                    }
                });
        new ItemTouchHelper(swipe).attachToRecyclerView(binding.rvMessages);
    }

    private void showNewMessagesIndicator() {
        binding.btnNewMessages.setVisibility(View.VISIBLE);
    }

    private void hideNewMessagesIndicator() {
        binding.btnNewMessages.setVisibility(View.GONE);
    }

    private void setupComposer() {
        binding.btnSendMessage.setOnClickListener(v -> sendCurrentText());
        binding.btnCancelReply.setOnClickListener(v -> clearReplyTarget());
        // The single entry point for proposing a spot now lives here, not in the
        // header. The picker keeps its draggable pin, campus boundary rule and
        // "Meet" marker label; only the trigger moved.
        binding.btnProposeMeeting.setOnClickListener(v -> launchMeetingPicker());

        binding.etMessageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateSendAffordance();
            }
        });
        updateSendAffordance();
    }

    private void updateSendAffordance() {
        boolean hasText = binding.etMessageInput.getText() != null
                && binding.etMessageInput.getText().toString().trim().length() > 0;
        binding.btnSendMessage.setEnabled(hasText && !isSending);
        binding.btnSendMessage.setAlpha(hasText && !isSending ? 1f : 0.4f);
    }

    // ------------------------------------------------------------------
    // Timeline loading
    // ------------------------------------------------------------------

    /**
     * One poll = one consistent-enough snapshot of all three sources. Each read
     * reports into the same latch; only the last one to arrive merges, so a
     * partial result can never be rendered. Out-of-order responses are dropped by
     * the sequence number rather than overwriting newer state.
     */
    private void loadTimeline(boolean initialLoad) {
        if (conversationId == null) return;
        if (isLoading) {
            // Never overlap polls. Remember the request instead and honour it the
            // moment the in-flight one lands, so a newer intent is never lost.
            pendingReload |= initialLoad;
            return;
        }
        isLoading = true;

        final long seq = ++pollSeq;
        final TimelineFetch fetch = new TimelineFetch(seq, initialLoad);

        chatRepository.getMessages(conversationId, new ChatRepository.DataCallback<List<Message>>() {
            @Override
            public void onSuccess(List<Message> data) {
                fetch.messages = data;
                fetch.deliver();
            }

            @Override
            public void onError(String message) {
                fetch.failed = true;
                fetch.deliver();
            }
        });

        chatRepository.getMeetingProposals(conversationId, new ChatRepository.DataCallback<List<MeetingLocation>>() {
            @Override
            public void onSuccess(List<MeetingLocation> data) {
                fetch.proposals = data;
                fetch.deliver();
            }

            @Override
            public void onError(String message) {
                fetch.failed = true;
                fetch.deliver();
            }
        });

        chatRepository.getMeetingResponseEvents(conversationId, new ChatRepository.DataCallback<List<MeetingResponseEvent>>() {
            @Override
            public void onSuccess(List<MeetingResponseEvent> data) {
                fetch.events = data;
                fetch.deliver();
            }

            @Override
            public void onError(String message) {
                fetch.failed = true;
                fetch.deliver();
            }
        });
    }

    /** Latch for the three parallel reads of one poll. */
    private final class TimelineFetch {
        private final long seq;
        private final boolean initialLoad;
        private int outstanding = 3;
        private boolean delivered;
        private boolean failed;
        List<Message> messages;
        List<MeetingLocation> proposals;
        List<MeetingResponseEvent> events;

        TimelineFetch(long seq, boolean initialLoad) {
            this.seq = seq;
            this.initialLoad = initialLoad;
        }

        void deliver() {
            if (delivered || --outstanding > 0) return;
            delivered = true;
            isLoading = false;

            boolean reloading = pendingReload;
            pendingReload = false;

            // A newer poll started, or the screen is gone: this snapshot is stale.
            if (seq != pollSeq || isDead()) return;
            // A failed tick is silent: the rows already on screen are still the
            // truth we last knew, and a transient network blip must not toast-spam.
            if (failed || messages == null) return;

            boolean nearBottom = isNearBottom();
            final boolean follow = ChatScrollPolicy.shouldFollow(
                    initialLoad || reloading, isPinnedToPosition, nearBottom);
            Log.d("ChatScroll", "deliver: initialLoad=" + initialLoad
                    + " reloading=" + reloading
                    + " isPinned=" + isPinnedToPosition
                    + " nearBottom=" + nearBottom
                    + " → follow=" + follow);
            captureAnchor();
            Log.d("ChatScroll", "deliver: anchorKey=" + anchorKey + " anchorOffset=" + anchorOffset);

            int oldCount = adapter.getItemCount();
            timeline = ChatTimeline.merge(messages, proposals, events, ChatActivity.this::nameOf);
            int newCount = timeline.size();

            submitTimeline(follow);
            prefetchNames();
            if (reloading) refreshHandler.post(ChatActivity.this::reloadNow);

            if (!follow && newCount > oldCount && oldCount > 0) {
                showNewMessagesIndicator();
            }
        }
    }

    private void submitTimeline(boolean follow) {
        boolean changed = adapter.submit(timeline);
        Log.d("ChatScroll", "submitTimeline: follow=" + follow + " changed=" + changed);
        hasLoadedOnce = true;
        renderEmptyState();

        if (pendingReplyKeyAfterLoad != null) {
            String key = pendingReplyKeyAfterLoad;
            pendingReplyKeyAfterLoad = null;
            int pos = adapter.indexOf(key);
            if (pos != RecyclerView.NO_POSITION) onReplySelected(timeline.get(pos));
        }

        // Nothing changed, so the list already shows the right rows and the
        // reader is already looking at the right part of them. Re-anchoring on an
        // unchanged diff is what made the conversation drift downwards once per
        // poll tick: restoreAnchor() calls scrollToPositionWithOffset, which
        // forces the LayoutManager to re-anchor on the next layout pass and
        // fights stackFromEnd's own bottom alignment. Leave an unchanged list
        // completely alone.
        if (!changed) {
            Log.d("ChatScroll", "submitTimeline: unchanged, scroll untouched");
            return;
        }

        if (follow) {
            Log.d("ChatScroll", "submitTimeline: changed -> scrollToEnd");
            scrollToEnd();
        } else {
            Log.d("ChatScroll", "submitTimeline: changed -> posting restoreAnchor");
            binding.rvMessages.post(this::restoreAnchor);
        }

        maybeAdvanceReadCursor();
    }

    /**
     * Marks incoming content read, but only when it has actually been seen.
     *
     * The gate is the same "is the reader at the live edge" signal that decides
     * whether to follow new content: if they are reading older messages, or have
     * jumped to a quote, nothing is marked. That is what stops opening a
     * conversation from silently clearing unseen messages.
     *
     * Two consequences that matter:
     *  - it only ever sends the newest INCOMING item's timestamp, so the reader's
     *    own sends are never counted and never marked;
     *  - it runs AFTER the scroll decision and touches no layout state, so it
     *    cannot cause the drift the poll fix had to eliminate.
     */
    private void maybeAdvanceReadCursor() {
        if (conversationId == null || isDead()) return;
        if (!isNearBottom() || isPinnedToPosition) return;

        String newestIncoming = newestIncomingCreatedAt();
        if (newestIncoming == null) return;   // nothing of theirs to have read
        if (newestIncoming.equals(lastMarkedReadAt)) return;   // already sent

        lastMarkedReadAt = newestIncoming;
        chatRepository.markConversationRead(conversationId, newestIncoming,
                new ChatRepository.DataCallback<RpcResponse>() {
                    @Override
                    public void onSuccess(RpcResponse data) {
                        if (isDead()) return;
                        // The counts are now stale by design; pull the truth so
                        // the nav badge clears the moment they are read.
                        unreadRepository.refresh();
                    }

                    @Override
                    public void onError(String message) {
                        // Silent: read state is best-effort, and the next tick or
                        // the next visit will retry. Never blocks the conversation.
                        lastMarkedReadAt = null;
                    }
                });
    }

    /** Newest item in the loaded timeline that was NOT authored by this reader. */
    private String newestIncomingCreatedAt() {
        String me = sessionManager.getUserId();
        if (me == null) return null;
        String newest = null;
        for (ChatTimelineItem item : timeline) {
            if (item.getKind() == ChatTimelineItem.KIND_EVENT) {
                String actor = item.getEvent().getActorId();
                if (me.equals(actor)) continue;
            } else {
                String sender = item.getSenderId();
                if (sender == null || me.equals(sender)) continue;
            }
            String createdAt = item.getCreatedAt();
            if (createdAt != null && (newest == null || createdAt.compareTo(newest) > 0)) {
                newest = createdAt;
            }
        }
        return newest;
    }

    /** Runs the load that was deferred because a poll was already in flight. */
    private void reloadNow() {
        if (isDead()) return;
        loadTimeline(false);
    }

    private void renderEmptyState() {
        boolean empty = timeline.isEmpty();
        binding.tvChatEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.tvChatEmpty.setText(hasLoadedOnce ? R.string.chat_empty : R.string.chat_loading);
    }

    /** Load the names this timeline needs, then re-resolve quotes in place. */
    private void prefetchNames() {
        Set<String> ids = new LinkedHashSet<>();
        for (ChatTimelineItem item : timeline) {
            if (item.getSenderId() != null) ids.add(item.getSenderId());
            if (item.getEvent() != null && item.getEvent().getActorId() != null) {
                ids.add(item.getEvent().getActorId());
            }
        }
        if (ids.isEmpty()) return;
        ProfileDirectory.getInstance().prefetch(this, ids, () -> {
            if (isDead()) return;

            // Re-evaluate the scroll decision right now, not from when the
            // prefetch was queued - the user may have scrolled in the meantime.
            boolean nearBottom = isNearBottom();
            boolean follow = ChatScrollPolicy.shouldFollow(
                    false, isPinnedToPosition, nearBottom);
            Log.d("ChatScroll", "prefetchNames cb: isPinned=" + isPinnedToPosition
                    + " nearBottom=" + nearBottom + " → follow=" + follow);
            captureAnchor();

            // Re-merge only: same rows, better display names. No network, no new
            // id, so DiffUtil rebinds just the affected rows.
            timeline = ChatTimeline.merge(collectMessages(), collectProposals(),
                    collectEvents(), ChatActivity.this::nameOf);
            // Same rule as submitTimeline: a re-merge that changed nothing (the
            // names were already cached) must not touch the scroll. This ran on
            // every single poll, so it was a second source of the same drift.
            if (!adapter.submit(timeline)) {
                Log.d("ChatScroll", "prefetchNames cb: unchanged, scroll untouched");
                return;
            }
            if (follow) {
                scrollToEnd();
            } else {
                binding.rvMessages.post(ChatActivity.this::restoreAnchor);
            }
        });
    }

    private List<Message> collectMessages() {
        List<Message> out = new ArrayList<>();
        for (ChatTimelineItem item : timeline) {
            if (item.getMessage() != null) out.add(item.getMessage());
        }
        return out;
    }

    private List<MeetingLocation> collectProposals() {
        List<MeetingLocation> out = new ArrayList<>();
        for (ChatTimelineItem item : timeline) {
            if (item.getProposal() != null) out.add(item.getProposal());
        }
        return out;
    }

    private List<MeetingResponseEvent> collectEvents() {
        List<MeetingResponseEvent> out = new ArrayList<>();
        for (ChatTimelineItem item : timeline) {
            if (item.getEvent() != null) out.add(item.getEvent());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Scroll position
    // ------------------------------------------------------------------

    /**
     * Anchored to the adapter's list, not ours: an optimistic own-send can make
     * the two differ, and the adapter's is what the screen is actually showing.
     */
    private void captureAnchor() {
        int first = layoutManager.findFirstVisibleItemPosition();
        ChatTimelineItem firstItem = adapter.getItem(first);
        if (firstItem == null) {
            anchorKey = null;
            return;
        }
        anchorKey = firstItem.getKey();
        View firstView = layoutManager.findViewByPosition(first);
        anchorOffset = firstView == null ? 0 : firstView.getTop() - binding.rvMessages.getPaddingTop();
    }

    private void restoreAnchor() {
        if (anchorKey == null) {
            Log.d("ChatScroll", "restoreAnchor: anchorKey is null, skip");
            return;
        }
        int pos = adapter.indexOf(anchorKey);
        if (pos == RecyclerView.NO_POSITION) {
            Log.d("ChatScroll", "restoreAnchor: key=" + anchorKey + " NOT FOUND, isPinned=" + isPinnedToPosition);
            // If the user explicitly navigated somewhere (pinned), do NOT yank
            // them to the bottom just because the anchor row disappeared.
            if (!isPinnedToPosition) scrollToEnd();
            return;
        }
        Log.d("ChatScroll", "restoreAnchor: key=" + anchorKey + " pos=" + pos + " offset=" + anchorOffset);
        final int targetPos = pos;
        final int targetOffset = anchorOffset;
        withProgrammaticScroll(() ->
                layoutManager.scrollToPositionWithOffset(targetPos, targetOffset));
    }

    private boolean isNearBottom() {
        if (adapter.getItemCount() == 0) return true;
        int last = layoutManager.findLastVisibleItemPosition();
        if (last == RecyclerView.NO_POSITION) return true;

        View lastView = layoutManager.findViewByPosition(last);
        if (lastView == null) return false;
        return ChatScrollPolicy.isNearBottom(
                binding.rvMessages.getHeight(), lastView.getBottom(),
                binding.rvMessages.getPaddingBottom(), BOTTOM_THRESHOLD_PX);
    }

    private void scrollToEnd() {
        // Hard guard: if the user navigated to an older message, nothing is
        // allowed to yank them to the bottom until they clear the pin.
        if (isPinnedToPosition) {
            Log.d("ChatScroll", "scrollToEnd: BLOCKED by isPinned");
            return;
        }
        int count = adapter.getItemCount();
        if (count > 0) {
            withProgrammaticScroll(() -> binding.rvMessages.scrollToPosition(count - 1));
        }
    }

    /**
     * Runs a scroll we asked for and marks the window as ours, so the scroll
     * listener does not read it as the reader choosing to move. The flag is
     * cleared after the layout pass that performs the scroll.
     */
    private void withProgrammaticScroll(Runnable action) {
        isProgrammaticScroll = true;
        action.run();
        binding.rvMessages.post(() -> binding.rvMessages.post(() -> {
            isProgrammaticScroll = false;
        }));
    }

    /** Scroll to a row and flash it, so "jump to the original" is visible. */
    private void jumpTo(String key) {
        int pos = adapter.indexOf(key);
        if (pos == RecyclerView.NO_POSITION) {
            showStatus(getString(R.string.chat_quote_unavailable), false);
            return;
        }
        Log.d("ChatScroll", "jumpTo: key=" + key + " pos=" + pos + " setting isPinned=true");
        // The reader asked to be here, so pin the view here: the next poll must
        // leave them on this row instead of following the conversation down.
        isPinnedToPosition = true;
        // The jump is OURS. Without this the scroll listener sees the list move,
        // finds it near the bottom (an accepted proposal sits one row above the
        // newest message), and releases the pin immediately - which is what made
        // the next poll drag the reader back down, once per tick.
        withProgrammaticScroll(() -> layoutManager.scrollToPositionWithOffset(pos, 0));
        binding.rvMessages.post(() -> {
            RecyclerView.ViewHolder holder = binding.rvMessages
                    .findViewHolderForAdapterPosition(pos);
            if (holder == null || isDead()) return;
            highlight(holder);
            holder.itemView.announceForAccessibility(getString(R.string.chat_quote_jump_cd));
        });
    }

    /**
     * Outlines the MESSAGE, not the row.
     *
     * The flash is a foreground, not a background swap, for two reasons: a
     * background swap would replace the bubble's own shape - whose padding is what
     * holds the text off the edge - so the text would jump for the length of the
     * flash; and it would replace a MaterialCardView's card background on proposal
     * rows. A foreground sits on top, changes no measurement, and is restored to
     * whatever was there before.
     *
     * Only the bubble is outlined. The avatar, the sender name and the timestamp
     * are outside it and stay dark, so the highlight reads as "this is the message
     * you quoted" instead of lighting up the entire list item.
     */
    private void highlight(@NonNull RecyclerView.ViewHolder holder) {
        View target = adapter.highlightTargetOf(holder);
        final android.graphics.drawable.Drawable previous = target.getForeground();
        int outline = adapter.isOutgoingHolder(holder)
                ? R.drawable.bg_chat_highlight_bubble_out
                : R.drawable.bg_chat_highlight_bubble_in;
        target.setForeground(ContextCompat.getDrawable(this, outline));
        target.postDelayed(() -> target.setForeground(previous), HIGHLIGHT_MS);
    }

    // ------------------------------------------------------------------
    // MessageAdapter.Listener
    // ------------------------------------------------------------------

    /**
     * A row became the reply target - from a right swipe, or from the row's
     * "Reply" accessibility action for readers who cannot swipe.
     *
     * Reached either way, so the composer behaves identically: the preview
     * appears above the input, anything already typed is untouched (this only
     * ever reads and renders - it never writes to the field), and selecting a
     * second row simply replaces the target.
     */
    @Override
    public void onReplySelected(ChatTimelineItem target) {
        if (target == null) return;
        replyTarget = target;
        renderReplyPreview();
        showComposerKeyboard();
    }

    /**
     * Puts the caret in the composer and raises the keyboard, so a reply can be
     * typed straight after the swipe. requestFocus() alone only moves focus -
     * the IME stays down - so showSoftInput is asked for explicitly, and posted
     * so it runs once this gesture's layout has settled rather than fighting the
     * in-flight scroll.
     */
    private void showComposerKeyboard() {
        final View field = binding.etMessageInput;
        field.requestFocus();
        field.post(() -> {
            if (isDead()) return;
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    @Override
    public void onJumpToItem(String key) {
        jumpTo(key);
    }

    @Override
    public void onViewMap(MeetingLocation proposal) {
        if (proposal == null) return;
        Intent intent = new Intent(this, LocationPickerActivity.class);
        intent.putExtra(LocationPickerActivity.EXTRA_MODE, LocationPickerActivity.MODE_VIEW_LOCATION);
        intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LAT, proposal.getLatitude());
        intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LNG, proposal.getLongitude());
        intent.putExtra(LocationPickerActivity.EXTRA_TITLE, LocationPickerActivity.LABEL_MEETING);
        intent.putExtra(LocationPickerActivity.EXTRA_NOTE, proposal.getLocationNote());
        startActivity(intent);
    }

    @Override
    public void onRespond(MeetingLocation proposal, boolean accept) {
        if (proposal == null || proposal.getId() == null) return;
        // One in-flight response per proposal: repeated taps are ignored rather
        // than queued, and the server would reject the second anyway.
        if (respondingMeetings.contains(proposal.getId())) return;
        respondingMeetings.add(proposal.getId());
        showStatus(getString(R.string.chat_responding), false);

        chatRepository.respondToMeetingProposal(proposal.getId(),
                accept ? MeetingResponseEvent.RESPONSE_ACCEPTED
                       : MeetingResponseEvent.RESPONSE_REJECTED,
                new ChatRepository.DataCallback<RpcResponse>() {
                    @Override
                    public void onSuccess(RpcResponse data) {
                        respondingMeetings.remove(proposal.getId());
                        if (isDead()) return;
                        hideStatus();
                        Toast.makeText(ChatActivity.this,
                                accept ? R.string.chat_accepted : R.string.chat_declined,
                                Toast.LENGTH_SHORT).show();
                        unreadRepository.refresh();
                        loadTimeline(false);
                    }

                    @Override
                    public void onError(String message) {
                        respondingMeetings.remove(proposal.getId());
                        if (isDead()) return;
                        // A stale card is the common case: someone answered first.
                        // Re-read so the card settles on the real state, and say so
                        // rather than showing a raw constraint message.
                        boolean stale = isStaleProposalError(message);
                        showStatus(stale
                                ? getString(R.string.chat_respond_stale)
                                : getString(R.string.chat_respond_failed, message),
                                !stale);
                        loadTimeline(false);
                    }
                });
    }

    private boolean isDead() {
        return isFinishing() || isDestroyed();
    }

    private boolean isStaleProposalError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.US);
        return lower.contains("already")
                || lower.contains("cannot respond to your own")
                || lower.contains("only conversation participants");
    }

    @Override
    public String displayNameOf(String userId, String fallback) {
        if (userId == null) return fallback;
        return ProfileDirectory.getInstance().getDisplayName(userId, fallback);
    }

    private String nameOf(String userId) {
        return displayNameOf(userId, null);
    }

    // ------------------------------------------------------------------
    // Composer
    // ------------------------------------------------------------------

    private void renderReplyPreview() {
        ReplyPreview preview = replyTarget == null ? null : replyTarget.getReplyPreview();
        if (preview == null && replyTarget != null) {
            // The target was never merged with a resolver (e.g. a synthetic item).
            preview = new ReplyPreview(
                    displayNameOf(replyTarget.getSenderId(), null),
                    replyTarget.getProposal() != null
                            ? replyTarget.getProposal().getLocationNote()
                            : replyTarget.getContent(),
                    replyTarget.getKind() == ChatTimelineItem.KIND_PROPOSAL);
        }
        if (preview == null) {
            binding.layoutReplyPreview.setVisibility(View.GONE);
            return;
        }
        ViewChatQuoteBinding bar = binding.replyPreview;
        bar.tvQuoteSender.setText(displayNameOf(replyTarget.getSenderId(), preview.getSenderName()));
        bar.tvQuoteExcerpt.setText(composerExcerpt(preview));
        // Inert here: the whole point of the composer preview is to show what
        // will be attached, and there is nothing to navigate to yet.
        bar.getRoot().setOnClickListener(null);
        bar.getRoot().setContentDescription(getString(R.string.chat_reply_to,
                bar.tvQuoteSender.getText()));
        binding.layoutReplyPreview.setVisibility(View.VISIBLE);
    }

    private String composerExcerpt(ReplyPreview preview) {
        if (!preview.isMeetingSpot()) {
            String text = preview.getExcerpt();
            return text == null ? "" : text.trim();
        }
        String note = preview.getExcerpt() == null ? "" : preview.getExcerpt().trim();
        return note.isEmpty()
                ? getString(R.string.chat_reply_meeting_brief)
                : getString(R.string.chat_reply_summary, note);
    }

    private void clearReplyTarget() {
        replyTarget = null;
        pendingReplyKeyAfterLoad = null;
        binding.layoutReplyPreview.setVisibility(View.GONE);
    }

    private void sendCurrentText() {
        if (isSending || conversationId == null) return; // Ignore rapid double taps.
        String text = binding.etMessageInput.getText() != null
                ? binding.etMessageInput.getText().toString().trim() : "";
        if (text.isEmpty()) return;

        final ChatTimelineItem target = replyTarget;
        final String replyMessageId = target != null && target.getMessage() != null
                ? target.getMessage().getId() : null;
        final String replyMeetingId = target != null && target.getProposal() != null
                ? target.getProposal().getId() : null;

        isSending = true;
        binding.btnSendMessage.setEnabled(false);
        binding.etMessageInput.setText("");
        clearReplyTarget();
        showStatus(getString(R.string.chat_sending), false);

        chatRepository.sendMessage(conversationId, text, replyMessageId, replyMeetingId,
                new ChatRepository.DataCallback<Message>() {
                    @Override
                    public void onSuccess(Message message) {
                        isSending = false;
                        if (isDead()) return;
                        hideStatus();
                        updateSendAffordance();
                        if (message == null) return;
                        
                        isPinnedToPosition = false;
                        hideNewMessagesIndicator();
                        
                        // Show it now rather than waiting a full poll cycle. The
                        // next poll merges the same row by id, so it will not
                        // appear twice.
                        ChatTimelineItem sent = ChatTimelineItem.text(message);
                        ReplyPreview preview = target == null ? null : target.getReplyPreview();
                        if (preview != null) sent.setReplyPreview(preview);

                        // Put the row in OUR timeline too, not just the adapter's.
                        // Otherwise the next poll would diff it away and the card
                        // would blink out and back in; with it in both lists the
                        // poll's server copy matches by id and only rebinds.
                        List<ChatTimelineItem> next = new ArrayList<>(timeline);
                        next.add(sent);
                        timeline = next;
                        adapter.submit(timeline);
                        // Sending is an explicit move to the newest message, so
                        // it releases the pin and follows. The reader asked for
                        // this by sending.
                        isPinnedToPosition = false;
                        hideNewMessagesIndicator();
                        scrollToEnd();
                        // Our own send clears nothing for us, but the other
                        // side's counts are now stale either way.
                        unreadRepository.refresh();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        isSending = false;
                        if (isDead()) return;
                        updateSendAffordance();
                        // Put both halves back: the text, and the quote it was
                        // meant to answer. A retry without the quote would send a
                        // different message than the one the reader composed.
                        replyTarget = target;
                        renderReplyPreview();
                        restoreFailedText(text, errorMessage);
                    }
                });
    }

    /**
     * A failed send must not eat the message, and must not clobber anything the
     * user typed in the meantime: an empty composer is restored exactly,
     * otherwise the failed text is kept alongside the newer text.
     */
    private void restoreFailedText(String failedText, String errorMessage) {
        CharSequence current = binding.etMessageInput.getText();
        boolean composerEmpty = current == null || current.toString().trim().isEmpty();
        String restored = composerEmpty ? failedText : current + "\n" + failedText;

        binding.etMessageInput.setText(restored);
        binding.etMessageInput.setSelection(binding.etMessageInput.getText().length());
        showStatus(getString(R.string.chat_send_failed_kept, errorMessage), true);
    }

    private void showStatus(String message, boolean isError) {
        if (message == null) return;
        binding.tvComposerStatus.setText(message);
        binding.tvComposerStatus.setTextColor(getColor(
                isError ? R.color.spotify_error : R.color.spotify_text2));
        binding.tvComposerStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        binding.tvComposerStatus.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    // Meeting proposals
    // ------------------------------------------------------------------

    /** The single entry point: the composer map icon. */
    private void launchMeetingPicker() {
        if (conversationId == null) return;
        MeetingLocation seed = currentAgreedSpot();
        Intent intent = new Intent(this, LocationPickerActivity.class);
        intent.putExtra(LocationPickerActivity.EXTRA_MODE,
                LocationPickerActivity.MODE_PROPOSE_MEETING);
        if (seed != null) {
            // Start from where you already agreed to meet; it is the most likely
            // place the reader wants to adjust from.
            intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LAT, seed.getLatitude());
            intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LNG, seed.getLongitude());
            if (seed.getLocationNote() != null) {
                intent.putExtra(LocationPickerActivity.EXTRA_NOTE, seed.getLocationNote());
            }
        }
        meetingPickerLauncher.launch(intent);
    }

    private MeetingLocation currentAgreedSpot() {
        for (ChatTimelineItem item : timeline) {
            if (item.getProposal() != null && item.getProposal().isCurrent()) {
                return item.getProposal();
            }
        }
        return null;
    }

    private void proposeMeetingSpot(double lat, double lng, String note) {
        if (conversationId == null) return;
        showStatus(getString(R.string.chat_proposing), false);

        chatRepository.proposeMeetingLocation(conversationId, lat, lng, note,
                new ChatRepository.DataCallback<RpcResponse>() {
                    @Override
                    public void onSuccess(RpcResponse data) {
                        if (isDead()) return;
                        hideStatus();
                        
                        isPinnedToPosition = false;
                        hideNewMessagesIndicator();
                        
                        Toast.makeText(ChatActivity.this, R.string.chat_spot_proposed,
                                Toast.LENGTH_LONG).show();
                        // One more card in the thread; older ones are untouched.
                        loadTimeline(true);
                    }

                    @Override
                    public void onError(String message) {
                        if (isDead()) return;
                        showStatus(getString(R.string.chat_spot_failed, message), true);
                    }
                });
    }

    private void resolveConversationByClaimId() {
        chatRepository.getMyConversations(new ChatRepository.DataCallback<List<Conversation>>() {
            @Override
            public void onSuccess(List<Conversation> conversations) {
                if (conversations != null) {
                    for (Conversation c : conversations) {
                        if (claimId != null && claimId.equals(c.getClaimId())) {
                            conversationId = c.getId();
                            reportTitle = c.getReportTitle();
                            binding.tvChatTitle.setText(c.getReportTitle());
                            loadTimeline(true);
                            startForegroundPolling();
                            return;
                        }
                    }
                }
                Toast.makeText(ChatActivity.this, R.string.chat_not_ready, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}

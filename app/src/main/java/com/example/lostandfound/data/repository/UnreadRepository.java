package com.example.lostandfound.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.lostandfound.data.model.ConversationUnread;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The single, app-wide source of unread counts.
 *
 * WHY ONE OBJECT RATHER THAN A POLLER PER SCREEN
 * Badges have to be correct on Home, Browse, Create, Profile and Settings, not
 * just on Chats. Giving each screen its own refresh loop would mean five loops
 * firing the same request, with no single answer to "what is unread right now",
 * and they would keep running while the screen is off-screen. This one loop is
 * started once by MainActivity while the app is in the foreground and stopped
 * when it leaves, and any number of screens can observe it.
 *
 * This is a foreground refresh only. It is NOT a push pipeline: there is no
 * background delivery, no FCM, and no service here. Nothing claims otherwise.
 * (See migration 012 for why the server side is the same shape.)
 */
public class UnreadRepository {

    private static final long POLL_INTERVAL_MS = 5000L;

    /** Observers are held in a concurrent set: they are added and removed from
     *  lifecycle callbacks that need not match the thread doing the fetch. */
    public interface Listener {
        void onUnreadChanged(Map<String, Integer> countsByConversation, int total);
    }

    private static volatile UnreadRepository instance;

    private final ChatRepository chatRepository;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();

    /** Immutable snapshot handed to listeners; never mutated after publish. */
    private volatile Map<String, Integer> counts = Collections.emptyMap();
    private volatile int total;

    private boolean running;
    private boolean fetchInFlight;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private UnreadRepository(Context context) {
        this.chatRepository = new ChatRepository(context.getApplicationContext());
    }

    public static UnreadRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (UnreadRepository.class) {
                if (instance == null) {
                    instance = new UnreadRepository(context);
                }
            }
        }
        return instance;
    }

    /** Idempotent: safe to call from every onResume without stacking loops. */
    public synchronized void start() {
        if (running) return;
        running = true;
        handler.post(tick);
    }

    public synchronized void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
            // Deliver the current answer immediately so a screen that opens
            // between polls is never briefly wrong.
            listener.onUnreadChanged(counts, total);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Read by screens to render without waiting for the next tick. */
    public Map<String, Integer> counts() {
        return counts;
    }

    public int total() {
        return total;
    }

    /** Called when the signed-in account changes: the old answer is discarded. */
    public void reset() {
        counts = Collections.emptyMap();
        total = 0;
        publish();
    }

    /** Pull now instead of waiting for the tick (used after sending a message). */
    public void refresh() {
        if (fetchInFlight) return;   // never overlap
        fetchInFlight = true;
        chatRepository.getConversationUnreadCounts(new ChatRepository.DataCallback<List<ConversationUnread>>() {
            @Override
            public void onSuccess(List<ConversationUnread> data) {
                fetchInFlight = false;
                Map<String, Integer> next = new LinkedHashMap<>();
                if (data != null) {
                    for (ConversationUnread row : data) {
                        if (row != null && row.getConversationId() != null
                                && row.getUnreadCount() > 0) {
                            next.put(row.getConversationId(), row.getUnreadCount());
                        }
                    }
                }
                apply(new HashMap<>(next));
            }

            @Override
            public void onError(String message) {
                fetchInFlight = false;
                // Silent: a background tick failing keeps the last known answer,
                // which is still the truth we had. No toast spam.
            }
        });
    }

    private void apply(Map<String, Integer> next) {
        if (next.equals(counts)) return;   // nothing changed; do not churn the UI
        counts = next;
        publish();
    }

    private void publish() {
        int sum = com.example.lostandfound.ui.common.UnreadFormatter.total(counts);
        total = sum;
        for (Listener listener : listeners) {
            listener.onUnreadChanged(counts, total);
        }
    }
}
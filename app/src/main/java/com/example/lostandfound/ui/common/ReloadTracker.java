package com.example.lostandfound.ui.common;

/**
 * Guards one reloadable section against duplicate requests and stale
 * responses. A section starts a load with {@link #tryStart} (or
 * {@link #forceStart} when its parameters changed), captures
 * {@link #currentId}, and only applies a response whose id
 * {@link #isCurrent}. Terminal branches of the current request call
 * {@link #finish}; stale branches return early without touching loading
 * state, so an older response can never overwrite newer results or hide an
 * indicator that belongs to the newer load.
 */
public final class ReloadTracker {

    private boolean loading;
    private int generation;

    /**
     * Starts a load unless one is already in flight. A repeated tap while
     * loading returns false and issues no request.
     */
    public boolean tryStart() {
        if (loading) {
            return false;
        }
        loading = true;
        generation++;
        return true;
    }

    /**
     * Starts a load unconditionally, superseding any in-flight one. For
     * parameter changes (new filters), where the pending response is obsolete
     * even if it arrives first.
     */
    public void forceStart() {
        loading = true;
        generation++;
    }

    /** Marks the current load finished, releasing the gate for the next one. */
    public void finish() {
        loading = false;
    }

    public boolean isLoading() {
        return loading;
    }

    /** Id the current load captured; compare with {@link #isCurrent}. */
    public int currentId() {
        return generation;
    }

    /** True only for the response of the latest started load. */
    public boolean isCurrent(int requestId) {
        return requestId == generation;
    }
}
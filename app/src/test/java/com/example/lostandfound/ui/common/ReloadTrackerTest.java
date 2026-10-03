package com.example.lostandfound.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the reload contract the Recent Reports section relies on: one request
 * in flight at a time, repeated taps dropped, and a superseded response never
 * applied. The fragment pairs each id check with its isAdded/binding guards,
 * which cover the destroyed-view case and need a device; everything
 * decidable here is asserted here.
 */
public class ReloadTrackerTest {

    @Test
    public void firstStart_succeeds() {
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        assertTrue(tracker.isLoading());
    }

    @Test
    public void repeatedTapWhileLoading_isDropped() {
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        assertFalse("a second tap must not issue a duplicate request",
                tracker.tryStart());
        assertTrue(tracker.isLoading());
    }

    @Test
    public void finish_releasesTheGate() {
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        tracker.finish();
        assertFalse(tracker.isLoading());
        assertTrue("a tap after completion must start a new load",
                tracker.tryStart());
    }

    @Test
    public void staleResponse_afterForceStart_isNotCurrent() {
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        int staleId = tracker.currentId();
        tracker.forceStart();
        int freshId = tracker.currentId();
        assertFalse("the older response must never overwrite the newer load",
                tracker.isCurrent(staleId));
        assertTrue(tracker.isCurrent(freshId));
    }

    @Test
    public void staleBranch_leavesNewerLoadRunning() {
        ReloadTracker tracker = new ReloadTracker();
        tracker.tryStart();
        tracker.forceStart();
        // The stale branch returns early without calling finish(), so the
        // newer load keeps its indicator until its own response lands.
        assertTrue(tracker.isLoading());
    }

    @Test
    public void generation_advancesPerStart() {
        ReloadTracker tracker = new ReloadTracker();
        tracker.tryStart();
        int first = tracker.currentId();
        tracker.finish();
        tracker.tryStart();
        assertTrue("each load gets a distinct id",
                tracker.currentId() != first);
        assertTrue(tracker.isCurrent(tracker.currentId()));
    }

    @Test
    public void successEmptyAndFailure_allTerminateTheLoad() {
        // Every terminal branch of a load — success, empty results, network or
        // server failure — must call finish() so the button works again. This
        // pins the tracker half of that contract; the fragment half hides both
        // indicators on each of those branches.
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        tracker.finish();
        assertFalse(tracker.isLoading());
    }

    @Test
    public void recreationWithInflightLoad_appliesToNewView() {
        // The tracker is a fragment field, so it survives onDestroyView while
        // the binding does not. A load started before the view was destroyed
        // is still the current load afterwards: the resume on the new view
        // drops its duplicate start, and the in-flight response still applies
        // instead of leaving the new view permanently empty.
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        int inflightId = tracker.currentId();
        // onDestroyView: tracker untouched, only the view is gone.
        // onResume/onHiddenChanged on the recreated view: same params, a load
        // is already coming, so the duplicate start is dropped.
        assertFalse(tracker.tryStart());
        // The in-flight response arrives: still current, applies to the new view.
        assertTrue(tracker.isCurrent(inflightId));
        tracker.finish();
        assertFalse(tracker.isLoading());
    }

    @Test
    public void filterChangeAfterRecreate_supersedesInflightLoad() {
        // New parameters obsolete the pending response even across a view
        // recreation: forceStart invalidates it, so it can change neither the
        // list nor the error state nor the spinner.
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        int staleId = tracker.currentId();
        // Recreated view, user picks a different filter.
        tracker.forceStart();
        int freshId = tracker.currentId();
        assertFalse(tracker.isCurrent(staleId));
        assertTrue(tracker.isCurrent(freshId));
        assertTrue(tracker.isLoading());
    }

    @Test
    public void completedLoadBeforeRecreate_startsFreshAfterwards() {
        // A finished load leaves no residue: after recreation the next load
        // gets a new id and the previous ids are dead.
        ReloadTracker tracker = new ReloadTracker();
        assertTrue(tracker.tryStart());
        int oldId = tracker.currentId();
        tracker.finish();
        assertTrue(tracker.tryStart());
        assertFalse(tracker.isCurrent(oldId));
        assertTrue(tracker.isCurrent(tracker.currentId()));
    }
}
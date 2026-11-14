package net.basov.retrace.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The comparison of a trip with its reference track
// ----------------------------------------------------------------------
//
// TrackService gives each point of the trip to add(). This class keeps the
// reference track, the matcher, the smoothed difference and the finish
// signal. It has no Android code, thus the tests run it on the JVM.
//
// There are three types of selection:
// - The manual selection: setReference(). A track that Retrace cannot read
//   gives a trip with no comparison. The recording itself still works.
// - The automatic selection: searchAutomatically(). At the next point, the
//   search reads the saved tracks near that point. ReferenceSelector then
//   selects one of them. See RouteAgreement for the stop of an automatic
//   comparison that differs from the trip.
// - No comparison: reset() alone.
//
// select() changes the reference track during the trip. The points of the
// trip then go to the new matcher again. Thus the comparison continues as
// if the trip started with that track. A finish in these points sets the
// finish signal, but add() returns false for them.
//
// A replay excludes its own track with setExcluded(), because a track
// always agrees with itself.
public final class TripComparison {

    private final TrackSource source;

    private Profile profile = Profile.WALKING;
    // The id of the replayed track, or null.
    private String excludedId;

    private String referenceId;
    // True if the trip follows the reference track in the opposite direction.
    private boolean referenceReversed;
    // The name of the reference track if it is a way, or null.
    private String referenceWayName;
    private int selection = RecordingSnapshot.SELECTION_OFF;
    private ReferenceSelector selector;
    private TrackMatcher matcher;
    private DeltaSmoother smoother = DeltaSmoother.forProfile(Profile.WALKING);
    // The check of an automatic reference track after the selection, or
    // null. See RouteAgreement.
    private RouteAgreement agreement;
    private MatchResult match;
    private long smoothedDeltaMs = MatchResult.UNKNOWN;
    private boolean finishSignaled;

    public TripComparison(TrackSource source) {
        this.source = source;
    }

    // Removes the reference track and the comparison. The excluded track
    // stays, because select() calls this method during a replay.
    public void reset(Profile p) {
        profile = p;
        referenceId = null;
        referenceReversed = false;
        referenceWayName = null;
        selection = RecordingSnapshot.SELECTION_OFF;
        selector = null;
        matcher = null;
        agreement = null;
        match = null;
        smoother = DeltaSmoother.forProfile(p);
        smoothedDeltaMs = MatchResult.UNKNOWN;
        finishSignaled = false;
    }

    public void setExcluded(String id) {
        excludedId = id;
    }

    // The automatic selection starts at the next point.
    public void searchAutomatically() {
        selection = RecordingSnapshot.SELECTION_SEARCH;
    }

    // The manual selection. In the opposite direction, the matcher gets the
    // reversed track.
    public void setReference(String id, boolean reversed) {
        try {
            Track reference = source.load(id);
            if (reference.points.size() >= 2) {
                matcher = new TrackMatcher(reversed ? reference.reversed() : reference, profile);
                referenceId = id;
                referenceReversed = reversed;
                referenceWayName = reference.isWay() ? reference.name : null;
                selection = RecordingSnapshot.SELECTION_MANUAL;
            }
        } catch (IOException | IllegalArgumentException e) {
            matcher = null;
        }
    }

    // A new reference track during the trip. A path of null with automatic
    // false is no comparison. The points of the trip go to the new matcher
    // again.
    public void select(String id, boolean reversed, boolean automatic, List<TrackPoint> points) {
        reset(profile);
        if (automatic) {
            selection = RecordingSnapshot.SELECTION_SEARCH;
        } else if (id != null) {
            setReference(id, reversed);
        }
        for (TrackPoint p : points) {
            add(p);
        }
    }

    // Gives the point to the matcher, or to the selector during the
    // automatic selection. Returns true at the first point of the finish.
    public boolean add(TrackPoint fix) {
        if (selection == RecordingSnapshot.SELECTION_SEARCH) {
            startSearch(fix);
        }
        if (selector != null) {
            return compareCandidates(fix);
        }
        if (matcher == null) {
            return false;
        }
        MatchResult result = matcher.update(fix);
        if (agreement != null) {
            agreement.add(fix, result);
            if (agreement.differs()) {
                stopDifferentComparison();
                return false;
            }
        }
        return follow(fix, result);
    }

    public String referenceId() {
        return referenceId;
    }

    public boolean referenceReversed() {
        return referenceReversed;
    }

    public String referenceWayName() {
        return referenceWayName;
    }

    public int selection() {
        return selection;
    }

    public MatchResult match() {
        return match;
    }

    public long smoothedDeltaMs() {
        return smoothedDeltaMs;
    }

    public boolean finishSignaled() {
        return finishSignaled;
    }

    // Starts the automatic selection at the first point. The source first
    // drops the far tracks. Each other saved track is read and checked
    // alone. Thus only the near tracks stay in memory.
    private void startSearch(TrackPoint first) {
        List<ReferenceSelector.Candidate> near = new ArrayList<>();
        for (String id : source.near(first, ReferenceSelector.startLimitM(profile, first))) {
            // The replayed track always agrees with itself. It is not a
            // candidate for its own replay.
            if (id.equals(excludedId)) {
                continue;
            }
            try {
                Track track = source.load(id);
                if (ReferenceSelector.routeNear(track, profile, first)) {
                    near.add(new ReferenceSelector.Candidate(id, track, false));
                    near.add(new ReferenceSelector.Candidate(id, track.reversed(), true));
                }
            } catch (IOException | IllegalArgumentException e) {
                // A track that Retrace cannot read is not a candidate.
            }
        }
        if (near.isEmpty()) {
            selection = RecordingSnapshot.SELECTION_NOT_FOUND;
            return;
        }
        selector = new ReferenceSelector(near, profile, first);
        selection = RecordingSnapshot.SELECTION_TEMPORARY;
    }

    // The trip goes away from its automatic reference track. The comparison
    // stops, and the trip continues as a new route.
    private void stopDifferentComparison() {
        reset(profile);
        selection = RecordingSnapshot.SELECTION_NOT_FOUND;
    }

    // The temporary reference track can change at each point. A change
    // starts the comparison again, with the result of the matcher of the new
    // candidate.
    private boolean compareCandidates(TrackPoint fix) {
        ReferenceSelector.State state = selector.update(fix);
        if (state == ReferenceSelector.State.NONE) {
            reset(profile);
            selection = RecordingSnapshot.SELECTION_NOT_FOUND;
            return false;
        }
        String id = selector.current().id;
        boolean reversed = selector.current().reversed;
        if (!id.equals(referenceId) || reversed != referenceReversed) {
            referenceId = id;
            referenceReversed = reversed;
            Track candidate = selector.current().track;
            referenceWayName = candidate.isWay() ? candidate.name : null;
            match = null;
            smoother.reset();
            smoothedDeltaMs = MatchResult.UNKNOWN;
            finishSignaled = false;
        }
        MatchResult result = selector.currentResult();
        if (state == ReferenceSelector.State.SELECTED) {
            matcher = selector.selectedMatcher();
            agreement = selector.selectedAgreement();
            selector = null;
            selection = RecordingSnapshot.SELECTION_AUTOMATIC;
        }
        return follow(fix, result);
    }

    // Takes one result of the matcher. Returns true at the first point of
    // the finish.
    private boolean follow(TrackPoint fix, MatchResult result) {
        boolean wasStarted = match != null && match.started();
        match = result;
        if (match.state == TrackMatcher.State.FINISHED) {
            // At the finish, the exact value is the result.
            smoothedDeltaMs = match.deltaTimeMs;
            if (!finishSignaled) {
                finishSignaled = true;
                return true;
            }
            return false;
        }
        if (match.started() && !wasStarted) {
            // At the start or at a join, the difference starts again from zero.
            smoother.reset();
        }
        if (match.state == TrackMatcher.State.ON_ROUTE && match.deltaTimeMs != MatchResult.UNKNOWN) {
            smoothedDeltaMs = smoother.add(fix.timeMs, match.deltaTimeMs);
        }
        return false;
    }
}

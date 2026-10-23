package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The state of the recording at one moment
// ----------------------------------------------------------------------
//
// TrackService makes a new snapshot after each fix. The activity reads the
// last snapshot once each second. A snapshot is immutable. Thus the two
// threads need no lock.
//
// A trip with a reference track also has the path of the reference track,
// the last result of TrackMatcher and the smoothed time difference. A trip
// without a reference track has null and UNKNOWN there. The selection field
// tells how the trip got its reference track.
public final class RecordingSnapshot {

    // The values of the error field.
    public static final int ERROR_NONE = 0;
    public static final int ERROR_NO_PERMISSION = 1;
    public static final int ERROR_STORAGE = 2;
    public static final int ERROR_NO_GPS = 3;

    // The values of the selection field.
    // No comparison. The user selected this.
    public static final int SELECTION_OFF = 0;
    // The automatic selection waits for the first point of the trip.
    public static final int SELECTION_SEARCH = 1;
    // The automatic selection continues. The reference track is temporary.
    public static final int SELECTION_TEMPORARY = 2;
    // Retrace selected the reference track.
    public static final int SELECTION_AUTOMATIC = 3;
    // The user selected the reference track.
    public static final int SELECTION_MANUAL = 4;
    // No saved track agrees with the trip. The trip has no comparison.
    public static final int SELECTION_NOT_FOUND = 5;

    // The state of a service that does not record.
    public static final RecordingSnapshot IDLE =
            new RecordingSnapshot(false, null, 0, 0, 0, Double.NaN, TrackPoint.NO_TIME, 0, ERROR_NONE,
                    null, SELECTION_OFF, null, MatchResult.UNKNOWN);

    public final boolean recording;
    public final Profile profile;
    public final long startTimeMs;
    public final int points;
    public final double distanceM;

    // The accuracy of the last fix, also of a fix that the filter dropped.
    public final double lastAccuracyM;

    // The time of the last fix, or NO_TIME before the first fix.
    public final long lastFixTimeMs;

    // The service adds 1 each time that it saves a track. The activity then
    // knows that the list of tracks changed.
    public final int savedCount;

    // The error that stopped the last recording, or ERROR_NONE. The activity
    // shows a message for it.
    public final int error;

    // The path of the reference track file, or null.
    public final String referencePath;

    // One of the SELECTION values.
    public final int selection;

    // The last result of TrackMatcher, or null.
    public final MatchResult match;

    // The time difference after DeltaSmoother, or UNKNOWN. The screen shows
    // this value. At the finish it is the exact result.
    public final long smoothedDeltaMs;

    public RecordingSnapshot(boolean recording, Profile profile, long startTimeMs, int points,
                             double distanceM, double lastAccuracyM, long lastFixTimeMs,
                             int savedCount, int error, String referencePath, int selection,
                             MatchResult match, long smoothedDeltaMs) {
        this.recording = recording;
        this.profile = profile;
        this.startTimeMs = startTimeMs;
        this.points = points;
        this.distanceM = distanceM;
        this.lastAccuracyM = lastAccuracyM;
        this.lastFixTimeMs = lastFixTimeMs;
        this.savedCount = savedCount;
        this.error = error;
        this.referencePath = referencePath;
        this.selection = selection;
        this.match = match;
        this.smoothedDeltaMs = smoothedDeltaMs;
    }

    public boolean hasReference() {
        return referencePath != null;
    }

    public boolean hasFix() {
        return lastFixTimeMs != TrackPoint.NO_TIME;
    }
}

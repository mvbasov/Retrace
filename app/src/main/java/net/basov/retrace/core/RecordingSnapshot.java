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
// without a reference track has null and UNKNOWN there.
public final class RecordingSnapshot {

    // The values of the error field.
    public static final int ERROR_NONE = 0;
    public static final int ERROR_NO_PERMISSION = 1;
    public static final int ERROR_STORAGE = 2;
    public static final int ERROR_NO_GPS = 3;

    // The state of a service that does not record.
    public static final RecordingSnapshot IDLE =
            new RecordingSnapshot(false, null, 0, 0, 0, Double.NaN, TrackPoint.NO_TIME, 0, ERROR_NONE,
                    null, null, MatchResult.UNKNOWN);

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

    // The last result of TrackMatcher, or null.
    public final MatchResult match;

    // The time difference after DeltaSmoother, or UNKNOWN. The screen shows
    // this value. At the finish it is the exact result.
    public final long smoothedDeltaMs;

    public RecordingSnapshot(boolean recording, Profile profile, long startTimeMs, int points,
                             double distanceM, double lastAccuracyM, long lastFixTimeMs,
                             int savedCount, int error, String referencePath, MatchResult match,
                             long smoothedDeltaMs) {
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

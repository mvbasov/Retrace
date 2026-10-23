package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The profiles of movement
// ----------------------------------------------------------------------
//
// A profile holds the limits for one type of movement. The values come from
// section 5.3 of doc/PLAN.md. A later milestone lets the user change them.
//
// The id goes into the GPX type element and into the file name. Thus an id
// must not change after a release.
public enum Profile {

    WALKING("walking", 20, 2, 25, 15, 15),
    RUNNING("running", 25, 3, 30, 10, 20),
    CYCLING("cycling", 30, 5, 40, 8, 25),
    DRIVING("driving", 50, 10, 60, 5, 40);

    // The text in GPX files and file names.
    public final String id;

    // Retrace drops a fix with an accuracy worse than this value, in meters.
    public final double accuracyLimitM;

    // Retrace drops a fix nearer than this value to the last point, in meters.
    public final double minDistanceM;

    // Above this cross-track distance, the trip is off route, in meters.
    public final double offRouteM;

    // The time window of the speed smoothing, in seconds.
    public final int speedSmoothingS;

    // The radius around the start and the finish, in meters.
    public final double radiusM;

    Profile(String id, double accuracyLimitM, double minDistanceM, double offRouteM,
            int speedSmoothingS, double radiusM) {
        this.id = id;
        this.accuracyLimitM = accuracyLimitM;
        this.minDistanceM = minDistanceM;
        this.offRouteM = offRouteM;
        this.speedSmoothingS = speedSmoothingS;
        this.radiusM = radiusM;
    }

    // Returns the profile with the id, or null for an unknown id. A GPX file
    // from another application can have any type text.
    public static Profile fromId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        for (Profile p : values()) {
            if (p.id.equals(key)) {
                return p;
            }
        }
        return null;
    }
}

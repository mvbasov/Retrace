package net.basov.retrace.core;

// ----------------------------------------------------------------------
// A waypoint: a named place of a track
// ----------------------------------------------------------------------
//
// During a trip, the user adds a waypoint with a button of the trip screen
// or of the notification. Retrace gives it the name wpt01, wpt02 and so on.
// The user can change the names later. The GPX file holds the waypoints as
// wpt elements before the track.
//
// A waypoint is immutable. withName() makes a copy with a new name.
public final class Waypoint {

    public final double latitude;
    public final double longitude;
    // The time, or TrackPoint.NO_TIME.
    public final long timeMs;
    // The elevation, or NaN.
    public final double elevationM;
    public final String name;

    public Waypoint(double latitude, double longitude, long timeMs, double elevationM,
                    String name) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timeMs = timeMs;
        this.elevationM = elevationM;
        this.name = name == null ? "" : name;
    }

    // A waypoint at a point of a track.
    public static Waypoint at(TrackPoint p, String name) {
        return new Waypoint(p.latitude, p.longitude, p.timeMs, p.elevationM, name);
    }

    public Waypoint withName(String newName) {
        return new Waypoint(latitude, longitude, timeMs, elevationM, newName);
    }

    public boolean hasTime() {
        return timeMs != TrackPoint.NO_TIME;
    }

    // The automatic name of the waypoint with this number, from 1: wpt01,
    // wpt02 and so on. A number above 99 has more digits.
    public static String autoName(int number) {
        return number < 10 ? "wpt0" + number : "wpt" + number;
    }
}

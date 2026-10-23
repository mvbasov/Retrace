package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The smoothing of the time difference for the screen
// ----------------------------------------------------------------------
//
// The time difference of TrackMatcher has GPS noise. At walking speed it
// moves by some seconds from one fix to the next. A display that jumps is
// hard to read. Thus the trip screen shows a smoothed value.
//
// The smoothing is an exponential mean with a time constant. A fix after a
// long gap moves the value more than a fix after one second. The first value
// is the start value. A jump of the state, for example at a join of the
// route, needs reset().
//
// The smoothing removes the fast part of the noise only. The slow GPS drift
// of two trips stays. See section 5.4 of doc/PLAN.md.
public final class DeltaSmoother {

    private final double timeConstantMs;
    private boolean started;
    private double value;
    private long lastTimeMs;

    public DeltaSmoother(double timeConstantMs) {
        if (timeConstantMs <= 0) {
            throw new IllegalArgumentException("time constant must be positive: " + timeConstantMs);
        }
        this.timeConstantMs = timeConstantMs;
    }

    // The time constant for a profile: two times its speed smoothing window.
    public static DeltaSmoother forProfile(Profile profile) {
        return new DeltaSmoother(2000.0 * profile.speedSmoothingS);
    }

    // Adds a value at a time, and returns the smoothed value.
    public long add(long timeMs, long deltaMs) {
        if (!started) {
            started = true;
            value = deltaMs;
        } else {
            double dt = Math.max(0, timeMs - lastTimeMs);
            double weight = 1 - Math.exp(-dt / timeConstantMs);
            value += weight * (deltaMs - value);
        }
        lastTimeMs = timeMs;
        return Math.round(value);
    }

    public void reset() {
        started = false;
    }

    public boolean started() {
        return started;
    }
}

package net.basov.retrace;

import android.content.Context;

import net.basov.retrace.core.Profile;

// ----------------------------------------------------------------------
// The user text for values of core
// ----------------------------------------------------------------------
//
// The core package has no Android resources. This class gives the text of
// strings.xml for a profile and for a distance.
final class Labels {

    private Labels() {
    }

    static String profile(Context context, Profile profile) {
        if (profile == null) {
            return context.getString(R.string.profile_unknown);
        }
        switch (profile) {
            case RUNNING:
                return context.getString(R.string.profile_running);
            case CYCLING:
                return context.getString(R.string.profile_cycling);
            case DRIVING:
                return context.getString(R.string.profile_driving);
            case WALKING:
            default:
                return context.getString(R.string.profile_walking);
        }
    }

    // Below one kilometer, the text shows whole meters.
    static String distance(Context context, double meters) {
        if (meters < 1000) {
            return context.getString(R.string.distance_m, (int) Math.round(meters));
        }
        return context.getString(R.string.distance_km, meters / 1000.0);
    }
}

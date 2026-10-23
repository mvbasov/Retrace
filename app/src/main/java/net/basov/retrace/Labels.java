package net.basov.retrace;

import android.content.Context;
import android.content.pm.PackageManager;

import net.basov.retrace.core.Profile;

// ----------------------------------------------------------------------
// The user text for values of core
// ----------------------------------------------------------------------
//
// The core package has no Android resources. This class gives the text of
// strings.xml for a profile and for a distance. It also gives the version
// name of the application.
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

    // The version name comes from app/build.gradle through the manifest. A
    // debug build adds "-debug".
    static String versionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return context.getString(R.string.value_unknown);
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

package net.basov.retrace;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.basov.retrace.core.Track;
import net.basov.retrace.core.WayBuilder;

// ----------------------------------------------------------------------
// The addition of a finished trip to its way
// ----------------------------------------------------------------------
//
// After a trip with a way as the reference track, the new track can join
// the way. The setting of AppSettings tells if Retrace asks, adds it
// automatically, or does nothing. TrackService adds it automatically, and
// a dialog adds it after a question. Both use add().
//
// The way gets its points from all its members again. If WayBuilder does
// not use the new track, the way stays as it was.
final class WayUpdater {

    static final int QUESTION = 0;
    static final int ADDED = 1;
    static final int NOT_USED = 2;
    static final int ERROR = 3;

    // A finished trip that can join its way. The service keeps one offer.
    // The visible screen takes it and shows the question or the result.
    static final class Offer {
        final String wayPath;
        final String wayName;
        final String trackPath;

        // QUESTION, or the result of add().
        final int result;

        Offer(String wayPath, String wayName, String trackPath, int result) {
            this.wayPath = wayPath;
            this.wayName = wayName;
            this.trackPath = trackPath;
            this.result = result;
        }
    }

    private WayUpdater() {
    }

    // Builds the way again with the new track. Call it on a background
    // thread. A member file that the user removed is not used.
    static int add(TrackStore store, File wayFile, File trackFile) {
        try {
            Track way = store.load(wayFile);
            if (!way.isWay() || way.members.contains(trackFile.getName())) {
                return way.isWay() ? ADDED : ERROR;
            }
            List<Track> tracks = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String member : way.members) {
                File f = store.trackFile(member);
                if (!f.isFile()) {
                    continue;
                }
                try {
                    tracks.add(store.load(f));
                    names.add(member);
                } catch (IOException | IllegalArgumentException e) {
                    // A member that Retrace cannot read is not used.
                }
            }
            tracks.add(store.load(trackFile));
            names.add(trackFile.getName());
            WayBuilder.Result r = WayBuilder.build(way.name, way.profile, tracks, 0);
            if (r.way == null || !r.used.contains(tracks.size() - 1)) {
                return NOT_USED;
            }
            List<String> members = new ArrayList<>();
            for (int i : r.used) {
                members.add(names.get(i));
            }
            store.saveWay(r.way.withMembers(members), wayFile);
            return ADDED;
        } catch (IOException | IllegalArgumentException e) {
            return ERROR;
        }
    }
}

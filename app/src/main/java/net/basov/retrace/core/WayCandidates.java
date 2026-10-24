package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The tracks that the way dialog offers
// ----------------------------------------------------------------------
//
// The dialog makes a new way from the route of a track, or it changes the
// tracks of a way. It shows the saved tracks of the same route. The caller
// gives the saved tracks one at a time to offer(). Thus only one track is
// in memory at a time.
//
// For a new way, the base is the track of the menu. A track of the same
// route has the profile of the base, and WayBuilder.match() accepts it.
//
// For a change of a way, the members of the way are always in the list.
// The way keeps their file names. Thus Retrace knows that they are on the
// route, and they need no match against the way. The old
// dialog found the members with a match against the way. After an
// automatic addition, the maintainer saw a way with three tracks, but the
// dialog found fewer than two. Only the tracks that are not members need
// the match.
//
// A way is never a member of a way. A track with a different profile, or
// with fewer than 2 points, is never in the list.
public final class WayCandidates {

    // One line of the dialog. A line is checked at first for each track of
    // a new way, and for each member of a way.
    public static final class Item {
        public final String name;
        public final boolean checked;
        public final boolean reversed;

        Item(String name, boolean checked, boolean reversed) {
            this.name = name;
            this.checked = checked;
            this.reversed = reversed;
        }
    }

    private final Track base;
    private final String baseName;
    private final List<Item> first = new ArrayList<>();
    private final List<Item> others = new ArrayList<>();

    // The base is the track of the menu, with its file name, or the way.
    public WayCandidates(Track base, String baseName) {
        this.base = base;
        this.baseName = baseName;
    }

    // Takes one saved track. Returns true if the dialog shows it.
    public boolean offer(String name, Track track) {
        if (track.isWay() || track.profile != base.profile || track.points.size() < 2) {
            return false;
        }
        if (!base.isWay() && name.equals(baseName)) {
            first.add(0, new Item(name, true, false));
            return true;
        }
        boolean member = base.isWay() && base.members.contains(name);
        WayBuilder.Match m;
        try {
            m = WayBuilder.match(base, track, base.profile);
        } catch (RuntimeException e) {
            // A fault in the match of one track must not stop the dialog.
            m = null;
        }
        if (member) {
            first.add(new Item(name, true, m != null && m.reversed));
            return true;
        }
        if (m != null && m.reject == 0) {
            others.add(new Item(name, !base.isWay(), m.reversed));
            return true;
        }
        return false;
    }

    // The base or the members first, then the other tracks in the sequence
    // of offer().
    public List<Item> items() {
        List<Item> all = new ArrayList<>(first);
        all.addAll(others);
        return all;
    }
}

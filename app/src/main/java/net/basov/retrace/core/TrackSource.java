package net.basov.retrace.core;

import java.io.IOException;
import java.util.List;

// ----------------------------------------------------------------------
// The saved tracks for the comparison
// ----------------------------------------------------------------------
//
// TripComparison reads the saved tracks through this interface. On the
// device, TrackService gives the files of TrackStore, and the id of a track
// is the path of its file. The tests give tracks from memory.
public interface TrackSource {

    // The ids of the saved tracks that can be near the fix. A far track can
    // be in the list, because the caller does the precise check.
    List<String> near(TrackPoint fix, double limitM);

    // Reads one saved track.
    Track load(String id) throws IOException;
}

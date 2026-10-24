package net.basov.retrace;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import net.basov.retrace.core.Profile;
import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.WayBuilder;

// ----------------------------------------------------------------------
// The dialogs that make a way and change the tracks of a way
// ----------------------------------------------------------------------
//
// The list of tracks opens them from the menu of a track or of a way. The
// dialog shows the saved tracks of the same route, with a checkbox each. A
// track of the same route has the profile of the base. It covers at least
// half of the route of the base, in one of the two directions. See
// WayBuilder.match().
// The dialog marks a track in the opposite direction. For a new way, each
// such track is checked. For a way, its members are checked.
//
// The time of a track in the opposite direction is correct only on a flat
// route. The message after the build tells it.
//
// The file work and WayBuilder run on the background thread. The dialogs
// and the messages are on the UI thread.
final class WayDialogs {

    private WayDialogs() {
    }

    // A new way from the route of a track. The track is the base of the
    // first pass of WayBuilder.
    static void make(Activity activity, ExecutorService files, TrackStore.Entry base,
                     Runnable done) {
        open(activity, files, base, null, done);
    }

    // A new set of tracks for a way. The way gets its new points in its old
    // file.
    static void change(Activity activity, ExecutorService files, TrackStore.Entry way,
                       Runnable done) {
        open(activity, files, way, way, done);
    }

    private static void open(Activity activity, ExecutorService files, TrackStore.Entry base,
                             TrackStore.Entry way, Runnable done) {
        TrackStore store = new TrackStore(activity);
        files.execute(() -> {
            List<Found> route = new ArrayList<>();
            try {
                route = sameRoute(store, store.load(base.file), base);
            } catch (IOException | IllegalArgumentException e) {
                route.clear();
            }
            final List<Found> found = route;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (found.size() < WayBuilder.MIN_TRACKS) {
                    message(activity, activity.getString(R.string.way_no_route));
                } else {
                    showChoice(activity, files, found, base, way, done);
                }
            });
        });
    }

    // A track of the route of the base, and its direction.
    private static final class Found {
        final TrackStore.Entry entry;
        final boolean reversed;

        Found(TrackStore.Entry entry, boolean reversed) {
            this.entry = entry;
            this.reversed = reversed;
        }
    }

    // The tracks of the route of the base, the base first if it is a track.
    // WayBuilder.match() tells which tracks follow the route, and their
    // direction. A way is never a member of a way.
    private static List<Found> sameRoute(TrackStore store, Track base,
                                         TrackStore.Entry baseEntry) {
        Profile profile = base.profile;
        List<Found> out = new ArrayList<>();
        for (TrackStore.Entry e : store.list()) {
            if (!e.readable || e.isWay() || e.profile != profile || e.points < 2) {
                continue;
            }
            if (e.file.equals(baseEntry.file)) {
                out.add(0, new Found(e, false));
                continue;
            }
            Track t;
            try {
                t = store.load(e.file);
            } catch (IOException | IllegalArgumentException ex) {
                continue;
            }
            WayBuilder.Match m = WayBuilder.match(base, t, profile);
            if (m.reject == 0) {
                out.add(new Found(e, m.reversed));
            }
        }
        return out;
    }

    private static void showChoice(Activity activity, ExecutorService files,
                                   List<Found> found, TrackStore.Entry base,
                                   TrackStore.Entry way, Runnable done) {
        List<TrackStore.Entry> tracks = new ArrayList<>();
        String[] items = new String[found.size()];
        boolean[] checked = new boolean[found.size()];
        for (int i = 0; i < found.size(); i++) {
            TrackStore.Entry e = found.get(i).entry;
            tracks.add(e);
            items[i] = activity.getString(R.string.track_details, e.name,
                    Labels.distance(activity, e.distanceM), TimeText.duration(e.durationMs));
            if (found.get(i).reversed) {
                items[i] = activity.getString(R.string.way_item_opposite, items[i]);
            }
            checked[i] = way == null || way.members.contains(e.file.getName());
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.way_tracks_title)
                .setMultiChoiceItems(items, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<TrackStore.Entry> selected = new ArrayList<>();
                    for (int i = 0; i < tracks.size(); i++) {
                        if (checked[i]) {
                            selected.add(tracks.get(i));
                        }
                    }
                    if (selected.size() < WayBuilder.MIN_TRACKS) {
                        message(activity, activity.getString(R.string.way_select_two));
                    } else {
                        build(activity, files, selected, base, way, done);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Builds and saves the way. The base of the first pass is the track of
    // the menu, or the first selected track for a change of a way.
    private static void build(Activity activity, ExecutorService files,
                              List<TrackStore.Entry> selected, TrackStore.Entry base,
                              TrackStore.Entry way, Runnable done) {
        TrackStore store = new TrackStore(activity);
        files.execute(() -> {
            String text;
            String rejectedText = null;
            boolean ok = false;
            try {
                List<Track> tracks = new ArrayList<>();
                int baseIndex = 0;
                for (int i = 0; i < selected.size(); i++) {
                    tracks.add(store.load(selected.get(i).file));
                    if (selected.get(i).file.equals(base.file)) {
                        baseIndex = i;
                    }
                }
                String name = way != null ? way.name
                        : activity.getString(R.string.way_name, base.name);
                WayBuilder.Result r = WayBuilder.build(name, tracks.get(0).profile, tracks, baseIndex);
                if (r.way == null) {
                    text = activity.getString(R.string.way_failed);
                } else {
                    List<String> members = new ArrayList<>();
                    for (int i : r.used) {
                        members.add(selected.get(i).file.getName());
                    }
                    File target = way == null ? null : way.file;
                    store.saveWay(r.way.withMembers(members), target);
                    text = activity.getString(R.string.way_done, members.size());
                    ok = true;
                    if (!r.rejected.isEmpty()) {
                        rejectedText = activity.getString(R.string.way_rejected, r.rejected.size());
                    }
                    if (!r.reversed.isEmpty()) {
                        String opposite = activity.getString(R.string.way_opposite_used,
                                r.reversed.size());
                        rejectedText = rejectedText == null ? opposite
                                : rejectedText + "\n\n" + opposite;
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                text = activity.getString(R.string.import_error_storage);
            }
            final String shown = text;
            final String warning = rejectedText;
            final boolean saved = ok;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (warning != null) {
                    message(activity, shown + "\n\n" + warning);
                } else if (saved) {
                    Toast.makeText(activity, shown, Toast.LENGTH_LONG).show();
                } else {
                    message(activity, shown);
                }
                done.run();
            });
        });
    }

    // Shows the offer of TrackService: the question, or the result of an
    // automatic addition.
    static void showOffer(Activity activity, ExecutorService files, WayUpdater.Offer offer,
                          Runnable done) {
        if (offer.result != WayUpdater.QUESTION) {
            showResult(activity, offer, offer.result);
            done.run();
            return;
        }
        new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.way_offer, offer.wayName))
                .setPositiveButton(R.string.way_add, (d, w) -> {
                    TrackStore store = new TrackStore(activity);
                    files.execute(() -> {
                        int result = WayUpdater.add(store, new File(offer.wayPath),
                                new File(offer.trackPath));
                        activity.runOnUiThread(() -> {
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                showResult(activity, offer, result);
                                done.run();
                            }
                        });
                    });
                })
                .setNegativeButton(R.string.way_skip, null)
                .show();
    }

    private static void showResult(Activity activity, WayUpdater.Offer offer, int result) {
        if (result == WayUpdater.ADDED) {
            Toast.makeText(activity, activity.getString(R.string.way_added, offer.wayName),
                    Toast.LENGTH_LONG).show();
        } else if (result == WayUpdater.NOT_USED) {
            message(activity, activity.getString(R.string.way_not_used, offer.wayName));
        } else {
            message(activity, activity.getString(R.string.way_update_error, offer.wayName));
        }
    }

    private static void message(Activity activity, String text) {
        new AlertDialog.Builder(activity)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}

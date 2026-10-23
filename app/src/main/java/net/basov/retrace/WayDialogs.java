package net.basov.retrace;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import net.basov.retrace.core.Geo;
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.ReferenceSelector;
import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackPoint;
import net.basov.retrace.core.WayBuilder;

// ----------------------------------------------------------------------
// The dialogs that make a way and change the tracks of a way
// ----------------------------------------------------------------------
//
// The list of tracks opens them from the menu of a track or of a way. The
// dialog shows the saved tracks of the same route, with a checkbox each. A
// track of the same route has the profile of the base and about its
// length. Its start and its finish are near the start and the finish of the
// base. For a new way, each such track is checked. For a way, its members
// are checked.
//
// The file work and WayBuilder run on the background thread. The dialogs
// and the messages are on the UI thread.
final class WayDialogs {

    // A track of the same route has a length between these parts of the
    // length of the base.
    private static final double MIN_LENGTH_PART = 0.7;
    private static final double MAX_LENGTH_PART = 1.4;

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
            List<TrackStore.Entry> route = new ArrayList<>();
            try {
                route = sameRoute(store, store.load(base.file), base);
            } catch (IOException | IllegalArgumentException e) {
                route.clear();
            }
            final List<TrackStore.Entry> found = route;
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

    // The tracks of the route of the base, the base first if it is a track.
    // A way is never a member of a way.
    private static List<TrackStore.Entry> sameRoute(TrackStore store, Track base,
                                                    TrackStore.Entry baseEntry) {
        Profile profile = base.profile;
        TrackPoint start = base.points.get(0);
        TrackPoint finish = base.points.get(base.points.size() - 1);
        double limit = ReferenceSelector.START_FACTOR * profile.radiusM;
        List<TrackStore.Entry> out = new ArrayList<>();
        for (TrackStore.Entry e : store.list()) {
            if (!e.readable || e.isWay() || e.profile != profile || e.points < 2) {
                continue;
            }
            double part = e.distanceM / Math.max(1, base.distanceM());
            if (part < MIN_LENGTH_PART || part > MAX_LENGTH_PART) {
                continue;
            }
            Track t;
            try {
                t = store.load(e.file);
            } catch (IOException | IllegalArgumentException ex) {
                continue;
            }
            if (Geo.distanceM(t.points.get(0), start) > limit
                    || Geo.distanceM(t.points.get(t.points.size() - 1), finish) > limit) {
                continue;
            }
            if (e.file.equals(baseEntry.file)) {
                out.add(0, e);
            } else {
                out.add(e);
            }
        }
        return out;
    }

    private static void showChoice(Activity activity, ExecutorService files,
                                   List<TrackStore.Entry> tracks, TrackStore.Entry base,
                                   TrackStore.Entry way, Runnable done) {
        String[] items = new String[tracks.size()];
        boolean[] checked = new boolean[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            TrackStore.Entry e = tracks.get(i);
            items[i] = activity.getString(R.string.track_details, e.name,
                    Labels.distance(activity, e.distanceM), TimeText.duration(e.durationMs));
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

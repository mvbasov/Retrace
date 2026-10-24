package net.basov.retrace;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import net.basov.retrace.core.Profile;

// ----------------------------------------------------------------------
// The dialog that selects the reference track
// ----------------------------------------------------------------------
//
// The trip screen uses it during a trip, and the list of tracks uses it
// before a replay. The first two lines are the automatic selection and no
// comparison. The saved tracks of the profile follow, the newest first.
//
// After a push on a track, a second dialog asks for the direction. The
// opposite direction tells that its time is correct only on a flat route.
final class ReferenceDialog {

    // The result of the dialog. A path of null with automatic false is no
    // comparison. Reversed is true for the opposite direction of the track.
    interface Listener {
        void onSelect(boolean automatic, String path, boolean reversed);
    }

    private ReferenceDialog() {
    }

    // Reads the list of tracks on the background thread, then shows the
    // dialog on the UI thread.
    static void show(Activity activity, ExecutorService files, Profile profile, Listener listener) {
        TrackStore store = new TrackStore(activity);
        files.execute(() -> {
            List<TrackStore.Entry> tracks = new ArrayList<>();
            for (TrackStore.Entry e : store.list()) {
                if (e.readable && e.profile == profile && e.points >= TrackStore.MIN_POINTS) {
                    tracks.add(e);
                }
            }
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    showList(activity, tracks, listener);
                }
            });
        });
    }

    // Puts the result into an intent for TrackService.
    static Intent putSelection(Intent intent, boolean automatic, String path, boolean reversed) {
        if (automatic) {
            intent.putExtra(TrackService.EXTRA_AUTOMATIC, true);
        } else if (path != null) {
            intent.putExtra(TrackService.EXTRA_REFERENCE, path);
            intent.putExtra(TrackService.EXTRA_REVERSED, reversed);
        }
        return intent;
    }

    // The direction of a trip on the route of a track.
    static void askDirection(Activity activity, String path, Listener listener) {
        String[] items = {
                activity.getString(R.string.direction_same),
                activity.getString(R.string.direction_opposite)};
        new AlertDialog.Builder(activity)
                .setTitle(R.string.direction_title)
                .setItems(items, (d, which) -> listener.onSelect(false, path, which == 1))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showList(Activity activity, List<TrackStore.Entry> tracks,
                                 Listener listener) {
        String[] items = new String[tracks.size() + 2];
        items[0] = activity.getString(R.string.select_automatic);
        items[1] = activity.getString(R.string.select_none);
        for (int i = 0; i < tracks.size(); i++) {
            TrackStore.Entry e = tracks.get(i);
            items[i + 2] = activity.getString(R.string.select_item, e.name,
                    Labels.distance(activity, e.distanceM));
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.select_reference)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        listener.onSelect(true, null, false);
                    } else if (which == 1) {
                        listener.onSelect(false, null, false);
                    } else {
                        askDirection(activity, tracks.get(which - 2).file.getPath(), listener);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

package net.basov.retrace;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

// ----------------------------------------------------------------------
// The menu of a track
// ----------------------------------------------------------------------
//
// The list of tracks and the track screen show the menu of a track. A long
// push on a line of the list shows the file items: share, rename and
// delete. The Menu button of the track screen shows the full menu: the trip
// items, the way item, then the file items.
//
// The menu is a dialog, not a context menu. From Android 7, a context menu is
// a popup at the place of the push. Some systems draw that popup with the
// night mode of the system, not with the color theme of Retrace.
//
// A trip starts on the list screen, because that screen asks for the
// location permission and checks the GPS. Thus the menu sends the trip to
// MainActivity. The intent closes the screens above the list.
final class TrackActions {

    // The extras of a trip for MainActivity. The profile is absent for a
    // track with no profile.
    static final String EXTRA_TRIP_REFERENCE = "net.basov.retrace.extra.TRIP_REFERENCE";
    static final String EXTRA_TRIP_REVERSED = "net.basov.retrace.extra.TRIP_REVERSED";
    static final String EXTRA_TRIP_PROFILE = "net.basov.retrace.extra.TRIP_PROFILE";

    // The changes that the listener gets after a successful action.
    static final int RENAMED = 1;
    static final int DELETED = 2;
    static final int WAY_MADE = 3;
    static final int WAY_CHANGED = 4;

    interface Listener {
        void onChanged(int change);
    }

    private static final int TRIP = 1;
    private static final int TRIP_OPPOSITE = 2;
    private static final int MAKE_WAY = 3;
    private static final int CHANGE_WAY = 4;
    private static final int SHARE = 5;
    private static final int RENAME = 6;
    private static final int DELETE = 7;

    private TrackActions() {
    }

    // The full menu of the track screen.
    static void showFull(Activity activity, ExecutorService files, TrackStore.Entry e,
                         Listener listener) {
        show(activity, files, e, true, listener);
    }

    // The file items of the list.
    static void showFileItems(Activity activity, ExecutorService files, TrackStore.Entry e,
                              Listener listener) {
        show(activity, files, e, false, listener);
    }

    private static void show(Activity activity, ExecutorService files, TrackStore.Entry e,
                             boolean full, Listener listener) {
        List<Integer> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean route = e.readable && e.points >= TrackStore.MIN_POINTS;
        if (full && route && !TrackService.snapshot().recording) {
            add(activity, ids, labels, TRIP, R.string.start_trip_on_route);
            add(activity, ids, labels, TRIP_OPPOSITE, R.string.start_trip_opposite);
        }
        if (full && e.readable && e.isWay()) {
            add(activity, ids, labels, CHANGE_WAY, R.string.change_way);
        } else if (full && route && e.profile != null) {
            add(activity, ids, labels, MAKE_WAY, R.string.make_way);
        }
        if (e.readable) {
            add(activity, ids, labels, SHARE, R.string.share);
            add(activity, ids, labels, RENAME, R.string.rename);
        }
        add(activity, ids, labels, DELETE, R.string.delete);
        new AlertDialog.Builder(activity)
                .setTitle(e.name)
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> act(activity, files, ids.get(which), e, listener))
                .show();
    }

    private static void add(Activity activity, List<Integer> ids, List<String> labels,
                            int id, int textId) {
        ids.add(id);
        labels.add(activity.getString(textId));
    }

    private static void act(Activity activity, ExecutorService files, int id,
                            TrackStore.Entry e, Listener listener) {
        if (id == TRIP) {
            startTrip(activity, e, false);
        } else if (id == TRIP_OPPOSITE) {
            // The time in the opposite direction is correct only on a flat
            // route. The user reads this before the start.
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.start_trip_opposite)
                    .setMessage(R.string.opposite_warning_long)
                    .setPositiveButton(R.string.start_recording,
                            (d, w) -> startTrip(activity, e, true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else if (id == MAKE_WAY) {
            WayDialogs.make(activity, files, e, () -> listener.onChanged(WAY_MADE));
        } else if (id == CHANGE_WAY) {
            WayDialogs.change(activity, files, e, () -> listener.onChanged(WAY_CHANGED));
        } else if (id == SHARE) {
            share(activity, e);
        } else if (id == RENAME) {
            askRename(activity, files, e, listener);
        } else if (id == DELETE) {
            askDelete(activity, files, e, listener);
        }
    }

    private static void startTrip(Activity activity, TrackStore.Entry e, boolean reversed) {
        Intent trip = new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TRIP_REFERENCE, e.file.getPath())
                .putExtra(EXTRA_TRIP_REVERSED, reversed);
        if (e.profile != null) {
            trip.putExtra(EXTRA_TRIP_PROFILE, e.profile.id);
        }
        activity.startActivity(trip);
    }

    private static void askRename(Activity activity, ExecutorService files, TrackStore.Entry e,
                                  Listener listener) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(e.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty() && !name.equals(e.name)) {
                        TrackStore store = new TrackStore(activity);
                        runFileTask(activity, files, () -> store.rename(e.file, name),
                                R.string.error_rename, () -> listener.onChanged(RENAMED));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void askDelete(Activity activity, ExecutorService files, TrackStore.Entry e,
                                  Listener listener) {
        TrackStore store = new TrackStore(activity);
        new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.delete_question, e.name))
                .setPositiveButton(R.string.delete, (d, w) -> runFileTask(activity, files, () -> {
                    if (!store.delete(e.file)) {
                        throw new IOException("cannot delete " + e.file);
                    }
                }, R.string.error_delete, () -> listener.onChanged(DELETED)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // The share intent gives the receiver the read permission for this one
    // URI. The clip data carries the permission through the chooser.
    private static void share(Activity activity, TrackStore.Entry e) {
        Uri uri = TrackProvider.uriFor(activity, e.file);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/gpx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, e.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(e.name, uri));
        try {
            activity.startActivity(Intent.createChooser(send,
                    activity.getString(R.string.share_title)));
        } catch (ActivityNotFoundException ex) {
            message(activity, activity.getString(R.string.share_no_app));
        }
    }

    // A file task that can fail.
    private interface FileTask {
        void run() throws IOException;
    }

    // Runs the task on the background thread. After a success, the done
    // action runs on the UI thread. After a fault, a message tells it.
    private static void runFileTask(Activity activity, ExecutorService files, FileTask task,
                                    int errorText, Runnable done) {
        files.execute(() -> {
            boolean ok;
            try {
                task.run();
                ok = true;
            } catch (IOException ex) {
                ok = false;
            }
            final boolean success = ok;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (success) {
                    done.run();
                } else {
                    message(activity, activity.getString(errorText));
                }
            });
        });
    }

    private static void message(Activity activity, String text) {
        new AlertDialog.Builder(activity)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}

package net.basov.retrace;

import android.app.Activity;
import android.content.Intent;

import java.util.concurrent.ExecutorService;

import net.basov.retrace.core.Profile;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.Replay;

// ----------------------------------------------------------------------
// The start of a replay
// ----------------------------------------------------------------------
//
// The track screen starts a replay after the dialog that selects the
// reference track. The trip screen repeats a stopped replay with the last
// selection and the last speed. Both send the same intent to TrackService.
//
// A replay needs no location permission and no GPS. The automatic
// selection does not use the replayed track itself.
final class ReplayStarter {

    private ReplayStarter() {
    }

    // Shows the dialog. Then the replay starts and the trip screen opens.
    static void ask(Activity activity, ExecutorService files, String path, Profile trackProfile) {
        ReferenceDialog.show(activity, files, Replay.profileFor(trackProfile),
                (automatic, reference, reversed) -> {
                    start(activity, path, automatic, reference, reversed, Replay.DEFAULT_SPEED);
                    activity.startActivity(new Intent(activity, RunActivity.class));
                });
    }

    // Starts the stopped replay of the snapshot again. The trip screen is
    // open, thus it shows the new replay.
    static void again(Activity activity, RecordingSnapshot s) {
        start(activity, s.replayPath, s.automaticAgain(), s.referenceAgain(),
                s.referenceReversed, s.replaySpeed);
    }

    private static void start(Activity activity, String path, boolean automatic,
                              String reference, boolean reversed, int speed) {
        Intent replay = new Intent(activity, TrackService.class)
                .setAction(TrackService.ACTION_REPLAY)
                .putExtra(TrackService.EXTRA_REPLAY, path)
                .putExtra(TrackService.EXTRA_SPEED, speed);
        activity.startService(ReferenceDialog.putSelection(replay, automatic, reference, reversed));
    }
}

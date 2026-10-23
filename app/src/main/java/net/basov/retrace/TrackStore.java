package net.basov.retrace;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import net.basov.retrace.core.GpxReader;
import net.basov.retrace.core.GpxWriter;
import net.basov.retrace.core.PointLog;
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackPoint;

// ----------------------------------------------------------------------
// The files of the tracks
// ----------------------------------------------------------------------
//
// Retrace keeps its files in the media directory of the application, for
// example /storage/emulated/0/Android/media/net.basov.retrace/. This
// directory needs no storage permission. The user can copy it for a backup.
//
// - tracks/ holds one GPX file for each saved track.
// - current.log holds the points of the current track. See PointLog.
//
// TrackService and MainActivity each make their own TrackStore. One lock
// for the whole process keeps their file work in sequence.
final class TrackStore {

    private static final Object LOCK = new Object();

    private static final String TRACK_DIRECTORY = "tracks";
    private static final String LOG_FILE = "current.log";
    private static final String GPX = ".gpx";

    // A track with fewer points is not a track. The log of such a trip is
    // removed and not saved.
    static final int MIN_POINTS = 2;

    // One line of the list of tracks.
    static final class Entry {
        final File file;
        final String name;
        final Profile profile;
        final int points;
        final double distanceM;
        final long durationMs;
        final long startTimeMs;
        final boolean readable;

        Entry(File file, Track track) {
            this.file = file;
            this.name = track.name;
            this.profile = track.profile;
            this.points = track.points.size();
            this.distanceM = track.distanceM();
            this.durationMs = track.durationMs();
            this.startTimeMs = track.startTimeMs();
            this.readable = true;
        }

        Entry(File file) {
            this.file = file;
            this.name = file.getName();
            this.profile = null;
            this.points = 0;
            this.distanceM = 0;
            this.durationMs = 0;
            this.startTimeMs = TrackPoint.NO_TIME;
            this.readable = false;
        }
    }

    private final File base;
    private final File trackDirectory;
    private final File logFile;

    TrackStore(Context context) {
        base = mediaDirectory(context);
        trackDirectory = new File(base, TRACK_DIRECTORY);
        logFile = new File(base, LOG_FILE);
    }

    // The media directory is on the shared storage. If the shared storage is
    // not available, the private directory of the application is the
    // fallback.
    @SuppressWarnings("deprecation")
    private static File mediaDirectory(Context context) {
        File[] dirs = context.getExternalMediaDirs();
        if (dirs != null && dirs.length > 0 && dirs[0] != null) {
            File dir = dirs[0];
            if (dir.isDirectory() || dir.mkdirs()) {
                return dir;
            }
        }
        return context.getFilesDir();
    }

    File directory() {
        return base;
    }

    // Returns the saved tracks, the newest first.
    List<Entry> list() {
        synchronized (LOCK) {
            List<Entry> entries = new ArrayList<>();
            File[] files = trackDirectory.listFiles();
            if (files == null) {
                return entries;
            }
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(GPX)) {
                    continue;
                }
                try {
                    entries.add(new Entry(f, readGpx(f)));
                } catch (IOException e) {
                    entries.add(new Entry(f));
                }
            }
            Collections.sort(entries, (a, b) -> {
                if (a.startTimeMs != b.startTimeMs) {
                    return a.startTimeMs < b.startTimeMs ? 1 : -1;
                }
                return b.file.getName().compareTo(a.file.getName());
            });
            return entries;
        }
    }

    void rename(File file, String newName) throws IOException {
        synchronized (LOCK) {
            Track track = readGpx(file);
            writeGpx(track.withName(newName), file);
        }
    }

    boolean delete(File file) {
        synchronized (LOCK) {
            return file.delete();
        }
    }

    // Makes a new, empty log file and writes its header. An old log file is
    // first saved as a track. Thus a new trip never removes the points of an
    // earlier trip.
    Writer openLog(Profile profile, long startTimeMs) throws IOException {
        synchronized (LOCK) {
            saveLogLocked();
            if (!base.isDirectory() && !base.mkdirs()) {
                throw new IOException("cannot make " + base);
            }
            Writer log = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(logFile, false), StandardCharsets.UTF_8));
            log.write(PointLog.header(profile, startTimeMs));
            log.write('\n');
            log.flush();
            return log;
        }
    }

    // Saves the log file as a GPX file and removes the log file. Returns the
    // GPX file, or null if the log held too few points.
    File saveLog() throws IOException {
        synchronized (LOCK) {
            return saveLogLocked();
        }
    }

    // Saves the log file of a trip that a stopped process left. The check of
    // the service and the save are under one lock. Thus the activity never
    // takes the log file of a running service.
    File recoverLog() throws IOException {
        synchronized (LOCK) {
            if (TrackService.isActive()) {
                return null;
            }
            return saveLogLocked();
        }
    }

    private File saveLogLocked() throws IOException {
        if (!logFile.isFile()) {
            return null;
        }
        PointLog.Content content;
        try (Reader in = new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8)) {
            content = PointLog.read(in);
        }
        File saved = null;
        if (content != null && content.points.size() >= MIN_POINTS) {
            String name = TimeText.localName(content.startTimeMs, offsetMs(content.startTimeMs));
            Track track = new Track(name, content.profile, content.points);
            saved = newTrackFile(content.startTimeMs, content.profile);
            writeGpx(track, saved);
        }
        if (!logFile.delete()) {
            throw new IOException("cannot remove " + logFile);
        }
        return saved;
    }

    // The file name holds the local start time and the profile, for example
    // 20261023-140509-walking.gpx. A second file of the same second gets a
    // number.
    private File newTrackFile(long startTimeMs, Profile profile) throws IOException {
        if (!trackDirectory.isDirectory() && !trackDirectory.mkdirs()) {
            throw new IOException("cannot make " + trackDirectory);
        }
        String stem = TimeText.fileStamp(startTimeMs, offsetMs(startTimeMs)) + "-" + profile.id;
        File file = new File(trackDirectory, stem + GPX);
        for (int i = 2; file.exists(); i++) {
            file = new File(trackDirectory, stem + "-" + i + GPX);
        }
        return file;
    }

    private static Track readGpx(File file) throws IOException {
        String fallback = file.getName().substring(0, file.getName().length() - GPX.length());
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return GpxReader.read(in, fallback);
        }
    }

    // Writes to a temporary file first, then renames it. A stopped process
    // then cannot leave half a GPX file.
    private static void writeGpx(Track track, File file) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            GpxWriter.write(track, out);
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            throw new IOException("cannot rename " + temp);
        }
    }

    private static long offsetMs(long timeMs) {
        return TimeZone.getDefault().getOffset(timeMs);
    }
}

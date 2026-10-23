package net.basov.retrace;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

// ----------------------------------------------------------------------
// The read access to the GPX files for other applications
// ----------------------------------------------------------------------
//
// Android 7 and newer do not let an application send a file:// URI to a
// different application. AndroidX has FileProvider for this, but Retrace
// uses no library. This class is a small provider for the saved tracks
// only.
//
// The URI is content://<package>.tracks/<file name>. The provider is not
// exported. Another application can read a file only with the permission
// that the share intent gives for that one URI. The provider gives read
// access only, and only to a GPX file directly inside the track directory.
public final class TrackProvider extends ContentProvider {

    private static final String MIME_GPX = "application/gpx+xml";

    static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(file.getName())
                .build();
    }

    // The package name holds the suffix of a debug build. Thus a debug
    // build and a release build on one device have different authorities.
    // The manifest makes the same name from the application id.
    private static String authority(Context context) {
        return context.getPackageName() + ".tracks";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read only");
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    // Many applications ask for the name and the size before they read.
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File file;
        try {
            file = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_GPX;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    // The URI has one path segment: the name of the file. A name with a
    // directory part, for example "..", gives no file.
    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !name.endsWith(".gpx")
                || name.indexOf('/') >= 0 || name.startsWith(".")) {
            throw new FileNotFoundException("no track: " + uri);
        }
        File directory = new TrackStore(getContext()).trackDirectory();
        File file = new File(directory, name);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                    || !file.isFile()) {
                throw new FileNotFoundException("no track: " + uri);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("no track: " + uri);
        }
        return file;
    }
}

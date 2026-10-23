package net.basov.retrace;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.basov.retrace.core.TileCachePolicy;

// ----------------------------------------------------------------------
// The loader of map tiles
// ----------------------------------------------------------------------
//
// MapView asks for each tile on the screen with get(). A tile in memory comes
// back at once. Otherwise the loader reads it from the disk or downloads it
// on a background thread, then calls the listener on the main thread.
//
// The loader obeys the OpenStreetMap tile usage policy:
// - It sends a User-Agent that names Retrace and its version.
// - It downloads only tiles that MapView shows now. A task for a tile that
//   is no longer on the screen stops before its download.
// - It uses two connections at most. It does not call disconnect(). The
//   closed stream gives the connection back for the next tile.
// - It keeps each tile by the cache headers, or for at least 7 days.
// - After a failed download, it waits RETRY_MS before the next try.
//
// The queue gives the newest request first. The tiles of the last screen
// thus come before the tiles of an older screen.
final class TileLoader {

    static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";

    static final int MAX_TILE_BYTES = 1024 * 1024;

    private static final int THREADS = 2;
    private static final int TIMEOUT_MS = 15000;
    private static final long RETRY_MS = 30000;

    // A tile that the server does not have.
    private static final long MISSING_RETRY_MS = 3600000;

    interface Listener {
        // Called on the main thread when a new tile is in the memory cache.
        void onTileReady();
    }

    // A queue that gives the newest task first.
    private static final class NewestFirst extends LinkedBlockingDeque<Runnable> {
        @Override
        public boolean offer(Runnable task) {
            return offerFirst(task);
        }
    }

    private final TileCache cache;
    private final String userAgent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            THREADS, THREADS, 30, TimeUnit.SECONDS, new NewestFirst());

    // These fields are guarded by this object.
    private final Set<String> queued = new HashSet<>();
    private final Map<String, Long> failedUntil = new HashMap<>();

    private volatile Set<String> wanted = Collections.emptySet();
    private volatile Listener listener;
    private volatile boolean closed;

    TileLoader(Context context) {
        cache = new TileCache(context);
        userAgent = "Retrace/" + versionName(context) + " (Android; net.basov.retrace)";
        pool.allowCoreThreadTimeOut(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // Sets the tiles on the screen. MapView calls it after each draw.
    void setWanted(Set<String> keys) {
        wanted = keys;
    }

    // Returns a tile from memory, or null. With null, the loader loads the
    // tile. With network false, it reads only the disk.
    Bitmap get(int z, int x, int y, boolean network) {
        String key = TileCache.key(z, x, y);
        Bitmap bitmap = cache.fromMemory(key);
        if (bitmap == null && !closed) {
            request(z, x, y, key, network);
        }
        return bitmap;
    }

    void close() {
        closed = true;
        listener = null;
        pool.shutdownNow();
    }

    private void request(int z, int x, int y, String key, boolean network) {
        boolean useNetwork = network;
        synchronized (this) {
            if (queued.contains(key)) {
                return;
            }
            Long until = failedUntil.get(key);
            if (until != null && until > System.currentTimeMillis()) {
                useNetwork = false;
            }
            queued.add(key);
        }
        final boolean download = useNetwork;
        pool.execute(() -> load(z, x, y, key, download));
    }

    // Background thread.
    private void load(int z, int x, int y, String key, boolean network) {
        try {
            if (closed || !wanted.contains(key)) {
                return;
            }
            long now = System.currentTimeMillis();
            TileCache.DiskTile disk = cache.fromDisk(z, x, y, now);
            if (disk != null) {
                cache.toMemory(key, disk.bitmap);
                ready();
                if (!disk.stale) {
                    return;
                }
            }
            if (network && wanted.contains(key)) {
                download(z, x, y, key, now);
            }
        } finally {
            synchronized (this) {
                queued.remove(key);
            }
        }
    }

    private void download(int z, int x, int y, String key, long now) {
        try {
            URL url = new URL(String.format(Locale.ROOT, TILE_URL, z, x, y));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", userAgent);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                InputStream error = connection.getErrorStream();
                if (error != null) {
                    error.close();
                }
                failed(key, code == HttpURLConnection.HTTP_NOT_FOUND ? MISSING_RETRY_MS : RETRY_MS);
                return;
            }
            byte[] png;
            try (InputStream in = connection.getInputStream()) {
                png = TileCache.readAll(in, MAX_TILE_BYTES);
            }
            Bitmap bitmap = TileCache.decode(png);
            if (bitmap == null) {
                failed(key, RETRY_MS);
                return;
            }
            long expiry = TileCachePolicy.expiryMs(now,
                    connection.getHeaderField("Cache-Control"), connection.getExpiration());
            cache.toDisk(z, x, y, png, expiry);
            cache.toMemory(key, bitmap);
            ready();
        } catch (IOException e) {
            failed(key, RETRY_MS);
        }
    }

    private synchronized void failed(String key, long waitMs) {
        failedUntil.put(key, System.currentTimeMillis() + waitMs);
    }

    private void ready() {
        main.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onTileReady();
            }
        });
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}

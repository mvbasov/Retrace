package net.basov.retrace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import net.basov.retrace.core.TileCachePolicy;

// ----------------------------------------------------------------------
// The tile cache in memory and on the disk
// ----------------------------------------------------------------------
//
// The memory cache holds the decoded bitmaps. It uses one eighth of the
// memory of the application.
//
// The disk cache holds one file for each tile in the cache directory of the
// application: tiles/z/x/y.tile. The first 8 bytes of the file are the end
// time of the tile, and the PNG data follows. Thus the end time does not
// depend on the file times of the file system.
//
// A tile after its end time is stale. MapView still shows it, and TileLoader
// downloads it again when the network is available. Thus the map works with
// no network.
//
// Each method that reads or writes a file blocks the thread. Call it from
// a TileLoader thread only. The memory methods are safe on each thread.
final class TileCache {

    // The default size limit of the disk cache. A later milestone moves it
    // to Settings.
    static final long DISK_LIMIT_BYTES = 100L * 1024 * 1024;

    // The cache checks its size after this count of new tiles.
    private static final int WRITES_PER_TRIM = 50;

    private static final String SUFFIX = ".tile";

    // A disk tile and its state.
    static final class DiskTile {
        final Bitmap bitmap;
        final boolean stale;

        DiskTile(Bitmap bitmap, boolean stale) {
            this.bitmap = bitmap;
            this.stale = stale;
        }
    }

    private final File directory;
    private final LruCache<String, Bitmap> memory;
    private int writesSinceTrim;

    TileCache(Context context) {
        directory = new File(context.getCacheDir(), "tiles");
        int kilobytes = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<String, Bitmap>(kilobytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    static String key(int z, int x, int y) {
        return z + "/" + x + "/" + y;
    }

    Bitmap fromMemory(String key) {
        return memory.get(key);
    }

    void toMemory(String key, Bitmap bitmap) {
        memory.put(key, bitmap);
    }

    // Reads a tile from the disk. Returns null if the disk does not hold it.
    DiskTile fromDisk(int z, int x, int y, long nowMs) {
        File file = file(z, x, y);
        if (!file.isFile()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            long expiry = in.readLong();
            byte[] png = readAll(in, (int) Math.min(file.length(), TileLoader.MAX_TILE_BYTES));
            Bitmap bitmap = decode(png);
            if (bitmap == null) {
                file.delete();
                return null;
            }
            return new DiskTile(bitmap, expiry < nowMs);
        } catch (IOException e) {
            file.delete();
            return null;
        }
    }

    // Writes a tile to the disk. A temporary file and a rename keep a stopped
    // process from leaving half a tile.
    void toDisk(int z, int x, int y, byte[] png, long expiryMs) {
        File file = file(z, x, y);
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return;
        }
        File temp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temp))) {
            out.writeLong(expiryMs);
            out.write(png);
        } catch (IOException e) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            return;
        }
        if (countWrite()) {
            trim(DISK_LIMIT_BYTES);
        }
    }

    // Two loader threads write tiles. Thus the count has a lock.
    private synchronized boolean countWrite() {
        if (++writesSinceTrim < WRITES_PER_TRIM) {
            return false;
        }
        writesSinceTrim = 0;
        return true;
    }

    // Removes the oldest tiles when the disk cache is above the limit. The
    // file time of a tile is its download time.
    void trim(long limitBytes) {
        List<TileCachePolicy.Entry> entries = new ArrayList<>();
        collect(directory, entries);
        for (TileCachePolicy.Entry e : TileCachePolicy.toRemove(entries, limitBytes)) {
            new File(e.name).delete();
        }
    }

    private static void collect(File dir, List<TileCachePolicy.Entry> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(SUFFIX)) {
                out.add(new TileCachePolicy.Entry(f.getPath(), f.length(), f.lastModified()));
            }
        }
    }

    private File file(int z, int x, int y) {
        return new File(directory, z + File.separator + x + File.separator + y + SUFFIX);
    }

    // OpenStreetMap tiles have no transparent pixels. RGB_565 uses half the
    // memory of ARGB_8888.
    static Bitmap decode(byte[] png) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(png, 0, png.length, options);
    }

    static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > maxBytes) {
                throw new IOException("the tile is larger than " + maxBytes + " bytes");
            }
        }
        return out.toByteArray();
    }
}

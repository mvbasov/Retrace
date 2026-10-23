package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ----------------------------------------------------------------------
// The rules of the tile cache
// ----------------------------------------------------------------------
//
// The tile usage policy of OpenStreetMap asks each application to keep
// tiles by the HTTP cache headers, or for at least 7 days. Retrace keeps
// each tile until the later of the two times.
//
// The disk cache has a size limit. When the cache is larger, Retrace removes
// the tiles with the earliest end time first, down to TRIM_TARGET of the
// limit. The end time follows the download time, thus the oldest tiles go
// first.
public final class TileCachePolicy {

    public static final long MIN_KEEP_MS = 7L * 24 * 3600 * 1000;

    // After a trim, the cache uses this part of its limit. The free space
    // stops a trim after each new tile.
    public static final double TRIM_TARGET = 0.9;

    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*\"?(\\d+)");

    private TileCachePolicy() {
    }

    // Returns the end time of a tile that Retrace downloads now. The cache
    // control header can be null. An expires value of 0 or less means no
    // Expires header. A max-age value wins over the Expires header.
    public static long expiryMs(long nowMs, String cacheControl, long expiresMs) {
        long fromHeaders = 0;
        Long maxAge = maxAgeSeconds(cacheControl);
        if (maxAge != null) {
            fromHeaders = nowMs + maxAge * 1000L;
        } else if (expiresMs > 0) {
            fromHeaders = expiresMs;
        }
        return Math.max(fromHeaders, nowMs + MIN_KEEP_MS);
    }

    // Returns the value of max-age in seconds, or null.
    static Long maxAgeSeconds(String cacheControl) {
        if (cacheControl == null) {
            return null;
        }
        Matcher m = MAX_AGE.matcher(cacheControl.toLowerCase(Locale.ROOT));
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // One file of the disk cache.
    public static final class Entry {
        public final String name;
        public final long sizeBytes;
        public final long expiryMs;

        public Entry(String name, long sizeBytes, long expiryMs) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.expiryMs = expiryMs;
        }
    }

    // Returns the entries to remove. The list is empty while the cache is
    // inside its limit.
    public static List<Entry> toRemove(List<Entry> entries, long limitBytes) {
        long total = 0;
        for (Entry e : entries) {
            total += e.sizeBytes;
        }
        List<Entry> remove = new ArrayList<>();
        if (total <= limitBytes) {
            return remove;
        }
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> Long.compare(a.expiryMs, b.expiryMs));
        long target = (long) (limitBytes * TRIM_TARGET);
        for (Entry e : sorted) {
            if (total <= target) {
                break;
            }
            remove.add(e);
            total -= e.sizeBytes;
        }
        return remove;
    }
}

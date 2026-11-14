package net.basov.retrace.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// ----------------------------------------------------------------------
// A cache of values that Retrace reads from files
// ----------------------------------------------------------------------
//
// TrackStore reads each GPX file for the list of tracks and for the
// automatic selection. With some hundred tracks, that is slow. This cache
// keeps one value for each file.
//
// The key is the path. A value is valid while the time of the last change
// and the length of the file are the same. A different application can
// change a file, thus the cache checks the two numbers at each read.
// TrackStore also removes the value of each file that it writes or deletes.
//
// The class has no lock. The caller holds its own lock.
public final class SummaryCache<V> {

    private static final class Item<V> {
        final long modifiedMs;
        final long length;
        final V value;

        Item(long modifiedMs, long length, V value) {
            this.modifiedMs = modifiedMs;
            this.length = length;
            this.value = value;
        }
    }

    private final Map<String, Item<V>> items = new HashMap<>();

    // The value, or null if the file changed or has no value.
    public V get(String path, long modifiedMs, long length) {
        Item<V> item = items.get(path);
        if (item == null || item.modifiedMs != modifiedMs || item.length != length) {
            return null;
        }
        return item.value;
    }

    public void put(String path, long modifiedMs, long length, V value) {
        items.put(path, new Item<>(modifiedMs, length, value));
    }

    public void remove(String path) {
        items.remove(path);
    }

    // Removes the value of each path that is not in the collection.
    public void retain(Collection<String> paths) {
        Set<String> keep = new HashSet<>(paths);
        for (Iterator<String> it = items.keySet().iterator(); it.hasNext(); ) {
            if (!keep.contains(it.next())) {
                it.remove();
            }
        }
    }
}

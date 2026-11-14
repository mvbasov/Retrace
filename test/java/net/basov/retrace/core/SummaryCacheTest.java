package net.basov.retrace.core;

import java.util.Arrays;

// ----------------------------------------------------------------------
// The tests of SummaryCache
// ----------------------------------------------------------------------
//
// A wrong hit of the cache shows old data of a track. Thus a change of the
// time or of the length of a file must give a miss.
public final class SummaryCacheTest {

    private static int failures = 0;

    public static void main(String[] args) {
        aSameFileIsAHit();
        aChangedFileIsAMiss();
        removeAndRetain();

        if (failures > 0) {
            System.out.println("SummaryCacheTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("SummaryCacheTest: every check passed");
    }

    private static void aSameFileIsAHit() {
        SummaryCache<String> c = new SummaryCache<>();
        check("empty", null, c.get("a", 1, 2));
        c.put("a", 1, 2, "A");
        check("hit", "A", c.get("a", 1, 2));
    }

    // An edit of a file changes its time. An edit in the same second can
    // keep the time, but then the length usually changes.
    private static void aChangedFileIsAMiss() {
        SummaryCache<String> c = new SummaryCache<>();
        c.put("a", 1, 2, "A");
        check("new time", null, c.get("a", 3, 2));
        check("new length", null, c.get("a", 1, 5));
        c.put("a", 3, 2, "B");
        check("new value", "B", c.get("a", 3, 2));
    }

    // TrackStore removes the entry of each file that it writes or deletes,
    // and keeps only the files of the directory.
    private static void removeAndRetain() {
        SummaryCache<String> c = new SummaryCache<>();
        c.put("a", 1, 1, "A");
        c.put("b", 1, 1, "B");
        c.put("c", 1, 1, "C");
        c.remove("a");
        check("removed", null, c.get("a", 1, 1));
        c.retain(Arrays.asList("b"));
        check("kept", "B", c.get("b", 1, 1));
        check("not kept", null, c.get("c", 1, 1));
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}

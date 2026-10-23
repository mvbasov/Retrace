package net.basov.retrace.core;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Random;

// ----------------------------------------------------------------------
// The test of TimeText
// ----------------------------------------------------------------------
//
// TimeText replaces java.time on Android 6. The JVM of the tests has
// java.time. Thus the test compares the two on many moments.
public final class TimeTextTest {

    private static int failures = 0;

    public static void main(String[] args) {
        theFormatAgreesWithJavaTime();
        theParserAgreesWithJavaTime();
        theParserAcceptsEachZoneForm();
        theParserRejectsBadText();
        localNamesAndFileNames();
        durations();
        floorDivisionRoundsDown();

        if (failures > 0) {
            System.out.println("TimeTextTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TimeTextTest: every check passed");
    }

    // The day arithmetic is easy to break at a month end, at a leap day and
    // before 1970. Random moments over five centuries find such a fault.
    private static void theFormatAgreesWithJavaTime() {
        Random random = new Random(42);
        long span = 500L * 365 * 86400000L;
        for (int i = 0; i < 20000; i++) {
            long ms = (long) ((random.nextDouble() - 0.4) * span);
            String expected = Instant.ofEpochMilli(ms).toString();
            String actual = TimeText.isoUtc(ms);
            if (!expected.equals(actual)) {
                check("format of " + ms, expected, actual);
                return;
            }
        }
        check("leap day", "2024-02-29T23:59:59Z", TimeText.isoUtc(1709251199000L));
        check("before 1970", "1969-12-31T23:59:59.999Z", TimeText.isoUtc(-1L));
    }

    // The parser must read each text that the writer makes.
    private static void theParserAgreesWithJavaTime() {
        Random random = new Random(7);
        long span = 200L * 365 * 86400000L;
        for (int i = 0; i < 20000; i++) {
            long ms = (long) ((random.nextDouble() - 0.2) * span);
            long actual = TimeText.parseIso(TimeText.isoUtc(ms));
            if (actual != ms) {
                check("parse of " + ms, ms, actual);
                return;
            }
        }
    }

    // Other applications write the zone in several forms.
    private static void theParserAcceptsEachZoneForm() {
        String[] texts = {
                "2026-10-23T14:05:00+03:00",
                "2026-10-23T14:05:00+0300",
                "2026-10-23T14:05:00+03",
                "2026-10-23T06:05:00-05:00",
                "2026-10-23T11:05:00Z",
                "2026-10-23T11:05:00",
                "2026-10-23T11:05:00.000Z",
                "2026-10-23 11:05:00z",
        };
        long expected = OffsetDateTime.parse("2026-10-23T11:05:00Z").toInstant().toEpochMilli();
        for (String t : texts) {
            check("zone form " + t, expected, TimeText.parseIso(t));
        }
        check("long fraction", expected + 123, TimeText.parseIso("2026-10-23T11:05:00.123456Z"));
        check("short fraction", expected + 500, TimeText.parseIso("2026-10-23T11:05:00.5Z"));
    }

    // A bad time must give an exception, and not a wrong moment.
    private static void theParserRejectsBadText() {
        String[] texts = {"", "2026-10-23", "2026/10/23T11:05:00Z", "2026-13-01T00:00:00Z",
                "2026-10-23T11:05:00*03:00", "yesterday"};
        for (String t : texts) {
            boolean thrown = false;
            try {
                TimeText.parseIso(t);
            } catch (IllegalArgumentException e) {
                thrown = true;
            }
            check("reject \"" + t + "\"", true, thrown);
        }
    }

    // The name and the file name use the local time. Moscow is three hours
    // ahead of UTC.
    private static void localNamesAndFileNames() {
        long ms = TimeText.parseIso("2026-10-23T11:05:09Z");
        long moscow = 3 * 3600000L;
        check("local name", "2026-10-23 14:05", TimeText.localName(ms, moscow));
        check("file stamp", "20261023-140509", TimeText.fileStamp(ms, moscow));
        check("file stamp next day", "20261024-000509",
                TimeText.fileStamp(ms, 12 * 3600000L + 3600000L));
    }

    private static void durations() {
        check("zero", "0:00", TimeText.duration(0));
        check("seconds", "0:59", TimeText.duration(59999));
        check("minutes", "42:10", TimeText.duration((42 * 60 + 10) * 1000L));
        check("hours", "1:02:03", TimeText.duration((3600 + 2 * 60 + 3) * 1000L));
        check("negative", "0:00", TimeText.duration(-5000));
    }

    // The copies of floorDiv and floorMod must agree with the Java methods.
    private static void floorDivisionRoundsDown() {
        long[] values = {-7, -6, -1, 0, 1, 6, 7, Long.MIN_VALUE / 2, Long.MAX_VALUE / 2};
        long[] divisors = {1, 3, 7, 1000, 86400000L};
        for (long x : values) {
            for (long y : divisors) {
                check("floorDiv " + x + "/" + y, Math.floorDiv(x, y), TimeText.floorDiv(x, y));
                check("floorMod " + x + "%" + y, Math.floorMod(x, y), TimeText.floorMod(x, y));
            }
        }
    }

    private static void check(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}

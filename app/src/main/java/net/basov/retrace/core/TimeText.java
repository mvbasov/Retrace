package net.basov.retrace.core;

// ----------------------------------------------------------------------
// Time as text: GPX times, track names, file names and durations
// ----------------------------------------------------------------------
//
// Android 6 has no java.time. SimpleDateFormat on Android 6 cannot read the
// zone form "+03:00" of a GPX time. Thus this class converts between a date
// and a day number with integer arithmetic only.
//
// The day conversion is the "days from civil" algorithm of Howard Hinnant.
// It is correct for each date of the proleptic Gregorian calendar.
public final class TimeText {

    private static final long MS_PER_DAY = 86400000L;

    private TimeText() {
    }

    // Returns a GPX time, for example 2026-10-23T11:05:00Z. The text holds
    // milliseconds only when they are not zero.
    public static String isoUtc(long timeMs) {
        StringBuilder out = new StringBuilder(24);
        appendDateTime(out, timeMs, '-', 'T', ':');
        long ms = floorMod(timeMs, 1000L);
        if (ms != 0) {
            out.append('.');
            pad(out, ms, 3);
        }
        return out.append('Z').toString();
    }

    // Reads a GPX time. The zone can be Z, +hh:mm, +hhmm or +hh. A time with
    // no zone is UTC. The fraction of a second can have any length.
    public static long parseIso(String text) {
        String s = text.trim();
        try {
            int year = Integer.parseInt(s.substring(0, 4));
            int month = Integer.parseInt(s.substring(5, 7));
            int day = Integer.parseInt(s.substring(8, 10));
            int hour = Integer.parseInt(s.substring(11, 13));
            int minute = Integer.parseInt(s.substring(14, 16));
            int second = Integer.parseInt(s.substring(17, 19));
            if (s.charAt(4) != '-' || s.charAt(7) != '-' || s.charAt(13) != ':'
                    || s.charAt(16) != ':' || "Tt ".indexOf(s.charAt(10)) < 0) {
                throw new IllegalArgumentException("not a GPX time: " + text);
            }
            if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23
                    || minute > 59 || second > 60) {
                throw new IllegalArgumentException("not a GPX time: " + text);
            }

            int pos = 19;
            long ms = 0;
            if (pos < s.length() && s.charAt(pos) == '.') {
                int start = ++pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
                String fraction = (s.substring(start, pos) + "000").substring(0, 3);
                ms = Integer.parseInt(fraction);
            }

            long offsetMs = 0;
            if (pos < s.length()) {
                offsetMs = zoneOffsetMs(s.substring(pos), text);
            }

            long days = daysFromCivil(year, month, day);
            long seconds = days * 86400L + hour * 3600L + minute * 60L + second;
            return seconds * 1000L + ms - offsetMs;
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("not a GPX time: " + text, e);
        }
    }

    // Returns the default name of a track, for example 2026-10-23 14:05.
    // The offset is the local time zone offset for that time.
    public static String localName(long timeMs, long offsetMs) {
        StringBuilder out = new StringBuilder(16);
        appendDateTime(out, timeMs + offsetMs, '-', ' ', ':');
        // Remove the seconds. A track name does not need them.
        out.setLength(out.length() - 3);
        return out.toString();
    }

    // Returns the time part of a file name, for example 20261023-140512.
    public static String fileStamp(long timeMs, long offsetMs) {
        StringBuilder out = new StringBuilder(15);
        appendDateTime(out, timeMs + offsetMs, (char) 0, '-', (char) 0);
        return out.toString();
    }

    // Returns a duration as h:mm:ss, or as m:ss below one hour.
    public static String duration(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long hours = total / 3600L;
        long minutes = (total / 60L) % 60L;
        long seconds = total % 60L;
        StringBuilder out = new StringBuilder();
        if (hours > 0) {
            out.append(hours).append(':');
            pad(out, minutes, 2);
        } else {
            out.append(minutes);
        }
        out.append(':');
        pad(out, seconds, 2);
        return out.toString();
    }

    // Returns the number of days from 1970-01-01 to the date.
    static long daysFromCivil(long year, long month, long day) {
        long y = month <= 2 ? year - 1 : year;
        long era = floorDiv(y, 400L);
        long yearOfEra = y - era * 400L;
        long monthIndex = month > 2 ? month - 3 : month + 9;
        long dayOfYear = (153L * monthIndex + 2L) / 5L + day - 1L;
        long dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear;
        return era * 146097L + dayOfEra - 719468L;
    }

    // Writes the date and the time of a moment in UTC. A separator of zero
    // writes no character.
    private static void appendDateTime(StringBuilder out, long timeMs,
                                       char dateSep, char middle, char timeSep) {
        long days = floorDiv(timeMs, MS_PER_DAY);
        long secondOfDay = floorMod(timeMs, MS_PER_DAY) / 1000L;

        long z = days + 719468L;
        long era = floorDiv(z, 146097L);
        long dayOfEra = z - era * 146097L;
        long yearOfEra = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L
                - dayOfEra / 146096L) / 365L;
        long dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L);
        long monthIndex = (5L * dayOfYear + 2L) / 153L;
        long day = dayOfYear - (153L * monthIndex + 2L) / 5L + 1L;
        long month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9;
        long year = yearOfEra + era * 400L + (month <= 2 ? 1 : 0);

        pad(out, year, 4);
        separator(out, dateSep);
        pad(out, month, 2);
        separator(out, dateSep);
        pad(out, day, 2);
        separator(out, middle);
        pad(out, secondOfDay / 3600L, 2);
        separator(out, timeSep);
        pad(out, (secondOfDay / 60L) % 60L, 2);
        separator(out, timeSep);
        pad(out, secondOfDay % 60L, 2);
    }

    private static long zoneOffsetMs(String zone, String text) {
        if (zone.equals("Z") || zone.equals("z")) {
            return 0;
        }
        char sign = zone.charAt(0);
        if (sign != '+' && sign != '-') {
            throw new IllegalArgumentException("not a GPX time: " + text);
        }
        String digits = zone.substring(1).replace(":", "");
        if (digits.length() != 2 && digits.length() != 4) {
            throw new IllegalArgumentException("not a GPX time: " + text);
        }
        int hours = Integer.parseInt(digits.substring(0, 2));
        int minutes = digits.length() == 4 ? Integer.parseInt(digits.substring(2, 4)) : 0;
        long offset = (hours * 60L + minutes) * 60000L;
        return sign == '-' ? -offset : offset;
    }

    // Android adds Math.floorDiv and Math.floorMod at API 24. These copies
    // round toward negative infinity, the same as the Java methods.
    static long floorDiv(long x, long y) {
        long q = x / y;
        if ((x % y != 0) && ((x < 0) != (y < 0))) {
            q--;
        }
        return q;
    }

    static long floorMod(long x, long y) {
        return x - floorDiv(x, y) * y;
    }

    private static void separator(StringBuilder out, char c) {
        if (c != 0) {
            out.append(c);
        }
    }

    private static void pad(StringBuilder out, long value, int width) {
        String digits = Long.toString(value);
        for (int i = digits.length(); i < width; i++) {
            out.append('0');
        }
        out.append(digits);
    }
}

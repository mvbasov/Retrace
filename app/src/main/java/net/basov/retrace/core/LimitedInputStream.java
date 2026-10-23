package net.basov.retrace.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

// ----------------------------------------------------------------------
// An input stream with a size limit
// ----------------------------------------------------------------------
//
// A file from another application can be very large. The GPX reader keeps
// all points in memory. This stream stops the read at the limit, before the
// memory of the application is full.
public final class LimitedInputStream extends FilterInputStream {

    // The read went past the limit.
    public static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        TooLargeException(long limit) {
            super("larger than " + limit + " bytes");
        }
    }

    private final long limit;
    private long count;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = super.read(buffer, offset, length);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        add(skipped);
        return skipped;
    }

    // Marks would let a reader read the same bytes two times.
    @Override
    public boolean markSupported() {
        return false;
    }

    private void add(long n) throws TooLargeException {
        count += n;
        if (count > limit) {
            throw new TooLargeException(limit);
        }
    }
}

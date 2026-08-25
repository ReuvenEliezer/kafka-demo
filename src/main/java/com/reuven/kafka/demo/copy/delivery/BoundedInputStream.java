package com.reuven.kafka.demo.copy.delivery;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bounds reads to exactly {@code limit} bytes of the delegate, then reports EOF, without ever
 * closing the delegate — the same underlying provider stream is shared across every part of a
 * chunked transfer (R3's pass-through streaming).
 */
final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    BoundedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = super.read();
        if (b >= 0) {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        int n = super.read(b, off, toRead);
        if (n > 0) {
            remaining -= n;
        }
        return n;
    }

    @Override
    public void close() {
        // Deliberately not closing the delegate — see class javadoc.
    }
}

package com.reuven.kafka.demo.copy.delivery;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Checksum;

/**
 * Accumulates a checksum and a byte count over whatever passes through, without buffering any of
 * it — the local half of the per-part integrity comparison (research.md R14 layer 1).
 */
final class CountingChecksumInputStream extends FilterInputStream {

    private final Checksum checksum;
    private long bytesRead;

    CountingChecksumInputStream(InputStream in, Checksum checksum) {
        super(in);
        this.checksum = checksum;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            checksum.update(b);
            bytesRead++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            checksum.update(b, off, n);
            bytesRead += n;
        }
        return n;
    }

    long bytesRead() {
        return bytesRead;
    }

    long checksumValue() {
        return checksum.getValue();
    }
}

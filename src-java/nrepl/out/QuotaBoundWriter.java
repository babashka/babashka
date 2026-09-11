package nrepl.out;

import java.io.*;

/**
 * Wrapper around <code>java.io.Writer</code> that throws
 * <code>nrepl.out.QuotaExceeded</code> once it has written more than quota
 * bytes.
 */
public class QuotaBoundWriter extends Writer {

    private final Writer wrappedWriter;
    private final int quota;
    private int remaining;

    public QuotaBoundWriter(Writer writer, int quotaBytes) {
        super();
        if (quotaBytes <= 0) {
            throw new IllegalArgumentException("Invalid quota: " + quotaBytes);
        }
        this.wrappedWriter = writer;
        this.quota = quotaBytes;
        this.remaining = quotaBytes;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        synchronized (this.lock) {
            boolean tooBig = (len > remaining);
            len = Math.min(len, remaining);
            if (len > 0) {
                wrappedWriter.write(cbuf, off, len);
                remaining -= len;
            }
            if (tooBig)
                throw new QuotaExceeded();
        }
    }

    @Override
    public void flush() throws IOException {
        wrappedWriter.flush();
    }

    @Override
    public void close() throws IOException {
        wrappedWriter.close();
    }

    @Override
    public String toString() {
        return wrappedWriter.toString();
    }
}

package nrepl.out;

import java.io.*;

/**
 * TeeOutputStream wraps a regular OutputStream and an additional Writer.
 * Everything that goes through this stream is teed into both wrapped outputs.
 */
public class TeeOutputStream extends OutputStream {

    private final OutputStream out1;
    private final OutputStream out2;
    private volatile boolean flushing = false;

    public TeeOutputStream(OutputStream out1, OutputStream out2) {
        this.out1 = out1;
        this.out2 = out2;
    }

    @Override
    public void write(int b) throws IOException {
        out1.write(b);
        out2.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out1.write(b, off, len);
        out2.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        // Prevent recursive flushing loops.
        if (!flushing) {
            flushing = true;
            try {
                out1.flush();
                out2.flush();
            } finally {
                flushing = false;
            }
        }
    }

    @Override
    public void close() throws IOException {
        try { out1.close(); }
        finally { out2.close(); }
    }
}

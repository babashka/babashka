package nrepl.out;

import clojure.lang.IFn;
import java.io.*;
import java.nio.charset.Charset;

/**
 * An OutputStream that buffers incoming bytes and sends complete lines
 * (ending with newline) to a network sender. Incomplete lines are held
 * in buffer until completed.
 */
public class CallbackBufferedOutputStream extends OutputStream {
    private final IFn callback;
    private final ByteArrayOutputStream buffer;
    private final int bufferSize;
    private final Charset charset;
    private final Object bufferLock = new Object();

    public CallbackBufferedOutputStream(IFn callback, int bufferSize) {
        this.callback = callback;
        this.bufferSize = bufferSize;
        this.charset = Charset.forName("UTF-8");
        this.buffer = new ByteArrayOutputStream();
    }

    private static boolean isSingleByteChar(byte b) {
        return (b & 0b10000000) == 0;
    }

    private static boolean isCharacterStartingByte(byte b) {
        return isSingleByteChar(b) ||
            (b & 0b11000000) == 0b11000000; // start of multi-byte UTF-8 character
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    @Override
    public void write(int b) throws IOException {
        synchronized (bufferLock) {
            if (isSingleByteChar((byte)b)) {
                buffer.write(b);
                if (b == '\n' || buffer.size() >= bufferSize)
                    maybeFlush(false);
            } else {
                // If we are behind flush because of multi-byte characters,
                // flush first and then append to fresh buffer.
                if (buffer.size() >= bufferSize && isCharacterStartingByte((byte)b))
                    maybeFlush(false);
                buffer.write(b);
            }
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        synchronized (bufferLock) {
            int end = off + len;
            while (off < end) {
                // Make sure to clamp to at least 1 character because
                // buffer.size() might run above bufferSize if multi-byte
                // characters are involved.
                int writeLen = clamp(bufferSize - buffer.size(), 1, len);
                boolean canFlush = false;
                if (isSingleByteChar(b[off+writeLen-1])) {
                    canFlush = true;
                } else {
                    // Scan until the next character-starting byte to avoid
                    // character tearing.
                    while (writeLen+1 < len) {
                        if (isCharacterStartingByte(b[off+writeLen])) {
                            canFlush = true;
                            break;
                        }
                        writeLen++;
                    }
                }
                buffer.write(b, off, writeLen);
                if (canFlush) maybeFlush(false);
                off += writeLen;
                len -= writeLen;
            }
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (bufferLock) {
            maybeFlush(true);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (bufferLock) {
            maybeFlush(true);
        }
    }

    /**
     * Invoke callback with the text accumulated in the buffer if it contains
     * newlines or went beyond buffer size or force is true.
     */
    private void maybeFlush(boolean force) throws IOException {
        int size = buffer.size();
        if (size == 0) return;

        String content = buffer.toString(charset.name());
        int length = content.length();
        int lastNewlineIndex = content.lastIndexOf('\n');
        int lengthToFlush = (force || size >= bufferSize) ? length : lastNewlineIndex + 1;
        if (lengthToFlush > 0) {
            String flushContent = content.substring(0, lengthToFlush);
            callback.invoke(flushContent);
            buffer.reset();
            if (lengthToFlush < length) {
                String remaining = content.substring(lengthToFlush);
                buffer.write(remaining.getBytes(charset));
            }
        }
    }
}

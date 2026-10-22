package nrepl.in;

import clojure.lang.IFn;
import java.io.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * An implementation of Reader which, when read is attempted, pulls characters
 * out of a wrapped LinkedBlockingQueue. Not fully thread-safe! The producers
 * should only interact with the thread-safe queue (via addInput() and addEof()
 * methods), and there should be only one consumer thread.
 */
public class QueuePollingReader extends Reader {

    private static final String EOF_MARKER = new String();

    private final IFn sendNeedInputRequest;
    private final LinkedBlockingQueue<String> queue;

    private String currentInput = null;
    private int currentInputIndex = 0;

    public QueuePollingReader(IFn sendNeedInputRequest) {
        this.queue = new LinkedBlockingQueue<>();
        this.sendNeedInputRequest = sendNeedInputRequest;
    }

    /** Return the next input char only if it's already present in the buffer or
     * in the queue. Return -1 if the char is not available, or -2 if the EOF
     * marker is found.
     */
    private int pollInputChar() {
        while (true) {
            if (currentInput == EOF_MARKER) {
                // Intentionally don't clear EOF marker, let the caller do it.
                return -2;
            }
            if (currentInput != null && currentInputIndex < currentInput.length()) {
                char c = currentInput.charAt(currentInputIndex);
                return c;
            }
            currentInputIndex = 0;
            currentInput = queue.poll();
            if (currentInput == null) return -1;
        }
    }

    /** Return the next input char. If the buffer and the queue are empty, send
     * the :need-input message to the client and block on the queue until the
     * next input is available. Return -1 if EOF is reached.
     */
    private int readInputChar() {
        while (true) {
            int c = pollInputChar();
            switch (c) {
            case -2:
                currentInput = null; // Clear EOF marker
                return -1;
            case -1:
                sendNeedInputRequest.invoke();
                try { currentInput = queue.take(); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                continue;
            default:
                currentInputIndex++; // Advance read pointer
                return c;
            }
        }
    }

    public void addInput(String input) {
        queue.add(input);
    }

    public void addEof() {
        queue.add(EOF_MARKER);
    }

    @Override
    public void close() throws IOException {
        queue.clear();
    }

    @Override
    public boolean ready() throws IOException {
        return pollInputChar() >= 0;
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        if (len == 0) return 0;

        // First char taken from an empty queue will cause `needs-input` message
        // to be sent to the client.
        int firstChar = readInputChar();
        if (firstChar < 0) return -1;
        buf[off] = (char)firstChar;

        // For subsequent read chars, only poll the queue for data already
        // there, and when queue becomes empty, return the number of chars read.
        int i = 1;
        while (i < len) {
            // Peek, so that an EOF marker stays in place and is reported by the
            // next read() instead of being swallowed here.
            int c = pollInputChar();
            if (c < 0) break;
            currentInputIndex++;
            buf[off + i++] = (char)c;
        }
        return i;
    }
}

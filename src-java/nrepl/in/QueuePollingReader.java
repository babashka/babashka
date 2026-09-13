package nrepl.in;

import clojure.lang.IFn;
import java.io.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * An implementation of Reader which, when read is attempted, pulls characters
 * out of a wrapped LinkedBlockingQueue.
 */
public class QueuePollingReader extends Reader {

    private final LinkedBlockingQueue queue;
    private final IFn requestInput;

    public QueuePollingReader(LinkedBlockingQueue queue, IFn requestInput) {
        this.queue = queue;
        this.requestInput = requestInput;
    }

    @Override
    public void close() throws IOException {
        queue.clear();
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        if (len == 0) return 0;

        // First char reading will cause `needs-input` message to be sent to the
        // client (if the queue doesn't contain data already). This logic is
        // implemented inside requestInput function.
        Object firstChar = requestInput.invoke();
        if (firstChar == null || firstChar.equals(-1)) return -1;
        buf[off] = (char)firstChar;

        // For subsuquent read chars, only poll the queue for data already
        // there, and when queue becomes empty, return the number of chars read.
        // BB-PATCH drain under the monitor the stdin op holds while it enqueues
        // a chunk, so a chunk is seen whole: a partial drain leaves the reader
        // one delimiter short and it asks for input a second time.
        int i = 1;
        synchronized (queue) {
            while (i < len) {
                Object c = queue.poll();
                if (c == null) break;
                buf[off + i++] = (char)c;
            }
        }
        return i;
    }
}

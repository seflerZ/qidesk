package com.qihua.bVNC.ssh;

import android.util.Log;

import com.trilead.ssh2.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * Phase 2 bridge between trilead's SSH {@link Session} and TermSession.
 *
 * <h2>Architecture</h2>
 * The challenge: TermSession refuses a null stream and immediately
 * {@code finish()}-es itself when its input stream returns -1. But the
 * trilead {@code Session} doesn't exist until {@code openShellSession()}
 * finishes on the SSH-Connect thread (1-10s after the user lands on the
 * terminal screen). We sit between them with a pair of pipes that
 * forward bytes once attach() binds the real trilead streams.
 *
 * <pre>
 *   remote shell ──► session.getStdout() ──► readPump ──► terminalInSink
 *                                                              │
 *                                                              ▼
 *   TermSession ◄── terminalIn (PipedInputStream) ◄───────────┘
 *   TermSession ──► terminalOutSink (PipedOutputStream) ──► writePump
 *                                                              │
 *                                                              ▼
 *   remote shell ◄── session.getStdin()  ◄─────────────────────┘
 * </pre>
 *
 * <h2>Why the pipe absorbs two failure modes</h2>
 * <ul>
 *   <li><b>Connect not yet complete.</b> TermSession's writer thread
 *       tries to flush queued keystrokes via termOutSink.write(); the
 *       pipe absorbs them in 8KB. The writePump only reads from the
 *       pipe AFTER attach() binds the real trilead stream. Pre-connect
 *       keystrokes are buffered, not lost.</li>
 *   <li><b>Transient network blip.</b> readPump logs the IOException
 *       and sleeps, doesn't close terminalInSink. TermSession keeps
 *       blocking on its read — preserving keyboard input through the
 *       blip. On teardown we close the pipe FIRST, which makes
 *       TermSession's reader wake with EOF and {@code finish()} return
 *       promptly.</li>
 * </ul>
 *
 * <h2>Architectural parallel</h2>
 * Mirrors the RDP / NVStream "independent worker + drawBitmap" model:
 * the trilead background thread drives the worker-internal state
 * machine (TermSession), and the UpdateCallback fires the only paint
 * driver. The difference is the worker outputs a state mutation (a
 * character grid) rather than raw pixels, so
 * {@code SshTerminalRenderer.renderInto(Bitmap)} is the in-place write.
 */
public class SshShellChannel {
    private static final String TAG = "SshShellChannel";

    /** 8KB so a paste of a screenful doesn't block the writer. */
    private static final int PIPE_SIZE = 8 * 1024;

    // Pipe pair #1: shell stdout → TermSession input.
    // readPump writes terminalInSink; TermSession reads from terminalIn.
    private final PipedInputStream terminalIn;
    private final PipedOutputStream terminalInSink;

    // Pipe pair #2: TermSession output → shell stdin.
    // TermSession writes terminalOutSink; writePump reads terminalOutSource.
    private final PipedInputStream terminalOutSource;
    private final PipedOutputStream terminalOutSink;

    private Thread readPump;
    private Thread writePump;
    private volatile Session session;
    private volatile boolean running;

    public SshShellChannel() throws IOException {
        terminalIn = new PipedInputStream(PIPE_SIZE);
        terminalInSink = new PipedOutputStream();
        terminalOutSource = new PipedInputStream(PIPE_SIZE);
        terminalOutSink = new PipedOutputStream();

        // Connect MUST happen before either end is used, per PipedStream contract.
        terminalInSink.connect(terminalIn);
        terminalOutSink.connect(terminalOutSource);
    }

    /** TermSession.setTermIn — bytes here are the remote shell's output. */
    public InputStream getTerminalIn() {
        return terminalIn;
    }

    /** TermSession.setTermOut — bytes written here go to the remote shell's stdin. */
    public OutputStream getTerminalOut() {
        return terminalOutSink;
    }

    /**
     * No-op kept for interface symmetry with the Phase 1 fake-shell.
     * The real work happens in {@link #attach(Session)}.
     */
    public void start() {
        // Intentionally empty: pumps are spawned by attach() once we have a Session.
    }

    /**
     * Bind this channel to a trilead {@link Session} that has been opened
     * via {@code session.startShell()}. Spawns read+write pump threads
     * that shuttle bytes between trilead and the pipes. Called from the
     * SSH-Connect background thread after auth succeeds.
     */
    public synchronized void attach(Session sshSession) {
        if (running) {
            Log.w(TAG, "attach: already running, ignoring");
            return;
        }
        if (sshSession == null) {
            Log.e(TAG, "attach: sshSession is null");
            return;
        }
        this.session = sshSession;
        running = true;

        readPump = new Thread(this::readPumpLoop, "SSH-Shell-ReadPump");
        readPump.setDaemon(true);
        readPump.start();

        writePump = new Thread(this::writePumpLoop, "SSH-Shell-WritePump");
        writePump.setDaemon(true);
        writePump.start();

        Log.i(TAG, "attach: read+write pumps started");
    }

    private void readPumpLoop() {
        InputStream src;
        try {
            src = session.getStdout();
        } catch (Exception e) {
            Log.e(TAG, "readPump: getStdout failed", e);
            return;
        }
        if (src == null) {
            Log.e(TAG, "readPump: getStdout returned null");
            return;
        }
        byte[] buf = new byte[1024];
        while (running) {
            int n;
            try {
                n = src.read(buf);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "readPump: read error (transient?): " + e.getMessage());
                }
                // Network blip or session closed. DON'T close terminalInSink
                // here — let TermSession keep blocking so the user can
                // reconnect or close the session explicitly. close() in
                // teardown is the one place that closes the pipe.
                try { Thread.sleep(100); } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            if (n < 0) {
                Log.i(TAG, "readPump: stdout returned -1 (remote end closed)");
                // Same reasoning as IOException: don't kill TermSession.
                try { Thread.sleep(100); } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            try {
                terminalInSink.write(buf, 0, n);
                terminalInSink.flush();
            } catch (IOException e) {
                if (running) Log.w(TAG, "readPump: pipe write failed (likely teardown)", e);
                return;
            }
        }
    }

    private void writePumpLoop() {
        OutputStream dst;
        try {
            dst = session.getStdin();
        } catch (Exception e) {
            Log.e(TAG, "writePump: getStdin failed", e);
            return;
        }
        if (dst == null) {
            Log.e(TAG, "writePump: getStdin returned null");
            return;
        }
        byte[] buf = new byte[1024];
        while (running) {
            int n;
            try {
                n = terminalOutSource.read(buf);
            } catch (IOException e) {
                if (running) Log.w(TAG, "writePump: pipe read failed", e);
                return;
            }
            if (n < 0) {
                // TermSession closed its end (e.g. on finish()). Stop.
                Log.i(TAG, "writePump: pipe EOF, exiting");
                return;
            }
            try {
                dst.write(buf, 0, n);
                dst.flush();
            } catch (IOException e) {
                if (running) Log.w(TAG, "writePump: stdin write failed", e);
                return;
            }
        }
    }

    /**
     * Tear down: stop pumps, close pipes, close the trilead Session. Safe
     * to call multiple times. Order matters:
     * <ol>
     *   <li>{@code running = false} — pumps exit on their next loop iter.</li>
     *   <li>close the {@code terminalIn} pipe — wakes TermSession's
     *       internal reader with EOF, so {@code termSession.finish()}
     *       returns promptly.</li>
     *   <li>close the {@code terminalOut} pipe — unblocks the write pump.</li>
     *   <li>close the trilead {@code Session} — tears down the SSH channel.</li>
     *   <li>interrupt the pump threads (already exited by now; safety net).</li>
     * </ol>
     */
    public void close() {
        Log.w(TAG, "close: closing SSH channel (stack=" + new Throwable().getStackTrace()[1] + ")");
        running = false;

        // 1. Close the read pipe first so TermSession can finish.
        try { terminalInSink.close(); } catch (IOException ignored) {}
        try { terminalIn.close(); } catch (IOException ignored) {}

        // 2. Close the write pipe so the write pump exits.
        try { terminalOutSink.close(); } catch (IOException ignored) {}
        try { terminalOutSource.close(); } catch (IOException ignored) {}

        // 3. Close the trilead Session.
        Session s = session;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable t) {
                Log.w(TAG, "close: session.close failed", t);
            }
        }

        // 4. Belt-and-braces: interrupt pump threads in case they're
        // still blocked in a syscall (e.g. read on a now-closed pipe
        // throws InterruptedIOException only when interrupted).
        if (readPump != null) {
            readPump.interrupt();
        }
        if (writePump != null) {
            writePump.interrupt();
        }
    }
}

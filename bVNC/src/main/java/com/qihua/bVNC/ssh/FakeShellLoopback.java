package com.qihua.bVNC.ssh;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * Phase 1 stand-in for a real SSH channel. Owns two pipes:
 *
 *   TermSession.getTermOut  ──►  fromTerminalSink  ──►  fromTerminalSource  ──►  echo thread
 *   echo thread             ──►  toTerminalSink    ──►  toTerminalSource    ──►  TermSession.getTermIn
 *
 * On start() we push a welcome banner + "$ " prompt into the to-terminal
 * pipe so the user has something visible the moment the terminal opens.
 * The echo thread reads each byte the user types, echoes it back, and on
 * "\r" or "\n" emits "\r\n$ " so the cursor drops to a fresh prompt line.
 *
 * Phase 2 will discard this whole class and feed TermSession's streams
 * directly into trilead's Session.getStdout / getStdin.
 */
public class FakeShellLoopback {
    private static final String TAG = "FakeShellLoopback";

    private static final byte[] WELCOME = (
            "Hello from fake shell!\r\n"
          + "Type something and press Enter.\r\n"
          + "$ "
    ).getBytes();

    private static final byte[] PROMPT = "\r\n$ ".getBytes();

    /** Bigger than the default 1KB so a paste of a screenful doesn't block. */
    private static final int PIPE_SIZE = 8 * 1024;

    private final PipedInputStream toTerminalSource = new PipedInputStream(PIPE_SIZE);
    private final PipedOutputStream toTerminalSink = new PipedOutputStream();
    private final PipedInputStream fromTerminalSource = new PipedInputStream(PIPE_SIZE);
    private final PipedOutputStream fromTerminalSink = new PipedOutputStream();

    private Thread echoThread;
    private volatile boolean running;

    public FakeShellLoopback() throws IOException {
        toTerminalSink.connect(toTerminalSource);
        fromTerminalSink.connect(fromTerminalSource);
    }

    /** TermSession.setTermIn — bytes here become terminal output the user sees. */
    public InputStream getTerminalIn() {
        return toTerminalSource;
    }

    /** TermSession.setTermOut — TermSession writes user keystrokes here. */
    public OutputStream getTerminalOut() {
        return fromTerminalSink;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            toTerminalSink.write(WELCOME);
            toTerminalSink.flush();
        } catch (IOException e) {
            Log.w(TAG, "welcome write failed", e);
        }
        echoThread = new Thread(this::echoLoop, "FakeShell-Echo");
        echoThread.setDaemon(true);
        echoThread.start();
    }

    private void echoLoop() {
        byte[] buf = new byte[256];
        while (running) {
            int read;
            try {
                read = fromTerminalSource.read(buf);
            } catch (IOException e) {
                if (running) Log.d(TAG, "read interrupted: " + e.getMessage());
                return;
            }
            if (read < 0) return;
            try {
                for (int i = 0; i < read; i++) {
                    byte b = buf[i];
                    if (b == '\r' || b == '\n') {
                        toTerminalSink.write(PROMPT);
                    } else {
                        toTerminalSink.write(b);
                    }
                }
                toTerminalSink.flush();
            } catch (IOException e) {
                if (running) Log.w(TAG, "echo write failed", e);
                return;
            }
        }
    }

    public void close() {
        running = false;
        try { toTerminalSink.close(); } catch (IOException ignored) {}
        try { toTerminalSource.close(); } catch (IOException ignored) {}
        try { fromTerminalSink.close(); } catch (IOException ignored) {}
        try { fromTerminalSource.close(); } catch (IOException ignored) {}
        if (echoThread != null) {
            echoThread.interrupt();
            echoThread = null;
        }
    }
}

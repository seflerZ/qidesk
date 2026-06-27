package com.qihua.bVNC.connection;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.ssh.SshTerminalConnection;
import com.qihua.bVNC.communicator.SshCommunicator;
import com.qihua.bVNC.input.RemoteSshKeyboard;
import com.qihua.bVNC.input.RemoteSshPointer;
import com.qihua.bVNC.ssh.SshShellChannel;
import com.qihua.bVNC.ssh.SshTerminalRenderer;
import com.undatech.opaque.Connection;

import java.util.concurrent.locks.ReentrantLock;

/**
 * SSH lifecycle owner. Moved out of RemoteCanvas so that adding a 6th
 * protocol doesn't touch the host.
 *
 * <h2>Phase 1 vs Phase 2</h2>
 * Phase 1 wired {@link SshTerminalRenderer} (TermSession + local
 * fake-shell loopback) into the canvas. Paints were driven entirely by
 * TermSession's {@code setUpdateCallback} on every screen mutation.
 *
 * Phase 2 replaces the fake loopback with {@link SshShellChannel}, which
 * bridges TermSession's piped tty streams to a real trilead
 * {@code Session} opened on a background "SSH-Connect" thread. From
 * TermSession's perspective nothing changed: it still reads from one
 * end of a pipe and writes to the other. The pipes are now back-fed by
 * a pair of pump threads that copy bytes between trilead's
 * {@code Session.getStdout()} / {@code getStdin()} and the pipe ends.
 *
 * <h2>Architectural parallel to RDP / NVStream</h2>
 * RDP runs FreeRDP in a native process that calls back into Java via
 * {@code OnGraphicsUpdate} to write pixels into the bitmap. NVStream
 * does the same through MediaCodec's {@code setOnFrameRenderedListener}.
 * SSH Phase 2 follows the same shape: a trilead background thread
 * connects, opens a shell, and drives the worker-internal state machine
 * (TermSession); the UpdateCallback fires {@link #paintAndRedraw()},
 * which is the only paint driver. The difference is the worker outputs
 * a state mutation (a character grid) rather than raw pixels, so
 * {@link SshTerminalRenderer#renderInto(Bitmap)} is the in-place write
 * into the bitmap, in lieu of {@code LibFreeRDP.updateGraphics} or
 * {@code PixelCopy.request}.
 */
public class SshConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "SshConnectionInitializer";

    private final Connection conn;
    private final Context ctx;

    /**
     * UpdateCallback fired by TermSession whenever the screen mutates
     * (remote shell printed something, user typed something, cursor
     * moved). The only paint driver — same as Phase 1, still the only
     * paint driver in Phase 2.
     */
    private final Runnable sshUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!canvas.isRunning || canvas.bitmapData == null || canvas.rfbconn == null) {
                return;
            }
            paintAndRedraw();
        }
    };

    private RemoteCanvas canvas;
    private SshTerminalRenderer renderer;

    /**
     * 5 FPS heartbeat (200 ms) repaint. Phase 1 spec §10.6 argued for
     * UpdateCallback-only, but in practice a fast burst of bytes from
     * the remote shell (e.g. the output of {@code ls}, or a long line
     * being echoed back) can land in {@code TermSession}'s state machine
     * faster than {@code notifyUpdate} fires — and {@code screen.drawText}
     * is called only once for the entire burst, missing the
     * mid-burst visible states. The heartbeat guarantees a paint every
     * 200 ms regardless of state-machine batching, while
     * {@link #sshUpdateRunnable} still drives low-latency paints for
     * normal keystroke/echo activity.
     *
     * <p>200 ms (5 FPS) is intentionally low. Phase 0 used 30 FPS and
     * spec §10.6 warns that heartbeat + UpdateCallback together produce
     * visibly uneven cadence under {@code DrawWorker}'s 10 ms addTask /
     * 13 ms run throttle — i.e. flicker. With UpdateCallback carrying
     * most paints and the heartbeat only catching missed bursts, 5 FPS
     * is enough to avoid the "half-rendered" symptom without re-
     * introducing flicker.
     */
    private static final long HEARTBEAT_INTERVAL_MS = 200L;
    private long lastPaintAt = 0;
    private int paintCounter;
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                long now = System.currentTimeMillis();
                long sinceLast = now - lastPaintAt;
                if (sinceLast > 1000) {
                    Log.i(TAG, "paint stats: " + paintCounter + " paints in last "
                            + sinceLast + " ms (heartbeat)");
                    paintCounter = 0;
                    lastPaintAt = now;
                }
                paintAndRedraw();
            } catch (Throwable t) {
                Log.w(TAG, "heartbeat paint failed", t);
            } finally {
                if (canvas != null && canvas.handler != null) {
                    canvas.handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            }
        }
    };
    private boolean heartbeatStarted;

    /**
     * Phase 3.1: real SSH terminal connection (replaces the Phase 2
     * {@code SSHConnection}, which is the VNC-over-SSH-tunnel helper).
     * Built in {@link #initialize(RemoteCanvas)}; the actual
     * connect+auth+startShell happens on {@link #connectThread} via
     * {@link SshTerminalConnection#connect} +
     * {@link SshTerminalConnection#openShell}.
     */
    private SshTerminalConnection sshTerminal;
    /**
     * SSH terminal connection parameters. Pulled from VNC-style
     * {@link Connection} fields ({@code getAddress/getPort/getUserName/getPassword})
     * in {@link #initialize}, then handed to {@code SshTerminalConnection.connect}
     * from the connect thread.
     */
    private String sshHost;
    private int sshPort;
    private String sshUser;
    private String sshPassword;
    private String savedHostKey;
    /**
     * Phase 2: bridge between the trilead Session and TermSession. Built
     * in {@link #initialize(RemoteCanvas)} so the renderer can wire its
     * streams before the network is up. The actual trilead attach happens
     * from {@link #doConnect()} via {@link SshShellChannel#attach}.
     */
    private SshShellChannel channel;
    private Thread connectThread;
    private volatile boolean connectStarted;
    /** Set to true when doConnect() finishes successfully. */
    private volatile boolean shellReady;
    /** Guards teardown / rebuild against the in-flight connect thread. */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /**
     * Background thread that owns {@code renderer.renderInto(mbitmap)}.
     * Running the paint on the main thread causes a visible "first-line
     * flash" while typing: TermSession's {@code setUpdateCallback} fires
     * synchronously into {@code paintAndRedraw} which blocks the main
     * thread for the duration of {@code renderInto} (10-30 ms). During
     * that window the surface still shows the previous frame, but
     * because the next paint immediately follows, the user sees a
     * flicker especially on long prompt lines (which have the largest
     * {@code drawRect}-then-{"drawText"} window).
     *
     * <p>Offloading to a dedicated paint thread:
     * <ul>
     *   <li>Main thread accepts paint requests instantly (no blocking
     *       on {@code renderInto});</li>
     *   <li>Multiple back-to-back paint requests coalesce on the paint
     *       thread's queue — the latest TermSession state is what
     *       actually gets painted;</li>
     *   <li>The surface draws only complete frames because
     *       {@code canvas.reDraw} is invoked after {@code renderInto}
     *       finishes on the paint thread.</li>
     * </ul>
     */
    private HandlerThread paintThread;
    private Handler paintHandler;

    private float density;

    public SshConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.SSH;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        return c != null && c.getConnectionType() == Constants.CONN_TYPE_SSH;
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        this.canvas = canvas;
        this.density = ctx.getResources().getDisplayMetrics().density;
        Log.i(TAG, "initialize: Phase 2 — TermSession + SshShellChannel + trilead.");

        int fbW = computeFbW();
        int fbH = computeFbH();
        Log.i(TAG, "SSH framebuffer size = " + fbW + " x " + fbH
                + " (displayRect " + canvas.displayRect.width() + "x" + canvas.displayRect.height()
                + ", factor " + Constants.SSH_SMART_RESOLUTION_FACTOR + ")");

        // 1. Pull VNC-style fields from the Connection (Phase 3.1: SSH
        //    terminal is its own protocol, NOT a VNC-over-SSH tunnel —
        //    it uses getAddress/Port/UserName/Password, not getSshXxx).
        //    No network yet; sshTerminal is created here without touching
        //    the socket. The actual connect+auth+startShell happens on
        //    connectThread via SshTerminalConnection.connect.
        sshHost = conn.getAddress();
        sshPort = conn.getPort() == 0 ? 22 : conn.getPort();
        sshUser = conn.getUserName();
        sshPassword = conn.getPassword();
        savedHostKey = conn.getSshHostKey() == null ? "" : conn.getSshHostKey();
        sshTerminal = new SshTerminalConnection(sshHost, sshPort, savedHostKey);

        SshCommunicator sshComm = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
        // SshCommunicator.close() must tear down the SSH terminal too.
        sshComm.setSshTerminalConnection(sshTerminal);
        canvas.rfbconn = sshComm;

        canvas.pointer = new RemoteSshPointer(canvas.rfbconn, canvas, canvas.handler, App.debugLog);
        canvas.keyboard = new RemoteSshKeyboard(canvas.rfbconn, ctx, canvas.handler, App.debugLog);

        // 2. Build the channel + renderer. TermSession is wired to the
        //    channel's pipes now; the channel starts pumping the moment
        //    doConnect() calls channel.attach() with a live trilead Session.
        //    The context is needed for loading the terminal font (Sarasa Mono
        //    SC Nerd) from APK assets.
        channel = new SshShellChannel();
        renderer = new SshTerminalRenderer(density, channel, ctx);
        // Forward grid-size changes to the remote PTY so the shell's
        // line editor knows the new dimensions. Trilead's resizePTY is
        // a no-op if the shell isn't open yet — safe to call preemptively.
        renderer.setGridSizeListener((cols, rows) -> {
            if (sshTerminal != null) sshTerminal.resizePty(cols, rows);
        });
        // Phase 3.7: getTermSession() now returns SshTermStateMachine
        // (libvterm wrapper) instead of the AAR's TermSession.
        ((RemoteSshKeyboard) canvas.keyboard).setTermSession(renderer.getTermSession());
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Phase 2 — opening terminal + connecting SSH.");
        canvas.waitUntilInflated();
        canvas.reallocateDrawable(canvas.displayRect.width(), canvas.displayRect.height());
        openRenderer();
        canvas.onConnectionSuccess();

        // Spawn the SSH-Connect thread. It runs in parallel with the
        // already-displayed empty grid: the user sees a blank blue
        // terminal for 1-10s while TCP+hostKey+auth round-trip, then
        // the remote shell's first prompt bytes arrive via the pump and
        // the UpdateCallback paints them.
        lifecycleLock.lock();
        try {
            if (connectStarted) {
                Log.w(TAG, "start: connect already started, ignoring duplicate");
                return;
            }
            connectStarted = true;
        } finally {
            lifecycleLock.unlock();
        }

        connectThread = new Thread(this::doConnect, "SSH-Connect");
        connectThread.setDaemon(false);
        connectThread.start();

        // Kick off the 25 FPS heartbeat. Updates keep firing every 40 ms
        // until teardown() removes the pending callbacks.
        startHeartbeat();
    }

    private void startHeartbeat() {
        if (heartbeatStarted) return;
        heartbeatStarted = true;
        if (canvas != null && canvas.handler != null) {
            canvas.handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
            Log.i(TAG, "startHeartbeat: 5 FPS heartbeat started");
        }
    }

    private void stopHeartbeat() {
        heartbeatStarted = false;
        if (canvas != null && canvas.handler != null) {
            canvas.handler.removeCallbacks(heartbeatRunnable);
        }
    }

    /**
     * Background worker: drives the trilead handshake off the UI thread.
     * Reports failures through {@link RemoteCanvas#handleUncaughtException}
     * so the existing fatal-error machinery handles them.
     */
    private void doConnect() {
        try {
            Log.i(TAG, "doConnect: connecting " + sshUser + "@" + sshHost + ":" + sshPort);
            boolean authOk = sshTerminal.connect(sshUser, sshPassword);
            if (!authOk) {
                throw new Exception("SSH password authentication failed for " + sshUser + "@" + sshHost);
            }
            Log.i(TAG, "doConnect: auth OK, opening shell with xterm-256color PTY");
            // Open the PTY at the renderer's *real* cols/rows, not the
            // 80x24 Phase 2 default. If we start at 80x24, TUI applications
            // (vim, less, htop, top) initialize their internal window to
            // 80x24 and only resize on a later SIGWINCH. They do redraw
            // on SIGWINCH, but the user-perceptible artifact is that the
            // TUI starts in the upper-left 80x24 quadrant of the mbitmap
            // and the surrounding area stays as the previous prompt /
            // blank — which looks like the TUI is broken. Starting at the
            // true size skips that initial misrender entirely.
            int cols = 80, rows = 24;
            if (renderer != null) {
                int rc = renderer.getCurrentCols();
                int rr = renderer.getCurrentRows();
                if (rc > 0) cols = rc;
                if (rr > 0) rows = rr;
            }
            Log.i(TAG, "doConnect: opening PTY at " + cols + "x" + rows);
            com.trilead.ssh2.Session sshSession = sshTerminal.openShell(cols, rows);
            // The pumps start forwarding bytes; TermSession will fire
            // UpdateCallback. We just need to re-render the now-populated
            // grid once the prompt arrives.
            channel.attach(sshSession);
            shellReady = true;
            Log.i(TAG, "doConnect: shell channel attached, terminal should start showing output");
            // Force one paint now so the (still empty, blue) grid gives
            // way to whatever the shell has already emitted since attach.
            postPaint();
        } catch (Throwable t) {
            Log.e(TAG, "doConnect: failed", t);
            if (canvas != null) {
                try {
                    canvas.handleUncaughtException(t);
                } catch (Throwable ignored) {
                    // canvas may already be tearing down; swallow.
                }
            }
        }
    }

    private void postPaint() {
        if (canvas == null || Looper.myLooper() != Looper.getMainLooper()) {
            // Already on main? Just paint. Otherwise hop.
            if (canvas != null) {
                canvas.handler.post(this::paintAndRedraw);
            }
            return;
        }
        paintAndRedraw();
    }

    /**
     * Tear down the renderer, channel, and SSH connection. Called by
     * {@code RemoteCanvas.closeConnection()}.
     */
    public void teardown(RemoteCanvas canvas) {
        lifecycleLock.lock();
        try {
            Log.i(TAG, "teardown: closing SSH");
            stopHeartbeat();
            stopPaintThread();
            // 1. Stop the connect thread if it's still in flight.
            if (connectThread != null && connectThread.isAlive()) {
                connectThread.interrupt();
            }
            // 2. Close the channel first (closes pipes, interrupts pumps,
            //    closes the trilead Session). Order matters: closing the
            //    channel BEFORE the renderer lets TermSession's reader
            //    thread wake from its read with EOF, so termSession.finish()
            //    returns promptly.
            try {
                if (channel != null) channel.close();
            } catch (Throwable t) {
                Log.w(TAG, "teardown: channel.close failed", t);
            }
            // 3. Close the renderer (finishes TermSession).
            closeRenderer();
            // 4. Belt-and-braces: tear down the trilead connection in case
            //    the channel didn't open a session.
            try {
                if (sshTerminal != null) sshTerminal.close();
            } catch (Throwable t) {
                Log.w(TAG, "teardown: sshTerminal.close failed", t);
            }
            connectStarted = false;
            shellReady = false;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Surface recreated (background→foreground, fold/unfold, rotation).
     * UpdateCallback doesn't fire on surface recreate alone, so paint the
     * current TermSession state explicitly here.
     */
    public void onSurfaceCreated(RemoteCanvas canvas) {
        if (canvas.bitmapData != null && canvas.rfbconn != null) {
            paintAndRedraw();
        }
    }

    /**
     * displayRect changed (foldable fold/unfold, rotation). Rebuild the
     * framebuffer, mbitmap, and renderer at the new size. Phase 1 / Phase 2
     * accept that the screen contents are reset — a new trilead session
     * is opened by the rebuild path's re-invocation of initialize/start.
     */
    @Override
    public void onDisplayRectChanged(Display display) {
        if (canvas.rfbconn == null) {
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        Rect newRect = new Rect();
        display.getRectSize(newRect);

        Rect oldRect = canvas.getDisplayRect();

        if (oldRect.equals(newRect)) {
            return;
        }

        canvas.setDisplayRect(newRect);
        canvas.setDisplayDensity(metrics.density);

        Log.i(TAG, "displayRect changed "
                + oldRect.width() + "x" + oldRect.height()
                + " -> " + newRect.width() + "x" + newRect.height()
                + ", rebuilding SSH framebuffer");

        rebuildSSHFramebuffer();

        canvas.bitmapData.frameBufferSizeChanged();
    }

    /**
     * Open the TermSession at the current mbitmap size and paint the
     * first frame. The UpdateCallback becomes the paint driver for all
     * subsequent screen mutations; this call IS the first-frame trigger.
     */
    private void openRenderer() {
        if (canvas.bitmapData == null || canvas.bitmapData.mbitmap == null) {
            Log.w(TAG, "openRenderer: bitmapData or mbitmap is null");
            return;
        }

        int w = canvas.bitmapData.mbitmap.getWidth();
        int h = canvas.bitmapData.mbitmap.getHeight();
        // Seed the freshly-allocated bitmap with our background colour so
        // empty cells (which drawText doesn't repaint) start out blue
        // instead of the default transparent black. Called every time
        // the bitmap is (re)allocated, including fold/unfold rebuilds.
        renderer.seedBackground(canvas.bitmapData.mbitmap);
        renderer.open(w, h, sshUpdateRunnable);
        ensurePaintThread();
        postPaintToBackground();
    }

    private void ensurePaintThread() {
        if (paintThread != null && paintThread.isAlive()) return;
        paintThread = new HandlerThread("SSH-Paint");
        paintThread.start();
        paintHandler = new Handler(paintThread.getLooper());
    }

    private void stopPaintThread() {
        if (paintHandler != null) {
            paintHandler.removeCallbacksAndMessages(null);
        }
        if (paintThread != null) {
            paintThread.quitSafely();
            paintThread = null;
        }
        paintHandler = null;
    }

    /**
     * Paint the current TermSession grid into mbitmap and post a reDraw.
     * Posts the actual {@code renderInto} work to the dedicated
     * {@code SSH-Paint} thread so the main thread (where
     * {@code TermSession.setUpdateCallback} fires) returns instantly and
     * the next UpdateCallback can fire without waiting on the previous
     * paint to finish. The paint thread processes paints serially, so
     * many rapid keystrokes coalesce to the latest state instead of
     * thrashing the surface.
     */
    private void postPaintToBackground() {
        if (paintHandler == null) return;
        // removeCallbacks drops any pending paint so we never have more
        // than one paint queued — the latest TermSession state is the
        // only one that gets rendered.
        paintHandler.removeCallbacks(paintRunnable);
        paintHandler.post(paintRunnable);
    }

    /**
     * Synchronous fallback. Used by the heartbeat (where the caller is
     * already on the main thread and we want a paint that goes through
     * the queue without busy-spinning). Goes through the same paint
     * thread so we keep all paints off the main thread.
     */
    private void paintAndRedraw() {
        postPaintToBackground();
    }

    private final Runnable paintRunnable = new Runnable() {
        @Override
        public void run() {
            if (renderer == null) return;
            if (canvas == null) return;
            if (canvas.bitmapData == null || canvas.bitmapData.mbitmap == null) return;
            if (canvas.rfbconn == null) return;
            paintCounter++;
            try {
                renderer.renderInto(canvas.bitmapData.mbitmap);
            } catch (Throwable t) {
                Log.w(TAG, "renderInto failed", t);
                return;
            }
            // reDraw schedules DrawTask onto DrawWorker (which paints
            // mbitmap to the SurfaceView). It is thread-safe, so calling
            // it from the paint thread is fine.
            canvas.reDraw(0, 0,
                    canvas.rfbconn.framebufferWidth(),
                    canvas.rfbconn.framebufferHeight());
        }
    };

    private void closeRenderer() {
        if (renderer == null) return;
        try {
            renderer.close();
        } catch (Throwable t) {
            Log.w(TAG, "renderer.close failed", t);
        }
        renderer = null;
    }

    private int computeFbW() {
        float w = canvas.displayRect != null ? canvas.displayRect.width() : 0;
        if (w <= 0) {
            w = ctx.getResources().getDisplayMetrics().widthPixels;
        }
        return Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
    }

    private int computeFbH() {
        float h = canvas.displayRect != null ? canvas.displayRect.height() : 0;
        if (h <= 0) {
            h = ctx.getResources().getDisplayMetrics().heightPixels;
        }
        return Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));
    }

    /**
     * Recreate the SSH RemoteConnectable, the mbitmap, and the renderer at
     * the current displayRect's size, then swap the new TermSession into the
     * keyboard. Accepts that the visible grid is reset.
     */
    private void rebuildSSHFramebuffer() {
        lifecycleLock.lock();
        try {
            int w = canvas.displayRect.width();
            int h = canvas.displayRect.height();
            int fbW = Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
            int fbH = Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));

            // 1. Tear down the old SSH session + channel + renderer.
            //    Order: stop pumps → close renderer → terminate SSH.
            stopPaintThread();
            try {
                if (channel != null) channel.close();
            } catch (Throwable t) {
                Log.w(TAG, "rebuild: channel.close failed", t);
            }
            closeRenderer();
            try {
                if (sshTerminal != null) sshTerminal.close();
            } catch (Throwable t) {
                Log.w(TAG, "rebuild: sshTerminal.close failed", t);
            }

            // 2. Build a fresh SshTerminalConnection + SshCommunicator (the
            //    rebuild could happen mid-handshake; the old one might
            //    never have completed). VNC-style fields, not SshXxx.
            sshTerminal = new SshTerminalConnection(sshHost, sshPort, savedHostKey);
            SshCommunicator sshComm = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
            sshComm.setSshTerminalConnection(sshTerminal);
            canvas.rfbconn = sshComm;
            canvas.reallocateDrawable(w, h);

            ((RemoteSshPointer) canvas.pointer).setProtocomm(canvas.rfbconn);
            ((RemoteSshKeyboard) canvas.keyboard).setRfb(canvas.rfbconn);

            // 3. Build a fresh channel + renderer.
            channel = new SshShellChannel();
            renderer = new SshTerminalRenderer(density, channel, ctx);
            renderer.setGridSizeListener((cols, rows) -> {
                if (sshTerminal != null) sshTerminal.resizePty(cols, rows);
            });
            // Phase 3.7: renderer.getTermSession() now returns
            // SshTermStateMachine (libvterm wrapper) instead of the
            // AAR's TermSession. The keyboard's setTermSession
            // signature is updated to match in Step 6.
            ((RemoteSshKeyboard) canvas.keyboard).setTermSession(renderer.getTermSession());

            openRenderer();
            // Restart heartbeat against the new renderer; the old
            // heartbeatRunnable self-reposts via canvas.handler so its
            // closure is safe across rebuilds, but we explicitly stop
            // and start to be defensive.
            stopHeartbeat();
            startHeartbeat();

            // 4. Re-spawn the connect thread (the old one belongs to the
            //    discarded session and would either have already
            //    completed into a now-orphaned Session, or be still
            //    running against a torn-down SSHConnection).
            connectStarted = false;
            shellReady = false;
            connectThread = new Thread(this::doConnect, "SSH-Connect");
            connectThread.setDaemon(false);
            connectThread.start();
        } catch (Exception e) {
            Log.e(TAG, "rebuildFramebuffer failed", e);
        } finally {
            lifecycleLock.unlock();
        }
    }
}

package com.qihua.bVNC.connection;

import android.content.Context;
import android.graphics.Bitmap;
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
import com.qihua.bVNC.ssh.SshTerminalScaling;
import com.undatech.opaque.Connection;
import com.undatech.opaque.DrawTask;

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
            // New remote output arrived: snap scrollback to the live bottom so
            // the user sees fresh output instead of staying parked in history.
            // Standard terminal behaviour — scroll back again after output
            // settles to inspect history.
            if (renderer != null && renderer.getTermSession() != null
                    && renderer.getTermSession().getScrollOffset() > 0) {
                renderer.getTermSession().scrollToBottom();
            }
            paintAndRedraw();
        }
    };

    private RemoteCanvas canvas;
    private SshTerminalRenderer renderer;
    /** 1:1 scaler that gives SSH the same canvas-pan pipeline as RDP/VNC.
     *  See {@link com.qihua.bVNC.ssh.SshTerminalScaling} for the rationale
     *  and {@link #resizeSSHFramebuffer} for the re-attach path. */
    private SshTerminalScaling sshScaler;

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

    /**
     * Expose the SshTerminalRenderer so callers (RemoteCanvasActivity
     * for the SSH selection-menu Paste action) can write strings into
     * the state machine without going through RemoteSshKeyboard.
     * Returns null if the renderer hasn't been constructed yet.
     */
    public SshTerminalRenderer getSshRenderer() {
        return renderer;
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
        // When the remote shell exits (exit / Ctrl+D, SSH session ends),
        // navigate back to the main connection list instead of leaving
        // the user staring at a dead canvas.
        channel.setOnDisconnect(() -> {
            // Full SSH cleanup: stop paint thread, close pipes+pumps,
            // destroy state machine, tear down trilead connection.
            // Must run BEFORE disconnectAndClose so the remote state
            // is clean for the next connection.
            teardown(canvas);
            // Then close the activity on the main thread.
            if (canvas != null && canvas.handler != null && canvas.activity != null) {
                canvas.handler.post(canvas.activity::disconnectAndClose);
            }
        });
        renderer = new SshTerminalRenderer(density, channel, ctx);
        // Forward grid-size changes to the remote PTY so the shell's
        // line editor knows the new dimensions. Trilead's resizePTY is
        // a no-op if the shell isn't open yet — safe to call preemptively.
        renderer.setGridSizeListener((cols, rows) -> {
            if (sshTerminal != null) sshTerminal.resizePty(cols, rows);
        });
        // NOTE: The keyboard's termSession cannot be set here because the
        // renderer hasn't opened yet (stateMachine is null). It is wired in
        // openRenderer() below, which is called from start().
        // Install a 1:1 scaler so the SSH terminal shares the same
        // canvas rendering pipeline as RDP/VNC: DrawWorker reads
        // canvas.scaler.getMatrix() to position mbitmap on the
        // SurfaceView, and RemoteCanvas.relativePan() is allowed
        // (isAbleToPan() = true) so the RDP-style IME push-up
        // (RemoteCanvasActivity's KeyBoardListenerHelper pan formula)
        // actually fires for SSH. Without a scaler the SSH IME was
        // a dead path: canvas.scaler == null made absolutePan a
        // no-op and DrawWorker skipped setMatrix entirely.
        sshScaler = new SshTerminalScaling();
        sshScaler.attachTo(canvas);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Phase 2 — opening terminal + connecting SSH.");
        canvas.waitUntilInflated();
        canvas.reallocateDrawable(canvas.displayRect.width(), canvas.displayRect.height());
        openRenderer();
        // Push the current AppCompat theme into the newly-created
        // stateMachine. initialize() already called applyTheme() for
        // VTermCanvasRenderer (palette + seedBackground), but at that
        // point stateMachine was null so the JNI defaults and OSC
        // query were skipped. Run again now so the first SSH
        // connection lands in the right theme from byte zero, not
        // just after the next uiMode flip.
        if (renderer != null) {
            renderer.applyTheme();
        }
        // Wire the keyboard to the newly-created libvterm state machine.
        // This must happen AFTER openRenderer() because stateMachine is only
        // instantiated inside SshTerminalRenderer.open().
        ((RemoteSshKeyboard) canvas.keyboard).setTermSession(renderer.getTermSession());
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
     * displayRect changed (foldable fold/unfold, rotation). Resize-only:
     * keep the live SSH connection, the libvterm state machine, the
     * renderer, and the SSH-Connect / SSH-Paint threads alive — only
     * the bitmap dimensions and the {@link SshCommunicator}'s reported
     * framebuffer size need to follow the new window. The next
     * {@code canvas.reDraw()} (issued by {@code correctAfterRotation}
     * 200 ms later, or naturally by the next input byte) calls into
     * {@link SshTerminalRenderer#renderInto}, which detects the new
     * cols/rows, fires {@code SshTermStateMachine.setSize(...)} on the
     * libvterm state machine, and triggers the gridSizeListener that
     * sends a {@code winch} to the remote PTY via
     * {@link SshTerminalConnection#resizePty}.
     *
     * <p>The previous behaviour tore the entire SSH stack down and
     * re-opened the trilead session on rotation, which dropped the
     * running TUI program (e.g. Claude Code) on every screen rotation.
     */
    /**
     * Cursor Y in mbitmap pixels — feeds the RDP-equivalent pointer
     * position into the IME push-up pan formula. See
     * {@link com.qihua.bVNC.ssh.SshTerminalRenderer#getCursorPixelY()}
     * for the computation.
     */
    public int getCursorPixelY() {
        return renderer != null ? renderer.getCursorPixelY() : 0;
    }

    /**
     * Re-apply the AppCompat day/night palette to the SSH terminal.
     * Called from {@code RemoteCanvasActivity.onConfigurationChanged}
     * when the uiMode flips (system night-mode toggle or the user
     * picking a different theme). The renderer's
     * {@code applyTheme} updates the in-process palette (VTermCanvasRenderer's
     * bgColor / defaultFg / defaultBg), pushes the new ARGB into
     * libvterm via JNI, and reseeds the mbitmap so the next paint
     * shows the new colours. Safe to call before
     * {@link #start} — it no-ops until the renderer is created.
     */
    public void applyTheme() {
        if (renderer == null) return;
        renderer.applyTheme();
        // Reseed the mbitmap with the new background so the user
        // sees the flip immediately rather than waiting for the
        // next byte from the remote shell.
        if (canvas != null && canvas.bitmapData != null
                && canvas.bitmapData.mbitmap != null
                && !canvas.bitmapData.mbitmap.isRecycled()) {
            renderer.seedBackground(canvas.bitmapData.mbitmap);
            canvas.reDraw(0, 0,
                    canvas.bitmapData.mbitmap.getWidth(),
                    canvas.bitmapData.mbitmap.getHeight());
        }
    }

    /**
     * Trigger OSC 10/11 queries through the live SSH connection
     * so the remote shell re-picks its contrast colour. Safe to
     * call from {@code RemoteCanvasActivity} on theme flips —
     * at that point the SSH connection is fully established and
     * the input pipe won't deliver garbled bytes into the shell
     * startup banner.
     */
    public void queryShellTheme() {
        if (renderer != null) {
            renderer.queryShellTheme();
        }
    }

    /**
     * IME visibility changed. SSH delegates the actual push-up to the
     * RDP pan formula in {@code RemoteCanvasActivity}'s
     * {@code KeyBoardListenerHelper} listener (with the libvterm cursor
     * Y substituted for pointerYPos), so this method is a no-op.
     *
     * <p>Earlier revisions grew the mbitmap by keyboardHeight on IME-up
     * to create a transparent "backup region" the pan could push off-
     * screen. That introduced a visible black flash because
     * Bitmap.createBitmap returned a transparent bitmap, the SSH-Paint
     * thread was stopped and restarted, and the DrawWorker drew the
     * new (transparent → seedBackground) mbitmap for 1–3 frames before
     * SSH-Paint repainted. Keeping the mbitmap at displayRect size
     * means the RDP pan just translates a stable bitmap — no realloc,
     * no paint-thread restart, no flash. The trade-off is that the
     * top keyboardHeight rows of real terminal content slide off-screen
     * (RDP does the same — RDP's "sacrifice" is its larger-than-viewport
     * mbitmap, SSH just accepts it directly on the terminal grid).
     */
    @Override
    public void onSoftKeyboardChanged(boolean isShow, int availableHeight) {
        // Intentionally empty — see the comment above. The RDP pan
        // formula in RemoteCanvasActivity handles the actual push-up;
        // this hook exists so the activity can call back into the SSH
        // initializer for any future per-IME logic without needing a
        // second instanceof check.
    }

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
                + ", resizing SSH framebuffer (keeping SSH session alive)");

        resizeSSHFramebuffer();

        // correctAfterRotation calls canvas.reDraw(...) 200 ms later;
        // that paints into the freshly seeded bitmap and triggers
        // SshTerminalRenderer.renderInto -> stateMachine.setSize ->
        // sshTerminal.resizePty (remote winch).
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
        // Wire scrollback: the pointer decodes scroll deltas into the state
        // machine's view offset and requests a coalesced repaint per event.
        // (setScrollback is idempotent across resizeSSHFramebuffer re-opens —
        //  the same sm + repaint hook survive a fold/unfold.)
        if (canvas.pointer instanceof RemoteSshPointer) {
            ((RemoteSshPointer) canvas.pointer)
                    .setScrollback(renderer.getTermSession(), this::postPaintToBackground);
            ((RemoteSshPointer) canvas.pointer).setRenderer(renderer);
        }
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
                // reDraw schedules DrawTask onto DrawWorker (which paints
                // mbitmap to the SurfaceView). Kept INSIDE the try so a
                // teardown race (drawWorker nulled by onDestroy, rfbconn
                // going null) can't throw and kill the SSH-Paint thread — a
                // dead paint thread means no further rendering at all (the
                // "terminal freezes / scroll does nothing" symptom).
                canvas.reDraw(new DrawTask(0, 0,
                        canvas.rfbconn.framebufferWidth(),
                        canvas.rfbconn.framebufferHeight(), true));
            } catch (Throwable t) {
                Log.w(TAG, "paint failed", t);
            }
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
     * Resize-only path invoked by {@link #onDisplayRectChanged} on rotation
     * or fold/unfold. KEEPS the live SSH connection, the {@link SshShellChannel},
     * the {@link SshTerminalRenderer}, the {@code SshTermStateMachine}, and the
     * {@code SSH-Connect} / {@code SSH-Paint} / heartbeat threads alive — only
     * the {@code mbitmap} (re-allocated at the new size and re-seeded with the
     * Solarized BG) and the {@link SshCommunicator}'s reported framebuffer size
     * are refreshed.
     *
     * <p>The next paint pass through {@link SshTerminalRenderer#renderInto}
     * detects the new cols/rows, fires {@code stateMachine.setSize(cols, rows)}
     * on libvterm, and triggers the gridSizeListener registered in
     * {@link #initialize} which calls {@code sshTerminal.resizePty(cols, rows)}
     * — the trilead call that sends a {@code TIOCSWINSZ} / SSH {@code window-change}
     * to the remote PTY, so the TUI (vim, htop, Claude Code, …) reflows
     * rather than restarting.
     */
    private void resizeSSHFramebuffer() {
        lifecycleLock.lock();
        try {
            int w = canvas.displayRect.width();
            int h = Math.max(1, canvas.displayRect.height());
            int fbW = Math.max(1, (int) (w * Constants.SSH_SMART_RESOLUTION_FACTOR));
            int fbH = Math.max(1, (int) (h * Constants.SSH_SMART_RESOLUTION_FACTOR));

            // 1. Stop the SSH-Paint thread (it writes into the old mbitmap).
            //    The SSH-Connect thread and the libvterm reader thread stay
            //    alive — they don't touch the bitmap, they keep feeding
            //    bytes into the state machine. SSH-VTerm-Reader in
            //    SshTerminalRenderer is also safe: it only touches
            //    stateMachine.write(), not the bitmap.
            stopPaintThread();

            // 2. Swap in a fresh SshCommunicator reporting the new fbW/fbH.
            //    It's a stub adapter (writePointerEvent / writeKeyEvent /
            //    writeFramebufferUpdateRequest are all no-ops); the only
            //    field that matters for SSH is its framebufferWidth/Height
            //    pair, which is final and so requires a new instance.
            //    setSshTerminalConnection re-injects the existing
            //    SshTerminalConnection so close() still tears down the
            //    right tunnel.
            SshCommunicator sshComm = new SshCommunicator(App.debugLog, canvas.handler, fbW, fbH);
            sshComm.setSshTerminalConnection(sshTerminal);
            canvas.rfbconn = sshComm;

            ((RemoteSshPointer) canvas.pointer).setProtocomm(canvas.rfbconn);
            ((RemoteSshKeyboard) canvas.keyboard).setRfb(canvas.rfbconn);

            // 3. Reallocate the mbitmap at the new display size. The new
            //    bitmap is uninitialised (transparent black) — seed the
            //    BG so the first paint of empty cells isn't a black flash.
            canvas.reallocateDrawable(w, h);
            if (renderer != null && canvas.bitmapData != null && canvas.bitmapData.mbitmap != null) {
                renderer.seedBackground(canvas.bitmapData.mbitmap);
            }

            // 4. Restart the SSH-Paint thread (it was stopped in step 1).
            //    The paint handler coalesces renderInto via
            //    removeCallbacks + post, so the very next paintRunnable
            //    will see the new bitmap dimensions, recompute cols/rows,
            //    call stateMachine.setSize(...), and fire the
            //    gridSizeListener → sshTerminal.resizePty(cols, rows).
            ensurePaintThread();
            postPaintToBackground();

            // 5. Trigger an immediate repaint so the user sees the new
            //    layout without waiting for the next keystroke / heartbeat
            //    tick. reDraw is a noop if isRunning is false (e.g. the
            //    initializer has been disposed).
            canvas.bitmapData.frameBufferSizeChanged();
            canvas.reDraw(0, 0, w, h);
        } catch (Exception e) {
            Log.e(TAG, "resizeSSHFramebuffer failed", e);
        } finally {
            lifecycleLock.unlock();
        }

        // re-attach scaler AFTER unlock so a slow attachTo doesn't
        // hold the lifecycle lock. The mbitmap swap above doesn't
        // touch canvas.scaler, but re-attachTo resets absoluteYPosition
        // to 0 so a stale pan from before rotation doesn't leave the
        // terminal scrolled off-screen on the new size.
        if (sshScaler != null) {
            sshScaler.attachTo(canvas);
        }
    }
}

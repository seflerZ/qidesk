package com.qihua.bVNC.ssh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.util.Log;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.ssh.libvterm.SshTermStateMachine;
import com.qihua.bVNC.ssh.libvterm.VTermCanvasRenderer;

import java.io.IOException;

/**
 * SSH terminal renderer. Phase 3.7 replaces the AAR's
 * {@code TermSession} (from the vendored Android-Terminal-Emulator
 * AAR) with libvterm via {@link SshTermStateMachine}. All paint /
 * keyboard / resize logic mirrors the AAR version; the only new
 * responsibility is reading the grid from the libvterm JNI state
 * machine.
 *
 * <p>The renderInto(Bitmap) call delegates to {@link VTermCanvasRenderer},
 * which iterates the libvterm grid (cols x rows) and paints each
 * cell. CJK width, cursor, attrs (bold/italic/underline/reverse)
 * are handled there.
 *
 * <p>The AAR's {@code TermSession.UpdateCallback} had no equivalent
 * in libvterm — instead the SSH-Vterm-Reader thread (started in
 * {@link #open}) drains SSH bytes into the state machine and the
 * paint path polls dirty rows on demand.
 */
public class SshTerminalRenderer {
    private static final String TAG = "SshTerminalRenderer";

    private static final int BG_COLOR = 0xFF002B36; // Solarized base03

    /** Inset on all four sides of the grid, in dp. */
    private static final float PADDING_DP = 8f;

    private final float density;
    private final SshShellChannel channel;
    /** Terminal font (Sarasa Mono SC Nerd + Nerd PUA-A + CJK). */
    private final Typeface terminalTypeface;
    private final byte[] readBuffer = new byte[4096];

    private SshTermStateMachine stateMachine;
    private VTermCanvasRenderer canvasRenderer;
    private int currentCols = -1;
    private int currentRows = -1;
    private boolean open;
    private boolean closed;
    /** Notified when the terminal grid size changes (fold/unfold/etc). */
    private GridSizeListener gridSizeListener;
    /** Background thread that drains SSH bytes into the state machine. */
    private Thread readerThread;

    public SshTerminalRenderer(float density, SshShellChannel channel, Context ctx) throws IOException {
        this.density = density;
        this.channel = channel;
        this.terminalTypeface = ctx != null
                ? TermFontFactory.load(ctx.getAssets())
                : Typeface.MONOSPACE;
    }

    /** Initialise the target bitmap with our background colour. */
    public void seedBackground(Bitmap target) {
        if (target == null || target.isRecycled()) return;
        target.eraseColor(BG_COLOR);
    }

    /** Notified when {@link #renderInto} detects cols/rows changed. */
    public void setGridSizeListener(GridSizeListener listener) {
        this.gridSizeListener = listener;
    }

    /** Callback for grid-size changes (e.g. PTY resize over SSH). */
    public interface GridSizeListener {
        void onGridSizeChanged(int cols, int rows);
    }

    /**
     * Start the libvterm state machine + the SSH reader thread.
     * Must be called after construction and before renderInto.
     *
     * @param initialPxW  initial bitmap width  (used to seed cols)
     * @param initialPxH  initial bitmap height (used to seed rows)
     * @param onUpdate    invoked on each SSH-Paint frame (paint path
     *                    is unchanged from AAR version). The state
     *                    machine internally records dirty rows; the
     *                    paint path doesn't need to know them.
     */
    public void open(int initialPxW, int initialPxH, Runnable onUpdate) {
        int fontSizePx = fontSizePx();
        int pad = paddingPx();
        canvasRenderer = new VTermCanvasRenderer(fontSizePx, pad, terminalTypeface);
        int cols = Math.max(20, (initialPxW - 2 * pad) / (int) canvasRenderer.charWidth);
        int rows = Math.max(10, (initialPxH - 2 * pad) / canvasRenderer.charHeight);
        currentCols = cols;
        currentRows = rows;
        stateMachine = new SshTermStateMachine(cols, rows);
        // Reader thread: drains SSH bytes from channel.getTerminalIn()
        // into the libvterm state machine. Runs until close().
        // (SshShellChannel.getTerminalIn() returns an InputStream
        //  from which the shell's output is read.)
        final java.io.InputStream sshOut = channel.getTerminalIn();
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long totalRead = 0;
                    while (!Thread.currentThread().isInterrupted()) {
                        int n = sshOut.read(readBuffer);
                        if (n < 0) {
                            Log.i(TAG, "readerThread: EOF on SSH channel, totalRead=" + totalRead);
                            return;
                        }
                        if (n > 0) {
                            totalRead += n;
                            Log.i(TAG, "readerThread: read n=" + n + " total=" + totalRead
                                    + " firstByte=0x" + Integer.toHexString(readBuffer[0] & 0xff)
                                    + " lastByte=0x" + Integer.toHexString(readBuffer[n-1] & 0xff));
                            if (stateMachine != null) {
                                stateMachine.write(readBuffer, 0, n);
                            }
                            if (onUpdate != null) onUpdate.run();
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "readerThread: SSH read failed", e);
                }
            }
        }, "SSH-VTerm-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
        channel.start();
        open = true;
        Log.i(TAG, "open: " + cols + " cols x " + rows + " rows, charW="
                + canvasRenderer.charWidth + " charH=" + canvasRenderer.charHeight
                + " pad=" + pad + "px");
    }

    public void renderInto(Bitmap target) {
        if (closed || target == null || target.isRecycled()) return;
        Canvas c = new Canvas(target);
        int pad = paddingPx();
        int cols = Math.max(20, (target.getWidth() - 2 * pad) / (int) canvasRenderer.charWidth);
        int rows = Math.max(10, (target.getHeight() - 2 * pad) / canvasRenderer.charHeight);
        if (cols != currentCols || rows != currentRows) {
            currentCols = cols;
            currentRows = rows;
            if (stateMachine != null) stateMachine.setSize(cols, rows);
            if (gridSizeListener != null) {
                gridSizeListener.onGridSizeChanged(cols, rows);
            }
        }
        if (stateMachine != null) {
            canvasRenderer.render(stateMachine, c);
        }
    }

    /**
     * Returns the libvterm state machine (replaces the AAR's
     * {@code getTermSession()}). Used by RemoteSshKeyboard to write
     * input codepoints.
     */
    public SshTermStateMachine getTermSession() {
        return stateMachine;
    }

    /**
     * Convenience for RemoteSshKeyboard — write a single codepoint
     * to the state machine as if the user had typed it.
     */
    public void writeCodepoint(int codepoint) {
        if (stateMachine != null) stateMachine.writeInput(codepoint);
    }

    /**
     * Current grid size as last computed by renderInto. The
     * SshConnectionInitializer passes this to SshTerminalConnection
     * so the PTY starts with the right cols/rows from the first
     * byte (avoids the upper-left 80x24 corner bug).
     */
    public int getCurrentCols() {
        return currentCols;
    }

    public int getCurrentRows() {
        return currentRows;
    }

    public void close() {
        if (closed) return;
        closed = true;
        open = false;
        if (readerThread != null) {
            readerThread.interrupt();
            try { readerThread.join(200); } catch (InterruptedException ignored) {}
            readerThread = null;
        }
        try {
            if (stateMachine != null) stateMachine.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "stateMachine.destroy failed", t);
        }
        channel.close();
    }

    private int fontSizePx() {
        return Math.max(1, Math.round(Constants.SSH_FONT_SIZE_DP * density));
    }

    private int paddingPx() {
        return Math.max(0, Math.round(PADDING_DP * density));
    }
}

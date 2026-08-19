package com.qihua.bVNC.ssh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.DoubleBufferBitmapData;
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

    /** Inset on all four sides of the grid, in dp. */
    private static final float PADDING_DP = 8f;

    private final float density;
    private final SshShellChannel channel;
    /** Terminal font (Sarasa Mono SC Nerd + Nerd PUA-A + CJK). */
    private final Typeface terminalTypeface;
    /** Activity context — used to resolve the theme-driven palette
     *  ({@code ssh_terminal_bg}/{@code ssh_terminal_fg}) on each paint
     *  so day/night flips in AppCompat reach the seed background without
     *  needing to recreate the renderer. */
    private final Context ctx;
    private final byte[] readBuffer = new byte[4096];

    private SshTermStateMachine stateMachine;
    private VTermCanvasRenderer canvasRenderer;
    private int currentCols = -1;
    private int currentRows = -1;
    private boolean closed;
    /** Notified when the terminal grid size changes (fold/unfold/etc). */
    private GridSizeListener gridSizeListener;
    /** Background thread that drains SSH bytes into the state machine. */
    private Thread readerThread;

    public SshTerminalRenderer(float density, SshShellChannel channel, Context ctx) throws IOException {
        this.density = density;
        this.channel = channel;
        this.ctx = ctx;
        this.terminalTypeface = ctx != null
                ? TermFontFactory.load(ctx.getAssets())
                : Typeface.MONOSPACE;
    }

    /** Initialise the target bitmap with our background colour. */
    public void seedBackground(DoubleBufferBitmapData target) {
        if (target == null) return;
        target.eraseFront(currentBgColor());
    }

    /**
     * Resolve the current bg from resources so that AppCompat day/night
     * flips show up on the next {@link #seedBackground}. Falls back to
     * Solarized base03 if the context is unavailable (legacy call
     * sites). Also re-applies the palette to the canvas renderer if it's
     * been built — that keeps {@code DEFAULT_FG}/{@code DEFAULT_BG} in
     * sync with the live theme.
     */
    public void applyTheme() {
        Log.i(TAG, "applyTheme: ctx=" + (ctx != null) + " canvasRenderer=" + (canvasRenderer != null) + " stateMachine=" + (stateMachine != null));
        if (canvasRenderer != null) {
            canvasRenderer.setPalette(ctx);
        }
        if (stateMachine != null && ctx != null) {
            int fg = ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_fg, ctx.getTheme());
            int bg = ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bg, ctx.getTheme());
            Log.i(TAG, "applyTheme: pushing fg=0x" + Integer.toHexString(fg) + " bg=0x" + Integer.toHexString(bg));
            stateMachine.setDefaultColors(fg, bg);
            int[] palette = new int[] {
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_black,  ctx.getTheme()), // 0
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_red,    ctx.getTheme()), // 1
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_green,  ctx.getTheme()), // 2
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_yellow, ctx.getTheme()), // 3
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_blue,   ctx.getTheme()), // 4
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_purple, ctx.getTheme()), // 5
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_cyan,   ctx.getTheme()), // 6
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_white,  ctx.getTheme()), // 7
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_black,  ctx.getTheme()), // 8
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_red,    ctx.getTheme()), // 9
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_green,  ctx.getTheme()), // 10
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_yellow, ctx.getTheme()), // 11
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_blue,   ctx.getTheme()), // 12
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_purple, ctx.getTheme()), // 13
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_cyan,   ctx.getTheme()), // 14
                ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bright_white,  ctx.getTheme()), // 15
            };
            stateMachine.setPalette(palette);
            Log.i(TAG, "applyTheme: pushed 16-colour palette");
        }
    }

    /**
     * Emit OSC 10/11 query bytes through the state machine so the
     * remote shell re-picks its contrast colour against the current
     * theme. Call this AFTER the SSH connection is live — calling
     * it during startup (before the SSH handshake completes) sends
     * raw bytes into the output buffer that end up on the wire as
     * garbled text.
     */
    public void queryShellTheme() {
        if (stateMachine == null) return;
        try {
            stateMachine.write(new byte[] { 0x1B, ']', '1', '0', ';', '?', 0x07 }, 0, 7);
            stateMachine.write(new byte[] { 0x1B, ']', '1', '1', ';', '?', 0x07 }, 0, 7);
            Log.i(TAG, "queryShellTheme: emitted OSC 10/11 ?");
        } catch (Throwable t) {
            Log.w(TAG, "queryShellTheme: OSC re-query write failed", t);
        }
    }

    private int currentBgColor() {
        if (ctx == null) return 0xFF002B36; // Solarized base03 fallback
        return ctx.getResources().getColor(com.qihua.bVNC.R.color.ssh_terminal_bg, ctx.getTheme());
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
        canvasRenderer = new VTermCanvasRenderer(fontSizePx, pad, terminalTypeface, ctx);
        int cols = Math.max(20, (initialPxW - 2 * pad) / (int) canvasRenderer.charWidth);
        int rows = Math.max(10, (initialPxH - 2 * pad) / canvasRenderer.charHeight);
        currentCols = cols;
        currentRows = rows;
        stateMachine = new SshTermStateMachine(cols, rows);
        // Cell height feeds scrollByPixels' px→row conversion for scrollback.
        stateMachine.setCellHeight(canvasRenderer.charHeight);
        // Wire libvterm's generated input bytes back to the SSH channel's stdin.
        stateMachine.setOutputStream(channel.getTerminalOut());
        // Reader thread: drains SSH bytes from channel.getTerminalIn()
        // into the libvterm state machine. Runs until close().
        // (SshShellChannel.getTerminalIn() returns an InputStream
        //  from which the shell's output is read.)
        final java.io.InputStream sshOut = channel.getTerminalIn();
        readerThread = new Thread(() -> {
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
                                + " bytes=" + formatBytes(readBuffer, n));
                        if (stateMachine != null) {
                            stateMachine.write(readBuffer, 0, n);
                        }
                        if (onUpdate != null) onUpdate.run();
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "readerThread: SSH read failed", e);
            }
        }, "SSH-VTerm-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
        channel.start();
        Log.i(TAG, "open: " + cols + " cols x " + rows + " rows, charW="
                + canvasRenderer.charWidth + " charH=" + canvasRenderer.charHeight
                + " pad=" + pad + "px");
    }

    public void renderInto(DoubleBufferBitmapData target) {
        if (closed || target == null) return;
        int w = target.getWidth();
        int h = target.getHeight();
        int pad = paddingPx();
        int cols = Math.max(20, (w - 2 * pad) / (int) canvasRenderer.charWidth);
        int rows = Math.max(10, (h - 2 * pad) / canvasRenderer.charHeight);
        if (cols != currentCols || rows != currentRows) {
            currentCols = cols;
            currentRows = rows;
            if (stateMachine != null) {
                int smCols = stateMachine.getCols();
                int smRows = stateMachine.getRows();
                if (cols <= smCols && rows <= smRows) {
                    stateMachine.setSize(cols, rows);
                    if (gridSizeListener != null) {
                        gridSizeListener.onGridSizeChanged(cols, rows);
                    }
                }
            }
        }
        final int bgColor = currentBgColor();
        target.paintAndPublish(canvas -> {
            canvas.drawColor(bgColor);
            if (stateMachine != null) {
                canvasRenderer.render(stateMachine, canvas);
            }
        });
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
     * Convenience for paste — write a String to the state machine,
     * one codepoint at a time. Used by SSH selection-menu "Paste" to
     * forward the Android clipboard into the remote shell.
     */
    public void writeString(String text) {
        if (stateMachine == null || text == null) return;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            int cp = text.codePointAt(i);
            stateMachine.writeInput(cp);
            // Skip the surrogate half so the next codePointAt picks up
            // the right value.
            if (Character.isSupplementaryCodePoint(cp)) i++;
        }
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

    /** Cell height in pixels — feeds scrollback px↔row conversion. */
    public int getCellHeight() {
        return canvasRenderer != null ? canvasRenderer.charHeight : 1;
    }

    /** Cell width in pixels. */
    public float getCellWidth() {
        return canvasRenderer != null ? canvasRenderer.charWidth : 1f;
    }

    /** Padding around the grid in pixels (left/top inset before col 0 / row 0). */
    public int getPaddingPx() {
        return canvasRenderer != null ? canvasRenderer.paddingPx : 0;
    }

    /**
     * Forward selection state to the underlying VTermCanvasRenderer so
     * the next render pass draws the highlight. Safe to call when
     * canvasRenderer hasn't been constructed yet (no-op).
     */
    public void setSelection(int anchorRow, int anchorCol, int endRow, int endCol) {
        if (canvasRenderer != null) {
            canvasRenderer.setSelection(anchorRow, anchorCol, endRow, endCol);
        }
    }

    /** Drop the active selection highlight. */
    public void clearSelection() {
        if (canvasRenderer != null) {
            canvasRenderer.clearSelection();
        }
    }

    /**
     * Y pixel of the libvterm cursor's bottom in mbitmap coordinates.
     * Used by the SSH IME push-up path to substitute for the
     * RDP-equivalent pointer.getY() in {@code RemoteCanvasActivity}'s
     * IME listener pan formula. Returns 0 if the renderer hasn't
     * opened yet (no state machine to query).
     */
    public int getCursorPixelY() {
        if (stateMachine == null || canvasRenderer == null) {
            return 0;
        }
        SshTermStateMachine.CursorInfo c = stateMachine.getCursor();
        if (c == null) {
            return 0;
        }
        // Bottom of the cursor's cell, not the centre. The RDP pan
        // formula wants pointerYPos to be the pixel position of "the
        // row the user is interacting with", so that
        // panDistance = pointerYPos + keyboardHeight - canvas.getHeight()
        // pushes the row to exactly keyboardHeight pixels below the
        // screen top — i.e. just above the keyboard. The cursor's
        // centre (row * charHeight + charHeight/2) under-counted by
        // half a cell and left the terminal content sitting one row
        // below the keyboard top.
        return (c.row + 2) * canvasRenderer.charHeight;
    }

    public void close() {
        if (closed) return;
        closed = true;
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

    /**
     * Format up to 256 bytes for logging: printable ASCII as-is, common
     * control chars as named escapes (\r \n \t \e \a \b), everything else
     * as \xNN. Used by the reader thread to see exactly what the remote
     * shell is emitting (zsh completion sequences, cursor moves, etc.).
     */
    private static String formatBytes(byte[] buf, int len) {
        int n = Math.min(len, 256);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            int b = buf[i] & 0xff;
            switch (b) {
                case 0x07: sb.append("\\a"); break;
                case 0x08: sb.append("\\b"); break;
                case 0x09: sb.append("\\t"); break;
                case 0x0a: sb.append("\\n"); break;
                case 0x0d: sb.append("\\r"); break;
                case 0x1b: sb.append("\\e"); break;
                default:
                    if (b >= 0x20 && b < 0x7f) {
                        sb.append((char) b);
                    } else {
                        sb.append(String.format("\\x%02x", b));
                    }
            }
        }
        if (len > n) sb.append("...(+").append(len - n).append(")");
        return sb.toString();
    }
}

package com.qihua.bVNC.connection;

import android.content.Context;
import android.view.Display;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.Connection;

/**
 * Strategy for one remote-desktop protocol's lifecycle. Owns everything
 * protocol-specific that RemoteCanvas used to inline: building the
 * communicator, pointer, keyboard, decoder, and starting the network.
 *
 * Lifecycle:
 *   initialize()  -- phase 1, on UI thread before cThread starts.
 *                    Build communicators/pointer/keyboard/decoder.
 *   start()       -- phase 2, on the cThread. Open the socket, do the
 *                    handshake, kick off network reads.
 *
 * Adding a 6th protocol = one new class + one factory entry.
 *
 * Per-protocol lifecycle hooks (teardown, surface-recreate, resize) live
 * only on the subclasses that need them; RemoteCanvas type-checks for
 * the concrete initializer when it needs to call one. See §8.17 for the
 * history of why we don't expose them as abstract methods.
 */
public abstract class ConnectionInitializer {
    /** Which protocol this initializer drives. */
    public abstract ProtocolType getType();

    /** True if this initializer handles the given connection. */
    public abstract boolean supports(Connection conn, Context ctx);

    /** Phase 1: build communicators, pointer, keyboard, decoder. Mutates canvas. */
    public abstract void initialize(RemoteCanvas canvas) throws Exception;

    /** Phase 2: actually start the network. Mutates canvas; runs in cThread. */
    public abstract void start(RemoteCanvas canvas) throws Exception;

    public abstract void onDisplayRectChanged(Display display);

    /**
     * Soft keyboard visibility changed. Default is a no-op — RDP / VNC /
     * SPICE / NVStream already have protocol-agnostic image-pan handling
     * in {@code RemoteCanvasActivity}. SSH overrides this because the
     * terminal grid has no overflow to pan; instead it reflows the grid
     * by resizing mbitmap to {@code availableHeight} (which
     * {@code SshTerminalRenderer.renderInto} notices and reports back as
     * a {@code TIOCSWINSZ} via {@code SshTerminalConnection.resizePty}).
     *
     * @param isShow         true if the IME just became visible
     * @param availableHeight the visible display frame bottom in pixels
     *                        (window height minus IME height, in window
     *                        coordinates) — the SSH initializer uses this
     *                        as the new bitmap height so the grid reflows
     *                        to fit above the keyboard
     */
    public void onSoftKeyboardChanged(boolean isShow, int availableHeight) {
    }
}

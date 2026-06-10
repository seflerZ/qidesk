package com.qihua.bVNC.connection;

import android.content.Context;
import android.graphics.Rect;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.Connection;

/**
 * Strategy for one remote-desktop protocol's lifecycle. Owns everything
 * protocol-specific that RemoteCanvas used to inline: building the
 * communicator, pointer, keyboard, decoder, starting the network, and
 * protocol-specific surface hooks.
 *
 * Lifecycle:
 *   initialize()  -- phase 1, on UI thread before cThread starts.
 *                    Build communicators/pointer/keyboard/decoder.
 *   start()       -- phase 2, on the cThread. Open the socket, do the
 *                    handshake, kick off network reads.
 *   onSurfaceCreated() / onDisplayRectChanged() / teardown() -- hooks
 *                    called by RemoteCanvas at matching lifecycle points.
 *   reinitialize()  -- rebuild after a credential change. Default impl
 *                    tears down then re-initializes.
 *
 * Adding a 6th protocol = one new class + one factory entry.
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

    /** Stop heartbeat, close rfbconn. Default no-op; SSH/RDP/NVStream override. */
    public void teardown(RemoteCanvas canvas) {
    }

    /** Re-trigger any per-protocol redraw after the surface is recreated. */
    public void onSurfaceCreated(RemoteCanvas canvas) {
    }

    /**
     * Called when displayRect changes (e.g. foldable fold/unfold).
     * SSH overrides to rebuild the framebuffer at the new size.
     */
    public void onDisplayRectChanged(RemoteCanvas canvas, Rect oldRect, Rect newRect) {
    }

    /**
     * True if this protocol needs a continuous redraw heartbeat because
     * nothing on the network pushes DrawTasks (e.g. SSH Phase 0 stub).
     */
    public boolean needsRedrawHeartbeat() {
        return false;
    }

    /** Heartbeat interval in ms; ignored if needsRedrawHeartbeat is false. */
    public int heartbeatIntervalMs() {
        return 33;
    }

    /** Re-initialize after credential change. Default: teardown + initialize. */
    public void reinitialize(RemoteCanvas canvas) throws Exception {
        teardown(canvas);
        initialize(canvas);
    }
}

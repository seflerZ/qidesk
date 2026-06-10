package com.qihua.bVNC.connection;

import android.content.Context;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.Connection;

/**
 * Picks the right ConnectionInitializer for a given Connection.
 * Mirrors the InputHandlerGamepad.initializeRemoteGamepad ladder.
 *
 * Stage 1 stub: returns null so the build still passes while the
 * concrete initializers are still being extracted. Subsequent stages
 * will populate create() as each protocol is migrated.
 */
public class ConnectionInitializerFactory {
    public static ConnectionInitializer create(Connection conn, Context ctx) {
        // SPICE / Opaque is selected by app flavor, not by getConnectionType().
        if (Utils.isSpice(ctx) || Utils.isOpaque(ctx)) {
            return new SpiceConnectionInitializer(conn, ctx);
        }
        if (conn == null) {
            return null;
        }
        int type = conn.getConnectionType();
        if (type == Constants.CONN_TYPE_VNC) {
            return new VncConnectionInitializer(conn, ctx);
        } else if (type == Constants.CONN_TYPE_RDP) {
            return new RdpConnectionInitializer(conn, ctx);
        } else if (type == Constants.CONN_TYPE_NVSTREAM) {
            return new NvStreamConnectionInitializer(conn, ctx);
        } else if (type == Constants.CONN_TYPE_SSH) {
            return new SshConnectionInitializer(conn, ctx);
        }
        return null;
    }
}

package com.qihua.bVNC.connection;

import android.content.Context;
import android.util.Log;

import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.input.RemoteRdpKeyboard;
import com.qihua.bVNC.input.RemoteRdpPointer;
import com.undatech.opaque.Connection;
import com.undatech.opaque.RdpCommunicator;

/**
 * RDP lifecycle owner. Builds the RdpCommunicator (FreeRDP wrapper),
 * wires RDP pointer/keyboard, and kicks off the network connect with
 * the user's resolution and quality settings.
 */
public class RdpConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "RdpConnectionInitializer";

    private final Connection conn;
    private final Context ctx;

    public RdpConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.RDP;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        return c != null && c.getConnectionType() == Constants.CONN_TYPE_RDP;
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "initialize: Initializing RDP connection.");

        canvas.rdpcomm = new RdpCommunicator(ctx, canvas.handler, canvas,
                canvas.connection.getUserName(), canvas.connection.getRdpDomain(),
                canvas.connection.getPassword(), App.debugLog);
        canvas.rfbconn = canvas.rdpcomm;
        canvas.pointer = new RemoteRdpPointer(canvas.rfbconn, canvas, canvas.handler, App.debugLog);
        canvas.keyboard = new RemoteRdpKeyboard(canvas.rdpcomm, canvas, canvas.handler,
                App.debugLog, false);

        // in order to support fractional sensitivity, we use the integer divide 10 to make it a float.
        canvas.pointer.setSensitivity(Utils.querySharedPreferenceInt(ctx, Constants.touchpadCursorSpeed, 10) / 10);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Starting RDP connection.");

        // Get the address and port (based on whether an SSH tunnel is being established or not).
        String address = canvas.getAddress();
        int rdpPort = canvas.getRemoteProtocolPort(canvas.connection.getPort());
        canvas.waitUntilInflated();
        int remoteWidth = canvas.getRemoteWidth(canvas.displayRect.width(), canvas.displayRect.height());
        int remoteHeight = canvas.getRemoteHeight(canvas.displayRect.width(), canvas.displayRect.height());

        canvas.rdpcomm.setConnectionParameters(address, rdpPort, canvas.connection.getNickname(), remoteWidth,
                // currently we don't support customize performance flags
                remoteHeight, true, true,
                false, false,
                false, true,
                canvas.connection.getRedirectSdCard(), canvas.connection.getConsoleMode(),
                canvas.connection.getRemoteSoundType(), canvas.connection.getEnableRecording(),
                canvas.connection.getRemoteFx(), canvas.connection.getEnableGfx(),
                canvas.connection.getEnableGfxH264(),
                canvas.connection.getRdpColor(), canvas.connection.getZoomLevel());
        canvas.rdpcomm.connect();
    }
}

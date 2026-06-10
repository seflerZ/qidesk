package com.qihua.bVNC.connection;

import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.os.SystemClock;

import com.qihua.bVNC.App;
import com.qihua.bVNC.COLORMODEL;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.Decoder;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.communicator.RfbCommunicator;
import com.qihua.bVNC.exceptions.AnonCipherUnsupportedException;
import com.qihua.bVNC.input.RemoteVncKeyboard;
import com.qihua.bVNC.input.RemoteVncPointer;
import com.tigervnc.rfb.AuthFailureException;
import com.undatech.opaque.Connection;
import com.undatech.opaque.RemoteClientLibConstants;

/**
 * VNC lifecycle owner. Builds the Decoder + RfbCommunicator, wires
 * VNC-flavoured pointer/keyboard, drives the handshake, and dispatches
 * password / username / auth-failure prompts back to the canvas's
 * RemoteCanvasHandler.
 *
 * sendUnixAuth is the VNC-over-SSH helper that types the unix username
 * and password into the VNC server. It stays here because it's a
 * VNC-driven step (the SSH tunnel has already been set up by the VNC
 * communicator's connection sequence).
 */
public class VncConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "VncConnectionInitializer";

    private final Connection conn;
    private final Context ctx;

    public VncConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.VNC;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        return c != null && c.getConnectionType() == Constants.CONN_TYPE_VNC;
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "Initializing connection to: " + canvas.connection.getAddress()
                + ", port: " + canvas.connection.getPort());
        boolean sslTunneled = canvas.connection.getConnectionType() == Constants.CONN_TYPE_STUNNEL;
        canvas.decoder = new Decoder(canvas,
                canvas.connection.getUseLocalCursor() == Constants.CURSOR_FORCE_LOCAL);
        canvas.rfb = new RfbCommunicator(canvas.decoder, canvas,
                canvas.connection.getPrefEncoding(), canvas.connection.getViewOnly(),
                sslTunneled, canvas.connection.getIdHashAlgorithm(),
                canvas.connection.getIdHash(), canvas.connection.getX509KeySignature(),
                App.debugLog);

        canvas.rfbconn = canvas.rfb;
        canvas.pointer = new RemoteVncPointer(canvas.rfbconn, canvas, canvas.handler, App.debugLog);
        boolean rAltAsIsoL3Shift = Utils.querySharedPreferenceBoolean(ctx,
                Constants.rAltAsIsoL3ShiftTag);
        canvas.keyboard = new RemoteVncKeyboard(canvas.rfbconn, canvas, canvas.handler,
                rAltAsIsoL3Shift, App.debugLog);

        // in order to support fractional sensitivity, we use the integer divide 10 to make it a float.
        canvas.pointer.setSensitivity(Utils.querySharedPreferenceInt(ctx, Constants.touchpadCursorSpeed, 10) / 10);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        try {
            String address = canvas.getAddress();
            int vncPort = canvas.getRemoteProtocolPort(canvas.connection.getPort());
            Log.i(TAG, "Establishing VNC session to: " + address + ", port: " + vncPort);
            // TODO: VNC Server cert is not set when the connection is SSH tunneled because there at
            // TODO: present it is assumed the connection is either SSH tunneled or x509 encrypted,
            // TODO: and when both are the case, there is no way to save the x509 cert.
            String sslCert = canvas.connection.getX509KeySignature();
            canvas.rfb.initializeAndAuthenticate(address, vncPort, canvas.connection.getUserName(),
                    canvas.connection.getPassword(), canvas.connection.getUseRepeater(),
                    canvas.connection.getRepeaterId(), canvas.connection.getConnectionType(),
                    sslCert);
        } catch (AnonCipherUnsupportedException e) {
            canvas.showFatalMessageAndQuit(ctx.getString(com.qihua.bVNC.R.string.error_anon_dh_unsupported));
            return;
        } catch (RfbCommunicator.RfbPasswordAuthenticationException e) {
            Log.e(TAG, "Authentication failed, will prompt user for password");
            canvas.handler.sendEmptyMessage(RemoteClientLibConstants.GET_VNC_PASSWORD);
            return;
        } catch (RfbCommunicator.RfbUsernameRequiredException e) {
            Log.e(TAG, "Username required, will prompt user for username and password");
            canvas.handler.sendEmptyMessage(RemoteClientLibConstants.GET_VNC_CREDENTIALS);
            return;
        } catch (AuthFailureException e) {
            Log.e(TAG, "TigerVNC AuthFailureException: " + e.getLocalizedMessage());
            canvas.handler.sendEmptyMessage(RemoteClientLibConstants.GET_VNC_CREDENTIALS);
            return;
        } catch (Exception e) {
            throw new Exception(ctx.getString(com.qihua.bVNC.R.string.error_vnc_unable_to_connect) +
                    Utils.messageAndStackTraceAsString(e));
        }

        canvas.rfb.writeClientInit();
        canvas.rfb.readServerInit();

        // Is custom resolution enabled?
        if (canvas.connection.getRdpResType() != Constants.VNC_GEOM_SELECT_DISABLED) {
            canvas.waitUntilInflated();
            canvas.rfb.setPreferredFramebufferSize(
                    canvas.getRemoteWidth(canvas.displayRect.width(), canvas.displayRect.height()),
                    canvas.getRemoteHeight(canvas.displayRect.width(), canvas.displayRect.height()));
        }

        canvas.reallocateDrawable(canvas.displayRect.width(), canvas.displayRect.height());
        canvas.decoder.setPixelFormat(canvas.rfb);

        canvas.handler.post(() ->
                canvas.progressDialog.setMessage(ctx.getString(com.qihua.bVNC.R.string.info_progress_dialog_downloading)));

        sendUnixAuth(canvas);

        try {
            canvas.rfb.processProtocol();
        } catch (RfbCommunicator.RfbUltraVncColorMapException e) {
            Log.e(TAG, "UltraVnc supports only 24bpp. Switching color mode and reconnecting.");
            canvas.connection.setColorModel(COLORMODEL.C24bit.nameString());
            canvas.connection.save(ctx);
            canvas.handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
        }
    }

    /**
     * VNC-over-SSH helper: types the unix username and password into
     * the VNC server. Only fires when the connection is ssh-tunneled
     * and the user has enabled AutoXUnixAuth (x11vnc's "-unixpw" mode).
     */
    private void sendUnixAuth(RemoteCanvas canvas) {
        if (canvas.sshTunneled && canvas.connection.getAutoXUnixAuth()) {
            canvas.keyboard.keyEvent(KeyEvent.KEYCODE_UNKNOWN, new KeyEvent(SystemClock.uptimeMillis(),
                    canvas.connection.getSshUser(), 0, 0));
            canvas.keyboard.keyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            canvas.keyboard.keyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));

            canvas.keyboard.keyEvent(KeyEvent.KEYCODE_UNKNOWN, new KeyEvent(SystemClock.uptimeMillis(),
                    canvas.connection.getSshPassword(), 0, 0));
            canvas.keyboard.keyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            canvas.keyboard.keyEvent(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }
}

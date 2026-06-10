package com.qihua.bVNC.connection;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceHolder;
import android.widget.Toast;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.R;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.input.RemoteNvStreamKeyboard;
import com.qihua.bVNC.input.RemoteNvStreamPointer;
import com.undatech.opaque.Connection;
import com.undatech.opaque.NvCommunicator;

/**
 * NVStream (Sunshine/Moonlight) lifecycle owner. Builds the
 * NvCommunicator, wires NVStream pointer/keyboard, builds the
 * PreferenceConfiguration (resolution, bitrate, FPS, HDR, audio, etc.),
 * and kicks off the network connect.
 *
 * The computer is looked up in the activity's ComputerDetails cache by
 * the SSH server UUID (reusing the SSH field for the machine's UUID is
 * a project convention).
 */
public class NvStreamConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "NvStreamConnectionInitializer";

    private final Connection conn;
    private final Context ctx;

    public NvStreamConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.NVSTREAM;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        return c != null && c.getConnectionType() == Constants.CONN_TYPE_NVSTREAM;
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "initialize: Initializing NvStream connection.");

        canvas.nvcomm = new NvCommunicator(canvas.activity, canvas, canvas.handler);
        canvas.rfbconn = canvas.nvcomm;

        canvas.pointer = new RemoteNvStreamPointer(canvas.nvcomm, canvas, canvas.handler, App.debugLog);
        canvas.keyboard = new RemoteNvStreamKeyboard(canvas.nvcomm, canvas, canvas.handler, App.debugLog);

        // in order to support fractional sensitivity, we use the integer divide 10 to make it a float.
        canvas.pointer.setSensitivity(Utils.querySharedPreferenceInt(ctx, Constants.touchpadCursorSpeed, 10) / 10);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "start: Starting NvStream connection.");
        startWithSurface(canvas, canvas.surfaceHolder);
    }

    private void startWithSurface(RemoteCanvas canvas, SurfaceHolder surfaceHolder) throws Exception {
        // We reuse the SSH server as the UUID of the computer
        String uuid = canvas.connection.getSshServer();
        ComputerDetails computerDetails = canvas.activity.getComputerDetail(uuid);
        if (computerDetails == null) {
            throw new IllegalStateException("computer not found, UUID: " + uuid);
        }

        String appName = canvas.connection.getUserName();
        int appId = Integer.parseInt(canvas.connection.getPassword());

        int remoteWidth = canvas.getRemoteWidth(canvas.displayRect.width(), canvas.displayRect.height());
        int remoteHeight = canvas.getRemoteHeight(canvas.displayRect.width(), canvas.displayRect.height());

        // defined here now, can be configured in later versions
        PreferenceConfiguration prefConfig = new PreferenceConfiguration();
        prefConfig.absoluteMouseMode = true;
        prefConfig.enableAudioFx = false;
        prefConfig.fps = 60;
        prefConfig.enableSops = true;
        prefConfig.bindAllUsb = true;
        prefConfig.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        prefConfig.multiController = false;
        prefConfig.disableWarnings = true;
        prefConfig.enablePip = false;
        prefConfig.width = remoteWidth;
        prefConfig.height = remoteHeight;
        prefConfig.enableHdr = false;
        prefConfig.bitrate = 18000 * (remoteWidth / 1920);
        prefConfig.disableWarnings = true;
        prefConfig.incomingFrameQueueSize = 2;
        prefConfig.videoFormat = PreferenceConfiguration.FormatOption.AUTO;
        prefConfig.enableLatencyToast = false;
        prefConfig.enablePerfOverlay = Utils.querySharedPreferenceBoolean(canvas.activity, Constants.enableDebugInfo, false);
//        prefConfig.videoFormat = PreferenceConfiguration.FormatOption.FORCE_H264;

        // reduce bitrate if on cellular connection
        if (!computerDetails.activeAddress.address.equals(computerDetails.localAddress.address)) {
            prefConfig.bitrate = 10000 * (remoteWidth / 1920);
            prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
            prefConfig.incomingFrameQueueSize = 3;
            prefConfig.fps = 60;

            canvas.activity.runOnUiThread(() -> Toast.makeText(canvas.activity.getApplicationContext()
                    , R.string.cellular_connection_warning, Toast.LENGTH_SHORT).show());
        }

        canvas.nvcomm.setConnectionParameters(computerDetails.activeAddress.address,
                computerDetails.activeAddress.port,
                computerDetails.httpsPort, remoteWidth, remoteHeight,
                canvas.activity.getUniqueId(), appName,
                appId, computerDetails.serverCert,
                prefConfig);

        canvas.nvcomm.connect(surfaceHolder);

        canvas.controller = new ControllerHandler(canvas.activity, canvas.nvcomm.getConnection(),
                canvas.activity, canvas.nvcomm.getPrefConfig());
    }
}

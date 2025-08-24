package com.undatech.opaque;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.View;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class NvCommunicator extends RfbConnectable implements NvConnectionListener, PerfOverlayListener {
    private MediaCodecDecoderRenderer decoderRenderer;
    private NvConnection conn;
    private NvApp app;
    private Activity activity;
    private final Viewable viewable;
    private boolean attemptedConnection;
    private Handler handler;

    private final static int PTRFLAGS_HWHEEL = 0x0400;
    private final static int PTRFLAGS_WHEEL = 0x0200;
    private final static int PTRFLAGS_WHEEL_NEGATIVE = 0x0100;
    //private final static int PTRFLAGS_DOWN           = 0x8000;

    public static final int POINTER_DOWN_MASK = 0x8000;

    private final static int MOUSE_BUTTON_NONE = 0x0000;
    private final static int MOUSE_BUTTON_MOVE = 0x0800;
    private final static int MOUSE_BUTTON_LEFT = 0x1000;
    private final static int MOUSE_BUTTON_RIGHT = 0x2000;

    private static final int MOUSE_BUTTON_MIDDLE = 0x4000;
    private static final int MOUSE_BUTTON_SCROLL_UP = PTRFLAGS_WHEEL | 0x0058;
    private static final int MOUSE_BUTTON_SCROLL_DOWN = PTRFLAGS_WHEEL | PTRFLAGS_WHEEL_NEGATIVE | 0x00a8;
    private static final int MOUSE_BUTTON_SCROLL_LEFT = PTRFLAGS_HWHEEL | 0x0058;
    private static final int MOUSE_BUTTON_SCROLL_RIGHT = PTRFLAGS_HWHEEL | PTRFLAGS_WHEEL_NEGATIVE | 0x00a8;

    public NvCommunicator(Activity activity, Viewable viewable, Handler handler) {
        super(false, handler);
        this.activity = activity;
        this.viewable = viewable;
        this.attemptedConnection = false;
        this.handler = handler;
    }

    public void setConnectionParameters(String host, int port, int httpsPort,
                                        String uniqueId, String appName, int appId,
                                        X509Certificate serverCert) {
        app = new NvApp(appName != null ? appName : "app", appId, false);
        Context context = this.activity.getApplicationContext();

        // defined here now, can be configured in later versions
        PreferenceConfiguration prefConfig = new PreferenceConfiguration();
        prefConfig.width = 1920;
        prefConfig.height = 1080;
        prefConfig.enableHdr = false;
        prefConfig.bitrate = 12_000_000;
        prefConfig.absoluteMouseMode = true;
        prefConfig.enableAudioFx = true;
        prefConfig.fps = 60;
        prefConfig.enableSops = true;
        prefConfig.bindAllUsb = true;
        prefConfig.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        prefConfig.multiController = false;

        viewable.reallocateDrawable(prefConfig.width, prefConfig.height);

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(context);
        MediaCodecHelper.initialize(context, glPrefs.glRenderer);

        decoderRenderer = new MediaCodecDecoderRenderer(
                activity,
                prefConfig,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {

                    }
                },
                0,
                false,
                prefConfig.enableHdr,
                glPrefs.glRenderer,
                this);

        // H.264 is always supported
        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
            if (prefConfig.enableHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (prefConfig.enableHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
            }
        }

        // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        int roundedRefreshRate = 90;
        int chosenFrameRate = prefConfig.fps;
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Using drop mode for FPS > Hz");
                } else if (roundedRefreshRate <= 49) {
                    // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Bogus refresh rate: " + roundedRefreshRate);
                }
                else {
                    chosenFrameRate = roundedRefreshRate - 1;
                    LimeLog.info("Adjusting FPS target for screen to " + chosenFrameRate);
                }
            }
        }

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setLaunchRefreshRate(prefConfig.fps)
                .setRefreshRate(prefConfig.fps)
                .setApp(app)
                .setBitrate(prefConfig.bitrate)
                .setEnableSops(false)
                .enableLocalAudioPlayback(false)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(1)
                .setClientRefreshRateX100((int)(prefConfig.fps * 100))
                .setAudioConfiguration(prefConfig.audioConfiguration)
                .setColorSpace(decoderRenderer.getPreferredColorSpace())
                .setColorRange(decoderRenderer.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
                .build();

        // Initialize the connection
        conn = new NvConnection(context,
                new ComputerDetails.AddressTuple(host, port),
                httpsPort, uniqueId, config,
                PlatformBinding.getCryptoProvider(context), serverCert);
    }

    public void connect(SurfaceHolder surfaceHolder) {
        if (!attemptedConnection) {
            attemptedConnection = true;
            decoderRenderer.setRenderTarget(surfaceHolder);
            decoderRenderer.setGraphicsListener((surface, x, y, width, height) -> {
                if (viewable == null) {
                    return;
                }

                Bitmap bitmap = viewable.getBitmap();
                if (bitmap == null) {
                    return;
                }

                PixelCopy.request(surface, bitmap, (result) -> {
                    if (result != PixelCopy.SUCCESS) {
                        return;
                    }

                    DrawTask drawTask = new DrawTask(x, y, width, height, true);

                    viewable.reDraw(drawTask);
                }, handler);
            });

            Context context = activity.getApplicationContext();
            conn.start(new AndroidAudioRenderer(context, false),
                    decoderRenderer, NvCommunicator.this);
        }
    }

    @Override
    public void stageStarting(String stage) {
//        android.util.Log.d(TAG, "OnSettingsChanged called, wxh: " + width + "x" + height);
    }

    @Override
    public void stageComplete(String stage) {

    }

    @Override
    public void stageFailed(String stage, int portFlags, int errorCode) {

    }

    @Override
    public void connectionStarted() {

    }

    @Override
    public void connectionTerminated(int errorCode) {

    }

    @Override
    public void connectionStatusUpdate(int connectionStatus) {

    }

    @Override
    public void displayMessage(String message) {

    }

    @Override
    public void displayTransientMessage(String message) {

    }

    @Override
    public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {

    }

    @Override
    public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {

    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {

    }

    @Override
    public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {

    }

    @Override
    public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {

    }

    @Override
    public void onPerfUpdate(String text) {

    }

    @Override
    public int framebufferWidth() {
        return 1920;
    }

    @Override
    public int framebufferHeight() {
        return 1080;
    }

    @Override
    public String desktopName() {
        return "Game";
    }

    @Override
    public void requestUpdate(boolean incremental) {

    }

    @Override
    public void requestResolution(int x, int y) throws Exception {

    }

    @Override
    public void writeClientCutText(String text) {

    }

    @Override
    public void setIsInNormalProtocol(boolean state) {

    }

    @Override
    public boolean isInNormalProtocol() {
        return false;
    }

    @Override
    public String getEncoding() {
        return null;
    }

    @Override
    public void writePointerEvent(int x, int y, int metaState, int pointerMask, boolean relative) {
        if ((pointerMask & MOUSE_BUTTON_MOVE) > 0) {
            if (relative) {
                MoonBridge.sendMouseMoveAsMousePosition((short) x, (short) y,
                        (short) framebufferWidth(), (short) framebufferHeight());
            } else {
                MoonBridge.sendMousePosition((short) x, (short) y,
                        (short) framebufferWidth(), (short) framebufferHeight());
            }
        }

        if ((pointerMask & MOUSE_BUTTON_LEFT) > 0) {
            if ((pointerMask & POINTER_DOWN_MASK) > 0) {
                MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, MouseButtonPacket.BUTTON_LEFT);
            } else {
                MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, MouseButtonPacket.BUTTON_LEFT);
            }
        }

        if ((pointerMask & MOUSE_BUTTON_RIGHT) > 0) {
            if ((pointerMask & POINTER_DOWN_MASK) > 0) {
                MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, MouseButtonPacket.BUTTON_RIGHT);
            } else {
                MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, MouseButtonPacket.BUTTON_RIGHT);
            }
        }
    }

    @Override
    public void writeKeyEvent(int key, int metaState, boolean down) {

    }

    @Override
    public void writeSetPixelFormat(int bitsPerPixel, int depth, boolean bigEndian, boolean trueColour, int redMax, int greenMax, int blueMax, int redShift, int greenShift, int blueShift, boolean fGreyScale) {

    }

    @Override
    public void writeFramebufferUpdateRequest(int x, int y, int w, int h, boolean b) {

    }

    @Override
    public void close() {

    }

    @Override
    public void reconnect() {

    }

    @Override
    public boolean isCertificateAccepted() {
        return false;
    }

    @Override
    public void setCertificateAccepted(boolean certificateAccepted) {

    }
}

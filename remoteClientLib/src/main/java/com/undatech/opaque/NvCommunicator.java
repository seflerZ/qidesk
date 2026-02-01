package com.undatech.opaque;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.undatech.opaque.input.RemoteKeyboard;
import com.undatech.opaque.input.RemotePointer;
import com.undatech.remoteClientLib.R;

import java.security.cert.X509Certificate;

public class NvCommunicator extends RfbConnectable implements NvConnectionListener, PerfOverlayListener {
    private PreferenceConfiguration prefConfig;
    private MediaCodecDecoderRenderer decoderRenderer;
    private NvConnection conn;
    private NvApp app;
    private Activity activity;
    private final Viewable viewable;
    private boolean attemptedConnection;
    private KeyboardTranslator keyboardTranslator;
    private Handler handler;

    private int framebufferWidth;
    private int framebufferHeight;

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
    private String debugMsg;

    public NvCommunicator(Activity activity, Viewable viewable, Handler handler) {
        super(false, handler);
        this.activity = activity;
        this.viewable = viewable;
        this.attemptedConnection = false;
        this.handler = handler;

        this.keyboardTranslator = new KeyboardTranslator();

        modifierMap.put(RemoteKeyboard.CTRL_MASK, (int) KeyboardPacket.MODIFIER_CTRL);
        modifierMap.put(RemoteKeyboard.RCTRL_MASK, (int) KeyboardPacket.MODIFIER_CTRL);
        modifierMap.put(RemoteKeyboard.ALT_MASK, (int) KeyboardPacket.MODIFIER_ALT);
        modifierMap.put(RemoteKeyboard.RALT_MASK, (int) KeyboardPacket.MODIFIER_ALT);
        modifierMap.put(RemoteKeyboard.SUPER_MASK, (int) KeyboardPacket.MODIFIER_META);
        modifierMap.put(RemoteKeyboard.RSUPER_MASK, (int) KeyboardPacket.MODIFIER_META);
        modifierMap.put(RemoteKeyboard.SHIFT_MASK, (int) KeyboardPacket.MODIFIER_SHIFT);
        modifierMap.put(RemoteKeyboard.RSHIFT_MASK, (int) KeyboardPacket.MODIFIER_SHIFT);
    }

    public void setConnectionParameters(String host, int port, int httpsPort, int remoteWidth, int remoteHeight,
                                        String uniqueId, String appName, int appId,
                                        X509Certificate serverCert, PreferenceConfiguration prefConfig) {
        app = new NvApp(appName != null ? appName : "app", appId, false);
        Context context = this.activity.getApplicationContext();

        framebufferWidth = remoteWidth;
        framebufferHeight = remoteHeight;

        viewable.reallocateDrawable(prefConfig.width, prefConfig.height);

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(context);
        MediaCodecHelper.initialize(context, glPrefs.glRenderer);

        decoderRenderer = new MediaCodecDecoderRenderer(
                activity,
                prefConfig,
                e -> {},
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
        int roundedRefreshRate = 60;
        int chosenFrameRate = prefConfig.fps;
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Using drop mode for FPS > Hz");
                } else {
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
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(false)
                .setAudioEncryption(false)
                .setMaxPacketSize(1460)
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

        this.prefConfig = prefConfig;
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
                    drawTask.setDebugMsg(debugMsg);

                    viewable.reDraw(drawTask);
                }, handler);
            });

            Context context = activity.getApplicationContext();
            conn.start(new AndroidAudioRenderer(context, true),
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
        activity.runOnUiThread(() -> {
            String text = "Connection failed\nStage: " + stage + ", Error code: " + errorCode;
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
        });
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
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void displayTransientMessage(String message) {
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        });
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
        if (prefConfig.enablePerfOverlay) {
            debugMsg = text;
        }
    }

    @Override
    public int framebufferWidth() {
        return framebufferWidth;
    }

    @Override
    public int framebufferHeight() {
        return framebufferHeight;
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
        if (conn != null) {
            conn.sendUtf8Text(text);
        }
    }

    @Override
    public void setIsInNormalProtocol(boolean state) {

    }

    @Override
    public boolean isInNormalProtocol() {
        return true;
    }

    @Override
    public String getEncoding() {
        return null;
    }

    @Override
    public void writePointerEvent(int x, int y, int metaState, int pointerMask, boolean relative) {
        this.metaState = metaState;
        if ((pointerMask & RemotePointer.POINTER_DOWN_MASK) != 0) {
            translateModifierKeys(true);
        }

        if ((pointerMask & MOUSE_BUTTON_MOVE) > 0) {
            if (relative) {
                MoonBridge.sendMouseMoveAsMousePosition((short) x, (short) y,
                        (short) framebufferWidth(), (short) framebufferHeight());
            } else {
                MoonBridge.sendMousePosition((short) x, (short) y,
                        (short) framebufferWidth(), (short) framebufferHeight());
            }
            return;
        }

        if ((pointerMask & MOUSE_BUTTON_LEFT) > 0) {
            if ((pointerMask & POINTER_DOWN_MASK) > 0) {
                MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, MouseButtonPacket.BUTTON_LEFT);
            } else {
                MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, MouseButtonPacket.BUTTON_LEFT);
            }
            return;
        }

        if ((pointerMask & MOUSE_BUTTON_MIDDLE) > 0) {
            if ((pointerMask & POINTER_DOWN_MASK) > 0) {
                MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, MouseButtonPacket.BUTTON_MIDDLE);
            } else {
                MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, MouseButtonPacket.BUTTON_MIDDLE);
            }
            return;
        }

        if ((pointerMask & MOUSE_BUTTON_RIGHT) > 0) {
            if ((pointerMask & POINTER_DOWN_MASK) > 0) {
                MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, MouseButtonPacket.BUTTON_RIGHT);
            } else {
                MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, MouseButtonPacket.BUTTON_RIGHT);
            }
            return;
        }

        if ((pointerMask & PTRFLAGS_WHEEL) == PTRFLAGS_WHEEL) {
            short distance = (short) (pointerMask & 0x00ff);
            if ((pointerMask & PTRFLAGS_WHEEL_NEGATIVE) == PTRFLAGS_WHEEL_NEGATIVE) {
                distance = ((short)((distance - 256) * 3));
            } else {
                distance = ((short)(distance * 3));
            }

            MoonBridge.sendMouseHighResScroll(distance);
            return;
        }

        if ((pointerMask & PTRFLAGS_HWHEEL) == PTRFLAGS_HWHEEL) {
            short distance = (short) (pointerMask & 0x00ff);

            if ((pointerMask & PTRFLAGS_WHEEL_NEGATIVE) == PTRFLAGS_WHEEL_NEGATIVE) {
                distance = (short)((distance - 256) * 3);
            } else {
                distance = (short)(distance * 3);
            }

            MoonBridge.sendMouseHighResHScroll(distance);
            return;
        }

        if ((pointerMask & RemotePointer.POINTER_DOWN_MASK) == 0) {
            translateModifierKeys(false);
        }
    }

    @Override
    public void writeKeyEvent(int key, int metaState, boolean down) {
        this.metaState = metaState;

        translateModifierKeys(down);

        conn.sendKeyboardInput((short) key
                , down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP
                , (byte)remoteKeyboardState.getRemoteMetaState()
                , (byte)0);
    }

    // Returns true if the key stroke was consumed
    private void translateModifierKeys(boolean down) {
        for (int modifierMask : modifierMap.keySet()) {
            if ((metaState & modifierMask) > 0) {
                int modifier = modifierMap.get(modifierMask);
                remoteKeyboardState.updateRemoteMetaState(modifier, down);
            }
        }
    }

    @Override
    public void writeSetPixelFormat(int bitsPerPixel, int depth, boolean bigEndian, boolean trueColour, int redMax, int greenMax, int blueMax, int redShift, int greenShift, int blueShift, boolean fGreyScale) {

    }

    @Override
    public void writeTouchEvent(int x, int y, int flags, int contactId) {
        // 使用MoonBridge发送触摸事件到NVStream服务器
        // 将坐标转换为浮点数格式，范围从0.0到1.0
        float normalizedX = (float)x / (float)framebufferWidth();
        float normalizedY = (float)y / (float)framebufferHeight();

        // 调用底层库发送触摸事件
        MoonBridge.sendTouchEvent(
                (byte) flags,          // event type (down/up/move)
                contactId,          // pointer identifier
                normalizedX,        // normalized x coordinate
                normalizedY,        // normalized y coordinate
                1.0f,               // pressure
                1.0f,               // contact area major
                1.0f,               // contact area minor
                (short) 0            // rotation
        );
    }

    public void setPrefConfig(PreferenceConfiguration prefConfig) {
        this.prefConfig = prefConfig;
    }

    @Override
    public void writeFramebufferUpdateRequest(int x, int y, int w, int h, boolean b) {

    }

    @Override
    public void close() {
        if (prefConfig.enableLatencyToast) {
            int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
            int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
            String message = null;
            if (averageEndToEndLat > 0) {
                message = activity.getApplicationContext().getString
                        (R.string.conn_client_latency)+" "+averageEndToEndLat+" ms";
                if (averageDecoderLat > 0) {
                    message += " ("+activity.getApplicationContext().getString
                            (R.string.conn_client_latency_hw)+" "+averageDecoderLat+" ms)";
                }
            }
            else if (averageDecoderLat > 0) {
                message = activity.getApplicationContext().getString
                        (R.string.conn_hardware_latency)+" "+averageDecoderLat+" ms";
            }

            int videoFormat = decoderRenderer.getActiveVideoFormat();
            // Add the video codec to the post-stream toast
            if (message != null) {
                message += " [";

                if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                    message += "H.264";
                }
                else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                    message += "HEVC";
                }
                else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                    message += "AV1";
                }
                else {
                    message += "UNKNOWN";
                }

                if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                    message += " HDR";
                }

                message += "]";
            }

            if (message != null) {
                displayMessage(message);
            }
        }

        conn.stop();
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

    public NvConnection getConnection() {
        return conn;
    }

    public PreferenceConfiguration getPrefConfig() {
        return prefConfig;
    }
}

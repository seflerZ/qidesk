package com.undatech.opaque;

import android.app.Activity;
import android.content.Context;
import android.view.SurfaceHolder;

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
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class NvCommunicator implements NvConnectionListener, PerfOverlayListener {
    private MediaCodecDecoderRenderer decoderRenderer;
    private NvConnection conn;
    private NvApp app;
    private Activity activity;
    private boolean attemptedConnection;

    public NvCommunicator(Activity activity) {
        this.activity = activity;
        this.attemptedConnection = false;
    }

    public void setConnectionParameters(String host, int port, int httpsPort,
                                        String uniqueId, String appName, int appId, byte[] derCertData) {
        app = new NvApp(appName != null ? appName : "app", appId, false);
        Context context = this.activity.getApplicationContext();

        // defined here now, can be configured in later versions
        PreferenceConfiguration prefConfig = new PreferenceConfiguration();
        prefConfig.width = 1920;
        prefConfig.height = 1080;
        prefConfig.enableHdr = false;
        prefConfig.bitrate = 1_000_000;
        prefConfig.absoluteMouseMode = true;
        prefConfig.enableAudioFx = false;
        prefConfig.fps = 60;
        prefConfig.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        prefConfig.multiController = false;

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

        X509Certificate serverCert = null;
        try {
            if (derCertData != null) {
                serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(derCertData));
            }
        } catch (CertificateException e) {
            throw new IllegalArgumentException("Invalid cert data");
        }

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

            Context context = activity.getApplicationContext();
            conn.start(new AndroidAudioRenderer(context, false),
                    decoderRenderer, NvCommunicator.this);
        }
    }

    @Override
    public void stageStarting(String stage) {

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
}

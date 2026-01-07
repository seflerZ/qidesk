package com.undatech.opaque;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ManualBookmark;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.undatech.opaque.input.RdpKeyboardMapper;
import com.undatech.opaque.input.RemoteKeyboard;
import com.undatech.opaque.input.RemotePointer;
import com.undatech.opaque.util.GeneralUtils;

import org.apache.commons.validator.routines.InetAddressValidator;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;

public class RdpCommunicator extends RfbConnectable implements RdpKeyboardMapper.KeyProcessingListener,
        LibFreeRDP.UIEventListener, LibFreeRDP.EventListener {
    static final String TAG = "RdpCommunicator";

    private final static int VK_CONTROL = 0x11;
    private final static int VK_LCONTROL = 0xA2;
    private final static int VK_RCONTROL = 0xA3;
    private final static int VK_LMENU = 0xA4;
    private final static int VK_RMENU = 0xA5;
    private final static int VK_LSHIFT = 0xA0;
    private final static int VK_RSHIFT = 0xA1;
    private final static int VK_LWIN = 0x5B;
    private final static int VK_RWIN = 0x5C;
    private final static int VK_EXT_KEY = 0x00000100;
    private final RdpCommunicator myself;
    private final Viewable viewable;
    private SessionState session;
    private BookmarkBase bookmark;
    // Keeps track of libFreeRDP instance
    private GlobalApp freeRdpApp;
    private Context context;
    private boolean isInNormalProtocol = false;
    // This variable indicates whether or not the user has accepted an untrusted
    // security certificate. Used to control progress while the dialog asking the user
    // to confirm the authenticity of a certificate is displayed.
    private boolean certificateAccepted = false;
    private boolean reattemptWithoutCredentials = false;
    private boolean authenticationAttempted = false;
    private boolean disconnectRequested = false;

    private String username, password, domain;

    public RdpCommunicator(Context context, Handler handler, Viewable viewable, String username,
                           String domain, String password, boolean debugLogging) {
        super(debugLogging, handler);
        // This is necessary because it initializes a synchronizedMap referenced later.
        this.freeRdpApp = new GlobalApp();
        patchFreeRdpCore();
        // Create a manual bookmark and populate it from settings.
        this.bookmark = new ManualBookmark();
        this.context = context;
        this.viewable = viewable;
        this.myself = this;
        this.username = username;
        this.domain = domain;
        this.password = password;
        modifierMap.put(RemoteKeyboard.CTRL_MASK, VK_LCONTROL);
        modifierMap.put(RemoteKeyboard.RCTRL_MASK, (VK_RCONTROL | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.ALT_MASK, VK_LMENU);
        modifierMap.put(RemoteKeyboard.RALT_MASK, (VK_RMENU | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.SUPER_MASK, (VK_LWIN | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.RSUPER_MASK, (VK_RWIN | VK_EXT_KEY));
        modifierMap.put(RemoteKeyboard.SHIFT_MASK, VK_LSHIFT);
        modifierMap.put(RemoteKeyboard.RSHIFT_MASK, VK_RSHIFT);
        initSession(username, domain, password);
    }

    private void patchFreeRdpCore() {
        Class cls = this.freeRdpApp.getClass();
        try {
            Log.i(TAG, "Initializing sessionMap in GlobalApp");
            Field sessionMap = cls.getDeclaredField("sessionMap");
            sessionMap.setAccessible(true);
            sessionMap.set(this.freeRdpApp, Collections.synchronizedMap(new HashMap<Long, SessionState>()));
        } catch (NoSuchFieldException e) {
            Log.e(TAG, "There is no longer a sessionMap field in GlobalApp");
        } catch (IllegalAccessException e) {
            Log.e(TAG, "The field sessionMap in GlobalApp was not accessible despite our attempts");
        }
    }

    @Override
    public void setIsInNormalProtocol(boolean state) {
        android.util.Log.d(TAG, "setIsInNormalProtocol: " + state);
        isInNormalProtocol = state;
    }

    @Override
    public int framebufferWidth() {
        return session.getBookmark().getActiveScreenSettings().getWidth();
    }

    @Override
    public int framebufferHeight() {
        return session.getBookmark().getActiveScreenSettings().getHeight();
    }

    @Override
    public String desktopName() {
        return ((ManualBookmark) session.getBookmark()).getHostname();
    }

    @Override
    public void requestUpdate(boolean incremental) {
        // NOT USED for RDP.
    }

    @Override
    public void writeClientCutText(String text) {
        synchronized (viewable) {
            LibFreeRDP.sendClipboardData(session.getInstance(), text);
        }
    }

    @Override
    public boolean isInNormalProtocol() {
        return isInNormalProtocol;
    }

    @Override
    public String getEncoding() {
        return "RDP";
    }

    @Override
    public void writePointerEvent(int x, int y, int metaState, int pointerMask, boolean rel) {
        this.metaState = metaState;

//        if ((pointerMask & RemotePointer.POINTER_DOWN_MASK) != 0) {
//            sendModifierKeys(true);
//        }

        synchronized (viewable) {
            LibFreeRDP.sendCursorEvent(session.getInstance(), x, y, pointerMask);
        }

//        if ((pointerMask & RemotePointer.POINTER_DOWN_MASK) == 0) {
//            sendModifierKeys(false);
//        }
    }

    @Override
    public void writeKeyEvent(int key, int metaState, boolean down) {
        // Not used for actually sending keyboard events, but rather to record the current metastate.
        // The key event is sent to the KeyboardMapper from RemoteRdpKeyboard, and
        // when processed through the keyboard mapper, it ends up in one of the KeyProcessingListener
        // methods defined here.
        GeneralUtils.debugLog(this.debugLogging, TAG, "writeKeyEvent: setting metaState to: " + metaState);
        this.metaState = metaState;
    }

    @Override
    public void writeSetPixelFormat(int bitsPerPixel, int depth,
                                    boolean bigEndian, boolean trueColour, int redMax, int greenMax,
                                    int blueMax, int redShift, int greenShift, int blueShift,
                                    boolean fGreyScale) {
        // NOT USED for RDP.
    }

    @Override
    public void writeFramebufferUpdateRequest(int x, int y, int w, int h,
                                              boolean b) {
        // NOT USED for RDP.
    }

    @Override
    public void close() {
        setIsInNormalProtocol(false);
        disconnectRequested = true;
        long instance = session.getInstance();
        if (instance != 0) {
            handler.postDelayed(() -> {
                LibFreeRDP.disconnect(instance);
                LibFreeRDP.freeInstance(instance);
            }, 500);
        }
    }

    @Override
    public void reconnect() {
        // 首先关闭当前连接，释放资源
        close();

        // 等待资源释放完成（添加适当延迟）
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            android.util.Log.e(TAG, "Interrupted while waiting for disconnect: " + e.getMessage());
            Thread.currentThread().interrupt();
        }

        // 然后重新初始化会话并连接
        initSession(username, domain, password);
        connect();
    }

    @Override
    public boolean isCertificateAccepted() {
        return certificateAccepted;
    }

    @Override
    public void setCertificateAccepted(boolean certificateAccepted) {
        this.certificateAccepted = certificateAccepted;
    }

    private void sendModifierKeys(boolean down) {
        for (int modifierMask : modifierMap.keySet()) {
            if (remoteKeyboardState.shouldSendModifier(metaState, modifierMask, down)) {
                int modifier = modifierMap.get(modifierMask);
                GeneralUtils.debugLog(this.debugLogging, TAG, "sendModifierKeys, modifierMask:" +
                        modifierMask + ", sending: " + modifier + ", down: " + down);

                synchronized (viewable) {
                    LibFreeRDP.sendKeyEvent(session.getInstance(), modifier, down);
                }

                remoteKeyboardState.updateRemoteMetaState(modifierMask, down);
            }
        }
    }

    // ****************************************************************************
    // KeyboardMapper.KeyProcessingListener implementation
    @Override
    public void processVirtualKey(int virtualKeyCode, boolean down) {
//        GeneralUtils.debugLog(this.debugLogging, TAG, "processVirtualKey: " +
//                "Processing VK key: " + virtualKeyCode + ". Is it down: " + down);

        synchronized (viewable) {
            if (down) {
                sendModifierKeys(true);
            }

//        GeneralUtils.debugLog(this.debugLogging, TAG, "processVirtualKey: " +
//                "Sending VK key: " + virtualKeyCode + ". Is it down: " + down);


            synchronized (viewable) {
                LibFreeRDP.sendKeyEvent(session.getInstance(), virtualKeyCode, down);
            }

            if (!down) {
                sendModifierKeys(false);
            }
        }
    }

    @Override
    public void processUnicodeKey(int unicodeKey, boolean down, boolean suppressMetaState) {
//        GeneralUtils.debugLog(this.debugLogging, TAG, "processUnicodeKey: " +
//                "Processing unicode key: " + unicodeKey + ", down: " + down +
//                ", metaState: " + metaState + ", suppressMetaState: " + suppressMetaState);


        if (down && !suppressMetaState) {
            sendModifierKeys(true);
        }

//        GeneralUtils.debugLog(this.debugLogging, TAG, "processUnicodeKey: " +
//                "Sending unicode key: " + unicodeKey + ", down: " + down + ", metaState: " + metaState);
        synchronized (viewable) {
            LibFreeRDP.sendUnicodeKeyEvent(session.getInstance(), unicodeKey, down);
        }

        if (!down && !suppressMetaState) {
            sendModifierKeys(false);
        }
    }

    @Override
    public void switchKeyboard(int keyboardType) {
        // This is functionality specific to aFreeRDP.
    }

    @Override
    public void modifiersChanged() {
        // This is functionality specific to aFreeRDP.
    }

    @Override
    public void requestResolution(int x, int y) {
        // TODO Auto-generated method stub
    }

    private void initSession(String username, String domain, String password) {
        bookmark.setUsername(username);
        bookmark.setDomain(domain);
        bookmark.setPassword(password);
        session = GlobalApp.createSession(bookmark, context);
        session.setUIEventListener(this);
        LibFreeRDP.setEventListener(this);
    }

    public void setConnectionParameters(String address, int rdpPort, String nickname, int remoteWidth,
                                        int remoteHeight, boolean wallpaper, boolean fontSmoothing,
                                        boolean desktopComposition, boolean fullWindowDrag,
                                        boolean menuAnimations, boolean theming, boolean redirectSdCard,
                                        boolean consoleMode, int redirectSound, boolean enableRecording,
                                        boolean enableRemoteFx, boolean enableGfx, boolean enableGfxH264,
                                        int colors, int zoomLevel) {
        // Set a writable data directory
        //LibFreeRDP.setDataDirectory(session.getInstance(), getContext().getFilesDir().toString());
        // Get the address and port (based on whether an SSH tunnel is being established or not).
        bookmark.<ManualBookmark>get().setLabel(nickname);
        bookmark.<ManualBookmark>get().setHostname(addBracketsToIpv6Address(address));
        bookmark.<ManualBookmark>get().setPort(rdpPort);

        BookmarkBase.DebugSettings debugSettings = bookmark.getDebugSettings();
        debugSettings.setDebugLevel("ERROR");
        //debugSettings.setAsyncUpdate(false);
        //debugSettings.setAsyncInput(false);
        //debugSettings.setAsyncChannel(false);

        // Set screen settings to native res if instructed to, or if height or width are too small.
        BookmarkBase.ScreenSettings screenSettings = bookmark.getActiveScreenSettings();
        screenSettings.setWidth(remoteWidth);
        screenSettings.setHeight(remoteHeight);
        screenSettings.setColors(colors);
        screenSettings.setZoomLevel(zoomLevel);

        // Set performance flags.
        BookmarkBase.PerformanceFlags performanceFlags = bookmark.getPerformanceFlags();
        performanceFlags.setRemoteFX(enableRemoteFx);
        performanceFlags.setWallpaper(wallpaper);
        performanceFlags.setFontSmoothing(fontSmoothing);
        performanceFlags.setDesktopComposition(desktopComposition);
        performanceFlags.setFullWindowDrag(fullWindowDrag);
        performanceFlags.setMenuAnimations(menuAnimations);
        performanceFlags.setTheming(theming);
        performanceFlags.setGfx(enableGfx);
        performanceFlags.setH264(enableGfxH264);

        BookmarkBase.AdvancedSettings advancedSettings = bookmark.getAdvancedSettings();
        advancedSettings.setRedirectSDCard(redirectSdCard);
        advancedSettings.setConsoleMode(consoleMode);
        advancedSettings.setRedirectSound(redirectSound);
        advancedSettings.setRedirectMicrophone(enableRecording);
        advancedSettings.setSecurity(0); // Automatic negotiation
    }

    /**
     * Adds brackets around a valid IPv6 address that does not already start with [.
     *
     * @param address
     * @return
     */
    private String addBracketsToIpv6Address(String address) {
        InetAddressValidator validator = InetAddressValidator.getInstance();
        if (validator.isValidInet6Address(address) && !address.startsWith("[")) {
            address = "[" + address + "]";
        }
        return address;
    }

    public void connect() {
        session.connect(context);
    }

    @Override
    public void OnPreConnect(long instance) {
        Log.v(TAG, "OnPreConnect");
    }

    //////////////////////////////////////////////////////////////////////////////////
    //  Implementation of LibFreeRDP.EventListener.  Through the functions implemented
    //  below, FreeRDP communicates connection state information.
    //////////////////////////////////////////////////////////////////////////////////

    @Override
    public void OnConnectionSuccess(long instance) {
        Log.v(TAG, "OnConnectionSuccess");
        reattemptWithoutCredentials = false;
        authenticationAttempted = false;
        myself.setIsInNormalProtocol(true);

        viewable.onConnectionSuccess();
    }

    @Override
    public void OnConnectionFailure(long instance) {
        Log.v(TAG, "OnConnectionFailure");
        myself.setIsInNormalProtocol(false);
    }

    @Override
    public void OnDisconnecting(long instance) {
        Log.v(TAG, "OnDisconnecting, reattemptWithoutCredentials: " + reattemptWithoutCredentials +
                ", authenticationAttempted: " + authenticationAttempted +
                ", disconnectRequested: " + disconnectRequested +
                ", isInNormalProtocol: " + myself.isInNormalProtocol());
        if (reattemptWithoutCredentials && !myself.isInNormalProtocol()) {
            reattemptWithoutCredentials = false;
            // It could be bad credentials that caused the disconnection, so trying to connect
            // once again with no credentials before reporting the type of failure.
            initSession("", "", "");
            connect();
        } else if (authenticationAttempted && !myself.isInNormalProtocol()) {
            Log.v(TAG, "Sending message: RDP_AUTH_FAILED");
            handler.sendEmptyMessage(RemoteClientLibConstants.GET_RDP_CREDENTIALS);
        } else if (!disconnectRequested && !myself.isInNormalProtocol()) {
            Log.v(TAG, "Sending message: RDP_UNABLE_TO_CONNECT");
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_UNABLE_TO_CONNECT);
        } else if (!disconnectRequested) {
            myself.setIsInNormalProtocol(false);
//            initSession(username, domain, password);
//            connect();
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_CONNECT_FAILURE);
        }
    }

    @Override
    public void OnDisconnected(long instance) {
        Log.v(TAG, "OnDisconnected");
        if (!myself.isInNormalProtocol()) {
            Log.v(TAG, "Sending message: RDP_UNABLE_TO_CONNECT");
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_UNABLE_TO_CONNECT);
        } else {
            Log.v(TAG, "Sending message: RDP_CONNECT_FAILURE");
            handler.sendEmptyMessage(RemoteClientLibConstants.RDP_CONNECT_FAILURE);
        }
    }

    @Override
    public void OnPointerNew(byte[] pdata, int width, int height, int xPos, int yPos) {
        // Reconstruct bitmap headers
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int offset = 0;
        for (int h=0; h<height; h++) {
            for (int w=0; w<width; w++) {
                int color = 0;

                //set the color from ARGB byte sequence
                color = (pdata[offset] & 0xFF) << 24 | color;
                color = (pdata[offset + 1] & 0xFF) << 16 | color;
                color = (pdata[offset + 2] & 0xFF) << 8 | color;
                color = (pdata[offset + 3] & 0xFF) | color;

                offset += 4;

                bitmap.setPixel(w, h, color);
            }
        }

        viewable.setSoftCursorBitmap(bitmap, width, height, xPos, yPos);
    }

    @Override
    public void OnSettingsChanged(int width, int height, int bpp) {
        android.util.Log.d(TAG, "OnSettingsChanged called, wxh: " + width + "x" + height);
        viewable.reallocateDrawable(width, height);
    }

    //////////////////////////////////////////////////////////////////////////////////
    //  Implementation of LibFreeRDP.UIEventListener. Through the functions implemented
    //  below libspice and FreeRDP communicate remote desktop size and updates.
    //////////////////////////////////////////////////////////////////////////////////

    @Override
    public boolean OnAuthenticate(StringBuilder username, StringBuilder domain, StringBuilder password) {
        android.util.Log.d(TAG, "OnAuthenticate called.");
        authenticationAttempted = true;

        username.setLength(0);
        domain.setLength(0);
        password.setLength(0);

        username.append(this.username);
        domain.append(this.domain);
        password.append(this.password);

        return true;
    }

    @Override
    public int OnVerifiyCertificate(String commonName, String subject,
                                    String issuer, String fingerprint, boolean mismatch) {
        android.util.Log.d(TAG, "OnVerifiyCertificate called.");

        // Send a message containing the certificate to our handler.
        Message m = new Message();
        m.setTarget(handler);
        m.what = RemoteClientLibConstants.DIALOG_RDP_CERT;
        Bundle strings = new Bundle();
        strings.putString("subject", subject);
        strings.putString("issuer", issuer);
        strings.putString("fingerprint", fingerprint);
        m.obj = strings;
        handler.sendMessage(m);

        // Block while user decides whether to accept certificate or not.
        // The activity ends if the user taps "No", so we block indefinitely here.
        synchronized (this) {
            while (!certificateAccepted) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        return 1;
    }

    @Override
    public boolean OnGatewayAuthenticate(StringBuilder username,
                                         StringBuilder domain, StringBuilder password) {
        android.util.Log.d(TAG, "OnGatewayAuthenticate called.");
        return this.OnAuthenticate(username, domain, password);
    }

    @Override
    public int OnVerifyChangedCertificate(String commonName, String subject,
                                          String issuer, String fingerprint, String oldSubject,
                                          String oldIssuer, String oldFingerprint) {
        android.util.Log.d(TAG, "OnVerifyChangedCertificate called.");
        return this.OnVerifiyCertificate(commonName, subject, issuer, fingerprint, true);
    }

    @Override
    public void OnGraphicsUpdate(int x, int y, int width, int height) {
        //android.util.Log.v(TAG, "OnGraphicsUpdate called: " + x +", " + y + " + " + width + "x" + height );
        if (viewable == null || session == null) {
            return;
        }

        Bitmap bitmap = viewable.getBitmap();
        if (bitmap == null) {
            return;
        }

        DrawTask drawTask = new DrawTask(x, y, width, height, true);

        LibFreeRDP.updateGraphics(session.getInstance(), bitmap
                , 0
                , 0
                , bitmap.getWidth()
                , bitmap.getHeight());

        viewable.reDraw(drawTask);
    }

    @Override
    public void OnGraphicsResize(int width, int height, int bpp) {
        android.util.Log.d(TAG, "OnGraphicsResize called.");
        OnSettingsChanged(width, height, bpp);
    }

    @Override
    public void OnRemoteClipboardChanged(String data) {
        android.util.Log.d(TAG, "OnRemoteClipboardChanged called.");
        remoteClipboardChanged(data);
    }
}

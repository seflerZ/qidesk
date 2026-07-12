/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * Copyright (C) 2004 Horizon Wimba.  All Rights Reserved.
 * Copyright (C) 2001-2003 HorizonLive.com, Inc.  All Rights Reserved.
 * Copyright (C) 2001,2002 Constantin Kaplinsky.  All Rights Reserved.
 * Copyright (C) 2000 Tridia Corporation.  All Rights Reserved.
 * Copyright (C) 1999 AT&T Laboratories Cambridge.  All Rights Reserved.
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

//
// RemoteCanvas is a subclass of android.view.SurfaceView which draws a VNC
// desktop on it.
//

package com.qihua.bVNC;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.text.ClipboardManager;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.limelight.binding.input.ControllerHandler;
import com.qihua.android.bc.BCFactory;
import com.qihua.bVNC.communicator.RfbCommunicator;
import com.qihua.bVNC.connection.ConnectionInitializer;
import com.qihua.bVNC.connection.ConnectionInitializerFactory;
import com.qihua.bVNC.connection.ProtocolType;
import com.qihua.bVNC.connection.SpiceConnectionInitializer;
import com.qihua.bVNC.draw.DrawWorker;
import com.qihua.bVNC.dialogs.GetTextFragment;
import com.qihua.bVNC.input.InputHandler;
import com.qihua.bVNC.input.InputHandlerTouchpad;
import com.qihua.bVNC.input.RemoteCanvasHandler;
import com.qihua.bVNC.ssh.SSHConnection;
import com.qihua.bVNC.input.RemoteKeyboard;
import com.qihua.bVNC.input.RemotePointer;
import com.qihua.bVNC.input.RemoteSshKeyboard;
import com.qihua.bVNC.util.SmartResolutionUtils;
import com.undatech.opaque.Connection;
import com.undatech.opaque.DrawTask;
import com.undatech.opaque.MessageDialogs;
import com.undatech.opaque.NvCommunicator;
import com.undatech.opaque.RdpCommunicator;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.opaque.RemoteConnectable;
import com.undatech.opaque.SpiceCommunicator;
import com.undatech.opaque.Viewable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;

public class RemoteCanvas extends SurfaceView implements Viewable
        , SurfaceHolder.Callback, GetTextFragment.OnFragmentDismissedListener {
    private final static String TAG = "RemoteCanvas";

    public SurfaceHolder surfaceHolder;

    public AbstractScaling scaler;

    // Variable indicating that we are currently scrolling in simulated touchpad mode.
    public boolean cursorBeingMoved = false;

    public Rect displayRect;
    public float displayDensity;

    // Connection parameters
    public Connection connection;
    public SSHConnection sshConnection = null;

    // The communicators for different protocols
    public RemoteConnectable rfbconn = null;
    public RfbCommunicator rfb = null;
    public SpiceCommunicator spicecomm = null;
    public RdpCommunicator rdpcomm = null;
    public NvCommunicator nvcomm = null;

    public boolean maintainConnection = true;
    public AbstractBitmapData bitmapData;
    // Progress dialog shown at connection time.
    public AlertDialog progressDialog;
    public boolean serverJustCutText = false;
    public Runnable setModes;
    public Runnable hideKeyboardAndExtraKeys;
    public boolean spiceUpdateReceived = false;
    /**
     * Handler for the dialogs that display the x509/RDP/SSH key signatures to the user.
     * Also shows the dialogs which show various connection failures.
     */
    public Handler handler;

    /**
     * The ConnectionInitializer that owns protocol-specific lifecycle
     * for the current connection. Set in initializeCanvas(); null until
     * then. Per-protocol heartbeat, surface hooks, and teardown are
     * delegated to it; the rest of RemoteCanvas is protocol-agnostic.
     */
    public ConnectionInitializer connInitializer;

    private DrawWorker drawWorker;

    private InputHandler inputHandler;

    private boolean outDisplay = false;

    Database database;
    public Map<String, String> vmNameToId = new HashMap<String, String>();
    // RFB Decoder
    public Decoder decoder = null;
    // The remote pointer and keyboard
    public RemotePointer pointer;
    public RemoteKeyboard keyboard;
    public ControllerHandler controller;
    public boolean useFull = false;
    boolean compact = false;
    // Used to set the contents of the clipboard.
    ClipboardManager clipboard;
    Timer clipboardMonitorTimer;
    ClipboardMonitor clipboardMonitor;
    /*
     * Position of the top left portion of the <i>visible</i> part of the screen, in
     * full-frame coordinates
     */
    public int absoluteXPosition = 0, absoluteYPosition = 0;

    /*
     * How much to shift coordinates over when converting from full to view coordinates.
     */
    float shiftX = 0, shiftY = 0;

    /*
     * This variable holds the height of the visible rectangle of the screen. It is used to keep track
     * of how much of the screen is hidden by the soft keyboard if any.
     */
    int visibleHeight = -1;

    /*
     * Protocol identity. The boolean flags used to live here as
     * fields, but they were a four-way `isXxx = ...` ladder kept in
     * sync with the dispatch in initializeCanvas(). Now that each
     * protocol's lifecycle is owned by a ConnectionInitializer
     * strategy, the source of truth is `currentInitializer`; the
     * accessors below delegate to `instanceof`.
     */

    public boolean isRunning = false;

    public boolean sshTunneled = false;
    boolean userPanned = false;
    String vvFileName;
    /**
     * This runnable displays a message on the screen.
     */
    CharSequence screenMessage;
    /**
     * Shows a non-fatal error message.
     *
     * @param error
     */
    Runnable showDialogMessage = new Runnable() {
        public void run() {
            Utils.showErrorMessage(getContext(), String.valueOf(screenMessage));
        }
    };

    // Internal bitmap data
    private int capacity;
    public FpsCounter fpsCounter;

    private Runnable showMessage = new Runnable() {
        public void run() {
            Toast.makeText(getContext(), screenMessage, Toast.LENGTH_SHORT).show();
        }
    };
    /**
     * This runnable causes a toast with information about the current connection to be shown.
     */
    private Runnable desktopInfo = new Runnable() {
        public void run() {
            showConnectionInfo();
        }
    };

    private boolean touchpad = false;
    public RemoteCanvasActivity activity;

    /**
     * Constructor used by the inflation apparatus
     *
     * @param context
     */
    public RemoteCanvas(final Context context, AttributeSet attrs) {
        super(context, attrs);

        fpsCounter = new FpsCounter();

        // 👇 关键：让 MIUI 知道这个 View 是“有意图”接收触摸的
        setClickable(true);           // 必须
        setFocusable(true);           // 推荐
        setFocusableInTouchMode(true); // 推荐（尤其在嵌套场景）

        clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);

        if (!isTouchpad()) {
            drawWorker = new DrawWorker(this);
        }

        surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);

        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.connection_progress, null);

        // we do not initialte the progress dialog if we are in external canvas
        // we will accept it from the touchpad canvas
        if (!outDisplay) {
            // 配置ProgressBar和TextView
            ProgressBar progressBar = dialogView.findViewById(R.id.progressBar);
            TextView messageView = dialogView.findViewById(R.id.message);
            messageView.setText(R.string.info_progress_dialog_establishing);

            progressDialog = new AlertDialog.Builder(getContext())
                    .setTitle(R.string.info_progress_dialog_connecting)
                    .setView(dialogView)
                    .setCancelable(true)
                    .setOnCancelListener(dialog -> {
                        handler.post(() ->
                                Utils.showFatalErrorMessage(getContext(),
                                        getContext().getString(R.string.info_progress_dialog_aborted)));
                    })
                    .create();

            progressDialog.setCanceledOnTouchOutside(false);
        }
    }

    public void startPointerCapture() {
        requestPointerCapture();
        requestFocus();
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        return inputHandler.onPointerEvent(event);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (!outDisplay && touchpad) {
            drawTouchpadHint();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (!outDisplay && touchpad) {
            drawTouchpadHint(width, height);
        }
    }

    /**
     * Catch {@code ACTION_MULTIPLE} events at the view level.
     *
     * The activity uses {@code View.OnKeyListener} to forward key events to
     * the input handler, but {@code OnKeyListener.onKey} is only invoked
     * for {@code ACTION_DOWN}. For {@code ACTION_MULTIPLE} (the IME's
     * "here's a chunk of unicode text" flavor on some IMEs) the default
     * {@code View.onKeyMultiple} returns {@code false} and the event
     * bubbles up the tree unhandled — it never reaches
     * {@link RemoteSshKeyboard#processLocalKeyEvent}.
     *
     * For SSH we forward {@code ACTION_MULTIPLE} directly to the keyboard
     * so the existing {@code processLocalKeyEvent} ACTION_MULTIPLE branch
     * can run. Other protocols ignore this and fall through to the default
     * behavior.
     */
    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE
                && keyboard != null
                && getProtocolType() == ProtocolType.SSH) {
            return keyboard.keyEvent(keyCode, event);
        }
        return super.onKeyMultiple(keyCode, repeatCount, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        isRunning = false;
    }

    void init(final Connection settings, final Handler handler, final Runnable setModes, final Runnable hideKeyboardAndExtraKeys, final String vvFileName) {
        this.connection = settings;
        this.handler = handler;
        this.setModes = setModes;
        this.hideKeyboardAndExtraKeys = hideKeyboardAndExtraKeys;
        this.vvFileName = vvFileName;

        checkNetworkConnectivity();
        initializeClipboardMonitor();

        // SPICE / Opaque lifecycle now lives in SpiceConnectionInitializer.
        // init() is only ever called from the Opaque flavor, so the
        // factory always returns SpiceConnectionInitializer here.
        connInitializer = ConnectionInitializerFactory.create(settings, getContext());
        SpiceConnectionInitializer spice = (SpiceConnectionInitializer) connInitializer;
        spice.vvFileName = vvFileName;
        try {
            spice.initialize(this);
            spice.start(this);
        } catch (Throwable e) {
            handleUncaughtException(e);
        }
    }

    /**
     * Checks whether the device has networking and quits with an error if it doesn't.
     */
    private void checkNetworkConnectivity() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isAvailable() || !activeNetwork.isConnected()) {
            disconnectAndShowMessage(R.string.error_not_connected_to_network, R.string.error_dialog_title);
        }
    }

    public void disconnectAndShowMessage(final int messageId, final int titleId) {
        closeConnection();
        handler.post(new Runnable() {
            public void run() {
                MessageDialogs.displayMessageAndFinish(getContext(), messageId, titleId);
            }
        });
    }

    public void disconnectAndShowMessage(final int messageId, final int titleId, final String textToAppend) {
        closeConnection();
        handler.post(new Runnable() {
            public void run() {
                MessageDialogs.displayMessageAndFinish(getContext(), messageId, titleId, textToAppend);
            }
        });
    }

    public void disconnectWithoutMessage() {
        closeConnection();
//        Utils.justFinish(getContext());
    }

    public void saveZoomFactor(float zoomFactor) {
        connection.setLastZoomFactor(zoomFactor);
        connection.saveAndWriteRecent(false, getContext());
    }

    /**
     * Initializes the clipboard monitor.
     */
    private void initializeClipboardMonitor() {
        clipboardMonitor = new ClipboardMonitor(getContext(), this);
        if (clipboardMonitor != null) {
            clipboardMonitorTimer = new Timer();
            if (clipboardMonitorTimer != null) {
                clipboardMonitorTimer.schedule(clipboardMonitor, 0, 500);
            }
        }
    }

    /**
     * Reinitialize Canvas
     */
    public void reinitializeCanvas() {
        Log.i(TAG, "Reinitializing remote canvas");
        initializeCanvas(this.connection, this.setModes, this.hideKeyboardAndExtraKeys);
        handler.post(this.hideKeyboardAndExtraKeys);
    }

    /**
     * Reinitialize Opaque
     */
    public void reinitializeOpaque() {
        Log.i(TAG, "Reinitializing remote canvas opaque");
        init(this.connection, this.handler, this.setModes, this.hideKeyboardAndExtraKeys, this.vvFileName);
        handler.post(this.hideKeyboardAndExtraKeys);
    }

    public void showProgessDialog() {
        progressDialog.show();
    }

    public void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    /**
     * Create a view showing a remote desktop connection
     *
     * @param conn     Connection settings
     * @param setModes Callback to run on UI thread after connection is set up
     */
    public RemotePointer initializeCanvas(Connection conn, final Runnable setModes, final Runnable hideKeyboardAndExtraKeys) {
        maintainConnection = true;
        this.setModes = setModes;
        this.hideKeyboardAndExtraKeys = hideKeyboardAndExtraKeys;
        connection = conn;
        sshTunneled = (connection.getConnectionType() == Constants.CONN_TYPE_SSH);
        handler = new RemoteCanvasHandler(getContext(), this, connection);

        // Per-protocol lifecycle now lives in ConnectionInitializer strategies.
        // The factory picks one based on the connection type (and SPICE
        // app flavor). Protocol identity is exposed via the isXxx()
        // accessors below, which delegate to `currentInitializer`.
        connInitializer = ConnectionInitializerFactory.create(conn, getContext());
        if (connInitializer == null) {
            // No strategy for this connection. The non-Opaque entry
            // points (VNC/RDP/NVStream/SSH) all have their own
            // initializers; reaching this branch means the factory
            // was unable to recognise the connection.
            try {
                throw new Exception("unknown connection type");
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        } else {
            try {
                connInitializer.initialize(this);
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        }

        clipboardMonitor = new ClipboardMonitor(getContext(), this);
        clipboardMonitorTimer = new Timer();
        try {
            clipboardMonitorTimer.schedule(clipboardMonitor, 0, 500);
        } catch (NullPointerException ignored) {
        }

        return pointer;
    }

    public void startConnection() {
        if (connInitializer == null) {
            // No strategy matched. With every protocol's initializer in
            // place the factory always returns one; this branch is a
            // defensive net for unknown connection types.
            try {
                throw new Exception("unknown connection type");
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        } else {
            try {
                connInitializer.start(this);
            } catch (Throwable e) {
                handleUncaughtException(e);
            }
        }
    }

    public void handleUncaughtException(Throwable e) {
        if (maintainConnection) {
            Log.e(TAG, e.toString());
//            e.printStackTrace();
            // Ensure we dismiss the progress dialog before we finish
            if (progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            if (e instanceof OutOfMemoryError) {
                disposeDrawable();
                showFatalMessageAndQuit(getContext().getString(R.string.error_out_of_memory));
            } else {
                String error = getContext().getString(R.string.error_connection_failed);
                if (e.getMessage() != null) {
                    if (e.getMessage().indexOf("SSH") < 0 &&
                            (e.getMessage().indexOf("authentication") > -1 ||
                                    e.getMessage().indexOf("Unknown security result") > -1 ||
                                    e.getMessage().indexOf("password check failed") > -1)
                    ) {
                        error = getContext().getString(R.string.error_vnc_authentication);
                    }
                    error = error + "<br>" + e.getLocalizedMessage();
                }
                showFatalMessageAndQuit(error);
            }
        }
    }

    /**
     * Retreives the requested remote width.
     */
    @Override
    public int getDesiredWidth() {
        int w = getRemoteWidth(displayRect.width(), displayRect.height());
        if (!connection.isRequestingNewDisplayResolution() &&
                connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM) {
            w = connection.getRdpWidth();
        }
        Log.d(TAG, "Width requested: " + w);
        return w;
    }

    /**
     * Retreives the requested remote height.
     */
    @Override
    public int getDesiredHeight() {
        int h = getRemoteHeight(displayRect.width(), displayRect.height());
        if (!connection.isRequestingNewDisplayResolution() &&
                connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM) {
            h = connection.getRdpHeight();
        }
        Log.d(TAG, "Height requested: " + h);
        return h;
    }


    /**
     * Retreives the requested remote width.
     */
    public int getRemoteWidth(int viewWidth, int viewHeight) {
        int remoteWidth = 0;
        int reqWidth = connection.getRdpWidth();
        int reqHeight = connection.getRdpHeight();
        if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM &&
                reqWidth >= 2 && reqHeight >= 2) {
            remoteWidth = reqWidth;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_NATIVE) {
            remoteWidth = viewWidth;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_FULL_HD) {
            remoteWidth = 1920;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_2K) {
            remoteWidth = 2560;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_SMART) {
            // 在外接显示器模式下，使用外接显示器的分辨率
            int smartWidth = calculateSmartResolutionWidth();
            remoteWidth = smartWidth;
        } else {
            remoteWidth = 1920;
        }

        return remoteWidth;
    }

    /**
     * Retreives the requested remote height.
     */
    public int getRemoteHeight(int viewWidth, int viewHeight) {
        int remoteHeight = 0;
        int reqWidth = connection.getRdpWidth();
        int reqHeight = connection.getRdpHeight();
        if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM &&
                reqWidth >= 2 && reqHeight >= 2) {
            remoteHeight = reqHeight;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_NATIVE) {
            remoteHeight = viewHeight;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_FULL_HD) {
            remoteHeight = 1080;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_2K) {
            remoteHeight = 1440;
        } else if (connection.getRdpResType() == Constants.RDP_GEOM_SELECT_SMART) {
            // 在外接显示器模式下，使用外接显示器的分辨率
            int smartHeight = calculateSmartResolutionHeight();
            remoteHeight = smartHeight;
        } else {
            remoteHeight = 1080;
        }

        return remoteHeight;
    }

    /**
     * 计算智能分辨率的宽度
     * 在外接显示器模式下，使用外接显示器的分辨率
     */
    private int calculateSmartResolutionWidth() {
        if (outDisplay) {
            // 外接显示器模式：使用displayRect的尺寸
            return SmartResolutionUtils.calculateSmartResolution(displayRect.width(), displayRect.height())[0];
        } else {
            return SmartResolutionUtils.calculateSmartResolution(getContext())[0];
        }
    }

    /**
     * 计算智能分辨率的高度
     * 在外接显示器模式下，使用外接显示器的分辨率
     */
    private int calculateSmartResolutionHeight() {
        if (outDisplay) {
            // 外接显示器模式：使用displayRect的尺寸
            return SmartResolutionUtils.calculateSmartResolution(displayRect.width(), displayRect.height())[1];
        } else {
            return SmartResolutionUtils.calculateSmartResolution(getContext())[1];
        }
    }

    void showMessage(final String error) {
        Log.d(TAG, "showMessage");
        screenMessage = error;
        handler.removeCallbacks(showDialogMessage);
        handler.post(showDialogMessage);
    }

    /**
     * Closes the connection and shows a fatal message which ends the activity.
     *
     * @param error
     */
    public void showFatalMessageAndQuit(final String error) {
        closeConnection();
        handler.post(new Runnable() {
            public void run() {
                Utils.showFatalErrorMessage(getContext(), error);
            }
        });
    }

    public void showToastAndReconnect(final String warn) {
        reConnect();
        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(getContext(), warn, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * If necessary, initializes an SSH tunnel and returns local forwarded port, or
     * if SSH tunneling is not needed, returns the given port.
     *
     * @return
     */
    public int getRemoteProtocolPort(int port) throws Exception {
        int result = 0;

        if (sshTunneled) {
            sshConnection = new SSHConnection(connection, getContext(), handler);
            int newPort = sshConnection.initializeSSHTunnel();
            if (newPort > 0)
                port = newPort;
            result = sshConnection.createLocalPortForward(port);
        } else {
            if (getProtocolType() == ProtocolType.VNC && port <= 20) {
                result = Constants.DEFAULT_VNC_PORT + port;
            } else {
                result = port;
            }
        }
        return result;
    }

    /**
     * Returns localhost if using SSH tunnel or saved VNC address.
     *
     * @return
     */
    public String getAddress() {
        if (sshTunneled) {
            return new String("127.0.0.1");
        } else
            return connection.getAddress();
    }

    /**
     * Initializes the drawable and bitmap into which the remote desktop is drawn.
     *
     * @param dx
     * @param dy
     * @throws IOException
     */
    @Override
    public void reallocateDrawable(int dx, int dy) {
        Log.i(TAG, "Desktop name is " + rfbconn.desktopName());
        Log.i(TAG, "Desktop size is " + rfbconn.framebufferWidth() + " x " + rfbconn.framebufferHeight());

        int fbsize = rfbconn.framebufferWidth() * rfbconn.framebufferHeight();

        capacity = BCFactory.getInstance().getBCActivityManager().getMemoryClass(Utils.getActivityManager(getContext()));

        if (connection.getForceFull() == BitmapImplHint.AUTO) {
            if (fbsize * CompactBitmapData.CAPACITY_MULTIPLIER <= capacity * 1024 * 1024) {
                useFull = true;
                compact = true;
            } else if (fbsize * FullBufferBitmapData.CAPACITY_MULTIPLIER <= capacity * 1024 * 1024) {
                useFull = true;
            } else {
                useFull = false;
            }
        } else {
            useFull = (connection.getForceFull() == BitmapImplHint.FULL);
        }

        ProtocolType protocol = getProtocolType();
        boolean isPushlessProtocol = protocol == ProtocolType.RDP
                || protocol == ProtocolType.NVSTREAM
                || protocol == ProtocolType.SSH;
        boolean isUltraCompactProtocol = isPushlessProtocol || protocol == ProtocolType.SPICE;
        if (isPushlessProtocol) {
            // SSH Phase 0 reuses UltraCompactBitmapData: its drawable
            // overrides Drawable.draw(Canvas), its constructor creates
            // mbitmap, and the SshConnectionInitializer.start path then
            // paints the hardcoded "Hello SSH" text into mbitmap once.
            bitmapData = new UltraCompactBitmapData(rfbconn, this, isUltraCompactProtocol);
            Log.i(TAG, "Using UltraCompactBufferBitmapData.");
        } else if (!useFull) {
            bitmapData = new LargeBitmapData(rfbconn, this, dx, dy, capacity);
            Log.i(TAG, "Using LargeBitmapData.");
        } else {
            try {
                // TODO: Remove this if Android 4.2 receives a fix for a bug which causes it to stop drawing
                // the bitmap in CompactBitmapData when under load (say playing a video over VNC).
                if (!compact) {
                    bitmapData = new FullBufferBitmapData(rfbconn, this, capacity);
                    Log.i(TAG, "Using FullBufferBitmapData.");
                } else {
                    bitmapData = new CompactBitmapData(rfbconn, this, protocol == ProtocolType.SPICE);
                    Log.i(TAG, "Using CompactBufferBitmapData.");
                }
            } catch (Throwable e) { // If despite our efforts we fail to allocate memory, use LBBM.
                disposeDrawable();

                useFull = false;
                bitmapData = new LargeBitmapData(rfbconn, this, dx, dy, capacity);
                Log.i(TAG, "Using LargeBitmapData.");
            }
        }

        try {
            if (needsLocalCursor()) {
                initializeSoftCursor();
            }

            handler.post(setModes);
//            myDrawable.syncScroll();
            if (decoder != null) {
                decoder.setBitmapData(bitmapData);
            }

            isRunning = true;
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
    }

    /**
     * Determines if the app should show a local cursor or not
     */
    private boolean needsLocalCursor() {
        ProtocolType protocol = getProtocolType();
        boolean isRdpSpiceOrOpaque = protocol == ProtocolType.RDP || protocol == ProtocolType.SPICE;
        boolean localCursorNotForceDisabled =
                connection.getUseLocalCursor() != Constants.CURSOR_FORCE_DISABLE;
        boolean localCursorForceEnabled =
                connection.getUseLocalCursor() == Constants.CURSOR_FORCE_LOCAL;
        return (isRdpSpiceOrOpaque && localCursorNotForceDisabled) || localCursorForceEnabled;
    }

    /**
     * Disposes of the old drawable which holds the remote desktop data.
     */
    private void disposeDrawable() {
        if (bitmapData != null)
            bitmapData.dispose();
        bitmapData = null;
//        System.gc();
    }

    /**
     * The remote desktop's size has changed and this method
     * reinitializes local data structures to match.
     */
    public void updateFBSize() {
        try {
            bitmapData.frameBufferSizeChanged();
        } catch (Throwable e) {
            boolean useLBBM = false;

            // If we've run out of memory, try using another bitmapdata type.
            if (e instanceof OutOfMemoryError) {
                disposeDrawable();

                // If we were using CompactBitmapData, try FullBufferBitmapData.
                if (compact == true) {
                    compact = false;
                    try {
                        bitmapData = new FullBufferBitmapData(rfbconn, this, capacity);
                    } catch (Throwable e2) {
                        useLBBM = true;
                    }
                } else
                    useLBBM = true;

                // Failing FullBufferBitmapData or if we weren't using CompactBitmapData, try LBBM.
                if (useLBBM) {
                    disposeDrawable();

                    useFull = false;
                    bitmapData = new LargeBitmapData(rfbconn, this, displayRect.width(), displayRect.height(), capacity);
                }
                if (decoder != null) {
                    decoder.setBitmapData(bitmapData);
                }
            }
        }

        handler.post(setModes);
        bitmapData.syncScroll();
    }

    /**
     * Displays a short toast message on the screen.
     *
     * @param message
     */
    public void displayShortToastMessage(final CharSequence message) {
        screenMessage = message;
        handler.removeCallbacks(showMessage);
        handler.post(showMessage);
    }

    /**
     * Displays a short toast message on the screen.
     *
     * @param messageID
     */
    public void displayShortToastMessage(final int messageID) {
        screenMessage = getResources().getText(messageID);
        handler.removeCallbacks(showMessage);
        handler.post(showMessage);
    }

    /**
     * Lets the drawable know that an update from the remote server has arrived.
     */
    public void doneWaiting() {
        bitmapData.doneWaiting();
    }

    /**
     * Indicates that RemoteCanvas's scroll position should be synchronized with the
     * drawable's scroll position (used only in LargeBitmapData)
     */
    public void syncScroll() {
        bitmapData.syncScroll();
    }

    /*
     * f(x,s) is a function that returns the coordinate in screen/scroll space corresponding
     * to the coordinate x in full-frame space with scaling s.
     *
     * This function returns the difference between f(x,s1) and f(x,s2)
     *
     * f(x,s) = (x - i/2) * s + ((i - w)/2)) * s
     *        = s (x - i/2 + i/2 + w/2)
     *        = s (x + w/2)
     *
     *
     * f(x,s) = (x - ((i - w)/2)) * s
     * @param oldscaling
     * @param scaling
     * @param imageDim
     * @param windowDim
     * @param offset
     * @return
     */

    /**
     * Requests a remote desktop update at the specified rectangle.
     */
    public void writeFramebufferUpdateRequest(int x, int y, int w, int h, boolean incremental) throws IOException {
        bitmapData.prepareFullUpdateRequest(incremental);
        rfbconn.writeFramebufferUpdateRequest(x, y, w, h, incremental);
    }

    /**
     * Requests an update of the entire remote desktop.
     */
    public void writeFullUpdateRequest(boolean incremental) {
        bitmapData.prepareFullUpdateRequest(incremental);
        rfbconn.writeFramebufferUpdateRequest(bitmapData.getXoffset(), bitmapData.getYoffset(),
                bitmapData.bmWidth(), bitmapData.bmHeight(), incremental);
    }

    /**
     * Set the device clipboard text with the string parameter.
     */
    public void setClipboardText(String s) {
        if (s != null && s.length() > 0) {
            clipboard.setText(s);
        }
    }

    /**
     * Method that disconnects from the remote server.
     */
    public void closeConnection() {
        maintainConnection = false;

        if (keyboard != null) {
            // Tell the server to release any meta keys.
            keyboard.clearMetaState();
            keyboard.keyEvent(0, new KeyEvent(KeyEvent.ACTION_UP, 0));
        }

        // Close the rfb connection.
        if (rfbconn != null) {
            rfbconn.close();
        }

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // Close the SSH tunnel.
        if (sshConnection != null) {
            sshConnection.terminateSSHTunnel();
            sshConnection = null;
        }

        if (connection != null) {
            Log.d(TAG, "Saving screenshot to " + getContext().getFilesDir() + "/" + connection.getScreenshotFilename());
            Utils.writeScreenshotToFile(bitmapData, getContext().getFilesDir() + "/" + connection.getScreenshotFilename(), 720);
        }

//        disposeDrawable();
        onDestroy();
    }

    public void reConnect() {
        rfbconn.reconnect();
    }

    /**
     * Cleans up resources after a disconnection.
     */
    public void onDestroy() {
        Log.v(TAG, "Cleaning up resources");

        removeCallbacksAndMessages();
        if (drawWorker != null) {
            drawWorker.stop();
            drawWorker = null;
        }
        if (clipboardMonitorTimer != null) {
            clipboardMonitorTimer.cancel();
            // Occasionally causes a NullPointerException
            //clipboardMonitorTimer.purge();
            clipboardMonitorTimer = null;
        }
        clipboardMonitor = null;
        clipboard = null;
        setModes = null;
        decoder = null;
        scaler = null;
        screenMessage = null;
        desktopInfo = null;

        disposeDrawable();
    }

    public void removeCallbacksAndMessages() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * Computes the X and Y offset for converting coordinates from full-frame coordinates to view coordinates.
     */
    public void computeShiftFromFullToView() {
        shiftX = (rfbconn.framebufferWidth() - displayRect.width()) / 2;
        shiftY = (rfbconn.framebufferHeight() - displayRect.height()) / 2;
    }

    /**
     * Change to Canvas's scroll position to match the absoluteXPosition
     */
    void resetScroll() {
//        float scale = getZoomFactor();
        //android.util.Log.d(TAG, "resetScroll: " + (absoluteXPosition - shiftX) * scale + ", "
        //                                        + (absoluteYPosition - shiftY) * scale);

        reDraw(0, 0, getWidth(), getHeight());
//        scrollTo((int) ((absoluteXPosition) * scale),
//                (int) ((absoluteYPosition) * scale));
    }

    /**
     * Make sure mouse is visible on displayable part of screen
     */
    public boolean movePanToMakePointerVisible() {
        //Log.d(TAG, "movePanToMakePointerVisible");
        if (rfbconn == null)
            return false;

        // Do not pan when not scaled.
//        if (getZoomFactor() == getMinimumScale()) {
//            return;
//        }

        boolean panX = true;
        boolean panY = true;

        boolean panned = false;

        // We only pan if the current scaling is able to pan.
        if (scaler != null && !scaler.isAbleToPan())
            return false;

        // Coordinates in screen's resolution
        int x = (int) (pointer.getX());
        int y = (int) (pointer.getY());
        int wthresh = (int) (Constants.H_THRESH / getZoomFactor());
        int hthresh = (int) (Constants.W_THRESH / getZoomFactor());

        // Coordinates in picture's resolution
        int w = getVisibleDesktopWidth();
        int h = getVisibleDesktopHeight();
        int iw = getImageWidth();
        int ih = getImageHeight();

        // bWidth = black border width
        int bWidth = getBlackBorderWidth();

        // This is definitely 0 because we have the image top aligned
        int bHeight = 0;

        // newX and newY are in screen's resolution
        int newX = absoluteXPosition;
        int newY = absoluteYPosition;

        if ((x + bWidth > w + absoluteXPosition - wthresh) && (absoluteXPosition + w < bWidth + iw)) {
            newX += (x - (w + absoluteXPosition - wthresh - bWidth));

            // left padding x + image_width visible is larger than left black border + image_width
            // which means the right side has been reached, no more space to do right panning now
            if (newX + w > iw + bWidth) {
                newX = iw - w + bWidth;
            }
        } else if ((x + bWidth < absoluteXPosition + wthresh) && (x > 0)) {
            newX += (x - (absoluteXPosition + wthresh - bWidth));
            if (newX < bWidth) {
                newX = bWidth;
            }
        }

        if (panX && newX != absoluteXPosition) {
            absoluteXPosition = newX;
            panned = true;
        }

        // when the soft keyboard shown, the bottom space decreased, we must
        // cut off the height to make the bottom edge pan work properly.
        float keyboardHeight = activity.getKeyboardHeightInVisibleImage();

        // do not pan when there is space left on the screen
        if ((y + bHeight > h + absoluteYPosition - hthresh - keyboardHeight) && (absoluteYPosition + h < bHeight + ih + keyboardHeight)) {
            newY += ((y) - (h + absoluteYPosition - hthresh - keyboardHeight) - bHeight);
            if (newY + h > ih + bHeight + keyboardHeight) {
                newY = (int) (ih + bHeight + keyboardHeight - h);
            }
        } else if ((y + bHeight < absoluteYPosition + hthresh) && y > 0) {
            newY += (y - (absoluteYPosition + hthresh - bHeight));
            if (newY < bHeight) {
                newY = bHeight;
            }
        }

        if (panY && newY != absoluteYPosition) {
            absoluteYPosition = newY;
            panned = true;
        }

        if (panned) {
            //scrollBy(newX - absoluteXPosition, newY - absoluteYPosition);
            resetScroll();
        }

        return panned;
    }

    public int getTopMargin(double scale) {
        return (int) (Constants.TOP_MARGIN / scale);
    }

    public int getBottomMargin(double scale) {
        return (int) (Constants.BOTTOM_MARGIN / scale);
    }

    /**
     * Pan by a number of pixels (relative pan)
     *
     * @param dX
     * @param dY
     * @return True if the pan changed the view (did not move view out of bounds); false otherwise
     */
    public boolean relativePan(float dX, float dY) {
        Log.d(TAG, "relativePan: " + dX + ", " + dY);

        // We only pan if the current scaling is able to pan.
        if (scaler != null && !scaler.isAbleToPan())
            return false;

//        double sX = dX;
//        double sY = dY;

        // Prevent panning right or below desktop image except for provision for on-screen
        // buttons and curved screens
//        if (absoluteXPosition + getVisibleDesktopWidth() + sX > getImageWidth())
//            sX = getImageWidth() - getVisibleDesktopWidth() - absoluteXPosition;
//        if (absoluteYPosition + getVisibleDesktopHeight() + sY > getImageHeight())
//            sY = getImageHeight() - getVisibleDesktopHeight() - absoluteYPosition;

//        absoluteXPosition += sX;
//        absoluteYPosition += sY;
        if (dX != 0.0 || dY != 0.0) {
            absolutePan((int) (absoluteXPosition + dX), (int) (absoluteYPosition + dY), false);
            return true;
        }
        return false;
    }

    public boolean relativePan(float dX, float dY, boolean force) {
        android.util.Log.d(TAG, "relativePan: " + dX + ", " + dY);

        // We only pan if the current scaling is able to pan.
        if (scaler != null && !scaler.isAbleToPan())
            return false;

//        double sX = dX;
//        double sY = dY;

        // Prevent panning right or below desktop image except for provision for on-screen
        // buttons and curved screens
//        if (absoluteXPosition + getVisibleDesktopWidth() + sX > getImageWidth())
//            sX = getImageWidth() - getVisibleDesktopWidth() - absoluteXPosition;
//        if (absoluteYPosition + getVisibleDesktopHeight() + sY > getImageHeight())
//            sY = getImageHeight() - getVisibleDesktopHeight() - absoluteYPosition;

//        absoluteXPosition += sX;
//        absoluteYPosition += sY;
        if (dX != 0.0 || dY != 0.0) {
            absolutePan((int) (absoluteXPosition + dX), (int) (absoluteYPosition + dY), force);
            return true;
        }
        return false;
    }

    /**
     * Absolute pan. These coordinates are in image's resolution.
     *
     * @param x
     * @param y
     */
    public void absolutePan(int x, int y, boolean force) {
        //android.util.Log.d(TAG, "absolutePan: " + x + ", " + y);

        if (scaler != null) {
            int vW = getVisibleDesktopWidth();
            int vH = getVisibleDesktopHeight();
            int w = getImageWidth();
            int h = getImageHeight();

            int bWidth = (int) ((getWidth() - w * getMinimumScale()) / 2);
            if (bWidth <= 0) {
                bWidth = 0;
            }

            if (!force) {
                if (x + vW > w + bWidth) x = w + bWidth - vW;
                if (y + vH > h) y = h - vH;
                if (x < 0) x = 0;
                if (y < 0) y = 0;
            }

            absoluteXPosition = x;
            absoluteYPosition = y;
            resetScroll();
        }
    }

    /* (non-Javadoc)
     * @see android.view.View#onScrollChanged(int, int, int, int)
     */
    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (bitmapData != null) {
            bitmapData.scrollChanged(absoluteXPosition, absoluteYPosition);
        }
    }

    @Override
    public Bitmap getBitmap() {
        if (bitmapData == null) {
            return null;
        }

        return bitmapData.mbitmap;
    }

    public void setActivity(RemoteCanvasActivity activity) {
        this.activity = activity;
    }

    public void showCursor() {
        bitmapData.setDrawCursor(true);
    }

    public void hideCursor() {
        bitmapData.setDrawCursor(false);
    }

    /**
     * Causes a redraw of the myDrawable to happen at the indicated coordinates.
     */
    public void reDraw(int x, int y, int w, int h) {
        reDraw(new DrawTask(x, y, w, h));
    }

    public void reDraw(DrawTask drawTask) {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }

        //android.util.Log.i(TAG, "reDraw called: " + x +", " + y + " + " + w + "x" + h);
//        float scale = getZoomFactor();
//        float shiftedX = x - shiftX;
//        float shiftedY = y - shiftY;

        // drawWorker is null only after onDestroy (line ~1000) nulled it.
        // A paintRunnable can still be in flight on the SSH-Paint thread at
        // that point (teardown race) — without this guard reDraw NPEs, and
        // since paintRunnable runs on a HandlerThread the exception KILLS the
        // paint thread, after which nothing renders at all.
        if (drawWorker == null) return;
        drawWorker.addTask(drawTask);
    }

    /**
     * This is a float-accepting version of reDraw().
     * Causes a redraw of the myDrawable to happen at the indicated coordinates.
     */
    public void reDraw(float x, float y, float w, float h) {
        reDraw((int) x, (int) y, (int) w, (int) h);
    }

    /**
     * Displays connection info in a toast message.
     */
    public void showConnectionInfo() {
        if (rfbconn == null)
            return;

        String msg = null;
        int idx = rfbconn.desktopName().indexOf("(");
        if (idx > 0) {
            // Breakup actual desktop name from IP addresses for improved
            // readability
            String dn = rfbconn.desktopName().substring(0, idx).trim();
            String ip = rfbconn.desktopName().substring(idx).trim();
            msg = dn + "\n" + ip;
        } else
            msg = rfbconn.desktopName();
        msg += "\n" + rfbconn.framebufferWidth() + "x" + rfbconn.framebufferHeight();
        String enc = rfbconn.getEncoding();
        // Encoding might not be set when we display this message
        if (decoder != null && decoder.getColorModel() != null) {
            if (enc != null && !enc.equals("")) {
                msg += ", " + rfbconn.getEncoding() + getContext().getString(R.string.info_encoding) + decoder.getColorModel().toString();
            }
            msg += ", " + decoder.getColorModel().toString();
        }
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Invalidates (to redraw) the location of the remote pointer.
     */
    public void invalidateMousePosition() {
        // NVStream is not using local cursor at present
        if (bitmapData != null && getProtocolType() != ProtocolType.NVSTREAM) {
            // add little offset for the cursor image
            bitmapData.moveCursorRect(pointer.getX() - pointer.getHotspotX(), pointer.getY() - pointer.getHotspotY());
            RectF r = bitmapData.getCursorRect();
            reDraw(r.left, r.top, r.width(), r.height());
        }
    }

    @Override
    public void setMousePointerPosition(int x, int y) {
        softCursorMove(x, y);
    }

    @Override
    public void mouseMode(boolean relative) {
        if (relative && !connection.getInputMode().equals(InputHandlerTouchpad.ID)) {
            showMessage(getContext().getString(R.string.info_set_touchpad_input_mode));
        } else {
            this.pointer.setRelativeEvents(relative);
        }
    }

    @Override
    public boolean isAbleToPan() {
        return scaler.isAbleToPan();
    }

    @Override
    public void onConnectionSuccess() {
        handler.post(() -> {
            TextView messageView = progressDialog.findViewById(R.id.message);
            messageView.setText(R.string.info_continue_connected);

            // there's a bug of some system if we don't send any key event after connection
            // that no further image will be transfered
            pointer.moveMouse(getImageHeight() / 2, getImageWidth() / 2, 0);
        });
    }

    /**
     * Moves soft cursor into a particular location.
     *
     * @param x
     * @param y
     */
    public synchronized void softCursorMove(int x, int y) {
        if (bitmapData.isNotInitSoftCursor() && connection.getUseLocalCursor() != Constants.CURSOR_FORCE_DISABLE) {
            initializeSoftCursor();
        }

        if (!cursorBeingMoved || pointer.isRelativeEvents()) {
            pointer.setX(x);
            pointer.setY(y);
            RectF prevR = new RectF(bitmapData.getCursorRect());
            // Move the cursor.
            bitmapData.moveCursorRect(x, y);
            // Show the cursor.
//            RectF r = myDrawable.getCursorRect();
//            reDraw(r.left, r.top, r.width(), r.height());
//            reDraw(prevR.left, prevR.top, prevR.width(), prevR.height());
        }
    }

    /**
     * Initializes the data structure which holds the remote pointer data.
     */
    void initializeSoftCursor() {
    }

    public void setSoftCursorPixels(int[] pixels, int width, int height, int xPos, int yPos) {
        bitmapData.setCursorRect(pointer.getX() - xPos, pointer.getY() - yPos, width, height, 0, 0);
        bitmapData.setSoftCursor(pixels);

        pointer.setHotspotX(xPos);
        pointer.setHotspotY(yPos);
    }

    public void setSoftCursorBitmap(Bitmap bitmap, int width, int height, int xPos, int yPos) {
        bitmapData.setCursorRect(pointer.getX() - xPos, pointer.getY() - yPos, width, height, 0, 0);
        bitmapData.setSoftCursor(bitmap);

        pointer.setHotspotX(xPos);
        pointer.setHotspotY(yPos);
    }

    public RemotePointer getPointer() {
        return pointer;
    }

    public RemoteKeyboard getKeyboard() {
        return keyboard;
    }

    public ControllerHandler getController() {
        return controller;
    }

    public float getZoomFactor() {
        if (scaler == null)
            return 1;
        return scaler.getZoomFactor();
    }

    public int getVisibleDesktopWidth() {
        return (int) ((double) getWidth() / getZoomFactor());
    }

    public int getVisibleDesktopHeight() {
        return (int) ((double) getHeight() / getZoomFactor());
    }

    public int getImageVisibleInScreenHeight() {
        return (int) ((getImageHeight() - getAbsY()) * getZoomFactor());
    }

    public void setVisibleDesktopHeight(int newHeight) {
        visibleHeight = newHeight;
    }

    public int getImageWidth() {
        return rfbconn.framebufferWidth();
    }

    public int getImageHeight() {
        return rfbconn.framebufferHeight();
    }

    public int getCenteredXOffset() {
        return (int) (rfbconn.framebufferWidth() * getMinimumScale() - getWidth()) / 2;
    }

    public int getBlackBorderWidth() {
        int bWidth = (int) ((getWidth() - getImageWidth() * getMinimumScale()) / 2);
        if (bWidth <= 0) {
            bWidth = 0;
        }

        return bWidth;
    }

    public int getCenteredYOffset() {
        return (int) (rfbconn.framebufferHeight() * getMinimumScale() - getHeight()) / 2;
    }

    public float getMinimumScale() {
        if (bitmapData != null) {
            return bitmapData.getMinimumScale();
        } else
            return 1.f;
    }

    public float getDisplayDensity() {
        return displayDensity;
    }

    public void setDisplayDensity(float displayDensity) {
        this.displayDensity = displayDensity;
    }

    public boolean isColorModel(COLORMODEL cm) {
        if (getProtocolType() == ProtocolType.VNC && decoder != null) {
            return (decoder.getColorModel() != null) && decoder.getColorModel().equals(cm);
        } else {
            return false;
        }
    }

    public void setColorModel(COLORMODEL cm) {
        if (getProtocolType() == ProtocolType.VNC && decoder != null) {
            decoder.setColorModel(cm);
        }
    }

    public boolean getMouseFollowPan() {
        return connection.getFollowPan();
    }

    public int getAbsX() {
        return absoluteXPosition;
    }

    public int getAbsY() {
        return absoluteYPosition;
    }

    /**
     * Used to wait until getWidth and getHeight return sane values.
     */
    public void waitUntilInflated() {
        synchronized (this) {
            while (displayRect.width() == 0 || displayRect.height() == 0) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        // no need to hide system cursor since the remote one is on the external display
        if (!touchpad) {
            return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
        }

        return super.onResolvePointerIcon(event, pointerIndex);
    }

    /**
     * Used to detect when the view is inflated to a sane size other than 0x0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Log.d(TAG, "onSizeChanged: " + w + "x" + h);
        if (w > 0 && h > 0) {
            synchronized (this) {
                this.notify(); // 唤醒等待布局的线程
            }
        }
    }

    /**
     * Function used to initialize an empty SSH HostKey for a new VNC over SSH connection.
     */
    public String retrievevvFileName() {
        return this.vvFileName;
    }

    @Override
    public void onTextObtained(String dialogId, String[] obtainedString, boolean dialogCancelled, boolean save) {
        if (dialogCancelled) {
            handler.sendEmptyMessage(RemoteClientLibConstants.DISCONNECT_NO_MESSAGE);
            return;
        }

        switch (dialogId) {
            case GetTextFragment.DIALOG_ID_GET_VNC_CREDENTIALS:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_VNC_USERNAME.");
                connection.setUserName(obtainedString[0]);
                connection.setPassword(obtainedString[1]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_VNC_PASSWORD:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_VNC_PASSWORD.");
                connection.setPassword(obtainedString[0]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_RDP_CREDENTIALS:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_VNC_PASSWORD.");
                connection.setUserName(obtainedString[0]);
                connection.setRdpDomain(obtainedString[1]);
                connection.setPassword(obtainedString[2]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_SPICE_PASSWORD:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_SPICE_PASSWORD.");
                connection.setPassword(obtainedString[0]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_OPAQUE_CREDENTIALS:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_OPAQUE_CREDENTIALS");
                connection.setUserName(obtainedString[0]);
                connection.setPassword(obtainedString[1]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
                break;
            case GetTextFragment.DIALOG_ID_GET_OPAQUE_PASSWORD:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_OPAQUE_PASSWORD");
                connection.setPassword(obtainedString[0]);
                connection.setKeepPassword(save);
                connection.save(getContext());
                synchronized (spicecomm) {
                    spicecomm.notify();
                }
                break;
            case GetTextFragment.DIALOG_ID_GET_OPAQUE_OTP_CODE:
                Log.i(TAG, "Text obtained from DIALOG_ID_GET_OPAQUE_OTP_CODE");
                connection.setOtpCode(obtainedString[0]);
                synchronized (spicecomm) {
                    spicecomm.notify();
                }
                break;
            default:
                Log.e(TAG, "Unknown dialog type.");
                break;
        }
    }

    public void setInputHandler(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    public void setOutDisplay(boolean outDisplay) {
        this.outDisplay = outDisplay;
    }

    public boolean isOutDisplay() {
        return this.outDisplay;
    }

    public boolean isTouchpad() {
        return touchpad;
    }

    public void setTouchpad(boolean touchpad) {
        this.touchpad = touchpad;
    }

    public void setScaler(AbstractScaling scaler) {
        this.scaler = scaler;
    }

    /*
     * In external display mode, dialog will display in the touchpad canvas, so we accept it here
     */
    public void setProgressDialog(AlertDialog progressDialog) {
        this.progressDialog = progressDialog;
    }

    public AlertDialog getProgressDialog() {
        return progressDialog;
    }

//    public float getZoomLevelFactor() {
//        return connection.getZoomLevel() / 100;
//    }

    public FpsCounter getFpsCounter() {
        return fpsCounter;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        // ★★★ 核心1：返回true，告诉系统「我这个View是文本编辑器」，具备输入能力
        return true;
    }

    /**
     * Provide an InputConnection so the soft keyboard can bind to this SurfaceView.
     *
     * <p>Without this, {@link InputMethodManager#showSoftInput} may refuse to show
     * the keyboard on a plain SurfaceView. For SSH we also convert IME commitText()
     * (the final candidate string delivered by Chinese IMEs) into KeyEvents with
     * keyCode=0 + getCharacters(), which {@link RemoteSshKeyboard} already handles.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Always return a real InputConnection. The IME needs this to bind to
        // the SurfaceView and show the soft keyboard. If we only return it once
        // getProtocolType() == SSH, the IME may have already bound before the
        // connection initializer finished, leaving us with no way to receive
        // text. For non-SSH protocols we just fall back to BaseInputConnection's
        // default behaviour in each override.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE;

        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                Log.i(TAG, "IME commitText: '" + text + "' protocol=" + getProtocolType());
                if (keyboard == null || text == null || text.length() == 0) {
                    return super.commitText(text, newCursorPosition);
                }
                // For every protocol (RDP/VNC/SPICE/NVStream/SSH) the keyboard
                // implementations already understand KEYCODE_UNKNOWN + getCharacters()
                // for printable text. Special-case Enter so it sends a real KEYCODE_ENTER
                // rather than a literal '\n' character.
                for (int i = 0; i < text.length(); ) {
                    int cp = Character.codePointAt(text, i);
                    if (cp == '\n') {
                        keyboard.keyEvent(KeyEvent.KEYCODE_ENTER,
                                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                        keyboard.keyEvent(KeyEvent.KEYCODE_ENTER,
                                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                    } else {
                        String chars = new String(Character.toChars(cp));
                        long time = SystemClock.uptimeMillis();
                        KeyEvent event = new KeyEvent(time, chars, 0,
                                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
                        keyboard.keyEvent(0, event);
                    }
                    i += Character.charCount(cp);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                Log.i(TAG, "IME sendKeyEvent: keyCode=" + event.getKeyCode()
                        + " action=" + event.getAction() + " chars=" + event.getCharacters()
                        + " protocol=" + getProtocolType());
                if (keyboard != null) {
                    keyboard.keyEvent(event.getKeyCode(), event);
                    return true;
                }
                return super.sendKeyEvent(event);
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                Log.i(TAG, "IME deleteSurroundingText: before=" + beforeLength
                        + " protocol=" + getProtocolType());
                if (keyboard == null || beforeLength <= 0) {
                    return super.deleteSurroundingText(beforeLength, afterLength);
                }
                for (int i = 0; i < beforeLength; i++) {
                    keyboard.keyEvent(KeyEvent.KEYCODE_DEL,
                            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    keyboard.keyEvent(KeyEvent.KEYCODE_DEL,
                            new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                }
                return true;
            }
        };
    }

    public void drawTouchpadHint() {
        drawTouchpadHint(displayRect.width(), displayRect.height());
    }

    public void drawTouchpadHint(int width, int height) {
        Paint paint = new Paint();

        Typeface font = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        paint.setTypeface(font);
        paint.setTextSize(64);

        paint.setColor(0x33ffffff);

        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockHardwareCanvas();
            if (canvas != null) {
                synchronized (surfaceHolder) {
                    String text = getContext().getString(R.string.use_as_touchpad);
                    float textWidth = paint.measureText(text);
                    float x = (width - textWidth) / 2f;

                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    canvas.drawText(text, x, height / 2f, paint);
                }
            }
        } finally {
            if (canvas != null) {
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }

    public long getLastDrawMs() {
        // drawWorker is only initialized when !isTouchpad() in the
        // constructor (line 245-247). On SSH connection, the global
        // layout listener can fire before RemoteCanvas finishes
        // constructing, sending a relayoutViews() → checkAndAdjustRemoteResolution()
        // call into getLastDrawMs() while drawWorker is still null.
        // Return 0 (never-drawn) so the caller's existing
        // `getLastDrawMs() <= 0` early-return path runs cleanly.
        return drawWorker != null ? drawWorker.getLastDraw() : 0L;
    }

    /**
     * Which protocol is currently driving this canvas, or null before
     * initializeCanvas() runs. Prefer this over per-protocol booleans.
     */
    public ProtocolType getProtocolType() {
        return connInitializer != null ? connInitializer.getType() : null;
    }

    public void setDisplayRect(Rect displayRect) {
        this.displayRect = displayRect;
    }

    public Rect getDisplayRect() {
        return this.displayRect;
    }
}

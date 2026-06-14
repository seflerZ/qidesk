package com.qihua.bVNC.connection;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import com.qihua.bVNC.App;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.R;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.input.RemoteSpiceKeyboard;
import com.qihua.bVNC.input.RemoteSpicePointer;
import com.undatech.opaque.Connection;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.opaque.SpiceCommunicator;
import com.undatech.opaque.proxmox.ProxmoxClient;
import com.undatech.opaque.proxmox.pojo.PveRealm;
import com.undatech.opaque.proxmox.pojo.PveResource;
import com.undatech.opaque.proxmox.pojo.SpiceDisplay;
import com.undatech.opaque.proxmox.pojo.VmStatus;
import com.undatech.opaque.util.FileUtils;

import org.apache.http.HttpException;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.login.LoginException;

/**
 * SPICE lifecycle owner. Covers both the Opaque and SPICE app flavors.
 *
 * The flow is:
 *   initialize() — build the SpiceCommunicator + RemoteSpicePointer +
 *                  RemoteSpiceKeyboard. Same setup for every SPICE
 *                  variant.
 *   start()      — dispatch on the connection's metadata:
 *                    vv file in intent  -> startFromVvFile()
 *                    PVE connection     -> startPve()
 *                    oVirt URL          -> startOvirt()
 *                    otherwise          -> startSpiceConnection()
 *                                        (direct libspice connect)
 *
 * retrieveVvFileFromPve is a helper used by startPve.
 */
public class SpiceConnectionInitializer extends ConnectionInitializer {
    private static final String TAG = "SpiceConnectionInitializer";

    private final Connection conn;
    private final Context ctx;
    /** vv file name passed in via Intent. Mirrors RemoteCanvas#init's parameter. */
    public String vvFileName;

    public SpiceConnectionInitializer(Connection conn, Context ctx) {
        this.conn = conn;
        this.ctx = ctx;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.SPICE;
    }

    @Override
    public boolean supports(Connection c, Context c2) {
        // SPICE / Opaque is selected by app flavor, not by getConnectionType().
        return Utils.isSpice(c2) || Utils.isOpaque(c2);
    }

    @Override
    public void initialize(RemoteCanvas canvas) throws Exception {
        Log.i(TAG, "initialize: building SpiceCommunicator + input.");
        canvas.spicecomm = new SpiceCommunicator(ctx, canvas.handler, canvas,
                conn.isRequestingNewDisplayResolution() || conn.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM,
                !Utils.isFree(ctx) && conn.isUsbEnabled(), App.debugLog);
        canvas.rfbconn = canvas.spicecomm;
        canvas.pointer = new RemoteSpicePointer(canvas.spicecomm, canvas, canvas.handler, App.debugLog);
        try {
            canvas.keyboard = new RemoteSpiceKeyboard(canvas.getResources(), canvas.spicecomm, canvas,
                    canvas.handler, conn.getLayoutMap(), App.debugLog);
        } catch (Throwable e) {
            canvas.handleUncaughtException(e);
        }
        canvas.maintainConnection = true;
        //spicecomm.setUIEventListener(RemoteCanvas.this);
        canvas.spicecomm.setHandler(canvas.handler);
    }

    @Override
    public void start(RemoteCanvas canvas) throws Exception {
        if (vvFileName == null) {
            if (conn.getConnectionTypeString().equals(ctx.getString(R.string.connection_type_pve))) {
                startPve(canvas);
            } else {
                conn.setAddress(Utils.getHostFromUriString(conn.getAddress()));
                startOvirt(canvas);
            }
        } else {
            startFromVvFile(canvas, vvFileName);
        }
    }

    @Override
    public void onDisplayRectChanged(Display display) {

    }

    /**
     * Direct libspice connect when the user has an existing SPICE
     * server's address/port. Mirrors the old RemoteCanvas#startSpiceConnection.
     */
    public void startSpiceConnection(RemoteCanvas canvas) throws Exception {
        // Get the address and port (based on whether an SSH tunnel is being established or not).
        String address = canvas.getAddress();
        // To prevent an SSH tunnel being created when port or TLS port is not set, we only
        // getPort when port/tport are positive.
        int port = conn.getPort();
        if (port > 0) {
            port = canvas.getRemoteProtocolPort(port);
        }

        int tport = conn.getTlsPort();
        if (tport > 0) {
            tport = canvas.getRemoteProtocolPort(tport);
        }

        canvas.spicecomm.connectSpice(address, Integer.toString(port), Integer.toString(tport), conn.getPassword(),
                conn.getCaCertPath(), null, // TODO: Can send connection.getCaCert() here instead
                conn.getCertSubject(), conn.getEnableSound());
    }

    /**
     * oVirt entrypoint: fetch the VM list, prompt the user if there's
     * more than one, then connect via the oVirt REST API.
     */
    void startOvirt(final RemoteCanvas canvas) {
        Thread cThread = new Thread() {
            @Override
            public void run() {
                try {
                    // Obtain user's password if necessary.
                    if (conn.getPassword().equals("")) {
                        Log.i(TAG, "Displaying a dialog to obtain user's password.");
                        canvas.handler.sendEmptyMessage(RemoteClientLibConstants.GET_PASSWORD);
                        synchronized (canvas.spicecomm) {
                            canvas.spicecomm.wait();
                        }
                    }

                    String ovirtCaFile = null;
                    if (conn.isUsingCustomOvirtCa()) {
                        ovirtCaFile = conn.getOvirtCaFile();
                    } else {
                        ovirtCaFile = new File(ctx.getFilesDir(), "ssl/certs/ca-certificates.crt").getPath();
                    }

                    // If not VM name is specified, then get a list of VMs and let the user pick one.
                    if (conn.getVmname().equals("")) {
                        int success = canvas.spicecomm.fetchOvirtVmNames(conn.getHostname(), conn.getUserName(),
                                conn.getPassword(), ovirtCaFile, conn.isSslStrict());
                        ArrayList<String> vmNames = canvas.spicecomm.getVmNames();
                        if (success != 0 || vmNames.isEmpty()) {
                            return;
                        } else {
                            if (vmNames.size() == 1) {
                                conn.setVmname(vmNames.get(0));
                                conn.save(ctx);
                            } else {
                                while (conn.getVmname().equals("")) {
                                    Log.i(TAG, "Displaying a dialog with VMs to the user.");
                                    for (String s : vmNames) {
                                        canvas.vmNameToId.put(s, s);
                                    }
                                    canvas.handler.sendMessage(com.qihua.bVNC.input.RemoteCanvasHandler
                                            .getMessageStringList(RemoteClientLibConstants.DIALOG_DISPLAY_VMS,
                                                    "vms", vmNames));
                                    synchronized (canvas.spicecomm) {
                                        canvas.spicecomm.wait();
                                    }
                                }
                            }
                        }
                    }
                    canvas.spicecomm.setHandler(canvas.handler);
                    canvas.spicecomm.connectOvirt(conn.getHostname(),
                            conn.getVmname(),
                            conn.getUserName(),
                            conn.getPassword(),
                            ovirtCaFile,
                            conn.isAudioPlaybackEnabled(), conn.isSslStrict());

                    try {
                        synchronized (canvas.spicecomm) {
                            canvas.spicecomm.wait(35000);
                        }
                    } catch (InterruptedException e) {
                    }

                    if (!canvas.spiceUpdateReceived && canvas.maintainConnection) {
                        canvas.handler.sendEmptyMessage(RemoteClientLibConstants.OVIRT_TIMEOUT);
                    }
                } catch (Throwable e) {
                    canvas.handleUncaughtException(e);
                }
            }
        };
        cThread.start();
    }

    /**
     * PVE (Proxmox) entrypoint: log into the Proxmox API, find a VM
     * (prompt if more than one), then ask the API for a .vv file
     * describing the SPICE display, then connect.
     */
    void startPve(final RemoteCanvas canvas) {
        Thread cThread = new Thread() {
            @Override
            public void run() {
                try {
                    // Obtain user's password if necessary.
                    if (conn.getPassword().equals("")) {
                        Log.i(TAG, "Displaying a dialog to obtain user's password.");
                        canvas.handler.sendEmptyMessage(RemoteClientLibConstants.GET_PASSWORD);
                        synchronized (canvas.spicecomm) {
                            canvas.spicecomm.wait();
                        }
                    }

                    String user = conn.getUserName();
                    String realm = RemoteClientLibConstants.PVE_DEFAULT_REALM;

                    int indexOfAt = conn.getUserName().indexOf('@');
                    if (indexOfAt != -1) {
                        realm = user.substring(indexOfAt + 1);
                        user = user.substring(0, indexOfAt);
                    }

                    String uriToParse = conn.getHostname();
                    if (!uriToParse.startsWith("http://") && !uriToParse.startsWith("https://")) {
                        uriToParse = String.format("%s%s", "https://", uriToParse);
                    }
                    Uri uri = Uri.parse(uriToParse);
                    String protocol = uri.getScheme();
                    String host = uri.getHost();
                    int port = uri.getPort();
                    if (port < 0) {
                        port = 8006;
                    }
                    String pveUri = String.format("%s://%s:%d", protocol, host, port);

                    ProxmoxClient api = new ProxmoxClient(pveUri, conn, canvas.handler);
                    HashMap<String, PveRealm> realms = api.getAvailableRealms();

                    if (realms.get(realm).getTfa() != null) {
                        Log.i(TAG, "Displaying a dialog to obtain OTP/TFA.");
                        canvas.handler.sendEmptyMessage(RemoteClientLibConstants.GET_OTP_CODE);
                        synchronized (canvas.spicecomm) {
                            canvas.spicecomm.wait();
                        }
                    }

                    api.login(user, realm, conn.getPassword(), conn.getOtpCode());

                    Map<String, PveResource> nameToResources = api.getResources();

                    if (nameToResources.isEmpty()) {
                        Log.e(TAG, "No available VMs found for user in PVE cluster");
                        canvas.disconnectAndShowMessage(R.string.error_no_vm_found_for_user, R.string.error_dialog_title);
                        return;
                    }

                    String vmId = conn.getVmname();
                    if (vmId.matches("/")) {
                        vmId = conn.getVmname().replaceAll(".*/", "");
                        conn.setVmname(vmId);
                        conn.save(ctx);
                    }

                    String node = null;
                    String virt = null;

                    if (nameToResources.size() == 1) {
                        Log.e(TAG, "A single VM was found, so picking it.");
                        String key = (String) nameToResources.keySet().toArray()[0];
                        PveResource a = nameToResources.get(key);
                        node = a.getNode();
                        virt = a.getType();
                        conn.setVmname(a.getVmid());
                        conn.save(ctx);
                    } else {
                        while (conn.getVmname().isEmpty()) {
                            Log.i(TAG, "PVE: Displaying a dialog with VMs to the user.");
                            for (String s : nameToResources.keySet()) {
                                canvas.vmNameToId.put(nameToResources.get(s).getName() + " (" + s + ")", s);
                            }
                            ArrayList<String> vms = new ArrayList<>(canvas.vmNameToId.keySet());
                            canvas.handler.sendMessage(com.qihua.bVNC.input.RemoteCanvasHandler
                                    .getMessageStringList(RemoteClientLibConstants.DIALOG_DISPLAY_VMS,
                                            "vms", vms));
                            synchronized (canvas.spicecomm) {
                                canvas.spicecomm.wait();
                            }
                        }

                        if (nameToResources.get(conn.getVmname()) != null) {
                            node = nameToResources.get(conn.getVmname()).getNode();
                            virt = nameToResources.get(conn.getVmname()).getType();
                        } else {
                            Log.e(TAG, "No VM with the following ID was found: " + conn.getVmname());
                            canvas.disconnectAndShowMessage(R.string.error_no_such_vm_found_for_user, R.string.error_dialog_title);
                            return;
                        }
                    }

                    vmId = conn.getVmname();
                    if (!vmId.isEmpty()) {
                        String vv = retrieveVvFileFromPve(canvas, host, api, vmId, node, virt);
                        if (vv != null) {
                            startFromVvFile(canvas, vv);
                        }
                    }
                } catch (LoginException e) {
                    Log.e(TAG, "Failed to login to PVE.");
                    canvas.handler.sendEmptyMessage(RemoteClientLibConstants.PVE_FAILED_TO_AUTHENTICATE);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse json from PVE.");
                    canvas.handler.sendEmptyMessage(RemoteClientLibConstants.PVE_FAILED_TO_PARSE_JSON);
                } catch (IOException e) {
                    Log.e(TAG, "IO Error communicating with PVE API: " + e.getMessage());
                    canvas.handler.sendMessage(com.qihua.bVNC.input.RemoteCanvasHandler
                            .getMessageString(RemoteClientLibConstants.PVE_API_IO_ERROR,
                                    "error", e.getMessage()));
                    e.printStackTrace();
                } catch (HttpException e) {
                    Log.e(TAG, "PVE API returned error code: " + e.getMessage());
                    canvas.handler.sendMessage(com.qihua.bVNC.input.RemoteCanvasHandler
                            .getMessageString(RemoteClientLibConstants.PVE_API_UNEXPECTED_CODE,
                                    "error", e.getMessage()));
                } catch (Throwable e) {
                    canvas.handleUncaughtException(e);
                }
            }
        };
        cThread.start();
    }

    /**
     * Run on a worker thread: ask the Proxmox API for a .vv file
     * describing the SPICE display for the chosen VM. The vv file
     * is written to a temp file and the path is returned.
     */
    String retrieveVvFileFromPve(final RemoteCanvas canvas, final String hostname, final ProxmoxClient api,
                                 final String vmId, final String node, final String virt) {
        Log.i(TAG, "Trying to connect to PVE host: " + hostname);
        final String tempVvFile = ctx.getFilesDir() + "/tempfile.vv";
        FileUtils.deleteFile(tempVvFile);

        Thread cThread = new Thread() {
            @Override
            public void run() {
                try {
                    VmStatus status = api.getCurrentStatus(node, virt, Integer.parseInt(vmId));
                    if (status.getStatus().equals(VmStatus.STOPPED)) {
                        api.startVm(node, virt, Integer.parseInt(vmId));
                        while (!status.getStatus().equals(VmStatus.RUNNING)) {
                            status = api.getCurrentStatus(node, virt, Integer.parseInt(vmId));
                            SystemClock.sleep(500);
                        }
                    }
                    SpiceDisplay spiceData = api.spiceVm(node, virt, Integer.parseInt(vmId));
                    if (spiceData != null) {
                        spiceData.outputToFile(tempVvFile, hostname);
                    } else {
                        Log.e(TAG, "PVE returned null data for display.");
                        canvas.handler.sendEmptyMessage(RemoteClientLibConstants.PVE_NULL_DATA);
                    }
                } catch (LoginException e) {
                    Log.e(TAG, "Failed to login to PVE.");
                    canvas.handler.sendEmptyMessage(RemoteClientLibConstants.PVE_FAILED_TO_AUTHENTICATE);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse json from PVE.");
                    canvas.handler.sendEmptyMessage(RemoteClientLibConstants.PVE_FAILED_TO_PARSE_JSON);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error converting PVE ID to integer.");
                    canvas.handler.sendEmptyMessage(RemoteClientLibConstants.PVE_VMID_NOT_NUMERIC);
                } catch (IOException e) {
                    Log.e(TAG, "IO Error communicating with PVE API: " + e.getMessage());
                    canvas.handler.sendMessage(com.qihua.bVNC.input.RemoteCanvasHandler
                            .getMessageString(RemoteClientLibConstants.PVE_API_IO_ERROR,
                                    "error", e.getMessage()));
                    e.printStackTrace();
                } catch (HttpException e) {
                    Log.e(TAG, "PVE API returned error code: " + e.getMessage());
                    canvas.handler.sendMessage(com.qihua.bVNC.input.RemoteCanvasHandler
                            .getMessageString(RemoteClientLibConstants.PVE_API_UNEXPECTED_CODE,
                                    "error", e.getMessage()));
                }
                synchronized (tempVvFile) {
                    tempVvFile.notify();
                }
            }
        };
        cThread.start();

        synchronized (tempVvFile) {
            try {
                tempVvFile.wait();
            } catch (InterruptedException e) {
                canvas.handler.sendEmptyMessage(RemoteClientLibConstants.PVE_TIMEOUT_COMMUNICATING);
                e.printStackTrace();
            }
        }

        File checkFile = new File(tempVvFile);
        if (!checkFile.exists() || checkFile.length() == 0) {
            return null;
        }
        return tempVvFile;
    }

    void startFromVvFile(final RemoteCanvas canvas, final String vvFileName) {
        Thread cThread = new Thread() {
            @Override
            public void run() {
                try {
                    canvas.spicecomm.startSessionFromVvFile(vvFileName, conn.isAudioPlaybackEnabled());
                } catch (Throwable e) {
                    canvas.handleUncaughtException(e);
                }
            }
        };
        cThread.start();
    }
}

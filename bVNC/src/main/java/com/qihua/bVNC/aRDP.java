/**
 * Copyright (C) 2012 Iordan Iordanov
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

package com.qihua.bVNC;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.SpinnerDialog;
import com.qihua.bVNC.gesture.GestureEditorActivity;
import com.qihua.util.PermissionGroups;
import com.qihua.util.PermissionsManager;
import com.morpheusly.common.Utilities;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * aRDP is the Activity for setting up RDP connections.
 */
public class aRDP extends MainConfiguration {
    private final static String TAG = "aRDP";
    private LinearLayout layoutAdvancedSettings;
    private EditText sshServer;
    private EditText sshPort;
    private EditText sshUser;
    private EditText portText;
    private EditText textPassword;
    private ToggleButton toggleAdvancedSettings;
    //private Spinner colorSpinner;
    private Spinner spinnerRdpGeometry;
    private String lastRawApplist;
    private Spinner spinnerRdpZoomLevel;
    private EditText textUsername;
    private EditText rdpDomain;
    private EditText rdpWidth;
    private EditText rdpHeight;
    private CheckBox checkboxKeepPassword;
    private CheckBox checkboxUseDpadAsArrows;
    private RadioGroup groupRemoteSoundType;
    private CheckBox checkboxEnableRecording;
    private CheckBox checkboxEnableGesture;
    private CheckBox checkboxConsoleMode;
//    private CheckBox checkboxRedirectSdCard;
    private CheckBox checkboxRemoteFx;
    private CheckBox checkboxDesktopBackground;
    private CheckBox checkboxFontSmoothing;
    private CheckBox checkboxDesktopComposition;
    private CheckBox checkboxWindowContents;
    private CheckBox checkboxMenuAnimation;
    private CheckBox checkboxVisualStyles;
    private CheckBox checkboxRotateDpad;
    private CheckBox checkboxUseLastPositionToolbar;
    private CheckBox checkboxUseSshPubkey;
    private CheckBox checkboxEnableGfx;
    private CheckBox checkboxEnableGfxH264;
    private CheckBox checkboxPreferSendingUnicode;
    private Spinner spinnerRdpColor;
    private Spinner spinnerNvApp;
    private List<NvApp> lastNvApps;
    private ArrayAdapter<String> adapterNvAppNames;
    private List<String> rdpColorArray;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ComputerManagerService.ApplistPoller poller;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, final IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start polling
                    managerBinder.startPolling(new ComputerManagerListener() {
                        @Override
                        public void notifyComputerUpdated(final ComputerDetails details) {
                            if (details.pairState == PairingManager.PairState.PAIRED
                                    && details.manualAddress != null) {
                                aRDP.this.runOnUiThread(() -> {
                                    String computerAddress = details.manualAddress.toString();

                                    if (computerAddress.equals(ipText.getText() + ":" + portText.getText())) {
                                        Button btn = findViewById(R.id.nvstream_pair);
                                        btn.setEnabled(false);
                                        btn.setBackgroundColor(getColor(R.color.grey_overlay));
                                        btn.setTextColor(getColor(R.color.theme));
                                        btn.setText("PAIRED & " + details.state.toString());

                                        nickText.setText(details.name);
                                        sshServer.setText(details.uuid);

                                        // Since the computer is online, start polling the apps
                                        if (poller == null) {
                                            poller = managerBinder.createAppListPoller(details);
                                            poller.start();
                                        }

                                        if (details.rawAppList != null) {
                                            try {
                                                updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                                            } catch (XmlPullParserException | IOException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    }
                                });
                            }
                        }
                    });

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(aRDP.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onCreate(Bundle icicle) {
        layoutID = R.layout.main_rdp;

        super.onCreate(icicle);

        // Bind to the ComputerManager service
        bindService(new Intent(aRDP.this,
                ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);

        sshServer = (EditText) findViewById(R.id.sshServer);
        sshPort = (EditText) findViewById(R.id.sshPort);
        sshUser = (EditText) findViewById(R.id.sshUser);
        portText = (EditText) findViewById(R.id.textPORT);
        textPassword = (EditText) findViewById(R.id.textPASSWORD);
        textUsername = (EditText) findViewById(R.id.textUsername);
        rdpDomain = (EditText) findViewById(R.id.rdpDomain);

        // Here we say what happens when the Pubkey Checkbox is checked/unchecked.
        checkboxUseSshPubkey = (CheckBox) findViewById(R.id.checkboxUseSshPubkey);

        checkboxKeepPassword = (CheckBox) findViewById(R.id.checkboxKeepPassword);
        checkboxUseDpadAsArrows = (CheckBox) findViewById(R.id.checkboxUseDpadAsArrows);
        checkboxRotateDpad = (CheckBox) findViewById(R.id.checkboxRotateDpad);
        checkboxUseLastPositionToolbar = (CheckBox) findViewById(R.id.checkboxUseLastPositionToolbar);
        // The advanced settings button.
        toggleAdvancedSettings = (ToggleButton) findViewById(R.id.toggleAdvancedSettings);
        layoutAdvancedSettings = (LinearLayout) findViewById(R.id.layoutAdvancedSettings);
        toggleAdvancedSettings.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton arg0, boolean checked) {
                if (checked)
                    layoutAdvancedSettings.setVisibility(View.VISIBLE);
                else
                    layoutAdvancedSettings.setVisibility(View.GONE);
            }
        });

        spinnerNvApp = (Spinner) findViewById(R.id.spinnerNvApp);
        adapterNvAppNames = new ArrayAdapter<>(getApplicationContext(),
                android.R.layout.simple_spinner_item,
                new ArrayList<>());
        adapterNvAppNames.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerNvApp.setAdapter(adapterNvAppNames);
        spinnerNvApp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                NvApp nvApp = lastNvApps.get(position);
                textUsername.setText(nvApp.getAppName());
                textPassword.setText(String.valueOf(nvApp.getAppId()));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        rdpColorArray = Utilities.Companion.toList(getResources().getStringArray(R.array.rdp_colors));
        spinnerRdpColor = (Spinner) findViewById(R.id.spinnerRdpColor);
        spinnerRdpColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view, int itemIndex, long id) {
                selected.setRdpColor(Integer.parseInt(rdpColorArray.get(itemIndex)));
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });

        // The geometry type and dimensions boxes.
        spinnerRdpGeometry = (Spinner) findViewById(R.id.spinnerRdpGeometry);
        spinnerRdpZoomLevel = (Spinner) findViewById(R.id.spinnerRdpZoomLevel);
        rdpWidth = (EditText) findViewById(R.id.rdpWidth);
        rdpHeight = (EditText) findViewById(R.id.rdpHeight);
        spinnerRdpGeometry.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view, int itemIndex, long id) {
                selected.setRdpResType(itemIndex);
                setRemoteWidthAndHeight();
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });

        spinnerRdpZoomLevel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view, int itemIndex, long id) {
                int level;
                switch (itemIndex) {
                    case 0:
                        level = 100;
                        break;
                    case 1:
                        level = 125;
                        break;
                    case 2:
                        level = 140;
                        break;
                    case 3:
                        level = 160;
                        break;
                    case 4:
                        level = 200;
                        break;
                    default:
                        level = 100;
                }

                selected.setZoomLevel(level);
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });

        connectionType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> ad, View view, int itemIndex, long id) {
                selectedConnType = itemIndex;
                selected.setConnectionType(selectedConnType);
//                selected.save(MainConfiguration.this);
//                updateViewFromSelected();

                if (selectedConnType == Constants.CONN_TYPE_RDP) {
                    setVisibilityOfSshWidgets(View.GONE);
                    rdpDomain.setVisibility(View.VISIBLE);

                    findViewById(R.id.nvstream_pair).setVisibility(View.GONE);

                    findViewById(R.id.textPASSWORD).setVisibility(View.VISIBLE);
                    findViewById(R.id.checkboxKeepPassword).setVisibility(View.VISIBLE);
                    findViewById(R.id.textUsername).setVisibility(View.VISIBLE);
                    findViewById(R.id.textNickname).setVisibility(View.VISIBLE);

                    findViewById(R.id.geometryGroup).setVisibility(View.VISIBLE);
                    findViewById(R.id.checkboxEnableRecording).setVisibility(View.VISIBLE);
                    findViewById(R.id.textDescriptGeom).setVisibility(View.VISIBLE);

                    if (selected.getPort() <= 0) {
                        portText.setText("3389");
                    }
                } else if (selectedConnType == Constants.CONN_TYPE_VNC) {
                    rdpDomain.setVisibility(View.GONE);

                    findViewById(R.id.nvstream_pair).setVisibility(View.GONE);

                    findViewById(R.id.textPASSWORD).setVisibility(View.VISIBLE);
                    findViewById(R.id.checkboxKeepPassword).setVisibility(View.VISIBLE);
                    findViewById(R.id.textUsername).setVisibility(View.VISIBLE);
                    findViewById(R.id.textNickname).setVisibility(View.VISIBLE);

                    findViewById(R.id.geometryGroup).setVisibility(View.GONE);
                    findViewById(R.id.checkboxEnableRecording).setVisibility(View.GONE);
                    findViewById(R.id.textDescriptGeom).setVisibility(View.GONE);

                    if (selected.getPort() <= 0) {
                        portText.setText("5900");
                    }
                } else if (selectedConnType == Constants.CONN_TYPE_NVSTREAM) {
                    rdpDomain.setVisibility(View.GONE);

                    findViewById(R.id.nvstream_pair).setVisibility(View.VISIBLE);

                    findViewById(R.id.geometryGroup).setVisibility(View.GONE);
                    findViewById(R.id.checkboxEnableRecording).setVisibility(View.GONE);
                    findViewById(R.id.textDescriptGeom).setVisibility(View.GONE);
                    findViewById(R.id.textNickname).setVisibility(View.GONE);

                    findViewById(R.id.textPASSWORD).setVisibility(View.GONE);
                    findViewById(R.id.checkboxKeepPassword).setVisibility(View.GONE);
                    findViewById(R.id.textUsername).setVisibility(View.GONE);

                    findViewById(R.id.geometryGroup).setVisibility(View.VISIBLE);
                    findViewById(R.id.checkboxEnableRecording).setVisibility(View.GONE);
                    findViewById(R.id.geometryGroupZoom).setVisibility(View.GONE);

                    if (selected.getPort() <= 0) {
                        portText.setText("48010");
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> ad) {
            }
        });

        groupRemoteSoundType = (RadioGroup) findViewById(R.id.groupRemoteSoundType);
        checkboxEnableRecording = (CheckBox) findViewById(R.id.checkboxEnableRecording);
        checkboxConsoleMode = (CheckBox) findViewById(R.id.checkboxConsoleMode);
//        checkboxRedirectSdCard = (CheckBox) findViewById(R.id.checkboxRedirectSdCard);
        checkboxEnableGesture = (CheckBox) findViewById(R.id.checkboxEnableGesture);
        checkboxRemoteFx = (CheckBox) findViewById(R.id.checkboxRemoteFx);
        checkboxDesktopBackground = (CheckBox) findViewById(R.id.checkboxDesktopBackground);
        checkboxFontSmoothing = (CheckBox) findViewById(R.id.checkboxFontSmoothing);
        checkboxDesktopComposition = (CheckBox) findViewById(R.id.checkboxDesktopComposition);
        checkboxWindowContents = (CheckBox) findViewById(R.id.checkboxWindowContents);
        checkboxMenuAnimation = (CheckBox) findViewById(R.id.checkboxMenuAnimation);
        checkboxVisualStyles = (CheckBox) findViewById(R.id.checkboxVisualStyles);
        checkboxEnableGfx = (CheckBox) findViewById(R.id.checkboxEnableGfx);
        checkboxEnableGfxH264 = (CheckBox) findViewById(R.id.checkboxEnableGfxH264);
        checkboxPreferSendingUnicode = (CheckBox) findViewById(R.id.checkboxPreferSendingUnicode);
//        setConnectionTypeSpinnerAdapter(R.array.rdp_connection_type);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }

        if (poller != null) {
            poller.stop();
        }
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        lastNvApps = appList;
        if (appList == null || appList.isEmpty()) {
            return;
        }

        // set the spinner to last selected
        if (selected.getPassword() != null) {
            for (int i = 0; i < lastNvApps.size(); i++) {
                NvApp nvApp = lastNvApps.get(i);
                int curAppId = Integer.parseInt(selected.getPassword());
                if (nvApp.getAppId() != curAppId) {
                    continue;
                }

                spinnerNvApp.setSelection(i);
            }
        }

        List<String> appNames = appList.stream().map(NvApp::getAppName).collect(Collectors.toList());

        adapterNvAppNames.clear();
        adapterNvAppNames.addAll(appNames);

        // show the spinner
        spinnerNvApp.setVisibility(View.VISIBLE);
    }

    /**
     * Enables and disables the EditText boxes for width and height of remote desktop.
     */
    private void setRemoteWidthAndHeight() {
        if (selected.getRdpResType() != Constants.RDP_GEOM_SELECT_CUSTOM) {
            rdpWidth.setEnabled(false);
            rdpHeight.setEnabled(false);
        } else {
            rdpWidth.setEnabled(true);
            rdpHeight.setEnabled(true);
        }
    }

    protected void updateViewFromSelected() {
        if (selected == null)
            return;
        super.commonUpdateViewFromSelected();

        sshServer.setText(selected.getSshServer());
        sshPort.setText(Integer.toString(selected.getSshPort()));
        sshUser.setText(selected.getSshUser());

        checkboxUseSshPubkey.setChecked(selected.getUseSshPubKey());

        portText.setText(Integer.toString(selected.getPort()));

        if (selected.getKeepPassword() || selected.getPassword().length() > 0) {
            textPassword.setText(selected.getPassword());
        }

        checkboxKeepPassword.setChecked(selected.getKeepPassword());
        checkboxUseDpadAsArrows.setChecked(selected.getUseDpadAsArrows());
        checkboxRotateDpad.setChecked(selected.getRotateDpad());
        checkboxUseLastPositionToolbar.setChecked((!isNewConnection) ? selected.getUseLastPositionToolbar() : this.useLastPositionToolbarDefault());
        nickText.setText(selected.getNickname());
        textUsername.setText(selected.getUserName());
        rdpDomain.setText(selected.getRdpDomain());
        spinnerRdpColor.setSelection(rdpColorArray.indexOf(String.valueOf(selected.getRdpColor())));
        spinnerRdpGeometry.setSelection(selected.getRdpResType());
        spinnerRdpZoomLevel.setSelection(convert2ZoomIndex(selected.getZoomLevel()));
        rdpWidth.setText(Integer.toString(selected.getRdpWidth()));
        rdpHeight.setText(Integer.toString(selected.getRdpHeight()));
        setRemoteWidthAndHeight();
        setRemoteSoundTypeFromSettings(selected.getRemoteSoundType());
        checkboxEnableRecording.setChecked(selected.getEnableRecording());
        checkboxEnableGesture.setChecked(selected.getEnableGesture());
        checkboxConsoleMode.setChecked(selected.getConsoleMode());
//        checkboxRedirectSdCard.setChecked(selected.getRedirectSdCard());
        checkboxRemoteFx.setChecked(selected.getRemoteFx());
        checkboxDesktopBackground.setChecked(selected.getDesktopBackground());
        checkboxFontSmoothing.setChecked(selected.getFontSmoothing());
        checkboxDesktopComposition.setChecked(selected.getDesktopComposition());
        checkboxWindowContents.setChecked(selected.getWindowContents());
        checkboxMenuAnimation.setChecked(selected.getMenuAnimation());
        checkboxVisualStyles.setChecked(selected.getVisualStyles());
        checkboxEnableGfx.setChecked(selected.getEnableGfx());
        checkboxEnableGfxH264.setChecked(selected.getEnableGfxH264());
        checkboxPreferSendingUnicode.setChecked(selected.getPreferSendingUnicode());

        /* TODO: Reinstate color spinner but for RDP settings.
        colorSpinner = (Spinner)findViewById(R.id.colorformat);
        COLORMODEL[] models=COLORMODEL.values();
        ArrayAdapter<COLORMODEL> colorSpinnerAdapter = new ArrayAdapter<COLORMODEL>(this, R.layout.connection_list_entry, models);
        colorSpinner.setAdapter(colorSpinnerAdapter);
        colorSpinner.setSelection(0);
        COLORMODEL cm = COLORMODEL.valueOf(selected.getColorModel());
        COLORMODEL[] colors=COLORMODEL.values();
        for (int i=0; i<colors.length; ++i)
        {
            if (colors[i] == cm) {
                colorSpinner.setSelection(i);
                break;
            }
        }*/
    }

    private int convert2ZoomIndex(int zoomLevel) {
        if (zoomLevel == 100) {
            return 0;
        }

        if (zoomLevel == 125) {
            return 1;
        }

        if (zoomLevel == 140) {
            return 2;
        }

        if (zoomLevel == 160) {
            return 3;
        }

        if (zoomLevel == 200) {
            return 4;
        }

        return 0;
    }

    private URI parseRawUserInputToUri(String rawUserInput) {
        try {
            // Try adding a scheme and parsing the remaining input.
            // This handles input like 127.0.0.1:47989, [::1], [::1]:47989, and 127.0.0.1.
            URI uri = new URI("moonlight://" + rawUserInput);
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        try {
            // Attempt to escape the input as an IPv6 literal.
            // This handles input like ::1.
            URI uri = new URI("moonlight://[" + rawUserInput + "]");
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        return null;
    }

    private boolean isWrongSubnetSiteLocalAddress(String address) {
        try {
            InetAddress targetAddress = InetAddress.getByName(address);
            if (!(targetAddress instanceof Inet4Address) || !targetAddress.isSiteLocalAddress()) {
                return false;
            }

            // We have a site-local address. Look for a matching local interface.
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (!(addr.getAddress() instanceof Inet4Address) || !addr.getAddress().isSiteLocalAddress()) {
                        // Skip non-site-local or non-IPv4 addresses
                        continue;
                    }

                    byte[] targetAddrBytes = targetAddress.getAddress();
                    byte[] ifaceAddrBytes = addr.getAddress().getAddress();

                    // Compare prefix to ensure it's the same
                    boolean addressMatches = true;
                    for (int i = 0; i < addr.getNetworkPrefixLength(); i++) {
                        if ((ifaceAddrBytes[i / 8] & (1 << (i % 8))) != (targetAddrBytes[i / 8] & (1 << (i % 8)))) {
                            addressMatches = false;
                            break;
                        }
                    }

                    if (addressMatches) {
                        return false;
                    }
                }
            }

            // Couldn't find a matching interface
            return true;
        } catch (Exception e) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            e.printStackTrace();
            return false;
        }
    }

    private ComputerDetails doAddPc(String rawUserInput) {
        boolean wrongSiteLocal = false;
        boolean invalidInput = false;
        boolean success;
        int portTestResult;

        SpinnerDialog dialog = SpinnerDialog.displayDialog(this, getResources().getString(R.string.title_add_pc),
                getResources().getString(R.string.msg_add_pc), false);

        ComputerDetails computerDetails = new ComputerDetails();

        try {
            // Check if we parsed a host address successfully
            URI uri = parseRawUserInputToUri(rawUserInput);
            if (uri != null && uri.getHost() != null && !uri.getHost().isEmpty()) {
                String host = uri.getHost();
                int port = uri.getPort();

                // If a port was not specified, use the default
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT;
                }

                computerDetails.manualAddress = new ComputerDetails.AddressTuple(host, port);
                success = managerBinder.addComputerBlocking(computerDetails);
                if (!success){
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host);
                }
            } else {
                // Invalid user input
                success = false;
                invalidInput = true;
            }
        } catch (Exception e) {
            success = false;
            invalidInput = true;
        }

        // Keep the SpinnerDialog open while testing connectivity
        if (!success && !wrongSiteLocal && !invalidInput) {
            // Run the test before dismissing the spinner because it can take a few seconds.
            portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443,
                    MoonBridge.ML_PORT_FLAG_TCP_47984 | MoonBridge.ML_PORT_FLAG_TCP_47989);
        } else {
            // Don't bother with the test if we succeeded or the IP address was bogus
            portTestResult = MoonBridge.ML_TEST_RESULT_INCONCLUSIVE;
        }

        dialog.dismiss();

        if (invalidInput) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_unknown_host), false);
        }
        else if (wrongSiteLocal) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_wrong_sitelocal), false);
        }
        else if (!success) {
            String dialogText;
            if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
                dialogText = getResources().getString(R.string.nettest_text_blocked);
            }
            else {
                dialogText = getResources().getString(R.string.addpc_fail);
            }
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), dialogText, false);
        }
        else {
            aRDP.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(aRDP.this, getResources().getString(R.string.addpc_success), Toast.LENGTH_LONG).show();
                }
            });
        }

        if (!success) {
            return null;
        }

        return computerDetails;
    }

    public void nvStreamPair(View view) {
        String address = ipText.getText() +  ":" + portText.getText();

        ComputerDetails computerDetails = managerBinder.getComputerByAddress(address);
        if (computerDetails == null) {
            computerDetails = doAddPc(address);
        }

        if (computerDetails == null) {
            // error handling
            return;
        }

        // Fill in the NickName of this device
        nickText.setText(computerDetails.name);
        textUsername.setText(computerDetails.uuid);

        doPair(computerDetails);
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(aRDP.this, getResources().getString(com.limelight.R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(aRDP.this, getResources().getString(com.limelight.R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(aRDP.this, getResources().getString(com.limelight.R.string.pairing), Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(aRDP.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        message = getResources().getString(com.limelight.R.string.pair_succeed);
                        success = true;
                    } else {
                        final String pinStr = PairingManager.generatePinString();

                        // Spin the dialog off in a thread because it blocks
                        Dialog.displayDialog(aRDP.this, getResources().getString(com.limelight.R.string.pair_pairing_title),
                                getResources().getString(com.limelight.R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                                        getResources().getString(com.limelight.R.string.pair_pairing_help), false);

                        PairingManager pm = httpConn.getPairingManager();

                        PairingManager.PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr);
                        if (pairState == PairingManager.PairState.PIN_WRONG) {
                            message = getResources().getString(com.limelight.R.string.pair_incorrect_pin);
                        } else if (pairState == PairingManager.PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(com.limelight.R.string.pair_pc_ingame);
                            } else {
                                message = getResources().getString(com.limelight.R.string.pair_fail);
                            }
                        } else if (pairState == PairingManager.PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(com.limelight.R.string.pair_already_in_progress);
                        } else if (pairState == PairingManager.PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = getResources().getString(com.limelight.R.string.pair_succeed);
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        } else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(com.limelight.R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(com.limelight.R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;

                runOnUiThread(() -> {
                    if (toastMessage != null) {
                        Toast.makeText(aRDP.this, toastMessage, Toast.LENGTH_LONG).show();
                    }

//                        if (toastSuccess) {
//                            // Open the app list after a successful pairing attempt
//                            doAppList(computer, true, false);
//                        }
//                        else {
//                            // Start polling again if we're still in the foreground
//                            startComputerUpdates();
//                        }
                });
            }
        }).start();
    }

    protected void updateSelectedFromView() {
        commonUpdateSelectedFromView();

        if (selected == null) {
            return;
        }
        try {
            selected.setPort(Integer.parseInt(portText.getText().toString()));
            selected.setSshPort(Integer.parseInt(sshPort.getText().toString()));
        } catch (NumberFormatException nfe) {
        }

        selected.setNickname(nickText.getText().toString());
        selected.setSshServer(sshServer.getText().toString());
        selected.setSshUser(sshUser.getText().toString());

        // If we are using an SSH key, then the ssh password box is used
        // for the key pass-phrase instead.
        selected.setUseSshPubKey(checkboxUseSshPubkey.isChecked());
        selected.setRdpDomain(rdpDomain.getText().toString());
        selected.setRdpResType(spinnerRdpGeometry.getSelectedItemPosition());
        try {
            selected.setRdpWidth(Integer.parseInt(rdpWidth.getText().toString()));
            selected.setRdpHeight(Integer.parseInt(rdpHeight.getText().toString()));
        } catch (NumberFormatException nfe) {
        }
        setRemoteSoundTypeFromView(groupRemoteSoundType);
        selected.setEnableRecording(checkboxEnableRecording.isChecked());
        selected.setEnableGesture(checkboxEnableGesture.isChecked());
        selected.setConsoleMode(checkboxConsoleMode.isChecked());
//        selected.setRedirectSdCard(checkboxRedirectSdCard.isChecked());
        selected.setRemoteFx(checkboxRemoteFx.isChecked());
        selected.setDesktopBackground(checkboxDesktopBackground.isChecked());
        selected.setFontSmoothing(checkboxFontSmoothing.isChecked());
        selected.setDesktopComposition(checkboxDesktopComposition.isChecked());
        selected.setWindowContents(checkboxWindowContents.isChecked());
        selected.setMenuAnimation(checkboxMenuAnimation.isChecked());
        selected.setVisualStyles(checkboxVisualStyles.isChecked());
        selected.setEnableGfx(checkboxEnableGfx.isChecked());
        selected.setEnableGfxH264(checkboxEnableGfxH264.isChecked());

        selected.setUserName(textUsername.getText().toString());
        selected.setPassword(textPassword.getText().toString());

        selected.setKeepPassword(checkboxKeepPassword.isChecked());
        selected.setUseDpadAsArrows(checkboxUseDpadAsArrows.isChecked());
        selected.setRotateDpad(checkboxRotateDpad.isChecked());
        selected.setUseLastPositionToolbar(checkboxUseLastPositionToolbar.isChecked());
        selected.setPreferSendingUnicode(checkboxPreferSendingUnicode.isChecked());
        // TODO: Reinstate Color model spinner but for RDP settings.
        //selected.setColorModel(((COLORMODEL)colorSpinner.getSelectedItem()).nameString());
    }

    /**
     * Automatically linked with android:onClick in the layout.
     * @param view
     */
    public void toggleEnableRecording(View view) {
        CheckBox b = (CheckBox) view;
        Activity a = this;

        if (PermissionsManager.hasPermission(a, PermissionGroups.RECORD_AND_MODIFY_AUDIO)) {
            selected.setEnableRecording(b.isChecked());
            return;
        }

        // reqest for recoding permission
        android.app.AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(getString(R.string.request_recording_parameter_title));
        alertDialogBuilder.setMessage(getString(R.string.request_recording_parameter_description));

        alertDialogBuilder.setPositiveButton(getText(R.string.ok), (dialog, which) -> {
            PermissionsManager.requestPermissions(a, PermissionGroups.RECORD_AND_MODIFY_AUDIO, true);
        });

        alertDialogBuilder.create().show();
    }

    public void toggleEnableGesture(View view) {
        CheckBox b = (CheckBox) view;
        selected.setEnableGesture(b.isChecked());
    }

    public void editGesture(View view) {
        Intent intent = new Intent(this, GestureEditorActivity.class);
        selected.save(this);
        intent.putExtra("connId", selected.getId());
        startActivity(intent);
    }

    public void toggleEnableStorageRedirect(View view) {
        CheckBox b = (CheckBox) view;
        PermissionsManager.requestPermissions(this, PermissionGroups.EXTERNAL_STORAGE_MANAGEMENT, true);

        selected.setRedirectSdCard(b.isChecked());
    }

    /**
     * Automatically linked with android:onClick in the layout.
     * @param view
     */
    public void remoteSoundTypeToggled(View view) {
//        if (Utils.isFree(this)) {
//            IntroTextDialog.showIntroTextIfNecessary(this, database, true);
//        }
    }

    /**
     * Sets the remote sound type in the settings from the specified parameter.
     * @param view
     */
    public void setRemoteSoundTypeFromView(View view) {
        RadioGroup g = (RadioGroup) view;
//        if (Utils.isFree(this)) {
//            IntroTextDialog.showIntroTextIfNecessary(this, database, true);
//            g.check(R.id.radioRemoteSoundDisabled);
//        }

        int id = g.getCheckedRadioButtonId();
        int soundType = Constants.REMOTE_SOUND_DISABLED;
        if (id == R.id.radioRemoteSoundOnServer) {
            soundType = Constants.REMOTE_SOUND_ON_SERVER;
        } else if (id == R.id.radioRemoteSoundOnDevice) {
            soundType = Constants.REMOTE_SOUND_ON_DEVICE;
        }
        selected.setRemoteSoundType(soundType);
    }

    public void setRemoteSoundTypeFromSettings(int type) {
        type = Constants.REMOTE_SOUND_ON_DEVICE;

        int id = 0;
        switch (type) {
            case Constants.REMOTE_SOUND_DISABLED:
                id = R.id.radioRemoteSoundDisabled;
                break;
            case Constants.REMOTE_SOUND_ON_DEVICE:
                id = R.id.radioRemoteSoundOnDevice;
                break;
            case Constants.REMOTE_SOUND_ON_SERVER:
                id = R.id.radioRemoteSoundOnServer;
                break;
        }
        groupRemoteSoundType.check(id);
    }

    public void save(MenuItem item) {
        if (ipText.getText().length() != 0 && portText.getText().length() != 0) {
            saveConnectionAndCloseLayout();
        } else {
            Toast.makeText(this, R.string.rdp_server_empty, Toast.LENGTH_LONG).show();
        }

    }
}

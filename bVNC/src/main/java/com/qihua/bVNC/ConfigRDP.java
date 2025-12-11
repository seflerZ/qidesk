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
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;

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
import com.undatech.opaque.Connection;
import com.undatech.opaque.util.ConnectionLoader;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * aRDP is the Activity for setting up RDP connections.
 */
public class ConfigRDP extends MainConfiguration {
    private final static String TAG = "aRDP";
    private EditText sshServer;
    private EditText sshPort;
    private EditText sshUser;
    private EditText portText;
    private EditText textPassword;
    private Spinner spinnerRdpGeometry;
    private Spinner spinnerRdpZoomLevel;
    private Spinner spinnerGestureConfig;
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
    private List<String> rdpColorArray;
    private ArrayList<String> gestureFileArray;
    private ConnectionLoader connectionLoader;

    @Override
    public void onCreate(Bundle icicle) {
        layoutID = R.layout.main_rdp;

        super.onCreate(icicle);

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
        spinnerGestureConfig = (Spinner) findViewById(R.id.spinnerGestureConfig);
        rdpWidth = (EditText) findViewById(R.id.rdpWidth);
        rdpHeight = (EditText) findViewById(R.id.rdpHeight);

        connectionLoader = new ConnectionLoader(getApplicationContext()
                , this, false);

        Map<String, Connection> connMap = connectionLoader.loadConnectionsById();
        Map<String, Connection> nameConMap = connMap.values().stream()
                .collect(Collectors.toMap(ConfigRDP::getGestureEntryName, connection -> connection));

        gestureFileArray = new ArrayList<>(nameConMap.keySet());
        gestureFileArray.add(0, getString(R.string.gesture_new_config_file));

        // 创建适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, // 当前上下文
                R.layout.gesture_connection_item, // 列表项布局
                R.id.connectionName, // 列表项中的 TextView ID（如果使用默认布局）
                gestureFileArray // 数据
        );

        spinnerGestureConfig.setAdapter(adapter);
        spinnerGestureConfig.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view, int itemIndex, long id) {
                Connection connection = nameConMap.get(adapter.getItem(itemIndex));
                if (connection == null) {
                    return;
                }

                String fileName = connection.getId();
                selected.setGestureConfig(fileName);
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });

        List<String> rdpGeometryArray
                = Arrays.asList(getResources().getStringArray(R.array.rdp_geometry));
        // 创建适配器
        ArrayAdapter<String> geoAdapter = new ArrayAdapter<>(
                this, // 当前上下文
                R.layout.gesture_connection_item, // 列表项布局
                R.id.connectionName, // 列表项中的 TextView ID（如果使用默认布局）
                rdpGeometryArray // 数据
        );
        spinnerRdpGeometry.setAdapter(geoAdapter);

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

        List<String> rdpZoomLevelArray
                = Arrays.asList(getResources().getStringArray(R.array.rdp_zoom_level));
        // 创建适配器
        ArrayAdapter<String> zoomAdapter = new ArrayAdapter<>(
                this, // 当前上下文
                R.layout.gesture_connection_item, // 列表项布局
                R.id.connectionName, // 列表项中的 TextView ID（如果使用默认布局）
                rdpZoomLevelArray // 数据
        );
        spinnerRdpZoomLevel.setAdapter(zoomAdapter);

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

        List<String> connTypes = Arrays.asList(getResources().getStringArray(R.array.rdp_connection_type));
        ArrayAdapter<String> connAdapter = new ThemedArrayAdapter<>(
                getApplicationContext(), // 当前上下文
                R.layout.large_text_spinner_list,
                connTypes // 数据
        );
        connAdapter.setDropDownViewResource(R.layout.large_text_spinner_list_dropdown);

        groupRemoteSoundType = (RadioGroup) findViewById(R.id.groupRemoteSoundType);
        checkboxEnableRecording = (CheckBox) findViewById(R.id.checkboxEnableRecording);
        checkboxConsoleMode = (CheckBox) findViewById(R.id.checkboxConsoleMode);
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

        setVisibilityOfSshWidgets(View.GONE);
        rdpDomain.setVisibility(View.VISIBLE);

        findViewById(R.id.textPASSWORD).setVisibility(View.VISIBLE);
        findViewById(R.id.checkboxKeepPassword).setVisibility(View.VISIBLE);
        findViewById(R.id.textUsername).setVisibility(View.VISIBLE);
        nickText.setEnabled(true);
        nickText.setHint(getString(R.string.nickname_caption_hint));

        findViewById(R.id.geometryGroup).setVisibility(View.VISIBLE);
        findViewById(R.id.checkboxEnableRecording).setVisibility(View.VISIBLE);
        findViewById(R.id.textDescriptGeom).setVisibility(View.VISIBLE);

        if (ipText.getText().length() <= 0) {
            portText.setText("3389");
        }
    }

    @NonNull
    private static String getGestureEntryName(Connection connection) {
        String nickName = connection.getNickname();
        if (nickName != null && !nickName.isEmpty()) {
            return nickName;
        }

        return connection.getUserName() + "@" + connection.getAddress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /**
     * Enables and disables the EditText boxes for width and height of remote desktop.
     */
    private void setRemoteWidthAndHeight() {
        if (selected.getRdpResType() == Constants.RDP_GEOM_SELECT_CUSTOM) {
            rdpWidth.setEnabled(true);
            rdpHeight.setEnabled(true);
        } else {
            rdpWidth.setEnabled(false);
            rdpHeight.setEnabled(false);
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
        spinnerGestureConfig.setSelection(convert2GestureIndex(selected));
        rdpWidth.setText(String.format(Locale.CHINA, "%d", selected.getRdpWidth()));
        rdpHeight.setText(String.format(Locale.CHINA, "%d", selected.getRdpHeight()));
        setRemoteWidthAndHeight();
        setRemoteSoundTypeFromSettings(selected.getRemoteSoundType());
        checkboxEnableRecording.setChecked(selected.getEnableRecording());
        checkboxEnableGesture.setChecked(selected.getEnableGesture());
        checkboxConsoleMode.setChecked(selected.getConsoleMode());
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
    }

    private int convert2GestureIndex(ConnectionBean selected) {
        Connection connection = connectionLoader.getConnectionById(selected.getGestureConfig());
        if (connection != null) {
            selected = (ConnectionBean) connection;
        }

        String legacyGestureName = getGestureEntryName(selected);
        if (gestureFileArray.contains(legacyGestureName)) {
            return gestureFileArray.indexOf(legacyGestureName);
        }

        return 0;
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

    protected void updateSelectedFromView() {
        commonUpdateSelectedFromView();

        if (selected == null) {
            return;
        }
        try {
            selected.setPort(Integer.parseInt(portText.getText().toString()));
            selected.setSshPort(Integer.parseInt(sshPort.getText().toString()));
        } catch (NumberFormatException ignored) {
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
        } catch (NumberFormatException ignored) {
        }

        setRemoteSoundTypeFromView(groupRemoteSoundType);
        selected.setEnableRecording(checkboxEnableRecording.isChecked());
        selected.setEnableGesture(checkboxEnableGesture.isChecked());
        selected.setConsoleMode(checkboxConsoleMode.isChecked());
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
     *
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

        String connId = selected.getGestureConfig();
        if (connId.isEmpty()) {
            connId = selected.getId();
        }

        intent.putExtra("connId", connId);
        startActivity(intent);
    }

    /**
     * Automatically linked with android:onClick in the layout.
     *
     * @param view
     */
    public void remoteSoundTypeToggled(View view) {
    }

    /**
     * Sets the remote sound type in the settings from the specified parameter.
     *
     * @param view
     */
    public void setRemoteSoundTypeFromView(View view) {
        RadioGroup g = (RadioGroup) view;

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

    private static class ThemedArrayAdapter<T> extends ArrayAdapter<T> {

        public ThemedArrayAdapter(Context context, int layout, List<T> objects) {
            super(context, layout, objects);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            if (view instanceof TextView) {
                TextView textView = (TextView) view;
                int nightModeFlags = getContext().getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    textView.setTextColor(Color.WHITE);
                } else {
                    textView.setTextColor(Color.BLACK);
                }
            }
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View view = super.getDropDownView(position, convertView, parent);
            if (view instanceof TextView) {
                TextView textView = (TextView) view;
                int nightModeFlags = getContext().getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    textView.setTextColor(Color.WHITE);
                } else {
                    textView.setTextColor(Color.BLACK);
                }
            }
            return view;
        }
    }
}

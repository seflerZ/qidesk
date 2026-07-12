/**
 * Copyright (C) 2013- Iordan Iordanov
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
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


package com.undatech.opaque;

import static com.qihua.bVNC.Utils.createMainScreenDialog;
import static com.qihua.bVNC.Utils.setClipboard;
import static com.qihua.bVNC.Utils.startUriIntent;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.qihua.bVNC.App;
import com.qihua.bVNC.ConnectionBean;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.Database;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.dialogs.GetTextFragment;
import com.qihua.bVNC.dialogs.ImportExportDialog;
import com.qihua.bVNC.dialogs.IntroTextDialog;
import com.qihua.bVNC.dialogs.RateOrShareFragment;
import com.qihua.bVNC.gesture.GestureImportExportUtil;
import com.qihua.util.MasterPasswordDelegate;
import com.undatech.opaque.util.ConnectionLoader;
import com.undatech.opaque.util.FileUtils;
import com.undatech.opaque.util.GeneralUtils;
import com.undatech.opaque.util.LogcatReader;
import com.qihua.bVNC.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConnectionGridActivity extends AppCompatActivity implements GetTextFragment.OnFragmentDismissedListener {
    private static String TAG = "ConnectionGridActivity";
    protected Database database;
    protected boolean isStarting = true;
    FragmentManager fragmentManager = getSupportFragmentManager();
    GetTextFragment getPassword = null;
    GetTextFragment getNewPassword = null;
    private Context appContext;
    private GridView gridView;
    private EditText search;
    private boolean isConnecting = false;
    private boolean togglingMasterPassword = false;
    private AppCompatImageButton addNewConnection = null;

    private AppCompatImageButton editDefaultSettings = null;

    private RateOrShareFragment rateOrShareFragment = new RateOrShareFragment();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

//        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setTitle(R.string.my_connections);

        appContext = getApplicationContext();
        setContentView(R.layout.grid_view_activity);

//        View decorView = getWindow().getDecorView();
//        int option = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
//        decorView.setSystemUiVisibility(option);

//        if (Build.VERSION.SDK_INT >= 28) {
//            WindowManager.LayoutParams params = getWindow().getAttributes();
//            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
//            getWindow().setAttributes(params);
//        }

        gridView = findViewById(R.id.gridView);
        gridView.setOnItemClickListener((parent, v, position, id) -> launchConnection(v));
        gridView.setOnItemLongClickListener((parent, v, position, id) -> {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(ConnectionGridActivity.this);
            String gridItemText = (String) ((TextView) v.findViewById(R.id.grid_item_text)).getText();
            String connID = (String) ((TextView) v.findViewById(R.id.grid_item_id)).getText();
            if (connID.equals("$NC")) {
                return true;
            }

            alertDialogBuilder.setTitle(getString(R.string.connection_edit_delete_prompt) + " " + gridItemText + " ?");

            CharSequence[] cs = {getString(R.string.connection_edit), getString(R.string.connection_delete), getString(R.string.connection_favorite)};
            if (gridItemText.contains("★")) {
                cs[2] = getString(R.string.connection_cancel_favorite);
            }

            alertDialogBuilder.setItems(cs, (dialog, item) -> {
                if (cs[item].toString().equals(getString(R.string.connection_edit))) {
                    editConnection(v);
                } else if (cs[item].toString().equals(getString(R.string.connection_delete))) {
                    deleteConnection(v);
                } else if (cs[item].toString().equals(getString(R.string.connection_favorite))) {
                    addFavConnection(v);
                } else if (cs[item].toString().equals(getString(R.string.connection_cancel_favorite))) {
                    cancelFavConnection(v);
                }
            });
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
            return true;
        });

        search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                createAndSetLabeledImageAdapterAndNumberOfColumns();
            }
        });

        database = ((App) getApplication()).getDatabase();
        if (getPassword == null) {
            getPassword = GetTextFragment.newInstance(GetTextFragment.DIALOG_ID_GET_MASTER_PASSWORD,
                    getString(R.string.master_password_verify), this,
                    GetTextFragment.PasswordNoKeep, R.string.master_password_verify_message,
                    R.string.master_password_set_error, null, null, null, false);
        }
        if (getNewPassword == null) {
            getNewPassword = GetTextFragment.newInstance(GetTextFragment.DIALOG_ID_GET_MATCHING_MASTER_PASSWORDS,
                    getString(R.string.master_password_set), this,
                    GetTextFragment.MatchingPasswordTwice, R.string.master_password_set_message,
                    R.string.master_password_set_error, null, null, null, false);
        }
        FileUtils.logFilesInPrivateStorage(this);
        FileUtils.deletePrivateFileIfExisting(this, ".config/freerdp/licenses");
//        addNewConnection = findViewById(R.id.addNewConnection);
//        addNewConnection.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                addNewConnection();
//            }
//        });

        // Night mode is set in com.qihua.bVNC.App.onCreate (via
        // applyThemeModeFromPrefs) on every cold start, so it is
        // already in place before any activity is created. No need
        // to repeat the AppCompatDelegate.setDefaultNightMode call
        // here.

        editDefaultSettings = findViewById(R.id.actionEditDefaultSettings);
        editDefaultSettings.setOnClickListener(v -> editDefaultSettings(null));
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private ConnectionLoader getConnectionLoader(Context context) {
        boolean connectionsInSharedPrefs = Utils.isOpaque(context);
        return new ConnectionLoader(appContext, this, connectionsInSharedPrefs);
    }

    private void createAndSetLabeledImageAdapterAndNumberOfColumns() {
        Map<String, Connection> connectionsByPosition = getConnectionLoader(this).loadConnectionsById();

        LabeledImageApapter labeledImageApapter = new LabeledImageApapter(
                ConnectionGridActivity.this,
                connectionsByPosition,
                search.getText().toString().toLowerCase().split(" "));
        gridView.setAdapter(labeledImageApapter);
    }

    private void launchConnection(View v) {
        Utils.hideKeyboard(this, getCurrentFocus());
        android.util.Log.i(TAG, "Launch Connection");

        if (IntroTextDialog.showIntroTextIfNecessary(this, database, true)) {
            return;
        }

        ActivityManager.MemoryInfo info = Utils.getMemoryInfo(this);
        if (info.lowMemory)
            System.gc();

        isConnecting = true;
        String id = (String) ((TextView) v.findViewById(R.id.grid_item_id)).getText();

        // Treat connection with id "$NC" as add new connection button
        if (id.equals("$NC")) {
            displayConnectionTypes();
            return;
        }

        Intent intent = new Intent(ConnectionGridActivity.this, GeneralUtils.getClassByName("com.qihua.bVNC.RemoteCanvasActivity"));
        ConnectionLoader connectionLoader = getConnectionLoader(this);
        if (Utils.isOpaque(this)) {
            ConnectionSettings cs = (ConnectionSettings) connectionLoader.getConnections().get(id);
            cs.loadFromSharedPreferences(appContext);
            intent.putExtra("com.undatech.opaque.ConnectionSettings", cs);
        } else {
            ConnectionBean conn = (ConnectionBean) connectionLoader.getConnections().get(id);
            intent.putExtra(Utils.getConnectionString(appContext), conn.gen_getPersistentBundle());
        }

        startActivity(intent);
    }

    private void editConnection(View v) {
        android.util.Log.d(TAG, "Modify Connection");
        String id = (String) ((TextView) v.findViewById(R.id.grid_item_id)).getText();
        ConnectionLoader connectionLoader = getConnectionLoader(this);
        Connection conn = connectionLoader.getConnectionById(id);
        String connTypeStr = Utils.getConnectionTypeString(conn.getConnectionType());
        Intent intent = new Intent(ConnectionGridActivity.this, Utils.getConnectionSetupClass(connTypeStr));
        if (Utils.isOpaque(this)) {
            ConnectionSettings cs = (ConnectionSettings) connectionLoader.getConnections().get(id);
            intent.putExtra("com.undatech.opaque.connectionToEdit", cs.getFilename());
        } else {
            intent.putExtra("isNewConnection", false);
            intent.putExtra("connID", conn.getId());
        }
        startActivity(intent);
    }

    private void deleteConnection(View v) {
        android.util.Log.d(TAG, "Delete Connection");
        String id = (String) ((TextView) v.findViewById(R.id.grid_item_id)).getText();
        String gridItemText = (String) ((TextView) v.findViewById(R.id.grid_item_text)).getText();
        Utils.showYesNoPrompt(this, getString(R.string.delete_connection) + "?", getString(R.string.delete_connection) + " " + gridItemText + " ?",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int i) {
                        ConnectionLoader connectionLoader = getConnectionLoader(ConnectionGridActivity.this);
                        if (Utils.isOpaque(ConnectionGridActivity.this)) {

                            String newListOfConnections = new String();

                            SharedPreferences sp = appContext.getSharedPreferences("generalSettings", Context.MODE_PRIVATE);
                            String currentConnectionsStr = sp.getString("connections", null);

                            ConnectionSettings cs = (ConnectionSettings) connectionLoader.getConnections().get(id);
                            if (sp != null) {
                                String[] currentConnections = currentConnectionsStr.split(" ");
                                for (String connection : currentConnections) {
                                    if (!connection.equals(cs.getFilename())) {
                                        newListOfConnections += " " + connection;
                                    }
                                }
                                android.util.Log.d(TAG, "Deleted connection, current list: " + newListOfConnections);
                                Editor editor = sp.edit();
                                editor.putString("connections", newListOfConnections.trim());
                                editor.apply();
                                File toDelete = new File(getFilesDir() + "/" + cs.getFilename() + ".png");
                                toDelete.delete();
                            }
                        } else {
                            ConnectionBean conn = (ConnectionBean) connectionLoader.getConnections().get(id);
                            conn.Gen_delete(database.getWritableDatabase());
                            database.close();
                        }
                        onResume();
                    }
                }, null);
    }

    private void addFavConnection(View v) {
        String id = (String) ((TextView) v.findViewById(R.id.grid_item_id)).getText();

        ConnectionLoader connectionLoader = getConnectionLoader(ConnectionGridActivity.this);
        ConnectionBean conn = (ConnectionBean) connectionLoader.getConnections().get(id);
        if (conn == null) {
            return;
        }

        conn.setPriority((int) (System.currentTimeMillis() / 1000));
        conn.save(this);

        recreate();
    }

    private void cancelFavConnection(View v) {
        String id = (String) ((TextView) v.findViewById(R.id.grid_item_id)).getText();

        ConnectionLoader connectionLoader = getConnectionLoader(ConnectionGridActivity.this);
        ConnectionBean conn = (ConnectionBean) connectionLoader.getConnections().get(id);
        if (conn == null) {
            return;
        }

        conn.setPriority(0);
        conn.save(this);

        recreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume of version " + Utils.getVersionAndCode(this));
        if (Utils.querySharedPreferenceBoolean(this, Constants.masterPasswordEnabledTag)) {
            showGetTextFragment(getPassword);
        } else {
            loadSavedConnections();
            IntroTextDialog.showIntroTextIfNecessary(this, database, isStarting);
        }

        isStarting = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");
        if (database != null)
            database.close();
    }

    @Override
    protected void onResumeFragments() {
        Log.i(TAG, "onResumeFragments called");
        super.onResumeFragments();
//        System.gc();
        if (Utils.querySharedPreferenceBoolean(this, Constants.masterPasswordEnabledTag)) {
            showGetTextFragment(getPassword);
        } else {
            loadSavedConnections();
        }
    }

    private void loadSavedConnections() {
        createAndSetLabeledImageAdapterAndNumberOfColumns();
    }

    private static class ConnectionType {
        final int iconRes;
        final String title;
        final String description;
        final String type;

        ConnectionType(int iconRes, String title, String description, String type) {
            this.iconRes = iconRes;
            this.title = title;
            this.description = description;
            this.type = type;
        }
    }

    private void displayConnectionTypes() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.select_connection_type);

        List<ConnectionType> connectionTypes = new ArrayList<>();
        connectionTypes.add(new ConnectionType(R.drawable.vnc_connection, "VNC", getString(R.string.description_vnc), "vnc"));
        connectionTypes.add(new ConnectionType(R.drawable.rdp_connection_2, "RDP", getString(R.string.description_rdp), "rdp"));
        connectionTypes.add(new ConnectionType(R.drawable.nvstream_connection, "NVStream", getString(R.string.description_nvstream), "nvstream"));
        connectionTypes.add(new ConnectionType(R.drawable.ssh_connection, "SSH", getString(R.string.description_ssh), "ssh"));

        ArrayAdapter<ConnectionType> adapter = new ArrayAdapter<ConnectionType>(this, R.layout.connection_type_list_item, connectionTypes) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.connection_type_list_item, parent, false);
                }

                ConnectionType currentType = getItem(position);

                ImageView icon = convertView.findViewById(R.id.connection_type_icon);
                TextView title = convertView.findViewById(R.id.connection_type_title);
                TextView description = convertView.findViewById(R.id.connection_type_description);

                if (currentType != null) {
                    icon.setImageResource(currentType.iconRes);
                    title.setText(currentType.title);
                    description.setText(currentType.description);
                }

                return convertView;
            }
        };

        builder.setAdapter(adapter, (dialog, which) -> {
            addNewConnection(connectionTypes.get(which).type);
        });

        builder.create().show();
    }

    /**
     * Starts a new connection.
     */
    public void addNewConnection(String type) {
        Intent intent = new Intent(ConnectionGridActivity.this,
                Utils.getConnectionSetupClass(type));
        intent.putExtra("isNewConnection", true);
        startActivity(intent);
    }

    public void addNewConnection() {
        addNewConnection("rdp");
    }


    /**
     * Linked with android:onClick to the add new connection action bar item.
     */
    public void addNewConnection(MenuItem menuItem) {
        displayConnectionTypes();
    }

    /**
     * Linked with android:onClick to the add new connection item in the activity.
     */
    public void addNewConnection(View view) {
        displayConnectionTypes();
    }

    /**
     * Linked with android:onClick to the copyLogcat action bar item.
     * @param menuItem
     */
    public void copyLogcat(MenuItem menuItem) {
        LogcatReader logcatReader = new LogcatReader();
        setClipboard(this, logcatReader.getMyLogcat(RemoteClientLibConstants.LOGCAT_MAX_LINES));
        Toast.makeText(getBaseContext(), getResources().getString(R.string.log_copied),
                Toast.LENGTH_LONG).show();
    }

    /**
     * Linked with android:onClick to the edit default settings action bar item.
     * @param menuItem
     */
    public void editDefaultSettings(MenuItem menuItem) {
        android.util.Log.d(TAG, "editDefaultSettings selected.");
        if (Utils.isOpaque(this)) {
            Intent intent = new Intent(ConnectionGridActivity.this, GeneralUtils.getClassByName("com.undatech.opaque.AdvancedSettingsActivity"));
            ConnectionSettings defaultConnection = new ConnectionSettings(RemoteClientLibConstants.DEFAULT_SETTINGS_FILE);
            defaultConnection.loadFromSharedPreferences(getApplicationContext());
            intent.putExtra("com.undatech.opaque.ConnectionSettings", defaultConnection);
            startActivityForResult(intent, RemoteClientLibConstants.DEFAULT_SETTINGS);
        } else {
            Intent intent = new Intent();
            intent.setClassName(this, "com.qihua.bVNC.GlobalPreferencesActivity");
            startActivity(intent);
        }
    }

    public void showPrivacyPolicy(MenuItem menuItem) {
        IntroTextDialog.showIntroText(this, database);
    }

    /**
     * Linked with android:onClick to share or rate action bar item.
     * @param menuItem
     */
    public void rateOrShare(MenuItem menuItem) {
        android.util.Log.d(TAG, "rateOrShare selected.");
        if (!rateOrShareFragment.isVisible()) {
            rateOrShareFragment.show(fragmentManager, "");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.grid_view_activity_actions, menu);
//        inflater.inflate(R.menu.input_mode_menu_item, menu);
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * This function is used to retrieve data returned by activities started with startActivityForResult.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        android.util.Log.i(TAG, "onActivityResult");

        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case RemoteClientLibConstants.DEFAULT_SETTINGS:
                if (resultCode == Activity.RESULT_OK) {
                    Bundle b = data.getExtras();
                    ConnectionSettings defaultSettings = (ConnectionSettings) b.get("com.undatech.opaque.ConnectionSettings");
                    defaultSettings.saveToSharedPreferences(this);
                } else {
                    android.util.Log.i(TAG, "Error during AdvancedSettingsActivity.");
                }
                break;
            case RemoteClientLibConstants.IMPORT_SETTINGS_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    if (data != null && data.getData() != null) {
                        ContentResolver resolver = getContentResolver();
                        boolean connectionsInSharedPrefs = Utils.isOpaque(this);
                        InputStream in = FileUtils.getInputStreamFromUri(resolver, data.getData());

                        // Read all bytes first to check format and support multiple reads
                        byte[] allBytes;
                        try {
                            allBytes = readAllBytes(in);
                        } catch (IOException e) {
                            android.util.Log.e(TAG, "Error reading input stream", e);
                            break;
                        }

                        // Check if it's a ZIP file (starts with PK magic bytes)
                        boolean isZip = (allBytes.length >= 2 && allBytes[0] == 0x50 && allBytes[1] == 0x4B);

                        if (isZip) {
                            // It's a ZIP file - handle gestures
                            android.util.Log.i(TAG, "Detected ZIP format with gestures");
                            ByteArrayInputStream zipStream = new ByteArrayInputStream(allBytes);

                            if (connectionsInSharedPrefs) {
                                // For Opaque: IDs are filenames, map directly
                                SharedPreferences sp = getSharedPreferences("generalSettings", Context.MODE_PRIVATE);
                                String oldConnections = sp.getString("connections", "");
                                String[] oldConnIds = oldConnections != null ? oldConnections.trim().split("\\s+") : new String[0];

                                // Import connection data and extract gestures
                                InputStream connDataStream;
                                try {
                                    Map<String, String> oldToNewIdMap = new HashMap<>();
                                    // For Opaque, filenames are preserved, so oldId = newId
                                    for (String id : oldConnIds) {
                                        oldToNewIdMap.put(id, id);
                                    }
                                    connDataStream = GestureImportExportUtil.importGesturesAndConnections(
                                            this, oldToNewIdMap, "connections.json", zipStream);
                                } catch (IOException e) {
                                    android.util.Log.e(TAG, "Error importing gestures", e);
                                    connDataStream = zipStream;
                                }
                                ConnectionSettings.importSettingsFromJsonToSharedPrefs(connDataStream, this);
                            } else {
                                // For bVNC: need to map old IDs to new IDs by content matching
                                try {
                                    // Get existing connections before import for matching
                                    ConnectionLoader loader = getConnectionLoader(this);
                                    Map<String, Connection> existingBefore = loader.loadConnectionsById();

                                    // First extract ZIP to temp dir and import connections
                                    File tempDir = new File(this.getCacheDir(), "gesture_import_temp");
                                    if (tempDir.exists()) {
                                        deleteDirectoryRecursively(tempDir);
                                    }
                                    tempDir.mkdirs();

                                    // Extract ZIP
                                    zipStream = new ByteArrayInputStream(allBytes);
                                    Map<String, GestureImportExportUtil.GestureFiles> manifest =
                                            GestureImportExportUtil.extractZipToDir(zipStream, tempDir);

                                    // Import connections from the extracted XML
                                    File connXmlFile = new File(tempDir, "connections.xml");
                                    FileInputStream connXmlStream = new FileInputStream(connXmlFile);
                                    Utils.importSettingsFromXml(connXmlStream, database.getWritableDatabase());

                                    // Get new connections after import
                                    database.close();
                                    database = ((App) getApplication()).getDatabase();
                                    Map<String, Connection> existingAfter = loader.loadConnectionsById();

                                    // Map and copy gesture files
                                    File gesturesDir = this.getDir("gestures", Context.MODE_PRIVATE);
                                    File actionsDir = this.getDir("actions", Context.MODE_PRIVATE);

                                    List<String> orderedNewIds = new java.util.ArrayList<>(existingAfter.keySet());
                                    int manifestIndex = 0;
                                    for (Map.Entry<String, GestureImportExportUtil.GestureFiles> manifestEntry : manifest.entrySet()) {
                                        GestureImportExportUtil.GestureFiles files = manifestEntry.getValue();
                                        if (files.gestureFile == null && files.actionFile == null) continue;

                                        if (manifestIndex < orderedNewIds.size()) {
                                            String newId = orderedNewIds.get(manifestIndex);

                                            // Copy gesture file
                                            if (files.gestureFile != null) {
                                                File src = new File(tempDir, files.gestureFile);
                                                File dest = new File(gesturesDir, newId + "_gestures.dat");
                                                com.undatech.opaque.util.FileUtils.copyFile(src, dest.getPath());
                                            }

                                            // Copy action file
                                            if (files.actionFile != null) {
                                                File src = new File(tempDir, files.actionFile);
                                                File dest = new File(actionsDir, newId + "_actions.dat");
                                                com.undatech.opaque.util.FileUtils.copyFile(src, dest.getPath());
                                            }
                                            manifestIndex++;
                                        }
                                    }

                                    // Clean up temp dir
                                    deleteDirectoryRecursively(tempDir);

                                } catch (IOException e) {
                                    android.util.Log.e(TAG, "Error importing gestures for bVNC", e);
                                    Utils.importSettingsFromXml(new ByteArrayInputStream(allBytes), database.getWritableDatabase());
                                }
                            }
                        } else {
                            // Old format without gestures - process as before
                            if (connectionsInSharedPrefs) {
                                ConnectionSettings.importSettingsFromJsonToSharedPrefs(new ByteArrayInputStream(allBytes), this);
                            } else {
                                Utils.importSettingsFromXml(new ByteArrayInputStream(allBytes), database.getWritableDatabase());
                            }
                        }
                        recreate();
                    } else {
                        android.util.Log.e(TAG, "File uri not found, not importing settings");
                    }
                } else {
                    android.util.Log.e(TAG, "Error while selecting file to import settings from");
                }
                break;
            case RemoteClientLibConstants.EXPORT_SETTINGS_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    if (data != null && data.getData() != null) {
                        ContentResolver resolver = getContentResolver();
                        boolean connectionsInSharedPrefs = Utils.isOpaque(this);

                        try {
                            ConnectionLoader connectionLoader = getConnectionLoader(this);
                            Map<String, Connection> connections = connectionLoader.loadConnectionsById();
                            String[] connectionIds = connections.keySet().toArray(new String[0]);

                            if (connectionsInSharedPrefs) {
                                // For Opaque: export to byte array first, then package with gestures
                                ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
                                ConnectionSettings.exportSettingsFromSharedPrefsToJson(jsonOut, this);
                                ByteArrayInputStream jsonIn = new ByteArrayInputStream(jsonOut.toByteArray());

                                OutputStream out = FileUtils.getOutputStreamFromUri(resolver, data.getData());
                                GestureImportExportUtil.exportGesturesAndConnections(
                                        this, connectionIds, "connections.json", jsonIn, out);
                            } else {
                                // For bVNC: export to byte array first, then package with gestures
                                ByteArrayOutputStream xmlOut = new ByteArrayOutputStream();
                                Utils.exportSettingsToXml(xmlOut, database.getReadableDatabase());
                                ByteArrayInputStream xmlIn = new ByteArrayInputStream(xmlOut.toByteArray());

                                OutputStream out = FileUtils.getOutputStreamFromUri(resolver, data.getData());
                                GestureImportExportUtil.exportGesturesAndConnections(
                                        this, connectionIds, "connections.xml", xmlIn, out);
                            }
                        } catch (IOException e) {
                            android.util.Log.e(TAG, "Error exporting settings with gestures", e);
                        }
                    } else {
                        android.util.Log.e(TAG, "File uri not found, not exporting settings");
                    }
                } else {
                    android.util.Log.e(TAG, "Error while selecting file to export settings to");
                }
                break;
        }
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onMenuOpened(int, android.view.Menu)
     */
    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        android.util.Log.d(TAG, "onMenuOpened");
        return true;
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onCreateDialog(int)
     */
    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == R.layout.importexport) {
            boolean connectionsInSharedPrefs = Utils.isOpaque(this);
            return new ImportExportDialog(this, database, connectionsInSharedPrefs);
        }
        return null;
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.itemExportImport) {
            showDialog(R.layout.importexport);
        }
//        else if (itemId == R.id.itemMasterPassword) {
//            if (Utils.isFree(this)) {
        IntroTextDialog.showIntroTextIfNecessary(this, database, true);
//            } else {
//                togglingMasterPassword = true;
//                if (Utils.querySharedPreferenceBoolean(this, Constants.masterPasswordEnabledTag)) {
//                    showGetTextFragment(getPassword);
//                } else {
//                    showGetTextFragment(getNewPassword);
//                }
//            }
        return true;
    }

    public void onTextObtained(String dialogId, String[] obtainedStrings, boolean wasCancelled, boolean keep) {
        Log.i(TAG, "onTextObtained");
        handlePassword(obtainedStrings[0], wasCancelled);
    }

    public void handlePassword(String providedPassword, boolean dialogWasCancelled) {
        Log.i(TAG, "handlePassword");
        boolean loadConnections;
        MasterPasswordDelegate passwordDelegate = new MasterPasswordDelegate(this, database);
        if (togglingMasterPassword) {
            loadConnections = passwordDelegate.toggleMasterPassword(providedPassword, dialogWasCancelled);
            togglingMasterPassword = false;
        } else {
            loadConnections = passwordDelegate.checkMasterPasswordAndQuitIfWrong(providedPassword, dialogWasCancelled);
        }
        if (loadConnections) {
            removeGetPasswordFragments();
            loadSavedConnections();
        }
    }

    private void showGetTextFragment(GetTextFragment f) {
        if (!f.isVisible()) {
            removeGetPasswordFragments();
            f.setCancelable(false);
            f.show(fragmentManager, "");
        }
    }

    private void removeGetPasswordFragments() {
        if (getPassword.isAdded()) {
            FragmentTransaction tx = this.getSupportFragmentManager().beginTransaction();
            tx.remove(getPassword);
            tx.commit();
            fragmentManager.executePendingTransactions();
        }
        if (getNewPassword.isAdded()) {
            FragmentTransaction tx = this.getSupportFragmentManager().beginTransaction();
            tx.remove(getNewPassword);
            tx.commit();
            fragmentManager.executePendingTransactions();
        }
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    /**
     * Builds a mapping from old connection IDs to new connection IDs based on
     * matching connection content (address, username, etc.).
     * Only connections that have gestures (present in gestureConnIds) are mapped.
     */
    private Map<String, String> buildConnectionIdMapping(
            Map<String, Connection> before,
            Map<String, Connection> after,
            Set<String> gestureConnIds) {

        Map<String, String> mapping = new HashMap<>();

        for (String oldId : gestureConnIds) {
            Connection oldConn = before.get(oldId);
            if (oldConn == null) continue;

            // Try to find matching connection in after map by content
            for (Map.Entry<String, Connection> entry : after.entrySet()) {
                Connection newConn = entry.getValue();
                if (connectionsMatch(oldConn, newConn)) {
                    mapping.put(oldId, entry.getKey());
                    break;
                }
            }
        }

        return mapping;
    }

    /**
     * Checks if two connections match based on their content.
     */
    private boolean connectionsMatch(Connection c1, Connection c2) {
        // Match by address and username as primary indicators
        String addr1 = c1.getAddress() != null ? c1.getAddress() : "";
        String addr2 = c2.getAddress() != null ? c2.getAddress() : "";
        String user1 = c1.getUserName() != null ? c1.getUserName() : "";
        String user2 = c2.getUserName() != null ? c2.getUserName() : "";

        return addr1.equals(addr2) && user1.equals(user2);
    }

    private void deleteDirectoryRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryRecursively(child);
                }
            }
        }
        file.delete();
    }

    public void showMainScreenHelp(View item) {
        Log.d(TAG, "showMainScreenHelp: Showing main screen help.");
        createMainScreenDialog(this);
    }

    public void showSupportForum(View item) {
        startUriIntent(this, "https://groups.google.com/forum/#!forum/bvnc-ardp-aspice-opaque-remote-desktop-clients");
    }

    public void emailUs(View item) {
        startUriIntent(this, "mailto:support@morpheusly.com");
    }

    public void reportBug(View item) {
        startUriIntent(this, "https://github.com/qihua/remote-desktop-clients/issues");
    }

    public void rateApp(View item) {
        Log.d(TAG, "rateApp: Showing rate app functionality");
        Utils.showRateAppDialog(this);
    }

    public void shareApp(View item) {
        Log.d(TAG, "shareApp: Copying app link to clipboard");
        String url = Utils.getDonationPackageUrl(this);
        setClipboard(this, url);
        Toast.makeText(appContext, R.string.share_app_toast, Toast.LENGTH_LONG).show();
    }

    public void donateToProject(View item) {
        startUriIntent(this, Utils.getDonationPackageLink(this));
    }

    public void moreApps(View item) {
        startUriIntent(this, "market://search?q=pub:\"Iordan Iordanov (Undatech)\"");
    }

    public void previousVersions(View item) {
        startUriIntent(this, "https://github.com/qihua/remote-desktop-clients/releases");
    }
}

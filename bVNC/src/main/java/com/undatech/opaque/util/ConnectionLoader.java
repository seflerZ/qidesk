package com.undatech.opaque.util;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.PersistableBundle;

import com.qihua.bVNC.ConnectionBean;
import com.qihua.bVNC.Database;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.Connection;
import com.undatech.opaque.ConnectionGridActivity;
import com.undatech.opaque.ConnectionSettings;
import com.qihua.bVNC.R;

import net.sqlcipher.database.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConnectionLoader {
    private static String TAG = "ConnectionLoader";
    private Context appContext;
    private boolean connectionsInSharedPrefs;
    private Map<String, Connection> connectionsById;
    private String[] connectionPreferenceFiles;
    private int numConnections = 0;
    private Activity activity;

    public ConnectionLoader(Context appContext, Activity activity, boolean connectionsInSharedPrefs) {
        this.appContext = appContext;
        this.connectionsInSharedPrefs = connectionsInSharedPrefs;
        this.activity = activity;
        this.connectionsById = new HashMap<>();
        loadConnectionsById();
    }

    public Map<String, Connection> loadConnectionsById() {
        if (connectionsInSharedPrefs) {
            loadFromSharedPrefs();
        } else {
            loadFromDatabase();
        }
        return connectionsById;
    }

    private void loadFromDatabase() {
        Database database = new Database(this.appContext);
        SQLiteDatabase db = database.getWritableDatabase();

        ArrayList<ConnectionBean> connections = new ArrayList<ConnectionBean>();

        ConnectionBean.getAll(db, ConnectionBean.GEN_TABLE_NAME, connections, ConnectionBean.newInstance);
        Collections.sort(connections);
        numConnections = connections.size();
        if (connections.size() == 0) {
            android.util.Log.i(TAG, "No connections in the database");
        } else {
            for (int i = 0; i < connections.size(); i++) {
                Connection connection = connections.get(i);
                connection.setRuntimeId(Integer.toString(i));
                connectionsById.put(Integer.toString(i), connection);
            }
        }

        updateMenuShortcut(connections);

        database.close();
    }

    private void updateMenuShortcut(List<ConnectionBean> connections) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N_MR1) {
            return;
        }

        ShortcutManager shortcutManager = getSystemService(appContext, ShortcutManager.class);
        if (shortcutManager == null) {
            return;
        }

        // remove all allShortcuts and rebuild it
        shortcutManager.removeAllDynamicShortcuts();

        ArrayList<ShortcutInfo> allShortcuts = new ArrayList<>();
        for (ConnectionBean conn :connections) {
            if (conn.getPriority() < 1000) {
                continue;
            }



            Intent intent = new Intent(activity, GeneralUtils.getClassByName("com.qihua.bVNC.RemoteCanvasActivity"));
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra(Utils.getConnectionString(appContext), conn.gen_getPersistentBundle());

            ShortcutInfo shortcut = new ShortcutInfo.Builder(appContext, conn.getId())
                    .setShortLabel(conn.getLabel())
                    .setLongLabel(conn.getUserName() + "@" + conn.getAddress() + ":" + conn.getPort())
                    .setIcon(Icon.createWithResource(appContext, R.drawable.computer))
                    .setIntent(intent)
                    .build();

            allShortcuts.add(shortcut);
        }

        shortcutManager.addDynamicShortcuts(allShortcuts);
    }

    private void loadFromSharedPrefs() {
        SharedPreferences sp = appContext.getSharedPreferences("generalSettings", Context.MODE_PRIVATE);
        String connections = sp.getString("connections", null);
        android.util.Log.d(TAG, "Loading connections from this list: " + connections);
        if (connections != null && !connections.equals("")) {
            connectionPreferenceFiles = connections.split(" ");
            numConnections = connectionPreferenceFiles.length;
            for (int i = 0; i < numConnections; i++) {
                Connection cs = new ConnectionSettings(connectionPreferenceFiles[i]);
                cs.setRuntimeId(Integer.toString(i));
                cs.load(appContext);
                android.util.Log.d(TAG, "Adding label: " + cs.getLabel());
                connectionsById.put(Integer.toString(i), cs);
            }
        }
    }

    public boolean isConnectionsInSharedPrefs() {
        return connectionsInSharedPrefs;
    }

    public String[] getConnectionPreferenceFiles() {
        return connectionPreferenceFiles;
    }

    public int getNumConnections() {
        return numConnections;
    }

    public Map<String, Connection> getConnectionsById() {
        return connectionsById;
    }
}

package com.qihua.bVNC;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Phase 0: minimal entry point. Builds a hardcoded SSH ConnectionBean,
 * launches RemoteCanvasActivity, and finishes. No real SSH yet.
 *
 * Phase 2 replaces this with a proper configuration form (nickname / host
 * / port / username / password / keep-password), reusing MainConfiguration
 * like ConfigVNC/ConfigRDP do.
 */
public class ConfigSSH extends Activity {

    private static final String TAG = "ConfigSSH";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ConnectionBean conn = new ConnectionBean(this);
        conn.setConnectionType(Constants.CONN_TYPE_SSH);
        conn.setNickname("SSH Terminal (Phase 0)");
        conn.setAddress("127.0.0.1");
        conn.setPort(22);
        conn.setUserName("test");
        conn.setPassword("");

        Intent intent = new Intent(this, RemoteCanvasActivity.class);
        intent.putExtra(Utils.getConnectionString(this), conn.gen_getPersistentBundle());
        Log.d(TAG, "Launching RemoteCanvasActivity for SSH Phase 0");
        startActivity(intent);
        finish();
    }
}

package com.qihua.bVNC;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Phase 2: minimal SSH entry point. Builds a hardcoded ConnectionBean
 * pointing at a known SSH server, launches RemoteCanvasActivity, and
 * finishes. Phase 3 replaces this with a proper configuration form
 * (nickname / host / port / username / password / keep-password /
 * pubkey), reusing MainConfiguration like ConfigVNC/ConfigRDP do.
 *
 * <p>The default {@code sshServer = 10.0.2.2} is the host loopback as
 * seen from an Android emulator (the emulator's gateway to its host
 * OS). For real-device testing, change this to a LAN address reachable
 * from the phone before building.
 */
public class ConfigSSH extends Activity {

    private static final String TAG = "ConfigSSH";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ConnectionBean conn = new ConnectionBean(this);
        conn.setConnectionType(Constants.CONN_TYPE_SSH);
        conn.setNickname("SSH Terminal (Phase 2)");
        // Phase 2 minimum demo: hardcoded target. Real Phase 3 will let
        // the user edit this through a config form.
        //
        // 10.0.2.2 = Android emulator's view of the host machine's
        // loopback. For a real device, change sshServer to a LAN IP
        // reachable from the phone (e.g. 192.168.1.x) before building.
        conn.setSshServer("sefler.site");
        conn.setSshPort(2222);
        // SSHConnection pulls credentials from getSshUser/getSshPassword,
        // not from getUserName/getPassword (those are VNC fields). Match
        // the VNC-over-SSH plumbing so the existing SSHConnection
        // constructor reads the right things.
        conn.setSshUser("sefler");
        conn.setSshPassword("scu0643yao@#$%");
        // Also keep the VNC-side address/port in sync so any code that
        // does canvas.connection.getAddress() / getPort() gets something
        // sensible rather than the defaults.
        conn.setAddress("10.0.2.2");
        conn.setPort(22);
        conn.setUserName("root");
        conn.setPassword("");

        Intent intent = new Intent(this, RemoteCanvasActivity.class);
        intent.putExtra(Utils.getConnectionString(this), conn.gen_getPersistentBundle());
        Log.d(TAG, "Launching RemoteCanvasActivity for SSH Phase 2");
        startActivity(intent);
        finish();
    }
}

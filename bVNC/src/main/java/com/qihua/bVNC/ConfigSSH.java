package com.qihua.bVNC;

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

/**
 * SSH terminal connection configuration (Phase 3.1).
 *
 * <p>Mirrors the shape of {@link ConfigVNC} / {@link ConfigRDP}: extends
 * {@link MainConfiguration}, uses the SSH-only layout {@code R.layout.main_ssh},
 * reads/writes VNC-style fields ({@code getAddress/getPort/getUserName/getPassword})
 * and explicitly clears the {@code SshXxx} fields so an SSH terminal profile
 * does not pollute the "VNC-over-SSH tunnel" field semantics on the same
 * {@link ConnectionBean}.
 *
 * <p>Phase 3.1 only supports password authentication. Pubkey + keyboard
 * interactive + full host-key fingerprint UI are deferred to Phase 3.6.
 */
public class ConfigSSH extends MainConfiguration {
    private static final String TAG = "ConfigSSH";
    private static final int DEFAULT_SSH_PORT = 22;

    private EditText sshServer;
    private EditText sshPort;
    private EditText sshUser;
    private EditText sshPassword;
    private CheckBox checkboxKeepSshPass;

    @Override
    public void onCreate(Bundle icicle) {
        // Use the SSH-only layout. Android resource resolution picks
        // layout-large/main_ssh.xml on large screens automatically.
        layoutID = R.layout.main_ssh;
        super.onCreate(icicle);

        sshServer = (EditText) findViewById(R.id.sshServer);
        sshPort = (EditText) findViewById(R.id.sshPort);
        sshUser = (EditText) findViewById(R.id.sshUser);
        sshPassword = (EditText) findViewById(R.id.sshPassword);
        checkboxKeepSshPass = (CheckBox) findViewById(R.id.checkboxKeepSshPass);

        // main_ssh.xml already has all the SSH group containers with
        // visibility="visible" by default, so we do NOT call
        // setVisibilityOfSshWidgets() (that helper toggles VNC-mode visibility
        // defaults which would be a no-op here anyway).
        Log.d(TAG, "onCreate done, isNewConnection=" + isNewConnection);
    }

    @Override
    protected void updateViewFromConnection() {
        if (selected == null) return;
        // Loads KeepSshPassword etc. and sshPassword/sshPassphrase from prefs.
        super.commonUpdateViewFromSelected();

        // SSH terminal uses VNC-style fields (Address/Port/UserName/Password).
        sshServer.setText(selected.getAddress());
        // New connections always default to port 22, even if ConnectionBean's
        // shared default left a stale RDP/VNC port (e.g. 3389) in the field.
        // Editing an existing SSH profile keeps the user-typed port.
        int port = isNewConnection ? 0 : selected.getPort();
        sshPort.setText(Integer.toString(port == 0 ? DEFAULT_SSH_PORT : port));
        sshUser.setText(selected.getUserName());
        if (selected.getKeepPassword() || !selected.getPassword().isEmpty()) {
            sshPassword.setText(selected.getPassword());
        } else {
            sshPassword.setText("");
        }
        checkboxKeepSshPass.setChecked(selected.getKeepPassword());

        selectedConnType = Constants.CONN_TYPE_SSH;
        nickText.setText(selected.getNickname());
    }

    @Override
    protected void updateConnectionFromView() {
        super.commonUpdateSelectedFromView();
        if (selected == null) return;

        try {
            selected.setPort(Integer.parseInt(sshPort.getText().toString()));
        } catch (NumberFormatException nfe) {
            selected.setPort(DEFAULT_SSH_PORT);
        }

        // VNC-style fields for the SSH terminal profile.
        selected.setAddress(sshServer.getText().toString());
        selected.setUserName(sshUser.getText().toString());
        selected.setPassword(sshPassword.getText().toString());
        selected.setKeepPassword(checkboxKeepSshPass.isChecked());

        // Explicitly clear SSH-tunnel fields so an SSH terminal profile
        // does not pollute the "VNC-over-SSH tunnel" semantics.
        selected.setSshServer("");
        selected.setSshPort(0);
        selected.setSshUser("");
        selected.setSshPassword("");
        selected.setSshPassPhrase("");
        selected.setSshHostKey("");
        selected.setSshPubKey("");
        selected.setSshPrivKey("");
        selected.setUseSshPubKey(false);

        selected.setNickname(nickText.getText().toString());
    }

    /**
     * Wired to {@code R.id.itemSave} in the {@code connectionsetupmenu}.
     * Validates server and username are non-empty, then persists via
     * {@link MainConfiguration#saveConnectionAndCloseLayout()}.
     */
    public void save(MenuItem item) {
        if (sshServer.getText().length() == 0) {
            Toast.makeText(this, R.string.ssh_server_empty, Toast.LENGTH_LONG).show();
            return;
        }
        if (sshUser.getText().length() == 0) {
            Toast.makeText(this, R.string.ssh_user_empty, Toast.LENGTH_LONG).show();
            return;
        }
        saveConnectionAndCloseLayout();
    }
}

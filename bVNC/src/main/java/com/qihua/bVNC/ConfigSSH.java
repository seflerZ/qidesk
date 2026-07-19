package com.qihua.bVNC;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.qihua.pubkeygenerator.PubkeyUtils;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * SSH terminal connection configuration (Phase 3.1, pubkey auth added Phase 3.6).
 *
 * <p>Mirrors the shape of {@link ConfigVNC} / {@link ConfigRDP}: extends
 * {@link MainConfiguration}, uses the SSH-only layout {@code R.layout.main_ssh},
 * reads/writes VNC-style fields ({@code getAddress/getPort/getUserName/getPassword})
 * and explicitly clears the {@code SshXxx} fields so an SSH terminal profile
 * does not pollute the "VNC-over-SSH tunnel" field semantics on the same
 * {@link ConnectionBean}.
 *
 * <p>Phase 3.6 adds a Manage Key button + passphrase field + Use Key checkbox
 * (wired through the {@code pubkeyGenerator} module via
 * {@link com.qihua.pubkeygenerator.GeneratePubkeyActivity}). The base class's
 * {@code onActivityResult} already handles {@code Constants.ACTIVITY_GEN_KEY}
 * and writes the returned {@code SshPrivKey}/{@code SshPubKey} back to
 * {@code selected} — we only need to surface the new fields into our
 * additional widgets so the toggle state survives a save/reload.
 *
 * <p>A Copy Public Key button lives below the Manage Key button. It reads
 * the selected connection's {@code SshPrivKey} (PKCS#8 base64, possibly
 * passphrase-encrypted), decrypts via {@code PubkeyUtils.decryptAndRecoverKeyPair}
 * using the passphrase from the sshPassphrase field, converts the resulting
 * public key to OpenSSH single-line format
 * ({@code ssh-rsa AAAA...== comment}), and pushes it to the system
 * clipboard with a brief toast. The user can then paste it into the
 * server's {@code ~/.ssh/authorized_keys} file.
 *
 * <p>Pubkey vs password priority — when {@code useSshPubKey && SshPrivKey
 * is set}, {@code SshConnectionInitializer.doConnect()} attempts pubkey auth
 * first and only falls back to password if the server rejects it. The
 * passphrase, if any, is decrypted via {@code PubkeyUtils.decryptAndRecoverKeyPair}
 * the same way {@code SSHConnection} does for the VNC-over-SSH tunnel.
 */
public class ConfigSSH extends MainConfiguration {
    private static final String TAG = "ConfigSSH";
    private static final int DEFAULT_SSH_PORT = 22;

    private EditText sshServer;
    private EditText sshPort;
    private EditText sshUser;
    private EditText sshPassword;
    private EditText sshPassphrase;
    private CheckBox checkboxKeepSshPass;
    private CheckBox checkboxUseSshPubkey;
    private Button buttonGeneratePubkey;
    private Button buttonImportPrivateKey;
    private Button buttonCopyPublicKey;

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
        sshPassphrase = (EditText) findViewById(R.id.sshPassphrase);
        checkboxKeepSshPass = (CheckBox) findViewById(R.id.checkboxKeepSshPass);
        checkboxUseSshPubkey = (CheckBox) findViewById(R.id.checkboxUseSshPubkey);
        buttonGeneratePubkey = (Button) findViewById(R.id.buttonGeneratePubkey);
        buttonImportPrivateKey = (Button) findViewById(R.id.buttonImportPrivateKey);
        buttonCopyPublicKey = (Button) findViewById(R.id.buttonCopyPublicKey);

        if (buttonImportPrivateKey != null) {
            buttonImportPrivateKey.setOnClickListener(v -> showImportPrivateKeyDialog());
        }
        if (buttonCopyPublicKey != null) {
            buttonCopyPublicKey.setOnClickListener(v -> copyPublicKeyToClipboard());
        }

        // Toggle the password/passphrase fields based on Use Key so the
        // user sees the right inputs for the chosen auth method. We do not
        // blank the fields — they may still hold values the user wants
        // preserved (e.g. password as fallback, or an old passphrase when
        // toggling Use Key off again).
        if (checkboxUseSshPubkey != null && sshPassword != null && sshPassphrase != null) {
            checkboxUseSshPubkey.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sshPassword.setEnabled(!isChecked);
                sshPassphrase.setEnabled(isChecked);
            });
            // Match the initial state to whichever is currently checked.
            boolean keyMode = checkboxUseSshPubkey.isChecked();
            sshPassword.setEnabled(!keyMode);
            sshPassphrase.setEnabled(keyMode);
        }

        Log.d(TAG, "onCreate done, isNewConnection=" + isNewConnection);
    }

    @Override
    protected void updateViewFromConnection() {
        if (selected == null) return;
        // Loads KeepSshPassword, sshPassphrase (via the base class's
        // sshPassphrase field lookup) and common fields from prefs.
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

        // Phase 3.6 — pubkey auth UI. The base class's commonUpdateViewFromSelected
        // already reads SshPassPhrase into the sshPassphrase EditText, but it
        // does not know about the Use-Key toggle, so we wire it here.
        if (checkboxUseSshPubkey != null) {
            checkboxUseSshPubkey.setChecked(selected.getUseSshPubKey());
            if (sshPassword != null && sshPassphrase != null) {
                boolean keyMode = checkboxUseSshPubkey.isChecked();
                sshPassword.setEnabled(!keyMode);
                sshPassphrase.setEnabled(keyMode);
            }
        }

        // Copy Public Key is only meaningful if a key has been generated
        // or imported. We always know whether the bean's SshPrivKey is
        // populated, so reflect that in the button's enabled state. The
        // user's passphrase isn't read here — they type it in on demand
        // at copy-time, never persisted.
        String priv = selected.getSshPrivKey();
        boolean hasKey = priv != null && !priv.isEmpty();
        if (buttonCopyPublicKey != null) {
            buttonCopyPublicKey.setEnabled(hasKey);
        }

        // Phase 3.6 — the Passphrase field only makes sense when the
        // saved key actually has a passphrase. Keys generated by the
        // in-app Generate flow default to no passphrase (empty), and
        // showing an empty password-style EditText in that case reads
        // as "you still need to fill this in" — confusing, because the
        // user never chose a passphrase. Hide the field entirely when
        // there's nothing to show; it reappears automatically once a
        // passphrase-protected key is generated or imported.
        if (sshPassphrase != null) {
            String pass = selected.getSshPassPhrase();
            boolean hasPassphrase = pass != null && !pass.isEmpty();
            sshPassphrase.setVisibility(hasPassphrase ? View.VISIBLE : View.GONE);
        }

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

        if (checkboxUseSshPubkey != null) {
            selected.setUseSshPubKey(checkboxUseSshPubkey.isChecked());
        }

        // Explicitly clear SSH-tunnel fields so an SSH terminal profile
        // does not pollute the "VNC-over-SSH tunnel" semantics.
        selected.setSshServer("");
        selected.setSshPort(0);
        selected.setSshUser("");
        selected.setSshPassword("");
        selected.setSshHostKey("");

        // Phase 3.6: SshPubKey/SshPrivKey are the generated-key fields,
        // shared between VNC-over-SSH-tunnel and the SSH terminal. We
        // intentionally preserve them — the generator activity writes
        // them via MainConfiguration.onActivityResult and they need
        // to survive across saves for the next connection. Don't blank
        // them like the rest of the SshXxx tunnel fields.

        selected.setNickname(nickText.getText().toString());
    }

    /**
     * Show a small dialog asking the user to paste their externally-
     * generated private key. Required for users who already have a
     * key-pair (e.g. created via {@code ssh-keygen} on a desktop) and
     * don't want to re-generate through {@code GeneratePubkeyActivity}.
     *
     * <p>The key string is parsed via
     * {@link PubkeyUtils#tryImportingPemAndPkcs8(Context, String, String)}
     * which already supports unencrypted RSA/DSA/ECDSA PEM and the
     * passphrase-encrypted PEM variant. On success we persist the
     * base64-encoded PKCS#8 form to the connection bean — exactly what
     * {@code SshTerminalConnection.connectWithPubkey} consumes.
     *
     * <p>After a successful import this also flips {@code useSshPubKey}
     * on and refreshes the form so the next open shows the right state.
     */
    private void showImportPrivateKeyDialog() {
        if (selected == null) return;

        final EditText keyEdit = new EditText(this);
        keyEdit.setHint(R.string.ssh_import_privkey_hint);
        keyEdit.setMinLines(8);
        keyEdit.setMaxLines(12);
        keyEdit.setSingleLine(false);
        keyEdit.setVerticalScrollBarEnabled(true);
        keyEdit.setHorizontallyScrolling(true);
        // Pre-fill from clipboard so the user can just tap Import if the
        // key is already on the clipboard (the common "copy from email"
        // flow when migrating from ConnectBot / Termius).
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0)
                        .coerceToText(this);
                if (clip != null) {
                    String s = clip.toString();
                    if (s.contains("PRIVATE KEY")) {
                        keyEdit.setText(s);
                        keyEdit.setSelection(s.length());
                    }
                }
            }
        } catch (Throwable ignored) {
            // Clipboard reads can throw SecurityException on some Android
            // versions; never let that block the dialog.
        }

        final EditText passEdit = new EditText(this);
        passEdit.setHint(R.string.ssh_passphrase_hint);
        passEdit.setSingleLine(true);
        passEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);
        root.addView(keyEdit);
        root.addView(passEdit);

        new AlertDialog.Builder(this)
                .setTitle(R.string.ssh_import_privkey_title)
                .setView(root)
                .setPositiveButton(R.string.ssh_import_privkey_import,
                        (dialog, which) -> {
                            String pem = keyEdit.getText().toString();
                            String pass = passEdit.getText().toString();
                            try {
                                KeyPair kp = PubkeyUtils.tryImportingPemAndPkcs8(this, pem, pass);
                                if (kp == null) {
                                    Toast.makeText(this, R.string.ssh_import_privkey_failed,
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                // Persist in the same form GeneratePubkeyActivity uses.
                                PrivateKey priv = kp.getPrivate();
                                PublicKey pub = kp.getPublic();
                                String encryptedPrivB64 = Base64.encodeToString(
                                        PubkeyUtils.getEncodedPrivate(priv, pass),
                                        Base64.DEFAULT);
                                String pubB64 = Base64.encodeToString(
                                        PubkeyUtils.getEncodedPublic(pub),
                                        Base64.DEFAULT);
                                selected.setSshPrivKey(encryptedPrivB64);
                                selected.setSshPubKey(pubB64);
                                if (pass != null && !pass.isEmpty()) {
                                    selected.setSshPassPhrase(pass);
                                    if (sshPassphrase != null) {
                                        sshPassphrase.setText(pass);
                                    }
                                }
                                if (!selected.getUseSshPubKey()) {
                                    selected.setUseSshPubKey(true);
                                }
                                selected.saveAndWriteRecent(true, this);
                                Toast.makeText(this, R.string.ssh_import_privkey_ok,
                                        Toast.LENGTH_LONG).show();
                                // Refresh the form so Use Key / Passphrase /
                                // Copy Public Key state show correctly without
                                // the user navigating away and back.
                                updateViewFromConnection();
                            } catch (Exception e) {
                                Log.w(TAG, "import private key failed", e);
                                Toast.makeText(this, R.string.ssh_import_privkey_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                .setNegativeButton(R.string.ssh_import_privkey_cancel, null)
                .show();
    }

    /**
     * Click handler for the Copy Public Key button. Reads the saved
     * private key (possibly passphrase-encrypted), decrypts it via
     * PubkeyUtils, converts to OpenSSH single-line format, and pushes
     * the result to the system clipboard.
     *
     * <p>Three failure modes each map to a distinct toast so the user
     * knows whether they need to (a) tap Manage Key first, (b) re-type
     * the passphrase, or (c) look at the system clipboard — the success
     * toast mentions pasting into ~/.ssh/authorized_keys.
     */
    private void copyPublicKeyToClipboard() {
        if (selected == null) return;
        String priv = selected.getSshPrivKey();
        if (priv == null || priv.isEmpty()) {
            Toast.makeText(this, R.string.ssh_pubkey_copy_no_key, Toast.LENGTH_LONG).show();
            return;
        }
        String passphrase = sshPassphrase != null ? sshPassphrase.getText().toString() : "";
        KeyPair kp = PubkeyUtils.decryptAndRecoverKeyPair(priv, passphrase);
        if (kp == null) {
            Toast.makeText(this, R.string.ssh_pubkey_copy_wrong_passphrase, Toast.LENGTH_LONG).show();
            return;
        }
        String openSsh;
        try {
            // null nickname → "pubkeygenerator@mobiledevice" by PubkeyUtils
            openSsh = PubkeyUtils.convertToOpenSSHFormat(kp.getPublic(), null);
        } catch (Exception e) {
            Log.w(TAG, "convertToOpenSSHFormat failed", e);
            Toast.makeText(this, R.string.ssh_pubkey_copy_wrong_passphrase, Toast.LENGTH_LONG).show();
            return;
        }
        if (openSsh == null || openSsh.isEmpty()) {
            Toast.makeText(this, R.string.ssh_pubkey_copy_wrong_passphrase, Toast.LENGTH_LONG).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Log.w(TAG, "ClipboardManager unavailable");
            return;
        }
        // Android 10+ ignores getPrimaryClip() outside our own app without
        // focused UI; setting the clipboard always works from the focused
        // activity. We use ClipData (the modern API) so newer Android
        // versions retain the entry under a meaningful label.
        cm.setPrimaryClip(ClipData.newPlainText("SSH public key", openSsh));
        Toast.makeText(this, R.string.ssh_pubkey_copy_ok, Toast.LENGTH_LONG).show();
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

    /**
     * Hooks the GeneratePubkeyActivity result so the form auto-fills the
     * passphrase and toggles Use Key after a successful Generate/Import —
     * the VNC base class's {@code onActivityResult} already persists the
     * returned {@code SshPrivKey}/{@code SshPubKey}/{@code SshPassPhrase}
     * to the {@link ConnectionBean}, but it does NOT refresh the form
     * widgets. Without this override the user would see the saved values
     * re-loaded only after navigating away and back, which is exactly the
     * "Passphrase field is empty" surprise after Generate that we are
     * fixing here.
     *
     * <p>We also flip {@code useSshPubKey} on automatically — the user
     * just spent time generating a key, so they almost certainly want
     * pubkey auth for this connection. The Phase 3.6 logic in
     * {@code SshConnectionInitializer.doConnect()} requires the toggle to
     * be on before it routes through the pubkey branch.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != Constants.ACTIVITY_GEN_KEY
                || resultCode != Activity.RESULT_OK
                || data == null || data.getExtras() == null) {
            return;
        }
        Bundle b = data.getExtras();
        String pk = b.getString("PublicKey");
        if (pk == null) {
            return;
        }

        // We always end up with a key after a successful Generate or
        // Import — auto-enable the pubkey auth flag so the user doesn't
        // have to remember to tick the Use Key checkbox. Persist + refresh
        // before reading back the form so updateViewFromConnection sees
        // the new SshPassPhrase value the base class just wrote.
        if (selected != null && !selected.getUseSshPubKey()) {
            selected.setUseSshPubKey(true);
        }
        updateViewFromConnection();
    }
}

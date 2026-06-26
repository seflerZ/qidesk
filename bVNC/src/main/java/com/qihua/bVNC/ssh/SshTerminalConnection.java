package com.qihua.bVNC.ssh;

import android.util.Base64;
import android.util.Log;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.ServerHostKeyVerifier;

import java.io.IOException;

/**
 * SSH terminal connection (Phase 3.1).
 *
 * <p>Wraps a trilead {@link com.trilead.ssh2.Connection} + {@link com.trilead.ssh2.Session}
 * pair to provide a focused "connect, password-auth, start shell" surface
 * for the SSH terminal protocol. This is intentionally a separate class
 * from {@code SSHConnection} (which is the VNC-over-SSH tunnel / AutoX /
 * SecureTunnel general-purpose SSH helper); see ssh-feature-spec.md §3.1.
 *
 * <p>Phase 3.1 scope: password authentication only. Host-key verification
 * accepts any server key on the first connection (Phase 3.6 will add a
 * fingerprint dialog and KnownHosts persistence). Pubkey and
 * keyboard-interactive authentication are also deferred to Phase 3.6.
 *
 * <p>Thread model: {@link #connect} and {@link #openShell} are expected
 * to be called on a background thread (the "SSH-Connect" HandlerThread
 * owned by {@code SshConnectionInitializer}). {@link #close} and
 * {@link #resizePty} may be called from any thread.
 */
public final class SshTerminalConnection {
    private static final String TAG = "SshTerminalConnection";

    private Connection conn;
    private com.trilead.ssh2.Session session;
    private String serverHostKey;
    private String savedHostKey;
    private String host;
    private int port;

    /**
     * @param host             server hostname or IP
     * @param port             SSH port (typically 22)
     * @param savedHostKey     base64-encoded host key from the previous
     *                         successful connection (empty string on first
     *                         connection, see {@link #loadHostKey})
     */
    public SshTerminalConnection(String host, int port, String savedHostKey) {
        this.host = host;
        this.port = port;
        this.savedHostKey = savedHostKey == null ? "" : savedHostKey;
    }

    /**
     * Connect to the server, verify the host key (or accept it on first
     * connection — see the inline note below), and authenticate with the
     * given password.
     *
     * <p>Phase 3.6 plan: replace the trust-everything
     * {@link ServerHostKeyVerifier} below with one that compares the
     * current key against the {@code savedHostKey} passed to the
     * constructor (reject on mismatch), and on first connection pops a
     * dialog showing the fingerprint and lets the user Accept or
     * Reject, then persists the key back to the
     * {@code ConnectionBean.getSshHostKey()} field.
     */
    public boolean connect(String user, String password) throws IOException {
        if (conn != null) {
            throw new IllegalStateException("SshTerminalConnection.connect called twice");
        }
        conn = new Connection(host, port);

        // Phase 3.1: trust any host key. Phase 3.6 will tighten this.
        final ServerHostKeyVerifier trustingVerifier = new ServerHostKeyVerifier() {
            @Override
            public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm,
                                               byte[] serverHostKeyBytes) {
                serverHostKey = Base64.encodeToString(serverHostKeyBytes, Base64.DEFAULT);
                return true;
            }
        };

        ConnectionInfo info = conn.connect(trustingVerifier, 6000, 24000);
        Log.i(TAG, "connect: " + host + ":" + port
                + " serverHostKeyAlg=" + info.serverHostKeyAlgorithm
                + " kex=" + info.keyExchangeAlgorithm
                + " cipherC2S=" + info.clientToServerCryptoAlgorithm
                + " cipherS2C=" + info.serverToClientCryptoAlgorithm);

        return conn.authenticateWithPassword(user, password);
    }

    /**
     * Open a shell session with an xterm-256color PTY sized to the local
     * terminal grid. Mirrors the proven Phase 2 request path
     * ({@code SSHConnection.requestShellPty} + {@code startShell}):
     *   1. {@code xterm-256color} PTY first (best behavior under zsh/oh-my-zsh)
     *   2. {@code xterm} fallback if the server rejects modes
     *   3. dumb PTY as last resort
     *
     * @return the live shell session whose {@code getStdout()}/{@code getStdin()}
     *         streams should be handed to {@code SshShellChannel.setSession}
     */
    public com.trilead.ssh2.Session openShell(int cols, int rows) throws IOException {
        if (conn == null) {
            throw new IllegalStateException("openShell called before connect");
        }
        com.trilead.ssh2.Session s = conn.openSession();
        try {
            s.requestPTY("xterm-256color", cols, rows, 640, 480, null);
        } catch (IOException e) {
            Log.w(TAG, "PTY request with modes failed, falling back: " + e.getMessage());
            try {
                s.requestPTY("xterm", cols, rows, 0, 0, null);
            } catch (IOException e2) {
                Log.w(TAG, "PTY request fallback failed, starting with dumb PTY: " + e2.getMessage());
            }
        }
        s.startShell();
        this.session = s;
        return s;
    }

    /**
     * Notify the SSH server that the local terminal grid size changed
     * (e.g. after fold/unfold). No-op if the shell hasn't been opened yet.
     */
    public void resizePty(int cols, int rows) {
        com.trilead.ssh2.Session s = this.session;
        if (s == null) return;
        try {
            s.resizePTY(cols, rows, 0, 0);
            Log.i(TAG, "resizePty: " + cols + "x" + rows);
        } catch (Throwable t) {
            Log.w(TAG, "resizePty failed: " + t.getMessage());
        }
    }

    /**
     * Tear down the shell session and the underlying connection.
     * Safe to call multiple times; safe to call from any thread.
     */
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Throwable t) {
                Log.w(TAG, "session.close failed", t);
            }
            session = null;
        }
        if (conn != null) {
            try {
                conn.close();
            } catch (Throwable t) {
                Log.w(TAG, "conn.close failed", t);
            }
            conn = null;
        }
    }

    /**
     * @return the base64-encoded host key from the most recent successful
     *         {@link #connect}, or empty string if the connection has
     *         never been established. Callers should persist this back
     *         into the {@code ConnectionBean} for the next connection.
     */
    public String getCurrentHostKey() {
        return serverHostKey == null ? "" : serverHostKey;
    }

    public boolean isConnected() {
        return conn != null;
    }
}

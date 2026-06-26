package com.qihua.bVNC.ssh.libvterm;

import android.util.Log;

/**
 * Step 1 hello-world stub. Validates the NDK / CMake / JNI toolchain
 * works for this project (which has no other native build path).
 *
 * Step 3 will replace this with the full libvterm JNI bridge.
 */
public final class SshTermStateMachine {
    private static final String TAG = "SshTermStateMachine";

    static {
        try {
            System.loadLibrary("vterm");
            Log.i(TAG, "libvterm.so loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libvterm.so not found: " + e.getMessage());
            throw e;
        }
    }

    public int nativeHello() {
        return nativeHello();
    }

    private static native int nativeHello();
}

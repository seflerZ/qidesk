package com.qihua.bVNC.input;

import android.content.Context;
import android.os.Handler;

import com.undatech.opaque.RemoteConnectable;

public abstract class RemoteKeyboard extends com.undatech.opaque.input.RemoteKeyboard {
    public RemoteKeyboard(RemoteConnectable r, Context v, Handler h, boolean debugLog) {
        super(r, v, h, debugLog);
    }

    public abstract void sendMetaKey(MetaKeyBean meta);
}

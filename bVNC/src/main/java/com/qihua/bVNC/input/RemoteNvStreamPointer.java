package com.qihua.bVNC.input;

import android.os.Handler;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.RemoteConnectable;

public class RemoteNvStreamPointer extends RemotePointer {
    private static final String TAG = "RemoteNvStreamPointer";

    private final static int PTRFLAGS_HWHEEL = 0x0400;
    private final static int PTRFLAGS_WHEEL = 0x0200;
    private final static int PTRFLAGS_WHEEL_NEGATIVE = 0x0100;
    //private final static int PTRFLAGS_DOWN           = 0x8000;
    private final static int MOUSE_BUTTON_NONE = 0x0000;
    private final static int MOUSE_BUTTON_MOVE = 0x0800;
    private final static int MOUSE_BUTTON_LEFT = 0x1000;
    private final static int MOUSE_BUTTON_RIGHT = 0x2000;

    public final static int CONTACT_FLAG_DOWN = 0x0001;
    public final static int CONTACT_FLAG_UPDATE = 0x0003;
    public final static int CONTACT_FLAG_UP = 0x0002;
    public final static int CONTACT_FLAG_CANCELED = CONTACT_FLAG_UP;

    private static final int MOUSE_BUTTON_MIDDLE = 0x4000;
    private static final int MOUSE_BUTTON_SCROLL_UP = PTRFLAGS_WHEEL | 0x0058;
    private static final int MOUSE_BUTTON_SCROLL_DOWN = PTRFLAGS_WHEEL | PTRFLAGS_WHEEL_NEGATIVE | 0x00a8;
    private static final int MOUSE_BUTTON_SCROLL_LEFT = PTRFLAGS_HWHEEL | 0x0058;
    private static final int MOUSE_BUTTON_SCROLL_RIGHT = PTRFLAGS_HWHEEL | PTRFLAGS_WHEEL_NEGATIVE | 0x00a8;

    public RemoteNvStreamPointer(RemoteConnectable nvcomm, RemoteCanvas canvas, Handler handler,
                                 boolean debugLogging) {
        super(nvcomm, canvas, handler, debugLogging);
    }
    
    // 添加触摸事件方法
    public void touchDown(int x, int y, int contactId) {
        protocomm.writeTouchEvent(x, y, CONTACT_FLAG_DOWN, contactId);
    }

    public void touchUpdate(int x, int y, int contactId) {
        protocomm.writeTouchEvent(x, y, CONTACT_FLAG_UPDATE, contactId);
    }

    public void touchUp(int x, int y, int contactId) {
        protocomm.writeTouchEvent(x, y, CONTACT_FLAG_UP, contactId);
    }

    public void touchCancel(int x, int y, int contactId) {
        protocomm.writeTouchEvent(x, y, CONTACT_FLAG_CANCELED, contactId);
    }

    @Override
    public void leftButtonDown(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_LEFT | POINTER_DOWN_MASK;
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void middleButtonDown(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_MIDDLE | POINTER_DOWN_MASK;
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void rightButtonDown(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_RIGHT | POINTER_DOWN_MASK;
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void scrollUp(int x, int y, int speed, int metaState) {
        if (speed < 0) {
            pointerMask = MOUSE_BUTTON_SCROLL_UP;
        } else {
            pointerMask = PTRFLAGS_WHEEL | (speed & 0x00ff);
        }

        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void scrollDown(int x, int y, int speed, int metaState) {
        if (speed < 0) {
            pointerMask = MOUSE_BUTTON_SCROLL_DOWN;
        } else {
            pointerMask = PTRFLAGS_WHEEL | PTRFLAGS_WHEEL_NEGATIVE | (speed & 0x00ff);
        }
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void scrollLeft(int x, int y, int speed, int metaState) {
        if (speed < 0) {
            pointerMask = MOUSE_BUTTON_SCROLL_LEFT;
        } else {
            pointerMask = PTRFLAGS_HWHEEL | (speed & 0x00ff);
        }
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void scrollRight(int x, int y, int speed, int metaState) {
        if (speed < 0) {
            pointerMask = MOUSE_BUTTON_SCROLL_RIGHT;
        } else {
            pointerMask = PTRFLAGS_HWHEEL | PTRFLAGS_WHEEL_NEGATIVE | (speed & 0x00ff);
        }
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void moveMouse(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_MOVE;
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void moveMouseButtonDown(int x, int y, int metaState) {
        pointerMask |= MOUSE_BUTTON_MOVE;
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void moveMouseButtonUp(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_MOVE & ~POINTER_DOWN_MASK;
        sendPointerEvent(x, y, metaState, true);
    }

    @Override
    public void releaseButton(int x, int y, int metaState) {
        if ((pointerMask & POINTER_DOWN_MASK) == 0) {
            return;
        }

        pointerMask &= ~(POINTER_DOWN_MASK | MOUSE_BUTTON_MOVE);
        sendPointerEvent(x, y, metaState, false);
    }
}

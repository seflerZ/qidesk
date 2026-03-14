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

package com.qihua.bVNC.input;

import android.os.Handler;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.RfbConnectable;
import com.undatech.opaque.util.GeneralUtils;

public class RemoteVncPointer extends RemotePointer {
    public static final int MOUSE_BUTTON_NONE = 0;
    public static final int MOUSE_BUTTON_MOVE = 0;
    public static final int MOUSE_BUTTON_LEFT = 1;
    public static final int MOUSE_BUTTON_MIDDLE = 2;
    public static final int MOUSE_BUTTON_RIGHT = 4;
    public static final int MOUSE_BUTTON_SCROLL_UP = 8;
    public static final int MOUSE_BUTTON_SCROLL_DOWN = 16;
    public static final int MOUSE_BUTTON_SCROLL_LEFT = 32;
    public static final int MOUSE_BUTTON_SCROLL_RIGHT = 64;
    private static final String TAG = "RemotePointer";

    public RemoteVncPointer(RfbConnectable rfb, RemoteCanvas canvas, Handler handler,
                            boolean debugLogging) {
        super(rfb, canvas, handler, debugLogging);
    }

    @Override
    public void leftButtonDown(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_LEFT;
        sendPointerEvent(x, y, metaState, false);
    }

    @Override
    public void middleButtonDown(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_MIDDLE;
        sendPointerEvent(x, y, metaState, false);
    }

    @Override
    public void rightButtonDown(int x, int y, int metaState) {
        pointerMask = MOUSE_BUTTON_RIGHT;
        sendPointerEvent(x, y, metaState, false);
    }

    private long lastScrollMs = 0;

    @Override
    public void scrollUp(int x, int y, int speed, int metaState) {
        if (System.currentTimeMillis() - lastScrollMs < 200) {
            // avoid too fast scrolling
            return;
        }

        pointerMask = MOUSE_BUTTON_SCROLL_UP;
        sendPointerEvent(x, y, metaState, false);
        pointerMask = MOUSE_BUTTON_NONE;
        sendPointerEvent(x, y, metaState, false);

        lastScrollMs = System.currentTimeMillis();
    }

    @Override
    public void scrollDown(int x, int y, int speed, int metaState) {
        if (System.currentTimeMillis() - lastScrollMs < 200) {
            // avoid too fast scrolling
            return;
        }

        pointerMask = MOUSE_BUTTON_SCROLL_DOWN | POINTER_DOWN_MASK;
        sendPointerEvent(x, y, metaState, false);
        pointerMask = MOUSE_BUTTON_NONE;
        sendPointerEvent(x, y, metaState, false);

        lastScrollMs = System.currentTimeMillis();
    }

    @Override
    public void scrollLeft(int x, int y, int speed, int metaState) {
        if (System.currentTimeMillis() - lastScrollMs < 200) {
            // avoid too fast scrolling
            return;
        }

        pointerMask = MOUSE_BUTTON_SCROLL_LEFT | POINTER_DOWN_MASK;
        sendPointerEvent(x, y, metaState, false);
        pointerMask = MOUSE_BUTTON_NONE;
        sendPointerEvent(x, y, metaState, false);

        lastScrollMs = System.currentTimeMillis();
    }

    @Override
    public void scrollRight(int x, int y, int speed, int metaState) {
        if (System.currentTimeMillis() - lastScrollMs < 200) {
            // avoid too fast scrolling
            return;
        }

        pointerMask = MOUSE_BUTTON_SCROLL_RIGHT | POINTER_DOWN_MASK;
        sendPointerEvent(x, y, metaState, false);
        pointerMask = MOUSE_BUTTON_NONE;
        sendPointerEvent(x, y, metaState, false);

        lastScrollMs = System.currentTimeMillis();
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

    @Override
    public void touchDown(int x, int y, int contactId) {
        // VNC协议不支持原生触摸事件，使用鼠标左键模拟
        leftButtonDown(x, y, 0);
    }

    @Override
    public void touchUpdate(int x, int y, int contactId) {
        // VNC协议不支持原生触摸事件，使用鼠标移动模拟
        moveMouseButtonDown(x, y, 0);
    }

    @Override
    public void touchUp(int x, int y, int contactId) {
        // VNC协议不支持原生触摸事件，使用鼠标释放模拟
        releaseButton(x, y, 0);
    }

    @Override
    public void touchCancel(int x, int y, int contactId) {
        // VNC协议不支持原生触摸事件，使用鼠标释放模拟
        releaseButton(x, y, 0);
    }
}

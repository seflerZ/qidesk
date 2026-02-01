package com.qihua.bVNC.input;

import static com.undatech.opaque.util.InputUtils.isNoQwertyKbd;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.RfbConnectable;

public abstract class RemotePointer {

    public static final int POINTER_DOWN_MASK = 0x8000;
    public static float DEFAULT_SENSITIVITY = 2.5f;
    public static boolean DEFAULT_ACCELERATED = true;
    /**
     * Current state of "mouse" buttons
     */
    protected int pointerMask = 0;
    protected int prevPointerMask = 0;
    protected RemoteCanvas canvas;
    protected Context context;
    protected Handler handler;
    protected RfbConnectable protocomm;
    /**
     * Indicates where the mouse pointer is located.
     */
    protected int pointerX, pointerY;
    protected int hotspotX, hotspotY;
    protected boolean relativeEvents = false;
    protected float sensitivity = DEFAULT_SENSITIVITY;
    protected boolean accelerated = DEFAULT_ACCELERATED;
    protected boolean debugLogging = false;
    MouseScroller scroller;

    public RemotePointer(RfbConnectable protocomm, RemoteCanvas canvas, Handler handler,
                         boolean debugLogging) {
        this.protocomm = protocomm;
        this.canvas = canvas;
        this.context = canvas.getContext();
        this.handler = handler;
        //pointerX  = canvas.getImageWidth()/2;
        //pointerY  = canvas.getImageHeight()/2;
        scroller = new MouseScroller();
        this.debugLogging = debugLogging;
    }

    protected boolean shouldBeRightClick(KeyEvent e) {
        boolean result = false;
        int keyCode = e.getKeyCode();

        // If the camera button is pressed
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            result = true;
            // Or the back button is pressed
        }

        return result;
    }

    public int getX() {
        return pointerX;
    }

    public void setX(int newX) {
        pointerX = newX;
    }

    public int getY() {
        return pointerY;
    }

    public void setY(int newY) {
        pointerY = newY;
    }

    public int getHotspotX() {
        return hotspotX;
    }

    public void setHotspotX(int hotspotX) {
        this.hotspotX = hotspotX;
    }

    public int getHotspotY() {
        return hotspotY;
    }

    public void setHotspotY(int hotspotY) {
        this.hotspotY = hotspotY;
    }

    /**
     * Move mouse pointer to specified coordinates.
     */
    public void movePointer(int x, int y) {
        //android.util.Log.d("RemotePointer", "movePointer");
//        canvas.invalidateMousePosition();
        pointerX = x;
        pointerY = y;
//        canvas.invalidateMousePosition();
        moveMouseButtonUp(x, y, 0);
    }

    /**
     * If necessary move the pointer to be visible.
     */
    public void movePointerToMakeVisible() {
        //android.util.Log.d("RemotePointer", "movePointerToMakeVisible");
        if (canvas.getMouseFollowPan()) {
            int absX = canvas.getAbsX();
            int absY = canvas.getAbsY();
            int vW = canvas.getVisibleDesktopWidth();
            int vH = canvas.getVisibleDesktopHeight();
            if (pointerX < absX || pointerX >= absX + vW ||
                    pointerY < absY || pointerY >= absY + vH) {
                movePointer(absX + vW / 2, absY + vH / 2);
            }
        }
    }

    /**
     * Handles any hardware buttons designated to perform mouse events.
     */
    public boolean hardwareButtonsAsMouseEvents(int keyCode, KeyEvent e, int combinedMetastate) {
        boolean used = false;
        boolean down = (e.getAction() == KeyEvent.ACTION_DOWN) ||
                (e.getAction() == KeyEvent.ACTION_MULTIPLE);
        if (down)
            pointerMask = POINTER_DOWN_MASK;
        else
            pointerMask = 0;

        if (shouldBeRightClick(e)) {
            rightButtonDown(getX(), getY(), combinedMetastate);
            SystemClock.sleep(100);
            releaseButton(getX(), getY(), combinedMetastate);
            used = true;
        }

        return used;
    }

    abstract public void leftButtonDown(int x, int y, int metaState);

    abstract public void middleButtonDown(int x, int y, int metaState);

    abstract public void rightButtonDown(int x, int y, int metaState);

    abstract public void scrollUp(int x, int y, int speed, int metaState);

    abstract public void scrollDown(int x, int y, int speed, int metaState);

    abstract public void scrollLeft(int x, int y, int speed, int metaState);

    abstract public void scrollRight(int x, int y, int speed, int metaState);

    abstract public void releaseButton(int x, int y, int metaState);

    abstract public void moveMouse(int x, int y, int metaState);

    abstract public void moveMouseButtonDown(int x, int y, int metaState);

    abstract public void moveMouseButtonUp(int x, int y, int metaState);

    abstract public void touchDown(int x, int y, int contactId);

    abstract public void touchUpdate(int x, int y, int contactId);

    abstract public void touchCancel(int x, int y, int contactId);

    abstract public void touchUp(int x, int y, int contactId);


    public boolean isRelativeEvents() {
        return relativeEvents;
    }

    public void setRelativeEvents(boolean relativeEvents) {
        this.relativeEvents = relativeEvents;
        if (relativeEvents) {
            setSensitivity(1.0f);
            setAccelerated(false);
        } else {
            setSensitivity(DEFAULT_SENSITIVITY);
            setAccelerated(DEFAULT_ACCELERATED);
        }
    }

    public float getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public boolean isAccelerated() {
        return accelerated;
    }

    public void setAccelerated(boolean accelerated) {
        this.accelerated = accelerated;
    }

    public class MouseScroller implements Runnable {
        public int direction = 0;
        int delay = 100;

        @Override
        public void run() {
            if (direction == 0) {
                RemotePointer.this.scrollUp(pointerX, pointerY, -1, 0);
            } else {
                RemotePointer.this.scrollDown(pointerX, pointerY, -1, 0);
            }
            handler.postDelayed(this, delay);

        }
    }

}

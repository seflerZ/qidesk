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

import android.gesture.GestureOverlayView;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.view.InputDeviceCompat;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.util.GeneralUtils;
import com.qihua.bVNC.R;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Semaphore;

abstract class InputHandlerGeneric extends MyGestureDectector.SimpleOnGestureListener
        implements InputHandler, ScaleGestureDetector.OnScaleGestureListener {
    private static final String TAG = "InputHandlerGeneric";
    public static final int POINTER_SAMPLING_MS = 10;
    protected final boolean debugLogging;

    // If swipe events are registered once every baseSwipeTime miliseconds, then
    // swipeSpeed will be one. If more often, swipe-speed goes up, if less, down.
    final long baseSwipeTime = 200;
    // The minimum distance a scale event has to traverse the FIRST time before scaling starts.
    final double minScaleFactor = 0.1;
    protected MyGestureDectector gestureDetector;
    protected MyScaleGestureDetector scalingGestureDetector;
    // Handles to the RemoteCanvas view and RemoteCanvasActivity activity.
    protected RemoteCanvas canvas;
    protected RemoteCanvas touchpad;
    protected RemoteCanvasActivity activity;
    protected PanRepeater panRepeater;
    // Various drag modes in which we don't detect gestures.
    protected boolean panMode = false;
    protected boolean dragMode = false;
    protected boolean rightDragMode = false;
    protected boolean middleDragMode = false;
    protected float cumulatedY = 0;
    protected float cumulatedX = 0;

    protected float dragX, dragY;
    protected float gestureX, gestureY;
    protected float totalDragX, totalDragY;
    protected boolean singleHandedGesture = false;
    protected boolean singleHandedJustEnded = false;
    // These variables keep track of which pointers have seen ACTION_DOWN events.
    protected boolean secondPointerWasDown = false;
    protected long inertiaStartTime = 0;
    protected Thread inertiaThread;
    protected long inertiaBaseInterval = 10;
    protected boolean inertiaScrollingEnabled = true;
    protected int inertiaMetaState = 0;
    protected Semaphore inertiaSemaphore = new Semaphore(0);
    protected float lastSpeedX = 0;
    protected float lastSpeedY = 0;
    protected float lastX = 0;
    protected float lastY = 0;
    protected boolean thirdPointerWasDown = false;
    protected boolean thirdPointerGesture = false;

    protected RemotePointer pointer;
    // This is the initial "focal point" of the gesture (between the two fingers).
    float xInitialFocus;
    float yInitialFocus;
    // This is the final "focal point" of the gesture (between the two fingers).

    int lastDelta = 0;

    float xCurrentFocus;
    float yCurrentFocus;
    // These variables record whether there was a two-finger swipe performed up or down.
    boolean inSwiping = false;
    boolean inertiaSwiping = false;
    boolean scrollUp = false;
    boolean scrollDown = false;
    boolean scrollLeft = false;
    boolean scrollRight = false;
    // These variables indicate whether the dpad should be used as arrow keys
    // and whether it should be rotated.
    boolean useDpadAsArrows = false;
    boolean rotateDpad = false;
    // The variables which indicates how many scroll events to send per swipe
    // event and the maximum number to send at one time.
    long swipeSpeed = 1;
    // This is how far the swipe has to travel before a swipe event is generated.
    float startSwipeDist = 5f;
    boolean canSwipeToMove = false;
    float baseSwipeDist = 4f;
    // This is how far from the top and bottom edge to detect immersive swipe.
    float immersiveSwipeRatio = 0.09f;
    boolean immersiveSwipeY = false;
    boolean immersiveSwipeX = false;
    // Some variables indicating what kind of a gesture we're currently in or just finished.
    boolean inScrolling = false;
    boolean inScaling = false;
    boolean scalingJustFinished = false;

    // What the display density is.
    float displayDensity = 0;
    // Indicates that the next onFling will be disregarded.
    boolean disregardNextOnFling = false;
    // Queue which holds the last two MotionEvents which triggered onScroll
    float lastZoomFactor = 1;
    private long lastDragStartTime;
    Queue<Float> distXQueue;
    Queue<Float> distYQueue;
    private boolean dragHelped = false;
    private boolean canEnlarge = true;
    private boolean immersiveSwipeEnabled = true;
    protected boolean touchpadFeedback = true;

    private final View edgeRight;
    private final View edgeLeft;
    private final View edgeTop;
    private final View edgeBottom;

    InputHandlerGeneric(RemoteCanvasActivity activity, RemoteCanvas canvas, RemoteCanvas touchpad, RemotePointer pointer,
                        boolean debugLogging) {
        this.activity = activity;
        this.touchpad = touchpad;
        this.canvas = canvas;
        this.pointer = pointer;
        this.debugLogging = debugLogging;

        edgeLeft = activity.findViewById(R.id.edgeLeft);
        edgeRight = activity.findViewById(R.id.edgeRight);
        edgeTop = activity.findViewById(R.id.edgeTop);
        edgeBottom = activity.findViewById(R.id.edgeBottom);

//        useDpadAsArrows = true; //activity.getUseDpadAsArrows();
//        rotateDpad = false; //activity.getRotateDpad();

        gestureDetector = new MyGestureDectector(activity, this, null, false);
        scalingGestureDetector = new MyScaleGestureDetector(activity, this);

        gestureDetector.setOnDoubleTapListener(this);

        this.panRepeater = new PanRepeater(canvas, canvas.handler);

        displayDensity = touchpad.getDisplayDensity();

        distXQueue = new LinkedList<>();
        distYQueue = new LinkedList<>();

        touchpadFeedback = Utils.querySharedPreferenceBoolean(canvas.getContext(), Constants.touchpadFeedback);

        immersiveSwipeEnabled = Utils.querySharedPreferenceBoolean(activity.getApplicationContext()
                , Constants.touchpadEdgeWheel, true);

//        baseSwipeDist = baseSwipeDist / displayDensity;
//        startSwipeDist = startSwipeDist / displayDensity;
//        immersiveSwipeDistance = immersiveSwipeDistance / displayDensity;
//        GeneralUtils.debugLog(debugLogging, TAG, "displayDensity, baseSwipeDist, immersiveSwipeRatio: "
//                + displayDensity + " " + baseSwipeDist + " " + immersiveSwipeRatio);

        // for inertia scrolling
        inertiaThread = new Thread(() -> {
            while (true) {
                try {
                    inertiaSemaphore.acquire();
                } catch (Exception ignored) {
                    // stop immediately
                    continue;
                }

                if (lastSpeedX == 0 && lastSpeedY == 0) {
                    continue;
                }

                int speedX = (int) lastSpeedX;
                int speedY = (int) lastSpeedY;

                if (inertiaSwiping) {
                    while ((speedX != 0 || speedY != 0) && !inertiaThread.isInterrupted()) {
                        doScroll(pointer.getX(), pointer.getY(), -speedX, -speedY, inertiaMetaState);
//                        pointer.moveMouse(pointer.getX() + speedX, pointer.getY() + speedY, inertiaMetaState);

                        speedX = (int) (speedX * 0.9);
                        speedY = (int) (speedY * 0.9);

                        SystemClock.sleep(inertiaBaseInterval);
                    }
                } else {
                    while ((speedX != 0 || speedY != 0) && !inertiaThread.isInterrupted()) {
                        pointer.moveMouse(pointer.getX() + speedX, pointer.getY() + speedY, inertiaMetaState);
                        canvas.movePanToMakePointerVisible();

                        speedX = (int) (speedX * 0.9);
                        speedY = (int) (speedY * 0.9);

                        SystemClock.sleep(inertiaBaseInterval);
                    }
                }

                inertiaSwiping = false;
            }
        });

        inertiaThread.setDaemon(true);
        inertiaThread.start();
    }

    /**
     * Function to get appropriate X coordinate from motion event for this input handler.
     * @return the appropriate X coordinate.
     */
    protected int getX(MotionEvent e) {
        float scale = canvas.getZoomFactor();
        return (int) (canvas.getAbsX() + e.getX() / scale);
    }

    protected boolean doScroll(int x, int y, float distanceX, float distanceY, int meta) {
        return true;
    }

    /**
     * Function to get appropriate Y coordinate from motion event for this input handler.
     * @return the appropriate Y coordinate.
     */
    protected int getY(MotionEvent e) {
        float scale = canvas.getZoomFactor();
        return (int) (canvas.getAbsY() + (e.getY() - 1.f * canvas.getTop()) / scale);
    }

    /**
     * Computes the acceleration depending on the size of the supplied delta.
     *
     * @param delta
     * @return
     */
    protected float computeAcceleration(float delta) {
        float origSign = getSign(delta);
        delta = Math.abs(delta);
        boolean accelerated = pointer.isAccelerated();
        if (delta <= 10 * canvas.getZoomFactor()) {
            delta = delta * 0.85f;
        } else if (accelerated && delta <= 15.0f * canvas.getZoomFactor()) {
            delta = delta * 1f;
        } else if (accelerated && delta <= 30.0f * canvas.getZoomFactor()) {
            delta = delta * 1.5f;
        } else if (accelerated && delta <= 40.0f * canvas.getZoomFactor()) {
            delta = delta * 2.5f;
        } else if (accelerated && delta <= 50.0f * canvas.getZoomFactor()) {
            delta = delta * 3f;
        } else if (accelerated) {
            delta = delta * 3.5f;
        }
        return origSign * delta;
    }

    protected float computeAcceleration4mouse(float delta) {
        float origSign = getSign(delta);
        delta = Math.abs(delta);
        boolean accelerated = pointer.isAccelerated();

        if (accelerated && delta <= 60.0f * canvas.getZoomFactor()) {
            delta = delta * 1.5f;
        } else if (accelerated && delta <= 120.0f * canvas.getZoomFactor()) {
            delta = delta * 2.0f;
        } else {
            delta = delta * 2.5f;
        }

        return origSign * delta;
    }

    private long lastPointerEventTime = 0;

    /**
     * Handles actions performed by a mouse-like device.
     * @param e touch or generic motion event
     * @return
     */
    @Override
    public boolean onPointerEvent(MotionEvent e) {
        boolean used = false;
        final int action = e.getActionMasked();
        final int meta = e.getMetaState();
        final int bstate = e.getButtonState();

        float diffX = e.getX();
        float diffY = e.getY();

        if (System.currentTimeMillis() - lastPointerEventTime < POINTER_SAMPLING_MS && action == MotionEvent.ACTION_MOVE) {
            cumulatedX += diffX;
            cumulatedY += diffY;

            return true;
        }

        if (pointer.pointerY >= canvas.getImageHeight()
                && action == MotionEvent.ACTION_DOWN
                && canvas.isOutDisplay()) {
            pointer.pointerY = pointer.pointerY - 20;
            touchpad.releasePointerCapture();
            touchpad.showCursor();

            return true;
        }

        cumulatedX = 0;
        cumulatedY = 0;

        diffX += cumulatedX;
        diffY += cumulatedY;

        lastPointerEventTime = System.currentTimeMillis();

        FpsCounter fpsCounter = canvas.getFpsCounter();
        if (fpsCounter != null) {
            fpsCounter.countInput();
        }

        // position stabilize
//        if (Math.abs(diffX) / Math.abs(diffY) > 10) {
//            diffY = 0;
//        } else if (Math.abs(diffY) / Math.abs(diffX) > 10) {
//            diffX = 0;
//        }

        // Make distanceX/Y display density independent.
        float sensitivity = pointer.getSensitivity() / 2;
        int x = (int) (diffX * sensitivity + pointer.pointerX);
        int y = (int) (diffY * sensitivity + pointer.pointerY);

        switch (action) {
            // If a mouse button was pressed or mouse was moved.
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                switch (bstate) {
                    case MotionEvent.BUTTON_PRIMARY:
                        pointer.leftButtonDown(x, y, meta);

                        break;
                    case MotionEvent.BUTTON_SECONDARY:
                    case MotionEvent.BUTTON_STYLUS_PRIMARY:
                        pointer.rightButtonDown(x, y, meta);

                        break;
                    case MotionEvent.BUTTON_TERTIARY:
                    case MotionEvent.BUTTON_STYLUS_SECONDARY:
                        pointer.middleButtonDown(x, y, meta);

                        break;
                    default:
                        // move only
                        pointer.moveMouse(x, y, meta);
                        canvas.movePanToMakePointerVisible();
                }
                used = true;
                break;
            // If a mouse button was released.
            case MotionEvent.ACTION_UP:
                switch (bstate) {
                    case 0:
                        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) {
                            break;
                        }
                    case MotionEvent.BUTTON_PRIMARY:
                    case MotionEvent.BUTTON_SECONDARY:
                    case MotionEvent.BUTTON_TERTIARY:
                    case MotionEvent.BUTTON_STYLUS_PRIMARY:
                    case MotionEvent.BUTTON_STYLUS_SECONDARY:
                        pointer.releaseButton(x, y, meta);
                        used = true;
                        break;
                }
                break;
            // If the mouse wheel was scrolled.
            case MotionEvent.ACTION_SCROLL:
                float vscroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float hscroll = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                scrollDown = false;
                scrollUp = false;
                scrollRight = false;
                scrollLeft = false;
                // Determine direction and speed of scrolling.
                if (vscroll < 0) {
                    swipeSpeed = (int) (-1 * vscroll);
                    scrollDown = true;
                } else if (vscroll > 0) {
                    swipeSpeed = (int) vscroll;
                    scrollUp = true;
                } else if (hscroll < 0) {
                    swipeSpeed = (int) (-1 * hscroll);
                    scrollRight = true;
                } else if (hscroll > 0) {
                    swipeSpeed = (int) hscroll;
                    scrollLeft = true;
                } else
                    break;

                sendScrollEvents(x, y, -1, meta);
                used = true;
                break;
        }

        // if in external displaying mode and pointer reached to the bottom of the screen, release the pointer capture
//        if (action == MotionEvent.ACTION_MOVE && (canvas != touchpad)) {
//            if (pointer.getY() >= canvas.getImageHeight() - 1) {
//                touchpad.releasePointerCapture();
//            }
//        }
//        canvas.setMousePointerPosition(pointer.getX(), pointer.getY());

        return used;
    }

    /**
     * Sends scroll events with previously set direction and speed.
     * @param x
     * @param y
     * @param meta
     */
    protected void sendScrollEvents(int x, int y, int delta, int meta) {
        GeneralUtils.debugLog(debugLogging, TAG, "sendScrollEvents");

        if (scrollDown) {
            pointer.scrollDown(x, y, delta, meta);
        } else if (scrollUp) {
            pointer.scrollUp(x, y, delta, meta);
        } else if (scrollRight) {
            pointer.scrollLeft(x, y, delta, meta);
        } else if (scrollLeft) {
            pointer.scrollRight(x, y, delta, meta);
        }
    }

    /*
     * @see android.view.GestureDetector.SimpleOnGestureListener#onSingleTapConfirmed(android.view.MotionEvent)
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
//        GeneralUtils.debugLog(debugLogging, TAG, "onSingleTapConfirmed, e: " + e);

        activity.readSpecialKeysState();

        if (dragMode || rightDragMode || middleDragMode) {
            return true;
        }

        if (detectImmersiveRange(e.getX(), e.getY())) {
            activity.toggleKeyboard();
            return true;
        }

        int metaState = e.getMetaState() | canvas.getKeyboard().getMetaState();
        int x = getX(e);
        int y = getY(e);
        pointer.leftButtonDown(x, y, metaState);

        if (touchpadFeedback) {
            activity.sendShortVibration();
        }

        SystemClock.sleep(100);
        pointer.releaseButton(x, y, metaState);

//        activity.resetOnScreenKeys(0);

        return true;
    }

    /*
     * @see android.view.GestureDetector.SimpleOnGestureListener#onDoubleTap(android.view.MotionEvent)
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (dragMode || rightDragMode || middleDragMode || detectImmersiveRange(e.getX(), e.getY())) {
            return true;
        }

        GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTap, e: " + e);

        totalDragX = 0;
        totalDragY = 0;

        dragMode = true;

        // These are for drag helper
        lastZoomFactor = canvas.getZoomFactor();
        lastDragStartTime = System.currentTimeMillis();

        return true;
    }

    /*
     * @see android.view.GestureDetector.SimpleOnGestureListener#onLongPress(android.view.MotionEvent)
     */
    @Override
    public void onLongPress(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onLongPress, e: " + e);

        if (secondPointerWasDown || thirdPointerWasDown || dragMode || detectImmersiveRange(e.getX(), e.getY())) {
            GeneralUtils.debugLog(debugLogging, TAG,
                    "onLongPress: right/middle-click gesture in progress, not starting drag mode");
            return;
        }

        totalDragX = 0;
        totalDragY = 0;

        String longPressType = Utils.querySharedPreferenceString(activity.getApplicationContext(), Constants.touchpadLongPressAction, "left");

        if (longPressType.equals("left")) {
            dragMode = true;
        } else if (longPressType.equals("middle")) {
            middleDragMode = true;
        } else if (longPressType.equals("right")){
            rightDragMode = true;
        } else if (longPressType.equals("gesture")) {
            // Here we mock a ACTION_DOWN event for gestureOverlayView to transmit the touch events to it flawlessly
            // further touch events will be transmitted in method onTouchEvent.
            GestureOverlayView gestureOverlay = activity.findViewById(R.id.gestureOverlay);
            gestureOverlay.setVisibility(View.VISIBLE);

            float x = e.getX();
            float y = e.getY();

            // 生成并分发模拟事件
            MotionEvent downEvent = MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    0
            );
            gestureOverlay.dispatchTouchEvent(downEvent);
            downEvent.recycle();
        }  // else do nothing

    }

    /**
     * Indicates that drag modes and scrolling have ended.
     * @return whether any mode other than the drag modes was enabled
     */
    protected boolean endDragModesAndScrolling() {
        GeneralUtils.debugLog(debugLogging, TAG, "endDragModesAndScrolling");
        boolean nonDragGesture = true;
        canvas.cursorBeingMoved = false;
        panMode = false;
        inSwiping = false;
        inScrolling = false;
        immersiveSwipeX = false;
        immersiveSwipeY = false;

        if (dragMode || rightDragMode || middleDragMode) {
            nonDragGesture = false;
            dragMode = false;
            rightDragMode = false;
            middleDragMode = false;
        }
        return nonDragGesture;
    }

    /**
     * Modify the event so that the mouse goes where we specify.
     * @param e event to be modified.
     * @param x new x coordinate.
     * @param y new y coordinate.
     */
    protected void setEventCoordinates(MotionEvent e, float x, float y) {
        GeneralUtils.debugLog(debugLogging, TAG, "setEventCoordinates");
        e.setLocation(x, y);
    }

    protected boolean detectImmersiveRange(float x, float y) {
        return detectImmersiveVertical(x) || detectImmersiveHorizontal(y);
    }

    protected boolean detectImmersiveVertical(float x) {
        if (!immersiveSwipeEnabled || canvas.isOutDisplay()) {
            return false;
        }

        float immersiveXDistance = Math.min(Math.max(touchpad.getWidth() * immersiveSwipeRatio, 50), 150);

        return x <= immersiveXDistance || touchpad.getWidth() - x <= immersiveXDistance;
    }

    protected boolean detectImmersiveHorizontal(float y) {
        if (!immersiveSwipeEnabled || canvas.isOutDisplay()) {
            return false;
        }

        float immersiveYDistance = Math.min(Math.max(touchpad.getHeight() * immersiveSwipeRatio, 50), 150);

        return Constants.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                (y <= immersiveYDistance || touchpad.getHeight() - y <= immersiveYDistance);
    }

    private boolean detectLeftRange(float x, float y) {
        float bottomXDistance = Math.max(touchpad.getWidth() * immersiveSwipeRatio, 20);
        float bottomYDistance = touchpad.getHeight() / 2f;

        return Constants.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                (x <= bottomXDistance || touchpad.getWidth() - x <= bottomXDistance) && y > bottomYDistance;
    }

    private boolean detectRightRange(float x, float y) {
        float bottomXDistance = Math.max(touchpad.getWidth() * immersiveSwipeRatio, 20);
        float bottomYDistance = touchpad.getHeight() / 2f;

        return Constants.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                (x <= bottomXDistance || touchpad.getWidth() - x <= bottomXDistance) && y < bottomYDistance;
    }

    private void detectImmersiveSwipe(float x, float y) {
        // if global switch off or external display mode, disable it
        if (!immersiveSwipeEnabled || canvas.isOutDisplay()) {
            return;
        }

        GeneralUtils.debugLog(debugLogging, TAG, "detectImmersiveSwipe");

        float immersiveXDistance = Math.min(Math.max(touchpad.getWidth() * immersiveSwipeRatio, 50), 150);
        float immersiveYDistance = Math.min(Math.max(touchpad.getHeight() * immersiveSwipeRatio, 50), 150);

        if (detectImmersiveVertical(x)) {
            inSwiping = true;
            immersiveSwipeY = true;

            if (x <= immersiveXDistance) {
                edgeLeft.setVisibility(View.VISIBLE);
                setEdgeWidth(edgeLeft, (int) immersiveXDistance);
            } else {
                edgeRight.setVisibility(View.VISIBLE);
                setEdgeWidth(edgeRight, (int) immersiveXDistance);
            }

            return;
        }

        if (detectImmersiveHorizontal(y)) {
            inSwiping = true;
            immersiveSwipeX = true;

            if (y <= immersiveYDistance) {
                edgeTop.setVisibility(View.VISIBLE);
                setEdgeHeight(edgeTop, (int) immersiveYDistance);
            } else {
                edgeBottom.setVisibility(View.VISIBLE);
                setEdgeHeight(edgeBottom, (int) immersiveYDistance);
            }

            return;
        }

        inSwiping = false;
        immersiveSwipeX = false;
        immersiveSwipeY = false;
    }

    private void setEdgeWidth(View view, int newWidthDp) {
        // 1. 获取布局参数
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.width = newWidthDp;

        // 3. 应用参数
        view.setLayoutParams(params);
    }

    private void setEdgeHeight(View view, int newHeightDp) {
        // 1. 获取布局参数
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.height = newHeightDp;

        // 3. 应用参数
        view.setLayoutParams(params);
    }


    /*
     * @see com.qihua.bVNC.input.InputHandler#0yonTouchEvent(android.view.MotionEvent)
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent, e: " + e);

        final int action = e.getActionMasked();
        final int index = e.getActionIndex();
        final int pointerID = e.getPointerId(index);
        final int meta = e.getMetaState();

        FpsCounter fpsCounter = canvas.getFpsCounter();
        if (fpsCounter != null) {
            fpsCounter.countInput();
        }

        GestureOverlayView gestureOverlay = activity.findViewById(R.id.gestureOverlay);

        // 当手势层可见时，直接转发事件，这样可以无缝将触摸事件转至手势层
        if (gestureOverlay.getVisibility() == View.VISIBLE) {
            // for three pointer movement
            if (pointerID > 0 && action != MotionEvent.ACTION_UP) {
                return true;
            }

            float x = e.getX();
            float y = e.getY();

            MotionEvent translatedEvent = MotionEvent.obtain(
                    e.getDownTime(),
                    e.getEventTime(),
                    e.getAction(),
                    x,
                    y,
                    e.getMetaState()
            );

            boolean handled = gestureOverlay.dispatchTouchEvent(translatedEvent);
            translatedEvent.recycle();

            return handled;
        }

        if (scalingGestureDetector.onTouchEvent(e) || inScaling) {
            return true;
        }

        InputDevice device = e.getDevice();
        if (device == null) return false;

        if ((device.getSources() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            if ((e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                touchpad.post(() -> touchpad.startPointerCapture());
                return true; // 消费事件，不传递给其他组件
            }
        }

        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: pointerID: " + pointerID);
        switch (pointerID) {
            case 0:
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        disregardNextOnFling = false;
                        singleHandedJustEnded = false;
                        // We have put down first pointer on the screen, so we can reset the state of all click-state variables.
                        // Permit sending mouse-down event on long-tap again.
                        secondPointerWasDown = false;
                        // Permit right-clicking again.
                        thirdPointerWasDown = false;
                        thirdPointerGesture = false;
                        // Cancel any effect of scaling having "just finished" (e.g. ignoring scrolling).
                        scalingJustFinished = false;
                        // Cancel drag modes and scrolling.
                        if (!singleHandedGesture)
                            endDragModesAndScrolling();
                        canvas.cursorBeingMoved = true;
                        // If we are manipulating the desktop, turn off bitmap filtering for faster response.
                        canvas.bitmapData.paint.setFilterBitmap(false);
                        // Indicate where we start dragging from.
                        dragX = e.getX();
                        dragY = e.getY();

                        gestureX = e.getX();
                        gestureY = e.getY();

                        lastSpeedX = lastSpeedY = 0;

                        inertiaSwiping = false;

                        if (inertiaThread != null) {
                            inertiaThread.interrupt();
                        }

                        lastX = e.getX();
                        lastY = e.getY();

//                        activity.hideKeyboardAndExtraKeys();

                        // Detect whether this is potentially the start of a gesture to show the nav bar.
                        // No single finger scrolling in free edition
//                        detectImmersiveSwipe(dragX);

                        // Stop inertia scrolling
                        inertiaStartTime = System.currentTimeMillis();

                        detectImmersiveSwipe(dragX, dragY);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        long timeElapsed = System.currentTimeMillis() - inertiaStartTime;
                        long interval = inertiaBaseInterval * 2;

                        if (timeElapsed > interval) {
                            if (lastX != 0) {
                                lastSpeedX = ((e.getX() - lastX) / timeElapsed) * inertiaBaseInterval / canvas.getZoomFactor();
                                lastSpeedX = lastSpeedX * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
                            }

                            if (lastY != 0) {
                                lastSpeedY = ((e.getY() - lastY) / timeElapsed) * inertiaBaseInterval / canvas.getZoomFactor();
                                lastSpeedY = lastSpeedY * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
                            }

                            inertiaStartTime = System.currentTimeMillis();
                        }

                        lastX = e.getX();
                        lastY = e.getY();

                        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: ACTION_MOVE");
                        // Send scroll up/down events if swiping is happening.
                        if (panMode) {
                            float scale = canvas.getZoomFactor();
                            canvas.relativePan(-(int) ((e.getX() - dragX) * scale), -(int) ((e.getY() - dragY) * scale));
                            dragX = e.getX();
                            dragY = e.getY();
                            GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: ACTION_MOVE panMode");
                            break;
                        } else if (dragMode || rightDragMode || middleDragMode) {
                            if (totalDragY == 0 && totalDragX == 0) {
                                if (dragMode) {
                                    pointer.leftButtonDown(getX(e), getY(e), meta);
                                } else if (rightDragMode) {
                                    pointer.rightButtonDown(getX(e), getY(e), meta);
                                } else if (middleDragMode) {
                                    pointer.middleButtonDown(getX(e), getY(e), meta);
                                }

                                if (touchpadFeedback) {
                                    activity.sendShortVibration();
                                }

                                // make it nonzero to prevent being trigger again
                                totalDragX = 0.1f;
                                totalDragY = 0.1f;
                            }

                            // do not enlarge if the cursor moved away
                            if (totalDragX > 50 || totalDragY > 50) {
                                canEnlarge = false;
                            }

                            // If try to drag with long time, enlarge the screen for drag helper. This is very helpful in selecting texts in small screen.
                            if (System.currentTimeMillis() - lastDragStartTime > 800 && canEnlarge
                                    && dragMode && (totalDragX < 100 && totalDragY < 100)
                                    && lastZoomFactor < 2.0f) {
                                canvas.scaler.changeZoom(activity, 2.5f/canvas.getZoomFactor(), pointer.getX(), pointer.getY());
                                dragHelped = true;
                            }

                            pointer.moveMouseButtonDown(getX(e), getY(e), meta);
                            canvas.movePanToMakePointerVisible();
                            GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: ACTION_MOVE in a drag mode, moving mouse with button down");
                            break;
                        }

                        if (thirdPointerWasDown
                                && (Math.abs(e.getX() - dragX) > 80 || Math.abs(e.getY() - dragY) > 80)) {
                            thirdPointerGesture = true;
                            // Here we mock a ACTION_DOWN event for gestureOverlayView to transmit the touch events to it flawlessly
                            // further touch events will be transmitted in method onTouchEvent.
                            gestureOverlay = activity.findViewById(R.id.gestureOverlay);
                            gestureOverlay.setVisibility(View.VISIBLE);

                            // 生成并分发模拟事件
                            MotionEvent downEvent = MotionEvent.obtain(
                                    SystemClock.uptimeMillis(),
                                    SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_DOWN,
                                    e.getX(),
                                    e.getY(),
                                    0
                            );
                            gestureOverlay.dispatchTouchEvent(downEvent);
                            downEvent.recycle();
                        }

                        break;
                    case MotionEvent.ACTION_UP:
                        edgeLeft.setVisibility(View.INVISIBLE);
                        edgeRight.setVisibility(View.INVISIBLE);
                        edgeTop.setVisibility(View.INVISIBLE);
                        edgeBottom.setVisibility(View.INVISIBLE);

                        cumulatedX = 0;
                        cumulatedY = 0;

                        canSwipeToMove = false;

                        break;
                }
                break;
            case 1:
                switch (action) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // We re-calculate the initial focal point to be between the 1st and 2nd pointer index.
                        xInitialFocus = (e.getX(pointerID));
                        yInitialFocus = (e.getY(pointerID));

                        // Permit sending mouse-down event on long-tap again.
                        secondPointerWasDown = true;
                        // Permit right-clicking again.
                        thirdPointerWasDown = false;
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        break;
                }
                break;

            case 2:
                switch (action) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        thirdPointerWasDown = true;
                        secondPointerWasDown = false;

                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        break;
                }
                break;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (!inSwiping && !inScaling && secondPointerWasDown) {
                pointer.rightButtonDown(getX(e), getY(e), meta);

                if (touchpadFeedback) {
                    activity.sendShortVibration();
                }

                SystemClock.sleep(100);
                pointer.releaseButton(getX(e), getY(e), meta);

                secondPointerWasDown = false;
            }

            if (!inSwiping && !inScaling && thirdPointerWasDown) {
                String threePointerAction = Utils.querySharedPreferenceString(activity,
                        Constants.threePointerTouchAction, "keyboard");

                if ("keyboard".equals(threePointerAction)) {
                    activity.toggleKeyboard(null);
                } else {
                    pointer.middleButtonDown(getX(e), getY(e), meta);

                    if (touchpadFeedback) {
                        activity.sendShortVibration();
                    }

                    SystemClock.sleep(100);
                    pointer.releaseButton(getX(e), getY(e), meta);
                }

                thirdPointerWasDown = false;
            }

            canEnlarge = true;

            // for single finger movement
            if (inertiaScrollingEnabled && !dragMode && !inSwiping) {
                if (activity.isToolbarShowing() && canvas.connection.getEnableGesture()) {

                } else {
                    inertiaMetaState = e.getMetaState();
                    inertiaSemaphore.release();
                }
            }

            // for two finger inertia scrolling
            if (inertiaScrollingEnabled && inSwiping) {
                inertiaSwiping = true;
                inertiaSemaphore.release();
            }

            if (dragMode || rightDragMode || middleDragMode) {
                if (dragHelped) {
                    canvas.scaler.changeZoom(activity, lastZoomFactor / canvas.getZoomFactor(), pointer.getX(), pointer.getY());
                    dragHelped = false;
                }

                // release the drag button down
                pointer.releaseButton(getX(e), getY(e), meta);

                SystemClock.sleep(100);

                if (dragMode && totalDragX < 8 && totalDragY < 8) {
                    // if the double tap performed without any movement, perform a additional click
                    // to form a double click. note that the first click is performed during the drag

                    pointer.leftButtonDown(getX(e), getY(e), meta);

                    if (touchpadFeedback) {
                        activity.sendShortVibration();
                    }
                    SystemClock.sleep(100);

                    pointer.releaseButton(getX(e), getY(e), meta);
                }

                endDragModesAndScrolling();
            }
        }

        return gestureDetector.onTouchEvent(e);
    }

    /*
     * @see android.view.ScaleGestureDetector.OnScaleGestureListener#onScale(android.view.ScaleGestureDetector)
     */
    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        GeneralUtils.debugLog(debugLogging, TAG, "onScale");

        // Get the current focus.
        xCurrentFocus = detector.getFocusX();
        yCurrentFocus = detector.getFocusY();

        if (!inSwiping) {
            // prevent right click
            secondPointerWasDown = false;

            if (!inScaling && Math.abs(1.0 - detector.getScaleFactor()) < minScaleFactor) {
                GeneralUtils.debugLog(debugLogging, TAG, "Not scaling due to small scale factor");
                return false;
            }

            if (canvas != null && canvas.scaler != null) {
                if (!inScaling) {
                    inScaling = true;
                }

                GeneralUtils.debugLog(debugLogging, TAG, "Changing zoom level: " + detector.getScaleFactor());
                canvas.scaler.changeZoom(activity, detector.getScaleFactor(), pointer.getX(), pointer.getY());
            }
        }

        return true;
    }

    /*
     * @see android.view.ScaleGestureDetector.OnScaleGestureListener#onScaleBegin(android.view.ScaleGestureDetector)
     */
    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        GeneralUtils.debugLog(debugLogging, TAG, "onScaleBegin (" + xInitialFocus + "," + yInitialFocus + ")");
        inScaling = false;
        scalingJustFinished = false;
        // Cancel any swipes that may have been registered last time.
        inSwiping = false;
        scrollDown = false;
        scrollUp = false;
        scrollRight = false;
        scrollLeft = false;
        return true;
    }

    /*
     * @see android.view.ScaleGestureDetector.OnScaleGestureListener#onScaleEnd(android.view.ScaleGestureDetector)
     */
    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        GeneralUtils.debugLog(debugLogging, TAG, "onScaleEnd");
        inScaling = false;
        inSwiping = false;
        scalingJustFinished = true;
    }

    /*
     * @see com.qihua.bVNC.input.InputHandler#onKeyDown(int, android.view.KeyEvent)
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (e.getDeviceId() > 10) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                canvas.getKeyboard().onScreenAltOn();

                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));

                canvas.getKeyboard().onScreenAltOff();

                return true;
            } else if (keyCode == KeyEvent.KEYCODE_FORWARD) {
                canvas.getKeyboard().onScreenAltOn();

                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));

                canvas.getKeyboard().onScreenAltOff();

                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // If the toolbar is already shown, disconnect
            if (activity.getSupportActionBar() == null || activity.getSupportActionBar().isShowing()) {
                // Save current zoom factor, but not on second display mode
                if (!canvas.isOutDisplay()) {
                    canvas.saveZoomFactor(canvas.getZoomFactor());
                }

                activity.disconnectAndClose();

                return true;
            }

            if (!activity.getSupportActionBar().isShowing()) {
                activity.showToolbar();
            }

            String longPressType = Utils.querySharedPreferenceString(activity.getApplicationContext(), Constants.touchpadLongPressAction, "left");
            // Hide gesture overlay if triggered by long press
            if ("gesture".equals(longPressType)) {
                GestureOverlayView gestureOverlay = activity.findViewById(R.id.gestureOverlay);
                gestureOverlay.setVisibility(View.GONE);
            }

            touchpad.releasePointerCapture();

            return true;
        }

//        GeneralUtils.debugLog(debugLogging, TAG, "onKeyDown, e: " + e);
        try {
            return canvas.getKeyboard().keyEvent(keyCode, e);
        } catch (Exception ignore) {
            return false;
        }
    }

    /*
     * @see com.qihua.bVNC.input.InputHandler#onKeyUp(int, android.view.KeyEvent)
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onKeyDown, e: " + e);
        return canvas.getKeyboard().keyEvent(keyCode, e);
    }

    /**
     * Returns the sign of the given number.
     * @param number
     * @return
     */
    protected float getSign(float number) {
        float sign;
        if (number >= 0) {
            sign = 1.f;
        } else {
            sign = -1.f;
        }
        return sign;
    }

    boolean consumeAsMouseWheel(MotionEvent e1, MotionEvent e2) {
        boolean useEvent = false;
        if (!canvas.isAbleToPan()) {
            GeneralUtils.debugLog(debugLogging, TAG, "consumeAsMouseWheel, fit-to-screen");
            useEvent = true;
        }

        if (e1.getSource() == InputDeviceCompat.SOURCE_MOUSE ||
                e1.getSource() == InputDeviceCompat.SOURCE_CLASS_POINTER ||
                e1.getSource() == InputDeviceCompat.SOURCE_CLASS_TRACKBALL ||
                e1.getSource() == InputDeviceCompat.SOURCE_TOUCHPAD ||
                e1.getSource() == InputDeviceCompat.SOURCE_DPAD
        ) {
            GeneralUtils.debugLog(debugLogging, TAG, "consumeAsMouseWheel, mouse-like source");
            useEvent = true;
        }

        if (!useEvent) {
            return false;
        }

        int meta = e1.getMetaState();
        scrollUp = false;
        scrollDown = false;
        scrollLeft = false;
        scrollRight = false;
        swipeSpeed = 1;

        if (e1.getX() < e2.getX()) {
            scrollLeft = true;
        } else if (e1.getX() > e2.getX()) {
            scrollRight = true;
        }

        if (e1.getY() < e2.getY()) {
            scrollUp = true;
        } else if (e1.getY() > e2.getY()) {
            scrollDown = true;
        }
        sendScrollEvents(getX(e1), getY(e1), -1, meta);
        return true;
    }
}

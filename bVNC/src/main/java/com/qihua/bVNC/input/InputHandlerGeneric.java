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

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.core.view.InputDeviceCompat;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.undatech.opaque.input.RemoteKeyboard;
import com.undatech.opaque.util.GeneralUtils;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Semaphore;

abstract class InputHandlerGeneric extends MyGestureDectector.SimpleOnGestureListener
        implements InputHandler, ScaleGestureDetector.OnScaleGestureListener {
    private static final String TAG = "InputHandlerGeneric";
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
    protected float dragX, dragY;
    protected float gestureX, gestureY;
    protected float totalDragX, totalDragY;
    protected boolean singleHandedGesture = false;
    protected boolean singleHandedJustEnded = false;
    // These variables keep track of which pointers have seen ACTION_DOWN events.
    protected boolean secondPointerWasDown = false;
    protected long inertiaStartTime = 0;
    protected Thread inertiaThread;
    protected long inertiaBaseInterval = 16;
    protected boolean inertiaScrollingEnabled = true;
    protected int inertiaMetaState = 0;
    protected Semaphore inertiaSemaphore = new Semaphore(0);
    protected float lastSpeedX = 0;
    protected float lastSpeedY = 0;
    protected float lastX = 0;
    protected float lastY = 0;
    protected boolean thirdPointerWasDown = false;
    protected RemotePointer pointer;
    // This is the initial "focal point" of the gesture (between the two fingers).
    float xInitialFocus;
    float yInitialFocus;
    // This is the final "focal point" of the gesture (between the two fingers).

    int lastDelta = 0;

    // 0, 1, 2, 3 = up lef down right
    int lastScrollDirection = 0;

    float xCurrentFocus;
    float yCurrentFocus;
    // These variables record whether there was a two-finger swipe performed up or down.
    boolean inSwiping = false;
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
    float startSwipeDist = 1.f;
    float baseSwipeDist = 0.f;
    // This is how far from the top and bottom edge to detect immersive swipe.
    float immersiveSwipeRatio = 0.1f;
    boolean immersiveSwipe = false;
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

    InputHandlerGeneric(RemoteCanvasActivity activity, RemoteCanvas canvas, RemoteCanvas touchpad, RemotePointer pointer,
                        boolean debugLogging) {
        this.activity = activity;
        this.touchpad = touchpad;
        this.canvas = canvas;
        this.pointer = pointer;
        this.debugLogging = debugLogging;

        // TODO: Implement this
        useDpadAsArrows = true; //activity.getUseDpadAsArrows();
        rotateDpad = false; //activity.getRotateDpad();

        gestureDetector = new MyGestureDectector(activity, this, null, false);
        scalingGestureDetector = new MyScaleGestureDetector(activity, this);

        gestureDetector.setOnDoubleTapListener(this);

        this.panRepeater = new PanRepeater(canvas, canvas.handler);

        displayDensity = canvas.getDisplayDensity();

        distXQueue = new LinkedList<Float>();
        distYQueue = new LinkedList<Float>();

//        baseSwipeDist = baseSwipeDist / displayDensity;
//        startSwipeDist = startSwipeDist / displayDensity;
//        immersiveSwipeDistance = immersiveSwipeDistance / displayDensity;
        GeneralUtils.debugLog(debugLogging, TAG, "displayDensity, baseSwipeDist, immersiveSwipeRatio: "
                + displayDensity + " " + baseSwipeDist + " " + immersiveSwipeRatio);

        // for inertia scrolling
        inertiaThread = new Thread(new Runnable() {
            @Override
            public void run(){
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

                    while ((speedX != 0 || speedY != 0) && !inertiaThread.isInterrupted()) {
                        pointer.moveMouse(pointer.getX() + speedX, pointer.getY() + speedY, inertiaMetaState);
                        canvas.movePanToMakePointerVisible();

                        speedX = (int) (speedX * 0.85);
                        speedY = (int) (speedY * 0.85);

                        SystemClock.sleep(inertiaBaseInterval);
                    }

                    canvas.movePanToMakePointerVisible();
                }
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

        // position stabilize
        if (Math.abs(diffX) / Math.abs(diffY) > 10) {
            diffY = 0;
        } else if (Math.abs(diffY) / Math.abs(diffX) > 10) {
            diffX = 0;
        }

        int x = (int) (computeAcceleration4mouse(diffX) + pointer.pointerX);
        int y = (int) (computeAcceleration4mouse(diffY) + pointer.pointerY);

        switch (action) {
            // If a mouse button was pressed or mouse was moved.
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                switch (bstate) {
                    case MotionEvent.BUTTON_PRIMARY:
                        canvas.movePanToMakePointerVisible();
                        pointer.leftButtonDown(x, y, meta);
                        used = true;
                        break;
                    case MotionEvent.BUTTON_SECONDARY:
                    case MotionEvent.BUTTON_STYLUS_PRIMARY:
                        canvas.movePanToMakePointerVisible();
                        pointer.rightButtonDown(x, y, meta);
                        used = true;
                        break;
                    case MotionEvent.BUTTON_TERTIARY:
                    case MotionEvent.BUTTON_STYLUS_SECONDARY:
                        canvas.movePanToMakePointerVisible();
                        pointer.middleButtonDown(x, y, meta);
                        used = true;
                        break;
                    default:
                        // move only
                        pointer.moveMouse(x, y, meta);
                }
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
                        canvas.movePanToMakePointerVisible();
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
            // If the mouse was moved OR as reported, some external mice trigger this when a
            // mouse button is pressed as well, so we check bstate here too.
//            case MotionEvent.ACTION_HOVER_MOVE:
//                canvas.movePanToMakePointerVisible();
//                switch (bstate) {
//                    case MotionEvent.BUTTON_PRIMARY:
//                        pointer.leftButtonDown(x, y, meta);
//                        break;
//                    case MotionEvent.BUTTON_SECONDARY:
//                    case MotionEvent.BUTTON_STYLUS_PRIMARY:
//                        pointer.rightButtonDown(x, y, meta);
//                        break;
//                    case MotionEvent.BUTTON_TERTIARY:
//                    case MotionEvent.BUTTON_STYLUS_SECONDARY:
//                        pointer.middleButtonDown(x, y, meta);
//                        break;
//                    default:
//                        pointer.moveMouseButtonUp(x, y, meta);
//                        break;
//                }
//                used = true;
        }

        canvas.movePanToMakePointerVisible();

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

        pointer.releaseButton(x, y, meta);
    }

    /*
     * @see android.view.GestureDetector.SimpleOnGestureListener#onSingleTapConfirmed(android.view.MotionEvent)
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onSingleTapConfirmed, e: " + e);
        if (dragMode) {
            return true;
        }

        int metaState = e.getMetaState();
        pointer.leftButtonDown(getX(e), getY(e), metaState);
        SystemClock.sleep(50);
        pointer.releaseButton(getX(e), getY(e), metaState);
//        canvas.movePanToMakePointerVisible();
        return true;
    }

    /*
     * @see android.view.GestureDetector.SimpleOnGestureListener#onDoubleTap(android.view.MotionEvent)
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onDoubleTap, e: " + e);

        totalDragX = 0;
        totalDragY = 0;

        // Will handle the double click if the drag not performed in endDragModesAndScrolling()

        dragMode = true;

        activity.sendShortVibration();

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

        if (secondPointerWasDown || thirdPointerWasDown || dragMode) {
            GeneralUtils.debugLog(debugLogging, TAG,
                    "onLongPress: right/middle-click gesture in progress, not starting drag mode");
            return;
        }

        activity.sendShortVibration();

        totalDragX = 0;
        totalDragY = 0;

        rightDragMode = true;
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
        immersiveSwipe = false;
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

    private void detectImmersiveSwipe(float x, float y) {
        GeneralUtils.debugLog(debugLogging, TAG, "detectImmersiveSwipe");

        if (x <= canvas.getWidth() * immersiveSwipeRatio
                || touchpad.getWidth() - x <= canvas.getWidth() * immersiveSwipeRatio
                || touchpad.getHeight() - y <= canvas.getImageHeight() * immersiveSwipeRatio
                || y <= canvas.getImageHeight() * immersiveSwipeRatio) {
            inSwiping = true;
            immersiveSwipe = true;
        } else if (!singleHandedGesture) {
            inSwiping = false;
            immersiveSwipe = false;
        }
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

        if (scalingGestureDetector.onTouchEvent(e) || inScaling) {
            return true;
        }

//        if (android.os.Build.VERSION.SDK_INT >= 14) {
        // Handle and consume actions performed by a (e.g. USB or bluetooth) mouse.
        if (e.getDeviceId() > 10) {
            if (e.getButtonState() == MotionEvent.BUTTON_PRIMARY) {
                touchpad.startPointerCapture();
            }

            return true;
        }
//        }

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
                        // Cancel any effect of scaling having "just finished" (e.g. ignoring scrolling).
                        scalingJustFinished = false;
                        // Cancel drag modes and scrolling.
                        if (!singleHandedGesture)
                            endDragModesAndScrolling();
                        canvas.cursorBeingMoved = true;
                        // If we are manipulating the desktop, turn off bitmap filtering for faster response.
                        canvas.myDrawable.paint.setFilterBitmap(false);
                        // Indicate where we start dragging from.
                        dragX = e.getX();
                        dragY = e.getY();

                        gestureX = e.getX(index);
                        gestureY = e.getY(index);

                        lastSpeedX = lastSpeedY = 0;

                        if (inertiaThread != null) {
                            inertiaThread.interrupt();
                        }

                        lastX = e.getX();
                        lastY = e.getY();

//                        activity.hideKeyboardAndExtraKeys();

                        // Detect whether this is potentially the start of a gesture to show the nav bar.
                        detectImmersiveSwipe(dragX, dragY);

                        // Stop inertia srolling
                        inertiaStartTime = System.currentTimeMillis();

                        break;
                    case MotionEvent.ACTION_MOVE:
                        long timeElapsed = System.currentTimeMillis() - inertiaStartTime;
                        long interval = inertiaBaseInterval * 3;

                        if (timeElapsed > interval) {
                            if (lastX != 0) {
                                lastSpeedX = (1000 * (e.getX() - lastX)) / (timeElapsed * inertiaBaseInterval * (canvas.getZoomFactor() * 0.8f));
                            }

                            if (lastY != 0) {
                                lastSpeedY = (1000 * (e.getY() - lastY)) / (timeElapsed * inertiaBaseInterval * (canvas.getZoomFactor() * 0.8f));
                            }

                            inertiaStartTime = System.currentTimeMillis();
                        }

                        lastX = e.getX();
                        lastY = e.getY();

                        // do not move when toolbar is showing, instead do the gesture recognition
                        if (activity.isToolbarShowing()) {
                            break;
                        }

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
                            if (dragMode && totalDragY == 0 && totalDragX == 0) {
                                pointer.leftButtonDown(getX(e), getY(e), meta);
                            } else if (rightDragMode && totalDragY == 0 && totalDragX == 0) {
                                pointer.rightButtonDown(getX(e), getY(e), meta);
                            } else if (middleDragMode && totalDragY == 0 && totalDragX == 0){
                                pointer.middleButtonDown(getX(e), getY(e), meta);
                            }

                            // do not enlarge if the cursor moved away
                            if (Math.abs(totalDragX) > 50 || Math.abs(totalDragY) > 50) {
                                canEnlarge = false;
                            }

                            // If try to drag with long time, enlarge the screen for drag helper. This is very helpful in selecting texts in small screen.
                            if (System.currentTimeMillis() - lastDragStartTime > 600 && canEnlarge
                                    && dragMode && (Math.abs(totalDragX) < 150 && Math.abs(totalDragY) < 150)
                                    && lastZoomFactor < 2.0f) {
                                canvas.canvasZoomer.changeZoom(activity, 2.5f/canvas.getZoomFactor(), pointer.getX(), pointer.getY());
                                dragHelped = true;
                            }

                            pointer.moveMouseButtonDown(getX(e), getY(e), meta);
                            canvas.movePanToMakePointerVisible();
                            GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: ACTION_MOVE in a drag mode, moving mouse with button down");
                            break;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (inertiaScrollingEnabled && !activity.isToolbarShowing() && !immersiveSwipe && !secondPointerWasDown && !thirdPointerWasDown) {
//                            timeElapsed = System.currentTimeMillis() - inertiaStartTime;
//                            interval = inertiaBaseInterval * 2;
//
//                            if (timeElapsed > interval) {
//                                if (lastX != 0) {
//                                    lastSpeedX = (1000 * (e.getX() - lastX)) / (timeElapsed * inertiaBaseInterval * canvas.getZoomFactor());
//                                }
//
//                                if (lastY != 0) {
//                                    lastSpeedY = (1000 * (e.getY() - lastY)) / (timeElapsed * inertiaBaseInterval * canvas.getZoomFactor());
//                                }
//
//                                inertiaStartTime = System.currentTimeMillis();
//                            }

//                            if (lastSpeedX == 0 && lastSpeedY == 0) {
//                                lastSpeedX = (1000 * (e.getX() - lastX)) / (timeElapsed * (inertiaBaseInterval) * canvas.getZoomFactor());
//                                lastSpeedY = (1000 * (e.getX() - lastX)) / (timeElapsed * (inertiaBaseInterval) * canvas.getZoomFactor());
//                            }

                            inertiaMetaState = e.getMetaState();
                            inertiaSemaphore.release();
                        }

                        if (activity.isToolbarShowing()){
                            // the three pointer gestures
                            if ((e.getY(index) - gestureY) > 130 && Math.abs(e.getX(index) - gestureX) < 100) {
                                canvas.getKeyboard().sendUnicode('w', RemoteKeyboard.CTRL_MASK);

                                Toast.makeText(pointer.context, "手势：关闭标签", Toast.LENGTH_SHORT).show();
                            } else if ((e.getX(index) - gestureX) < -130 && Math.abs(e.getY(index) - gestureY) < 100) {
                                canvas.getKeyboard().onScreenAltOn();

                                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));

                                canvas.getKeyboard().onScreenAltOff();

                                Toast.makeText(pointer.context, "手势：返回", Toast.LENGTH_SHORT).show();
                            } else if ((e.getX(index) - gestureX) > 130 && Math.abs(e.getY(index) - gestureY) < 100) {
                                canvas.getKeyboard().onScreenAltOn();

                                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                                canvas.getKeyboard().keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));

                                canvas.getKeyboard().onScreenAltOff();

                                Toast.makeText(pointer.context, "手势：前进", Toast.LENGTH_SHORT).show();
                            }
//                            else if ((e.getX(index) - gestureX) > 130 && e.getY(index) - gestureY < -130) {
//                                canvas.getKeyboard().sendUnicode('c', KeyEvent.META_CTRL_LEFT_ON);
//
//                                Toast.makeText(pointer.context, "手势：复制", Toast.LENGTH_SHORT).show();
//
//                            }
//                            else if ((e.getX(index) - gestureX) > 130 && e.getY(index) - gestureY > 130) {
//                                canvas.getKeyboard().sendUnicode('v', KeyEvent.META_CTRL_LEFT_ON);
//
//                                Toast.makeText(pointer.context, "手势：粘贴", Toast.LENGTH_SHORT).show();
//
//                            }
//                            else if ((e.getX(index) - gestureX) < -130 && e.getY(index) - gestureY > 130) {
//                                canvas.getKeyboard().sendUnicode('z', KeyEvent.META_CTRL_LEFT_ON);
//
//                                Toast.makeText(pointer.context, "手势：撤消", Toast.LENGTH_SHORT).show();
//                            }
                            else if ((e.getY(index) - gestureY) < -130 && Math.abs(e.getX(index) - gestureX) < 100) {
                                canvas.getKeyboard().sendUnicode('1', RemoteKeyboard.ALT_MASK);

                                Toast.makeText(pointer.context, "手势：任务视图", Toast.LENGTH_SHORT).show();
                            }

                            activity.hideToolbar();
                        }

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

                        // start position for two finger gesture
                        gestureX = e.getX(index);
                        gestureY = e.getY(index);

                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        //two finger gesture
//                        if (e.getY(index) - gestureY < -130) {
//                            canvas.getKeyboard().sendUnicode('1', RemoteKeyboard.ALT_MASK);
//                            Toast.makeText(pointer.context, "手势：任务视图", Toast.LENGTH_SHORT).show();
//
//                            secondPointerWasDown = false;
//                        } else if (e.getY(index) - gestureY > 130) {
//                            canvas.getKeyboard().sendUnicode('w', RemoteKeyboard.CTRL_MASK);
//                            Toast.makeText(pointer.context, "手势：关闭标签", Toast.LENGTH_SHORT).show();
//
//                            secondPointerWasDown = false;
//                        }
                }
                break;

            case 2:
                switch (action) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        thirdPointerWasDown = true;
                        secondPointerWasDown = false;

                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP:
                        if (!inScaling && thirdPointerWasDown) {
                            activity.toggleKeyboard(null);

                            thirdPointerWasDown = false;
                        }
                        break;
                }
                break;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (!inSwiping && !inScaling && secondPointerWasDown) {
                pointer.rightButtonDown(getX(e), getY(e), meta);
                SystemClock.sleep(50);
                pointer.releaseButton(getX(e), getY(e), meta);

                secondPointerWasDown = false;
            }

            canEnlarge = true;

            if (!endDragModesAndScrolling()) {
                pointer.releaseButton(getX(e), getY(e), meta);

                if (dragHelped) {
                    canvas.canvasZoomer.changeZoom(activity, lastZoomFactor / canvas.getZoomFactor(), pointer.getX(), pointer.getY());

                    canvas.getKeyboard().sendUnicode('c', KeyEvent.META_CTRL_LEFT_ON);
                    Toast.makeText(pointer.context, "自动复制", Toast.LENGTH_SHORT).show();

                    dragHelped = false;
                }
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
            if (!inScaling && Math.abs(1.0 - detector.getScaleFactor()) < minScaleFactor) {
                GeneralUtils.debugLog(debugLogging, TAG, "Not scaling due to small scale factor");
                return false;
            }

            if (canvas != null && canvas.canvasZoomer != null) {
                if (!inScaling) {
                    inScaling = true;
                    // prevent right click
                    secondPointerWasDown = false;
                }

                GeneralUtils.debugLog(debugLogging, TAG, "Changing zoom level: " + detector.getScaleFactor());
                canvas.canvasZoomer.changeZoom(activity, detector.getScaleFactor(), pointer.getX(), pointer.getY());
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
            if (activity.getSupportActionBar().isShowing()) {
                // Save current zoom factor, but not on second display mode
                if (!canvas.isOutDisplay()) {
                    canvas.saveZoomFactor(canvas.getZoomFactor());
                }

                canvas.disconnectWithoutMessage();

                return true;
            }

            // release mouse capture
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                touchpad.releasePointerCapture();
            }

            activity.showToolbar();
            return true;
        }

        GeneralUtils.debugLog(debugLogging, TAG, "onKeyDown, e: " + e);
        return canvas.getKeyboard().keyEvent(keyCode, e);
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

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
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.util.Pair;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.util.GeneralUtils;
import com.qihua.bVNC.R;

public class InputHandlerTouchpad extends InputHandlerGeneric {
    public static final String ID = "TOUCHPAD_MODE";
    static final String TAG = "InputHandlerTouchpad";
    public static final int SCROLL_SAMPLING_MS = 33;

    public InputHandlerTouchpad(RemoteCanvasActivity activity, RemoteCanvas canvas, RemoteCanvas touchpad,
                                RemotePointer pointer, boolean debugLogging) {
        super(activity, canvas, touchpad, pointer, debugLogging);

        this.displayDensity = activity.getResources().getDisplayMetrics().density;
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#getDescription()
     */
    @Override
    public String getDescription() {
        return canvas.getResources().getString(R.string.input_method_touchpad_description);
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#getId()
     */
    @Override
    public String getId() {
        return ID;
    }

    // Add the following variables in the class member variable area
    private long lastScrollTimeMs = System.currentTimeMillis();

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean pResult = super.onTouchEvent(e);
        if (pResult) {
            return true;
        }

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

                        // Stop inertia scrolling
                        inertiaStartTime = System.currentTimeMillis();

                        if (touchpadFeedback) {
                            activity.sendShortVibration();
                        }

                        detectImmersiveSwipe(dragX, dragY);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        long timeElapsed = System.currentTimeMillis() - inertiaStartTime;
                        long interval = inertiaBaseInterval * 2;

                        if (timeElapsed > interval) {
                            if (lastX != 0) {
                                lastSpeedX = ((e.getX() - lastX) / timeElapsed) * inertiaBaseInterval / canvas.getZoomFactor() / 1.6f;
                                lastSpeedX = lastSpeedX * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
                            }

                            if (lastY != 0) {
                                lastSpeedY = ((e.getY() - lastY) / timeElapsed) * inertiaBaseInterval / canvas.getZoomFactor() / 1.6f;
                                lastSpeedY = lastSpeedY * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
                            }

                            inertiaStartTime = System.currentTimeMillis();
                        }

                        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent: ACTION_MOVE");
                        // Send scroll up/down events if swiping is happening.
                        if (dragMode || rightDragMode || middleDragMode) {
                            // 添加当前触摸点到分析器，仅在单指下有效
                            if (e.getPointerCount() == 1 && !detectImmersiveRange(e.getX(), e.getY())) {
                                touchMovementAnalyzer.addTouchPoint(e.getX(), e.getY());

                                // 强化版dragHelped功能：支持普通移动模式下的临时放大
                                if (dragHelpEnabled) {
                                    boolean isSlowMovement = touchMovementAnalyzer.analyzeMovement();

                                    if (isSlowMovement && canvas.getZoomFactor() < 1.5f) {
                                        // 检测到慢速移动，启用临时放大
                                        lastZoomFactor = canvas.getZoomFactor();
                                        canvas.scaler.changeZoom(activity, 2f / canvas.getZoomFactor(), pointer.getX(), pointer.getY());
                                        dragHelped = true;
                                    }
                                }
                            }

                            if (totalMoveY == 0 && totalMoveX == 0) {
                                if (dragMode) {
                                    pointer.leftButtonDown(getX(e), getY(e), meta);
                                } else if (rightDragMode) {
                                    pointer.rightButtonDown(getX(e), getY(e), meta);
                                } else if (middleDragMode) {
                                    pointer.middleButtonDown(getX(e), getY(e), meta);
                                }

                                // make it nonzero to prevent being trigger again
                                totalMoveX = 0.1f;
                                totalMoveY = 0.1f;
                            }

                            // when reached to the edge, keep the cursor continue moving
                            int x = getX(e);
                            int y = getY(e);

                            if (dragMode
                                    && System.currentTimeMillis() - lastDragHelpTimeMs > 200) {
                                if (e.getX() >= touchpad.getWidth() - getImmersiveXDistance()) {
                                    x += 50;
                                } else if (e.getX() <= getImmersiveXDistance()) {
                                    x -= 50;
                                }

                                if (e.getY() >= touchpad.getHeight() - getImmersiveYDistance()) {
                                    y += 50;
                                } else if (e.getY() <= getImmersiveYDistance()) {
                                    y -= 50;
                                }

                                lastDragHelpTimeMs = System.currentTimeMillis();
                            }

                            pointer.moveMouseButtonDown(x, y, meta);
                            canvas.movePanToMakePointerVisible();
                        }

                        totalMoveX += Math.abs(e.getX() - lastX);
                        totalMoveY += Math.abs(e.getY() - lastY);

                        lastX = e.getX();
                        lastY = e.getY();

//                        if (thirdPointerWasDown
//                                && (Math.abs(e.getX() - dragX) > 80 || Math.abs(e.getY() - dragY) > 80)) {
//                            thirdPointerGesture = true;
//                            // Here we mock a ACTION_DOWN event for gestureOverlayView to transmit the touch events to it flawlessly
//                            // further touch events will be transmitted in method onTouchEvent.
//                            gestureOverlay = activity.findViewById(R.id.gestureOverlay);
//                            gestureOverlay.setVisibility(View.VISIBLE);
//
//                            // 生成并分发模拟事件
//                            MotionEvent downEvent = MotionEvent.obtain(
//                                    SystemClock.uptimeMillis(),
//                                    SystemClock.uptimeMillis(),
//                                    MotionEvent.ACTION_DOWN,
//                                    e.getX(),
//                                    e.getY(),
//                                    0
//                            );
//                            gestureOverlay.dispatchTouchEvent(downEvent);
//                            downEvent.recycle();
//                        }

                        break;
                    case MotionEvent.ACTION_UP:
                        edgeLeft.setVisibility(View.INVISIBLE);
                        edgeRight.setVisibility(View.INVISIBLE);
                        edgeTop.setVisibility(View.INVISIBLE);
                        edgeBottom.setVisibility(View.INVISIBLE);

                        canSwipeToMove = false;

                        cumulatedX = 0;
                        cumulatedY = 0;

                        if (totalMoveY <= 10 && totalMoveX <= 10 && (detectImmersiveLeft(e.getX(), e.getY())
                                || detectImmersiveRight(e.getX(), e.getY()))) {

                            activity.showGestureLayer(2000);

                            return true;
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
                SystemClock.sleep(50);
                pointer.releaseButton(getX(e), getY(e), meta);

                secondPointerWasDown = false;
            }

            if (!inSwiping && !inScaling && thirdPointerWasDown) {
                String threePointerAction = Utils.querySharedPreferenceString(activity,
                        Constants.threePointerTouchAction, "keyboard");

                if ("keyboard".equals(threePointerAction)) {
                    activity.toggleKeyboard(null);
                } else if ("gesture".equals(threePointerAction)) {
                    // 唤出手势功能
                    activity.toggleGestureLayer();
                    canvas.getHandler().postDelayed(() -> {
                        activity.hideGestureLayer();
                    }, 1000);

                    if (touchpadFeedback) {
                        activity.sendShortVibration();
                    }
                } else {
                    pointer.middleButtonDown(getX(e), getY(e), meta);
                    SystemClock.sleep(50);
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

                // release the drag button down
                pointer.releaseButton(getX(e), getY(e), meta);

                SystemClock.sleep(50);

                if (totalMoveX < 8 && totalMoveY < 8) {
                    // if the double tap performed without any movement, perform a additional click
                    // to form a double click. note that the first click is performed during the drag

                    pointer.leftButtonDown(getX(e), getY(e), meta);
                    SystemClock.sleep(50);
                    pointer.releaseButton(getX(e), getY(e), meta);
                }

                if (dragHelped) {
                    // 临时放大已结束，恢复原始缩放比例
                    canvas.scaler.changeZoom(activity, lastZoomFactor / canvas.getZoomFactor(), pointer.getX(), pointer.getY());
                    dragHelped = false;
                    // 重置触摸分析器，避免下次拖拽立即触发放大
                    touchMovementAnalyzer.reset();
                }

                endDragModesAndScrolling();
            }

            totalMoveY = 0;
            totalMoveX = 0;
        }

        return gestureDetector.onTouchEvent(e);
    }

    /*
     * (non-Javadoc)
     * @see android.view.GestureDetector.SimpleOnGestureListener#onScroll(android.view.MotionEvent, android.view.MotionEvent, float, float)
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        GeneralUtils.debugLog(debugLogging, TAG, "onScroll, e1: " + e1 + ", e2:" + e2);
    
        if (activity.isToolbarShowing()) {
            return true;
        }
    
        cumulatedX += distanceX;
        cumulatedY += distanceY;
    
        final int meta = e2.getMetaState();
        boolean twoFingers = (e1.getPointerCount() == 2);
        twoFingers = twoFingers || (e2.getPointerCount() == 2);
    
        if (!twoFingers && !immersiveSwipeX && !immersiveSwipeY) {
            if (System.currentTimeMillis() - lastScrollTimeMs < POINTER_SAMPLING_MS) {
                return true;
            }

            Pair<Integer, Integer> pointerPos = computePointerPos(-cumulatedX, -cumulatedY, 1.5f);
    
            pointer.moveMouse(pointerPos.first, pointerPos.second, meta);
    
            canvas.movePanToMakePointerVisible();
    
            cumulatedX = 0;
            cumulatedY = 0;
    
            lastScrollTimeMs = System.currentTimeMillis();
    
            return true;
        }
    
        if (inScaling) {
            return true;
        }
    
        if (thirdPointerWasDown) {
            return true;
        }

        // Decrease sampling time intervals when screen fresh rate is high.
        long scrollSamplingTimeMs = Math.min(1000 / canvas.fpsCounter.getAvgFps(), SCROLL_SAMPLING_MS);
        if (System.currentTimeMillis() - lastScrollTimeMs < scrollSamplingTimeMs) {
            return true;
        }
    
        if (!inScrolling && twoFingers && (Math.abs(distanceX) > 4 || Math.abs(distanceY) > 4)) {
            inScrolling = true;
            inSwiping = true;
        }
    
        // Calculate swipe speed and apply acceleration using the helper
        long currentTime = System.currentTimeMillis();
        // 使用指针加速助手计算加速倍数，双指滑动的基础倍数为1.6f
        float speedMultiplier = pointerAccelerationHelper.calculateAccelerationMultiplier(
            currentTime, cumulatedX, cumulatedY, 1.2f);
    
        // Make distanceX/Y display density independent with speed-based acceleration.
        distanceX = (cumulatedX / displayDensity) * canvas.getZoomFactor() * speedMultiplier;
        distanceY = (cumulatedY / displayDensity) * canvas.getZoomFactor() * speedMultiplier;
    
        // If in swiping mode, indicate a swipe at regular intervals.
        if (inSwiping || immersiveSwipeX || immersiveSwipeY) {
            scrollDown = false;
            scrollUp = false;
            scrollRight = false;
            scrollLeft = false;
    
            doScroll(getX(e2), getY(e2), distanceX, distanceY, meta);
        }
    
        cumulatedX = 0;
        cumulatedY = 0;
    
        lastScrollTimeMs = System.currentTimeMillis();
    
        return false;
    }

    public boolean doScroll(int x, int y, float distanceX, float distanceY, int meta) {
        if (distanceY > 0) {
            scrollDown = true;
        } else if (distanceY < 0) {
            scrollUp = true;
        }

        if (distanceX > 0) {
            scrollRight = true;
        } else if (distanceX < 0) {
            scrollLeft = true;
        }

        if (cumulatedY * distanceY < 0) {
            cumulatedY = 0;
        }

        if (cumulatedX * distanceX < 0) {
            cumulatedX = 0;
        }

        // get the relative moving distance compared to one step
        float ratioY = distanceY * displayDensity / 2;
        float ratioX = distanceX * displayDensity / 2;

        // The direction is just up side down.
        int newY = (int) -(ratioY);
        int newX = (int) (ratioX);
        int delta = 0;

        if (Math.abs(distanceY) >= Math.abs(distanceX)) {
            scrollRight = false;
            scrollLeft = false;
        } else {
            scrollUp = false;
            scrollDown = false;
        }

        if ((scrollUp || scrollDown) && !immersiveSwipeX) {
            if (distanceY < 0 && newY == 0) {
                delta = 0;
            } else if (distanceY > 0 && newY == 0) {
                delta = 0;
            } else {
                delta = newY;
            }

            if (delta == 0) {
                return true;
            }

            if (delta > 255) {
                delta = 255;
            } else if (delta < -255) {
                delta = -255;
            }

            if (delta < 0) {
                // use positive number to represent the component directly for
                // the least two bytes
                delta = 256 + delta;
            }

            lastDelta = delta;

            // Set the coordinates to where the swipe began (i.e. where scaling started).
            sendScrollEvents(x, y, delta, meta);

            swipeSpeed = 1;
        }

        if ((scrollRight || scrollLeft) && !immersiveSwipeY) {
            if (distanceX < 0 && newX == 0) {
                delta = 0;
            } else if (distanceX > 0 && newX == 0) {
                delta = 0;
            } else {
                delta = newX;
            }

            if (delta == 0) {
                return true;
            }

            if (delta > 255) {
                delta = 255;
            } else if (delta < -255) {
                delta = -255;
            }

            if (delta < 0) {
                // use positive number to represent the component directly for
                // the least two bytes
                delta = 256 + delta;
            }

            lastDelta = delta;

            // Set the coordinates to where the swipe began (i.e. where scaling started).
            sendScrollEvents(x, y, delta, meta);

            swipeSpeed = 1;
        }

        return false;
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandlerGeneric#getX(android.view.MotionEvent)
     */
    protected int getX(MotionEvent e) {
        RemotePointer p = canvas.getPointer();
        if (dragMode || rightDragMode || middleDragMode) {
            float distanceX = e.getX() - dragX;
            dragX = e.getX();

            // Compute the absolute new X coordinate.
            return Math.round(p.getX() + distanceX);
        }

        dragX = e.getX();
        return p.getX();
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandlerGeneric#getY(android.view.MotionEvent)
     */
    protected int getY(MotionEvent e) {
        RemotePointer p = canvas.getPointer();
        if (dragMode || rightDragMode || middleDragMode) {
            float distanceY = e.getY() - dragY;
            dragY = e.getY();

            // Compute the absolute new Y coordinate.
            return Math.round(p.getY() + distanceY);
        }

        dragY = e.getY();
        return p.getY();
    }
}
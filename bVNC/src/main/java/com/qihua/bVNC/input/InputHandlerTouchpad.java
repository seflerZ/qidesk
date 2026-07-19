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
import com.qihua.bVNC.BuildConfig;
import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.util.GeneralUtils;
import com.qihua.bVNC.R;
import com.qihua.bVNC.connection.ProtocolType;

import java.util.concurrent.Semaphore;

public class InputHandlerTouchpad extends InputHandlerGeneric {
    public static final String ID = "TOUCHPAD_MODE";
    static final String TAG = "InputHandlerTouchpad";

    public InputHandlerTouchpad(RemoteCanvasActivity activity, RemoteCanvas canvas, RemoteCanvas touchpad,
                                RemotePointer pointer, boolean debugLogging) {
        super(activity, canvas, touchpad, pointer, debugLogging);

        this.displayDensity = activity.getResources().getDisplayMetrics().density;

        // 惯性滚动:高级功能,受用户开关控制,且 free 版(EDGE_ENABLED=false)运行时强制关闭
        inertiaScrollingEnabled = Utils.querySharedPreferenceBoolean(activity.getApplicationContext(),
                Constants.inertiaEnabled, true) && BuildConfig.EDGE_ENABLED;

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

                float speedX = lastSpeedX * inertiaBaseInterval;
                float speedY = lastSpeedY * inertiaBaseInterval;

                if (inertiaSwiping) {
                    while ((Math.abs(speedX) > INERTIA_STOP_THRESHOLD || Math.abs(speedY) > INERTIA_STOP_THRESHOLD)
                            && !inertiaThread.isInterrupted()) {
                        doScroll(pointer.getX(), pointer.getY(), -speedX, -speedY, inertiaMetaState);

                        speedX *= INERTIA_DECAY;
                        speedY *= INERTIA_DECAY;

                        SystemClock.sleep(inertiaBaseInterval);
                    }
                } else {
                    while ((Math.abs(speedX) > INERTIA_STOP_THRESHOLD || Math.abs(speedY) > INERTIA_STOP_THRESHOLD)
                            && !inertiaThread.isInterrupted()) {
                        int nextX = Math.round(pointer.getX() + speedX);
                        int nextY = Math.round(pointer.getY() + speedY);
                        pointer.moveMouse(nextX, nextY, inertiaMetaState);

                        // pan 节流:只在高速时调,避免每 16ms 一次布局
                        if (Math.abs(speedX) > PAN_TRIGGER_THRESHOLD || Math.abs(speedY) > PAN_TRIGGER_THRESHOLD) {
                            canvas.movePanToMakePointerVisible();
                        }

                        speedX *= INERTIA_DECAY;
                        speedY *= INERTIA_DECAY;

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
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#getDescription()
     */
    @Override
    public String getDescription() {
        return canvas.getResources().getString(R.string.input_method_touchpad_description);
    }

    /**
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#getId()
     */
    @Override
    public String getId() {
        return ID;
    }

    // Add the following variables in the class member variable area
    private long lastScrollTimeMs = System.currentTimeMillis();
    // 边缘滑条重绘的独立节流门:不能复用 lastScrollTimeMs——onScroll 的 immersive 分支
    // 每次事件都会写 lastScrollTimeMs,同一事件流里紧接着调 updateActiveEdgeSlider 时
    // 时间差恒为 ~0,复用会让滑条永远被跳过。用独立时间戳与 SCROLL_SAMPLING_MS 对齐。
    private long lastEdgeUpdateMs = 0;

    // inertia scrolling state (moved down from InputHandlerGeneric; touchpad-only)
    private Thread inertiaThread;
    private final Semaphore inertiaSemaphore = new Semaphore(0);
    private long inertiaStartTime = 0;
    private long inertiaBaseInterval = 16;
    private boolean inertiaScrollingEnabled = false;
    private boolean inertiaSwiping = false;
    private int inertiaMetaState = 0;
    private float lastSpeedX = 0;
    private float lastSpeedY = 0;
    private float lastX = 0;
    private float lastY = 0;
    // 单指移动动量采样:上次在 onScroll 单指分支更新光标的时刻,用于算松手速度 + 停顿判定
    private long lastMoveSampleMs = 0;

    // SSH text selection. Set by onSshLongPress (driven by the standard
    // InputHandlerGeneric.onLongPress path when the active protocol
    // is SSH). When armed, ACTION_MOVE extends the highlight and
    // ACTION_UP pops the AlertDialog instead of releasing a mouse
    // button. Anchor coords are kept so the popup can be positioned
    // at the long-press point.
    private boolean sshSelectionMode = false;
    private float sshAnchorViewX = 0f, sshAnchorViewY = 0f;

    // 指数衰减近似: 0.92 每 16ms tick ≈ e^(-0.083*16) ≈ 0.92,30 帧 ≈ 8% 残余
    private static final float INERTIA_DECAY = 0.92f;
    // 速度小于此阈值(px/tick)即停止,避免无限逼近 0
    private static final float INERTIA_STOP_THRESHOLD = 0.5f;
    // pan 节流:tick 内速度超过此阈值才调 movePanToMakePointerVisible,避免每 16ms 一次布局
    private static final float PAN_TRIGGER_THRESHOLD = 50f;
    // 单指移动动量:松手时每 tick 光标位移低于此值不触发滑行,精细微调不飘(仅快甩才滑)
    private static final float INERTIA_FLING_MIN_SPEED = 4f;
    // 松手距最后一次移动超过此时长视为已停顿,不触发滑行,避免"移动-停顿-松手"误滑
    private static final long INERTIA_FLING_TIMEOUT_MS = 60;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // SSH selection short-circuit runs BEFORE super.onTouchEvent so
        // its DOWN-time vibration and the GestureDetector's onLongPress
        // path don't fire alongside our sshLongPress timer (which is
        // what actually drives selection). Without this guard the user
        // gets two to three buzzes per selection.
        if (sshSelectionMode && pointer instanceof RemoteSshPointer) {
            return onTouchEventSsh(e);
        }

        boolean pResult = super.onTouchEvent(e);
        if (pResult) {
            return true;
        }

        android.util.Log.e(TAG, "onTouchEvent, e: " + e);

        final int action = e.getActionMasked();
        final int index = e.getActionIndex();
        final int pointerID = e.getPointerId(index);
        final int meta = e.getMetaState();

        // SSH selection is armed by onSshLongPress (called from
        // InputHandlerGeneric.onLongPress). Once armed, MOVE/UP go
        // through onTouchEventSsh so the highlight updates and the
        // menu pops. Down-side vibration / long-press timer are kept
        // on the standard path so we don't double-fire with the
        // base class.
        if (sshSelectionMode && pointer instanceof RemoteSshPointer) {
            return onTouchEventSsh(e);
        }

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

        // handle scroll(one\two fingers), long press and double tap
        if (gestureDetector.onTouchEvent(e)) {
            return true;
        }

        android.util.Log.e(TAG, "onTouchEvent: pointerID: " + pointerID);
        // 快照拖动状态:下面 ACTION_UP 分支里的 endDragModesAndScrolling() 会把 dragMode 清成
        // false,若等到后面惯性判断时再读 dragMode 就已失效,导致拖动松手也误触发惯性滚动。
        boolean wasDragging = dragMode || rightDragMode || middleDragMode;
        switch (pointerID) {
            case 0:
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        // We have put down first pointer on the screen, so we can reset the state of all click-state variables.
                        // Permit sending mouse-down event on long-tap again.
                        secondPointerWasDown = false;
                        // Permit right-clicking again.
                        thirdPointerWasDown = false;
                        // Cancel any effect of scaling having "just finished" (e.g. ignoring scrolling).
                        scalingJustFinished = false;
                        // Cancel drag modes and scrolling.
                        endDragModesAndScrolling();
                        canvas.cursorBeingMoved = true;
                        // If we are manipulating the desktop, turn off bitmap filtering for faster response.
                        canvas.bitmapData.paint.setFilterBitmap(false);

                        gestureX = e.getX();
                        gestureY = e.getY();

                        lastDragX = e.getX();
                        lastDragY = e.getY();

                        lastSpeedX = lastSpeedY = 0;
                        // 新手势开始,清采样时间戳,避免用上次手势的旧时刻算出巨大 dt
                        lastMoveSampleMs = 0;

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

                        detectImmersiveSwipe(e.getX(), e.getY());
                        break;
                    case MotionEvent.ACTION_MOVE:
                        long timeElapsed = System.currentTimeMillis() - inertiaStartTime;
                        long interval = inertiaBaseInterval * 2;

                        if (timeElapsed > interval) {
                            if (lastX != 0) {
                                lastSpeedX = ((e.getX() - lastX) / timeElapsed) / canvas.getZoomFactor() / 1.6f;
                                lastSpeedX = lastSpeedX * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
                            }

                            if (lastY != 0) {
                                lastSpeedY = ((e.getY() - lastY) / timeElapsed) / canvas.getZoomFactor() / 1.6f;
                                lastSpeedY = lastSpeedY * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
                            }

                            inertiaStartTime = System.currentTimeMillis();
                        }

                        android.util.Log.e(TAG, "onTouchEvent: ACTION_MOVE");
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

                            // when reached to the edge, keep the cursor continue moving
                            int x = getDragPointerX(e);
                            int y = getDragPointerY(e);

                            if (System.currentTimeMillis() - lastDragHelpTimeMs > 200) {
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

                        // Update edge slider position during swipe
                        if (inSwiping && System.currentTimeMillis() - lastEdgeUpdateMs >= SCROLL_SAMPLING_MS) {
                            updateActiveEdgeSlider(e.getX(), e.getY());
                            lastEdgeUpdateMs = System.currentTimeMillis();
                        }

                        totalMoveX += Math.abs(e.getX() - lastX);
                        totalMoveY += Math.abs(e.getY() - lastY);

                        lastX = e.getX();
                        lastY = e.getY();

                        break;
                    case MotionEvent.ACTION_UP:
                        hideEdgeViews();

                        if (dragMode || rightDragMode || middleDragMode) {
                            // the mouse down event is at onDoubleTap()
                            pointer.releaseButton(getDragPointerX(e), getDragPointerY(e), meta);

                            if (dragHelped) {
                                // 临时放大已结束，恢复原始缩放比例
                                canvas.scaler.changeZoom(activity, lastZoomFactor / canvas.getZoomFactor(), pointer.getX(), pointer.getY());
                                dragHelped = false;
                                // 重置触摸分析器，避免下次拖拽立即触发放大
                                touchMovementAnalyzer.reset();
                            }

                            endDragModesAndScrolling();
                        }

                        cumulatedX = 0;
                        cumulatedY = 0;

                        lastDragX = 0;
                        lastDragY = 0;

                        totalMoveX = 0;
                        totalMoveY = 0;

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
                pointer.rightButtonDown(getDragPointerX(e), getDragPointerY(e), meta);
                SystemClock.sleep(50);
                pointer.releaseButton(getDragPointerX(e), getDragPointerY(e), meta);

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
                    canvas.getHandler().postDelayed(() -> activity.hideGestureLayer(), 1000);

                    if (touchpadFeedback) {
                        activity.sendShortVibration();
                    }
                } else {
                    pointer.middleButtonDown(getDragPointerX(e), getDragPointerY(e), meta);
                    SystemClock.sleep(50);
                    pointer.releaseButton(getDragPointerX(e), getDragPointerY(e), meta);
                }

                thirdPointerWasDown = false;
            }

            canEnlarge = true;

            // for single finger movement
            if (inertiaScrollingEnabled && !wasDragging && !inSwiping) {
                if (activity.isToolbarShowing() && canvas.connection.getEnableGesture()) {

                } else {
                    // 仅快甩才滑行:松手速度(每 tick 光标位移)超阈值,且松手距最后一次采样未停顿
                    float flingSpeedX = lastSpeedX * inertiaBaseInterval;
                    float flingSpeedY = lastSpeedY * inertiaBaseInterval;
                    boolean fastEnough = Math.abs(flingSpeedX) > INERTIA_FLING_MIN_SPEED
                            || Math.abs(flingSpeedY) > INERTIA_FLING_MIN_SPEED;
                    boolean notPaused = lastMoveSampleMs != 0
                            && System.currentTimeMillis() - lastMoveSampleMs <= INERTIA_FLING_TIMEOUT_MS;

                    if (fastEnough && notPaused) {
                        inertiaMetaState = e.getMetaState();
                        inertiaSemaphore.release();
                    }
                }
            }

            // for two finger inertia scrolling
            if (inertiaScrollingEnabled && inSwiping) {
                // 与单指一致:仅快甩才滑行,松手速度超阈值且未停顿。停顿判定用 lastScrollTimeMs
                // (滚动路径走 doScroll,不经过单指分支,lastMoveSampleMs 不会被更新)。
                float flingSpeedX = lastSpeedX * inertiaBaseInterval;
                float flingSpeedY = lastSpeedY * inertiaBaseInterval;
                boolean fastEnough = Math.abs(flingSpeedX) > INERTIA_FLING_MIN_SPEED
                        || Math.abs(flingSpeedY) > INERTIA_FLING_MIN_SPEED;
                boolean notPaused = System.currentTimeMillis() - lastScrollTimeMs <= INERTIA_FLING_TIMEOUT_MS;

                if (fastEnough && notPaused) {
                    // 惯性阶段手指已离开,immersive(跟随手指在边缘的位置)语义已不存在。
                    // 边缘滚动松手不走 endDragModesAndScrolling,immersiveSwipeX/Y 残留为 true,
                    // 会在 doScroll(638/671 行)门掉某方向分量,导致边缘惯性不如双指顺。
                    // 这里清掉,让边缘惯性与双指走完全相同的 doScroll 路径。
                    immersiveSwipeX = false;
                    immersiveSwipeY = false;
                    inertiaSwiping = true;
                    inertiaSemaphore.release();
                }
            }
        }

        return true;
    }

    /**
     * SSH-mode touch handler. Long-press entry into selection is
     * driven by the standard {@link InputHandlerGeneric#onLongPress}
     * path — it calls our {@link #onSshLongPress} subclass hook when
     * the active protocol is SSH, which sets {@link #sshSelectionMode}
     * and pins the anchor at the long-press point. So all this method
     * has to do for ACTION_MOVE / ACTION_UP is extend / pop the menu.
     * ACTION_DOWN falls through to super.onTouchEvent so the standard
     * DOWN-time state-machine reset and the GestureDetector's
     * long-press timer run unchanged.
     */
    private boolean onTouchEventSsh(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && sshSelectionMode) {
            int bx = (int) e.getX();
            int by = (int) e.getY();
            ((RemoteSshPointer) pointer).extendSelectionPx(bx, by);
            canvas.invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP && sshSelectionMode) {
            RemoteSshPointer sshPointer = (RemoteSshPointer) pointer;
            String text = sshPointer.consumeSelectedText();
            sshSelectionMode = false;
            android.util.Log.e(TAG,
                    "ACTION_UP sshSelection: textLen=" + (text == null ? -1 : text.length()));
            int[] screenLoc = new int[2];
            canvas.getLocationOnScreen(screenLoc);
            float screenX = screenLoc[0] + sshAnchorViewX;
            float screenY = screenLoc[1] + sshAnchorViewY;
            activity.showSelectionMenu(text, screenX, screenY);
            canvas.invalidate();
            return true;
        }
        return false;
    }

    @Override
    protected void onSshLongPress(MotionEvent e) {
        // Standard long-press fires after ~500 ms (ViewConfiguration
        // default) on a finger that hasn't drifted past the touch
        // slop. Pin the anchor at the long-press coordinates and arm
        // selection mode. The base class has already vibrated once
        // — we deliberately do NOT vibrate again.
        sshSelectionMode = true;
        sshAnchorViewX = e.getX();
        sshAnchorViewY = e.getY();
        if (pointer instanceof RemoteSshPointer) {
            ((RemoteSshPointer) pointer).enterSelectionPx((int) e.getX(), (int) e.getY());
        }
        canvas.invalidate();
        android.util.Log.e(TAG,
                "onSshLongPress FIRED anchor=(" + (int) e.getX() + "," + (int) e.getY() + ")");
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        // SSH mode no longer uses double-tap as the entry into selection
        // mode — long-press does that now (see the SSH branch in
        // InputHandlerGeneric.onLongPress). Double-tap still triggers the
        // original mouse-double-click semantics for non-SSH protocols.
        if (dragMode || detectImmersiveRange(e.getX(), e.getY())) {
            return false;
        }

        if (touchpadFeedback) {
            activity.sendShortVibration();
        }

        // this down will be release when the drag is completed in ACTION_UP event
        pointer.leftButtonDown(getDragPointerX(e), getDragPointerY(e), 0);

        dragMode = true;

        // consider a double click if drag not present
        canvas.getHandler().postDelayed(() -> {
            if (totalMoveY > 0f || totalMoveX > 0f) {
                // drag there, not a double click gesture
                return;
            }

            // no drag after double click, consider it a double click
            pointer.leftButtonDown(getDragPointerX(e), getDragPointerY(e), 0);
            pointer.releaseButton(getDragPointerX(e), getDragPointerY(e), 0);
        }, 150);

        return true;
    }

    /**
     * (non-Javadoc)
     * @see android.view.GestureDetector.SimpleOnGestureListener#onScroll(android.view.MotionEvent, android.view.MotionEvent, float, float)
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
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

            // 动量采样:用光标坐标位移(而非手指位移)除以采样间隔,量纲对齐惯性线程
            // else 分支的 pointer.getX()+speed,松手滑行速度不会突变。
            long now = System.currentTimeMillis();
            long dt = now - lastMoveSampleMs;
            if (lastMoveSampleMs != 0 && dt > 0) {
                lastSpeedX = (pointerPos.first - pointer.getX()) / (float) dt;
                lastSpeedY = (pointerPos.second - pointer.getY()) / (float) dt;
            } else {
                lastSpeedX = lastSpeedY = 0;
            }
            lastMoveSampleMs = now;

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
        long scrollSamplingTimeMs = SCROLL_SAMPLING_MS;
        if (canvas.fpsCounter.getAvgFps() > 0) {
            scrollSamplingTimeMs = Math.min(1000 / canvas.fpsCounter.getAvgFps(), SCROLL_SAMPLING_MS);
        }

        if (System.currentTimeMillis() - lastScrollTimeMs < scrollSamplingTimeMs) {
           return true;
        }
    
        if (!inScrolling && twoFingers && (Math.abs(distanceX) > 4 || Math.abs(distanceY) > 4)) {
            inScrolling = true;
            inSwiping = true;
        }
    
        // Calculate swipe speed and apply acceleration using the helper
        float speedMultiplier = pointerAccelerationHelper.calculateAccelerationMultiplier(
                System.currentTimeMillis(), cumulatedX, cumulatedY, 1.2f);
    
        // Make distanceX/Y display density independent with speed-based acceleration.
        distanceX = (cumulatedX / displayDensity) * canvas.getZoomFactor() * speedMultiplier;
        distanceY = (cumulatedY / displayDensity) * canvas.getZoomFactor() * speedMultiplier;

        // If in swiping mode, indicate a swipe at regular intervals.
        if (inSwiping || immersiveSwipeX || immersiveSwipeY) {
            scrollDown = false;
            scrollUp = false;
            scrollRight = false;
            scrollLeft = false;

            cumulatedX = 0;
            cumulatedY = 0;

            lastScrollTimeMs = System.currentTimeMillis();
    
            return doScroll(getDragPointerX(e2), getDragPointerY(e2), distanceX, distanceY, meta);
        }
    
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

    /**
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandlerGeneric#getX(android.view.MotionEvent)
     */
    protected int getDragPointerX(MotionEvent e) {
        RemotePointer p = canvas.getPointer();
        if (dragMode || rightDragMode || middleDragMode) {
            float distanceX = 0;
            if (lastDragX > 0) {
                distanceX = e.getX() - lastDragX;
            }

            lastDragX = e.getX();

            // Compute the absolute new X coordinate.
            return Math.round(p.getX() + distanceX);
        }

        return p.getX();
    }

    /**
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandlerGeneric#getY(android.view.MotionEvent)
     */
    protected int getDragPointerY(MotionEvent e) {
        RemotePointer p = canvas.getPointer();
        if (dragMode || rightDragMode || middleDragMode) {
            float distanceY = 0;
            if (lastDragY > 0) {
                distanceY = e.getY() - lastDragY;
            }

            lastDragY = e.getY();

            // Compute the absolute new Y coordinate.
            return Math.round(p.getY() + distanceY);
        }

        return p.getY();
    }
}
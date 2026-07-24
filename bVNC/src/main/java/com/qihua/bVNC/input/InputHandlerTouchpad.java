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

                        // 每一 tick 都调一次:惯性尾巴速度已衰得很小,但光标可能正好停在视图
                        // 可见边界外侧仍向同方向推,这时不调就会一直留在屏外。
                        // movePanToMakePointerVisible 内部自己判是否需要 pan(没越界时直接
                        // return false),没有越界时不会触发 resetScroll/layout,代价可控。
                        canvas.movePanToMakePointerVisible();

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
                    // ============================================================
                    // 低帧率协议 (RDP / VNC) 上的 scroll inertia 显式禁用
                    // ============================================================
                    // 背景:doScroll 对边缘 / 双指滚动做了两层降频处理——
                    //   1. delta 系数减半 (distanceY * density / 4,原 /2)
                    //   2. RDP 路径计划用 RdpScrollCoalescer 32ms 窗合包 (FLUSH_MS=32)
                    // 这两层处理后每包 delta 翻倍,RDP 服务器侧 wheel 累加更稳定
                    // (参见相关 RdpScrollCoalescer 调研历史)。但在松手阶段,
                    // inertia 路径再走 doScroll 时,中间只有几个 tick,
                    // 这些 tick 各被合 1~2 个 30Hz 窗口,服务器可能收不到或
                    // 被新一轮手势覆盖,体感是"手指在快、松手后慢"。
                    //
                    // 与 NVStream/SPICE/SSH 的差异:NVStream 内部 batch
                    // 路径天然无此问题,所以不需要在这里 bypass。
                    //
                    // 副作用说明:这里的 return-true 只阻止 scroll inertia
                    // 释放,不影响 doScroll 本体,也不影响单指 pointer inertia
                    // (pointer inertia 走 inertiaThread 的 else 分支
                    // → pointer.moveMouse,跟此处分支无关)。
                    //
                    // 未来如果加了新的 RFB 派生指针类型 (如 RemoteSpiceRfbPointer),
                    // 想继承同样的处理就把它的 instanceof 加进下面这个或判断里。
                    // ============================================================
                    if (pointer instanceof RemoteRdpPointer || pointer instanceof RemoteVncPointer) {
                        return true;
                    }
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
            // Anchor the popup at the FINGER-UP point, not the long-press
            // anchor — the user dragged to extend, so the up position is
            // where their thumb ended up. Falling back to the long-press
            // anchor (sshAnchorViewX/Y) makes the menu pop at the start
            // of the selection, which feels wrong.
            int[] screenLoc = new int[2];
            canvas.getLocationOnScreen(screenLoc);
            float screenX = screenLoc[0] + e.getX();
            float screenY = screenLoc[1] + e.getY();
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

            // 加速基数 1.5 → 1.35 → 1.2 → 1.0,接近无加速线性
            Pair<Integer, Integer> pointerPos = computePointerPos(-cumulatedX, -cumulatedY, 1.0f);

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

        // 协议感知采样门:
        //   - NVStream/SPICE 走 SCROLL_SAMPLING_MS (30ms)。NV 内部 60fps,30ms 是合适节拍。
        //   - RDP/VNC 帧率低,30ms 不会到一帧(需要 100ms+),用频繁小 tick 反而不会被
        //     服务器处理掉。100ms 门让每个 scroll 事件携带更多合并位移,服务端能一次
        //     完成 notch 累加。合并效果同 pointer 移动的 30ms 同窗逻辑,但 RDP/VNC
        //     可以容忍更大窗。
        // 同时保留:高于屏刷的速率下不至于一秒百次发包。
        boolean lowFpsScroll = pointer instanceof RemoteRdpPointer || pointer instanceof RemoteVncPointer;
        long scrollSamplingTimeMs;
        if (lowFpsScroll) {
            scrollSamplingTimeMs = 100;
        } else {
            scrollSamplingTimeMs = SCROLL_SAMPLING_MS;
            if (canvas.fpsCounter.getAvgFps() > 0) {
                scrollSamplingTimeMs = Math.min(1000 / canvas.fpsCounter.getAvgFps(), SCROLL_SAMPLING_MS);
            }
        }

        if (System.currentTimeMillis() - lastScrollTimeMs < scrollSamplingTimeMs) {
           return true;
        }
    
        // 双指 swipe 起判:onScroll 在入口已经过 30ms sampling 门(见上行),
        // 再叠 distance > 2 是冗余(threshold 的设计场景已经被 sampling gate 覆盖)。
        // 只保留“首次进入两指 scroll” 的状态切换。
        if (!inScrolling && twoFingers) {
            inScrolling = true;
            inSwiping = true;
        }
    
        // Calculate swipe speed and apply acceleration using the helper
        float speedMultiplier = pointerAccelerationHelper.calculateAccelerationMultiplier(
                System.currentTimeMillis(), cumulatedX, cumulatedY, 1.3f);
    
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

        // 边缘 / 双指 scroll:onScroll 在过 sampling 门时已经把 cumulatedX/Y 累加并清零,
        // 这里 distanceX/Y 已含本窗合并值。
        // ratioY / 4:distanceY 已经被 zoomFactor(常 ≥ 2)放大,除 4 让单 tick 位移
        // 在服务器端转 notch 时更平滑,且与服务器 WHEEL_DELTA 数值匹配。
        // 频率上由 onScroll 那一道 30ms sampling 门统一节流,本函数不再叠加门。
        float ratioY = distanceY * displayDensity / 4;
        float ratioX = distanceX * displayDensity / 4;

        // The direction is just up side down.
        int newY = (int) -(ratioY);
        int newX = (int) (ratioX);

        if (Math.abs(distanceY) >= Math.abs(distanceX)) {
            scrollRight = false;
            scrollLeft = false;
        } else {
            scrollUp = false;
            scrollDown = false;
        }

        if ((scrollUp || scrollDown) && !immersiveSwipeX) {
            // 双指 / 沉浸边缘 scroll 的 30ms 采样门由 onScroll line 691 统一处理,
            // 这里不重复门。delta 系数 /4 让单 tick 位移减半,与服务器端 wheelDelta 配合。
            int delta = newY;  // newY == 0 已被下方 if 跳过

            if (delta != 0) {
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

                sendScrollEvents(x, y, delta, meta);

                swipeSpeed = 1;
            }
        }

        if ((scrollRight || scrollLeft) && !immersiveSwipeY) {
            // 见上 Y 分支注释
            int delta = newX;  // newX == 0 已被下方 if 跳过

            if (delta != 0) {
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

                sendScrollEvents(x, y, delta, meta);

                swipeSpeed = 1;
            }
        }

        return false;
    }

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
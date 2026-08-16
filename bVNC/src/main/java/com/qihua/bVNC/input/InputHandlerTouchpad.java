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

public class InputHandlerTouchpad extends InputHandlerGeneric {
    public static final String ID = "TOUCHPAD_MODE";
    static final String TAG = "InputHandlerTouchpad";
    public static final int SCROLL_SAMPLING_MS = 30;

    public InputHandlerTouchpad(RemoteCanvasActivity activity, RemoteCanvas canvas, RemoteCanvas touchpad,
                                RemotePointer pointer, boolean debugLogging) {
        super(activity, canvas, touchpad, pointer, debugLogging);

        this.displayDensity = activity.getResources().getDisplayMetrics().density;

        // 惯性滚动:高级功能,受用户开关控制,且 free 版(EDGE_ENABLED=false)运行时强制关闭。
        // SSH 也禁用:终端文本滚动由 libvterm 渲染,惯性导致越界 scroll 会出现
        // alt-buffer / scrollback 状态错乱;另外 SSH 文本选择(长按选词)在惯性尾巴
        // 里会跟着滑,体感不稳。
        // 后台线程本身由基类启动(总在跑、信号量驱动),这里只决定要不要 release。
        enableInertiaScrolling(Utils.querySharedPreferenceBoolean(activity.getApplicationContext(),
                Constants.inertiaEnabled, true) && BuildConfig.EDGE_ENABLED
                && !isSshPointer());
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

    // make sure first scroll triggers
    private long lastScrollTimeMs = 0;
    // 边缘滑条重绘的独立节流门:不能复用 lastScrollTimeMs——onScroll 的 immersive 分支
    // 每次事件都会写 lastScrollTimeMs,同一事件流里紧接着调 updateActiveEdgeSlider 时
    // 时间差恒为 ~0,复用会让滑条永远被跳过。用独立时间戳与 SCROLL_SAMPLING_MS 对齐。
    private long lastEdgeUpdateMs = 0;
    // inertia scrolling state lives in InertiaScroller (owned by the
    // base class); this class just opts in via enableInertiaScrolling(...)
    // and drives the sampling API exposed through InputHandlerGeneric.
    private float lastX = 0;
    private float lastY = 0;

    // SSH text selection. Set by onSshLongPress (driven by the standard
    // InputHandlerGeneric.onLongPress path when the active protocol
    // is SSH). When armed, ACTION_MOVE extends the highlight and
    // ACTION_UP pops the AlertDialog instead of releasing a mouse
    // button. Anchor coords are kept so the popup can be positioned
    // at the long-press point.
    private boolean sshSelectionMode = false;

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
                        // Open scroll gate for this gesture. Must be ACTION_DOWN, not ACTION_UP:
                        // ACTION_UP's inertia trigger still reads lastScrollTimeMs to judge "not paused",
                        // so clearing it in the same handler would self-conflict.
                        lastScrollTimeMs = 0;
                        canvas.cursorBeingMoved = true;
                        // If we are manipulating the desktop, turn off bitmap filtering for faster response.
                        canvas.bitmapData.paint.setFilterBitmap(false);

                        gestureX = e.getX();
                        gestureY = e.getY();

                        lastDragX = e.getX();
                        lastDragY = e.getY();

                        // 新手势开始:基类负责清采样时间戳 + 中断上一手惯性尾巴,
                        // 避免用上次手势的旧时刻算出巨大 dt。
                        resetInertiaSampling();
                        stopInertia();

                        lastX = e.getX();
                        lastY = e.getY();

                        if (touchpadFeedback) {
                            activity.sendShortVibration();
                        }

                        detectImmersiveSwipe(e.getX(), e.getY());
                        break;
                    case MotionEvent.ACTION_MOVE:
                        // 手指位移动量采样(松手速度 → 惯性尾巴)
                        recordFingerMoveSample(e.getX(), e.getY(), lastX, lastY, canvas.getZoomFactor());

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
            if (totalMoveX < 1 && totalMoveY < 1 && secondPointerWasDown) {
                pointer.rightButtonDown(getDragPointerX(e), getDragPointerY(e), meta);
                SystemClock.sleep(50);
                pointer.releaseButton(getDragPointerX(e), getDragPointerY(e), meta);

                secondPointerWasDown = false;
            }

            cumulatedX = 0;
            cumulatedY = 0;

            lastDragX = 0;
            lastDragY = 0;

            totalMoveX = 0;
            totalMoveY = 0;

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
            if (isInertiaEnabled() && !wasDragging && !inSwiping) {
                if (!activity.isToolbarShowing() || !canvas.connection.getEnableGesture()) {
                    tryStartSingleFingerInertia(meta);
                }
            }

            // for two finger inertia scrolling
            if (isInertiaEnabled() && inSwiping) {
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
                if (isLowFrameRateScrollProtocol()) {
                    return true;
                }
                tryStartScrollInertia(meta, lastScrollTimeMs);
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
            SystemClock.sleep(50);
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
            return false;
        }

        if (inScaling || thirdPointerWasDown) {
            return false;
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

            Pair<Integer, Integer> pointerPos = computePointerPos(-cumulatedX, -cumulatedY, 0.6f);

            // 动量采样:用光标坐标位移(而非手指位移)除以采样间隔,量纲对齐惯性线程
            // else 分支的 pointer.getX()+speed,松手滑行速度不会突变。
            recordScrollCursorSample(pointerPos.first, pointerPos.second);

            pointer.moveMouse(pointerPos.first, pointerPos.second, meta);

            canvas.movePanToMakePointerVisible();

            cumulatedX = 0;
            cumulatedY = 0;

            lastScrollTimeMs = System.currentTimeMillis();

            return true;
        }

        // 协议感知采样门:
        //   - NVStream/SPICE 走 SCROLL_SAMPLING_MS (30ms)。NV 内部 60fps,30ms 是合适节拍。
        //   - RDP/VNC 帧率低,30ms 不会到一帧(需要 100ms+),用频繁小 tick 反而不会被
        //     服务器处理掉。100ms 门让每个 scroll 事件携带更多合并位移,服务端能一次
        //     完成 notch 累加。合并效果同 pointer 移动的 30ms 同窗逻辑,但 RDP/VNC
        //     可以容忍更大窗。
        // 同时保留:高于屏刷的速率下不至于一秒百次发包。
        boolean lowFpsScroll = isLowFrameRateScrollProtocol();
        long scrollSamplingTimeMs;
        if (lowFpsScroll) {
            scrollSamplingTimeMs = 60;
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
    
        // Calculate swipe speed and apply acceleration using the helper.
        // 传本帧 delta（onScroll 已经把多帧 distance 累进 cumulatedX/Y，本 tick 增量即 cumulatedX/Y 自身——
        // helper 内部存的是上一次发送时的累计值，差分得到的就是这一发送周期里的总位移，单位 px/ms 反映真实速度）。
        float speedMultiplier = pointerAccelerationHelper.calculateAccelerationMultiplier(
                System.currentTimeMillis(), cumulatedX, cumulatedY, 1.3f
                , 1.4f, 10f);

        // 等效缩放系数:单屏 = zoomFactor(zoom 越大越灵活,常规行为);
        // 外屏 = 1.5 * dpiRatio,zoomFactor 显式抵消——dpi 补偿 + 远距离触控板加成,
        // 且 zoom 越大越收敛(避免看细节时一划出屏)。
        float effectiveZoom;
        if (canvas.isOutDisplay()) {
            int localDpi = (int) (touchpad.getDisplayDensity() * 160f);
            int extDpi = canvas.getDisplayDpi();
            float dpiRatio = (extDpi > 0 && localDpi > 0)
                    ? ((float) localDpi / (float) extDpi) : 1.0f;
            effectiveZoom = 1.5f * dpiRatio;
        } else {
            effectiveZoom = canvas.getZoomFactor();
        }

        // Make distanceX/Y display density independent. 加速直接吃在 dpi 归一化之后的 px/ms
        // 速度上 —— 飞速 swipe 时本帧 delta 大,speedMultiplier 自然冲上去,符合直觉。
        distanceX = (cumulatedX / displayDensity) * effectiveZoom * speedMultiplier;
        distanceY = (cumulatedY / displayDensity) * effectiveZoom * speedMultiplier;

        scrollDown = false;
        scrollUp = false;
        scrollRight = false;
        scrollLeft = false;

        cumulatedX = 0;
        cumulatedY = 0;

        lastScrollTimeMs = System.currentTimeMillis();

        return doScroll(getDragPointerX(e2), getDragPointerY(e2), distanceX, distanceY, meta);
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

        // The direction is just upside down. divide zoom factor to keep the scroll reasonable
        // after zoom in, otherwise it will be too fast then
        int newY = (int) (-distanceY / canvas.getZoomFactor());
        int newX = (int) (distanceX / canvas.getZoomFactor());

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
            sendChunkedScroll(x, y, newY, meta);
        }

        if ((scrollRight || scrollLeft) && !immersiveSwipeY) {
            // 见上 Y 分支注释
            sendChunkedScroll(x, y, newX, meta);
        }

        // return false so the gestureDetector's onScroll() pipeline lets the
        // scroll inertia tail (handled by InertiaScroller via the base
        // class's doScroll() callback) keep firing on the background thread
        return false;
    }

    // 大 delta 拆成多个 255 包连发;包之间 sleep 让 native queue 有消化时间,
    // 避免飞速滑动时主线程一帧塞爆 native 端导致状态错乱。
    private void sendChunkedScroll(int x, int y, int delta, int meta) {
        if (delta == 0) {
            return;
        }

        int chunk = delta > 0 ? 255 : -255;
        int encoded = chunk < 0 ? 256 + chunk : chunk;
        int remaining = Math.abs(delta);

        while (remaining >= 255) {
            sendScrollEvents(x, y, encoded, meta);
            remaining -= 255;

            SystemClock.sleep(5);
        }

        int tail = delta > 0 ? remaining : -remaining;
        lastDelta = encoded;
        if (tail != 0) {
            lastDelta = tail < 0 ? 256 + tail : tail;
            sendScrollEvents(x, y, lastDelta, meta);
        }
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
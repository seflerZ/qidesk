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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.core.util.Pair;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.connection.ProtocolType;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.util.GeneralUtils;
import com.qihua.bVNC.R;
import com.qihua.bVNC.BuildConfig;

abstract class InputHandlerGeneric extends MyGestureDectector.SimpleOnGestureListener
        implements InputHandler, ScaleGestureDetector.OnScaleGestureListener {
    private static final String TAG = "InputHandlerGeneric";
    public static final int POINTER_SAMPLING_MS = 13;

    protected final boolean debugLogging;

    // The minimum distance a scale event has to traverse the FIRST time before scaling starts.
    final double minScaleFactor = 0.1;
    protected MyGestureDectector gestureDetector;
    protected MyScaleGestureDetector scalingGestureDetector;
    // Handles to the RemoteCanvas view and RemoteCanvasActivity activity.
    protected RemoteCanvas canvas;
    protected RemoteCanvas touchpad;
    protected RemoteCanvasActivity activity;

    // Various drag modes in which we don't detect gestures.
    protected boolean panMode = false;
    protected boolean dragMode = false;
    protected boolean rightDragMode = false;
    protected boolean middleDragMode = false;
    protected float cumulatedY = 0;
    protected float cumulatedX = 0;

    protected float lastDragX, lastDragY;
    protected float gestureX, gestureY;
    protected float totalMoveX, totalMoveY;
    // These variables keep track of which pointers have seen ACTION_DOWN events.
    protected boolean secondPointerWasDown = false;
    protected boolean thirdPointerWasDown = false;

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
    boolean scrollUp = false;
    boolean scrollDown = false;
    boolean scrollLeft = false;
    boolean scrollRight = false;
    // The variables which indicates how many scroll events to send per swipe
    // event and the maximum number to send at one time.
    long swipeSpeed = 1;
    // This is how far from the top and bottom edge to detect immersive swipe.
    float immersiveSwipeRatio = 0.1f;
    boolean immersiveSwipeY = false;
    boolean immersiveSwipeX = false;
    // Some variables indicating what kind of a gesture we're currently in or just finished.
    boolean inScrolling = false;
    boolean inScaling = false;
    boolean scalingJustFinished = false;

    // What the display density is.
    float displayDensity = 0;
    // Queue which holds the last two MotionEvents which triggered onScroll
    float lastZoomFactor = 1;
    protected boolean dragHelped = false;
    protected boolean canEnlarge = true;
    private boolean immersiveSwipeEnabled = true;
    protected boolean touchpadFeedback = true;

    protected final DotMatrixEdgeView edgeRight;
    protected final DotMatrixEdgeView edgeLeft;
    protected final DotMatrixEdgeView edgeTop;
    protected final DotMatrixEdgeView edgeBottom;
    protected DotMatrixEdgeView activeEdgeSlider = null; // Currently active edge slider
    protected long lastDragHelpTimeMs;
    protected boolean dragHelpEnabled = false;

    // 滑动窗口分析器，用于检测慢速精细操作
    protected TouchMovementAnalyzer touchMovementAnalyzer;

    /** 惯性滚动后台执行器:状态、采样、松手判定、daemon worker 一体封装 */
    protected final InertiaScroller inertiaScroller;

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

        gestureDetector = new MyGestureDectector(activity, this, null, false);
        scalingGestureDetector = new MyScaleGestureDetector(activity, this);

        gestureDetector.setOnDoubleTapListener(this);

        displayDensity = touchpad.getDisplayDensity();

        // 初始化滑动窗口分析器
        touchMovementAnalyzer = new TouchMovementAnalyzer(displayDensity, debugLogging);

        touchpadFeedback = Utils.querySharedPreferenceBoolean(canvas.getContext(), Constants.touchpadFeedback);

        immersiveSwipeEnabled = Utils.querySharedPreferenceBoolean(activity.getApplicationContext()
                , Constants.touchpadEdgeWheel, true) && BuildConfig.EDGE_ENABLED;

        dragHelpEnabled = Utils.querySharedPreferenceBoolean(activity.getApplicationContext()
                , Constants.dragHelpEnabled, false);

        // 初始化指针加速助手
        pointerAccelerationHelper = new PointerAccelerationHelper();

        // 惯性后台执行器:daemon worker 始终在跑,无信号时空转 acquire()。
        // 是否真正 release 信号由 enable(...) 决定。
        // 双指 scroll inertia 走回调 lambda 回到子类自己的 doScroll() 实现,
        // 保证惯性尾巴的 scroll 行为与正常 onScroll 完全一致(同样的 immersive / edge 语义)。
        inertiaScroller = new InertiaScroller(activity, canvas, pointer,
                (x, y, dx, dy, meta) -> doScroll(x, y, dx, dy, meta));
    }

    /**
     * 子类在 super(...) 构造完成后调用,声明本会话是否启用惯性滚动。
     * <p>
     * 默认 false。{@link InertiaScroller} daemon worker 始终在跑,只是
     * 无信号时永远空转 acquire(),所以 SSH / RDP-low-fps / free 版这类
     * 想关掉的子类,只要传 false 进来即可。
     */
    protected void enableInertiaScrolling(boolean enabled) {
        inertiaScroller.enable(enabled);
    }

    /** 当前是否启用了惯性滚动。子类用于 ACTION_UP 分支短路决策。 */
    protected boolean isInertiaEnabled() {
        return inertiaScroller.isEnabled();
    }

    /**
     * 中断正在跑的惯性尾巴(新手势开始时调用,防止上一手惯性残留)。
     * 不必关心 enabled 状态——若已禁用,中断本身也无副作用。
     */
    protected void stopInertia() {
        inertiaScroller.stop();
    }

    /**
     * 标记手势开始:清采样时间戳,避免用上次手势的旧时刻算出巨大 dt。
     * 在 ACTION_DOWN 里调一下即可。
     */
    protected void resetInertiaSampling() {
        inertiaScroller.resetSampling();
    }

    /**
     * 单指手指 MOVE 时的动量采样(以手指位移 / 时间算松手速度)。
     *
     * @param fingerX    当前手指 view-X
     * @param fingerY    当前手指 view-Y
     * @param lastFingerX 上一次采样手指 view-X
     * @param lastFingerY 上一次采样手指 view-Y
     * @param zoomFactor canvas.getZoomFactor() —— 光标位置 = 手指位置 / zoom
     */
    protected void recordFingerMoveSample(float fingerX, float fingerY,
                                          float lastFingerX, float lastFingerY,
                                          float zoomFactor) {
        inertiaScroller.recordFingerMoveSample(fingerX, fingerY, lastFingerX, lastFingerY, zoomFactor);
    }

    /**
     * onScroll 单指分支的动量采样(以光标位移 / 时间算松手速度)。
     *
     * @param cursorX 本帧目标光标 X(已算入加速 + sensitivity)
     * @param cursorY 本帧目标光标 Y
     */
    protected void recordScrollCursorSample(int cursorX, int cursorY) {
        inertiaScroller.recordScrollCursorSample(cursorX, cursorY);
    }

    /**
     * ACTION_UP 单指分支调用,内部已封 enable + fastEnough + notPaused 三道关。
     */
    protected void tryStartSingleFingerInertia(int metaState) {
        inertiaScroller.tryStartSingleFinger(metaState);
    }

    /**
     * 双指 / 沉浸边缘 scroll 惯性触发。
     * <p>
     * RDP/VNC 等低帧率协议子类必须自己加短路,本类不持有 {@code pointer}
     * 协议字段以外的协议知识(子类内部还有 immersive 残留清零等协议细节)。
     *
     * @param metaState          ACTION_UP 时的 metaState
     * @param lastScrollSampleMs 子类 onScroll 双指分支记录的 lastScrollTimeMs
     */
    protected void tryStartScrollInertia(int metaState, long lastScrollSampleMs) {
        boolean started = inertiaScroller.tryStartScroll(metaState, lastScrollSampleMs);
        if (started) {
            // 惯性阶段手指已离开,immersive(跟随手指在边缘的位置)语义已不存在。
            // 边缘滚动松手不走 endDragModesAndScrolling,immersiveSwipeX/Y 残留为 true,
            // 会在 doScroll 门掉某方向分量,导致边缘惯性不如双指顺。
            // 这里清掉,让边缘惯性与双指走完全相同的 doScroll 路径。
            // (这是 input handler 的 immersive 状态,与 inertia 系统本身无关,故留在基类)
            immersiveSwipeX = false;
            immersiveSwipeY = false;
        }
    }

    /** 是否 RDP / VNC 类低帧率 scroll 协议;子类用于 scroll inertia bypass 短路。 */
    protected boolean isLowFrameRateScrollProtocol() {
        return inertiaScroller.isLowFrameRateScrollProtocol();
    }

    /** 是否 SSH 指针;子类构造里用于在 enable 之前显式关掉。 */
    protected boolean isSshPointer() {
        return inertiaScroller.isSshPointer();
    }

    /**
     * Function to get appropriate X coordinate from motion event for this input handler.
     * @return the appropriate X coordinate.
     */
    protected int getDragPointerX(MotionEvent e) {
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
    protected int getDragPointerY(MotionEvent e) {
        float scale = canvas.getZoomFactor();
        return (int) (canvas.getAbsY() + (e.getY() - 1.f * canvas.getTop()) / scale);
    }

    // 添加指针加速助手
    protected PointerAccelerationHelper pointerAccelerationHelper;

    protected Pair<Integer, Integer> computePointerPos(float diffX, float diffY, float base) {
        long currentTime = System.currentTimeMillis();
        float speedMultiplier = pointerAccelerationHelper.calculateAccelerationMultiplier(
                currentTime, diffX, diffY, base, 1f, 2f);

        // Make distanceX/Y display density independent and apply acceleration
        float sensitivity = pointer.getSensitivity();
        int x = Math.round(diffX * sensitivity * speedMultiplier / canvas.getDisplayDensity() + pointer.pointerX);
        int y = Math.round(diffY * sensitivity * speedMultiplier / canvas.getDisplayDensity() + pointer.pointerY);

        return new Pair<>(x, y);
    }

    /**
     * Handles actions performed by a mouse-like device.
     * @param e touch or generic motion event
     * @return true if the event was consumed (button pressed/released/moved or wheel scrolled), false otherwise
     */
    @Override
    public boolean onPointerEvent(MotionEvent e) {
        boolean used = false;
        final int action = e.getActionMasked();
        final int meta = e.getMetaState();
        final int bstate = e.getButtonState();

        float newX = e.getX();
        float newY = e.getY();

        // shortcut for releasing pointer when in external monitor mode
        if (canvas.isOutDisplay() &&
                pointer.pointerY >= canvas.getHeight()
                && action == MotionEvent.ACTION_DOWN) {
            pointer.pointerY = pointer.pointerY - 20;
            touchpad.releasePointerCapture();

            return true;
        }

        // the cursor may be hidden in touch direct mode here, since every touch there
        // will trigger pointer hide event
        canvas.showCursor();

        FpsCounter fpsCounter = canvas.getFpsCounter();
        if (fpsCounter != null) {
            fpsCounter.countInput();
        }

        Pair<Integer, Integer> pointerPos = computePointerPos(newX, newY, 0.8f);
        int x = pointerPos.first;
        int y = pointerPos.second;

        switch (action) {
            // If a mouse button was pressed or mouse was moved.
            case MotionEvent.ACTION_DOWN:
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
                }
                used = true;
                break;
            case MotionEvent.ACTION_MOVE:
                switch (bstate) {
                    case MotionEvent.BUTTON_PRIMARY:
                    case MotionEvent.BUTTON_SECONDARY:
                    case MotionEvent.BUTTON_STYLUS_PRIMARY:
                    case MotionEvent.BUTTON_TERTIARY:
                    case MotionEvent.BUTTON_STYLUS_SECONDARY:
                        pointer.moveMouseButtonDown(x, y, meta);
                        break;
                    default:
                        // move only
                        pointer.moveMouse(x, y, meta);
                        canvas.movePanToMakePointerVisible();
                        break;
                }
                used = true;
                break;
            // If a mouse button was released.
            case MotionEvent.ACTION_UP:
                pointer.releaseButton(x, y, meta);
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
                    scrollDown = true;
                } else if (vscroll > 0) {
                    scrollUp = true;
                } else if (hscroll < 0) {
                    scrollRight = true;
                } else if (hscroll > 0) {
                    scrollLeft = true;
                } else {
                    break;
                }

                sendScrollEvents(x, y, -1, meta);
                used = true;
                break;
        }

        activity.readSpecialKeysState();

        return used;
    }

    /**
     * Sends scroll events with previously set direction and speed.
     * @param x scroll event x coordinate on the remote screen
     * @param y scroll event y coordinate on the remote screen
     * @param meta meta key state at the time of the scroll
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

    /**
     * @see android.view.GestureDetector.SimpleOnGestureListener#onSingleTapConfirmed(android.view.MotionEvent)
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        if (dragMode || rightDragMode || middleDragMode) {
            return true;
        }

        if (detectImmersiveUp(e.getX(), e.getY()) || detectImmersiveDown(e.getX(), e.getY())) {
            activity.toggleKeyboard();
            return true;
        }

        String longPressAction = Utils.querySharedPreferenceString(activity.getApplicationContext(), Constants.touchpadLongPressAction, "left");
        if (detectImmersiveLeft(e.getX(), e.getY()) || detectImmersiveRight(e.getX(), e.getY())) {
            if (longPressAction.equals("gesture")) {
                activity.toggleKeyboard();
            } else {
                activity.toggleGestureLayer();
                canvas.getHandler().postDelayed(() -> activity.hideGestureLayer(), 2000);
            }

            return true;
        }

        int metaState = e.getMetaState() | canvas.getKeyboard().getMetaState();
        int x = getDragPointerX(e);
        int y = getDragPointerY(e);

        activity.readSpecialKeysState();

        pointer.leftButtonDown(x, y, metaState);
        pointer.releaseButton(x, y, metaState);

        return true;
    }

    /**
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

        totalMoveX = 0;
        totalMoveY = 0;

        String longPressType = Utils.querySharedPreferenceString(activity.getApplicationContext(), Constants.touchpadLongPressAction, "left");

        // SSH short-circuit: instead of entering dragMode (which would
        // commit to a mouse-drag gesture on long-press → release), SSH
        // mode routes the long-press into text-selection. Subclasses
        // override onSshLongPress to record the anchor and arm the
        // selection state machine. Falling back to dragMode=false keeps
        // ACTION_MOVE/UP flowing through the normal handler — no
        // special SSH branch is needed in onTouchEvent.
        if (canvas.getProtocolType() == ProtocolType.SSH) {
            onSshLongPress(e);
            return;
        }

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

            activity.sendShortVibration();

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

        activity.sendShortVibration();
    }

    /**
     * Subclass hook fired from {@link #onLongPress} when the active
     * protocol is SSH. The default no-op preserves the existing mouse-
     * drag long-press behavior on non-SSH protocols; subclasses
     * override to route the long-press into text selection.
     */
    protected void onSshLongPress(MotionEvent e) {
        // Default: do nothing — super.onLongPress already returned
        // without setting dragMode, so the upcoming ACTION_UP will
        // behave like a normal click (which is exactly what the user
        // would expect for a long-press that "didn't go anywhere").
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

    protected boolean detectImmersiveRange(float x, float y) {
        return detectImmersiveVertical(x) || detectImmersiveHorizontal(y);
    }

    protected boolean detectImmersiveVertical(float x) {
        if (!immersiveSwipeEnabled) {
            return false;
        }

        float immersiveXDistance = getImmersiveXDistance();

        return x <= immersiveXDistance || touchpad.getWidth() - x <= immersiveXDistance;
    }

    protected float getImmersiveXDistance() {
        return Math.min(Math.max(touchpad.getWidth() * immersiveSwipeRatio, 130), 190);
    }

    protected float getImmersiveYDistance() {
        return Math.min(Math.max(touchpad.getHeight() * immersiveSwipeRatio, 130), 190);
    }

    protected boolean detectImmersiveHorizontal(float y) {
        if (!immersiveSwipeEnabled) {
            return false;
        }

        float immersiveYDistance = getImmersiveYDistance();

        return y <= immersiveYDistance || touchpad.getHeight() - y <= immersiveYDistance;
    }

    protected boolean detectImmersiveLeft(float x, float y) {
        if (!immersiveSwipeEnabled) {
            return false;
        }

        return detectImmersiveLeftRaw(x, y);
    }
    protected boolean detectImmersiveLeftRaw(float x, float y) {
        float bottomXDistance = getImmersiveXDistance();

        return x <= bottomXDistance;
    }

    protected boolean detectImmersiveRight(float x, float y) {
        if (!immersiveSwipeEnabled) {
            return false;
        }

        return detectImmersiveRightRaw(x, y);
    }

    protected boolean detectImmersiveRightRaw(float x, float y) {
        float bottomXDistance = getImmersiveXDistance();

        return x >= touchpad.getWidth() - bottomXDistance;
    }

    protected boolean detectImmersiveUp(float x, float y) {
        if (!immersiveSwipeEnabled) {
            return false;
        }

        float bottomYDistance = getImmersiveYDistance();

        return y <= bottomYDistance;
    }

    protected boolean detectImmersiveDown(float x, float y) {
        if (!immersiveSwipeEnabled) {
            return false;
        }

        return detectImmersiveDownRaw(x, y);
    }

    protected boolean detectImmersiveDownRaw(float x, float y) {
        float bottomYDistance = getImmersiveYDistance();

        return y >= touchpad.getHeight() - bottomYDistance;
    }

    protected void detectImmersiveSwipe(float x, float y) {
        // if global switch off or external display mode, disable it
        if (!immersiveSwipeEnabled) {
            return;
        }

        GeneralUtils.debugLog(debugLogging, TAG, "detectImmersiveSwipe: x=" + x + ", y=" + y);

        float immersiveXDistance = getImmersiveXDistance();
        float immersiveYDistance = getImmersiveYDistance();

        if (detectImmersiveVertical(x)) {
            inSwiping = true;
            immersiveSwipeY = true;
            if (x <= immersiveXDistance) {
                if (activeEdgeSlider != edgeLeft) {
                    // Hide others and show left
                    edgeRight.setVisibility(View.INVISIBLE);
                    edgeTop.setVisibility(View.INVISIBLE);
                    edgeBottom.setVisibility(View.INVISIBLE);
                    edgeLeft.setVisibility(View.VISIBLE);
                    edgeLeft.setOrientation(DotMatrixEdgeView.EdgeOrientation.LEFT);
                }
                float pos = y / touchpad.getHeight();
                edgeLeft.setTouchPosition(pos);
                activeEdgeSlider = edgeLeft;
                inSwiping = true;
                immersiveSwipeY = true;
            } else {
                if (activeEdgeSlider != edgeRight) {
                    edgeLeft.setVisibility(View.INVISIBLE);
                    edgeTop.setVisibility(View.INVISIBLE);
                    edgeBottom.setVisibility(View.INVISIBLE);
                    edgeRight.setVisibility(View.VISIBLE);
                    edgeRight.setOrientation(DotMatrixEdgeView.EdgeOrientation.RIGHT);
                }
                float pos = y / touchpad.getHeight();
                edgeRight.setTouchPosition(pos);
                activeEdgeSlider = edgeRight;
                inSwiping = true;
                immersiveSwipeY = true;
            }

            return;
        }

        if (detectImmersiveHorizontal(y)) {
            if (y <= immersiveYDistance) {
                if (activeEdgeSlider != edgeTop) {
                    edgeLeft.setVisibility(View.INVISIBLE);
                    edgeRight.setVisibility(View.INVISIBLE);
                    edgeBottom.setVisibility(View.INVISIBLE);
                    edgeTop.setVisibility(View.VISIBLE);
                    edgeTop.setOrientation(DotMatrixEdgeView.EdgeOrientation.TOP);
                }
                float pos = x / touchpad.getWidth();
                edgeTop.setTouchPosition(pos);
                activeEdgeSlider = edgeTop;
            } else {
                if (activeEdgeSlider != edgeBottom) {
                    edgeLeft.setVisibility(View.INVISIBLE);
                    edgeRight.setVisibility(View.INVISIBLE);
                    edgeTop.setVisibility(View.INVISIBLE);
                    edgeBottom.setVisibility(View.VISIBLE);
                    edgeBottom.setOrientation(DotMatrixEdgeView.EdgeOrientation.BOTTOM);
                }
                float pos = x / touchpad.getWidth();
                edgeBottom.setTouchPosition(pos);
                activeEdgeSlider = edgeBottom;
            }
            inSwiping = true;
            immersiveSwipeX = true;

            return;
        }

        // Not in any edge zone, clear states but don't hide - let fade handle it
        inSwiping = false;
        immersiveSwipeX = false;
        immersiveSwipeY = false;
    }

    /**
     * Update the active edge slider position based on touch coordinates.
     * Call this during touch move events.
     */
    protected void updateActiveEdgeSlider(float x, float y) {
        if (activeEdgeSlider == null) return;

        float pos;
        DotMatrixEdgeView.EdgeOrientation orientation = activeEdgeSlider.getOrientation();

        if (orientation == DotMatrixEdgeView.EdgeOrientation.LEFT ||
            orientation == DotMatrixEdgeView.EdgeOrientation.RIGHT) {
            pos = y / touchpad.getHeight();
        } else {
            pos = x / touchpad.getWidth();
        }

        activeEdgeSlider.setTouchPosition(pos);
    }

    protected void hideEdgeViews() {
        GeneralUtils.debugLog(debugLogging, TAG, "hideEdgeViews: activeEdgeSlider = " + (activeEdgeSlider != null));

        // Save reference to active slider before clearing
        final DotMatrixEdgeView fadingSlider = activeEdgeSlider;
        activeEdgeSlider = null;

        // Fade out the active slider
        if (fadingSlider != null) {
            fadingSlider.fadeOut(() -> {
                GeneralUtils.debugLog(debugLogging, TAG, "fadeOut complete");
                fadingSlider.setVisibility(View.INVISIBLE);
                fadingSlider.resetFade();
            });
        }

        // Immediately hide and reset others (not the fading one)
        if (fadingSlider != edgeLeft) {
            edgeLeft.setVisibility(View.INVISIBLE);
            edgeLeft.reset();
        }
        if (fadingSlider != edgeRight) {
            edgeRight.setVisibility(View.INVISIBLE);
            edgeRight.reset();
        }
        if (fadingSlider != edgeTop) {
            edgeTop.setVisibility(View.INVISIBLE);
            edgeTop.reset();
        }
        if (fadingSlider != edgeBottom) {
            edgeBottom.setVisibility(View.INVISIBLE);
            edgeBottom.reset();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        InputDevice device = e.getDevice();
        if (device == null) return false;

        if (((device.getSources() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
                || (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            if ((e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0
                    && e.getY() < canvas.getHeight() - 20
                    && e.getAction() == MotionEvent.ACTION_DOWN) {
                touchpad.post(() -> touchpad.startPointerCapture());
            }

            canvas.showCursor();
            return true; // 消费事件，不传递给其他组件
        }

        return false;
    }

    /**
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

    /**
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

    /**
     * @see android.view.ScaleGestureDetector.OnScaleGestureListener#onScaleEnd(android.view.ScaleGestureDetector)
     */
    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        GeneralUtils.debugLog(debugLogging, TAG, "onScaleEnd");
        inScaling = false;
        inSwiping = false;
        scalingJustFinished = true;
    }

    /**
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

            GestureOverlayView gestureOverlay = activity.findViewById(R.id.gestureOverlay);
            gestureOverlay.setVisibility(View.GONE);

            touchpad.releasePointerCapture();

            return true;
        }

//        GeneralUtils.debugLog(debugLogging, TAG, "onKeyDown, e: " + e);
        return canvas.getKeyboard().keyEvent(keyCode, e);
    }

    /**
     * @see com.qihua.bVNC.input.InputHandler#onKeyUp(int, android.view.KeyEvent)
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onKeyDown, e: " + e);
        return canvas.getKeyboard().keyEvent(keyCode, e);
    }

    @Override
    public void cleanup() {
        // Reset so DirectTouch → Touchpad doesn't reuse stale
        // lastTimestamp / lastDistanceX/Y from the previous session.
        if (pointerAccelerationHelper != null) {
            pointerAccelerationHelper.reset();
        }
    }
}
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

import android.os.SystemClock;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.Utils;

import java.util.concurrent.Semaphore;

/**
 * 单指光标惯性 + 双指 scroll 惯性的后台执行器。封装所有惯性滚动状态:
 * 速度采样、信号量、松手判定、daemon worker,以及 RDP/VNC/SSH 协议识别。
 * <p>
 * 不属于本类、应留在 input handler 里的东西:
 * <ul>
 *   <li>touchpad 特有的 scroll 节流门({@code lastScrollTimeMs}、
 *       {@code lastEdgeUpdateMs})——属于 scroll 路径,与惯性系统正交</li>
 *   <li>{@code immersiveSwipeX/Y} flag 的清零——通过 {@link InertiaHook}
 *       钩子注入,避免硬编码 input handler 字段</li>
 *   <li>RDP/VNC 短路 + {@code return true} 拦截 UP 的语义——子类决定</li>
 * </ul>
 */
final class InertiaScroller {
    private static final String TAG = "InertiaScroller";

    // 光标移动惯性衰减:0.92 每 16ms tick ≈ e^(-0.083*16),30 帧 ≈ 8% 残余,
    // 沿用旧值(短促、跟手)
    private static final float DECAY_MOVE = 0.92f;
    // 滚动惯性衰减:0.96 每 16ms tick ≈ e^(-0.041*16),30 帧 ≈ 30% 残余,
    // 调慢后滚动惯性尾巴更柔顺
    private static final float DECAY_SCROLL = 0.96f;
    // 速度小于此阈值(px/tick)即停止,避免无限逼近 0
    private static final float STOP_THRESHOLD = 0.5f;
    // 单指移动动量:松手时每 tick 光标位移低于此值不触发滑行,精细微调不飘(仅快甩才滑)
    private static final float FLING_MIN_SPEED = 4f;
    // 松手距最后一次移动超过此时长视为已停顿,不触发滑行,避免"移动-停顿-松手"误滑
    private static final long FLING_TIMEOUT_MS = 60;

    private final RemoteCanvasActivity activity;
    private final RemoteCanvas canvas;
    private final RemotePointer pointer;
    /** 输入 handler 提供的 doScroll 实现(双指 scroll 惯性的真正出口)。 */
    private final DoScrollCallback doScroll;

    // ---- 后台线程 + 信号量(总是起来,空转时仅 acquire() 阻塞不耗 CPU)----
    private final Thread thread;
    private final Semaphore semaphore = new Semaphore(0);

    // ---- 共享状态:外部写入 + 后台线程读取 ----
    /** 由 enable() 决定是否真正开始释放信号 */
    private boolean enabled = false;
    /** inertia 线程当前是单指光标惯性(false)还是双指 scroll 惯性(true) */
    private boolean swiping = false;
    /** 松手时 ACTION_UP 的 metaState,惯性尾巴沿用 */
    private int metaState = 0;
    /** 松手速度(px/ms),由 recordFingerMoveSample / recordScrollCursorSample 写入 */
    private float initialSpeedX = 0;
    private float initialSpeedY = 0;
    /** dt 计算基准 —— finger/sample 的最近一次采样墙钟时刻 */
    private long startTime = 0;
    /** 16ms tick 基准 —— 不动,跟以前一致 */
    private long baseInterval = 16;
    /** 单指移动动量采样:上次更新光标的时刻,用于算松手速度 + 停顿判定 */
    private long lastMoveSampleMs = 0;

    InertiaScroller(RemoteCanvasActivity activity,
                    RemoteCanvas canvas,
                    RemotePointer pointer,
                    DoScrollCallback doScroll) {
        this.activity = activity;
        this.canvas = canvas;
        this.pointer = pointer;
        this.doScroll = doScroll;

        thread = new Thread(this::runLoop, "InertiaScroller");
        thread.setDaemon(true);
        thread.start();
    }

    /** scroll inertia 真正出口的双指 scroll 钩子,封 {@code pointer.scrollUp/Down/Left/Right}。 */
    interface DoScrollCallback {
        boolean doScroll(int x, int y, float distanceX, float distanceY, int metaState);
    }

    /**
     * 声明本会话是否启用惯性滚动。默认 false。
     * <p>
     * daemon worker 始终在跑,无信号时永远空转 acquire()。
     * SSH / RDP-low-fps / free 版等想关掉的子类,只要传 false 进来即可。
     */
    void enable(boolean enabled) {
        this.enabled = enabled;
    }

    /** 当前是否已 enable —— 用于子类在 ACTION_UP 短路决策时查询。 */
    boolean isEnabled() {
        return enabled;
    }

    /**
     * 中断正在跑的惯性尾巴(新手势开始时调用,防止上一手惯性残留)。
     * 不必关心 enabled 状态——若已禁用,中断本身也无副作用。
     */
    void stop() {
        swiping = false;
        initialSpeedX = initialSpeedY = 0;
        thread.interrupt();
    }

    /**
     * 标记手势开始:清采样时间戳,避免用上次手势的旧时刻算出巨大 dt。
     * 在 ACTION_DOWN 里调一下即可。
     */
    void resetSampling() {
        lastMoveSampleMs = 0;
        initialSpeedX = initialSpeedY = 0;
        startTime = System.currentTimeMillis();
    }

    /**
     * 单指手指 MOVE 时的动量采样(以手指位移 / 时间算松手速度)。
     * 用手指坐标而非光标坐标:手指在滚出 touchpad 边缘时不会再有 MOVE
     * 事件,所以这里采样终止,惯性尾巴自然衰减。
     */
    void recordFingerMoveSample(float fingerX, float fingerY,
                                float lastFingerX, float lastFingerY,
                                float zoomFactor) {
        long timeElapsed = System.currentTimeMillis() - startTime;
        long interval = baseInterval * 2;

        if (timeElapsed > interval) {
            if (lastFingerX != 0) {
                initialSpeedX = ((fingerX - lastFingerX) / timeElapsed) / zoomFactor / 1.6f;
                initialSpeedX = initialSpeedX
                        * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
            }
            if (lastFingerY != 0) {
                initialSpeedY = ((fingerY - lastFingerY) / timeElapsed) / zoomFactor / 1.6f;
                initialSpeedY = initialSpeedY
                        * Utils.querySharedPreferenceInt(activity, Constants.touchpadCursorSpeed, 1) / 10;
            }
            startTime = System.currentTimeMillis();
        }
    }

    /**
     * onScroll 单指分支的动量采样(以光标位移 / 时间算松手速度)。
     * 量纲与后台 worker else 分支的 pointer.getX() + speed 对齐,
     * 松手滑行速度不会突变。
     */
    void recordScrollCursorSample(int cursorX, int cursorY) {
        long now = System.currentTimeMillis();
        long dt = now - lastMoveSampleMs;
        if (lastMoveSampleMs != 0 && dt > 0) {
            initialSpeedX = (cursorX - pointer.getX()) / (float) dt;
            initialSpeedY = (cursorY - pointer.getY()) / (float) dt;
        } else {
            initialSpeedX = initialSpeedY = 0;
        }
        lastMoveSampleMs = now;
    }

    /**
     * ACTION_UP 单指分支调用,内部已封 enable + fastEnough + notPaused 三道关。
     *
     * @return true 表示已释放信号,后续可以 return true 拦截这次 UP;
     *         false 表示没满足条件(子类一般不需返回值)
     */
    boolean tryStartSingleFinger(int metaState) {
        if (!enabled) {
            return false;
        }
        if (!isFlingFastEnough() || !isSingleFingerNotPaused()) {
            return false;
        }
        this.metaState = metaState;
        semaphore.release();
        return true;
    }

    /**
     * 双指 / 沉浸边缘 scroll 惯性触发。
     * <p>
     * RDP/VNC 等低帧率协议子类必须自己加短路,本类不持有 {@code pointer}
     * 协议字段以外的协议知识(子类内部还有 immersive 残留清零等协议细节)。
     *
     * @param lastScrollSampleMs 子类 onScroll 双指分支记录的 lastScrollTimeMs
     * @return true 表示已释放信号(子类应 return true 拦截这次 UP)
     */
    boolean tryStartScroll(int metaState, long lastScrollSampleMs) {
        if (!enabled) {
            return false;
        }
        if (!isFlingFastEnough() || !isScrollNotPaused(lastScrollSampleMs)) {
            return false;
        }
        swiping = true;
        this.metaState = metaState;
        semaphore.release();
        return true;
    }

    /**
     * 是否"当前指针类型属于低帧率 scroll 协议"——RDP / VNC 类
     * scroll inertia 在低帧率协议上被显式跳过的判定。
     * 公开给子类复用,避免每个子类各自 instanceof 一份。
     */
    boolean isLowFrameRateScrollProtocol() {
        return pointer instanceof RemoteRdpPointer || pointer instanceof RemoteVncPointer;
    }

    /**
     * SSH 指针类型识别——子类构造里用于在 enable 之前显式关掉
     * (SSH 文本滚动由 libvterm 渲染,惯性导致越界 scroll 会出
     * alt-buffer / scrollback 状态错乱;文本选择长按会被惯性尾巴带偏)。
     */
    boolean isSshPointer() {
        return pointer instanceof RemoteSshPointer;
    }

    // ------------------------------------------------------------------
    // 后台 worker
    // ------------------------------------------------------------------

    private void runLoop() {
        while (true) {
            try {
                semaphore.acquire();
            } catch (Exception ignored) {
                // stop immediately
                continue;
            }

            if (initialSpeedX == 0 && initialSpeedY == 0) {
                continue;
            }

            float speedX = initialSpeedX * baseInterval;
            float speedY = initialSpeedY * baseInterval;

            if (swiping) {
                while ((Math.abs(speedX) > STOP_THRESHOLD || Math.abs(speedY) > STOP_THRESHOLD)
                        && !thread.isInterrupted()) {
                    doScroll.doScroll(pointer.getX(), pointer.getY(), -speedX, -speedY, metaState);

                    speedX *= DECAY_SCROLL;
                    speedY *= DECAY_SCROLL;

                    SystemClock.sleep(baseInterval);
                }
            } else {
                while ((Math.abs(speedX) > STOP_THRESHOLD || Math.abs(speedY) > STOP_THRESHOLD)
                        && !thread.isInterrupted()) {
                    int nextX = Math.round(pointer.getX() + speedX);
                    int nextY = Math.round(pointer.getY() + speedY);
                    pointer.moveMouse(nextX, nextY, metaState);

                    // 每一 tick 都调一次:惯性尾巴速度已衰得很小,但光标可能正好停在视图
                    // 可见边界外侧仍向同方向推,这时不调就会一直留在屏外。
                    // movePanToMakePointerVisible 内部自己判是否需要 pan(没越界时直接
                    // return false),没有越界时不会触发 resetScroll/layout,代价可控。
                    canvas.movePanToMakePointerVisible();

                    speedX *= DECAY_MOVE;
                    speedY *= DECAY_MOVE;

                    SystemClock.sleep(baseInterval);
                }
            }

            swiping = false;
        }
    }

    // ------------------------------------------------------------------
    // 松手判定的私有 helpers
    // ------------------------------------------------------------------

    private boolean isFlingFastEnough() {
        float flingSpeedX = initialSpeedX * baseInterval;
        float flingSpeedY = initialSpeedY * baseInterval;
        return Math.abs(flingSpeedX) > FLING_MIN_SPEED
                || Math.abs(flingSpeedY) > FLING_MIN_SPEED;
    }

    private boolean isSingleFingerNotPaused() {
        return lastMoveSampleMs != 0
                && System.currentTimeMillis() - lastMoveSampleMs <= FLING_TIMEOUT_MS;
    }

    private boolean isScrollNotPaused(long lastScrollSampleMs) {
        return System.currentTimeMillis() - lastScrollSampleMs <= FLING_TIMEOUT_MS;
    }
}
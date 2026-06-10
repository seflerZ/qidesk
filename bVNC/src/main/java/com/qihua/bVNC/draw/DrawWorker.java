package com.qihua.bVNC.draw;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.Log;
import android.view.SurfaceHolder;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.Utils;
import com.undatech.opaque.DrawTask;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pulls DrawTasks off a queue and paints them onto the canvas's
 * SurfaceHolder on a dedicated thread.
 *
 * Throttling rules (preserved from the old inner-class):
 *   - addTask coalesces if the previous task is < 10 ms old
 *   - run skips drawing if the previous draw was < 13 ms ago
 *   - frames older than 16 ms count as drops (when isCount() is true)
 *
 * Lifecycle: constructor starts the thread; call stop() to terminate
 * cleanly. The old inner-class never had a stop(), so the thread leaked
 * for the lifetime of the process.
 */
public class DrawWorker implements Runnable {
    private static final String TAG = "DrawWorker";

    /** Sentinel queued by stop() to wake the blocking take() and exit. */
    private static final DrawTask POISON = new DrawTask(0, 0, 0, 0);

    private final RemoteCanvas canvas;
    private final Thread thread;
    private final LinkedBlockingQueue<DrawTask> queue = new LinkedBlockingQueue<>();
    private final boolean showFps;

    private volatile boolean running = true;
    private volatile long lastDraw;

    public DrawWorker(RemoteCanvas canvas) {
        this.canvas = canvas;
        this.showFps = Utils.querySharedPreferenceBoolean(canvas.getContext(),
                Constants.enableDebugInfo, false);

        this.thread = new Thread(this, "DrawWorker");
        this.thread.start();
    }

    public void count() {
        if (canvas.fpsCounter != null) {
            canvas.fpsCounter.count();
        }
    }

    public void addTask(DrawTask task) {
        DrawTask lastTask = queue.peek();
        if (lastTask != null
                && System.currentTimeMillis() - lastTask.getInTimeMs() < 10) {
            return;
        }
        queue.add(task);
    }

    /** Stop the worker thread. Idempotent. */
    public void stop() {
        running = false;
        queue.offer(POISON);
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long getLastDraw() {
        return lastDraw;
    }

    public boolean isShowFps() {
        return canvas.fpsCounter != null;
    }

    @Override
    public void run() {
        // use the highest priority to draw the frame to avoid micro stutter
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        while (running) {
            Canvas glCanvas = null;
            SurfaceHolder holder = canvas.surfaceHolder;
            try {
                DrawTask task = queue.take();
                if (task == POISON || !running) {
                    break;
                }

                if (isShowFps() && task.isCount()) {
                    canvas.fpsCounter.count();
                }

                // prevent the pointer refresh event goes too fast
                if (task.getInTimeMs() - lastDraw < 13) {
                    continue;
                }

                // when isCount is true, the update comes from a real image update
                if (System.currentTimeMillis() - task.getInTimeMs() > 16 && task.isCount()) {
                    // drop frame, lagging
                    canvas.fpsCounter.finish(task.getInTimeMs());
                    canvas.fpsCounter.frameDrop();
                }

                if (holder == null || canvas.scaler == null || canvas.bitmapData == null) {
                    continue;
                }

                glCanvas = holder.lockHardwareCanvas();
                if (glCanvas == null) {
                    continue;
                }
                glCanvas.setMatrix(canvas.scaler.getMatrix());
                glCanvas.translate(-canvas.absoluteXPosition, -canvas.absoluteYPosition);
                glCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                canvas.bitmapData.drawable.draw(glCanvas);

                if (canvas.fpsCounter != null) {
                    canvas.fpsCounter.finish(task.getInTimeMs());
                    if (showFps) {
                        canvas.fpsCounter.drawFps(glCanvas);
                        canvas.fpsCounter.drawDebugMsg(glCanvas, task.getDebugMsg());
                    }
                }

                lastDraw = System.currentTimeMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "draw failed", e);
            } finally {
                if (glCanvas != null) {
                    try {
                        holder.unlockCanvasAndPost(glCanvas);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}

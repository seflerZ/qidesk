package com.qihua.bVNC.input;

import android.view.MotionEvent;
import com.undatech.opaque.util.GeneralUtils;
import java.util.LinkedList;

/**
 * 触摸移动分析器，用于检测慢速精细操作
 * 通过滑动窗口机制监测2秒内的移动距离，实现智能临时放大功能
 */
public class TouchMovementAnalyzer {
    private static final String TAG = "TouchMovementAnalyzer";
    private static final long WINDOW_DURATION_MS = 2000; // 3 秒滑动窗口
    private static final float MIN_DISTANCE_DP = 30.0f; // 最小移动距离阈值(dp)
    private static final int EXIT_MULTIPLIER = 8;
    private static final float DP_TO_PX_RATIO = 2.0f; // 粗略的dp到px转换比例
    
    // 触摸轨迹点记录
    private LinkedList<TouchPoint> touchPoints = new LinkedList<>();
    private float density;
    private boolean debugLogging;
    
    // 分析结果
    private boolean isSlowMovementDetected = false;
    private long lastAnalysisTime = 0;
    private static final long ANALYSIS_INTERVAL_MS = 200; // 200ms分析间隔
    
    public TouchMovementAnalyzer(float displayDensity, boolean debugLogging) {
        this.density = displayDensity;
        this.debugLogging = debugLogging;
    }
    
    /**
     * 触摸轨迹点数据结构
     */
    private static class TouchPoint {
        float x, y;
        long timestamp;
        
        TouchPoint(float x, float y, long timestamp) {
            this.x = x;
            this.y = y;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 添加新的触摸点到分析队列
     */
    public void addTouchPoint(float x, float y) {
        long currentTime = System.currentTimeMillis();
        touchPoints.add(new TouchPoint(x, y, currentTime));
        
        // 清理过期的点（超出2秒窗口的点）
        cleanupExpiredPoints(currentTime);
    }
    
    /**
     * 清理超出时间窗口的旧点
     */
    private void cleanupExpiredPoints(long currentTime) {
        while (!touchPoints.isEmpty() &&
               (currentTime - touchPoints.getFirst().timestamp) > WINDOW_DURATION_MS) {
            touchPoints.removeFirst();
        }
    }
    
    /**
     * 分析当前触摸移动模式
     * @return true表示检测到慢速移动，应该启用临时放大
     */
    public boolean analyzeMovement() {
        long currentTime = System.currentTimeMillis();
        
        // 避免过于频繁的分析
        if (currentTime - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            return isSlowMovementDetected;
        }
        
        lastAnalysisTime = currentTime;
        
        // 至少需要2个点才能分析
        if (touchPoints.size() < 2) {
            return isSlowMovementDetected;
        }

        // 检查是否有足够的历史数据（至少1秒）
        TouchPoint oldestPoint = touchPoints.getFirst();
        TouchPoint newestPoint = touchPoints.getLast();

        if (newestPoint.timestamp - oldestPoint.timestamp < 1000) {
            // 数据不足，保持当前状态
            return isSlowMovementDetected;
        }
        
        // 计算平均移动速度（距离/时间）
        float totalDistance = calculateTotalDistance();
        long durationMs = newestPoint.timestamp - oldestPoint.timestamp;
        float speedInDpPerSecond = (durationMs > 0) ? 
            (totalDistance / density * DP_TO_PX_RATIO) / (durationMs / 1000.0f) : 0;

        isSlowMovementDetected = speedInDpPerSecond < (isSlowMovementDetected
                ? MIN_DISTANCE_DP * EXIT_MULTIPLIER : MIN_DISTANCE_DP);

        return isSlowMovementDetected;
    }
    
    /**
     * 计算轨迹总移动距离（欧几里得距离累加）
     */
    private float calculateTotalDistance() {
        float totalDistance = 0;
        float lastDistance = 0;
        float currentDistance = 0;
        for (int i = 1; i < touchPoints.size(); i++) {
            TouchPoint prev = touchPoints.get(i - 1);
            TouchPoint curr = touchPoints.get(i);
            float dx = curr.x - prev.x;
            float dy = curr.y - prev.y;
            currentDistance = (float)Math.sqrt(dx * dx + dy * dy);
            if (currentDistance > MIN_DISTANCE_DP * 10) {
                // 跳过长时间的移动点
                continue;
            }

            lastDistance = currentDistance;
            totalDistance += lastDistance;
        }
        return totalDistance;
    }
    
    /**
     * 重置分析器状态
     */
//    public void reset() {
//        touchPoints.clear();
//        isSlowMovementDetected = false;
//        lastAnalysisTime = 0;
//        GeneralUtils.debugLog(debugLogging, TAG, "Analyzer reset");
//    }
}
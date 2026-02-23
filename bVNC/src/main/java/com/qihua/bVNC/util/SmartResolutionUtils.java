package com.qihua.bVNC.util;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.qihua.bVNC.Constants;

/**
 * 智能分辨率工具类
 * 根据设备屏幕密度和尺寸计算合适的远程桌面分辨率
 */
public class SmartResolutionUtils {
    private static final String TAG = "SmartResolutionUtils";
    
    /**
     * 计算智能分辨率
     * 基于PPI分档位，原始大小乘以对应系数
     * 
     * @param context 应用上下文
     * @return 包含宽度和高度的整数数组 [width, height]
     */
    public static int[] calculateSmartResolution(Context context) {
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            
            int baseWidth = metrics.widthPixels;
            int baseHeight = metrics.heightPixels;
            float density = metrics.density;
            int orientation = context.getResources().getConfiguration().orientation;
            
            // 计算PPI
            float xDpi = metrics.xdpi;
            float yDpi = metrics.ydpi;
            float ppi = (xDpi + yDpi) / 2.0f;
            
            Log.d(TAG, "Screen PPI: " + ppi + ", Density: " + density);
            
            // 基于PPI分档位确定缩放系数
            float scaleCoefficient = getPpiScaleCoefficient(ppi);

            // 应用缩放系数
            int smartWidth = Math.round(baseWidth / scaleCoefficient);
            int smartHeight = Math.round(baseHeight / scaleCoefficient);

            return new int[]{smartWidth, smartHeight};
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating smart resolution: " + e.getMessage(), e);
            return new int[]{1920, 1080}; // 出错时返回默认值
        }
    }
    
    /**
     * 根据PPI值确定缩放系数
     * PPI越高，系数越大，远程桌面分辨率越高
     */
    private static float getPpiScaleCoefficient(float ppi) {
        // PPI分档位及对应系数
        if (ppi >= 400) {
            return 1.5f;  // 超高PPI (xxxhdpi+) - 系数1.8
        } else if (ppi >= 320) {
            return 1.3f;  // 高PPI (xxhdpi) - 系数1.5
        } else if (ppi >= 240) {
            return 1.1f;  // 中高PPI (xhdpi) - 系数1.3
        } else if (ppi >= 160) {
            return 1.0f;  // 标准PPI (hdpi) - 系数1.1
        } else {
            return 1.0f;  // 低PPI (mdpi/lldpi) - 系数1.0
        }
    }
    

    
    /**
     * 获取智能分辨率描述文本
     */
    public static String getSmartResolutionDescription(Context context) {
        int[] resolution = calculateSmartResolution(context);
        return resolution[0] + " × " + resolution[1];
    }
    
    /**
     * 检查是否应该启用智能分辨率
     */
    public static boolean shouldUseSmartResolution(int resType) {
        return resType == Constants.RDP_GEOM_SELECT_SMART;
    }
    
    /**
     * 监听屏幕方向变化的回调接口
     */
    public interface OnOrientationChangeListener {
        void onOrientationChanged(int newWidth, int newHeight);
    }
}
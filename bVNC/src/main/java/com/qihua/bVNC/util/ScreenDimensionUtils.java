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

package com.qihua.bVNC.util;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.undatech.opaque.util.GeneralUtils;

/**
 * Utility class for handling screen dimension-related operations.
 * Provides methods to get screen size identifiers for different device configurations.
 */
public class ScreenDimensionUtils {
    private static final String TAG = "ScreenDimensionUtils";
    
    /**
     * Screen size categories based on screen dimensions
     */
    public enum ScreenSizeCategory {
        PHONE_SMALL,        // 小屏手机 (< 4.5英寸)
        PHONE_NORMAL,       // 普通手机 (4.5-6英寸)
        PHONE_LARGE,        // 大屏手机 (> 6英寸)
        TABLET_SMALL,       // 小平板 (7-9英寸)
        TABLET_LARGE,       // 大平板 (> 9英寸)
        FOLDABLE_CLOSED,    // 折叠屏关闭状态
        FOLDABLE_OPEN       // 折叠屏展开状态
    }
    
    /**
     * Get a unique identifier for the current screen dimensions and orientation
     * This identifier will be used to save/load different layouts for different screen configurations
     * 
     * @param context Application context
     * @return String identifier in format: "width_height_orientation_category"
     */
    public static String getScreenDimensionIdentifier(Context context) {
        if (context == null) {
            return "default";
        }
        
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return "default";
            }
            
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            float density = metrics.density;
            int orientation = context.getResources().getConfiguration().orientation;
            
            // Convert pixels to dp for consistent comparison
            int screenWidthDp = (int) (screenWidth / density);
            int screenHeightDp = (int) (screenHeight / density);
            
            // Determine screen size category
            ScreenSizeCategory category = categorizeScreenSize(screenWidthDp, screenHeightDp, orientation);
            
            // Create identifier: width_height_orientation_category
            String identifier = String.format("%d_%d_%s_%s", 
                screenWidth, 
                screenHeight, 
                orientation == Configuration.ORIENTATION_LANDSCAPE ? "LANDSCAPE" : "PORTRAIT",
                category.toString()
            );
            
            GeneralUtils.debugLog(true, TAG, "Screen dimension identifier: " + identifier + 
                " (width: " + screenWidth + "px, height: " + screenHeight + "px, density: " + density + ")");
            
            return identifier;
            
        } catch (Exception e) {
            GeneralUtils.debugLog(true, TAG, "Error getting screen dimension identifier: " + e.getMessage());
            return "default";
        }
    }
    
    /**
     * Categorize screen size based on dimensions
     */
    private static ScreenSizeCategory categorizeScreenSize(int widthDp, int heightDp, int orientation) {
        // Use the larger dimension as reference for categorization
        int maxDimensionDp = Math.max(widthDp, heightDp);
        int minDimensionDp = Math.min(widthDp, heightDp);
        
        // Detect foldable devices by checking for extreme aspect ratios
        float aspectRatio = (float) maxDimensionDp / minDimensionDp;
        
        // Foldable devices typically have very high aspect ratios when closed
        if (aspectRatio > 2.0f && minDimensionDp < 500) {
            return ScreenSizeCategory.FOLDABLE_CLOSED;
        }
        
        // When open, foldable devices have more normal aspect ratios but large screens
        if (maxDimensionDp > 1200 && aspectRatio < 2.0f) {
            return ScreenSizeCategory.FOLDABLE_OPEN;
        }
        
        // Traditional device categorization
        if (maxDimensionDp < 400) {
            return ScreenSizeCategory.PHONE_SMALL;
        } else if (maxDimensionDp < 600) {
            return ScreenSizeCategory.PHONE_NORMAL;
        } else if (maxDimensionDp < 800) {
            return ScreenSizeCategory.PHONE_LARGE;
        } else if (maxDimensionDp < 1000) {
            return ScreenSizeCategory.TABLET_SMALL;
        } else {
            return ScreenSizeCategory.TABLET_LARGE;
        }
    }
    
    /**
     * Get a simplified screen identifier for storage purposes
     * This creates a more general identifier that groups similar screen sizes together
     */
    public static String getSimplifiedScreenIdentifier(Context context) {
        if (context == null) {
            return "default";
        }
        
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return "default";
            }
            
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            float density = metrics.density;
            int orientation = context.getResources().getConfiguration().orientation;
            
            // Convert to dp and round to nearest 100 for grouping
            int screenWidthGroup = Math.round((screenWidth / density) / 100) * 100;
            int screenHeightGroup = Math.round((screenHeight / density) / 100) * 100;
            
            String identifier = String.format("%d_%d_%s", 
                screenWidthGroup, 
                screenHeightGroup,
                orientation == Configuration.ORIENTATION_LANDSCAPE ? "LS" : "PT"
            );
            
            GeneralUtils.debugLog(true, TAG, "Simplified screen identifier: " + identifier);
            return identifier;
            
        } catch (Exception e) {
            GeneralUtils.debugLog(true, TAG, "Error getting simplified screen identifier: " + e.getMessage());
            return "default";
        }
    }
    
    /**
     * Get display metrics for the current screen
     */
    public static DisplayMetrics getDisplayMetrics(Context context) {
        if (context == null) {
            return new DisplayMetrics();
        }
        
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                return metrics;
            }
        } catch (Exception e) {
            GeneralUtils.debugLog(true, TAG, "Error getting display metrics: " + e.getMessage());
        }
        
        return context.getResources().getDisplayMetrics();
    }
}
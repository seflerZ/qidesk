/*
 * Copyright (C) 2023 Your Company
 * 
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package com.qihua.bVNC.input;

/**
 * 统一的指针加速工具类，用于计算基于速度的指针移动加速
 */
public class PointerAccelerationHelper {
    
    private static final float DEFAULT_SPEED_ACCELERATION_FACTOR = 0.5f;
    private static final float DEFAULT_MAX_ACCELERATION = 2.5f;
    
    // 存储上一次的运动数据用于速度计算
    private long lastTimestamp;
    private float lastDistanceX;
    private float lastDistanceY;
    
    private float speedAccelerationFactor;
    private float maxAcceleration;
    
    /**
     * 构造函数
     */
    public PointerAccelerationHelper() {
        this(DEFAULT_SPEED_ACCELERATION_FACTOR, DEFAULT_MAX_ACCELERATION);
    }
    
    /**
     * 构造函数，允许自定义加速参数
     * 
     * @param speedAccelerationFactor 速度加速因子
     * @param maxAcceleration 最大加速倍数
     */
    public PointerAccelerationHelper(float speedAccelerationFactor, float maxAcceleration) {
        this.speedAccelerationFactor = speedAccelerationFactor;
        this.maxAcceleration = maxAcceleration;
        reset();
    }
    
    /**
     * 重置内部状态
     */
    public void reset() {
        this.lastTimestamp = 0;
        this.lastDistanceX = 0;
        this.lastDistanceY = 0;
    }
    
    /**
     * 计算指针移动的加速倍数
     * 
     * @param currentTime 当前时间戳
     * @param currentDistanceX 当前X方向的距离
     * @param currentDistanceY 当前Y方向的距离
     * @param baseMultiplier 基础乘数（例如：单指滑动0.8f，双指滑动1.6f等）
     * @return 计算得出的加速倍数
     */
    public float calculateAccelerationMultiplier(long currentTime, 
                                               float currentDistanceX, 
                                               float currentDistanceY, 
                                               float baseMultiplier) {
        float speedMultiplier = baseMultiplier;
        
        if (lastTimestamp > 0) {
            long timeDiff = currentTime - lastTimestamp;
            if (timeDiff > 0) {
                // 计算X和Y方向的速度
                float speedX = Math.abs(currentDistanceX - lastDistanceX) / timeDiff;
                float speedY = Math.abs(currentDistanceY - lastDistanceY) / timeDiff;
                float speed = Math.max(speedX, speedY);
                
                // 根据速度应用加速
                speedMultiplier = baseMultiplier + (speed * speedAccelerationFactor);
                speedMultiplier = Math.min(speedMultiplier, maxAcceleration);
            }
        }
        
        // 更新最后的值
        lastDistanceX = currentDistanceX;
        lastDistanceY = currentDistanceY;
        lastTimestamp = currentTime;
        
        return speedMultiplier;
    }
    
    /**
     * 计算指针移动的加速倍数（适用于没有基础乘数的情况，如onPointerEvent）
     * 
     * @param currentTime 当前时间戳
     * @param currentDistanceX 当前X方向的距离
     * @param currentDistanceY 当前Y方向的距离
     * @return 计算得出的加速倍数
     */
    public float calculateAccelerationMultiplier(long currentTime, 
                                               float currentDistanceX, 
                                               float currentDistanceY) {
        return calculateAccelerationMultiplier(currentTime, currentDistanceX, currentDistanceY, 1.0f);
    }
    
    /**
     * 获取当前加速因子
     */
    public float getSpeedAccelerationFactor() {
        return speedAccelerationFactor;
    }
    
    /**
     * 设置加速因子
     */
    public void setSpeedAccelerationFactor(float speedAccelerationFactor) {
        this.speedAccelerationFactor = speedAccelerationFactor;
    }
    
    /**
     * 获取最大加速倍数
     */
    public float getMaxAcceleration() {
        return maxAcceleration;
    }
    
    /**
     * 设置最大加速倍数
     */
    public void setMaxAcceleration(float maxAcceleration) {
        this.maxAcceleration = maxAcceleration;
    }
    
    /**
     * 获取最后记录的时间戳
     */
    public long getLastTimestamp() {
        return lastTimestamp;
    }
}
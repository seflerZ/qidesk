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

import android.view.MotionEvent;

import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.undatech.opaque.util.GeneralUtils;
import com.qihua.bVNC.R;

import java.util.HashMap;
import java.util.Map;

public class InputHandlerDirectTouch extends InputHandlerGeneric {
    public static final String ID = "DIRECT_TOUCH_MODE";
    static final String TAG = "InputHandlerDirectTouch";

    // 用于跟踪多点触摸的接触ID映射
    private final Map<Integer, Integer> contactIdMap = new HashMap<>();
    private int nextContactId = 0;

    public InputHandlerDirectTouch(RemoteCanvasActivity activity, RemoteCanvas canvas,
                                   RemotePointer pointer, boolean debugLogging) {
        super(activity, canvas, canvas, pointer, debugLogging);
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#getDescription()
     */
    @Override
    public String getDescription() {
        return canvas.getResources().getString(R.string.input_method_direct_touch_description);
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#getId()
     */
    @Override
    public String getId() {
        return ID;
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#onTouchEvent(android.view.MotionEvent)
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent, e: " + e);

        final int action = e.getActionMasked();
        final int meta = e.getMetaState();

        FpsCounter fpsCounter = canvas.getFpsCounter();
        if (fpsCounter != null) {
            fpsCounter.countInput();
        }

        // 检查指针是否支持触摸事件（RDP或NVStream）
        if (pointer instanceof RemoteRdpPointer || pointer instanceof RemoteNvStreamPointer) {
            // 处理多点触摸事件
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    // 第一个触摸点（总是index 0）
                    handleTouchDown(e, 0, pointer);
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    // 额外的触摸点，使用getActionIndex()获取实际的指针索引
                    handleTouchDown(e, e.getActionIndex(), pointer);
                    break;

                case MotionEvent.ACTION_MOVE:
                    // 处理所有移动的触摸点
                    handleTouchMove(e, pointer);
                    break;

                case MotionEvent.ACTION_UP:
                    // 最后一个指针抬起，使用getActionIndex()获取被抬起的指针索引
                    handleTouchUp(e, e.getActionIndex(), pointer);
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    // 某个特定指针抬起，使用getActionIndex()获取实际的指针索引
                    handleTouchUp(e, e.getActionIndex(), pointer);
                    break;

                case MotionEvent.ACTION_CANCEL:
                    // 触摸被取消
                    handleTouchCancel(e, pointer);
                    break;
            }
        } else {
            // 如果指针类型不支持多点触摸，则回退到标准鼠标处理
            return super.onTouchEvent(e);
        }

        return true;
    }

    private void handleTouchDown(MotionEvent e, int pointerIndex, RemotePointer pointer) {
        int pointerId = e.getPointerId(pointerIndex);
        int x = (int) (canvas.getAbsX() + e.getX(pointerIndex) / canvas.getZoomFactor());
        int y = (int) (canvas.getAbsY() + (e.getY(pointerIndex) - 1.f * canvas.getTop()) / canvas.getZoomFactor());

        // 分配或获取接触ID
        int contactId = allocateContactId(pointerId);
        
        GeneralUtils.debugLog(debugLogging, TAG, "Touch Down - pointerIndex: " + pointerIndex + ", pointerId: " + pointerId + ", contactId: " + contactId + ", contactIdMap size after allocation: " + contactIdMap.size());
        
        // 发送触摸按下事件 - 使用反射来调用适当的触摸方法
        pointer.touchDown(x, y, contactId);
    }

    private void handleTouchMove(MotionEvent e, RemotePointer pointer) {
        int pointerCount = e.getPointerCount();

        GeneralUtils.debugLog(debugLogging, TAG, "Touch Move - pointerCount: " + pointerCount + ", contactIdMap size: " + contactIdMap.size());
        
        for (int i = 0; i < pointerCount; i++) {
            int pointerId = e.getPointerId(i);
            
            // 只处理已知的触摸点
            if (contactIdMap.containsKey(pointerId)) {
                int contactId = contactIdMap.get(pointerId);
                int x = (int) (canvas.getAbsX() + e.getX(i) / canvas.getZoomFactor());
                int y = (int) (canvas.getAbsY() + (e.getY(i) - 1.f * canvas.getTop()) / canvas.getZoomFactor());

                GeneralUtils.debugLog(debugLogging, TAG, "Touch Move: x=" + x + ", y=" + y + ", contactId=" + contactId);
                
                // 发送触摸更新事件
                pointer.touchUpdate(x, y, contactId);
            } else {
                GeneralUtils.debugLog(debugLogging, TAG, "Touch Move: Skipping pointerId " + pointerId + ", not in contactIdMap");
            }
        }
    }

    private void handleTouchUp(MotionEvent e, int pointerIndex, RemotePointer pointer) {
        // 获取实际的指针ID
        int pointerId = e.getPointerId(pointerIndex);
        
        GeneralUtils.debugLog(debugLogging, TAG, "Touch Up - pointerIndex: " + pointerIndex + ", pointerId: " + pointerId + ", contactIdMap size: " + contactIdMap.size());
        
        if (contactIdMap.containsKey(pointerId)) {
            int contactId = contactIdMap.get(pointerId);
            int x = (int) (canvas.getAbsX() + e.getX(pointerIndex) / canvas.getZoomFactor());
            int y = (int) (canvas.getAbsY() + (e.getY(pointerIndex) - 1.f * canvas.getTop()) / canvas.getZoomFactor());

            GeneralUtils.debugLog(debugLogging, TAG, "Touch Up: x=" + x + ", y=" + y + ", contactId=" + contactId);
            
            // 发送触摸抬起事件
            pointer.touchUp(x, y, contactId);
            
            // 释放接触ID
            releaseContactId(pointerId);
        } else {
            GeneralUtils.debugLog(debugLogging, TAG, "Touch Up: pointerId " + pointerId + " not found in contactIdMap. Available IDs: " + contactIdMap.keySet());
        }
    }

    private void handleTouchCancel(MotionEvent e, RemotePointer pointer) {
        // 取消所有活动的触摸点
        for (Map.Entry<Integer, Integer> entry : contactIdMap.entrySet()) {
            int pointerId = entry.getKey();
            int contactId = entry.getValue();
            
            // 从MotionEvent中获取最后已知的坐标
            int pointerIndex = findPointerIndexById(e, pointerId);
            int x, y;
            if (pointerIndex >= 0) {
                x = (int) (canvas.getAbsX() + e.getX(pointerIndex) / canvas.getZoomFactor());
                y = (int) (canvas.getAbsY() + (e.getY(pointerIndex) - 1.f * canvas.getTop()) / canvas.getZoomFactor());
            } else {
                // 如果无法找到特定指针的坐标，使用第一个指针的坐标作为后备
                x = (int) (canvas.getAbsX() + e.getX(0) / canvas.getZoomFactor());
                y = (int) (canvas.getAbsY() + (e.getY(0) - 1.f * canvas.getTop()) / canvas.getZoomFactor());
            }

            GeneralUtils.debugLog(debugLogging, TAG, "Touch Cancel: x=" + x + ", y=" + y + ", contactId=" + contactId);
            
            // 发送触摸取消事件
            pointer.touchCancel(x, y, contactId);
        }
        
        // 清空接触ID映射
        contactIdMap.clear();
    }

    // 根据pointerId查找在MotionEvent中的索引
    private int findPointerIndexById(MotionEvent e, int pointerId) {
        int pointerCount = e.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            if (e.getPointerId(i) == pointerId) {
                return i;
            }
        }
        return -1; // 未找到
    }

    private int allocateContactId(int pointerId) {
        if (!contactIdMap.containsKey(pointerId)) {
            contactIdMap.put(pointerId, nextContactId++);
        }
        return contactIdMap.get(pointerId);
    }

    private void releaseContactId(int pointerId) {
        contactIdMap.remove(pointerId);
    }
}
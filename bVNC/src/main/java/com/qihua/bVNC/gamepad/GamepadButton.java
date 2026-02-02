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

package com.qihua.bVNC.gamepad;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Custom button view for gamepad controls.
 * Handles button press visual feedback and click events.
 */
public class GamepadButton extends View {
    private static final String TAG = "GamepadButton";

    private String label;
    private int keyCode;
    private boolean isPressed = false;
    private boolean editMode = false;

    private OnButtonClickListener listener;

    // Button appearance
    private Paint backgroundPaint;
    private Paint textPaint;
    private Paint borderPaint;
    private float cornerRadius;

    // Colors
    private static final int COLOR_NORMAL = Color.parseColor("#60000000");
    private static final int COLOR_PRESSED = Color.parseColor("#A0FFFFFF");
    private static final int COLOR_EDIT = Color.parseColor("#A0FFA500");
    private static final int COLOR_BORDER = Color.parseColor("#FFFFFFFF");

    public interface OnButtonClickListener {
        void onButtonDown(int keyCode);
        void onButtonUp(int keyCode);
    }

    public GamepadButton(Context context, String label, int keyCode) {
        super(context);
        this.label = label;
        this.keyCode = keyCode;
        init();
    }

    private void init() {
        cornerRadius = 8 * getResources().getDisplayMetrics().density;

        // Background paint
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(COLOR_NORMAL);

        // Text paint
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(COLOR_BORDER);
        textPaint.setTextSize(24 * getResources().getDisplayMetrics().density);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Border paint
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(COLOR_BORDER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);

        setClickable(true);
    }

    public void setOnButtonClickListener(OnButtonClickListener listener) {
        this.listener = listener;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        invalidate();
    }

    public boolean isPressed() {
        return isPressed;
    }

    public String getLabel() {
        return label;
    }

    public int getKeyCode() {
        return keyCode;
    }

    /**
     * Set the button size
     */
    public void setButtonSize(int size) {
        // Update button size
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
        params.width = size;
        params.height = size;
        setLayoutParams(params);

        // Update text size proportional to button size
        float density = getResources().getDisplayMetrics().density;
        float baseSize = 60 * density;
        float sizeRatio = (float) size / baseSize;
        textPaint.setTextSize(24 * density * sizeRatio);

        // Update corner radius proportional to button size
        cornerRadius = 8 * density * sizeRatio;

        invalidate();
    }

    /**
     * Simulate a button press (for touch events from overlay)
     */
    public void simulatePress() {
        if (!isPressed) {
            isPressed = true;
            backgroundPaint.setColor(COLOR_PRESSED);
            invalidate();
            if (listener != null) {
                listener.onButtonDown(keyCode);
            }
        }
    }

    /**
     * Simulate a button release (for touch events from overlay)
     */
    public void simulateRelease() {
        if (isPressed) {
            isPressed = false;
            backgroundPaint.setColor(editMode ? COLOR_EDIT : COLOR_NORMAL);
            invalidate();
            if (listener != null) {
                listener.onButtonUp(keyCode);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode) {
            // 在编辑模式下，让父视图处理所有触摸事件
            return false;
        }

        // 非编辑模式下的正常触摸处理
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 在按下时，改变视觉状态并立即触发按钮按下事件
                isPressed = true;
                backgroundPaint.setColor(COLOR_PRESSED);
                invalidate();
                
                // 立即触发按钮按下事件
                if (listener != null) {
                    listener.onButtonDown(keyCode);
                }
                
                // 检查是否是select键长按进入编辑模式
                if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_SELECT) {
                    // 对于select键，启动长按检测以进入编辑模式
                    postDelayed(longPressRunnable, 600); // 600ms 作为长按时间
                }
                
                return true;

            case MotionEvent.ACTION_UP:
                // 移除长按回调（如果有的话）
                removeCallbacks(longPressRunnable);
                
                // 触发按钮释放事件
                if (listener != null) {
                    listener.onButtonUp(keyCode);
                }
                
                // 重置按钮状态
                isPressed = false;
                backgroundPaint.setColor(editMode ? COLOR_EDIT : COLOR_NORMAL);
                invalidate();
                
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                isPressed = false;
                backgroundPaint.setColor(editMode ? COLOR_EDIT : COLOR_NORMAL);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                // 检查手指是否仍在按钮范围内
                float x = event.getX();
                float y = event.getY();
                boolean withinBounds = x >= 0 && x <= getWidth() && y >= 0 && y <= getHeight();
                
                if (isPressed && !withinBounds) {
                    // 手指移出按钮范围，取消按钮按下状态和发送释放事件
                    removeCallbacks(longPressRunnable);
                    
                    if (listener != null) {
                        listener.onButtonUp(keyCode);
                    }
                    
                    isPressed = false;
                    backgroundPaint.setColor(editMode ? COLOR_EDIT : COLOR_NORMAL);
                    invalidate();
                } else if (!isPressed && withinBounds) {
                    // 手指移回按钮范围
                    isPressed = true;
                    backgroundPaint.setColor(COLOR_PRESSED);
                    invalidate();
                    
                    if (listener != null) {
                        listener.onButtonDown(keyCode);
                    }
                    
                    // 重新启动长按检测（如果是select键）
                    if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_SELECT) {
                        postDelayed(longPressRunnable, 600);
                    }
                }
                return true;
        }

        return super.onTouchEvent(event);
    }
    
    private Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            // 长按select键，进入编辑模式
            if (getParent() instanceof GamepadOverlay) {
                ((GamepadOverlay) getParent()).enterEditModeForButton(GamepadButton.this);
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // Update color based on state
        if (editMode) {
            backgroundPaint.setColor(COLOR_EDIT);
        } else if (isPressed) {
            backgroundPaint.setColor(COLOR_PRESSED);
        } else {
            backgroundPaint.setColor(COLOR_NORMAL);
        }

        // Draw background
        RectF rect = new RectF(2, 2, width - 2, height - 2);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint);

        // Draw border
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint);

        // Draw label text
        float textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2;
        canvas.drawText(label, width / 2f, textY, textPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Make button square based on the smaller dimension
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec),
                           MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(size, size);
    }
}
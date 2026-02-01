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
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.qihua.bVNC.input.InputHandlerGamepad;
import com.qihua.bVNC.R;
import com.undatech.opaque.util.GeneralUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Overlay view that displays gamepad buttons and handles button interactions.
 * Includes floating analog sticks that appear on touch.
 */
public class GamepadOverlay extends FrameLayout {
    private static final String TAG = "GamepadOverlay";
    private static final String PREF_PREFIX = "gamepad_";
    private String connectionId = "";

    private InputHandlerGamepad inputHandler;

    // Analog stick views
    private View leftStickBase;
    private View leftStickKnob;
    private View rightStickBase;
    private View rightStickKnob;

    // Button views
    private GamepadButton buttonA;
    private GamepadButton buttonB;
    private GamepadButton buttonX;
    private GamepadButton buttonY;
    private GamepadButton buttonStart;
    private GamepadButton buttonSelect;
    private GamepadButton dpadUp;
    private GamepadButton dpadDown;
    private GamepadButton dpadLeft;
    private GamepadButton dpadRight;
    private GamepadButton buttonL1;
    private GamepadButton buttonL2;
    private GamepadButton buttonR1;
    private GamepadButton buttonR2;

    // SharedPreferences for saving button positions
    private SharedPreferences prefs;

    // Button map for quick lookup
    private Map<String, GamepadButton> buttonMap;

    // Edit mode state
    private boolean editMode = false;
    private GamepadButton draggingButton = null;
    private GamepadButton resizingButton = null;
    private float dragStartX, dragStartY;
    private float resizeStartSize;
    private float resizeStartDistance;

    // For multi-touch resize
    private int resizePointer1Id = -1;
    private int resizePointer2Id = -1;

    public GamepadOverlay(Context context) {
        super(context);
        init(context);
    }

    public GamepadOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public GamepadOverlay(Context context, InputHandlerGamepad inputHandler) {
        super(context);
        this.inputHandler = inputHandler;
        init(context);
    }

    public void setInputHandler(InputHandlerGamepad inputHandler) {
        this.inputHandler = inputHandler;
    }
    
    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
        // 重新初始化prefs以使用特定连接的存储
        if (getContext() != null) {
            // 使用连接ID作为部分文件名创建独立的偏好设置文件
            prefs = getContext().getSharedPreferences("gamepad_pos_" + connectionId, Context.MODE_PRIVATE);
            // 重新加载按钮位置
            loadButtonPositions();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void init(Context context) {
        // 初始化时如果没有连接ID，则使用默认设置
        prefs = context.getSharedPreferences("gamepad_pos_default", Context.MODE_PRIVATE);
        buttonMap = new HashMap<>();

        // Create layout
        setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        ));

        // Create analog stick visual elements
        createAnalogSticks();

        // Create gamepad buttons
        createGamepadButtons();

        // Set touch listener for edit mode
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (editMode) {
                    return handleEditModeTouch(event);
                }
                return false;
            }
        });
    }

    private void createAnalogSticks() {
        float stickSize = 120 * getResources().getDisplayMetrics().density;
        float knobSize = 50 * getResources().getDisplayMetrics().density;

        // Left stick
        leftStickBase = createCircleView(Color.parseColor("#40FFFFFF"), stickSize);
        leftStickKnob = createCircleView(Color.parseColor("#80FFFFFF"), knobSize);
        addView(leftStickBase);
        addView(leftStickKnob);

        leftStickBase.setVisibility(GONE);
        leftStickKnob.setVisibility(GONE);

        // Right stick
        rightStickBase = createCircleView(Color.parseColor("#40FFFFFF"), stickSize);
        rightStickKnob = createCircleView(Color.parseColor("#80FFFFFF"), knobSize);
        addView(rightStickBase);
        addView(rightStickKnob);

        rightStickBase.setVisibility(GONE);
        rightStickKnob.setVisibility(GONE);
    }

    private View createCircleView(int color, float size) {
        View view = new View(getContext()) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(color);
                paint.setAntiAlias(true);
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - 2, paint);
            }
        };
        LayoutParams params = new LayoutParams((int) size, (int) size);
        view.setLayoutParams(params);
        return view;
    }

    private void createGamepadButtons() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float density = metrics.density;

        // Default button positions (percentages of screen width/height)
        // A, B, X, Y buttons on the right side (XBOX-style layout)
        buttonA = createButton("A", KeyEvent.KEYCODE_BUTTON_A, 0.85f, 0.70f, density);  // Confirm/OK
        buttonB = createButton("B", KeyEvent.KEYCODE_BUTTON_B, 0.90f, 0.80f, density); // Back/Cancel
        buttonX = createButton("X", KeyEvent.KEYCODE_BUTTON_X, 0.80f, 0.80f, density);      // Extra function
        buttonY = createButton("Y", KeyEvent.KEYCODE_BUTTON_Y, 0.85f, 0.90f, density);      // Yet another function

        // Start and Select in the center
        buttonStart = createButton("START", KeyEvent.KEYCODE_BUTTON_START, 0.70f, 0.85f, density);
        buttonSelect = createButton("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, 0.60f, 0.85f, density);

        // D-pad on the left side
        dpadUp = createButton("▲", KeyEvent.KEYCODE_DPAD_UP, 0.15f, 0.70f, density);
        dpadDown = createButton("▼", KeyEvent.KEYCODE_DPAD_DOWN, 0.15f, 0.90f, density);
        dpadLeft = createButton("◀", KeyEvent.KEYCODE_DPAD_LEFT, 0.10f, 0.80f, density);
        dpadRight = createButton("▶", KeyEvent.KEYCODE_DPAD_RIGHT, 0.20f, 0.80f, density);

        // Shoulder buttons
        buttonL1 = createButton("L1", KeyEvent.KEYCODE_BUTTON_L1, 0.15f, 0.05f, density);
        buttonL2 = createButton("L2", KeyEvent.KEYCODE_BUTTON_L2, 0.15f, 0.12f, density);
        buttonR1 = createButton("R1", KeyEvent.KEYCODE_BUTTON_R1, 0.85f, 0.05f, density);
        buttonR2 = createButton("R2", KeyEvent.KEYCODE_BUTTON_R2, 0.85f, 0.12f, density);

        // Add all buttons to map
        buttonMap.put("A", buttonA);
        buttonMap.put("B", buttonB);
        buttonMap.put("X", buttonX);
        buttonMap.put("Y", buttonY);
        buttonMap.put("START", buttonStart);
        buttonMap.put("SELECT", buttonSelect);
        buttonMap.put("DPAD_UP", dpadUp);
        buttonMap.put("DPAD_DOWN", dpadDown);
        buttonMap.put("DPAD_LEFT", dpadLeft);
        buttonMap.put("DPAD_RIGHT", dpadRight);
        buttonMap.put("L1", buttonL1);
        buttonMap.put("L2", buttonL2);
        buttonMap.put("R1", buttonR1);
        buttonMap.put("R2", buttonR2);

        // Load saved positions
        loadButtonPositions();
    }

    private GamepadButton createButton(String label, int keyCode, float xPercent, float yPercent, float density) {
        GamepadButton button = new GamepadButton(getContext(), label, keyCode);
        int size = (int) (60 * density);
        LayoutParams params = new LayoutParams(size, size);

        // Initially set position based on screen size, but will be adjusted after layout
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        params.leftMargin = (int) (xPercent * screenWidth - size / 2);
        params.topMargin = (int) (yPercent * screenHeight - size / 2);

        button.setLayoutParams(params);
        addView(button);

        // Set button click listener
        button.setOnButtonClickListener(new GamepadButton.OnButtonClickListener() {
            @Override
            public void onButtonDown(int keyCode) {
                if (inputHandler != null && !editMode) {
                    inputHandler.sendGamepadButton(keyCode);
                }
            }

            @Override
            public void onButtonUp(int keyCode) {
                // Optional: handle button release
            }
        });

        return button;
    }

    /**
     * Enter edit mode for a specific button - called from GamepadButton
     */
    public void enterEditModeForButton(GamepadButton button) {
        editMode = true;
        
        // 启用所有按钮的编辑模式视觉反馈
        for (GamepadButton btn : buttonMap.values()) {
            btn.setEditMode(true);
        }
        
        // 开始拖拽当前按钮
        draggingButton = button;
        resizingButton = button;
    }
    
    /**
     * Exit edit mode and disable visual feedback for all buttons
     */
    private void exitEditMode() {
        editMode = false;
        for (GamepadButton button : buttonMap.values()) {
            button.setEditMode(false);
        }
        // Reset edit mode state
        draggingButton = null;
        resizingButton = null;
        resizePointer1Id = -1;
        resizePointer2Id = -1;
    }
    
    /**
     * Exit edit mode when button is long pressed in edit mode
     */
    public void exitEditModeFromButton() {
        // 不再支持通过按钮长按退出编辑模式
        // 只支持点击空白区域退出
    }

    public void showLeftStick(float x, float y, boolean show) {
        int stickSize = leftStickBase.getWidth();
        if (stickSize == 0) {
            stickSize = (int) (120 * getResources().getDisplayMetrics().density);
        }
        int knobSize = leftStickKnob.getWidth();
        if (knobSize == 0) {
            knobSize = (int) (50 * getResources().getDisplayMetrics().density);
        }

        if (show) {
            leftStickBase.setVisibility(VISIBLE);
            leftStickKnob.setVisibility(VISIBLE);

            LayoutParams baseParams = (LayoutParams) leftStickBase.getLayoutParams();
            baseParams.leftMargin = (int) (x - stickSize / 2);
            baseParams.topMargin = (int) (y - stickSize / 2);
            leftStickBase.setLayoutParams(baseParams);

            LayoutParams knobParams = (LayoutParams) leftStickKnob.getLayoutParams();
            knobParams.leftMargin = (int) (x - knobSize / 2);
            knobParams.topMargin = (int) (y - knobSize / 2);
            leftStickKnob.setLayoutParams(knobParams);
        } else {
            leftStickBase.setVisibility(GONE);
            leftStickKnob.setVisibility(GONE);
        }
    }

    public void showRightStick(float x, float y, boolean show) {
        int stickSize = rightStickBase.getWidth();
        if (stickSize == 0) {
            stickSize = (int) (120 * getResources().getDisplayMetrics().density);
        }
        int knobSize = rightStickKnob.getWidth();
        if (knobSize == 0) {
            knobSize = (int) (50 * getResources().getDisplayMetrics().density);
        }

        if (show) {
            rightStickBase.setVisibility(VISIBLE);
            rightStickKnob.setVisibility(VISIBLE);

            LayoutParams baseParams = (LayoutParams) rightStickBase.getLayoutParams();
            baseParams.leftMargin = (int) (x - stickSize / 2);
            baseParams.topMargin = (int) (y - stickSize / 2);
            rightStickBase.setLayoutParams(baseParams);

            LayoutParams knobParams = (LayoutParams) rightStickKnob.getLayoutParams();
            knobParams.leftMargin = (int) (x - knobSize / 2);
            knobParams.topMargin = (int) (y - knobSize / 2);
            rightStickKnob.setLayoutParams(knobParams);
        } else {
            rightStickBase.setVisibility(GONE);
            rightStickKnob.setVisibility(GONE);
        }
    }

    public void updateLeftStickPosition(float x, float y) {
        int knobSize = leftStickKnob.getWidth();
        if (knobSize == 0) {
            knobSize = (int) (50 * getResources().getDisplayMetrics().density);
        }

        LayoutParams params = (LayoutParams) leftStickKnob.getLayoutParams();
        params.leftMargin = (int) (x - knobSize / 2);
        params.topMargin = (int) (y - knobSize / 2);
        leftStickKnob.setLayoutParams(params);
    }

    public void updateRightStickPosition(float x, float y) {
        int knobSize = rightStickKnob.getWidth();
        if (knobSize == 0) {
            knobSize = (int) (50 * getResources().getDisplayMetrics().density);
        }

        LayoutParams params = (LayoutParams) rightStickKnob.getLayoutParams();
        params.leftMargin = (int) (x - knobSize / 2);
        params.topMargin = (int) (y - knobSize / 2);
        rightStickKnob.setLayoutParams(params);
    }

    /**
     * Check if touch is on a gamepad button
     */
    public boolean onButtonTouch(float x, float y, boolean isDown) {
        for (GamepadButton button : buttonMap.values()) {
            if (isPointInView(button, x, y)) {
                if (isDown) {
                    button.simulatePress();
                } else {
                    button.simulateRelease();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Handle button movement for drag effects
     */
    public boolean onButtonTouchMove(float x, float y) {
        for (GamepadButton button : buttonMap.values()) {
            if (button.isPressed() && !isPointInView(button, x, y)) {
                button.simulateRelease();
            }
        }
        return false;
    }

    /**
     * Release all buttons
     */
    public void onButtonTouchRelease() {
        for (GamepadButton button : buttonMap.values()) {
            button.simulateRelease();
        }
    }

    /**
     * Check if touch is on a gamepad button without triggering button press
     * Used by InputHandlerGamepad to determine if event should be handled by button or by gamepad
     */
    public boolean onButtonTouchRaw(float x, float y) {
        for (GamepadButton button : buttonMap.values()) {
            if (isPointInView(button, x, y)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if overlay is in edit mode
     */
    public boolean isEditMode() {
        return editMode;
    }

    private boolean isPointInView(View view, float x, float y) {
        // 获取按钮在父容器（GamepadOverlay）中的布局参数
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        float viewX = params.leftMargin;
        float viewY = params.topMargin;
        return x >= viewX && x <= viewX + view.getWidth() &&
               y >= viewY && y <= viewY + view.getHeight();
    }

    /**
     * Toggle edit mode for repositioning buttons
     */
    public void toggleEditMode() {
        editMode = !editMode;
        for (GamepadButton button : buttonMap.values()) {
            button.setEditMode(editMode);
        }
    }

    /**
     * Save button positions and sizes to SharedPreferences
     */
    public void saveButtonPositions() {
        if (prefs == null) return;
        
        SharedPreferences.Editor editor = prefs.edit();

        // Use actual view dimensions instead of screen dimensions
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        
        // If view hasn't been laid out yet, use screen dimensions as fallback
        if (viewWidth <= 0 || viewHeight <= 0) {
            viewWidth = getResources().getDisplayMetrics().widthPixels;
            viewHeight = getResources().getDisplayMetrics().heightPixels;
        }

        for (Map.Entry<String, GamepadButton> entry : buttonMap.entrySet()) {
            String name = entry.getKey();
            GamepadButton button = entry.getValue();
            LayoutParams params = (LayoutParams) button.getLayoutParams();

            // Save position as percentages for different screen sizes
            float xPercent = (float) (params.leftMargin + button.getWidth() / 2) / viewWidth;
            float yPercent = (float) (params.topMargin + button.getHeight() / 2) / viewHeight;

            // Save size as percentage of screen width
            float sizePercent = (float) button.getWidth() / viewWidth;

            editor.putFloat(PREF_PREFIX + name + "_x", xPercent);
            editor.putFloat(PREF_PREFIX + name + "_y", yPercent);
            editor.putFloat(PREF_PREFIX + name + "_size", sizePercent);
        }

        editor.apply();
        GeneralUtils.debugLog(true, TAG, "Button positions and sizes saved for connection: " + connectionId);
    }

    /**
     * Load button positions and sizes from SharedPreferences
     */
    private void loadButtonPositions() {
        if (prefs == null) return;
        
        // Use actual view dimensions instead of screen dimensions
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        
        // If view hasn't been laid out yet, use screen dimensions as fallback
        if (viewWidth <= 0 || viewHeight <= 0) {
            viewWidth = getResources().getDisplayMetrics().widthPixels;
            viewHeight = getResources().getDisplayMetrics().heightPixels;
        }

        for (Map.Entry<String, GamepadButton> entry : buttonMap.entrySet()) {
            String name = entry.getKey();
            GamepadButton button = entry.getValue();

            float xPercent = prefs.getFloat(PREF_PREFIX + name + "_x", -1);
            float yPercent = prefs.getFloat(PREF_PREFIX + name + "_y", -1);
            float sizePercent = prefs.getFloat(PREF_PREFIX + name + "_size", -1);

            // Load size if available
            if (sizePercent > 0) {
                int newSize = (int) (sizePercent * viewWidth);
                button.setButtonSize(newSize);
            }

            if (xPercent >= 0 && yPercent >= 0) {
                LayoutParams params = (LayoutParams) button.getLayoutParams();
                params.leftMargin = (int) (xPercent * viewWidth - button.getWidth() / 2);
                params.topMargin = (int) (yPercent * viewHeight - button.getHeight() / 2);
                button.setLayoutParams(params);
                GeneralUtils.debugLog(true, TAG, "Loaded " + name + " position for connection " + connectionId + ": " + xPercent + ", " + yPercent);
            }
        }
    }

    /**
     * Handle touch events in edit mode
     * Supports dragging (single touch) and resizing (two-finger pinch)
     */
    private boolean handleEditModeTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        final int index = event.getActionIndex();
        final int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // First pointer down - check if touching a button
                resizePointer1Id = pointerId;
                
                // 检查是否触摸到按钮，优先处理新触摸的按钮
                for (GamepadButton button : buttonMap.values()) {
                    if (isPointInView(button, x, y)) {
                        draggingButton = button;
                        resizingButton = button;
                        dragStartX = x - button.getLeft();
                        dragStartY = y - button.getTop();
                        resizeStartSize = button.getWidth();
                        return true;
                    }
                }
                
                // 如果点击空白区域，退出编辑模式
                exitEditMode();
                // 返回true表示事件已被处理，防止事件继续传播到InputHandlerGamepad
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Second pointer down - switch to resize mode
                if (event.getPointerCount() == 2) {
                    if (draggingButton != null) {
                        // Calculate initial distance between pointers
                        float x0 = event.getX(0);
                        float y0 = event.getY(0);
                        float x1 = event.getX(1);
                        float y1 = event.getY(1);
                        resizeStartDistance = (float) Math.sqrt(
                                Math.pow(x1 - x0, 2) + Math.pow(y1 - y0, 2));
                        resizePointer2Id = pointerId;
                        return true;
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                // Handle dragging (single pointer) or resizing (two pointers)
                if (event.getPointerCount() == 1 && draggingButton != null) {
                    // Drag mode - move button
                    LayoutParams params = (LayoutParams) draggingButton.getLayoutParams();
                    params.leftMargin = (int) (x - dragStartX);
                    params.topMargin = (int) (y - dragStartY);
                    draggingButton.setLayoutParams(params);
                    return true;
                } else if (event.getPointerCount() == 2 && resizingButton != null) {
                    // Resize mode - pinch to resize
                    float x0 = event.getX(0);
                    float y0 = event.getY(0);
                    float x1 = event.getX(1);
                    float y1 = event.getY(1);
                    float currentDistance = (float) Math.sqrt(
                            Math.pow(x1 - x0, 2) + Math.pow(y1 - y0, 2));

                    if (resizeStartDistance > 0) {
                        float scale = currentDistance / resizeStartDistance;
                        int newSize = (int) (resizeStartSize * scale);

                        // Limit size to reasonable bounds
                        int minSize = (int) (30 * getResources().getDisplayMetrics().density);
                        int maxSize = (int) (150 * getResources().getDisplayMetrics().density);
                        newSize = Math.max(minSize, Math.min(maxSize, newSize));

                        resizingButton.setButtonSize(newSize);
                    }
                    return true;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                // Check if any pointers are still touching
                if (event.getPointerCount() == 1) {
                    // One pointer left - go back to drag mode or save
                    if (draggingButton != null) {
                        saveButtonPositions();
                        resizePointer1Id = (pointerId == resizePointer1Id) ? resizePointer2Id : resizePointer1Id;
                        resizePointer2Id = -1;
                        return true;
                    }
                } else if (event.getPointerCount() == 0) {
                    // All pointers up - save and reset
                    if (draggingButton != null || resizingButton != null) {
                        saveButtonPositions();
                        draggingButton = null;
                        resizingButton = null;
                        resizePointer1Id = -1;
                        resizePointer2Id = -1;
                        return true;
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                // Cancel - reset state
                if (draggingButton != null || resizingButton != null) {
                    draggingButton = null;
                    resizingButton = null;
                    resizePointer1Id = -1;
                    resizePointer2Id = -1;
                    
                    // 退出编辑模式
                    editMode = false;
                    for (GamepadButton button : buttonMap.values()) {
                        button.setEditMode(false);
                    }
                    return true;
                }
                break;
        }

        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Reload button positions when view is attached to window
        post(new Runnable() {
            @Override
            public void run() {
                loadButtonPositions();
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Reload button positions when screen size changes
        if (w != oldw || h != oldh) {
            // Delay the reload to ensure the view is fully laid out
            post(new Runnable() {
                @Override
                public void run() {
                    loadButtonPositions();
                }
            });
        }
    }
}
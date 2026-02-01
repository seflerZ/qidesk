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

import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.gamepad.GamepadOverlay;
import com.undatech.opaque.util.GeneralUtils;
import com.qihua.bVNC.R;

public class InputHandlerGamepad extends InputHandlerGeneric {
    public static final String ID = "GAMEPAD_MODE";
    static final String TAG = "InputHandlerGamepad";

    // Analog stick states
    private float leftStickX = 0;
    private float leftStickY = 0;
    private float rightStickX = 0;
    private float rightStickY = 0;
    private int leftStickPointerId = -1;
    private int rightStickPointerId = -1;
    private float leftStickCenterX = 0;
    private float leftStickCenterY = 0;
    private float rightStickCenterX = 0;
    private float rightStickCenterY = 0;

    // Screen dimensions for dividing left/right zones
    private int screenWidth = 0;
    private int screenHeight = 0;
    private int screenMiddleX = 0;

    // Gamepad overlay reference
    private GamepadOverlay gamepadOverlay;

    // Handler for repeating key events (for d-pad and analog sticks)
    private Handler repeatHandler;
    private Runnable repeatRunnable;

    // Last repeat time
    private long lastRepeatTime = 0;
    private static final long REPEAT_INTERVAL_MS = 50;

    public InputHandlerGamepad(RemoteCanvasActivity activity, RemoteCanvas canvas,
                              RemotePointer pointer, boolean debugLogging) {
        super(activity, canvas, canvas, pointer, debugLogging);

        this.displayDensity = activity.getResources().getDisplayMetrics().density;
        repeatHandler = new Handler();

        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (lastRepeatTime > 0 && System.currentTimeMillis() - lastRepeatTime >= REPEAT_INTERVAL_MS) {
                    sendAnalogStickEvents();
                    repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS);
                }
            }
        };

        // Initialize gamepad overlay
        gamepadOverlay = new GamepadOverlay(activity, this);
        
        // Set the connection ID for button position storage
        if (canvas != null && canvas.connection != null) {
            gamepadOverlay.setConnectionId(canvas.connection.getId());
        }
        
        activity.runOnUiThread(() -> {
            View touchpadView = activity.findViewById(R.id.touchpad);
            if (touchpadView != null && touchpadView.getParent() != null) {
                ((android.widget.FrameLayout) touchpadView.getParent())
                        .addView(gamepadOverlay);
            } else {
                // Fallback: add to the root view if touchpad parent is not available
                android.view.ViewGroup rootView = (ViewGroup) activity.findViewById(android.R.id.content);
                rootView.addView(gamepadOverlay);
            }
        });
    }

    public void setScreenDimensions(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.screenMiddleX = width / 2;
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandler#getDescription()
     */
    @Override
    public String getDescription() {
        return canvas.getResources().getString(R.string.input_method_gamepad);
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
     * @see com.qihua.bVNC.input.InputHandlerGeneric#getX(android.view.MotionEvent)
     */
    @Override
    protected int getX(MotionEvent e) {
        // For gamepad, we do not use mouse coordinates
        return pointer.getX();
    }

    /*
     * (non-Javadoc)
     * @see com.qihua.bVNC.input.InputHandlerGeneric#getY(android.view.MotionEvent)
     */
    @Override
    protected int getY(MotionEvent e) {
        // For gamepad, we don't use mouse coordinates
        return pointer.getY();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        GeneralUtils.debugLog(debugLogging, TAG, "onTouchEvent, e: " + e);

        final int action = e.getActionMasked();
        final int index = e.getActionIndex();
        final int pointerID = e.getPointerId(index);

        FpsCounter fpsCounter = canvas.getFpsCounter();
        if (fpsCounter != null) {
            fpsCounter.countInput();
        }

        // Update screen dimensions if needed
        if (screenWidth == 0 || screenHeight == 0) {
            setScreenDimensions((int) e.getX(0), (int) e.getY(0));
        }

        // Determine which side of the screen the touch is on
        float touchX = e.getX(index);
        float touchY = e.getY(index);
        boolean isLeftSide = touchX < screenMiddleX;

        // 实时检查是否处于编辑模式（在处理事件期间编辑模式可能被退出）
        boolean isOnButton = gamepadOverlay != null && gamepadOverlay.onButtonTouchRaw(touchX, touchY);
        boolean isEditing = gamepadOverlay != null && gamepadOverlay.isEditMode();

        // 如果处于编辑模式，完全跳过游戏手柄的处理逻辑
        if (isEditing) {
            return false; // 不处理任何事件，让 GamepadOverlay 处理
        }

        // 在非编辑模式下，如果触摸在按钮上，不处理这些事件，让按钮自己处理
        if (isOnButton) {
            return false; // 让按钮自己处理触摸事件，包括长按检测
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                handlePointerDown(pointerID, touchX, touchY, isLeftSide);
                break;

            case MotionEvent.ACTION_MOVE:
                handlePointerMove(e, pointerID, isLeftSide);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                handlePointerUp(pointerID);
                break;

            case MotionEvent.ACTION_CANCEL:
                handlePointerCancel();
                break;
        }

        // 在非编辑模式下处理模拟摇杆事件
        return true;
    }

    private void handlePointerDown(int pointerId, float x, float y, boolean isLeftSide) {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer down: " + pointerId + ", leftSide: " + isLeftSide);

        // Check if this pointer is already tracking an analog stick
        if (leftStickPointerId != -1 && rightStickPointerId != -1) {
            // Both sticks are already being used, ignore this pointer
            return;
        }

        // Check if touch is on a gamepad button
        if (gamepadOverlay != null && gamepadOverlay.onButtonTouch(x, y, true)) {
            return;
        }

        // Otherwise, track as analog stick input
        if (isLeftSide && leftStickPointerId == -1) {
            leftStickPointerId = pointerId;
            leftStickCenterX = x;
            leftStickCenterY = y;
            leftStickX = 0;
            leftStickY = 0;
            showLeftStickVisual(x, y, true);
        } else if (!isLeftSide && rightStickPointerId == -1) {
            rightStickPointerId = pointerId;
            rightStickCenterX = x;
            rightStickCenterY = y;
            rightStickX = 0;
            rightStickY = 0;
            showRightStickVisual(x, y, true);
        }
    }

    private void handlePointerMove(MotionEvent e, int pointerId, boolean isLeftSide) {
        int index = e.findPointerIndex(pointerId);
        if (index < 0) {
            return;
        }

        float x = e.getX(index);
        float y = e.getY(index);

        // Check if this is a button touch
        if (gamepadOverlay != null && gamepadOverlay.onButtonTouchMove(x, y)) {
            return;
        }

        // Handle analog stick movement
        if (pointerId == leftStickPointerId) {
            updateAnalogStick(x, y, true);
        } else if (pointerId == rightStickPointerId) {
            updateAnalogStick(x, y, false);
        }
    }

    private void handlePointerUp(int pointerId) {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer up: " + pointerId);

        // Check button release
        if (gamepadOverlay != null) {
            gamepadOverlay.onButtonTouchRelease();
        }

        // Handle analog stick release
        if (pointerId == leftStickPointerId) {
            leftStickPointerId = -1;
            leftStickX = 0;
            leftStickY = 0;
            stopRepeatEvents();
            showLeftStickVisual(0, 0, false);
        } else if (pointerId == rightStickPointerId) {
            rightStickPointerId = -1;
            rightStickX = 0;
            rightStickY = 0;
            stopRepeatEvents();
            showRightStickVisual(0, 0, false);
        }
    }

    private void handlePointerCancel() {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer cancel");

        leftStickPointerId = -1;
        rightStickPointerId = -1;
        leftStickX = 0;
        leftStickY = 0;
        rightStickX = 0;
        rightStickY = 0;
        stopRepeatEvents();

        showLeftStickVisual(0, 0, false);
        showRightStickVisual(0, 0, false);

        if (gamepadOverlay != null) {
            gamepadOverlay.onButtonTouchRelease();
        }
    }

    private void updateAnalogStick(float x, float y, boolean isLeft) {
        float centerX = isLeft ? leftStickCenterX : rightStickCenterX;
        float centerY = isLeft ? leftStickCenterY : rightStickCenterY;
        float maxRadius = 100 * displayDensity; // Maximum stick movement radius

        float dx = x - centerX;
        float dy = y - centerY;

        // Normalize to -1 to 1 range
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > maxRadius) {
            float ratio = maxRadius / distance;
            dx *= ratio;
            dy *= ratio;
        }

        float normalizedX = dx / maxRadius;
        float normalizedY = dy / maxRadius;

        // Apply deadzone
        float deadzone = 0.15f;
        if (Math.abs(normalizedX) < deadzone && Math.abs(normalizedY) < deadzone) {
            normalizedX = 0;
            normalizedY = 0;
        }

        if (isLeft) {
            leftStickX = normalizedX;
            leftStickY = normalizedY;
        } else {
            rightStickX = normalizedX;
            rightStickY = normalizedY;
        }

        // Start repeat for continuous input
        lastRepeatTime = System.currentTimeMillis();
        if (!repeatHandler.hasCallbacks(repeatRunnable)) {
            repeatHandler.post(repeatRunnable);
        }

        // Update visual
        if (isLeft) {
            updateLeftStickVisual(centerX + dx, centerY + dy);
        } else {
            updateRightStickVisual(centerX + dx, centerY + dy);
        }
    }

    private void sendAnalogStickEvents() {
        if (leftStickPointerId == -1 && rightStickPointerId == -1) {
            stopRepeatEvents();
            return;
        }

        // Left stick typically maps to WASD or arrow keys
        if (Math.abs(leftStickX) > 0.15 || Math.abs(leftStickY) > 0.15) {
            sendAnalogStickKeys(leftStickX, leftStickY, true);
        }

        // Right stick could map to camera movement or other controls
        // For now, we can also map it to arrow keys or numpad
        if (Math.abs(rightStickX) > 0.15 || Math.abs(rightStickY) > 0.15) {
            sendAnalogStickKeys(rightStickX, rightStickY, false);
        }
    }

    private void sendAnalogStickKeys(float x, float y, boolean isLeft) {
        RemoteKeyboard keyboard = canvas.getKeyboard();

        // Map analog stick to keyboard keys
        // Left stick: WASD, Right stick: Arrow keys or IJKL
        int[] keys;
        if (isLeft) {
            // WASD for left stick
            keys = new int[]{
                    y < -0.3 ? KeyEvent.KEYCODE_W : -1,      // Up
                    y > 0.3 ? KeyEvent.KEYCODE_S : -1,       // Down
                    x < -0.3 ? KeyEvent.KEYCODE_A : -1,      // Left
                    x > 0.3 ? KeyEvent.KEYCODE_D : -1        // Right
            };
        } else {
            // Arrow keys for right stick
            keys = new int[]{
                    y < -0.3 ? KeyEvent.KEYCODE_DPAD_UP : -1,
                    y > 0.3 ? KeyEvent.KEYCODE_DPAD_DOWN : -1,
                    x < -0.3 ? KeyEvent.KEYCODE_DPAD_LEFT : -1,
                    x > 0.3 ? KeyEvent.KEYCODE_DPAD_RIGHT : -1
            };
        }

        // Send key down events (simulating continuous press)
        int metaState = keyboard.getMetaState();
        for (int keyCode : keys) {
            if (keyCode != -1) {
                keyboard.keyEvent(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                keyboard.keyEvent(keyCode, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            }
        }
    }

    private void stopRepeatEvents() {
        lastRepeatTime = 0;
        repeatHandler.removeCallbacks(repeatRunnable);
    }

    private void showLeftStickVisual(float x, float y, boolean show) {
        if (gamepadOverlay != null) {
            activity.runOnUiThread(() -> gamepadOverlay.showLeftStick(x, y, show));
        }
    }

    private void showRightStickVisual(float x, float y, boolean show) {
        if (gamepadOverlay != null) {
            activity.runOnUiThread(() -> gamepadOverlay.showRightStick(x, y, show));
        }
    }

    private void updateLeftStickVisual(float x, float y) {
        if (gamepadOverlay != null) {
            activity.runOnUiThread(() -> gamepadOverlay.updateLeftStickPosition(x, y));
        }
    }

    private void updateRightStickVisual(float x, float y) {
        if (gamepadOverlay != null) {
            activity.runOnUiThread(() -> gamepadOverlay.updateRightStickPosition(x, y));
        }
    }

    /**
     * Send a gamepad button press as keyboard event
     */
    public void sendGamepadButton(int keyCode) {
        GeneralUtils.debugLog(debugLogging, TAG, "Sending gamepad button: " + keyCode);
        RemoteKeyboard keyboard = canvas.getKeyboard();
        int metaState = keyboard.getMetaState();

        keyboard.keyEvent(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        SystemClock.sleep(20);
        keyboard.keyEvent(keyCode, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    /**
     * Start continuous key press (for d-pad or shoulder buttons)
     */
    public void startKeyPress(int keyCode) {
        if (repeatRunnable == null) {
            return;
        }
        // Could implement continuous press for d-pad
        sendGamepadButton(keyCode);
    }
    
    @Override
    public void cleanup() {
        // 移除游戏手柄覆盖层
        // Remove the gamepad overlay
        if (gamepadOverlay != null && gamepadOverlay.getParent() != null) {
            ((ViewGroup) gamepadOverlay.getParent()).removeView(gamepadOverlay);
        }
        
        // 停止重复按键事件
        // Stop repeating key events
        stopRepeatEvents();
        
        // 重置摇杆状态
        // Reset stick states
        leftStickX = 0;
        leftStickY = 0;
        rightStickX = 0;
        rightStickY = 0;
        leftStickPointerId = -1;
        rightStickPointerId = -1;
    }
    
    /**
     * Re-add the gamepad overlay to the view hierarchy when switching back to gamepad mode
     */
    public void showOverlay() {
        if (gamepadOverlay != null) {
            // First check if it's already added
            if (gamepadOverlay.getParent() != null) {
                return; // Already added
            }
            
            activity.runOnUiThread(() -> {
                View touchpadView = activity.findViewById(R.id.touchpad);
                if (touchpadView != null && touchpadView.getParent() != null) {
                    ((android.widget.FrameLayout) touchpadView.getParent())
                            .addView(gamepadOverlay);
                } else {
                    // Fallback: add to the root view if touchpad parent is not available
                    android.view.ViewGroup rootView = (ViewGroup) activity.findViewById(android.R.id.content);
                    rootView.addView(gamepadOverlay);
                }
            });
        }
    }
}

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

import android.os.Build;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.R;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.gamepad.GamepadOverlay;
import com.undatech.opaque.util.GeneralUtils;

public class InputHandlerGamepad extends InputHandlerGeneric {
    public static final String ID = "GAMEPAD_MODE";
    static final String TAG = "InputHandlerGamepad";

    // 摇杆灵敏度控制常量
    private static final float STICK_MAX_RADIUS_DP = 30.0f;  // 摇杆最大半径，数值越小灵敏度越高
    private static final float STICK_DEADZONE = 0.05f;        // 摇杆死区，数值越小越灵敏

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
    private int screenMiddleX = 0;

    // Gamepad overlay reference
    private GamepadOverlay gamepadOverlay;

    // Handler for repeating key events (for d-pad and analog sticks)
    private Handler repeatHandler;
    private Runnable repeatRunnable;

    // Last repeat time
    private long lastRepeatTime = 0;
    private static final long REPEAT_INTERVAL_MS = 50;

    // RemoteGamepad instance for handling gamepad commands (completely abstracted)
    private RemoteGamepad remoteGamepad;

    // 标记是否已经初始化了overlay
    private boolean overlayInitialized = false;

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

        // 注意：不再在构造函数中立即创建overlay，而是在需要时延迟创建
        // Initialize RemoteGamepad through factory method (no protocol checking here)
        initializeRemoteGamepad();
    }

    /**
     * 延迟初始化游戏手柄overlay，只在真正需要时创建
     */
    private void initializeOverlayIfNeeded() {
        if (overlayInitialized) {
            return; // 已经初始化过了
        }

        // Initialize gamepad overlay
        gamepadOverlay = new GamepadOverlay(activity, this);
        
        // Set the screen dimension ID for button position storage
        // Using shared configuration across all connections
        String screenDimensionId = com.qihua.bVNC.util.ScreenDimensionUtils.getSimplifiedScreenIdentifier(activity);
        gamepadOverlay.setScreenDimensionId(screenDimensionId);
        
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
            
            // 显示overlay
            if (gamepadOverlay != null) {
                gamepadOverlay.setVisibility(View.VISIBLE);
            }
        });
        
        overlayInitialized = true;
    }
    
    /**
     * 主动显示游戏手柄overlay（用于模式切换时立即显示）
     */
    public void showOverlay() {
        android.util.Log.d(TAG, "showOverlay called, overlayInitialized: " + overlayInitialized);
        initializeOverlayIfNeeded();
        
        // 确保overlay可见
        if (gamepadOverlay != null) {
            activity.runOnUiThread(() -> {
                gamepadOverlay.setVisibility(View.VISIBLE);
                android.util.Log.d(TAG, "Ensured gamepad overlay visibility after showOverlay");
            });
        }
        
        // 重新初始化RemoteGamepad（如果需要）
        if (remoteGamepad == null) {
            initializeRemoteGamepad();
        }
    }

    /**
     * Initialize the RemoteGamepad instance through factory method
     * Completely abstracted from protocol details
     */
    private void initializeRemoteGamepad() {
        // Protocol detection happens ONLY here in the factory
        if (canvas.isNvStream()) {
            remoteGamepad = new NvStreamRemoteGamepad(touchpad, touchpad.getHandler(), debugLogging);
        } else if (canvas.isRdp()) {
            remoteGamepad = new RdpRemoteGamepad(touchpad, touchpad.getHandler(), debugLogging);
        } else if (canvas.isVnc()) {
            remoteGamepad = new VncRemoteGamepad(touchpad, touchpad.getHandler(), debugLogging);
        } else {
            // Throw exception for unsupported connection types
            throw new IllegalArgumentException("Unsupported connection type for gamepad");
        }

        // Initialize the gamepad
        remoteGamepad.initialize();
    }

    public void setScreenDimensions(int width, int height) {
        this.screenMiddleX = width / 2;
    }

    @Override
    public String getDescription() {
        return canvas.getResources().getString(R.string.input_method_gamepad_description);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    protected int getX(MotionEvent e) {
        // For gamepad, we do not use mouse coordinates
        return pointer.getX();
    }

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

        // 确保overlay已初始化
        initializeOverlayIfNeeded();

        // Update screen dimensions if needed
        setScreenDimensions(canvas.getWidth(), canvas.getHeight());

        // Determine which side of the screen the touch is on
        float touchX = e.getX(index);
        float touchY = e.getY(index);
        boolean isLeftSide = touchX < screenMiddleX;

        // 实时检查是否处于编辑模式（在处理事件期间编辑模式可能被退出）
        boolean isEditing = gamepadOverlay != null && gamepadOverlay.isEditMode();

        // 如果处于编辑模式，完全跳过游戏手柄的处理逻辑
        if (isEditing) {
            return false; // 不处理任何事件，让 GamepadOverlay 处理
        }

        // 让 GamepadOverlay 先处理按钮触摸事件
        // 这样可以确保多按钮同时按下的事件得到正确处理
        boolean handledByOverlay = false;
        if (gamepadOverlay != null) {
            handledByOverlay = gamepadOverlay.dispatchTouchEvent(e);
        }
        
        // 检查当前触摸点是否在按钮上
        boolean isOnCurrentButton = gamepadOverlay != null && gamepadOverlay.onButtonTouchRaw(touchX, touchY);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                // 只有当触摸不在任何按钮上时才处理为摇杆操作
                if (!isOnCurrentButton) {
                    handlePointerDown(pointerID, touchX, touchY, isLeftSide);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                // Handle all moving pointers during move events
                int pointerCount = e.getPointerCount();
                for (int i = 0; i < pointerCount; i++) {
                    int movingPointerId = e.getPointerId(i);
                    float movingX = e.getX(i);
                    float movingY = e.getY(i);
                    
                    // Check if this pointer is currently controlling a stick
                    boolean isControllingStick = (movingPointerId == leftStickPointerId) || (movingPointerId == rightStickPointerId);
                    
                    // If this pointer is controlling a stick, use the original side determination
                    // Otherwise, use the current position to determine the side
                    boolean isMovingPointerLeftSide = isControllingStick ?
                        (movingPointerId == leftStickPointerId) : (movingX < screenMiddleX);
                    
                    // If this moving pointer is not on a button, process as stick movement
                    boolean isOnButtonAtPosition = gamepadOverlay != null && gamepadOverlay.onButtonTouchRaw(movingX, movingY);
                    if (!isOnButtonAtPosition) {
                        handlePointerMove(e, movingPointerId, isMovingPointerLeftSide);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                // 释放对应的摇杆，不管抬起时是否在按钮上
                // 检查该指针是否正在控制任一摇杆
                if (pointerID == leftStickPointerId || pointerID == rightStickPointerId) {
                    handlePointerUp(pointerID);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                handlePointerCancel();
                break;
        }

        // 如果事件没有被overlay处理且不是在按钮上，说明这是一个摇杆操作
        // 重要：返回handledByOverlay以正确反映事件是否被处理
        return handledByOverlay;
    }

    private void handlePointerDown(int pointerId, float x, float y, boolean isLeftSide) {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer down: " + pointerId + ", leftSide: " + isLeftSide);

        // Check if this pointer is already tracking an analog stick
        if (leftStickPointerId == pointerId || rightStickPointerId == pointerId) {
            // This pointer is already tracking a stick, no need to assign again
            return;
        }

        // Check if the touch is on a button first - if so, don't assign to analog stick
        boolean isOnButton = gamepadOverlay != null && gamepadOverlay.onButtonTouchRaw(x, y);
        
        // Only assign to appropriate stick if not on a button
        if (!isOnButton) {
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
    }

    private void handlePointerMove(MotionEvent e, int pointerId, boolean isLeftSide) {
        int index = e.findPointerIndex(pointerId);
        if (index < 0) {
            return;
        }

        float x = e.getX(index);
        float y = e.getY(index);

        // Check if this pointer that was controlling a stick has moved onto a button
        boolean isOnButton = gamepadOverlay != null && gamepadOverlay.onButtonTouchRaw(x, y);
        
        // Handle analog stick movement only if not on button
        if (pointerId == leftStickPointerId) {
            if (isOnButton) {
                // If this stick control moved onto a button, release the stick
                leftStickPointerId = -1;
                leftStickX = 0;
                leftStickY = 0;
                sendBothSticksData();
                stopRepeatEvents();
                showLeftStickVisual(0, 0, false);
                
                // Let the button handle the touch event
                if (gamepadOverlay != null) {
                    gamepadOverlay.onButtonTouch(x, y, true); // Simulate button press
                }
            } else {
                updateAnalogStick(x, y, true);
            }
        } else if (pointerId == rightStickPointerId) {
            if (isOnButton) {
                // If this stick control moved onto a button, release the stick
                rightStickPointerId = -1;
                rightStickX = 0;
                rightStickY = 0;
                sendBothSticksData();
                stopRepeatEvents();
                showRightStickVisual(0, 0, false);
                
                // Let the button handle the touch event
                if (gamepadOverlay != null) {
                    gamepadOverlay.onButtonTouch(x, y, true); // Simulate button press
                }
            } else {
                updateAnalogStick(x, y, false);
            }
        }
    }

    private void handlePointerUp(int pointerId) {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer up: " + pointerId);

        // Handle analog stick release
        if (pointerId == leftStickPointerId) {
            leftStickPointerId = -1;
            leftStickX = 0;
            leftStickY = 0;
            sendBothSticksData();
            stopRepeatEvents();
            showLeftStickVisual(0, 0, false);
        } else if (pointerId == rightStickPointerId) {
            rightStickPointerId = -1;
            rightStickX = 0;
            rightStickY = 0;
            sendBothSticksData();
            stopRepeatEvents();
            showRightStickVisual(0, 0, false);
        }
    }

    private void handlePointerCancel() {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer cancel");

        // Send zero values to reset both sticks before clearing them
        sendBothSticksData();
        
        leftStickPointerId = -1;
        rightStickPointerId = -1;
        leftStickX = 0;
        leftStickY = 0;
        rightStickX = 0;
        rightStickY = 0;
        stopRepeatEvents();

        showLeftStickVisual(0, 0, false);
        showRightStickVisual(0, 0, false);
    }

    private void updateAnalogStick(float x, float y, boolean isLeft) {
        float centerX = isLeft ? leftStickCenterX : rightStickCenterX;
        float centerY = isLeft ? leftStickCenterY : rightStickCenterY;
        float maxRadius = STICK_MAX_RADIUS_DP * displayDensity;

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
        if (Math.abs(normalizedX) < STICK_DEADZONE && Math.abs(normalizedY) < STICK_DEADZONE) {
            normalizedX = 0;
            normalizedY = 0;
        }

        if (isLeft) {
            leftStickX = normalizedX;
            leftStickY = -normalizedY;
        } else {
            rightStickX = normalizedX;
            rightStickY = -normalizedY;
        }

        // Immediately send both analog stick data to ensure consistent state
        sendBothSticksData();

        // Start repeat for continuous input
        lastRepeatTime = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!repeatHandler.hasCallbacks(repeatRunnable)) {
                repeatHandler.post(repeatRunnable);
            }
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
            // If no active sticks, just send neutral position occasionally to maintain connection
            if (System.currentTimeMillis() - lastRepeatTime > 1000) {
                sendBothSticksData();
            }
            return;
        }

        // Send both stick data to maintain consistent state
        sendBothSticksData();
    }

    private void sendBothSticksData() {
        // Use RemoteGamepad abstraction - completely protocol-independent
        if (remoteGamepad != null) {
            remoteGamepad.sendAnalogSticks(leftStickX, leftStickY, rightStickX, rightStickY);
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
     * Send a gamepad button press through abstracted interface
     */
    public void sendGamepadButton(int keyCode) {
        sendGamepadButton(keyCode, false);
    }
    
    /**
     * Send a gamepad button press with option to auto-release through abstracted interface
     */
    public void sendGamepadButton(int keyCode, boolean autoRelease) {
        GeneralUtils.debugLog(debugLogging, TAG, "Sending gamepad button: " + keyCode + ", autoRelease: " + autoRelease);
        
        // Use RemoteGamepad abstraction - completely protocol-independent
        if (remoteGamepad != null) {
            if (!autoRelease) {
                // Button press
                remoteGamepad.sendButtonDown(keyCode);
            } else {
                // Button release
                remoteGamepad.sendButtonUp(keyCode);
            }
        }
    }

    /**
     * Handle screen size change (e.g., rotation)
     * Recreate overlay with new screen dimensions
     */
    public void handleScreenSizeChange() {
        android.util.Log.d(TAG, "handleScreenSizeChange called");
        if (gamepadOverlay != null) {
            // Remove existing overlay
            ViewGroup parent = (ViewGroup) gamepadOverlay.getParent();
            if (parent != null) {
                parent.removeView(gamepadOverlay);
            }
            gamepadOverlay = null;
        }
        
        // Force re-initialization by resetting the flag
        overlayInitialized = false;
        
        // Recreate overlay with new screen dimensions
        showOverlay();
    }

    @Override
    public void cleanup() {
        // Clean up RemoteGamepad resources through abstracted interface
        if (remoteGamepad != null) {
            remoteGamepad.cleanup();
            remoteGamepad = null;
        }
        
        // 移除游戏手柄覆盖层
        // Remove the gamepad overlay
        if (gamepadOverlay != null && gamepadOverlay.getParent() != null) {
            ((ViewGroup) gamepadOverlay.getParent()).removeView(gamepadOverlay);
        }
        
        // 重置状态
        overlayInitialized = false;
        
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
}

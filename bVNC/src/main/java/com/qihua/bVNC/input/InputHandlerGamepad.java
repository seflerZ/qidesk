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
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.qihua.bVNC.FpsCounter;
import com.qihua.bVNC.RemoteCanvas;
import com.qihua.bVNC.RemoteCanvasActivity;
import com.qihua.bVNC.gamepad.GamepadOverlay;
import com.undatech.opaque.NvCommunicator;
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
        if (canvas.connection != null) {
            gamepadOverlay.setConnectionId(canvas.connection.getId());
        }
        
        // Notify Moonlight that a gamepad has arrived
        if (canvas.rfbconn instanceof NvCommunicator) {
            // Send controller arrival event - this tells Moonlight that a gamepad is connected
            MoonBridge.sendControllerArrivalEvent(
                (byte) 0,  // controller number
                (short) 1, // active gamepad mask
                MoonBridge.LI_CTYPE_XBOX, // controller type (using Xbox as standard)
                0xFFFFFFFF, // supported button flags (all buttons supported)
                (short) (MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE) // capabilities
            );
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
        boolean isEditing = gamepadOverlay != null && gamepadOverlay.isEditMode();

        // 如果处于编辑模式，完全跳过游戏手柄的处理逻辑
        if (isEditing) {
            return false; // 不处理任何事件，让 GamepadOverlay 处理
        }

        // 在非编辑模式下，检查是否有任何按钮被触摸
        // 重要：这里我们不阻止按钮处理自己的触摸事件，而是让它们并行工作
        boolean isOnAnyButton = gamepadOverlay != null && gamepadOverlay.onButtonTouchRaw(touchX, touchY);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                // 只有当触摸不在任何按钮上时才处理为摇杆操作
                if (!isOnAnyButton) {
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
                    boolean isMovingLeftSide = movingX < screenMiddleX;
                    
                    // 如果这个移动的触摸点不在任何按钮上，才处理为摇杆移动
                    if (!isOnAnyButton || !gamepadOverlay.onButtonTouchRaw(movingX, movingY)) {
                        handlePointerMove(e, movingPointerId, isMovingLeftSide);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                // 只有之前被识别为摇杆操作的触摸点才在这里处理释放
                if (!isOnAnyButton) {
                    handlePointerUp(pointerID);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                handlePointerCancel();
                break;
        }

        // 重要：返回false以允许其他组件（如按钮）也处理触摸事件
        // 这样可以实现摇杆和按钮的同时操作
        return false;
    }

    private void handlePointerDown(int pointerId, float x, float y, boolean isLeftSide) {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer down: " + pointerId + ", leftSide: " + isLeftSide);

        // Check if this pointer is already tracking an analog stick
        if (leftStickPointerId == pointerId || rightStickPointerId == pointerId) {
            // This pointer is already tracking a stick, no need to assign again
            return;
        }

        // Assign to appropriate stick if available
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
        // If both sticks are in use and this is a new pointer not on a button, ignore it
    }

    private void handlePointerMove(MotionEvent e, int pointerId, boolean isLeftSide) {
        int index = e.findPointerIndex(pointerId);
        if (index < 0) {
            return;
        }

        float x = e.getX(index);
        float y = e.getY(index);

        // Handle analog stick movement
        if (pointerId == leftStickPointerId) {
            updateAnalogStick(x, y, true);
        } else if (pointerId == rightStickPointerId) {
            updateAnalogStick(x, y, false);
        }
    }

    private void handlePointerUp(int pointerId) {
        GeneralUtils.debugLog(debugLogging, TAG, "Pointer up: " + pointerId);

        // Handle analog stick release
        if (pointerId == leftStickPointerId) {
            leftStickPointerId = -1;
            leftStickX = 0;
            leftStickY = 0;
            // Send zero values to reset the left stick while keeping right stick state
            sendBothSticksData();
            stopRepeatEvents();
            showLeftStickVisual(0, 0, false);
        } else if (pointerId == rightStickPointerId) {
            rightStickPointerId = -1;
            rightStickX = 0;
            rightStickY = 0;
            // Send zero values to reset the right stick while keeping left stick state
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
        float maxRadius = 40 * displayDensity; // Radius reduced to 40 for shorter movement distance

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
        float deadzone = 0.1f; // Reduced deadzone for higher sensitivity
        if (Math.abs(normalizedX) < deadzone && Math.abs(normalizedY) < deadzone) {
            normalizedX = 0;
            normalizedY = 0;
        }

        if (isLeft) {
            leftStickX = normalizedX;
            leftStickY = -normalizedY; // Invert Y-axis to correct direction
        } else {
            rightStickX = normalizedX;
            rightStickY = -normalizedY; // Invert Y-axis to correct direction
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
            if (System.currentTimeMillis() - lastRepeatTime > 1000) { // Send neutral every second if no active input
                sendBothSticksData();
            }
            return;
        }

        // Send both stick data to maintain consistent state
        sendBothSticksData();
    }

    private void sendAnalogStickKeys(float x, float y, boolean isLeft) {
        // Check if we have an NvCommunicator connection
        if (canvas.rfbconn instanceof NvCommunicator) {

            // Calculate the stick values in the range -32767 to 32767
            short stickX, stickY, rightStickX, rightStickY;
            
            if (isLeft) {
                stickX = (short) (x * 32767);
                stickY = (short) (y * 32767);
                rightStickX = (short) (this.rightStickX * 32767); // Current right stick X value
                rightStickY = (short) (this.rightStickY * 32767); // Current right stick Y value
            } else {
                stickX = (short) (this.leftStickX * 32767); // Current left stick X value
                stickY = (short) (this.leftStickY * 32767); // Current left stick Y value
                rightStickX = (short) (x * 32767);
                rightStickY = (short) (y * 32767);
            }
            
            // Do NOT map stick positions to buttons - let the sticks work as analog sticks
            int buttonFlags = 0; // No button flags based on stick positions
            
            // Send the controller input through MoonBridge
            MoonBridge.sendMultiControllerInput(
                (short) 0,  // controller number
                (short) 1,  // active gamepad mask (first gamepad)
                buttonFlags,
                (byte) 0,   // left trigger (0-255 range)
                (byte) 0,   // right trigger (0-255 range)
                stickX,     // left stick X (-32767 to 32767)
                stickY,     // left stick Y (-32767 to 32767)
                rightStickX, // right stick X (-32767 to 32767)
                rightStickY  // right stick Y (-32767 to 32767)
            );
        }
    }
    
    private void sendBothSticksData() {
        // Check if we have an NvCommunicator connection
        if (canvas.rfbconn instanceof NvCommunicator) {
            // Send both sticks' current values
            MoonBridge.sendMultiControllerInput(
                (short) 0,  // controller number
                (short) 1,  // active gamepad mask (first gamepad)
                0,          // button flags
                (byte) 0,   // left trigger (0-255 range)
                (byte) 0,   // right trigger (0-255 range)
                (short) (leftStickX * 32767),  // left stick X (-32767 to 32767)
                (short) (leftStickY * 32767),  // left stick Y (-32767 to 32767)
                (short) (rightStickX * 32767), // right stick X (-32767 to 32767)
                (short) (rightStickY * 32767)  // right stick Y (-32767 to 32767)
            );
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
        sendGamepadButton(keyCode, true); // 默认自动发送释放事件，用于短按
    }
    
    /**
     * Send a gamepad button press with option to auto-release
     */
    public void sendGamepadButton(int keyCode, boolean autoRelease) {
        GeneralUtils.debugLog(debugLogging, TAG, "Sending gamepad button: " + keyCode + ", autoRelease: " + autoRelease);
        
        // Check if we have an NvCommunicator connection
        if (canvas.rfbconn instanceof NvCommunicator) {
            // Handle triggers differently - they are analog values, not digital buttons
            byte leftTriggerValue = 0;
            byte rightTriggerValue = 0;
            
            // Check if this is a trigger press
            boolean isTrigger = false;
            
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                leftTriggerValue = (byte) 0xFF; // Full press
                isTrigger = true;
            } else if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                rightTriggerValue = (byte) 0xFF; // Full press
                isTrigger = true;
            }
            
            if (isTrigger) {
                // For triggers, send with appropriate trigger values but no button flags
                // We still need to maintain the current stick positions
                MoonBridge.sendMultiControllerInput(
                    (short) 0,  // controller number
                    (short) 1,  // active gamepad mask (first gamepad)
                    0,  // button flags (no digital buttons pressed for triggers)
                    leftTriggerValue,   // left trigger (0-255 range)
                    rightTriggerValue,   // right trigger (0-255 range)
                    (short) (leftStickX * 32767),  // left stick X (maintain current position)
                    (short) (leftStickY * 32767),  // left stick Y (maintain current position)
                    (short) (rightStickX * 32767),  // right stick X (maintain current position)
                    (short) (rightStickY * 32767)   // right stick Y (maintain current position)
                );
                
                // Send release event after a short delay to simulate trigger press/release
                if (autoRelease) {
                    Handler handler = new Handler();
                    final byte finalLeftTriggerValue = leftTriggerValue;
                    final byte finalRightTriggerValue = rightTriggerValue;
                    final int finalKeyCode = keyCode;
                    
                    handler.postDelayed(() -> {
                        byte releaseLeftTrigger = (finalKeyCode == KeyEvent.KEYCODE_BUTTON_L2) ? (byte) 0x00 : finalLeftTriggerValue;
                        byte releaseRightTrigger = (finalKeyCode == KeyEvent.KEYCODE_BUTTON_R2) ? (byte) 0x00 : finalRightTriggerValue;
                        
                        MoonBridge.sendMultiControllerInput(
                            (short) 0,  // controller number
                            (short) 1,  // active gamepad mask (first gamepad)
                            0,  // button flags (released)
                            releaseLeftTrigger,   // left trigger
                            releaseRightTrigger,   // right trigger
                            (short) (leftStickX * 32767),  // left stick X (maintain current position)
                            (short) (leftStickY * 32767),  // left stick Y (maintain current position)
                            (short) (rightStickX * 32767),  // right stick X (maintain current position)
                            (short) (rightStickY * 32767)   // right stick Y (maintain current position)
                        );
                    }, 50); // 50ms delay
                }
            } else {
                // Handle regular buttons
                int buttonFlag = mapKeyCodeToGamepadButton(keyCode);
                
                if (buttonFlag != 0) {
                    // Send the controller input through MoonBridge
                    MoonBridge.sendMultiControllerInput(
                        (short) 0,  // controller number
                        (short) 1,  // active gamepad mask (first gamepad)
                        buttonFlag,  // button flags
                        (byte) 0,   // left trigger (0-255 range)
                        (byte) 0,   // right trigger (0-255 range)
                        (short) (leftStickX * 32767),  // left stick X (maintain current position)
                        (short) (leftStickY * 32767),  // left stick Y (maintain current position)
                        (short) (rightStickX * 32767),  // right stick X (maintain current position)
                        (short) (rightStickY * 32767)   // right stick Y (maintain current position)
                    );
                    
                    // Send release event after a short delay to simulate button press/release
                    if (autoRelease) {
                        Handler handler = new Handler();
                        
                        handler.postDelayed(() -> {
                            MoonBridge.sendMultiControllerInput(
                                (short) 0,  // controller number
                                (short) 1,  // active gamepad mask (first gamepad)
                                0,  // button flags (released)
                                (byte) 0,   // left trigger
                                (byte) 0,   // right trigger
                                (short) (leftStickX * 32767),  // left stick X (maintain current position)
                                (short) (leftStickY * 32767),  // left stick Y (maintain current position)
                                (short) (rightStickX * 32767),  // right stick X (maintain current position)
                                (short) (rightStickY * 32767)   // right stick Y (maintain current position)
                            );
                        }, 50); // 50ms delay
                    }
                } else {
                    // Fallback to keyboard if not a gamepad button
                    RemoteKeyboard keyboard = canvas.getKeyboard();
                    int metaState = keyboard.getMetaState();

                    keyboard.keyEvent(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                    if (autoRelease) {
                        keyboard.keyEvent(keyCode, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                    }
                }
            }
        }
    }
    
    /**
     * Maps Android key codes to gamepad button flags
     */
    private int mapKeyCodeToGamepadButton(int keyCode) {
        switch (keyCode) {
            // Standard gamepad buttons - according to typical Android mapping
            case KeyEvent.KEYCODE_BUTTON_A:  // Physical A button on gamepad (typically bottom button)
                return ControllerPacket.A_FLAG; // A button (A_FLAG) - Bottom action button
            case KeyEvent.KEYCODE_BUTTON_B:  // Physical B button on gamepad (typically right button) 
                return ControllerPacket.B_FLAG; // B button (B_FLAG) - Right action button
            case KeyEvent.KEYCODE_BUTTON_X:  // Physical X button on gamepad (typically left button)
                return ControllerPacket.X_FLAG; // X button (X_FLAG) - Left action button
            case KeyEvent.KEYCODE_BUTTON_Y:  // Physical Y button on gamepad (typically top button)
                return ControllerPacket.Y_FLAG; // Y button (Y_FLAG) - Top action button
            case KeyEvent.KEYCODE_BUTTON_L1:
                return ControllerPacket.LB_FLAG; // Left bumper (LB_FLAG)
            case KeyEvent.KEYCODE_BUTTON_R1:
                return ControllerPacket.RB_FLAG; // Right bumper (RB_FLAG)
            // Note: L2 and R2 are handled separately as triggers, not as button flags
            case KeyEvent.KEYCODE_BUTTON_START:
                return ControllerPacket.PLAY_FLAG; // Start button (PLAY_FLAG)
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return ControllerPacket.BACK_FLAG; // Select/Back button (BACK_FLAG)
            case KeyEvent.KEYCODE_DPAD_UP:
                return ControllerPacket.UP_FLAG; // D-pad up (UP_FLAG)
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return ControllerPacket.DOWN_FLAG; // D-pad down (DOWN_FLAG)
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return ControllerPacket.LEFT_FLAG; // D-pad left (LEFT_FLAG)
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return ControllerPacket.RIGHT_FLAG; // D-pad right (RIGHT_FLAG)
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return ControllerPacket.LS_CLK_FLAG; // Left stick button (LS_CLK_FLAG)
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return ControllerPacket.RS_CLK_FLAG; // Right stick button (RS_CLK_FLAG)
            default:
                return 0; // Not a gamepad button
        }
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
        
        // Notify Moonlight that the gamepad has disconnected
        if (canvas.rfbconn instanceof NvCommunicator) {
            // Send controller removal event - this tells Moonlight that a gamepad is disconnected
            MoonBridge.sendControllerArrivalEvent(
                (byte) 0,  // controller number
                (short) 0, // active gamepad mask (no gamepads connected)
                MoonBridge.LI_CTYPE_XBOX, // controller type
                0, // supported button flags (no buttons supported)
                (short) 0 // capabilities (none)
            );
        }
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
            
            // Notify Moonlight that a gamepad has arrived again
            if (canvas.rfbconn instanceof NvCommunicator) {
                // Send controller arrival event - this tells Moonlight that a gamepad is connected
                MoonBridge.sendControllerArrivalEvent(
                    (byte) 0,  // controller number
                    (short) 1, // active gamepad mask
                    MoonBridge.LI_CTYPE_XBOX, // controller type (using Xbox as standard)
                    0xFFFFFFFF, // supported button flags (all buttons supported)
                    (short) (MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE) // capabilities
                );
            }
            
            activity.runOnUiThread(() -> {
                View touchpadView = activity.findViewById(R.id.touchpad);
                if (touchpadView != null && touchpadView.getParent() != null) {
                    ((android.widget.FrameLayout) touchpadView.getParent())
                            .addView(gamepadOverlay);
                } else {
                    // Fallback: add to the root view if touchpad parent is not available
                    android.view.ViewGroup rootView = activity.findViewById(android.R.id.content);
                    rootView.addView(gamepadOverlay);
                }
            });
        }
    }
}

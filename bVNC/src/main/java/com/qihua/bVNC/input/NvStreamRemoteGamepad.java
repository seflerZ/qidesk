package com.qihua.bVNC.input;

import android.os.Handler;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.NvCommunicator;
import com.undatech.opaque.RfbConnectable;
import com.undatech.opaque.util.GeneralUtils;

/**
 * NVStream协议的游戏手柄实现类
 * 继承RemoteGamepad，专门处理Moonlight/NVStream连接的游戏手柄输入
 */
public class NvStreamRemoteGamepad extends RemoteGamepad {
    private static final String TAG = "NvStreamRemoteGamepad";
    
    public NvStreamRemoteGamepad(RfbConnectable protocomm, RemoteCanvas canvas, Handler handler,
                                 boolean debugLogging) {
        super(protocomm, canvas, handler, debugLogging);
    }
    
    @Override
    public void initialize() {
        GeneralUtils.debugLog(debugLogging, TAG, "Initializing NVStream gamepad");
        
        // 发送控制器到达事件 - 通知Moonlight有一个游戏手柄已连接
        MoonBridge.sendControllerArrivalEvent(
            (byte) 0,  // 控制器编号
            (short) 1, // 激活的游戏手柄掩码
            MoonBridge.LI_CTYPE_XBOX, // 控制器类型（使用Xbox作为标准）
            0xFFFFFFFF, // 支持的按钮标志（所有按钮都支持）
            (short) (MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE) // 功能特性
        );
        
        GeneralUtils.debugLog(debugLogging, TAG, "NVStream gamepad initialized successfully");
    }
    
    @Override
    public void cleanup() {
        GeneralUtils.debugLog(debugLogging, TAG, "Cleaning up NVStream gamepad");
        
        // 检查是否是NvCommunicator连接
        if (!(protocomm instanceof NvCommunicator)) {
            return;
        }
        
        // 发送控制器移除事件 - 通知Moonlight游戏手柄已断开连接
        MoonBridge.sendControllerArrivalEvent(
            (byte) 0,  // 控制器编号
            (short) 0, // 激活的游戏手柄掩码（无游戏手柄连接）
            MoonBridge.LI_CTYPE_XBOX, // 控制器类型
            0, // 支持的按钮标志（无按钮支持）
            (short) 0 // 功能特性（无功能）
        );
        
        GeneralUtils.debugLog(debugLogging, TAG, "NVStream gamepad cleaned up successfully");
    }
    
    @Override
    public void sendButtonDown(int keyCode) {
        GeneralUtils.debugLog(debugLogging, TAG, "Sending button down: " + keyCode);
        
        // 检查是否是NvCommunicator连接
        if (!(protocomm instanceof NvCommunicator)) {
            return;
        }
        
        // 处理触发器按钮（L2/R2）
        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L2) {
            setLeftTriggerValue((byte) 0xFF); // 完全按下
            sendControllerInputWithCurrentState();
            return;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
            setRightTriggerValue((byte) 0xFF); // 完全按下
            sendControllerInputWithCurrentState();
            return;
        }
        
        // 处理常规按钮
        int buttonFlag = mapKeyCodeToControllerPacket(keyCode);
        if (buttonFlag != 0) {
            // 更新当前按钮标志位（添加按下按钮）
            setCurrentButtonFlags(getCurrentButtonFlags() | buttonFlag);
            sendControllerInputWithCurrentState();
        }
    }
    
    @Override
    public void sendButtonUp(int keyCode) {
        GeneralUtils.debugLog(debugLogging, TAG, "Sending button up: " + keyCode);
        
        // 检查是否是NvCommunicator连接
        if (!(protocomm instanceof NvCommunicator)) {
            return;
        }
        
        // 处理触发器按钮释放（L2/R2）
        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L2) {
            setLeftTriggerValue((byte) 0x00); // 完全释放
            sendControllerInputWithCurrentState();
            return;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
            setRightTriggerValue((byte) 0x00); // 完全释放
            sendControllerInputWithCurrentState();
            return;
        }
        
        // 处理常规按钮释放
        int buttonFlag = mapKeyCodeToControllerPacket(keyCode);
        if (buttonFlag != 0) {
            // 更新当前按钮标志位（移除释放按钮）
            setCurrentButtonFlags(getCurrentButtonFlags() & ~buttonFlag);
            sendControllerInputWithCurrentState();
        }
    }
    
    @Override
    public void sendAnalogSticks(float leftStickX, float leftStickY, 
                                float rightStickX, float rightStickY) {
        GeneralUtils.debugLog(debugLogging, TAG, "Sending analog sticks: L(" + leftStickX + "," + leftStickY + 
                             ") R(" + rightStickX + "," + rightStickY + ")");
        
        // 检查是否是NvCommunicator连接
        if (!(protocomm instanceof NvCommunicator)) {
            return;
        }
        
        // 发送控制器输入，包含摇杆数据和当前按钮状态
        MoonBridge.sendMultiControllerInput(
            (short) 0,  // 控制器编号
            (short) 1,  // 激活的游戏手柄掩码（第一个游戏手柄）
            getCurrentButtonFlags(),  // 当前按钮标志位
            getLeftTriggerValue(),    // 左触发器值 (0-255范围)
            getRightTriggerValue(),   // 右触发器值 (0-255范围)
            (short) (leftStickX * 32767),   // 左摇杆X (-32767到32767)
            (short) (leftStickY * 32767),   // 左摇杆Y (-32767到32767)
            (short) (rightStickX * 32767),  // 右摇杆X (-32767到32767)
            (short) (rightStickY * 32767)   // 右摇杆Y (-32767到32767)
        );
    }
    
    /**
     * 使用当前状态发送完整的控制器输入
     */
    private void sendControllerInputWithCurrentState() {
        // 发送控制器输入，保持当前的摇杆位置
        MoonBridge.sendMultiControllerInput(
            (short) 0,  // 控制器编号
            (short) 1,  // 激活的游戏手柄掩码（第一个游戏手柄）
            getCurrentButtonFlags(),     // 当前按钮标志位
            getLeftTriggerValue(),       // 左触发器值
            getRightTriggerValue(),      // 右触发器值
            (short) 0,  // 左摇杆X（零值，因为这是按钮事件）
            (short) 0,  // 左摇杆Y（零值）
            (short) 0,  // 右摇杆X（零值）
            (short) 0   // 右摇杆Y（零值）
        );
    }
    
    /**
     * 将Android按键码映射到ControllerPacket按钮标志
     * @param keyCode Android按键码
     * @return 对应的ControllerPacket按钮标志
     */
    private int mapKeyCodeToControllerPacket(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_BUTTON_A:  // 物理A按钮
                return ControllerPacket.A_FLAG; // A按钮 (底部动作按钮)
            case android.view.KeyEvent.KEYCODE_BUTTON_B:  // 物理B按钮
                return ControllerPacket.B_FLAG; // B按钮 (右侧动作按钮)
            case android.view.KeyEvent.KEYCODE_BUTTON_X:  // 物理X按钮
                return ControllerPacket.X_FLAG; // X按钮 (左侧动作按钮)
            case android.view.KeyEvent.KEYCODE_BUTTON_Y:  // 物理Y按钮
                return ControllerPacket.Y_FLAG; // Y按钮 (顶部动作按钮)
            case android.view.KeyEvent.KEYCODE_BUTTON_L1:
                return ControllerPacket.LB_FLAG; // 左保险杠
            case android.view.KeyEvent.KEYCODE_BUTTON_R1:
                return ControllerPacket.RB_FLAG; // 右保险杠
            case android.view.KeyEvent.KEYCODE_BUTTON_START:
                return ControllerPacket.PLAY_FLAG; // 开始按钮
            case android.view.KeyEvent.KEYCODE_BUTTON_SELECT:
                return ControllerPacket.BACK_FLAG; // 选择/返回按钮
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                return ControllerPacket.UP_FLAG; // 方向键上
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                return ControllerPacket.DOWN_FLAG; // 方向键下
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                return ControllerPacket.LEFT_FLAG; // 方向键左
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                return ControllerPacket.RIGHT_FLAG; // 方向键右
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBL:
                return ControllerPacket.LS_CLK_FLAG; // 左摇杆按钮
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBR:
                return ControllerPacket.RS_CLK_FLAG; // 右摇杆按钮
            default:
                return 0; // 不是游戏手柄按钮
        }
    }
}
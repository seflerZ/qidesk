package com.qihua.bVNC.input;

import android.os.Handler;

import com.qihua.bVNC.R;
import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.util.GeneralUtils;

/**
 * RDP协议的游戏手柄实现类
 * 继承RemoteGamepad，为RDP连接提供游戏手柄支持
 */
public class RdpRemoteGamepad extends RemoteGamepad {
    private static final String TAG = "RdpRemoteGamepad";
    
    public RdpRemoteGamepad(RemoteCanvas canvas, Handler handler,
                            boolean debugLogging) {
        super(canvas, handler, debugLogging);
    }
    
    @Override
    public void initialize() {
        GeneralUtils.debugLog(debugLogging, TAG, "Initializing RDP gamepad");
        // RDP协议的游戏手柄初始化逻辑
        showUnsupportedToast(context.getString(R.string.rdp_gamepad_not_supported));
    }
    
    @Override
    public void cleanup() {
        GeneralUtils.debugLog(debugLogging, TAG, "Cleaning up RDP gamepad");
        // RDP协议的游戏手柄清理逻辑
    }
    
    @Override
    public void sendButtonDown(int keyCode) {
        GeneralUtils.debugLog(debugLogging, TAG, "RDP gamepad button down: " + keyCode);
        showUnsupportedToast(context.getString(R.string.rdp_gamepad_not_supported));
    }
    
    @Override
    public void sendButtonUp(int keyCode) {
        GeneralUtils.debugLog(debugLogging, TAG, "RDP gamepad button up: " + keyCode);
        // 不显示提示，避免频繁弹出toast
    }
    
    @Override
    public void sendAnalogSticks(float leftStickX, float leftStickY, 
                                float rightStickX, float rightStickY) {
        GeneralUtils.debugLog(debugLogging, TAG, "RDP gamepad analog sticks: L(" + leftStickX + "," + leftStickY + 
                             ") R(" + rightStickX + "," + rightStickY + ")");
        // 不显示提示，避免频繁弹出toast
    }
}
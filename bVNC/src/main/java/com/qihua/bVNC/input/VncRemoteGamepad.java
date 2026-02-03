package com.qihua.bVNC.input;

import android.os.Handler;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.RfbConnectable;
import com.undatech.opaque.util.GeneralUtils;

/**
 * VNC协议的游戏手柄实现类
 * 继承RemoteGamepad，为VNC连接提供游戏手柄支持
 */
public class VncRemoteGamepad extends RemoteGamepad {
    private static final String TAG = "VncRemoteGamepad";
    
    public VncRemoteGamepad(RfbConnectable protocomm, RemoteCanvas canvas, Handler handler,
                           boolean debugLogging) {
        super(protocomm, canvas, handler, debugLogging);
    }
    
    @Override
    public void initialize() {
        GeneralUtils.debugLog(debugLogging, TAG, "Initializing VNC gamepad");
        // VNC协议的游戏手柄初始化逻辑
        showUnsupportedToast("VNC连接暂不支持手柄功能");
    }
    
    @Override
    public void cleanup() {
        GeneralUtils.debugLog(debugLogging, TAG, "Cleaning up VNC gamepad");
        // VNC协议的游戏手柄清理逻辑
    }
    
    @Override
    public void sendButtonDown(int keyCode) {
        GeneralUtils.debugLog(debugLogging, TAG, "VNC gamepad button down: " + keyCode);
        showUnsupportedToast("VNC连接暂不支持手柄功能");
    }
    
    @Override
    public void sendButtonUp(int keyCode) {
        GeneralUtils.debugLog(debugLogging, TAG, "VNC gamepad button up: " + keyCode);
        // 不显示提示，避免频繁弹出toast
    }
    
    @Override
    public void sendAnalogSticks(float leftStickX, float leftStickY, 
                                float rightStickX, float rightStickY) {
        GeneralUtils.debugLog(debugLogging, TAG, "VNC gamepad analog sticks: L(" + leftStickX + "," + leftStickY + 
                             ") R(" + rightStickX + "," + rightStickY + ")");
        // 不显示提示，避免频繁弹出toast
    }
}
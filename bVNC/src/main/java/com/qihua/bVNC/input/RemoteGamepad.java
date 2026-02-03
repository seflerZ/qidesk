package com.qihua.bVNC.input;

import android.content.Context;
import android.os.Handler;
import android.widget.Toast;

import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.RfbConnectable;

/**
 * 抽象游戏手柄类，为不同连接类型提供统一的游戏手柄接口
 * 类似于RemotePointer的设计模式
 */
public abstract class RemoteGamepad {
    protected static final String TAG = "RemoteGamepad";
    
    protected RemoteCanvas canvas;
    protected Context context;
    protected Handler handler;
    protected RfbConnectable protocomm;
    protected boolean debugLogging = false;
    
    // 当前按钮状态
    protected int currentButtonFlags = 0;
    
    // 模拟触发器值
    protected byte leftTriggerValue = 0;
    protected byte rightTriggerValue = 0;
    
    public RemoteGamepad(RemoteCanvas canvas, Handler handler,
                         boolean debugLogging) {
        this.protocomm = canvas.rfbconn;
        this.canvas = canvas;
        this.context = canvas.getContext();
        this.handler = handler;
        this.debugLogging = debugLogging;
    }
    
    /**
     * 初始化游戏手柄连接
     * 子类应该在此方法中注册游戏手柄或建立必要的连接
     */
    public abstract void initialize();
    
    /**
     * 清理游戏手柄资源
     * 子类应该在此方法中注销游戏手柄或断开连接
     */
    public abstract void cleanup();
    
    /**
     * 发送按钮按下事件
     * @param keyCode Android按键码
     */
    public abstract void sendButtonDown(int keyCode);
    
    /**
     * 发送按钮释放事件
     * @param keyCode Android按键码
     */
    public abstract void sendButtonUp(int keyCode);
    
    /**
     * 发送模拟摇杆数据
     * @param leftStickX 左摇杆X轴值 (-1.0 到 1.0)
     * @param leftStickY 左摇杆Y轴值 (-1.0 到 1.0)
     * @param rightStickX 右摇杆X轴值 (-1.0 到 1.0)
     * @param rightStickY 右摇杆Y轴值 (-1.0 到 1.0)
     */
    public abstract void sendAnalogSticks(float leftStickX, float leftStickY, 
                                         float rightStickX, float rightStickY);
    
    /**
     * 显示不支持提示信息
     * @param message 提示信息
     */
    protected void showUnsupportedToast(String message) {
        handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
    
    /**
     * 获取当前按钮标志位
     * @return 当前按下的按钮标志组合
     */
    public int getCurrentButtonFlags() {
        return currentButtonFlags;
    }
    
    /**
     * 设置当前按钮标志位
     * @param flags 按钮标志位
     */
    public void setCurrentButtonFlags(int flags) {
        this.currentButtonFlags = flags;
    }
    
    /**
     * 获取左触发器值
     * @return 左触发器值 (0-255)
     */
    public byte getLeftTriggerValue() {
        return leftTriggerValue;
    }
    
    /**
     * 设置左触发器值
     * @param value 触发器值 (0-255)
     */
    public void setLeftTriggerValue(byte value) {
        this.leftTriggerValue = value;
    }
    
    /**
     * 获取右触发器值
     * @return 右触发器值 (0-255)
     */
    public byte getRightTriggerValue() {
        return rightTriggerValue;
    }
    
    /**
     * 设置右触发器值
     * @param value 触发器值 (0-255)
     */
    public void setRightTriggerValue(byte value) {
        this.rightTriggerValue = value;
    }
}
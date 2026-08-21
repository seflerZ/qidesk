# QxRemotes 项目架构规格书

> 本文档面向"需要在该项目中新增一种协议 / 改一类界面 / 动 native 链路"的开发者,描述 QxRemotes(奇花远程)的模块划分、类层级、统一的输入 / 绘制 / native 编译契约,以及五条协议(VNC / RDP / SPICE / NVStream / SSH)的具体落点。
>
> 配套:
> - SSH 实现细节: [`ssh-feature-spec.md`](./ssh-feature-spec.md)
> - libvterm 集成说明: [`libvterm-integration.md`](./libvterm-integration.md)
> - 项目结构简版: [`project-structure.md`](./project-structure.md)

---

## 1. 项目定位

**QxRemotes(奇花远程)** 是一个 Android 上的多协议远程客户端(代号"协议聚合"),基于 iiordanov 的 [aRDP](https://github.com/iiordanov/remote-desktop-clients) fork。

- **支持的协议**:VNC / RDP / SPICE / NVStream(Moonlight) / SSH
- **代码总量**:核心 + UI ≈ 7 万行 Java + 少量 Kotlin
- **最低 SDK**:26,目标/编译 SDK:35
- **ABI**:arm64-v8a only(`remoteClientLib/build.gradle:30`)

---

## 2. 模块布局(`settings.gradle`)

```
remote-desktop-clients/
├── aRDP-app/             # APK 壳:com.qihua.rmt(Google Play 版)
├── bVNC/                 # APK 主体 + UI 库:com.qihua.bVNC  ← 主要工作都在这
├── remoteClientLib/      # 协议无关核心:com.undatech.opaque.*
├── pubkeyGenerator/      # SSH 公钥生成小工具(独立 lib)
├── common/               # 占位模块(目前基本空)
├── native-clients-libs/  (隐式 root 节点)
└── specs/                # 规格书目录
```

### 2.1 `aRDP-app` vs `bVNC` 的关系

| 维度 | `aRDP-app` | `bVNC` |
|------|------------|--------|
| 类型 | `com.android.application` | `com.android.library` + 同时也是 application(双身份) |
| 源码 | 0 行 Java,只一个 `AndroidManifest.xml` | ≈ 6 万行 Java |
| 入口 | `com.undatech.opaque.ConnectionGridActivity` | 同上 |
| Application 类 | `com.qihua.bVNC.App`(来自 bVNC 库) | — |
| 含义 | "RDP 专用发行包"的薄壳,所有 UI/逻辑都复用 bVNC | "VNC 专用发行包" + 公共 UI 库 |

> **结论**:所有 UI / 协议实现代码都落在 `bVNC/`。`aRDP-app` 只是个用来切换 applicationId 和签名的壳。

### 2.2 模块依赖关系

```
aRDP-app ──┐
           ├─→ bVNC ──→ remoteClientLib ──→ freeRDPCore (JNI)
bVNC(lib) ─┘                  │
                              └─→ moonlight-android (JNI)

bVNC ──→ pubkeyGenerator (公钥生成,api org.connectbot:sshlib:2.2.20)
bVNC ──→ remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore
bVNC ──→ remoteClientLib:jni:libs:deps:moonlight-android:app
```

显式声明于 `settings.gradle`:

```
include ':remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore'
include ':remoteClientLib:jni:libs:deps:moonlight-android:app'
include ':pubkeyGenerator'
include ':bVNC'
include ':aRDP-app'
include ':remoteClientLib'
include ':native-clients-libs'
include ':common'
```

> **`libvterm` 故意不是 AGP 模块**——`settings.gradle` 没有 `include ':...:libvterm'`。原因:libvterm 没有 Java 表面,纯 C 源码,只通过一个 JNI 桥(`remoteClientLib/jni/src/vterm_jni.c`)被 ndk-build 编出 `.so`,产物走 jniLibs 路径打包进 APK。详见 §9。

---

## 3. 协议支持矩阵

| 协议 | Config Activity | Initializer | Communicator | Keyboard | Pointer | Gamepad | BitmapData | 后端 |
|------|-----------------|-------------|--------------|----------|---------|---------|-----------|------|
| VNC | `ConfigVNC` | `VncConnectionInitializer` (`bVNC/.../connection/VncConnectionInitializer.java:34`) | `RfbCommunicator` (`bVNC/.../communicator/RfbCommunicator.java`, **不继承 `RemoteConnectable`**) | `RemoteVncKeyboard` (`bVNC/.../input/RemoteVncKeyboard.java:14`) | `RemoteVncPointer` (`bVNC/.../input/RemoteVncPointer.java:27`) | `VncRemoteGamepad`(不支持,只 toast) | `FullBufferBitmapData` / `CompactBitmapData` / `LargeBitmapData` | 纯 Java 自研 RFB |
| RDP | `ConfigRDP` | `RdpConnectionInitializer` (`bVNC/.../connection/RdpConnectionInitializer.java:21`) | `RdpCommunicator extends RemoteConnectable` (`remoteClientLib/.../RdpCommunicator.java:28`) | `RemoteRdpKeyboard` (`bVNC/.../input/RemoteRdpKeyboard.java:15`) | `RemoteRdpPointer` (`bVNC/.../input/RemoteRdpPointer.java:8`) | `RdpRemoteGamepad`(不支持) | `UltraCompactBitmapData` | FreeRDP JNI |
| SPICE | (复用 ConfigRDP,`Utils.isSpice` flavor) | `SpiceConnectionInitializer` (`bVNC/.../connection/SpiceConnectionInitializer.java:53`) | `SpiceCommunicator extends RemoteConnectable` (`remoteClientLib/.../SpiceCommunicator.java:48`) | `RemoteSpiceKeyboard` (`bVNC/.../input/RemoteSpiceKeyboard.java:39`) | `RemoteSpicePointer` (`bVNC/.../input/RemoteSpicePointer.java:28`) | — | `UltraCompactBitmapData` 或 `CompactBitmapData` | FreeRDP oVirt JNI |
| NVStream | `ConfigNVStream` | `NvStreamConnectionInitializer` (`bVNC/.../connection/NvStreamConnectionInitializer.java:33`) | `NvCommunicator extends RemoteConnectable` (`remoteClientLib/.../NvCommunicator.java:34`) | `RemoteNvStreamKeyboard` (`bVNC/.../input/RemoteNvStreamKeyboard.java:14`) | `RemoteNvStreamPointer` (`bVNC/.../input/RemoteNvStreamPointer.java:8`) | `NvStreamRemoteGamepad`(完整支持) | `UltraCompactBitmapData` | Moonlight JNI |
| **SSH** | **`ConfigSSH`** | **`SshConnectionInitializer`** (`bVNC/.../connection/SshConnectionInitializer.java:60`,938 行) | `SshCommunicator extends RemoteConnectable`(`bVNC/.../communicator/SshCommunicator.java:23`,write 全 no-op) | `RemoteSshKeyboard` (`bVNC/.../input/RemoteSshKeyboard.java:26`) | `RemoteSshPointer` (`bVNC/.../input/RemoteSshPointer.java:17`,按钮全 no-op,只 scrollback + 选区) | — | `DoubleBufferBitmapData` (`bVNC/.../DoubleBufferBitmapData.java:22`) | libvterm + trilead-ssh2 |

> **SSH 是特例**:它没有传统的"协议字节流 → 位图"流——它把 libvterm 的 cell grid 直接渲染成位图后,**沿用其他协议的 `AbstractBitmapData` 管线**(`DoubleBufferBitmapData` 是 SSH 专属,但走的是同一个 `drawBitmap` 收口点)。

---

## 4. 包结构

```
bVNC/src/main/java/
├── com.qihua.bVNC/                  # VNC 协议核心 + App 入口 + SSH 实现
│   ├── App.java
│   ├── ConfigVNC/RDP/NVStream/SSH   # 各协议配置页
│   ├── RemoteCanvas/RemoteCanvasActivity   # 核心 Activity(2104 行)
│   ├── communicator/                # 5 个 Communicator(RFB + 4 个 RemoteConnectable 子类)
│   ├── connection/                  # 5 个 *ConnectionInitializer + Factory + ProtocolType
│   ├── input/                       # 5 套 RemoteKeyboard/Pointer/Gamepad + InputHandler 策略
│   ├── ssh/                         # SSH 终端实现
│   │   ├── SSHConnection.java       # 旧 VNC-over-SSH 隧道(881 行)
│   │   ├── SshTerminalConnection.java   # Phase 3.1+:trilead 包装
│   │   ├── SshShellChannel.java     # 8 KB pipe 桥接 trilead ↔ state machine
│   │   ├── SshTerminalRenderer.java # libvterm 渲染宿主
│   │   ├── SshTerminalScaling.java  # 1:1 scaler(IME push-up 复用 RDP 管线)
│   │   ├── TermFontFactory.java     # 加载 SarasaMonoSCNerd 字体
│   │   └── libvterm/                # libvterm Java 绑定 + 渲染器
│   │       ├── SshTermStateMachine.java   # Java wrapper over JNI(long nativeHandle)
│   │       ├── VTermCanvasRenderer.java   # cell → Canvas 绘制
│   │       ├── TermCell.java
│   │       └── CursorInfo.java
│   ├── AbstractBitmapData.java + 5 子类
│   ├── AbstractBitmapDrawable.java
│   └── draw/DrawWorker.java         # 单一绘制点(见 §6)
│
└── com.undatech.opaque/             # 协议无关 UI(从 bVNC 抽出来的)
    ├── ConnectionGridActivity.java  # 连接列表 + 类型选择
    ├── Viewable.java / RemoteConnectable.java
    ├── RdpCommunicator / SpiceCommunicator / NvCommunicator
    ├── DrawTask.java
    └── input/                       # RemoteKeyboard / RemotePointer 抽象基类 + RdpKeyboardMapper

remoteClientLib/src/main/java/com/undatech/opaque/   # 与 bVNC 镜像,打成 jar 给 bVNC 引用
```

> **注意**:`com.undatech.opaque` 这个包**同时存在两份**——一份在 `bVNC/`,一份在 `remoteClientLib/`,后者被打成 jar 通过 `implementation files('libs/...jar')` 注入到 bVNC。这是历史包袱,新增代码应避免这种分叉。

---

## 5. 类层级(四层架构)

```
第 1 层:Android 系统事件
  RemoteCanvasActivity(2104 行)
    implements OnKeyListener, OnGenericMotionListener, GameGestures
    ↓ 收 KeyEvent / MotionEvent
第 2 层:模式选择(策略模式)
  InputHandler (interface, bVNC/input/InputHandler.java:26)
    ↑
    InputHandlerGeneric(abstract, bVNC/input/InputHandlerGeneric.java:50)
      ↑ DirectTouch | Touchpad | Gamepad | DPadMouse | DragPan | SwipePan
    ↓ 翻译成 X11 KeySym + 坐标 + scroll delta
第 3 层:协议无关抽象
  RemoteKeyboard(abstract,remoteClientLib/input/RemoteKeyboard.java:32)
    → sendKeySym / sendUnicode / sendText / processLocalKeyEvent
  RemotePointer(abstract,remoteClientLib/input/RemotePointer.java:25)
    → leftButtonDown / moveMouse / scrollUp/Down / touchDown/Update/Up
      ↑ 5 个协议各一份(见 §3 矩阵)
第 4 层:协议实现
  RfbCommunicator | RdpCommunicator | SpiceCommunicator | NvCommunicator | SshCommunicator
  ↑ 各自由 native lib / libvterm / 自研编码为协议字节流
```

### 5.1 RemoteKeyboard 与 RemotePointer 的契约

`RemoteKeyboard` 只声明一个抽象方法 `processLocalKeyEvent(int keyCode, KeyEvent evt, int additionalMetaState)`(`RemoteKeyboard.java:99`)。其他如 `sendKeySym`、`sendUnicode`、`sendText` 是具体方法,通过子类覆写 `processLocalKeyEvent` 完成编码。

`RemotePointer` 声明 14 个抽象方法(`RemotePointer.java:25-71`):
- 鼠标:`leftButtonDown / middleButtonDown / rightButtonDown / releaseButton / moveMouse / moveMouseButtonDown / moveMouseButtonUp`
- 滚轮:`scrollUp / scrollDown / scrollLeft / scrollRight`
- 触控:`touchDown / touchUpdate / touchUp / touchCancel`(子类自由扩展)
- 元数据:`hardwareButtonsAsMouseEvents / movePointer / movePointerToMakeVisible / getX/setX / getY/setY`

---

## 6. 统一绘制点(★ 核心架构契约)

**所有协议最终在 `DrawWorker.run()` 第 129 行收口**:

```java
// bVNC/.../draw/DrawWorker.java:121-129
glCanvas = holder.lockHardwareCanvas();                              // :121
...
glCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);        // :127
canvas.bitmapData.drawable.draw(glCanvas);                           // :129  ← 唯一绘制点
```

`AbstractBitmapDrawable.draw(Canvas)`(`bVNC/.../AbstractBitmapDrawable.java:71`)只做一件事:

```java
synchronized (this) {
    canvas.drawBitmap(data.mbitmap, xoff, yoff, _defaultPaint);  // 协议位图
    canvas.drawBitmap(softCursor, cursorRect.left, cursorRect.top, _defaultPaint);  // 软光标
}
```

### 6.1 契约

> **任何协议,只要"维护一个 `Bitmap mbitmap`,就能被绘制。**

| 协议 | `AbstractBitmapData` 子类 | 谁填 `mbitmap` |
|------|---------------------------|----------------|
| VNC | `FullBufferBitmapData` / `CompactBitmapData` / `LargeBitmapData` | `Decoder.java` 解析 RFB 矩形 → `drawRect()` / `copyRect()` |
| RDP | `UltraCompactBitmapData` (`RemoteCanvas.reallocateDrawable:767`) | FreeRDP JNI 回调 `RdpCommunicator.OnGraphicsUpdate` (`RdpCommunicator.java:566`) |
| SPICE | `UltraCompactBitmapData` 或 `CompactBitmapData` | `SpiceCommunicator` 回调 |
| NVStream | `UltraCompactBitmapData` | `NvCommunicator` PixelCopy 回调 |
| SSH | `DoubleBufferBitmapData` (`RemoteCanvas.java:756`) | `SshTerminalRenderer.renderInto(DoubleBufferBitmapData)`(`bVNC/.../ssh/SshTerminalRenderer.java:223`)——渲染 libvterm cell grid 到 mbitmap |

### 6.2 BitmapData 子类选择(`RemoteCanvas.reallocateDrawable:730-790`)

```java
if (protocol == ProtocolType.SSH) {
    bitmapData = new DoubleBufferBitmapData(rfbconn, this, w, h, Bitmap.Config.ARGB_8888);  // :764
} else if (isPushlessProtocol || protocol == ProtocolType.SPICE) {
    bitmapData = new UltraCompactBitmapData(rfbconn, this, isUltraCompactProtocol);  // :767
} else if (largeScreen) {
    bitmapData = new LargeBitmapData(rfbconn, this, dx, dy, capacity);  // :770
} else if (mediumMem) {
    bitmapData = new FullBufferBitmapData(rfbconn, this, capacity);  // :777
} else {
    bitmapData = new CompactBitmapData(rfbconn, this, isSpice);  // :780
}
```

> `RemoteCanvas.java:756-765` 注释明确说明:"SSH paints whole frames from its own thread",因此用 `DoubleBufferBitmapData` 做前后台缓冲,避免撕裂。

### 6.3 软光标

`needsLocalCursor()`(`RemoteCanvas.java:812-820`)决定是否绘制本地软光标:
- RDP / SPICE:本地光标,除非 `CURSOR_FORCE_LOCAL` flag 关闭
- VNC:由服务器 `useLocalCursor` 协商决定
- SSH:**始终不绘制**(终端不需要本地软光标)

---

## 7. 输入处理流

```
Android KeyEvent / MotionEvent
        ↓
RemoteCanvasActivity.onKeyDown / onGenericMotionEvent
        ↓
   (1) GameGestures 拦截?(手柄 / 快捷键)
        ↓
   (2) currentInputHandler.onKeyDown / onPointerEvent  ← 策略模式(见 §5)
        ↓
   (3) translate to KeySym / codepoint + (x,y) + scroll delta
        ↓
   (4) keyboard.processLocalKeyEvent(...) / pointer.xxxButtonDown / scrollUp  ← 协议无关抽象
        ↓
   (5) 协议实现编码字节流
        ↓
   (6) 协议网络写出
```

### 7.1 InputHandler 策略选择

由用户偏好 + `Utils` 偏好共同决定:

| 策略 | 用途 |
|------|------|
| `InputHandlerDirectTouch` | 默认(触屏 → 鼠标) |
| `InputHandlerTouchpad` | 触屏当触摸板 |
| `InputHandlerGamepad` | 手柄模拟鼠标 |
| `DPadMouseKeyHandler` | 方向键模拟鼠标 |
| `InputHandlerDragPan` / `InputHandlerSwipePan` | 拖动 / 滑动平移 |

### 7.2 XKeySym / Unicode 管线(按协议分流)

| 协议 | KeySym 翻译 | Unicode 处理 | 主要调用点 |
|------|-------------|--------------|-----------|
| VNC | `com.qihua.tigervnc.rfb.UnicodeToKeysym.translate(char)` | 直接 keysym | `RemoteVncKeyboard.java:238,266,362` → `rfb.writeKeyEvent(keysym, meta, down)` |
| RDP | (无 keysym 翻译) | `RdpKeyboardMapper`(`remoteClientLib/.../RdpKeyboardMapper.java:458-510`),分两路:VK 或 unicode | `LibFreeRDP.sendKeyEvent / sendUnicodeKeyEvent` |
| SPICE | layout 表查找(`RemoteSpiceKeyboard.java:50-79`) | `unicode | UNICODE_MASK` 编码 | `SpiceCommunicator.sendSpiceKeyEvent` → JNI |
| NVStream | `Limelight KeyboardTranslator`(`RemoteNvStreamKeyboard.java:23-24`) | ACTION_MULTIPLE 走 `sendUtf8Text` | `MoonBridge.sendKeyboardInput / sendUtf8Text` |
| SSH | **不翻译** | codepoint 直接进 libvterm | `SshTermStateMachine.writeInput(cp, mods)` → `vterm_keyboard_unichar`(`vterm_jni.c:548`) |

### 7.3 SSH 键盘特殊路径

`RemoteSshKeyboard` 跳过 `XKeySymCoverter` 和 keysym 概念,直接把 codepoint 喂 libvterm:

- 特殊键(箭头 / F1-F12 / Home / End / PageUp / Down / Insert / Forward-Del):`androidKeyToVtermKey(int)`(`RemoteSshKeyboard.java:215-241`) → `termSession.writeKey(vkey, mods)` → JNI `nativeWriteKey` → `vterm_keyboard_key`
- 可打印字符:`evt.getUnicodeChar(meta)` → `termSession.writeInput(cp, mods)` → JNI `nativeWriteInput` → `vterm_keyboard_unichar`
- ENTER/DEL/TAB/ESCAPE:直接 `writeInput(0x0D/0x7F/0x09/0x1B)`(`RemoteSshKeyboard.java:117-128`)
- modifier 状态:`metaToVtermMods(int)` 翻译 `KeyEvent.META_*_MASK` → libvterm `MOD_SHIFT/MOD_ALT/MOD_CTRL`(`RemoteSshKeyboard.java:249-258`)

---

## 8. 连接初始化流程

### 8.1 分发

`Utils.getConnectionSetupClass(String type)`(`bVNC/.../Utils.java:372-385`):

```java
case "vnc":     -> ConfigVNC.class
case "rdp":     -> ConfigRDP.class
case "nvstream":-> ConfigNVStream.class
case "ssh":     -> ConfigSSH.class
default:        -> throws UnsupportedOperationException
```

`Utils.getConnectionTypeString(int type)`(`Utils.java:327-340`):

```java
case 0:  -> "rdp"     // CONN_TYPE_RDP
case 1:  -> "vnc"     // CONN_TYPE_VNC
case 2:  -> "nvstream"// CONN_TYPE_NVSTREAM
case 99: -> "ssh"     // CONN_TYPE_SSH
default: -> "unsupported"
```

### 8.2 工厂

`ConnectionInitializerFactory.create(conn, ctx)`(`bVNC/.../connection/ConnectionInitializerFactory.java:17-37`):

```java
if (Utils.isSpice(ctx) || Utils.isOpaque(ctx)) -> SpiceConnectionInitializer
else switch (conn.getConnectionType()) {
    CONN_TYPE_VNC      -> VncConnectionInitializer
    CONN_TYPE_RDP      -> RdpConnectionInitializer
    CONN_TYPE_NVSTREAM -> NvStreamConnectionInitializer
    CONN_TYPE_SSH      -> SshConnectionInitializer
}
```

### 8.3 5 个 Initializer 对照

| 协议 | 文件:行 | 行数 | build phase 创建 | start phase 触发 |
|------|---------|------|------------------|------------------|
| VNC | `VncConnectionInitializer.java:34` | 180 | `RfbCommunicator` + `RemoteVncKeyboard` + `RemoteVncPointer` + `Decoder` | `"VNC-Connect"` 线程:auth → `setPreferredFramebufferSize` → `decoder.setPixelFormat` → `rfb.processProtocol()` |
| RDP | `RdpConnectionInitializer.java:21` | 86 | `RdpCommunicator` + `RemoteRdpKeyboard` + `RemoteRdpPointer` | `rdpcomm.setConnectionParameters(...)` → `rdpcomm.connect()` |
| SPICE | `SpiceConnectionInitializer.java:53` | 442 | `SpiceCommunicator` + `RemoteSpiceKeyboard` + `RemoteSpicePointer` | 三分支:`startPve` / `startOvirt` / `startFromVvFile` |
| NVStream | `NvStreamConnectionInitializer.java:33` | 139 | `NvCommunicator` + `RemoteNvStreamKeyboard` + `RemoteNvStreamPointer` | `setConnectionParameters` → `connect(surfaceHolder)` + 注册 `ControllerHandler` |
| **SSH** | **`SshConnectionInitializer.java:60`** | **938** | `SshTerminalConnection` + `SshCommunicator` + `RemoteSshKeyboard` + `RemoteSshPointer` + `SshShellChannel` + `SshTerminalRenderer` + `SshTerminalScaling` | `openRenderer()` + `"SSH-Connect"` 线程(trilead 握手 + 认证 + `openShell`)+ `startHeartbeat`(200ms 心跳 repaint) |

SSH 的 Initializer 体量远大于其他 4 个之和(~70%),因为它要把所有渲染/缩放/字体/状态机的搭建工作都做掉——其他协议靠 native lib 完成,SSH 没有。

### 8.4 RemoteCanvas 字段注入

每个 Initializer 在 `initialize(canvas)` 阶段把以下字段注入到 canvas:

```
canvas.rfb / rdpcomm / spicecomm / nvcomm / sshComm  // Communicator
canvas.rfbconn                                        // 通用引用(VNC 时是 rfb,其他是 *Communicator)
canvas.pointer                                        // RemotePointer 实例
canvas.keyboard                                       // RemoteKeyboard 实例
```

`bitmapData` 的创建则发生在 `RemoteCanvas.reallocateDrawable(dx, dy)`(`RemoteCanvas.java:730-807`),由调用方在 size 变化时触发;`RemoteCanvas.java:756-790` 的协议分支选具体子类。

---

## 9. Native 编译架构

### 9.1 三个 native lib,三套构建系统

| Lib | 构建系统 | 入口脚本 | 输出路径 | 是否 AGP module |
|-----|----------|----------|----------|----------------|
| **FreeRDP** | CMake(经 `android-build-freerdp.sh` 包装) | `FreeRDP/scripts/android-build-freerdp.sh` | `FreeRDP/client/Android/Studio/freeRDPCore/src/main/jniLibs/arm64-v8a/`(`libfreerdp-android.so`、`libfreerdp2.so`、`libfreerdp-client2.so`、`libwinpr2.so`、`libssl.so`、`libcrypto.so`) | **是**(:remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore,com.android.library) |
| **Moonlight** | ndk-build(走 moonlight 自己的 `app/src/main/jni/Android.mk`) | `moonlight-android/app/src/main/jni/Android.mk`(单行 `include $(call all-subdir-makefiles)`) | `app/src/main/obj/local/arm64-v8a/libmoonlight-core.so` → 手动 `cp` 到 `remoteClientLib/src/main/jniLibs/arm64-v8a/` | **是**(:remoteClientLib:jni:libs:deps:moonlight-android:app,com.android.library) |
| **libvterm** | ndk-build(走项目 dispatcher) | `remoteClientLib/jni/Android.mk`(单 `include libs/vterm_jni/Android.mk`,**不**用 `all-subdir-makefiles`) | `remoteClientLib/libs/arm64-v8a/libvterm.so` → 手动 `cp` 到 `remoteClientLib/src/main/jniLibs/arm64-v8a/` | **否**(故意不是,见 §9.2) |

### 9.2 为什么 libvterm 不是 AGP module

libvterm 没有 Java API 表面——只是 C 源码,被项目的 ndk-build 编译,产物走 jniLibs 资源路径。如果以后 libvterm 需要暴露 Java API(例如加 OSC 52 helper),那时再提升为 module。

> 对应 `CLAUDE.md:23-46` 中的设计记录。

### 9.3 `build-deps.sh` 入口

`remoteClientLib/jni/libs/build-deps.sh`(759 行)接受:

```bash
./build-deps.sh build         # 依次跑 build_freerdp → build_moonlight → build_vterm
./build-deps.sh build_freerdp
./build-deps.sh build_moonlight
./build-deps.sh build_vterm
./build-deps.sh clean         # 清掉 sentinels 和 .so
./build-deps.sh sdist         # 打包源码
```

每个 `build_*` 函数都遵循同一模式:
1. 检查 sentinel `*_BUILT`,存在则早返回
2. `pushd deps && git clone URL`(若 .git 缺失)
3. `git fetch && git checkout BRANCH && git reset --hard`
4. 设置 NDK / cmake 环境
5. 调用对应构建系统(CMake 或 ndk-build)
6. `cp` 产物到 `remoteClientLib/src/main/jniLibs/arm64-v8a/`
7. `touch *`_BUILT` sentinel

### 9.4 Sentinel 缓存标记

| Sentinel | 路径 | gitignored? |
|----------|------|-------------|
| `FREERDP_BUILT` | `remoteClientLib/jni/libs/FREERDP_BUILT` | 是(`/.gitignore:24`) |
| `MOONLIGHT_BUILT` | `remoteClientLib/jni/libs/MOONLIGHT_BUILT` | 是(`remoteClientLib/.gitignore:12`) |
| `VTERM_BUILT` | `remoteClientLib/jni/libs/VTERM_BUILT` | 是(`remoteClientLib/.gitignore:13`) |

> 都是 0-byte 文件,作用等同于"已编译"标记,删除可强制重编。

### 9.5 NDK env 关键坑(`NDK_LIBS_OUT`)

`build_vterm` 必须 `unset NDK_LIBS_OUT`(`build-deps.sh:691`),然后 `cp` 到 jniLibs。原因:

- ndk-build 的 `setup-app.mk:104-106` 在每次 build 前 `rm -f ${NDK_LIBS_OUT}/<ABI>/*`,会清空整个输出目录
- `build_moonlight` 用 `NDK_LIBS_OUT=src/main/jniLibs`(`export` 是进程级)
- 如果 vterm 不显式 `unset`,会 inherit 这个变量,然后 `rm -f src/main/jniLibs/arm64-v8a/*` 把 moonlight-core.so 一起冲掉

修复:vterm `unset NDK_LIBS_OUT`,让 ndk-build 装到默认 `libs/<ABI>/`,build 完后 `cp` 到 `src/main/jniLibs/arm64-v8a/`(AGP 唯一打包路径,见 `remoteClientLib/.gitignore:11`)。

### 9.6 deps 目录不是 git submodule

`remoteClientLib/jni/libs/deps/` 整目录在 root `.gitignore:20` 里,**不是 git submodule**——是 `build-deps.sh` 在运行时 `git clone` 出来的。三个 git remote:

- FreeRDP: `https://github.com/seflerZ/FreeRDP.git`(fork),branch `work2`
- Moonlight: `https://github.com/seflerZ/moonlight-android.git`(fork),branch `work`,带子模块 `moonlight-common-c`
- libvterm: `git@github.com:seflerZ/libvterm.git`(forked from leonerd.org.uk 0.3.3),branch `main`(注意:**不要用 upstream master 10.x**——硬依赖 ncurses,Bionic 没装)

### 9.7 AGP 打包路径

- `remoteClientLib/build.gradle:32-37` 注释明确说**没有** `externalNativeBuild` 块,刻意避免 AGP 每次打包重跑 ndk-build
- AGP 的 library/application plugin 自动把 `src/main/jniLibs/<abi>/*.so` 当作打包资源源集合
- 当前 `remoteClientLib/src/main/jniLibs/arm64-v8a/` 实际内容(2026-08-20):

```
libc++_shared.so       1,794,776 bytes
libmoonlight-core.so   3,896,928 bytes
libvterm.so               69,072 bytes
libvterm_jni.so           17,696 bytes
```

- FreeRDP 的 6 个 `.so` **不在这里**——它们住在 `freeRDPCore/src/main/jniLibs/arm64-v8a/`,由 `freeRDPCore` 这个 AGP 模块打包,经 AAR 转给 `remoteClientLib` 再到 bVNC/aRDP-app
- `bVNC/build.gradle:73-74` 通过 `implementation project(...)` 把两个原生 AGP 模块拉进来

### 9.8 JNI 表面(本项目自研)

全项目**唯一一个**自研 JNI 文件:`remoteClientLib/jni/src/vterm_jni.c`(923 行)。

- 18 个 `JNIEXPORT` 函数,全部绑到 `com.qihua.bVNC.ssh.libvterm.SshTermStateMachine`
- 函数清单:`nativeCreate`、`nativeSetSize`、`nativeGetCols/Rows`、`nativeWrite`、`nativeWriteInput`、`nativeWriteKey`、`nativeDrainOutput`、`nativeIsSyncOutput`、`nativePollDirty`、`nativeTakeDirtyRows`、`nativeGetCell`、`nativeGetScrollbackCount`、`nativeGetScrollbackCell`、`nativeGetCursor`、`nativeDestroy`、`nativeSetDefaultColors`、`nativeSetPalette`
- 编译入口:`remoteClientLib/jni/libs/vterm_jni/Android.mk:58`(`LOCAL_SRC_FILES := ../../src/vterm_jni.c`)
- 由项目 dispatcher `remoteClientLib/jni/Android.mk` 在 build 时 `include libs/vterm_jni/Android.mk`

> SPICE、RDP、NVStream 的 JNI 都在 vendored 第三方库内,本项目不直接维护。

### 9.9 一次 FreeRDP build 的完整链路(端到端)

```
用户 ./remoteClientLib/jni/libs/build-deps.sh build_freerdp
  ↓
build-deps.sh:604 build_freerdp()
  ↓ FREERDP_BUILT 存在?→ 早返回
  ↓ pushd deps
  ↓ git clone https://github.com/seflerZ/FreeRDP.git (若缺)
  ↓ git checkout work2 + reset --hard
  ↓ sed scripts/android-build.conf (改 OpenSSL SHA + BUILD_ARCH=arm64-v8a)
  ↓ install_ndk / install_cmake (下载 NDK r27d + cmake 3.31.9,首次)
  ↓ ANDROID_NDK=... OPENH264_NDK=... CMAKE_PATH=... export
  ↓
deps/FreeRDP/scripts/android-build-freerdp.sh
  ↓ cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake
  ↓ cmake --build . --target install
  ↓ 装到 deps/FreeRDP/client/Android/Studio/freeRDPCore/src/main/jniLibs/arm64-v8a/
  ↓
touch FREERDP_BUILT
  ↓
AGP 编译 :remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore
  ↓ 标准 jniLibs source set 拾取 6 个 .so
  ↓ 打成 freeRDPCore.aar
  ↓
:remoteClientLib:implementation project(:...:freeRDPCore) 拉进 AAR
  ↓
:bVNC:implementation project(:remoteClientLib) 继承 AAR
  ↓
最终 APK lib/arm64-v8a/ 含 6 个 FreeRDP .so + 4 个 nativeClientLib 的 .so
```

---

## 10. SSH 现状(详细)

SSH 已经是个**完整的协议栈**——不是"半成品"。`project-structure.md` 第 11 节"SSH 半成品"的描述已过时,以下为准确状态。

### 10.1 文件全景

`bVNC/src/main/java/com/qihua/bVNC/ssh/` 共 8 个 Java 文件:

| 文件 | 行数 | 角色 |
|------|------|------|
| `SSHConnection.java` | 881 | **旧** VNC-over-SSH 隧道(自动 x11vnc / 端口转发)——目前**仍被 RemoteCanvas 用作 VNC-over-SSH 隧道**,与终端流程并存 |
| `SshTerminalConnection.java` | 235 | **Phase 3.1+** trilead `Connection` + `Session` 包装 |
| `SshShellChannel.java` | 273 | **Phase 2** 8 KB pipe pair,两个 pump 线程("SSH-Shell-ReadPump"/"SSH-Shell-WritePump") |
| `SshTerminalRenderer.java` | 425 | **Phase 3.7** libvterm 渲染宿主,启动 "SSH-VTerm-Reader" 线程 |
| `SshTerminalScaling.java` | 97 | 1:1 `AbstractScaling` 子类,IME push-up 复用 RDP 管线 |
| `TermFontFactory.java` | 92 | 加载 `assets/fonts/SarasaMonoSCNerd-Regular.ttf`(24 MB,CJK + Nerd PUA-A),回退 `Typeface.MONOSPACE` |

`bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/`:

| 文件 | 行数 | 角色 |
|------|------|------|
| `SshTermStateMachine.java` | 496 | **Java wrapper over JNI**,持有 `long nativeHandle`(`vterm_jni.c::nativeCreate`) |
| `VTermCanvasRenderer.java` | 472 | 把 cell 画到 `Canvas`(live rows + scrollback rows + cursor + selection 覆盖层) |

`bVNC/src/main/java/com/qihua/bVNC/connection/SshConnectionInitializer.java`(938 行)负责所有搭桥工作。

### 10.2 线程模型(共 4 个线程)

1. **SSH-Connect**(`SshConnectionInitializer.doConnect:436`):trilead 握手 + 认证 + `openShell(cols, rows)`
2. **SSH-Shell-ReadPump / SSH-Shell-WritePump**(`SshShellChannel.attach:134-140`):trilead `Session` ↔ pipe ↔ state machine
3. **SSH-VTerm-Reader**(`SshTerminalRenderer.open:190`):drain pipe → `stateMachine.write(bytes)`
4. **SSH-Paint `HandlerThread`**(`SshConnectionInitializer.ensurePaintThread:736`):`paintRunnable` 跑 `renderer.renderInto(mbitmap)` + `canvas.reDraw(...)`

外加一个 **200ms heartbeat**(`SshConnectionInitializer.startHeartbeat:415-422`)在主线程上 fallback repaint。

> libvterm 自身**不是线程安全**的。所有 JNI 调用都用 `synchronized(this)` 串行化(`SshTermStateMachine.java:64,74,108,125,165,189,232,263,302,359,378,389,396,404`)。

### 10.3 JNI 桥

`remoteClientLib/jni/src/vterm_jni.c`(923 行,18 个 JNIEXPORT)。Java 端通过 `System.loadLibrary("vterm")` + `System.loadLibrary("vterm_jni")` 显式加载(`SshTermStateMachine.java:32-34`)。

### 10.4 键盘 / 指针契约

- `RemoteSshKeyboard`(`bVNC/.../input/RemoteSshKeyboard.java:26`):
  - 不走 `XKeySymCoverter`
  - 特殊键 → `androidKeyToVtermKey(keyCode)`(`:215`)→ `writeKey(vkey, mods)` → `vterm_keyboard_key`
  - 可打印 → `evt.getUnicodeChar(meta)` → `writeInput(cp, mods)` → `vterm_keyboard_unichar`
  - `metaToVtermMods(int)`(`:249-258`)翻译 `META_*_MASK` → `MOD_SHIFT/MOD_ALT/MOD_CTRL`
- `RemoteSshPointer`(`bVNC/.../input/RemoteSshPointer.java:17`):
  - 所有鼠标按钮 + touch 处理都是 **no-op**(SSH 不需要鼠标点击)
  - 只有 `scrollUp/Down`(`:95,317`)实做:`termMachine.scrollByLines(+MOUSE_WHEEL_LINES)` 或 `scrollByPixels(...)`
  - 文字选区:`enterSelectionPx / extendSelectionPx / cancelSelection / selectAllVisible / consumeSelectedText`

### 10.5 trilead-ssh2 依赖

- `pubkeyGenerator/build.gradle:47`:`api 'org.connectbot:sshlib:2.2.20'`
- `bVNC/build.gradle:78-80`:`api 'org.connectbot:sshlib:2.2.20'`(显式拉过来)
- `pubkeyGenerator` 还拉 BouncyCastle(密钥格式转换)、`eddsa`(ed25519)等
- 所有 SSH 代码使用 `com.trilead.ssh2.*`(connectbot fork,包名保留)

### 10.6 已知未实现 / TODO

- 主机指纹校验(`SshTerminalConnection.java:136-143`):**首次连接信任任何 host key**——Phase 3.6+ 计划加,未做
- RDP-style gamepad:**没有 `SshRemoteGamepad`**(SSH 终端无 gamepad 语义)
- 单元测试:**零**——`bVNC/src/test/`、`bVNC/src/androidTest/`、`pubkeyGenerator/src/test/`、`remoteClientLib/src/test/` 全部空或不存在
- `AbstractConnectionBean.setConnectionType(99)` 在 default 分支里把 port 设成 3389(走 `DEFAULT_RDP_PORT` 分支)——SSH 用户需手动改回 22,`SshConnectionInitializer.initialize:301-302` 把 0 当 22,但保存的 3389 直接透传

---

## 11. RDP 音频链路(参考)

RDP 音频工作正常。**Java 端没有 `AudioTrack`**——整条链是 native OpenSL ES。

### 11.1 实际链路(6 步)

| # | 在哪发生 | 文件:行 |
|---|---|---|
| 1 | UI 开关 → bookmark | `AdvancedSettingsActivity.java:217` → `BookmarkBase.redirectSound` |
| 2 | Java 拼 argv → JNI | `LibFreeRDP.java:369-373` 拼 `/audio-mode:0 /sound:latency:85` → `android_freerdp.c:879-911` |
| 3 | cmdline 解析 → 置 `AudioPlayback=TRUE` | `cmdline.c:2484` + `cmdline.c:967` 注册 rdpsnd static channel |
| 4 | `freerdp_client_load_addins` 拉 opensles backend | `android_freerdp.c:310` → 生成的 `tables.c:117` 解析 `opensles_freerdp_rdpsnd_client_subsystem_entry` |
| 5 | 首帧 PCM 触发 OpenSL ES 引擎创建 | `rdpsnd_opensles.c:137` → `opensl_io.c:273` 创 SL engine + buffer-queue player |
| 6 | 每帧 PCM 入 buffer queue → HAL | `rdpsnd_opensles.c:258` → `opensl_io.c:357` `Enqueue(...)` → Android 扬声器 |

### 11.2 关键事实

- **Java 端从不需要 `AudioTrack`**——`RemoteCanvasActivity.java:862` 的 `setVolumeControlStream(STREAM_MUSIC)` 只路由硬件音量键,不是播放
- OpenSL ES API 是 `SLAndroidSimpleBufferQueueItf::Enqueue`(**不是 Java 的 `AudioTrack`**),全在 native 层
- FreeRDP 的 addin 系统是**链接时符号解析**自动拉的——生成的 `tables.c:95` 用 `extern UINT opensles_freerdp_rdpsnd_client_subsystem_entry(void*)` 强制 linker 把 opensles `.a` 拽进来,**不需要** `android_freerdp.c` 里手动 `rdpsnd_register`
- 反之,要让 `fake` backend 接管,需用户显式传 `/sound:sys:fake`;Java 默认只发 `/sound:latency:85`(空 subsystem 名 = 默认 opensles)

---

## 12. 构建命令

```bash
# 一键 native 依赖(必须先)
cd remoteClientLib/jni/libs && ./build-deps.sh build

# 主 APK
./gradlew :aRDP-app:assembleGplayRelease      # Google Play 版(主)
./gradlew :aRDP-app:assembleFreeRelease       # 社区版(EDGE_ENABLED 差异)
./gradlew :bVNC:assembleRelease               # bVNC 库 AAR
```

**构建参数**:
- Gradle 8.13.1
- AGP `com.android.tools.build:gradle:8.13.1`
- Kotlin 1.6.21
- Java 1.8
- NDK r27d(`build-deps.conf:5`)
- cmake 3.31.9(首次 build 自动下载)
- ABI arm64-v8a only
- minSdk 26 / targetSdk 35 / compileSdk 35

---

## 13. 新增协议的标准做法

### 13.1 5 维类继承

新增协议至少要做 5 件事:

1. **Communicator**(extends `RemoteConnectable` 或像 VNC 一样自研):实现 `framebufferWidth / Height / desktopName / requestUpdate / close`,把 `writeKeyEvent / writePointerEvent / writeClientCutText` 编码成协议字节流
2. **Keyboard**(extends `RemoteKeyboard`):实现 `processLocalKeyEvent`,把 `KeyEvent` 编码到 Communicator
3. **Pointer**(extends `RemotePointer`):实现 14 个抽象方法(鼠标 / 滚轮 / touch)
4. **BitmapData**(extends `AbstractBitmapData`):维护 `mbitmap`,实现 `updateBitmap / drawRect / copyRect / syncScroll` 等抽象方法
5. **Initializer**(extends `ConnectionInitializer`):在 `initialize(canvas)` 阶段创建上述 4 个实例 + Communicator,注入到 canvas 字段

可选第 6 项 **Gamepad**(extends `RemoteGamepad`),如果协议支持手柄。

### 13.2 集成步骤

1. `Constants.java` 加 `CONN_TYPE_X = <int>`(找一个未占用号,SSH 用 99)
2. `Utils.getConnectionSetupClass(String type)` 加 case(`Utils.java:372-385`)
3. `Utils.getConnectionTypeString(int type)` 加 case(`Utils.java:327-340`)
4. `ConnectionInitializerFactory.create` 加 case(`ConnectionInitializerFactory.java:17-37`)
5. `RemoteCanvas.reallocateDrawable` 加 `if (protocol == ProtocolType.X) bitmapData = new XBitmapData(...)` 分支(`RemoteCanvas.java:756-790`)
6. `RemoteCanvas.declareConnection`(实际分布在 Initializer.initialize)接 `getConnectionType() == CONN_TYPE_X`
7. `AbstractConnectionBean.setConnectionType(<int>)` 加 default port 分支(否则会落到 RDP 默认 3389)
8. 配置 Activity(extends `MainConfiguration`)— `ConfigXxx.java` + `R.layout.main_xxx`
9. `ConnectionGridActivity.connectionTypes` 数组加一项(`ConnectionGridActivity.java:422-425`)让用户在 picker 里能选
10. `bVNC/AndroidManifest.xml` 加 icon / Activity / 所需 permission

### 13.3 SSH 是个反例

SSH 不遵守 §13.1 的标准 5 维结构,因为它没有"协议位图流"——它把文本渲染成位图后,沿用其他协议的 `DoubleBufferBitmapData` 管线。具体简化:

- 第 1 步:**不实现独立 Communicator**——`SshCommunicator`(`bVNC/.../communicator/SshCommunicator.java:23`)是个 thin `RemoteConnectable` adapter,所有 `writeKeyEvent` / `writePointerEvent` 都是 no-op;真实 sink 是 libvterm `SshTermStateMachine`,由 `RemoteSshKeyboard.setTermSession(...)` 直接持有
- 第 4 步:不实现独立 `XBitmapData`——直接用 `DoubleBufferBitmapData`,由 `SshTerminalRenderer.renderInto(...)` 填充

如果以后做"第 6 个协议",先看它是不是"位图流协议"(模仿 VNC/RDP/SPICE/NVStream)还是"文本/终端协议"(模仿 SSH)。前者按 §13.1 标准做法,后者按 SSH 模板。

---

## 附录 A:关键文件路径速查

```
bVNC/src/main/java/com/qihua/bVNC/
  App.java
  ConfigVNC.java / ConfigRDP.java / ConfigNVStream.java / ConfigSSH.java
  RemoteCanvas.java (1824 行)
  RemoteCanvasActivity.java (2104 行)
  AbstractBitmapData.java
  AbstractBitmapDrawable.java
  FullBufferBitmapData.java / CompactBitmapData.java / LargeBitmapData.java
  UltraCompactBitmapData.java / DoubleBufferBitmapData.java
  communicator/
    RfbCommunicator.java (2212 行,自研 RFB)
  connection/
    ConnectionInitializer.java (抽象)
    ConnectionInitializerFactory.java
    VncConnectionInitializer.java / RdpConnectionInitializer.java
    SpiceConnectionInitializer.java / NvStreamConnectionInitializer.java
    SshConnectionInitializer.java (938 行)
    ProtocolType.java (enum)
  draw/
    DrawWorker.java (单一绘制点)
  input/
    InputHandler.java (interface)
    InputHandlerGeneric.java
    RemoteGamepad.java (抽象)
    RemoteVncKeyboard.java / RemoteVncPointer.java / VncRemoteGamepad.java
    RemoteRdpKeyboard.java / RemoteRdpPointer.java / RdpRemoteGamepad.java
    RemoteSpiceKeyboard.java / RemoteSpicePointer.java
    RemoteNvStreamKeyboard.java / RemoteNvStreamPointer.java / NvStreamRemoteGamepad.java
    RemoteSshKeyboard.java (259 行) / RemoteSshPointer.java (391 行)
  ssh/
    SSHConnection.java (881 行,旧 VNC-over-SSH 隧道)
    SshTerminalConnection.java (235 行)
    SshShellChannel.java (273 行)
    SshTerminalRenderer.java (425 行)
    SshTerminalScaling.java (97 行)
    TermFontFactory.java (92 行)
    libvterm/
      SshTermStateMachine.java (496 行)
      VTermCanvasRenderer.java (472 行)
      TermCell.java / CursorInfo.java

remoteClientLib/src/main/java/com/undatech/opaque/
  ConnectionGridActivity.java
  Viewable.java / RemoteConnectable.java
  RdpCommunicator.java / SpiceCommunicator.java / NvCommunicator.java
  DrawTask.java
  input/
    RemoteKeyboard.java (abstract,基类)
    RemotePointer.java (abstract,基类)
    RdpKeyboardMapper.java
    XKeySymCoverter.java

remoteClientLib/jni/
  Android.mk / Application.mk (dispatcher,只 include libs/vterm_jni/Android.mk)
  src/vterm_jni.c (923 行,项目唯一自研 JNI)
  libs/
    build-deps.sh (759 行,3 个 native lib 构建入口)
    build-deps.conf (URL / branch / NDK 版本)
    deps/ (gitignored,运行时 git clone)
      FreeRDP/ (fork seflerZ/FreeRDP,branch work2)
      moonlight-android/ (fork seflerZ/moonlight-android,branch work)
      libvterm/ (fork seflerZ/libvterm,branch main)
    vterm_jni/Android.mk

remoteClientLib/src/main/jniLibs/arm64-v8a/ (gitignored,构建产物)
  libvterm.so / libvterm_jni.so / libc++_shared.so / libmoonlight-core.so
```

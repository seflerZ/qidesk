# QiDesk 项目结构规格书

> 本文档面向"需要在该项目中新增一种协议 / 新增一类界面"的开发者,描述模块划分、类层级、统一的输入与绘制契约。
> 配套:SSH 实现规格书见 [`ssh-feature-spec.md`](./ssh-feature-spec.md)。

---

## 1. 项目定位

**QiDesk(奇花远程)** 是一个 Android 上的多协议远程客户端(代号"协议聚合"),基于 iiordanov 的 [aRDP](https://github.com/iiordanov/remote-desktop-clients) fork。

- **支持的协议**:VNC / RDP / SPICE / NVStream(Moonlight)
- **支持 SSH 是当前的扩展目标**(见 `ssh-feature-spec.md`)
- **代码总量**:核心 + UI ≈ 7 万行 Java + 少量 Kotlin
- **最低 SDK**:26,目标/编译 SDK:35

---

## 2. 模块布局(`settings.gradle`)

```
remote-desktop-clients/
├── aRDP-app/        # APK 壳:com.qihua.rmt(Google Play 版)
├── bVNC/            # APK 主体 + UI 库:com.qihua.bVNC  ← 主要工作都在这
├── remoteClientLib/ # 协议无关核心:com.undatech.opaque.*
├── pubkeyGenerator/ # SSH 公钥生成小工具(独立 lib)
├── common/          # 占位模块(目前只有 1 个 Utilities.kt,基本空)
├── native-clients-libs/ (隐式)
│   ├── remoteClientLib/jni/libs/deps/FreeRDP/.../freeRDPCore   ← RDP/SPICE JNI
│   └── remoteClientLib/jni/libs/deps/moonlight-android/app     ← NVStream JNI
└── specs/           # 规格书目录
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

bVNC ──→ pubkeyGenerator (公钥生成)
```

`common/` 目前没有实际依赖,只是占位。

---

## 3. 协议支持矩阵

| 协议 | 配置 Activity | 协议实现类 | 后端 |
|------|---------------|------------|------|
| VNC  | `ConfigVNC`   | `RfbProto`(纯 Java 自研)        | 自研 |
| RDP  | `ConfigRDP`   | `RdpCommunicator` 包装 FreeRDP   | JNI |
| SPICE| (复用 `ConfigRDP`,以 oVirt 模式) | `SpiceCommunicator` 包装 FreeRDP oVirt | JNI |
| NVStream | `ConfigNVStream` | `NvCommunicator` 包装 Moonlight | JNI |
| **SSH** | **`ConfigSSH`(待建)** | **走 Bitmap 渲染,无独立后端类** | **trilead-ssh2(JAR,已在 `SSHConnection.java` 中使用)** |

> SSH 是个**特例**:它没有自己的"协议位图流"——它把文本渲染成位图后,沿用其他协议的 `AbstractBitmapData` 管线。

---

## 4. 包结构

```
bVNC/src/main/java/
├── com.qihua.bVNC/                # VNC 协议核心 + App 入口
│   ├── App.java                   # Application
│   ├── ConfigVNC/RDP/NVStream     # 各协议配置页
│   ├── RemoteCanvas/RemoteCanvasActivity   # 核心 Activity
│   ├── RfbProto.java              # VNC 协议实现
│   ├── SSHConnection.java         # SSH 连接逻辑(trilead,753 行,半成品)
│   ├── AbstractBitmapData.java    # 协议位图抽象
│   ├── FullBufferBitmapData.java  #   - 全缓冲
│   ├── CompactBitmapData.java     #   - 紧凑缓冲
│   ├── LargeBitmapData.java       #   - 大屏分块
│   ├── UltraCompactBitmapData.java#   - 超紧凑(RDP/SPICE/NVStream)
│   ├── AbstractBitmapDrawable.java
│   └── input/                     # 输入处理(键盘/鼠标/手势)
│
└── com.undatech.opaque/           # 协议无关 UI(从 bVNC 抽出来的)
    ├── ConnectionGridActivity.java  # 连接列表 + 类型选择
    ├── Viewable.java                # 显示抽象接口
    ├── RfbConnectable.java          # 协议抽象基类
    ├── RdpCommunicator.java
    ├── SpiceCommunicator.java
    ├── NvCommunicator.java
    ├── DrawTask.java
    └── input/                     # 协议无关的键盘/鼠标抽象
        ├── RemoteKeyboard.java
        ├── RemoteKeyboardState.java
        ├── RemotePointer.java
        ├── RdpKeyboardMapper.java
        └── XKeySymCoverter.java

remoteClientLib/src/main/java/
└── com.undatech.opaque/           # 与 bVNC 镜像,被打成 remoteClientLib.jar
    ├── RdpCommunicator.java
    ├── SpiceCommunicator.java
    ├── NvCommunicator.java
    ├── Viewable.java
    ├── RfbConnectable.java
    ├── DrawTask.java
    └── util/                      # 通用工具
        ├── GeneralUtils.java
        ├── InputUtils.java
        ├── OnTouchViewMover.java
        ├── RemoteToolbar.java
        └── UsbDeviceManager.kt
```

> **注意**:`com.undatech.opaque` 这个包**同时存在两份**——一份在 `bVNC/`,一份在 `remoteClientLib/`,后者被打成 jar,通过 `implementation files('libs/...jar')` 注入到 bVNC。这是个历史包袱,新增代码应避免这种分叉。

---

## 5. 类层级(四层架构)

```
第 1 层:系统事件
  Activity  (RemoteCanvasActivity, 2104 行)
    implements OnKeyListener, OnGenericMotionListener, GameGestures
    ↓ 收 Android KeyEvent / MotionEvent
第 2 层:模式选择 (策略模式)
  InputHandler (interface)
    ↑
    InputHandlerGeneric (abstract)
      ↑ DirectTouch | Touchpad | Gamepad | DPadMouse | DragPan | SwipePan
    ↓ 翻译成 X11 KeySym + 坐标
第 3 层:协议无关抽象
  RemoteKeyboard (abstract)        RemotePointer (abstract)
    ↑ sendKeyEvent(keySym, down)     ↑ sendPointerEvent(x, y, metaState, button)
    ↑ sendMetaKey(...)               ↑ sendScrollEvent(...)
      ↑ Vnc | Rdp | Spice | Nv         ↑ Vnc | Rdp | Spice | Nv
第 4 层:协议实现
  RfbProto (VNC) | RdpCommunicator | SpiceCommunicator | NvCommunicator
  ↑ 各自把事件编码成协议字节流
```

**关键类文件**:
- `InputHandler.java`(`bVNC/input/InputHandler.java:26`):6 个方法接口
- `InputHandlerGeneric.java`(`bVNC/input/InputHandlerGeneric.java:50`):抽象基类
- `RemoteKeyboard.java`(`remoteClientLib/input/RemoteKeyboard.java:32`):协议无关键盘,定义 X11 KeySym 常量
- `RemotePointer.java`(`remoteClientLib/input/RemotePointer.java:25`):协议无关鼠标
- `Viewable.java`(`remoteClientLib/Viewable.java:5`):显示接口

---

## 6. 统一绘制点(★ 核心架构契约)

**所有协议最终在 `RemoteCanvas.DrawWorker.run()` 第 1903 行收口**:

```java
// bVNC/.../RemoteCanvas.java:1897-1919
canvas = surfaceHolder.lockHardwareCanvas();
canvas.setMatrix(scaler.getMatrix());
canvas.translate((-absoluteXPosition), (-absoluteYPosition));
canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
bitmapData.drawable.draw(canvas);                    // ← 唯一绘制点
// ... FPS 调试信息
surfaceHolder.unlockCanvasAndPost(canvas);
```

`AbstractBitmapDrawable.draw(canvas, xoff, yoff)`(`AbstractBitmapDrawable.java:67-76`)只做两件事:

```java
synchronized (this) {
    canvas.drawBitmap(data.mbitmap, xoff, yoff, _defaultPaint);  // 协议位图
    canvas.drawBitmap(softCursor, cursorRect.left, cursorRect.top, _defaultPaint);  // 软光标
}
```

### 6.1 契约

> **任何协议,只要"维护一个 `Bitmap mbitmap`",就能被绘制。**

| 协议 | `AbstractBitmapData` 子类 | 怎么填 mbitmap |
|------|---------------------------|----------------|
| VNC | `FullBufferBitmapData` / `CompactBitmapData` / `LargeBitmapData` | `Decoder.java:197/217/247/...` 解析 RFB 矩形 → `bitmapData.drawRect()` / `copyRect()` |
| RDP | `UltraCompactBitmapData`(`RemoteCanvas.java:1335`) | `RdpCommunicator`(FreeRDP JNI)回调 |
| SPICE | `UltraCompactBitmapData` / `CompactBitmapData` | `SpiceCommunicator` 回调 |
| NVStream | `UltraCompactBitmapData` | `NvCommunicator`(Moonlight JNI)回调 |

### 6.2 子类选择

`RemoteCanvas.java:1330-1368` 按 `isSpice / isRdp / isNvStream` 选 `bitmapData` 实例:

```java
if (isSpice | isOpaque | isRdp | isNvStream) {
    bitmapData = new UltraCompactBitmapData(rfbconn, this, ...);
} else if (largeScreen) {
    bitmapData = new LargeBitmapData(rfbconn, this, ...);
} else if (mediumMem) {
    bitmapData = new FullBufferBitmapData(rfbconn, this, capacity);
} else {
    bitmapData = new CompactBitmapData(rfbconn, this, isSpice | isOpaque);
}
```

**新增协议的唯一改动点**就是这里加一个 `if (isSsh) bitmapData = new SshBitmapData(...)`。

### 6.3 `RfbConnectable` 子类

`RfbConnectable`(`remoteClientLib/RfbConnectable.java:31`)是协议逻辑层基类,定义:
- `framebufferWidth()` / `framebufferHeight()` / `desktopName()`
- `requestUpdate(boolean incremental)` — 主动请求帧
- `writeFramebufferUpdateRequest(...)` — 主动拉帧
- 持有 `RemoteKeyboardState`(modifier 状态)

**协议实现类继承关系**:
- `RdpCommunicator extends RfbConnectable implements RdpKeyboardMapper.KeyProcessingListener`
- `SpiceCommunicator extends RfbConnectable`
- `NvCommunicator extends RfbConnectable implements NvConnectionListener, PerfOverlayListener`
- VNC 例外:`RfbProto` 是 bVNC 自己的类,没有继承 `RfbConnectable`,但 `RemoteCanvas.rfbconn` 字段同时支持两者(因为是 `Object` 类型弱耦合)

---

## 7. 输入处理流(键盘 / 鼠标 / 手柄)

```
Android KeyEvent / MotionEvent
        ↓
RemoteCanvasActivity.onKeyDown / onGenericMotionEvent
        ↓
   (1) GameGestures 拦截? (手柄/快捷键)
        ↓
   (2) currentInputHandler.onKeyDown(keyCode, event)  ← 策略模式
        ↓
   (3) XKeySymCoverter 翻译成 X11 KeySym
        ↓
   (4) keyboard.sendKeyEvent(scanCode, metaState, down)  ← RemoteKeyboard 抽象
        ↓
   (5) 协议实现编码字节流
        ↓
   (协议网络写出)
```

**InputHandler 策略选择**(`Utils` 偏好 + 用户偏好):
- `InputHandlerDirectTouch` — 默认(触屏 → 鼠标)
- `InputHandlerTouchpad` — 触屏当触摸板
- `InputHandlerGamepad` — 手柄模拟鼠标
- `DPadMouseKeyHandler` — 方向键模拟鼠标

**键盘 modifier 状态**:`RemoteKeyboardState`(`remoteClientLib/input/`)维护 CapsLock / NumLock / ScrollLock + Ctrl/Shift/Alt/Meta 组合状态。

---

## 8. 配置与路由流程

```
App 启动
  ↓
ConnectionGridActivity(连接列表 + 类型选择)
  ↓ addNewConnection("vnc"|"rdp"|"nvstream")
Utils.getConnectionSetupClass(type)
  ↓ 返回 Class<?>
ConfigVNC / ConfigRDP / ConfigNVStream
  ↓ 用户填表保存,跳转到 RemoteCanvasActivity
RemoteCanvasActivity
  ↓
RemoteCanvas.declareConnection()  ← 按 connectionType 分流
  ↓
具体协议 setup(VNC/RDP/SPICE/NVStream)
  ↓
连接建立 → DrawWorker 开始绘制
```

**关键文件**:
- `Utils.getConnectionSetupClass()`(`Utils.java:370`):switch 分发
- `Utils.getConnectionTypeString()`(`Utils.java:327`):int → "vnc"/"rdp"/...
- `ConnectionGridActivity.addNewConnection()`(`ConnectionGridActivity.java:464`):入口
- `ConnectionGridActivity.displayConnectionTypes()`(`ConnectionGridActivity.java:420`):类型选择对话框

**当前的 SSH guard**(`ConnectionGridActivity.java:464-467`):
```java
public void addNewConnection(String type) {
    if (type.equals("ssh")) {
        return;   // ← 占位,SSH 实现后删除
    }
    ...
}
```

---

## 9. 关键工具类

| 类 | 位置 | 用途 |
|----|------|------|
| `Utils` | `bVNC/.../Utils.java` | 字符串 / 偏好 / 包名判定 / 分发 |
| `Constants` | `bVNC/.../Constants.java` | 协议端口、颜色模式常量 |
| `Database` | `bVNC/.../Database.java` | SQLCipher 加密连接数据库 |
| `ConnectionBean` | `bVNC/.../ConnectionBean.java` | 单条连接数据 |
| `NewConnection` | `bVNC/.../NewConnection.java` | 新建连接工具 |
| `MainConfiguration` | `bVNC/.../MainConfiguration.java` | 配置页基类 |
| `AbstractScaling` 子类 | `bVNC/.../AbstractScaling.java` | 缩放策略(FitToScreen / OneToOne / Zoom) |
| `Panner` | `bVNC/.../input/Panner.java` | 平移 |
| `MyGestureDectector` | `bVNC/.../input/MyGestureDectector.java` | 手势识别 |
| `MyScaleGestureDetector` | `bVNC/.../input/MyScaleGestureDetector.java` | 缩放手势 |

---

## 10. 构建系统

- **Gradle**:8.13.1
- **AGP**:`com.android.tools.build:gradle:8.13.1`
- **Kotlin**:1.6.21
- **Java**:1.8
- **Flavor**:`gplay` / `free`(主要差异:`BuildConfig.EDGE_ENABLED`)
- **签名**:`aRDP-app/build.gradle` 直接硬编码 keystore + 密码
- **NDK**:arm64-v8a only
- **minSdk** 26 / **targetSdk** 35 / **compileSdk** 35

**构建命令**:
```bash
./gradlew :aRDP-app:assembleGplayRelease      # 主 APK
./gradlew :bVNC:assembleRelease               # bVNC 库 AAR
```

---

## 11. 现有 SSH 半成品的位置

`bVNC/src/main/java/com/qihua/bVNC/SSHConnection.java`(753 行)存在但**未跑通**,具体状态:

- ✅ 已实现 trilead-ssh2 连接、密码 / 密钥认证、端口跳转、KnownHosts
- ✅ 已实现 `InteractiveCallback` 交互式认证
- ✅ 已有 `Session.getStdout() / getStdin()` 字节流出口
- ❌ **没有调用方**——没有 `SshConnectable`、没有 `SshBitmapData`、没有 `RemoteSshKeyboard/Pointer`、没有 `SshTerminalActivity`、没有 `ConfigSSH`
- ❌ `Utils.getConnectionSetupClass("ssh")` 抛 `UnsupportedOperationException`
- ❌ `ConnectionGridActivity.addNewConnection("ssh")` 被 guard 拦截
- ❌ trilead-ssh2 库本身没在 `bVNC/build.gradle` 中声明

**结论**:SSH 的"协议层"约 70% 已就绪,缺的是"与项目架构对接"的桥接代码 + 终端 UI。

---

## 12. 新增协议的标准做法(以 SSH 为例)

按照本项目的架构,新增一种协议的标准动作:

1. **协议逻辑类**:继承 `RfbConnectable`(或像 VNC 一样不继承,自行实现),实现 `framebufferWidth/Height/desktopName/requestUpdate`
2. **位图数据类**:继承 `AbstractBitmapData`,实现 `updateBitmap(x,y,w,h)`、`copyRect`、`drawRect`,在内部维护 `mbitmap`
3. **键盘类**:继承 `RemoteKeyboard`,重写 `sendKeyEvent`
4. **鼠标类**:继承 `RemotePointer`,重写 `sendPointerEvent / sendScrollEvent`
5. **配置页**:继承 `MainConfiguration`,字段为该协议特有
6. **分发注册**:
   - `Utils.getConnectionSetupClass()` 加 case
   - `Utils.getConnectionTypeString()` 加分支
   - `RemoteCanvas.declareConnection()` 加 if 分支,创建具体类 + 选 `bitmapData` 子类
7. **AndroidManifest**:`aRDP-app/AndroidManifest.xml` + `bVNC/AndroidManifest.xml` 注册 Activity
8. **资源**:图标、字符串(`description_*`)、连接类型列表

> SSH 是个特例:第 1 步不需要独立类(`RfbConnectable` 的协议层可由 `TermSession` 状态承担),其余标准动作照做。

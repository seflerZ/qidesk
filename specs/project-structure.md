# QxRemotes 项目结构规格书

> 本文档面向"需要在该项目中新增一种协议 / 新增一类界面"的开发者,描述模块划分、类层级、统一的输入与绘制契约。
> 配套:SSH 实现规格书见 [`ssh-feature-spec.md`](./ssh-feature-spec.md)。

---

## 1. 项目定位

**QxRemotes(奇花远程)** 是一个 Android 上的多协议远程客户端(代号"协议聚合"),基于 iiordanov 的 [aRDP](https://github.com/iiordanov/remote-desktop-clients) fork。

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
| VNC  | `ConfigVNC`   | `RfbCommunicator`(纯 Java 自研 RFB)        | 自研 |
| RDP  | `ConfigRDP`   | `RdpCommunicator` 包装 FreeRDP   | JNI |
| SPICE| (复用 `ConfigRDP`,以 oVirt 模式) | `SpiceCommunicator` 包装 FreeRDP oVirt | JNI |
| NVStream | `ConfigNVStream` | `NvCommunicator` 包装 Moonlight | JNI |
| **SSH** | **`ConfigSSH`** | **`SshCommunicator`(薄 adapter)+ `SshTerminalRenderer` 渲染 libvterm cells** | **libvterm + trilead-ssh2 2.2.20** |

> SSH 是个**特例**:它没有"协议位图流"——它把 libvterm cell grid 直接渲染成位图后,沿用通用 `DoubleBufferBitmapData` 管线;`SshCommunicator` 所有 `write*` 方法都是 no-op,真实 sink 是 libvterm `SshTermStateMachine`。详见 §11 和 [`architecture.md` §10](./architecture.md#10-ssh-现状详细)。

---

## 4. 包结构

```
bVNC/src/main/java/
├── com.qihua.bVNC/                # VNC 协议核心 + App 入口
│   ├── App.java                   # Application
│   ├── ConfigVNC/RDP/NVStream     # 各协议配置页
│   ├── RemoteCanvas/RemoteCanvasActivity   # 核心 Activity
│   ├── RfbProto.java              # VNC 协议实现
│   ├── SSHConnection.java         # VNC-over-SSH 隧道(881 行,旧 SSHConnection)
│   ├── ssh/                        # SSH 终端(完整 5 维协议栈,详见 §11)
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

## 11. SSH 现状(已完工,Phase 3.7+)

> **2026-08 更新**:本文档早期版本把 SSH 描述为"半成品",**已过时**。SSH 现在是个完整的协议栈——5 维类继承齐全,UI picker 里可选,`Utils.getConnectionSetupClass("ssh")` 返回 `ConfigSSH.class` 不再抛异常。

`bVNC/src/main/java/com/qihua/bVNC/ssh/` 共 6 个 Java 文件 + 1 个 `libvterm/` 子包:

| 文件 | 行数 | 角色 |
|------|------|------|
| `SSHConnection.java` | 881 | **旧** VNC-over-SSH 隧道(自动 x11vnc / 端口转发),仍被 RemoteCanvas 用作 VNC-over-SSH 隧道——与终端流程**并存** |
| `SshTerminalConnection.java` | 235 | Phase 3.1+:trilead `Connection` + `Session` 包装 |
| `SshShellChannel.java` | 273 | 8 KB pipe pair,两个 pump 线程("SSH-Shell-ReadPump"/"SSH-Shell-WritePump") |
| `SshTerminalRenderer.java` | 425 | Phase 3.7:libvterm 渲染宿主,启动 "SSH-VTerm-Reader" 线程 |
| `SshTerminalScaling.java` | 97 | 1:1 `AbstractScaling` 子类,IME push-up 复用 RDP 管线 |
| `TermFontFactory.java` | 92 | 加载 `assets/fonts/SarasaMonoSCNerd-Regular.ttf`(24 MB,CJK + Nerd PUA-A) |

`bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/`:

| 文件 | 行数 | 角色 |
|------|------|------|
| `SshTermStateMachine.java` | 496 | Java wrapper over JNI(18 个 JNIEXPORT,全部绑到本类) |
| `VTermCanvasRenderer.java` | 472 | cell → Canvas 绘制(live rows + scrollback rows + cursor + selection 覆盖层) |
| `TermCell.java` / `CursorInfo.java` | — | 渲染数据结构 |

**搭桥层**:`bVNC/.../connection/SshConnectionInitializer.java`(938 行,SSH 5 维继承的胶水)。

**已知未实现 / TODO**:
- 主机指纹校验(`SshTerminalConnection.java:136-143`):首次连接**信任任何 host key**——Phase 3.6+ 计划加,未做
- RDP-style gamepad:没有 `SshRemoteGamepad`(SSH 终端无 gamepad 语义)
- 单元测试:**零**——`bVNC/src/test/`、`bVNC/src/androidTest/` 全部空或不存在
- `AbstractConnectionBean.setConnectionType(99)` 在 default 分支里把 port 设成 3389——SSH 用户需手动改回 22

**完整架构见 [`architecture.md` §10](./architecture.md#10-ssh-现状详细)**。

---

## 12. 新增协议的标准做法

按照本项目的架构,新增一种**位图流协议**(模仿 VNC / RDP / SPICE / NVStream)的标准动作:

1. **协议逻辑类**:继承 `RemoteConnectable`(VNC 例外,直接继承 `RfbCommunicator` 自研),实现 `framebufferWidth / Height / desktopName / requestUpdate / close`
2. **位图数据类**:继承 `AbstractBitmapData`,实现 `updateBitmap(x,y,w,h)`、`copyRect`、`drawRect`,在内部维护 `mbitmap`
3. **键盘类**:继承 `RemoteKeyboard`,重写 `processLocalKeyEvent`
4. **鼠标类**:继承 `RemotePointer`,重写 `leftButtonDown / moveMouse / scrollUp / scrollDown / touchDown / touchUpdate` 等
5. **(可选)Gamepad**:继承 `RemoteGamepad`
6. **配置页**:继承 `MainConfiguration`,字段为该协议特有
7. **Initializer**:继承 `ConnectionInitializer`,在 `initialize(canvas)` 里把上述实例注入 canvas 字段
8. **分发注册**:
   - `Constants.java` 加 `CONN_TYPE_X = <int>`(SSH 已占 99)
   - `Utils.getConnectionSetupClass(String type)` 加 case(`Utils.java:372-385`)
   - `Utils.getConnectionTypeString(int type)` 加 case(`Utils.java:327-340`)
   - `ConnectionInitializerFactory.create` 加 case(`ConnectionInitializerFactory.java:17-37`)
   - `RemoteCanvas.reallocateDrawable` 加 `if (protocol == ProtocolType.X) bitmapData = new XBitmapData(...)` 分支(`RemoteCanvas.java:756-790`)
   - `AbstractConnectionBean.setConnectionType(<int>)` 加 default port 分支(否则会落到 RDP 默认 3389)
   - `ConnectionGridActivity.connectionTypes` 数组加一项(`ConnectionGridActivity.java:422-425`)让用户在 picker 里能选
9. **AndroidManifest**:`aRDP-app/AndroidManifest.xml` + `bVNC/AndroidManifest.xml` 注册 Activity / 权限
10. **资源**:图标、字符串(`description_*`)
11. **(可选)Native lib**:如果要借力现有 FreeRDP / Moonlight 模块,加 `implementation project(...)`;新增原生库则要扩 `build-deps.sh` + `settings.gradle`

> **SSH 不适用于本模板**——SSH 是"文本/终端协议"而非"位图流协议",它把 libvterm cell grid 直接渲染成位图后,沿用通用 `DoubleBufferBitmapData` 管线。SSH 不是"待完成的新协议",而是已完工的特例(见 §11)。**第 6 个协议若属位图流,按本节;若属终端/文本流,按 SSH 模板(见 [`architecture.md` §13.3](./architecture.md#133-ssh-是个反例))**。

**完整架构与示例见 [`architecture.md`](./architecture.md)**。

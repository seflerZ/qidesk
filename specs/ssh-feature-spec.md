# SSH 终端功能实现规格书

## 基本信息

| 项目 | 内容 |
|------|------|
| 功能名称 | SSH Terminal(纯文字终端) |
| 所属项目 | qidesk(奇花远程桌面) |
| 实现方式 | trilead-ssh2 + 自研 Bitmap 渲染 + 复用项目核心管线 |
| 当前状态 | Phase 0(最小骨架) |
| 关联文档 | [`project-structure.md`](./project-structure.md) |

---

## 0. 背景(更新)

奇花远程桌面已实现四种协议(VNC / RDP / SPICE / NVStream),它们的**显示**最终都汇聚到 `RemoteCanvas.DrawWorker.run()`(`RemoteCanvas.java:1903` 的 `bitmapData.drawable.draw(canvas)`),**输入**汇聚到 `InputHandler*` 策略链。

> 项目结构、类层级、绘制契约、输入链路的完整说明见 [`project-structure.md`](./project-structure.md)。
> 本文档不再重复上述内容,只描述 SSH 的差异化部分。

### 0.1 关键架构决策(★ 修订)

SSH 不同于其他协议的地方:它的**输出是文本,不是位图**。但本项目所有协议最终都"位图化"再绘制(`AbstractBitmapDrawable.draw` 只调 `canvas.drawBitmap(mbitmap, ...)`),所以:

> **SSH 终端不绕过项目核心管线,而是"把文本渲染到 mbitmap",然后走和其他协议完全一样的绘制路径。**

具体做法:
- 让 `TermSession`(来自 Android-Terminal-Emulator)做 VT100 状态机(持字符网格 / 光标 / 颜色)
- 新增 `SshBitmapData extends AbstractBitmapData`,在 `updateBitmap` 中把 `TermSession` 当前状态用 `Canvas.drawText` 画到 `mbitmap`
- `DrawWorker` 不需要任何改动,直接复用

这样 SSH 能**免费继承**:
- 缩放 / 平移 / 手势 / 缩放手势(`AbstractScaling` / `Panner`)
- 截图 / FPS 调试(`FpsCounter` 已有)
- 外接键鼠 / 手柄(`InputHandlerGamepad` 等)
- 软光标 / 剪贴板(`setSoftCursor` 等)

### 0.2 SSH 库选型(★ 修订)

**采用 trilead-ssh2**(已存在于 `SSHConnection.java`):

```gradle
implementation 'com.trilead:trilead-ssh2:1.2.0'
// 或:implementation files('libs/trilead-ssh2-1.2.0.jar')
```

> 原规格书指定的 Apache MINA sshd **不再使用**。理由:`SSHConnection.java`(753 行)已基于 trilead 实现了完整的连接 / 认证 / 端口跳转 / KnownHosts / InteractiveCallback 逻辑,改用 MINA 等于把这部分全部作废。沿用 trilead 可节省 60% 的协议层工作量。
> MINA 的优势(更新活跃、对现代 SSH 特性支持好)对本项目影响有限,因为我们只需要密码 / 密钥认证 + 一个 shell channel。

---

## 1. 对接架构(★ 修订)

```
              ┌────────────────────────────────────────────────────┐
              │              RemoteCanvasActivity                  │
              │  (复用,零改动)                                       │
              │                                                    │
              │  键盘 onKeyDown ──→  RemoteSshKeyboard.sendKeyEvent  │
              │                      (新增)                         │
              │                          │                          │
              │                          ▼                          │
              │                  TermSession.write(ansi_byte)        │
              │                  (VT100 状态机)                       │
              │                          │                          │
              │                          ▼                          │
              │                  SSHConnection.Session              │
              │                  (trilead-ssh2,已有)                  │
              │                  getStdin()       getStdout()       │
              │                       │             ▲               │
              └───────────────────────┼─────────────┼───────────────┘
                                      │ 网络字节流   │ 字节流
                                      ▼             │
                                  [ SSH Server ]    │
                                                    │
              ┌─────────────────────────────────────┼───────────────┐
              │  TermSession.getTermOut()  ←────────┘               │
              │  ↑ 状态变化(字符 / 颜色 / 光标)                       │
              │                                                     │
              │  SshBitmapData.updateBitmap(x, y, w, h)              │
              │   (新增) 读 TermSession → Canvas.drawText 写到 mbitmap│
              │   ↓                                                 │
              │  mbitmap (Bitmap) ── 与 VNC/RDP/SPICE/NVStream 同一  │
              │                       张 mbitmap,共用 DrawWorker    │
              └─────────────────────────────────────────────────────┘
```

**绘制链路**(零改动):
```
mbitmap
  → AbstractBitmapDrawable.draw(canvas)   [AbstractBitmapDrawable.java:67]
  → canvas.drawBitmap(mbitmap, ...)        [AbstractBitmapDrawable.java:71]
  → DrawWorker.run() 主循环                [RemoteCanvas.java:1871]
  → SurfaceView 屏幕呈现
```

**关键点**:`EmulatorView`(来自 Android-Terminal-Emulator)是一个自定义 `View` 组件——**我们不用它**。`TermSession` 才是与 View 无关的纯逻辑层。SSH 的"绘制"完全由 `SshBitmapData` 自己用 Android `Canvas.drawText` 完成,确保与项目其他协议共用一张 mbitmap 和同一个 DrawWorker。

---

## 2. 文件结构(★ 修订)

```
remote-desktop-clients/
├── specs/
│   ├── project-structure.md           # 项目结构(新建,见 §0)
│   └── ssh-feature-spec.md            # 本文件
│
├── bVNC/libs/                         # 新增
│   ├── emulatorview-release.aar       # Android-Terminal-Emulator 库(已编译在 ~/Android-Terminal-Emulator/...)
│   └── trilead-ssh2-1.2.0.jar         # SSH 客户端库(需下载)
│
├── bVNC/src/main/java/com/qihua/bVNC/
│   ├── SSHConnection.java             # 已有(753 行,trilead),无需改动
│   ├── ssh/
│   │   ├── SshConnectable.java        # 新增:extends RfbConnectable,负责 framebuffer 尺寸、SSH 启停
│   │   ├── SshBitmapData.java         # 新增:extends AbstractBitmapData,把 TermSession 渲染到 mbitmap
│   │   ├── SshTerminalRenderer.java   # 新增:把 TermSession 状态画到 Canvas(纯函数,SshBitmapData 内部调用)
│   │   ├── RemoteSshKeyboard.java     # 新增:extends RemoteKeyboard,KeyEvent → ANSI 字节 → TermSession
│   │   ├── RemoteSshPointer.java      # 新增:extends RemotePointer,no-op 或文本选择
│   │   ├── ConfigSSH.java             # 新增:extends MainConfiguration,SSH 配置页
│   │   └── SshTerminalActivity.java   # 新增:extends RemoteCanvasActivity(主要逻辑复用),少量 SSH 特殊处理
│   └── ... (现有 VNC / RDP / SPICE / NVStream 代码不动)
│
├── bVNC/src/main/res/layout/
│   ├── config_ssh.xml                 # 新增:SSH 配置页布局
│   └── ssh_terminal.xml               # 新增:终端 Activity 布局(其实可仅用 canvas_full.xml,按需)
│
├── bVNC/src/main/res/menu/
│   └── ssh_terminal_menu.xml          # 新增:终端菜单(重连、断开)
│
├── aRDP-app/src/main/AndroidManifest.xml    # 新增:注册 ConfigSSH、SshTerminalActivity
└── bVNC/src/main/AndroidManifest.xml        # 新增:同上
```

**复用 vs 新增**:
| 复用(零改动) | 新增 |
|---------------|------|
| `Viewable` 接口 | `SshConnectable` |
| `RfbConnectable` 基类 | `SshBitmapData` |
| `AbstractBitmapData` 抽象类 | `SshTerminalRenderer` |
| `AbstractBitmapDrawable` | `RemoteSshKeyboard` |
| `RemoteCanvas` | `RemoteSshPointer` |
| `RemoteCanvasActivity`(基类) | `ConfigSSH` |
| `DrawWorker`(主绘制循环) | `SshTerminalActivity` |
| `InputHandler*` 全套 | `config_ssh.xml` / `ssh_terminal.xml` |
| `Utils`(只加 1 行 case) | 2 个 AndroidManifest 改动 |
| `ConnectionGridActivity`(只删 3 行 guard) |  |

---

## 3. 实现任务清单(★ 重组:分 3 阶段,先骨架后功能)

### Phase 0 — 最小可跑通骨架(★ 优先)

**目标**:用硬编码字符串渲染到 mbitmap,跑通 `RemoteCanvas` 绘制链路,屏幕显示"Hello SSH"。
这一步**不依赖** trilead、TermSession、AAR,只验证架构契约是否真的成立。

- [ ] 新建 `SshConnectable extends RfbConnectable`,固定返回 framebuffer 尺寸 1280×720,desktopName = "SSH"
- [ ] 新建 `SshBitmapData extends AbstractBitmapData`,在 `updateBitmap` 中调用 `Canvas.drawText("Hello SSH", ...)`
- [ ] 在 `RemoteCanvas.declareConnection()`(行 1330-1368)加 `if (isSsh) bitmapData = new SshBitmapData(...)`
- [ ] 临时在 `ConnectionGridActivity.addNewConnection` 删掉 SSH guard(测试用)
- [ ] 临时在 `Utils.getConnectionSetupClass("ssh")` 返回 `RemoteCanvasActivity.class`(绕开 ConfigSSH,后续再补)
- [ ] `adb` 跑通后,屏幕上应看到"Hello SSH"

**Phase 0 完成 = 架构可行性验证**。如果 mbitmap 上的字符能跟着 `DrawWorker` 正确绘制到屏幕,后续 1/2 阶段就是把"硬编码字符串"换成"动态 TermSession 状态"。

### Phase 1 — 接入 TermSession(本地伪终端)

**目标**:引入 Android-Terminal-Emulator 的 `TermSession`,把硬编码字符串换成"TermSession 内存中的状态",并接受键盘输入(但**不接 SSH 网络**)。

- [ ] 把 `emulatorview-release.aar` 放到 `bVNC/libs/`
- [ ] `bVNC/build.gradle` 加 `implementation files('libs/emulatorview-release.aar')`
- [ ] `SshBitmapData` 内部创建 `TermSession`(用一个本地 `PipedInputStream` / `PipedOutputStream` 假装是 SSH 通道)
- [ ] 新建 `SshTerminalRenderer`,把 `TermSession` 当前文本状态画到 Canvas(用 `Paint.drawText` 按行列)
- [ ] 新建 `RemoteSshKeyboard extends RemoteKeyboard`,把 KeyEvent → ANSI 字节 → `TermSession.getTermIn()`
- [ ] 新建 `RemoteSshPointer extends RemotePointer`,no-op(SSH 没有鼠标)
- [ ] `RemoteCanvas.declareConnection()` 创建 `RemoteSshKeyboard` / `RemoteSshPointer`
- [ ] **可演示**:在屏幕终端里用软键盘输入"ls -la\n",看到 fake shell 回显"Hello from fake shell"

**Phase 1 完成 = 终端 + 输入链路验证**。此时 SSH 网络还没接,但键盘 → 终端 → 位图 的整条链路都通了。

### Phase 2 — 接入真 SSH(trilead)

**目标**:把 Phase 1 的本地 `PipedInputStream` 换成 trilead `Session.getStdout()/getStdin()`,真连 SSH 服务器。

- [ ] 下载 `trilead-ssh2-1.2.0.jar` 到 `bVNC/libs/`
- [ ] `bVNC/build.gradle` 加 `implementation files('libs/trilead-ssh2-1.2.0.jar')`
- [ ] 新建 `ConfigSSH extends MainConfiguration`,布局 `config_ssh.xml`:
  - 字段:昵称、服务器、端口(默认 22)、用户名、密码、保持密码
  - 隐藏 SSH 隧道 / 颜色模式 / 分辨率等无关字段
  - 保存后跳转 `SshTerminalActivity`
- [ ] 新建 `SshTerminalActivity extends RemoteCanvasActivity`,主要重写 `onCreate` 的协议初始化分支
- [ ] `SshConnectable` 内部组合现有 `SSHConnection.java`:
  - 复用 `SSHConnection.connect()` / 认证逻辑
  - 拿到 `Session` 后,把 `getStdout()` 接到 `TermSession.getTermOut()`(字节流),`getStdin()` 接到 `TermSession.getTermIn()`
- [ ] `Utils.getConnectionSetupClass("ssh")` 返回 `ConfigSSH.class`
- [ ] `AndroidManifest`(`aRDP-app` + `bVNC`)注册 `ConfigSSH` 和 `SshTerminalActivity`
- [ ] 资源:`config_ssh.xml` / `ssh_terminal.xml` / `ssh_terminal_menu.xml`
- [ ] 资源字符串:`description_ssh` 已有,补充其他 i18n
- [ ] **可演示**:真连一台 Linux 服务器,能正常执行命令

### Phase 3 — 完善

- [ ] 密钥认证(RSA / ED25519)+ 口令短语支持
- [ ] 心跳 / 网络切换(WiFi → 4G)重连
- [ ] 文本选择 / 复制粘贴
- [ ] 配色方案(Solarized Dark 等,`ColorScheme` 资源)
- [ ] 字体大小调整
- [ ] 测试:VNC/RDP/SPICE/NVStream 路径不能被 SSH 改动破坏

---

## 4. 关键类的草图

### 4.1 `SshConnectable`

```java
public class SshConnectable extends RfbConnectable {
    private final SSHConnection sshConn;       // 已有
    private TermSession termSession;           // Phase 1+
    private int fbW = 1280, fbH = 720;          // Phase 0 硬编码

    public SshConnectable(boolean debug, Handler h) {
        super(debug, h);
        this.sshConn = new SSHConnection(...);
    }

    @Override public int framebufferWidth()  { return fbW; }
    @Override public int framebufferHeight() { return fbH; }
    @Override public String desktopName()    { return "SSH:" + connection.getHostname(); }
    @Override public void requestUpdate(boolean incremental) { /* 触发 SshBitmapData.reDraw */ }
}
```

### 4.2 `SshBitmapData`

```java
public class SshBitmapData extends AbstractBitmapData {
    private SshTerminalRenderer renderer;
    private final Paint textPaint = new Paint();
    private final Paint bgPaint = new Paint();
    private final Typeface mono = Typeface.MONOSPACE;

    SshBitmapData(RfbConnectable rfb, RemoteCanvas c) {
        super(rfb, c);
        this.renderer = new SshTerminalRenderer(...);
        textPaint.setTypeface(mono);
        textPaint.setColor(0xFF839496);   // Solarized foreground
        bgPaint.setColor(0xFF002B36);    // Solarized background
    }

    @Override
    public void updateBitmap(int x, int y, int w, int h) {
        // 用 mbitmap 自己的 Canvas 画终端状态
        Canvas c = new Canvas(mbitmap);
        c.drawRect(0, 0, mbitmap.getWidth(), mbitmap.getHeight(), bgPaint);
        renderer.draw(c, termSession);  // 内部调 drawText
    }

    @Override public boolean validDraw(int x, int y, int w, int h) { return true; }
    @Override public int offset(int x, int y) { return y * mbitmap.getWidth() + x; }
    // copyRect / drawRect / scrollChanged / frameBufferSizeChanged:留空或最小实现
}
```

### 4.3 `RemoteSshKeyboard`

```java
public class RemoteSshKeyboard extends RemoteKeyboard {
    private final TermSession termSession;
    private final OutputStream termIn;

    public RemoteSshKeyboard(TermSession ts) {
        this.termSession = ts;
        this.termIn = ts.getTermIn();
    }

    @Override
    public boolean sendKeyEvent(int keySym, int metaState, boolean down) {
        if (!down) return true;
        // 把 X11 KeySym 翻译成 ANSI 字节,写入 termIn
        byte[] ansi = XKeySymToAnsi.convert(keySym, metaState);
        try { termIn.write(ansi); termIn.flush(); } catch (IOException ignored) {}
        return true;
    }
}
```

### 4.4 `SshTerminalActivity`

```java
public class SshTerminalActivity extends RemoteCanvasActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // super 已完成 RemoteCanvas 初始化;SSH 协议特定的钩子:
        // - 关闭光标动画(终端不显示 OS 鼠标)
        // - 隐藏分辨率/颜色工具栏条目
    }
}
```

---

## 5. 阶段验收标准

| 阶段 | 验收 |
|------|------|
| Phase 0 | 启动后屏幕中央显示"Hello SSH",用 DrawTask 流程,缩放/平移正常工作 |
| Phase 1 | 软键盘输入"echo hi\n",屏幕显示"hi"(fake shell 回显),`mbitmap` 实时刷新 |
| Phase 2 | 真连 `ssh user@127.0.0.1`,执行 `uname -a` 看到真实内核信息 |
| Phase 3 | 网络切换后自动重连;支持密钥认证 |

---

## 6. 风险与注意事项

1. **Android-Terminal-Emulator AAR 编译**:build.gradle 旧(compileSdk 22),需按规格书 § 旧版的兼容说明升级到 Gradle 8.4(用户本机已编译好,直接拷贝到 `bVNC/libs/` 即可)
2. **trilead-ssh2 已存在于 `SSHConnection.java` 但未在 `bVNC/build.gradle` 声明**:Phase 2 第一件事就是补依赖,否则编译报错
3. **mbitmap 频繁全量重绘**:终端是"每次按键都重画",不像 VNC 走局部 `drawRect`。Phase 0/1 用全量重绘足够(80×24 字符 ≈ 7KB,毫秒级),Phase 2 再考虑脏行优化
4. **不要直接用 `EmulatorView`**:它是自定义 View,会破坏 mbitmap 架构。`TermSession` 才是 View-无关的状态机
5. **输入法/外接键盘**:外接键盘走 `onKeyDown`,与现有 `InputHandler*` 兼容,只要 `RemoteSshKeyboard` 实现了 `sendKeyEvent` 即可

---

## 7. 参考资料

- [Apache MINA SSHD](https://mina.apache.org/sshd-project/)(已不采用,保留备查)
- [ConnectBot](https://github.com/connectbot/connectbot)(trilead-ssh2 的实际维护者)
- [Android-Terminal-Emulator](https://github.com/masonscped/Android-Terminal-Emulator)(AAR 源)
- 内部:[`project-structure.md`](./project-structure.md)
- 关键代码位置:
  - `RemoteCanvas.DrawWorker.run()`:`bVNC/.../RemoteCanvas.java:1871`
  - `AbstractBitmapDrawable.draw()`:`bVNC/.../AbstractBitmapDrawable.java:67`
  - `AbstractBitmapData` 抽象类:`bVNC/.../AbstractBitmapData.java:39`
  - `RemoteKeyboard` 抽象类:`remoteClientLib/.../input/RemoteKeyboard.java:32`
  - `RemotePointer` 抽象类:`remoteClientLib/.../input/RemotePointer.java:25`
  - `RemoteCanvas.declareConnection()`:`bVNC/.../RemoteCanvas.java:1330-1368`
  - `Utils.getConnectionSetupClass`:`bVNC/.../Utils.java:370`
  - 现有 `SSHConnection.java`:`bVNC/.../SSHConnection.java`(753 行,trilead 半成品)

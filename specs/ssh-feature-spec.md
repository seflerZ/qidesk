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
- **Phase 0 不引入自定义 `BitmapData` 子类**,而是复用 RDP/NVStream 用的 `UltraCompactBitmapData`,在 `RemoteCanvas.startSshConnection()` 调一个 stub 渲染器 `drawSshPlaceholderIntoBitmap()` 直接 `Canvas.drawText` 到 mbitmap(Phase 1+ 替换为 `TermSession` 驱动)
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

## 1. 对接架构(★ 修订,Phase 1 已落地)

> Phase 1 把这条链路接通了:键盘→TermSession→mbitmap 全部走通,只是 tty I/O 还指向本地 `PipedInputStream`(`FakeShellLoopback`)。Phase 2 再把 pipe 换成 trilead `Session.getStdout/getStdin`。

```
              ┌────────────────────────────────────────────────────┐
              │              RemoteCanvasActivity                  │
              │  (复用,零改动)                                       │
              │                                                    │
              │  IME / 硬键盘 onKeyDown ──→ RemoteSshKeyboard      │
              │                              .processLocalKeyEvent │
              │                              (KeyEvent → codepoint)│
              │                                    │                │
              │                                    ▼                │
              │                        TermSession.write(int)       │
              │                        (UTF-8 内置)                  │
              │                                    │                │
              │                                    ▼                │
              │  Phase 1: FakeShellLoopback.termIn (PipedInput)     │
              │  Phase 2: SSHConnection.Session.getStdin()         │
              │                                    │                │
              │                                    ▼ echo / shell   │
              │  Phase 1: FakeShellLoopback.termOut (PipedOutput)   │
              │  Phase 2: SSHConnection.Session.getStdout()        │
              │                                    │                │
              │                                    ▼                │
              │                     TermSession 读线程 → 状态机     │
              │                                    │                │
              │                                    ▼ 屏幕 / 光标变   │
              │                          setUpdateCallback          │
              │                          (主线程 mMsgHandler)        │
              │                                    │                │
              │                                    ▼                │
              │  SshConnectionInitializer.sshUpdateRunnable        │
              │  → paintAndRedraw()                                 │
              │     → SshTerminalRenderer.renderInto(mbitmap)      │
              │        → TermRenderHelper.render()                  │
              │           → screen.drawText(row, ..., cursorX=-1)  │
              │              (包私有,AAR 内部 BaseTextRenderer)    │
              │                                    │                │
              │                                    ▼                │
              │                  mbitmap (Bitmap)                    │
              │  → canvas.reDraw() → DrawWorker 主循环              │
              │  → AbstractBitmapDrawable.draw() → SurfaceView     │
              └────────────────────────────────────────────────────┘
```

> 单一重绘路径:`TermSession.setUpdateCallback` 触发 → `SshConnectionInitializer.sshUpdateRunnable` → `paintAndRedraw()`。`onSurfaceCreated` 直接调一次 `paintAndRedraw()` 兜底 surface 重建场景。**无 heartbeat**(详见 §10.6 修订)。

**绘制链路**(零改动,与 VNC/RDP/SPICE/NVStream 共用):
```
mbitmap
  → AbstractBitmapDrawable.draw(canvas)   [AbstractBitmapDrawable.java:67]
  → canvas.drawBitmap(mbitmap, ...)        [AbstractBitmapDrawable.java:71]
  → DrawWorker.run() 主循环                [RemoteCanvas.java:1871]
  → SurfaceView 屏幕呈现
```

**关键点**:
- `EmulatorView`(来自 Android-Terminal-Emulator)是一个自定义 `View` 组件——**我们不用它**。`TermSession` 才是与 View 无关的纯逻辑层。
- `TranscriptScreen.drawText` / `TerminalEmulator` / `PaintRenderer` / `BaseTextRenderer` 在 AAR 里都是 **package-private**。`TermRenderHelper` 用与 AAR 相同的 `jackpal.androidterm.emulatorview` 包名塞进 `remoteClientLib/`,从而继承包私有访问(详细见 §10.2)。
- 五个协议不再各自塞在 `RemoteCanvas` 里,而是抽成 `ConnectionInitializer` 策略类(§8.17)。SSH 走的是 `SshConnectionInitializer`,其它协议照旧。

---

## 2. 文件结构(★ 修订,Phase 1 已落地)

```
remote-desktop-clients/
├── specs/
│   ├── project-structure.md           # 项目结构(见 §0)
│   └── ssh-feature-spec.md            # 本文件
│
├── remoteClientLib/                    # 第三方依赖(与 FreeRDP / Moonlight 同住,见 §10.1)
│   ├── emulatorview-release.aar       # Android-Terminal-Emulator AAR(已编译在 ~/Android-Terminal-Emulator/...)
│   ├── src/main/java/jackpal/androidterm/emulatorview/
│   │   └── TermRenderHelper.java      # 包私有桥接(包名与 AAR 一致,详见 §10.2)
│   └── build.gradle                    # api(name: 'emulatorview-release', ext: 'aar')
│
├── bVNC/                               # 应用代码,Phase 1 增量
│   └── src/main/java/com/qihua/bVNC/
│       ├── SSHConnection.java         # 已有(753 行,trilead),Phase 2 复用
│       ├── ssh/
│       │   ├── SshTerminalRenderer.java  # 新增:持 TermSession + FakeShellLoopback + TermRenderHelper
│       │   └── FakeShellLoopback.java    # 新增:本地 tty 桥(Phase 2 删,换 trilead Session)
│       ├── connection/
│       │   └── SshConnectionInitializer.java  # §8.17 抽出的策略类(原 Phase 0 在 RemoteCanvas 内)
│       ├── input/
│       │   ├── RemoteSshKeyboard.java  # KeyEvent → TermSession.write(int),§8.1 提醒 extends bVNC 版本
│       │   └── RemoteSshPointer.java   # 15 个 no-op 方法,Phase 2+ 文本选择
│       └── communicator/
│           └── SshCommunicator.java   # extends RfbConnectable,固定 fbW/fbH,Phase 2 接 SSHConnection
│
├── bVNC/src/main/res/layout/    # Phase 2: config_ssh.xml / ssh_terminal.xml
├── bVNC/src/main/res/menu/      # Phase 2: ssh_terminal_menu.xml
└── aRDP-app/src/main/AndroidManifest.xml  # Phase 2: 注册 ConfigSSH / SshTerminalActivity
```

**复用 vs 新增**(Phase 1 视角):
| 复用(零改动) | 新增 |
|---------------|------|
| `Viewable` 接口 | `SshCommunicator`(原 `SshConnectable`,§8.17 改名) |
| `RfbConnectable` 基类 | `SshTerminalRenderer` |
| `AbstractBitmapData` 抽象类 | `FakeShellLoopback` |
| `AbstractBitmapDrawable` | `RemoteSshKeyboard`(扩 `processLocalKeyEvent`) |
| `RemoteCanvas` | `RemoteSshPointer`(no-op) |
| `RemoteCanvasActivity`(基类) | `SshConnectionInitializer`(策略类) |
| `DrawWorker`(主绘制循环) | `TermRenderHelper`(放 `remoteClientLib/`) |
| `InputHandler*` 全套 | |
| `ConnectionInitializer` 策略模式(§8.17) | |
| `Utils`(只加 1 行 case) | |

---

## 3. 实现任务清单(★ 重组:分 3 阶段,先骨架后功能)

### Phase 0 — 最小可跑通骨架(★ 优先)

**目标**:用硬编码字符串渲染到 mbitmap,跑通 `RemoteCanvas` 绘制链路,屏幕显示"Hello SSH"。
这一步**不依赖** trilead、TermSession、AAR,只验证架构契约是否真的成立。

- [x] 新建 `SshConnectable extends RfbConnectable`,framebuffer 尺寸 = `displayRect × SSH_SMART_RESOLUTION_FACTOR`,desktopName = "SSH Terminal"
- [x] 在 `RemoteCanvas.reallocateDrawable()` 把 SSH 加入 `if (isRdp | isNvStream | isSsh)` 分支,复用 `UltraCompactBitmapData`(见 §8.13)
- [x] 在 `RemoteCanvas.startSshConnection()` 调 `drawSshPlaceholderIntoBitmap()` 用 `Canvas.drawText("Hello SSH", ...)` 写 mbitmap
- [ ] 临时在 `ConnectionGridActivity.addNewConnection` 删掉 SSH guard(测试用)
- [ ] 临时在 `Utils.getConnectionSetupClass("ssh")` 返回 `RemoteCanvasActivity.class`(绕开 ConfigSSH,后续再补)
- [ ] `adb` 跑通后,屏幕上应看到"Hello SSH"

**Phase 0 完成 = 架构可行性验证**。如果 mbitmap 上的字符能跟着 `DrawWorker` 正确绘制到屏幕,后续 1/2 阶段就是把"硬编码字符串"换成"动态 TermSession 状态"。

### Phase 1 — 接入 TermSession(本地伪终端) ✅ DONE (2026-06)

**目标**:引入 Android-Terminal-Emulator 的 `TermSession`,把硬编码字符串换成"TermSession 内存中的状态",并接受键盘输入(但**不接 SSH 网络**)。

- [x] 把 `emulatorview-release.aar` 放到 `remoteClientLib/`(而非 `bVNC/libs/`,见 §10.1)
- [x] `remoteClientLib/build.gradle` 加 `api(name: 'emulatorview-release', ext: 'aar')`(用 `api` 而非 `implementation`,这样 bVNC 拿到的 `TermSession` 引用是 transitive 可见的)
- [x] 在 `remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java` 放包私有桥接(§10.2)
- [x] `SshTerminalRenderer` 内部创建 `TermSession` + `FakeShellLoopback`(双 `PipedInputStream/OutputStream` 假装 tty 通道,8KB buffer,§10.3)
- [x] `SshTerminalRenderer.renderInto(Bitmap)` 走 `TermRenderHelper.render` → `TranscriptScreen.drawText(row, ..., cursorX=-1)` + `PaintRenderer`,不用自己 `drawText` 按行画(§10.4)
- [x] `RemoteSshKeyboard.processLocalKeyEvent`:`KEYCODE_ENTER/DEL/TAB/ESCAPE` 映射 ANSI 字节,其余走 `evt.getUnicodeChar(metaState)` → `termSession.write(int)`(UTF-8 内置,§10.5)
- [x] `RemoteSshPointer`:15 个 no-op 方法(§8.2 提醒 extends `bVNC` 版本)
- [x] `SshConnectionInitializer`(策略类,§8.17)持有 renderer,重绘完全由 UpdateCallback 驱动(无 heartbeat,见 §10.6)
- [x] 折叠/展开:`rebuildFramebuffer()` 关闭旧 renderer + 重建新 renderer + 换 keyboard 的 TermSession 引用(Phase 1 接受屏幕内容重置)
- [x] **可演示**:终端开屏即显示 "Hello from fake shell!\nType something and press Enter.\n$ ",键入字符逐个回显,回车换行并再次打印 "$ ",Phase 1 验证完成

**Phase 1 完成 = 终端 + 输入链路验证**。此时 SSH 网络还没接,但键盘 → 终端 → 位图 的整条链路都通了,Phase 2 只需把 `FakeShellLoopback` 那对 pipe 换成 trilead `Session.getStdout/getStdin`(详见 §10)。

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

## 4. 关键类的草图(Phase 1 实际 API)

> 与 §3 旧版"待办"草图不同,以下是 Phase 1 已经落地的真实 API(类名 / 方法名 / 行为均以 `bVNC/` 实际文件为准)。

### 4.1 `SshCommunicator`(原 `SshConnectable`,§8.17 改名)

```java
// bVNC/src/main/java/com/qihua/bVNC/communicator/SshCommunicator.java
public class SshCommunicator extends RfbConnectable {
    private final int fbW, fbH;
    public SshCommunicator(boolean debug, Handler h, int fbW, int fbH) { ... }
    @Override public int framebufferWidth()  { return fbW; }
    @Override public int framebufferHeight() { return fbH; }
    @Override public String desktopName()    { return "SSH Terminal"; }
    @Override public void requestUpdate(boolean incremental) { /* 协议无关,UpdateCallback 走 SshConnectionInitializer */ }
    // ... 其它 14 个 RfbConnectable 抽象方法都是 no-op
}
```

### 4.2 `SshTerminalRenderer`(Phase 1 真实渲染器)

```java
// bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalRenderer.java
public class SshTerminalRenderer {
    private static final int BG_COLOR = 0xFF002B36; // Solarized base03
    private final float density;
    private final TermRenderHelper helper = new TermRenderHelper();
    private final TermSession termSession = new TermSession();
    private final FakeShellLoopback loopback;
    private int currentCols = -1, currentRows = -1;
    private boolean closed;

    public SshTerminalRenderer(float density) throws IOException {
        this.density = density;
        this.loopback = new FakeShellLoopback();
        termSession.setTermIn(loopback.getTerminalIn());
        termSession.setTermOut(loopback.getTerminalOut());
    }

    /**
     * 启动 TermSession + echo 线程。initialPxW/initialPxH 用于首帧 seed cols/rows。
     * onUpdate 是 TermSession 屏幕变化时触发的回调（主线程,内部 mMsgHandler）。
     */
    public void open(int initialPxW, int initialPxH, Runnable onUpdate) {
        helper.probe(fontSizePx());
        int cols = TermRenderHelper.computeCols(emptyCanvasOf(initialPxW), helper.charWidth);
        int rows = TermRenderHelper.computeRows(emptyCanvasOf(initialPxH), helper.charHeight);
        currentCols = cols; currentRows = rows;
        termSession.updateSize(cols, rows);
        if (onUpdate != null) {
            termSession.setUpdateCallback(() -> onUpdate.run());
        }
        loopback.start();
    }

    /** 画到 mbitmap。如果 cols/rows 变了自动调 updateSize。 */
    public void renderInto(Bitmap target) {
        if (closed || target == null || target.isRecycled()) return;
        Canvas c = new Canvas(target);
        c.drawColor(BG_COLOR);
        int cols = TermRenderHelper.computeCols(c, helper.charWidth);
        int rows = TermRenderHelper.computeRows(c, helper.charHeight);
        if (cols != currentCols || rows != currentRows) {
            currentCols = cols; currentRows = rows;
            termSession.updateSize(cols, rows);
        }
        helper.render(termSession, c, fontSizePx());
    }

    public TermSession getTermSession() { return termSession; }

    /** finish TermSession（join reader/writer 线程）+ close loopback。 */
    public void close() {
        if (closed) return;
        closed = true;
        try { termSession.finish(); } catch (Throwable t) { /* log */ }
        loopback.close();
    }

    private int fontSizePx() {
        return Math.max(1, Math.round(Constants.SSH_FONT_SIZE_DP * density));
    }
}
```

### 4.3 `FakeShellLoopback`(Phase 1 临时 tty 桥,Phase 2 删)

```java
// bVNC/src/main/java/com/qihua/bVNC/ssh/FakeShellLoopback.java
public class FakeShellLoopback {
    private static final byte[] WELCOME = ("Hello from fake shell!\r\n"
                                          + "Type something and press Enter.\r\n$ ").getBytes();
    private static final byte[] PROMPT = "\r\n$ ".getBytes();
    private static final int PIPE_SIZE = 8 * 1024;
    private final PipedInputStream toTerminalSource = new PipedInputStream(PIPE_SIZE);
    private final PipedOutputStream toTerminalSink = new PipedOutputStream();
    private final PipedInputStream fromTerminalSource = new PipedInputStream(PIPE_SIZE);
    private final PipedOutputStream fromTerminalSink = new PipedOutputStream();
    private Thread echoThread;
    private volatile boolean running;

    public FakeShellLoopback() throws IOException {
        toTerminalSink.connect(toTerminalSource);
        fromTerminalSink.connect(fromTerminalSource);
    }
    public InputStream  getTerminalIn()  { return toTerminalSource; }
    public OutputStream getTerminalOut() { return fromTerminalSink; }

    public void start() {
        if (running) return;
        running = true;
        try { toTerminalSink.write(WELCOME); toTerminalSink.flush(); }
        catch (IOException e) { /* log */ }
        echoThread = new Thread(this::echoLoop, "FakeShell-Echo");
        echoThread.setDaemon(true);
        echoThread.start();
    }
    private void echoLoop() {
        byte[] buf = new byte[256];
        while (running) {
            int read;
            try { read = fromTerminalSource.read(buf); }
            catch (IOException e) { if (running) /* log */; return; }
            if (read < 0) return;
            try {
                for (int i = 0; i < read; i++) {
                    byte b = buf[i];
                    if (b == '\r' || b == '\n') toTerminalSink.write(PROMPT);
                    else                          toTerminalSink.write(b);
                }
                toTerminalSink.flush();
            } catch (IOException e) { return; }
        }
    }
    public void close() { /* 关 4 路 stream + interrupt echoThread */ }
}
```

### 4.4 `TermRenderHelper`(AAR 桥接,放 `remoteClientLib/`)

```java
// remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java
public final class TermRenderHelper {
    private PaintRenderer renderer;
    private int rendererFontSize = -1;
    public float charWidth;   // valid after first render()/probe()
    public int   charHeight;

    /** 关键:与 AAR 同包,直接调包私有 getTranscriptScreen/getEmulator/drawText。 */
    public void render(TermSession session, Canvas canvas, int fontSizePx) {
        TerminalEmulator emu = session.getEmulator();
        TranscriptScreen screen = session.getTranscriptScreen();
        if (emu == null || screen == null) return;
        if (renderer == null || rendererFontSize != fontSizePx) {
            renderer = new PaintRenderer(fontSizePx, BaseTextRenderer.defaultColorScheme);
            rendererFontSize = fontSizePx;
        }
        charWidth  = renderer.getCharacterWidth();
        charHeight = renderer.getCharacterHeight();
        renderer.setReverseVideo(emu.getReverseVideo());
        int cols = computeCols(canvas, charWidth);
        int rows = computeRows(canvas, charHeight);
        int cy = emu.getCursorRow(), cx = emu.getCursorCol();
        boolean cursorVisible = emu.getShowCursor();
        float x = 0, y = charHeight; // baseline of first row
        for (int row = 0; row < rows; row++) {
            int cursorX = (cursorVisible && row == cy) ? cx : -1;
            screen.drawText(row, canvas, x, y, renderer, cursorX, -1, -1, "", 0);
            y += charHeight;
        }
    }
    public void probe(int fontSizePx) { /* 同样的 PaintRenderer 懒初始化 + 量 charW/charH */ }
    public static int computeCols(Canvas c, float charWidth)  { /* canvas.getWidth()/charWidth */ }
    public static int computeRows(Canvas c, int charHeight)  { /* canvas.getHeight()/charHeight */ }
}
```

### 4.5 `RemoteSshKeyboard`(Phase 1:实装 `processLocalKeyEvent`)

```java
// bVNC/src/main/java/com/qihua/bVNC/input/RemoteSshKeyboard.java
public class RemoteSshKeyboard extends RemoteKeyboard {
    private TermSession termSession;     // 注:非 final,fold/unfold 会换

    public void setTermSession(TermSession ts) { this.termSession = ts; }
    public void setRfb(RemoteConnectable rfb) { this.rfb = rfb; }  // 沿用 §8.15.2

    @Override
    public boolean processLocalKeyEvent(int keyCode, KeyEvent evt, int additionalMetaState) {
        if (termSession == null) return false;
        if (evt.getAction() != KeyEvent.ACTION_DOWN) return true;  // 吞掉 UP
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:   termSession.write('\r');  return true;
            case KeyEvent.KEYCODE_DEL:     termSession.write(0x7f);  return true; // ASCII DEL
            case KeyEvent.KEYCODE_TAB:     termSession.write('\t');  return true;
            case KeyEvent.KEYCODE_ESCAPE:  termSession.write(0x1b);  return true;
        }
        int codePoint = evt.getUnicodeChar(evt.getMetaState() | additionalMetaState);
        if (codePoint == 0) return false;   // 非可打印,放行给 host
        termSession.write(codePoint);       // TermSession 内部 UTF-8 编码
        return true;
    }

    @Override public void sendMetaKey(MetaKeyBean meta) { /* Phase 1 no-op */ }
}
```

### 4.6 `SshConnectionInitializer`(策略类,§8.17 抽出)

```java
// bVNC/src/main/java/com/qihua/bVNC/connection/SshConnectionInitializer.java
public class SshConnectionInitializer extends ConnectionInitializer {
    private final Runnable sshUpdateRunnable = () -> { if (/* canvas is live */) paintAndRedraw(); };
    private RemoteCanvas canvas;
    private SshTerminalRenderer renderer;
    private float density;

    public void initialize(RemoteCanvas canvas) throws Exception {
        this.canvas = canvas;
        this.density = ctx.getResources().getDisplayMetrics().density;
        canvas.rfbconn = new SshCommunicator(App.debugLog, canvas.handler, computeFbW(), computeFbH());
        canvas.pointer = new RemoteSshPointer(...);
        canvas.keyboard = new RemoteSshKeyboard(...);
        renderer = new SshTerminalRenderer(density);
        ((RemoteSshKeyboard) canvas.keyboard).setTermSession(renderer.getTermSession());
    }
    public void start(RemoteCanvas canvas) throws Exception {
        canvas.waitUntilInflated();
        canvas.reallocateDrawable(canvas.displayRect.width(), canvas.displayRect.height());
        openRenderer();   // renderer.open(pxW, pxH, sshUpdateRunnable) + 首帧 paintAndRedraw()
        canvas.onConnectionSuccess();
        // 单路径:TermSession 的 UpdateCallback 是唯一重绘驱动
    }
    public void teardown(RemoteCanvas canvas) {
        closeRenderer();  // termSession.finish() + loopback.close()
    }
    private void paintAndRedraw() {
        if (renderer == null) return;
        renderer.renderInto(canvas.bitmapData.mbitmap);
        canvas.reDraw(0, 0, canvas.rfbconn.framebufferWidth(), canvas.rfbconn.framebufferHeight());
    }
    private void rebuildFramebuffer() {
        /* 折叠/展开触发:close 旧 renderer → new SshCommunicator → reallocateDrawable
           → new SshTerminalRenderer → keyboard.setTermSession(new) → openRenderer()
           Phase 1 接受屏幕内容重置,Phase 2+ 再考虑 TermSession 状态迁移 */
    }
}
```

---

## 5. 阶段验收标准

| 阶段 | 验收 | 状态 |
|------|------|------|
| Phase 0 | 启动后屏幕中央显示"Hello SSH",用 DrawTask 流程,缩放/平移正常工作 | ✅ |
| Phase 1 | 终端开屏即显示 welcome banner + `$ ` 提示符;键入字符逐个回显;回车换行再次打印 `$ `;软键盘/硬键盘都能用;折叠→展开不黑屏;`mbitmap` 实时刷新(UpdateCallback 单路径,无闪烁) | ✅ |
| Phase 2 | 真连 `ssh user@127.0.0.1`,执行 `uname -a` 看到真实内核信息;`FakeShellLoopback` 整类删除,TermSession 直接接 trilead `Session.getStdout/getStdin` | ⏳ |
| Phase 3 | 网络切换后自动重连;支持密钥认证;文本选择/复制粘贴;配色/字号设置 | ⏳ |

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

---

## 8. Phase 0 实施踩坑(实施后新增)

> 本节是 **Phase 0 写完之后回填的**。在 §3 的任务清单里"看似简单"的步骤,实际编码时遇到了 12+ 个具体问题。这些问题**不影响架构正确性**,但**直接照搬 §3 的清单就会编译失败**。后续 Phase 1/2 应当把这些坑当作**默认约束**。

### 8.1 同名类有两份,必须 extends 对的那一个

项目中存在两个 `RemotePointer` 和两个 `RemoteKeyboard`:

| 类 | 路径 | 实际地位 |
|---|---|---|
| `com.undatech.opaque.input.RemotePointer` | `remoteClientLib/` | 104 行,几乎无直接子类,实质上是"早期抽象" |
| **`com.qihua.bVNC.input.RemotePointer`** | `bVNC/` | 232 行,持有 `RemoteCanvas canvas` / `MouseScroller`,**真正被 InputHandler* 使用的基类** |
| `com.undatech.opaque.input.RemoteKeyboard` | `remoteClientLib/` | 旧版 |
| **`com.qihua.bVNC.input.RemoteKeyboard`** | `bVNC/` | 被 `RemoteCanvas.keyboard` 字段引用的实际类型 |

> ⚠️ 之所以会分裂:历史上 `bVNC` 是独立项目,后来并入 `opaque` 多协议聚合框架,base class 抽到 `remoteClientLib` 一份,但 bVNC 那份因为已经依赖了 `RemoteCanvas` / `MouseScroller`(bVNC 私有类)没法直接搬走。

**坑点**:`RemoteCanvas.pointer` / `RemoteCanvas.keyboard` 字段的类型是 `bVNC.input.*` 版本。SSH 子类如果 extends `opaque.input.*` 版本,会被字段赋值时 class-cast-exception。

**结论**:`RemoteSshPointer` / `RemoteSshKeyboard` **必须 extends `com.qihua.bVNC.input.*` 版本**,不要被 §3 §4 草图误导。

**未来工作**(Phase 2+ 再考虑):合并 `bVNC.input.*` 和 `opaque.input.*`,抽取共享基类到 `remoteClientLib`。但这会触动所有协议的 input 实现,不在 Phase 0/1 范围。

### 8.2 `RemotePointer` 抽象方法签名被 bVNC 版本悄悄改了

直接对照两个版本,会发现 **bVNC 版本比 opaque 版本多了 / 改了 5 个方法**:

| 方法 | opaque 版本签名 | bVNC 版本签名 | 说明 |
|---|---|---|---|
| `scrollUp` | `(int x, int y, int metaState)` | `(int x, int y, int speed, int metaState)` | 多了 `speed` 参数 |
| `scrollDown` | 同上 | 同上 | 同上 |
| `scrollLeft` | 同上 | 同上 | 同上 |
| `scrollRight` | 同上 | 同上 | 同上 |
| `touchDown` | (不存在) | `(int x, int y, int contactId)` | 新增,5 个 touch* 全是新增 |
| `touchUpdate` / `touchCancel` / `touchUp` | (不存在) | 同上 | 同上 |

> 这 5 个 touch 方法是为多点触摸 / 手势预留的,InputHandler 在某些分支会调。

**坑点**:如果 SSH 的 `RemoteSshPointer` 只 override 了 §4 草图里列的 3 个 button + 3 个 moveMouse 方法,**编译不通过**——bVNC 的基类还有 5 个抽象方法等着实现。

**Phase 0 完整必须 override 的 15 个方法清单**:
```java
leftButtonDown / middleButtonDown / rightButtonDown              // 3
scrollUp / scrollDown / scrollLeft / scrollRight  // 4,都是 4 参
releaseButton                                                     // 1
moveMouse / moveMouseButtonDown / moveMouseButtonUp               // 3
touchDown / touchUpdate / touchCancel / touchUp                   // 4
```
共 15 个,全部 no-op(SSH 阶段 0 没有鼠标语义)。

### 8.3 父类访问修饰符全是 package-private,跨包子类化全失败

实施前我以为"extends 一下就完事",实际一写代码,IDE 立刻一片红。

具体来说,`AbstractBitmapData` 至少有 10 个成员对 `bVNC` 同包外不可见:

| 成员 | 改前 | 改后 | 必要性 |
|---|---|---|---|
| `framebufferwidth` | package-private | `protected` | (原计划: `SshBitmapData` 需要读它来计算布局)Phase 0 复用 `UltraCompactBitmapData`,这些字段在它自己的实现里已经处理,不需要改. 留作 Phase 1+ 备用. |
| `framebufferheight` | package-private | `protected` | 同上 |
| `bitmapwidth` | package-private | `protected` | 同上 |
| `bitmapheight` | package-private | `protected` | 同上 |
| `mbitmap` | package-private | `protected` | 同上 |
| `bitmapPixels` | package-private | `protected` | 备用(Phase 0 不一定用,但保持开放) |
| `memGraphics` | package-private | `protected` | 同上 |
| 构造方法 `AbstractBitmapData(RfbConnectable, RemoteCanvas)` | package-private | `protected` | 同上 |
| `createDrawable()` | package-private abstract | `protected` abstract | 必须 override |
| `drawRect(...)` | package-private abstract | `protected` abstract | 必须 override |
| `scrollChanged()` | package-private abstract | `protected` abstract | 必须 override |
| `syncScroll()` | package-private abstract | `protected` abstract | 必须 override |

另外 `AbstractBitmapDrawable` 的构造方法也要 `public`(否则 `createDrawable()` 返回的对象没法 new)。

**坑点**:
- 这是一次**最小化的可扩展性 refactor**——原代码假设 `AbstractBitmapData` 永远只有 `CompactBitmapData` / `FullBufferBitmapData` / `LargeBitmapData` / `UltraCompactBitmapData` 这 4 个同包子类,从来没考虑过跨包子类化。
- 改 `protected` **不会破坏现有 4 个子类**——它们都同包,`protected` ≥ package-private。
- 4 个子类里的 `createDrawable` / `drawRect` / `scrollChanged` / `syncScroll` override 也建议改成 `protected`,与基类一致(虽然 Java 允许子类把 `protected` 收紧到 package-private,但 IDE 会标黄)。

**教训**:实施新协议时,先做一次"假设我的子类在另一个 package"的访问修饰符审查。

### 8.4 `RfbConnectable` 是 17 个 abstract 方法的"接口怪物"

写 `SshConnectable` 之前我以为只需要 stub `framebufferWidth/Height` / `desktopName` / `close()` 这几个;实际 stub 的时候发现是 17 个,一个都不能少。

完整清单(Phase 0 全 no-op):
```java
int framebufferWidth() / int framebufferHeight()                      // 2
String desktopName() / String getEncoding()                           // 2
void requestUpdate(boolean incremental)                               // 1
void requestResolution(int x, int y) throws Exception                 // 1
void writeClientCutText(String text)                                  // 1
void setIsInNormalProtocol(boolean) / boolean isInNormalProtocol()    // 2
void writePointerEvent(int x, int y, int metaState,
                       int pointerMask, boolean relative)             // 1
void writeKeyEvent(int key, int metaState, boolean down)              // 1
void writeSetPixelFormat(int, int, boolean, boolean,
                         int, int, int, int, int, int, boolean)      // 1
void writeFramebufferUpdateRequest(int, int, int, int, boolean)       // 1
void close() / void reconnect()                                       // 2
boolean isCertificateAccepted() / void setCertificateAccepted(boolean) // 2
```
共 17 个。

**坑点**:`writeSetPixelFormat` 那个 11 参的方法,IDE 自动生成的签名一定要照搬——参数顺序错了编译通过但运行期崩。

**Phase 0 简化策略**:
- 真正"有意义"的只有 `framebufferWidth/Height` / `desktopName` / `getEncoding` / `setIsInNormalProtocol` / `setCertificateAccepted` 5 个
- 其余全部 no-op,写明 `// Phase 0: no network to write to` 注释

### 8.5 `RemoteCanvas.sshTunneled` 字段语义有冲突

`RemoteCanvas.java:471` 有这么一段(具体行号以当前代码为准):
```java
this.sshTunneled = conn.sshTunneled;
```

这个 `sshTunneled` 字段的原始语义是 **"VNC-over-SSH 隧道模式"**(VNC 流量被 SSH 加密),**不是** "这是 SSH 协议"。

**坑点**:如果 SSH 启动时不小心把这个标志设为 true,可能会走错代码分支(比如期望有 `ConnectionBean.sshServer` / `sshUser` / `sshPassword` 这些 VNC-over-SSH 的字段)。

**Phase 0 做法**:`ConnectionBean` 里没有为 SSH 设这个字段,默认 false,正好。但 Phase 2 接 trilead 时要确认 `SSHConnection` 不会污染这个标志。

### 8.6 `AbstractBitmapDrawable.drawing` 字段已死

`AbstractBitmapDrawable` 有一个 `boolean drawing` 字段,`startDrawing()` 把它置 true,`dispose()` 把它置 false。

**坑点**:**除了这两个 setter,没有任何 reader**。也就是说,`drawing` 字段现在完全是死代码。

为什么这成为坑?因为 Phase 0 我去查"什么时候 `mbitmap` 才被画到屏幕"时,以为 `drawing` 是关键状态,绕了一圈才发现不是——`DrawWorker.run()` 是个无条件 `while (true)` 循环,只要 `mbitmap != null` 就画,根本不读 `drawing`。

**教训**:不要被看似相关的字段名误导,直接看 `DrawWorker` 主循环就清楚。

### 8.7 `startConnection` 必须主动调 `reallocateDrawable`

VNC / RDP / SPICE / NVStream 的 `startConnection` 各有各的触发器:有的是网络层回调、有的是解码器第一帧 ready 时调 `onConnectionSuccess()`。但 **SSH 没有网络层**——连是"假"的,没有"第一帧"可言。

**坑点**:如果不在 `startSshConnection()` 里**手动**调 `reallocateDrawable(...)` + `onConnectionSuccess()`,`bitmapData` 永远是 `null`,屏幕黑屏。

正确写法(已在 `RemoteCanvas.startSshConnection()` 实现):
```java
private void startSshConnection() {
    // SSH 无网络,不会触发 decoder 第一帧回调,得自己造
    waitUntilInflated();           // 8.8 详述
    reallocateDrawable(...);       // 复用 UltraCompactBitmapData (RDP/NVStream 同款)
    onConnectionSuccess();         // 触发 UI 进入"已连接"状态
}
```

**教训**:加新协议时,如果它的连接触发链和其他协议都不同,要**手动模拟** "decoder 第一帧"。

### 8.8 `reallocateDrawable` 里有 `decoder != null` 检查

`RemoteCanvas.java:1407`(行号近似):
```java
if (decoder != null) {
    decoder.stop();
    decoder = null;
}
```

**坑点**:SSH 阶段 0 没有 `decoder` 字段(也没有 `VncDecoder` 实例),`decoder` 永远是 `null`。这里的 `if` 守卫保证了 SSH 路径下不会 NPE,**但前提是 SSH 路径下 `decoder` 真的从来没被赋过值**。

**教训**:加新协议时,凡是 `reallocateDrawable` 内部 if-guard 的字段,新协议要保持该字段为 `null` 或安全初值,不要盲目创建。

### 8.9 `displayRect` 在 `onCreate` 完成前就被读

`SshTerminalRenderer.renderInto(Bitmap)` 内部要计算 mbitmap 实际绘制区域,通常需要 `RemoteCanvas.displayRect` 这个 `RectF` 字段 (Phase 0 是 `drawSshPlaceholderIntoBitmap` 直接调, 同样需要 `displayRect` 已经就绪).

**坑点**:`displayRect` 在 `RemoteCanvas.onCreate` 之后才有意义(它依赖 `getWindow().getDecorView()` 的实际尺寸)。如果 SSH 的 `updateBitmap` 早于 `onCreate` 完成,会读到全零 RectF。

**Phase 0 解法**:`RemoteCanvas.startSshConnection()` 第一行调 `waitUntilInflated()`,确保 View 已经 measure / layout 过,`displayRect` 已经有合理值。`waitUntilInflated()` 在 §3 Phase 0 任务清单里被遗漏,实际是必需的。

### 8.10 `UltraCompactBitmapData` 的 `frameBufferSizeChanged` 重建陷阱

`AbstractBitmapData` 的 `framebufferSizeChanged()` 在 mbitmap 大小需要改变时被调. `UltraCompactBitmapData` 的实现: 如果 `bitmapwidth < framebufferwidth` 或 `bitmapheight < framebufferheight`,`dispose()` + `Bitmap.createBitmap(...)` 重建 mbitmap, 清空原内容.

**坑点**:折叠/展开场景下,如果只调一次 `drawSshPlaceholderIntoBitmap()`,之后 mbitmap 被 `frameBufferSizeChanged` 重建,文字会消失.

**Phase 0 解法**:`frameBufferSizeChanged` 在 SSH 场景不会被 RfbProto 触发(RfbProto 是 VNC/RDP 专有),所以 Phase 0 不会撞上. 折叠时通过 `setDisplayRect` override 主动调 `rebuildSshFramebuffer`,一次性重建 rfbconn + mbitmap + 重画.

### 8.11 `@Override` 注解的 sed 翻车教训

实施过程中我用 sed 给 4 个子类的 `createDrawable/drawRect/scrollChanged/syncScroll` 批量加 `@Override`,结果 sed 脚本没去重,**部分方法头上出现了两个 `@Override`**,编译失败。

**教训**:
- 批改代码用 awk 而不是 sed
- 改完必须跑 `./gradlew :bVNC:compileGplayReleaseJavaWithJavac` 验证,**不能 IDE 里看着像对的就算完**
- `javac` 对重复 `@Override` 的报错是 `method does not override or implement a method from a supertype`,提示不够直观,容易归因到别处

### 8.12 `ConfigSSH` 与 `SshTerminalActivity` 的 Phase 0 兜底

§3 Phase 0 任务清单里没明说,但实际跑通还需要:

- **`ConfigSSH` 必须是 `Activity` 的子类,且 `AndroidManifest` 里注册**——否则 `Utils.getConnectionSetupClass("ssh")` 返回它后,Intent 起不来
- **Phase 0 临时方案**:`Utils.getConnectionSetupClass("ssh")` 先返回 `RemoteCanvasActivity.class`,跳过 ConfigSSH,直接用硬编码 ConnectionBean 走 RemoteCanvasActivity
- **`ConnectionGridActivity.addNewConnection` 的 SSH guard 必须删掉**——原文是 `if (type.equals("ssh")) return;`,不删就永远点不进去

**Phase 0 启动链路**(完整):
```
桌面图标 / ConnectionGridActivity
  → addNewConnection("ssh")               // guard 删掉
  → Utils.getConnectionSetupClass("ssh") // 返回 RemoteCanvasActivity(临时)
  → new Intent(...).putExtras(connBean)  // connBean 是 ConfigSSH 里硬编码的
  → RemoteCanvasActivity.onCreate
  → RemoteCanvas.initializeCanvas        // isSsh = true
  → RemoteCanvas.startSshConnection
  → reallocateDrawable → (复用) UltraCompactBitmapData
  → drawSshPlaceholderIntoBitmap 画 "Hello SSH" 到 mbitmap
  → DrawWorker 主循环把 mbitmap 推到 surface (由 30 FPS 心跳驱动, 见 §8.13)
```

### 8.13 Phase 0 编译命令

```bash
# 仅编译 bVNC 模块(快,定位错误方便)
./gradlew :bVNC:compileGplayReleaseJavaWithJavac

# 完整打包(确认 APK 能出)
./gradlew :aRDP-app:assembleGplayDebug

# APK 位置
ls aRDP-app/build/outputs/apk/gplay/debug/
```

> Phase 0 验证标准:APK 能装到设备、点开能进终端、屏幕中央看到 "Hello SSH"、缩放 / 平移 / 截图能工作。

### 8.14 Phase 0 → Phase 1 的具体改动点预告

| 改动 | 位置 | 备注 |
|---|---|---|
| `SshTerminalRenderer.renderInto` 改为读 `TermSession` | `bVNC/.../ssh/SshTerminalRenderer.java` | 引入 `emulatorview-release.aar`. Phase 0 `drawSshPlaceholderIntoBitmap` 直接画硬编码字符串, 抽到独立类便于测试. |
| `SshConnectable` 增加 `TermSession` 字段 | `bVNC/.../ssh/SshConnectable.java` | |
| `RemoteSshKeyboard` 实现 `sendKeyEvent` | `bVNC/.../ssh/RemoteSshKeyboard.java` | KeySym → ANSI 字节表 |
| `ConfigSSH` 改为 `MainConfiguration` 真子类 | `bVNC/.../ConfigSSH.java` | 布局 `config_ssh.xml` |
| `Utils.getConnectionSetupClass("ssh")` 返回 `ConfigSSH.class` | `bVNC/.../Utils.java` | Phase 0 临时方案切回真方案 |
| 新建 `SshTerminalActivity extends RemoteCanvasActivity` | `bVNC/.../ssh/` | 大部分逻辑复用 |
| 两个 `AndroidManifest.xml` 注册新 Activity | | |

> Phase 0 故意把 `RemoteSshKeyboard` / `RemoteSshPointer` 写成 no-op,Phase 1 再加真实逻辑——这样 Phase 0 的"假终端"能跑起来,验证骨架。

### 8.15 Phase 0 完成后追加踩坑(本轮新增)

- **8.15.1 折叠/展开后 mbitmap 不重排**: SSH 没有服务端推 framebuffer 新尺寸. RDP/VNC 的 `updateFBSize() → frameBufferSizeChanged()` 路径只走 RfbProto,SSH 走不到. 修复: 覆盖 `RemoteCanvas.setDisplayRect(Rect)`,在 SSH 分支里比较新旧 width/height,如果变了就 `new SshConnectable` + `reallocateDrawable` + `drawSshPlaceholderIntoBitmap`. `RemoteCanvasActivity.correctAfterRotation` 在 `onConfigurationChanged` 后 300ms 触发,会调 `setDisplayRect`,所以折叠事件能被捕获. RDP 的 `correctAfterRotation` 只动 `displayRect` + `scaler`,不重建 mbitmap,因为 RDP 的 mbitmap 由服务端说了算——SSH 没有服务端,本地 view 变化必须直接驱动.

- **8.15.2 受保护字段访问**: `RemotePointer.protocomm` 和 `RemoteKeyboard.rfb` 都是 `protected`,但 `RemoteCanvas` 在 `com.qihua.bVNC` 包,父类在 `com.qihua.bVNC.input` 子包,跨包 protected 不可见. 修复: 在 `RemoteSshPointer` / `RemoteSshKeyboard` 加 package-public setter (`setProtocomm` / `setRfb`),供 `rebuildSshFramebuffer` 调用.

- **8.15.3 字号按密度而非按像素**: 之前 `textSize = w/50` 在 cover (1060 宽, density 2.7) = 7.8dp,展开 main (2156 宽, density 2.1) = 20.5dp. 折叠后字体反直觉地变小,展开后变大. 物理上一个字的大小本应在两个屏上接近. 修复: 新增 `Constants.SSH_FONT_SIZE_DP = 14f`,绘制时 `textSize = SSH_FONT_SIZE_DP * displayMetrics.density`. 两个屏上物理字高一致,视觉差异由 `SSH_SMART_RESOLUTION_FACTOR` 控(fit-center 缩放 mbitmap).

- **8.15.4 字号 vs 分辨率系数是两个独立维度**: `SSH_FONT_SIZE_DP` 决定"一个字符占多少物理距离",`SSH_SMART_RESOLUTION_FACTOR` 决定"mbitmap 比屏幕大/小多少,fit-center 缩放后视觉再放大/缩小多少". Phase 1 的"字号"用户设置可以两个都用,或只动 factor(更省内存).

### 8.16 Phase 0 验收

- [x] APK 编译并在 Honor 折叠屏上安装运行
- [x] 屏幕中央出现 "Hello SSH" + "Phase 0: minimal skeleton"
- [x] 青色 Solarized 背景铺满 mbitmap
- [x] 折叠→展开 / 展开→折叠: 字体物理大小不变,画面无黑边
- [x] 不需要任何用户输入(SmartResolution / 字号 / 字体设置都不存在)

### 8.17 后续重构: `RemoteCanvas` 拆出 `ConnectionInitializer` 策略类(2026-06)

**动机**: Phase 0 把 SSH 塞进 `RemoteCanvas` 后, 这个类已经膨胀到 2598 行, 5 协议(VNC/RDP/SPICE/NVStream/SSH)各有 `initializeXxxConnection()` + `startXxxConnection()` + 散落的 `if (isXxx)` 22 处. 再加 Phase 1+ SSH 的真实 `TermSession` 驱动, 这个类会失控.

**方案**: 抽象基类 `com.qihua.bVNC.connection.ConnectionInitializer` + 5 个具体实现 + 工厂. 模仿现有 `InputHandlerGamepad.initializeRemoteGamepad` 的协议分派模式.

**接口**(`ConnectionInitializer.java`, 70 行):
- `initialize(RemoteCanvas)` — 建 communicator/pointer/keyboard
- `start(RemoteCanvas)` — 真正发起网络连接(`cThread` 里跑)
- `teardown(RemoteCanvas)` — 默认 no-op, SSH 覆盖停心跳
- `onSurfaceCreated(RemoteCanvas)` / `onDisplayRectChanged(RemoteCanvas, Rect, Rect)` — 默认 no-op, SSH 覆盖重画占位符 / 重建 mbitmap
- `needsRedrawHeartbeat()` / `heartbeatIntervalMs()` — SSH 返回 true/33
- `reinitialize(RemoteCanvas)` — 默认 = teardown + initialize, Opaque 走相同路径

**5 个实现**(总计 ~990 行, 替换原 `RemoteCanvas` 中 ~750 行协议代码):
- `SshConnectionInitializer.java`(205 行)— Phase 0 桩, 30 FPS 心跳, "Hello SSH" 占位符, 折叠/展开 mbitmap 重建
- `VncConnectionInitializer.java`(154 行)— Decoder + RfbCommunicator + 握手 + 色深兜底 + `sendUnixAuth(canvas)` 助手
- `RdpConnectionInitializer.java`(75 行)— RdpCommunicator(FreeRDP) + `setConnectionParameters`
- `NvStreamConnectionInitializer.java`(128 行)— NvCommunicator + PreferenceConfiguration + 蜂窝降码率 + ControllerHandler
- `SpiceConnectionInitializer.java`(430 行)— SpiceCommunicator + 直连 / oVirt / PVE / .vv 文件四条入口

**工厂**(`ConnectionInitializerFactory.java`, 38 行): SPICE/Opaque 先看 app flavor(`Utils.isSpice(ctx)||Utils.isOpaque(ctx)`), 否则按 `conn.getConnectionType()` 分派. 加新协议 = 一个新类 + 一条工厂分支, 不动 `RemoteCanvas`.

**`RemoteCanvas` 瘦身效果**:
- 2598 → 1843 行 (-29%)
- 删除 6 个 `isVnc/isRdp/isSpice/isNvStream/isOpaque/isSsh` boolean 字段, 改为 `currentInitializer instanceof XxxConnectionInitializer` 的访问器方法
- `initializeCanvas` 5 路 `if/else` → 一行 `currentInitializer = ConnectionInitializerFactory.create(...); currentInitializer.initialize(this);`
- `startConnection` 5 路 → 一行 `currentInitializer.start(this);`
- `surfaceCreated` / `setDisplayRect` / `closeConnection` 协议特化 → 委托给 `currentInitializer.onSurfaceCreated / onDisplayRectChanged / teardown`

**保留不动**: `sshTunneled`(VNC-over-SSH 运行时状态, 不是协议选择), `reallocateDrawable` 里的 `isRdp() | isNvStream() | isSsh()` 选 `UltraCompactBitmapData`(将来可能挪到 `initializer.preferredBitmapImpl()`, 暂时一行 `if` 不值得抽), `getRemoteProtocolPort` / `getAddress` / `isColorModel` 的琐碎条件继续留 canvas.

**踩坑**:
- `AbstractBitmapData.mbitmap` 原本 `protected`, SshConnectionInitializer 直接读, 需提升为 `public`
- 子包跨包访问: `pointer/keyboard/handler/rfbconn/connection/spicecomm/rdpcomm/nvcomm/decoder/controller/activity/useFull/compact/displayRect/displayDensity/sshTunneled/surfaceHolder/vmNameToId/getAddress()/getRemoteProtocolPort/getRemoteWidth/getRemoteHeight/handleUncaughtException()` 都需要提升可见性给 initializer 用
- Opaque flavor 的 `RemoteCanvas#init()` 入口和默认 `initializeCanvas()` 不同, 工厂返回值需 cast 成 `SpiceConnectionInitializer` 才能塞 `vvFileName`
- 6 个布尔字段移除后, 9 处 `isXxx` 字段读必须改成 `isXxx()` 方法调; `RemoteCanvasActivity` 4 处 `canvas.isNvStream` 也得跟着改
- 一切共享状态仍走 canvas 公开字段, 没引入新的耦合; initializer 是 stateful (有 `vvFileName`、SSH 的 `sshRedrawHandler` 等), 不能复用单例

**验收**: gplayDebug / freeDebug / gplayRelease (AAB) 三种构建均通过. SSH / VNC / RDP / NVStream 端到端在 Honor 折叠屏上验证.

## 9. Phase 1+ 路线(预告)

Phase 1 最小集: `emulatorview-release.aar` 集成, 真实 `TermSession` 驱动 `UltraCompactBitmapData.updateBitmap`, `ConfigSSH` 落盘设置, 字号/字体设置 UI.

Phase 2 真实网络: 引入 `connectbot` 的 `SSHClient` / `Session` 桥接 `TermSession` stdin/stdout, 用户在终端上真正输入命令.

Phase 3 公钥/主机指纹: 复用 `pubkeyGenerator` + `Utils` 里的 VNC-over-SSH 主机指纹接受对话框.

---

## 10. Phase 1 实际数据流 + 踩坑(2026-06 实施后新增)

> §3 §4 是"想做什么";本节是"做完之后真实的形状,以及踩过的坑"。Phase 2 直接读这一节,不要再走 §3 的旧路径。

### 10.1 AAR 放 `remoteClientLib/` 而非 `bVNC/libs/`

最初按 `bVNC/libs/emulatorview-release.aar` 走,`bVNC` 模块自己编译 OK,但 `:aRDP-app:assembleGplayDebug` 失败:

```
Could not find :emulatorview-release:.
Searched in the following locations:
   - file:/home/sefler/remote-desktop-clients/remoteClientLib/emulatorview-release.aar
```

原因:`aRDP-app` 继承根 `build.gradle` 的 `allprojects { flatDir { dirs 'remoteClientLib' } }`,对 `bVNC/libs/` 一无所知。`implementation` 不会让 transitive 消费者看到 AAR 的 resolve 来源。

**结论**:
- AAR 放 `remoteClientLib/emulatorview-release.aar`(与 `android-database-sqlcipher-4.5.3.aar` 同居,这是 `remoteClientLib` 早就是"第三方依赖"模块的信号)
- 依赖声明放 `remoteClientLib/build.gradle`,**用 `api` 不用 `implementation`**——否则 bVNC 拿不到 `TermSession` 类
- `bVNC/build.gradle` 的 `flatDir { dirs 'libs' }` 块专门为这个 AAR 加的,现在删掉
- `bVNC/libs/` 现在只剩 `contentxml.jar` / `db.jar`(bVNC-app-local)

### 10.2 包私有桥接: 同包名继承访问

AAR 里这些类/方法都是 **package-private**:
- `TermSession.getTranscriptScreen()` / `getEmulator()`
- `TranscriptScreen.drawText(int row, Canvas, float x, float y, BaseTextRenderer, int cursorX, int selX1, int selX2, String imeText, int cursorMode)`
- `TerminalEmulator.getCursorRow()` / `getCursorCol()` / `getShowCursor()` / `getReverseVideo()`
- `PaintRenderer` / `BaseTextRenderer` / `BaseTextRenderer.defaultColorScheme`

Java 的 `package-private` 跨 JAR/AAR 是**按包名**生效,不要求同一模块/同一 classloader。所以把 `TermRenderHelper.java` 放到 `remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/`(包名 = AAR 包名),它就自动拿到包私有访问,不需要 `reflect`、不需要重新编译 AAR、不需要改 AAR 源码。

**坑**:
- 文件**必须**在源码包路径 `jackpal/androidterm/emulatorview/`,写 `com.qihua.bVNC.ssh.TermRenderHelper` 不行,即使靠 `import` 也不行(包私有是编译期按"自己所在包"判断的)
- 桥接类本身要 `public`,否则外部包连"new TermRenderHelper()" 都做不了
- 同理,如果 Phase 1.5+ 需要再碰 AAR 内部,就在 `remoteClientLib` 同包里继续加,不要新开包

### 10.3 `FakeShellLoopback` 双 pipe 设计

不是单 pipe。`TermSession.setTermIn/setTermOut` 要的是**两个流**(tty 双向):
- `TermSession.getTermIn`  ←  外壳(shell) 写出来、用户看到
- `TermSession.getTermOut` →  用户键入、外壳(shell) 读到

每个方向用一对 `PipedInputStream` / `PipedOutputStream`(8KB,默认 1KB 太小):

```
user key  →  termSession.write(int)  →  loopback.termOut(PipedOutputStream)
                                              ↓ (echo thread 读)
                                              ↓ bytes / "\r\n$ "
                                              ↓
user 看到 ←  termSession.getTermIn  ←  loopback.termIn(PipedInputStream)
```

**坑**:
- `PipedInputStream(8 * 1024)` ctor 第二参才是 buffer;`(int)` 单参只是 1KB
- `connect()` 必须在构造时调,不能在 `start()` 之后调——彼时一个流已用
- `echoLoop` 读 0/-1/IOException 三种退出条件要分清:`running=false` 时静默退出,其它情况要 log
- `close()` 必须:设 `running=false` → 关 4 路 stream → `interrupt()` 线程。少一步 TermSession 内部的 reader 线程会永远 `read()` 阻塞

### 10.4 渲染:用 AAR 的 `TranscriptScreen.drawText` 而非自己 `drawText`

§3 旧版草图设想自己 `for row, for col, c.drawText(String.valueOf(screen[row][col]), ...)`。这有几个问题:
1. `session.getEmulatorScreen().getScreenChars()` 在新版 AAR 里签名变了,容易踩
2. 自己管字体/颜色/光标/选中态/IME 提示,这些 `BaseTextRenderer` 都做好了
3. `PaintRenderer` 还负责 `getCharacterWidth/getCharacterHeight`——自己再量一次会跟它不一致

Phase 1 的正确做法:
- `TermRenderHelper.render` 调 `screen.drawText(row, canvas, x, y, renderer, cursorX, -1, -1, "", 0)`(`cursorX=-1` 表示该行不画光标,见 §10.4.1)
- `PaintRenderer(fontSizePx, BaseTextRenderer.defaultColorScheme)` 构造一次,fontSize 变了再换
- `TermRenderHelper.probe(fontSizePx)` 是首帧前的预热:不开新 Bitmap,用 1×1 ALPHA_8 假 Canvas 调 `computeCols/Rows` 算出 cols/rows 给 `termSession.updateSize`

#### 10.4.1 光标画在哪一行

- `emu.getCursorRow()` 是基于当前 active screen 顶部的相对行(0..rows-1)
- 循环里 `row == cy` 时 `cursorX = cx`,否则 `cursorX = -1`
- `TranscriptScreen.drawText` 内部根据 `cursorX` 决定光标颜色反转

### 10.5 键盘:用 `TermSession.write(int)` 走 UTF-8 编码

§3 旧版草图设想 `termIn.write(ansi_byte)` 直接写 `OutputStream`。但 `TermSession.write(int codePoint)` 公开方法内部已经做了:
- ASCII 走 fast path:单字节
- 非 ASCII 用 UTF-8 编码再走 `write(byte[],int,int)`

所以键盘代码直接 `termSession.write(codePoint)` 即可,不要碰 `termIn`。

**特殊键映射**:
- `KEYCODE_ENTER` → `\r`(`\n` 也行,TermSession 都能 handle,但 fake shell 的 echo 逻辑明确比对 `\r`/`\n`,一致用 `\r`)
- `KEYCODE_DEL` → `\x7f`(ASCII DEL,不是 `\b`(`\x08`)。TTY 规范里 erase 用 DEL,BACKSPACE 在某些 emulator 里是另外含义)
- `KEYCODE_TAB` → `\t`
- `KEYCODE_ESCAPE` → `\x1b`

**坑**:
- `ACTION_UP` 必须吞掉(return true),不让 IME/host 重新处理
- `evt.getUnicodeChar(metaState)` 返回 0 表示非可打印,放行(return false),不要写 0x00
- `additionalMetaState` 是上层传入的(比如 on-screen Ctrl 键),跟 `evt.getMetaState()` 或起来,例如 `evt.getUnicodeChar(evt.getMetaState() | additionalMetaState)`

### 10.6 单一重绘路径:UpdateCallback (★ 修订)

**早版(2026-06 初期)**:heartbeat 30 FPS + UpdateCallback 双路径。后被证明**有害**——heartbeat 与 UpdateCallback 在打字时争着画 `reDraw`,经 `DrawWorker.addTask`(10ms 节流)与 `DrawWorker.run`(13ms 节流)过滤后,真正画到屏幕的节奏变得不规则,用户感受是"闪烁"。

**当前(2026-06 修订后)**:只剩 UpdateCallback 一条路径。AAR 的 `TerminalEmulator` 没有 cursor 自动 blink(只有 ANSI DECTCEM 切显隐,不会自走),所以"屏幕没变"时本来就不该重画——heartbeat 是 Phase 0 ("Hello SSH" 占位符、无 UpdateCallback 可用)的遗留物,Phase 1 有 `setUpdateCallback` 之后变成纯冗余。

| 触发源 | 节奏 | 用途 |
|---|---|---|
| `termSession.setUpdateCallback(...)` | 屏幕变时立刻 | 唯一驱动:打字、回显、光标移动、ANSI blink 属性变化 |
| `openRenderer()` 末行 `paintAndRedraw()` | 连接建立时一次性 | **首帧 trigger**:替代被删的 heartbeat,保证 surface 在 WELCOME 字节到达前就刷一次(空 grid);之后 WELCOME 字节通过 pipe 触发 UpdateCallback 再画一遍 banner |
| `onSurfaceCreated` 直接调 `paintAndRedraw()` | surface 重建时一次性 | 兜底:surface 重建本身不触发 UpdateCallback,得手动画当前状态 |

```java
private void paintAndRedraw() {
    if (renderer == null) return;
    renderer.renderInto(canvas.bitmapData.mbitmap);
    canvas.reDraw(0, 0, canvas.rfbconn.framebufferWidth(), canvas.rfbconn.framebufferHeight());
}
```

**坑**:
- `canvas.reDraw` 把 `DrawTask` 投到 `drawWorker` 队列,线程安全,在主线程直接调没问题
- `termSession.setUpdateCallback` 的 `onUpdate()` 已经在主线程(TermSession 内部 `mMsgHandler = new Handler(Looper.getMainLooper())`),所以回调里**直接** paint,不要再 `post`
- 单一路径下:`DrawWorker` 的 10ms/13ms 节流只过滤"同帧内重复 enqueue",不会跨用户操作拉长延迟。打字节奏 = 屏幕刷新节奏
- idle 终端(无屏幕变化)= 零重绘 = 静态画面。**这是对的**,不是 bug;终端不动就不该重画

### 10.7 折叠/展开:重建 renderer,放弃 TermSession 状态

`SshConnectionInitializer.rebuildFramebuffer()` 流程:
1. `canvas.rfbconn = new SshCommunicator(...)`  ← 新 fbW/fbH
2. `canvas.pointer.setProtocomm(canvas.rfbconn)` ← 沿用 §8.15.2 setter
3. `canvas.reallocateDrawable(w, h)` ← 新 mbitmap
4. **老 renderer.close()** ← 关键,否则老 TermSession 内部的 reader 线程 + 老 FakeShellLoopback 的 echo 线程 + 4 个 pipe 都会泄漏
5. `renderer = new SshTerminalRenderer(density)`
6. `keyboard.setTermSession(renderer.getTermSession())` ← 换 keyboard 目标
7. `openRenderer()` ← `renderer.open(pxW, pxH, sshUpdateRunnable)` + 首帧 `paintAndRedraw()`(见 §10.6 表内首行 trigger)

Phase 1 接受:用户看到屏幕被清空,重新显示 welcome banner(因为新的 `FakeShellLoopback` 又写了一遍 WELCOME)。

**Phase 2+ 改进方向**:
- 保留老 TermSession,在新 renderer 里 setTermIn/setTermOut 重新指向新 pipe;但 TermSession.updateSize 后会清屏,所以"保留历史"得在 updateSize 前 snapshot transcript
- 短期最简实现:每次重建前 `termSession.finish()` + 新建,接受清屏(Phase 1 就这么干)

### 10.8 `Constants.SSH_FONT_SIZE_DP = 14f` 走 density

§8.15.3/8.15.4 已经定下:`textSize = SSH_FONT_SIZE_DP * displayMetrics.density`。Phase 1 在 `SshTerminalRenderer.fontSizePx()` 复用:

```java
private int fontSizePx() {
    return Math.max(1, Math.round(Constants.SSH_FONT_SIZE_DP * density));
}
```

`density` 在 `initialize()` 时从 `ctx.getResources().getDisplayMetrics().density` 取一次存字段,然后 renderer 整个生命周期不变。fold/unfold 不重新读——cover(~430 PPI, density 2.7)与 main(~340 PPI, density 2.1)之间是同一次折叠会话,不会变。

**为什么不用 `paint.measureText("M")`**:
- `Paint.measureText` 受 Paint 的 `setTextSize` / `setTypeface` / `setLetterSpacing` 等影响,跟 AAR 内部的 `PaintRenderer.getCharacterWidth` 不一定一致
- 直接复用 `PaintRenderer` 的测量,保证 cols/rows 跟渲染时一致

### 10.9 `emptyCanvasOf(int)` 的小技巧

`computeCols/Rows` 要的是 `Canvas.getWidth()` / `getHeight()`,但 `open()` 阶段没有真 Bitmap。`SshTerminalRenderer` 用一个 1×1 `ALPHA_8` 假 Bitmap 包到 Canvas 里,只喂宽度/高度数字。开销是一次 `Bitmap.createBitmap(max(1,n), max(1,n), ALPHA_8)`,首帧前一次,忽略不计。

### 10.10 `bVNC` 的 `flatDir { dirs 'libs' }` 现在空了

之前为这个 AAR 加的 `bVNC/build.gradle` 块:
```gradle
flatDir { dirs 'libs' }
```
AAR 搬走后这个块没用了。**已删**(避免误导后来人)。

### 10.11 LSP stale diagnostics 不是错误

写完 Phase 1 后,LSP 持续报:
```
SshTerminalRenderer.java is not on the classpath of project bVNC, only syntax errors are reported
```
伴随 `RemoteSshKeyboard.java` / `SshConnectionInitializer.java` 也有同样警告。这是 LSP cache 没跟上 gradle 依赖变化。**以 `./gradlew :bVNC:compileGplayReleaseJavaWithJavac` 为准**。LSP 警告只要没有红线 syntax error,都可以忽略。

### 10.12 Phase 1 编译 + 打包命令

```bash
# 仅编译 bVNC(快,定位错误方便)
./gradlew :bVNC:compileGplayReleaseJavaWithJavac   # 17s, BUILD SUCCESSFUL

# 完整打包(确认 AAR transitive 解析、APK 能出)
./gradlew :aRDP-app:assembleGplayDebug            # 11s, BUILD SUCCESSFUL

# APK 位置
ls -la aRDP-app/build/outputs/apk/gplay/debug/
# -rw-rw-r-- 1 sefler sefler 29976248  aRDP-app-gplay-debug.apk
```

### 10.13 Phase 1 验收(在 Honor 折叠屏,§8.16 同款机器)

- [ ] 启动 app → tap SSH tile → 终端开屏即显示 "Hello from fake shell!\nType something and press Enter.\n$ "(不是 "Hello SSH" 占位符)
- [ ] 软键盘输入 "ls -la",每个字符逐个出现在 `$ ` 之后
- [ ] 回车:光标下移一行,新行出现 "$ " 提示符
- [ ] 多次回车,确认滚动正常
- [ ] Backspace 删前一个字符(只删自己输入的,不会越过 `$ `)
- [ ] 折叠→展开:画面重新出现,无黑边,字号物理大小不变
- [ ] 切后台→切回前台:画面保留(来自 mbitmap,不是新画)
- [ ] VNC/RDP 真实连接不受影响(§8.17 重构回归检查)
- [ ] logcat 无 `SshConnectionInitializer` / `SshTerminalRenderer` / `RemoteSshKeyboard` 的 ERROR/WTF

Phase 1 验收 = 上面 9 条全过。


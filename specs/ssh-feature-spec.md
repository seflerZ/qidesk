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
              │  UltraCompactBitmapData.updateBitmap(x, y, w, h)       │
              │   (复用 RDP 的) Phase 0: 调 RemoteCanvas.drawSsh    │
              │   PlaceholderIntoBitmap() 写硬编码 "Hello SSH"        │
              │   Phase 1+: 由 TermSession 驱动, 同样路径            │
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

**关键点**:`EmulatorView`(来自 Android-Terminal-Emulator)是一个自定义 `View` 组件——**我们不用它**。`TermSession` 才是与 View 无关的纯逻辑层。SSH 的"绘制"通过 `UltraCompactBitmapData`(复用 RDP 的,见 §8.13)写到 mbitmap,确保与项目其他协议共用一张 mbitmap 和同一个 DrawWorker。

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
│   │   ├── SshTerminalRenderer.java   # 新增:把 TermSession 状态画到 Canvas(纯函数,Phase 0 stub 画 "Hello SSH",Phase 1+ 接 TermSession)
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
| `RfbConnectable` 基类 | (复用) `UltraCompactBitmapData` |
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

- [x] 新建 `SshConnectable extends RfbConnectable`,framebuffer 尺寸 = `displayRect × SSH_SMART_RESOLUTION_FACTOR`,desktopName = "SSH Terminal"
- [x] 在 `RemoteCanvas.reallocateDrawable()` 把 SSH 加入 `if (isRdp | isNvStream | isSsh)` 分支,复用 `UltraCompactBitmapData`(见 §8.13)
- [x] 在 `RemoteCanvas.startSshConnection()` 调 `drawSshPlaceholderIntoBitmap()` 用 `Canvas.drawText("Hello SSH", ...)` 写 mbitmap
- [ ] 临时在 `ConnectionGridActivity.addNewConnection` 删掉 SSH guard(测试用)
- [ ] 临时在 `Utils.getConnectionSetupClass("ssh")` 返回 `RemoteCanvasActivity.class`(绕开 ConfigSSH,后续再补)
- [ ] `adb` 跑通后,屏幕上应看到"Hello SSH"

**Phase 0 完成 = 架构可行性验证**。如果 mbitmap 上的字符能跟着 `DrawWorker` 正确绘制到屏幕,后续 1/2 阶段就是把"硬编码字符串"换成"动态 TermSession 状态"。

### Phase 1 — 接入 TermSession(本地伪终端)

**目标**:引入 Android-Terminal-Emulator 的 `TermSession`,把硬编码字符串换成"TermSession 内存中的状态",并接受键盘输入(但**不接 SSH 网络**)。

- [ ] 把 `emulatorview-release.aar` 放到 `bVNC/libs/`
- [ ] `bVNC/build.gradle` 加 `implementation files('libs/emulatorview-release.aar')`
- [ ] `SshTerminalRenderer` 内部创建 `TermSession`(用一个本地 `PipedInputStream` / `PipedOutputStream` 假装是 SSH 通道)
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
    @Override public void requestUpdate(boolean incremental) { /* Phase 1+: 触发 UltraCompactBitmapData.reDraw */ }
}
```

### 4.2 `SshTerminalRenderer`(Phase 1+ 真实渲染器)

> Phase 0 没用这个类,直接调 `RemoteCanvas.drawSshPlaceholderIntoBitmap()` 画 "Hello SSH". Phase 1+ 抽出独立类便于测试.

```java
public class SshTerminalRenderer {
    private TermSession session;
    private final Paint textPaint = new Paint();
    private final Paint bgPaint = new Paint();
    private final Typeface mono = Typeface.MONOSPACE;

    SshTerminalRenderer(TermSession session) {
        this.session = session;
        textPaint.setTypeface(mono);
        textPaint.setColor(0xFF839496);   // Solarized foreground
        bgPaint.setColor(0xFF002B36);    // Solarized background
    }

    /** 画到指定的 mbitmap. 由 RemoteCanvas 拿到 SshConnectable 的 size 后调用. */
    public void renderInto(Bitmap target) {
        Canvas c = new Canvas(target);
        c.drawRect(0, 0, target.getWidth(), target.getHeight(), bgPaint);
        // 读 TermSession 的当前文本状态 → 按行/列 drawText
        char[][] screen = session.getEmulatorScreen().getScreenChars();  // API 视实际调整
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                c.drawText(String.valueOf(screen[row][col]), col * charW, (row + 1) * charH, textPaint);
            }
        }
    }
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

## 9. Phase 1+ 路线(预告)

Phase 1 最小集: `emulatorview-release.aar` 集成, 真实 `TermSession` 驱动 `UltraCompactBitmapData.updateBitmap`, `ConfigSSH` 落盘设置, 字号/字体设置 UI.

Phase 2 真实网络: 引入 `connectbot` 的 `SSHClient` / `Session` 桥接 `TermSession` stdin/stdout, 用户在终端上真正输入命令.

Phase 3 公钥/主机指纹: 复用 `pubkeyGenerator` + `Utils` 里的 VNC-over-SSH 主机指纹接受对话框.


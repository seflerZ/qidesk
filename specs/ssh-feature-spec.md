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
implementation files('libs/trilead-ssh2-1.0.0-build222.jar')
```

> ⚠️ **版本号修正(2026-06)**:原规格书写的 `1.2.0` 在 mavenCentral / jcenter / aliyun / jboss nexus 等所有仓库**均不存在**(HTTP 404)。该版本号是规格书笔误。真实存在的最高稳定版本是 **`1.0.0-build222`**(2025-01 jenkinsci/trilead-ssh2 维护版,248KB),与 `SSHConnection.java` 已有的 import 路径 `com.trilead.ssh2.*` 完全兼容,API 不变。
>
> 原规格书指定的 Apache MINA sshd **不再使用**。理由:`SSHConnection.java`(753 行)已基于 trilead 实现了完整的连接 / 认证 / 端口跳转 / KnownHosts / InteractiveCallback / 密码+公私钥认证 逻辑,改用 MINA 等于把这部分全部作废。沿用 trilead 可节省 60% 的协议层工作量。
> MINA 的优势(更新活跃、对现代 SSH 特性支持好)对本项目影响有限,因为我们只需要密码 / 密钥认证 + 一个 shell channel。trilead-ssh2 build222 已经覆盖了 RSA / ECDSA / Ed25519 等所有现代密钥类型(底层走 JCE / BouncyCastle)。

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

### Phase 2 — 接入真 SSH(trilead,★ 修订 2026-06) ✅ 设备验收通过(2026-06-24)

**目标**:把 Phase 1 的本地 `PipedInputStream` 换成 trilead `Session.getStdout()/getStdin()`,真连 SSH 服务器。**架构上对齐 RDP / NVStream 的"独立 worker + drawBitmap"模型**——把 trilead 线程当成独立 worker,TermSession 是 worker 内部的状态机,worker 状态变化时回调 `renderInto(mbitmap)` + `reDraw`,主程序只展示位图。

**trilead 版本修正**(★ 进一步修订 2026-06-24):规格书旧版的 `trilead-ssh2-1.2.0.jar` 在 mavenCentral / jcenter / jboss nexus / aliyun 等仓库**均不存在**(404)。该版本号是规格书笔误。**真实情况是**:项目里 `pubkeyGenerator/build.gradle` 已经声明 `api 'org.connectbot:sshlib:2.2.20'`——connectbot 维护的 trilead-ssh2 fork,包名仍是 `com.trilead.ssh2.*`,API 完全兼容(build222 的 `Connection.setCompression` 和 `(String, KeyPair)` 形式的 `authenticateWithPublicKey` 已移除,需走 `(String, File, String)` + 写 temp file)。Phase 2 **不要单独 vendor trilead jar**(会导致与 sshlib duplicate class),**只在 `bVNC/build.gradle` 加 `api 'org.connectbot:sshlib:2.2.20'` 即可**。

**架构对照**(RDP / NVStream vs SSH Phase 2):
| 阶段 | RDP | NVStream | SSH Phase 2 |
|---|---|---|---|
| 独立 worker | FreeRDP native process | Moonlight native decoder | **trilead background thread**(我们启) |
| 内部状态机 | FreeRDP C 状态 | MediaCodec decoder buffer | **`TermSession` VT100 状态机** |
| Worker 状态变化的回调 | `OnGraphicsUpdate(x,y,w,h)`(JNI) | `onGraphicsUpdate(Surface,...)` Listener | **`TermSession.setUpdateCallback(...)`**(AAR 内置) |
| 写位图的方式 | `LibFreeRDP.updateGraphics(inst, bitmap, ...)` JNI in-place 写 | `PixelCopy.request(surface, bitmap, ...)` | **`SshTerminalRenderer.renderInto(mbitmap)`**(状态→位图) |
| 触发画屏 | `viewable.reDraw(drawTask)` | `viewable.reDraw(drawTask)` | **`canvas.reDraw(0,0,fbW,fbH)`** |
| 选用的 BitmapData | `UltraCompactBitmapData` | 同 | 同(§10.16) |

**为什么 SSH 还需要 pipe**(RDP/NVStream 不需要):
- `TermSession` 不接受 `null` 流,必须 `setTermIn/Out` 时给到非 null
- 网络断时若 `TermSession` 从 `session.getStdout()` 读到 -1,会立即 `finish()` 整个 session,键盘失灵
- pipe 作为**缓冲层**让 `TermSession` 永远只从 pipe 读,网络断时 pump 退出但 pipe 不关,`TermSession` 阻塞直到 teardown 主动关 pipe

**Phase 2 任务清单**(★ 重组,2026-06):
- [x] 1. 下载 `trilead-ssh2-1.0.0-build222.jar`(实际版本号,1.2.0 不存在)到 `bVNC/libs/`
- [x] 2. `bVNC/build.gradle` 加 `implementation files('libs/trilead-ssh2-1.0.0-build222.jar')`
- [x] 3. `SSHConnection.java` 新增 `public Session getSession()` + `public boolean openShellSession() throws Exception`(`openShellSession` 内部复用现成的 `connect()` / `verifyHostKey()` / `attemptSshPasswordAuthentication()` / `authenticateWithPubKey()` 路径,末尾加 `connection.openSession()` + `session.startShell()`(非 `execCommand`))
- [x] 4. 新建 `bVNC/.../ssh/SshShellChannel.java` —— 持 trilead `Session` + 双 pipe + 后台 pump 线程,提供与 `FakeShellLoopback` 同 4 方法接口(`getTerminalIn/Out` / `start` / `close`)
- [x] 5. `SshTerminalRenderer` 构造器签名改:`(float density, SshShellChannel channel)`,内部用 channel 流替代 FakeShellLoopback 流;`close()` 调 `channel.close()`
- [x] 6. `SshConnectionInitializer` 加 `sshConnection` / `channel` / `connectThread` / `connectStarted` 字段 + `ReentrantLock`;`initialize()` 建 SSHConnection + SshShellChannel + 改后的 SshTerminalRenderer;`start()` 启"SSH-Connect"线程调 `doConnect()`(运行 `openShellSession()` + `channel.setSession()`);`teardown()` 按顺序清理
- [x] 7. `SshCommunicator.close()` 调 `sshConnectionRef.terminateSSHTunnel()`(package-private setter 注入)
- [x] 8. 删除 `bVNC/.../ssh/FakeShellLoopback.java`
- [x] 9. `ConfigSSH.java` 改硬编码 `sshServer=10.0.2.2`(其它不动,最小可演示)
- [x] 10. **可演示**:真连 SSH 服务器(`ssh user@10.0.2.2` 或局域网),执行 `uname -a` 看到远端内核信息

**Phase 2 暂不做**(留 Phase 3):
- `ConfigSSH extends MainConfiguration` 真配置页(带 UI 字段、布局 `config_ssh.xml`)
- 新建 `SshTerminalActivity extends RemoteCanvasActivity`(最小演示继续用现有 `RemoteCanvasActivity`)
- `ssh_terminal.xml` / `ssh_terminal_menu.xml` 资源

### Phase 3 — 完善

#### §3.7 — 终端状态机替换为 libvterm(JNI)(2026-06-26,新增,详见 [`libvterm-integration.md`](./libvterm-integration.md))

**目标**:Phase 3.1 完成后 vim/less/htop 退出时双重 prompt / 残留内容等 AAR 缺陷已无法通过修 AAR 解决,换 libvterm(TragicWarrior 维护,2026-06-26 昨天还在推)。

**完整 spec 见** [`specs/libvterm-integration.md`](./libvterm-integration.md),本节只列差异点。

**架构决策**:
- 业务层(`SshConnectionInitializer` / `SshShellChannel` / `SshCommunicator` / `SshTerminalConnection` / `ConfigSSH` / 折叠屏 / paint HandlerThread / 字体加载) **0 改动**
- 状态机层(`SshTerminalRenderer` / `TermRenderHelper`) **整体重写**
- AAR(`emulatorview-release.aar` + `TermRenderHelper.java`) **删除**
- 引入 NDK / CMake(项目首次跑 native build)

**关键技术点**:
- libvterm C + JNI 桥(~400 行 C)
- 状态机不主动回调 Java,Java 在 paint 线程上 `pollDirty()` 拉
- `VTermCanvasRenderer` 自己用 `TextPaint` + `Canvas.drawText`,不走 AAR 的 `BaseTextRenderer`
- 折叠屏 / paint HandlerThread / 字体 / 认证 全部沿用

**预期效果**:
- vim / less / htop 退出后**无双重 prompt / 残留**
- zsh completion **不错位**
- OSC 52 / OSC 4 / 鼠标 SGR 模式 等 AAR 未实现功能 libvterm 标准支持

**工期预估**:6-9 工作日(不含设备调试)

**不做**:OSC 52 剪贴板 / OSC 4 颜色查询 / 鼠标 / 字体回退(留 Phase 4 评估 connectbot/termlib)

#### §3.1 — ConfigSSH 真配置页 + SSH 终端独立协议(2026-06-25) ✅

**目标**:把 Phase 2 的硬编码 `ConfigSSH.java` 改成 `MainConfiguration` 子类,跟 `ConfigVNC` / `ConfigRDP` 同形态;同时把 SSH 终端从 `SSHConnection`(VNC-over-SSH 隧道通用层)抽离,变成**独立协议**走 VNC-style 字段。

**架构决策(★ 关键)**:
- SSH 终端 = 独立协议 = 跟 RDP / NVStream 同字段集(`getAddress/getPort/getUserName/getPassword`)
- SSH 隧道 = VNC/RDP 加密辅助 = 仍用 `SshXxx` 字段
- 字段含义干净:`Address/Port/UserName/Password` 给"独立协议"(RDP/NVStream/SSH terminal);`SshXxx` 永远只给"VNC-over-SSH 隧道"
- 两套 SSH 逻辑长期共存:`SSHConnection`(VNC/RDP 加密工具,不动)+ `SshTerminalConnection`(SSH terminal 直调 trilead,新增)

**Phase 3.1 任务清单(2026-06-25 全部完成)**:
- [x] 1. 新建 `bVNC/src/main/res/layout/main_ssh.xml`(~110 行,只含 SSH terminal 字段:nickname / server / port / user / password / keep-pass,**不**含 VNC/RDP 字段、**不**含 SSH 隧道 pubkey UI)
- [x] 2. 新建 `bVNC/src/main/res/layout-large/main_ssh.xml`(大屏变体,`textAppearance` 改 Large)
- [x] 3. 重写 `bVNC/src/main/java/com/qihua/bVNC/ConfigSSH.java`(60→~110 行,`extends MainConfiguration`,字段源 VNC-style,显式清空 `SshXxx` 字段避免污染隧道语义)
- [x] 4. 新建 `bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalConnection.java`(~140 行,封装 trilead `Connection` + `Session`,公共方法 `connect(user, pwd)` / `openShell(cols, rows)` / `resizePty(cols, rows)` / `close()` / `getCurrentHostKey()`,**不** extends 任何东西直调 trilead)
- [x] 5. 改 `SshShellChannel` 构造器签名(本步**未做**——`SshShellChannel` 已经是 `SshShellChannel()` 无参 + `attach(Session)` 模式,内部不变,只换调用方)
- [x] 6. 改 `SshConnectionInitializer.java`(~80 行改:`SSHConnection sshConnection` → `SshTerminalConnection sshTerminal`;`initialize()` 从 VNC-style 字段读 4 个参数 + `conn.getSshHostKey()` 作 savedHostKey;`doConnect()` 调 `sshTerminal.connect()+openShell()`;`teardown()/rebuildSSHFramebuffer()` 调 `sshTerminal.close()`;`SshCommunicator` setter 同步改)
- [x] 7. 改 `bVNC/src/main/java/com/qihua/bVNC/communicator/SshCommunicator.java`(`setSshConnection(SSHConnection)` → `setSshTerminalConnection(SshTerminalConnection)`,`close()` 调 `sshTerminal.close()`)
- [x] 8. `values/strings.xml` + `values-zh-rCN/strings.xml` 加 `ssh_server_empty` / `ssh_user_empty` 提示
- [x] 9. 编译 + APK 打包验证:`./gradlew :bVNC:compileGplayReleaseJavaWithJavac` BUILD SUCCESSFUL;`./gradlew :aRDP-app:assembleGplayDebug` BUILD SUCCESSFUL(43.3MB,含 `res/layout/main_ssh.xml` + `res/layout-large-v4/main_ssh.xml` + Sarasa Nerd 字体 23.9MB)

**Phase 3.1 暂不做**(留 §3.2+):
- host key fingerprint dialog + KnownHosts 持久化(`SshTerminalConnection` 当前 trust-everything,Phase 3.6 改)
- pubkey 认证 + keyboard-interactive(Phase 3.6)
- 密钥 UI(`Manage Key` 按钮,Phase 3.6)

#### §3.2+ — 后续(待排期)

- [ ] 密钥认证(RSA / ECDSA / Ed25519)+ 口令短语 UI(`MainConfiguration` + `Manage Key` 按钮;库侧需扩展 `SshTerminalConnection` 走 `(String, File, String)` 签名)
- [ ] Host key fingerprint dialog(首次连接弹 dialog,显示 hex fingerprint,Accept/Reject,落库到 `ConnectionBean.getSshHostKey()`)
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
    public TermSession getTermSession()      { return termSession; }   // §10.14 引用,供 fold/unfold 后回查

    @Override
    public boolean processLocalKeyEvent(int keyCode, KeyEvent evt, int additionalMetaState) {
        if (termSession == null) return false;
        // Unicode-text delivery:CJK / 中文 IME 路径。IME 在 ACTION_DOWN 或 ACTION_MULTIPLE
        // 两种 action 上都走这一条(只判 keyCode==0 && chars!=null,不挑 action)。
        String chars = evt.getCharacters();
        if (keyCode == 0 && chars != null && chars.length() > 0) {
            int len = chars.length();
            for (int i = 0; i < len; ) {
                int cp = chars.codePointAt(i);
                termSession.write(cp);
                i += Character.charCount(cp);
            }
            return true;
        }
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
| Phase 2 | 真连 `ssh user@sefler.site:2222`(局域网真服务器,Honor 折叠屏 + emulator `10.0.2.2` 都验证);执行 `ls` / `uname -a` / `pwd` 等命令看到真实输出;`FakeShellLoopback` 整类删除,`SshShellChannel` 接 trilead 流替代;架构对齐 RDP/NVStream 的"独立 worker + drawBitmap"模型(`SSH-Connect` 线程跑 trilead connect+auth+startShell,`SSH-Paint` HandlerThread 跑 `renderInto` 防主线程 paint 卡住 TermSession 写入链路);键盘 / 退格 / 中文 IME / ls 等命令输出全部实时响应,无闪烁;oh-my-zsh unicode glyph 不显示是字体问题(Phase 3 修);`adb logcat` 无 `NetworkOnMainThreadException` / zombie thread(2026-06-24 设备验收) | ✅ 设备验收通过 |
| Phase 3 | UI 化的 `ConfigSSH extends MainConfiguration`(目前硬编码 4 个字段在 `ConfigSSH.java`);文本选择 / 复制粘贴;配色方案(Solarized Dark 等,`ColorScheme` 资源);字体大小调整;网络切换重连;密钥认证 UI(库侧已支持 `SSHConnection.authenticateWithPubKey`,只需 `ConfigSSH` 加切换 UI);oh-my-zsh unicode glyph(用支持 Nerd Font / Powerline 的字体替换 monospace) | ⏳ |
| Phase 3.7 | 终端状态机替换为 libvterm(JNI);vim/less/htop 退出后无双重 prompt / 残留;zsh completion 不错位;为 Phase 3.3 剪贴板 / Phase 3.3+ 鼠标 SGR 模式做基础设施准备。完整 spec 见 [`libvterm-integration.md`](./libvterm-integration.md) | ⏳ |

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
- `teardown(RemoteCanvas)` — 默认 no-op, SSH 覆盖关 TermSession + loopback
- `onSurfaceCreated(RemoteCanvas)` / `onDisplayRectChanged(RemoteCanvas, Rect, Rect)` — 默认 no-op, SSH 覆盖重画当前 grid / 重建 mbitmap
- `reinitialize(RemoteCanvas)` — 默认 = teardown + initialize, Opaque 走相同路径

> **2026-06-14 修订**:早版接口里 `needsRedrawHeartbeat()` / `heartbeatIntervalMs()` 已被删除。SSH 改用 `TermSession.setUpdateCallback` 单一重绘路径(见 §10.6),不再需要 heartbeat 钩子。Phase 0 早期 heartbeat + 占位符实现早已移除。

**5 个实现**(总计 ~990 行, 替换原 `RemoteCanvas` 中 ~750 行协议代码):
- `SshConnectionInitializer.java`(233 行)— Phase 1 实装, TermSession 驱动 + UpdateCallback 单路径, fold/unfold 重建 TermSession
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

### 10.14 中文 IME 输入路径(2026-06-14 现状)

**最终路径(单条)**:所有 IME 输入 —— 包括中文 / CJK 候选 —— 走 `RemoteSshKeyboard.processLocalKeyEvent`(`bVNC/.../input/RemoteSshKeyboard.java:50-102`)。`RemoteCanvas.onCreateInputConnection` **没有 SSH 分支**,所有协议都用默认 `BaseInputConnection`(即"丢 InputConnection 文本,只走 KeyEvent")。

中文能投到 TermSession 的关键代码在 `processLocalKeyEvent` 顶部:

```java
if (keyCode == 0 && chars != null && chars.length() > 0) {
    int len = chars.length();
    for (int i = 0; i < len; ) {
        int cp = chars.codePointAt(i);
        termSession.write(cp);
        i += Character.charCount(cp);
    }
    return true;
}
```

- `keyCode == 0` 的 KeyEvent 由 IME 用作"unicode 文本投递"通道(部分 IME 在 `ACTION_DOWN`、部分在 `ACTION_MULTIPLE`);两种 action 都走这个分支
- 后续 `switch` 处理 `KEYCODE_ENTER/DEL/TAB/ESCAPE` 映射到 ANSI 字节,普通按键走 `evt.getUnicodeChar(metaState)`
- 这一条路径在 Honor 折叠屏上验证过中英文 + 软硬键盘均能进入 TermSession

**历史**:
- 2026-06-12 `7e4be761 CJK input supported` 曾新增 `bVNC/.../input/SshInputConnection.java`,通过覆盖 `BaseInputConnection` 的 `commitText` / `setComposingText` / `finishComposingText` 三个回调把 IME 文本转发到 TermSession
- 2026-06-14 `1c9ce769 remove the so call SSH input connection` 删除了 `SshInputConnection.java` 与 `RemoteCanvas.onCreateInputConnection` 里的 SSH 分支。理由:IME 投中文的另一种形式(`KeyEvent` with `keyCode=0` + `getCharacters()="你好"`)被 `processLocalKeyEvent` 的 `if (keyCode == 0 && chars != null && chars.length() > 0)` 分支自然接住,不需要专门的 InputConnection 子类
- 保留:`RemoteSshKeyboard` 仍提供 `setTermSession(TermSession)` / `getTermSession()`,供 `SshConnectionInitializer` 在 fold/unfold 时换绑当前活跃的 TermSession

**副作用**:走 `KeyEvent` 通道意味着 IME 投的中文不会经过 fake shell 的 echo 翻倍(`commitText` 那条路径会),但 IME 自身的"pinyin 中间态"(`n` / `ni` / `nih`)也不会进终端 —— 在终端里看到的就是 IME 给的最终文本。等 Phase 2 换真 SSH channel 时行为不变。

**文件**(2026-06-14 状态):
- `bVNC/.../input/RemoteSshKeyboard.java` — 含 `processLocalKeyEvent` 的 `keyCode == 0` unicode 分支,`+ setTermSession/getTermSession`(给 initializer 换绑用),无 `Log.i`
- `bVNC/.../RemoteCanvas.java` — `onCreateInputConnection` 维持 `URI | NO_SUGGESTIONS`,无 SSH 分支
- (已删)`bVNC/.../input/SshInputConnection.java` — `1c9ce769` 删除


---

## 10.15-10.19 Phase 2 教训(2026-06-24,设备验收后新增)

> Phase 1 spec §10 写的是"键盘 → TermSession → 位图"管道工程;Phase 2 spec §3 写的是"删 fake shell,接 trilead"。**真正把 Phase 2 从"连得上"做到"敲命令不闪、退格正常"的,是下面这五条踩坑**,每条都不是"想做什么",是"做完之后真实的形状,以及踩过的坑"。

### 10.15 trilead-ssh2 版本号修正(规格书旧版错)

规格书 §3 旧版写的 `trilead-ssh2-1.2.0.jar` **在 mavenCentral / jcenter / jboss nexus / aliyun 全部 404**——该版本号是规格书笔误。**真正情况**:项目里 `pubkeyGenerator/build.gradle:40` 已经声明 `api 'org.connectbot:sshlib:2.2.20'`——connectbot 维护的 trilead-ssh2 fork,包名仍是 `com.trilead.ssh2.*`,API 与 build222 完全兼容。**Phase 2 不要手动下载 trilead jar**(会导致 `:aRDP-app:assembleGplayDebug` 报 `Duplicate class com.trilead.ssh2.transport.TransportManager`)。正确做法是 `bVNC/build.gradle` 加一行 `api 'org.connectbot:sshlib:2.2.20'`。

### 10.16 `Session.startShell()` 之前必须 `requestPTY`(★ 关键)

`com.trilead.ssh2.Session.startShell()` **默认申请 dumb PTY**(无 termtype、无尺寸、无 terminal modes)。服务器 shell 在 dumb PTY 下表现非常奇怪:
- **缩进乱**:dumb PTY 没 advertised width,服务器假定 80 字符 wrap;我们屏幕 200+ 字符宽,行长度计算错位
- **输入"无作用"**:dumb PTY 下 echo 行为不定,部分 shell 配成无 echo,你按了键看不到,误以为没生效;换行转换规则跟 Android 习惯的 CR 不一致,Enter 行为错乱

修复(`SSHConnection.java`):
```java
public boolean openShellSession() throws Exception {
    // ... connect + auth ...
    session = connection.openSession();
    requestShellPty(session);  // ← 必须先于 startShell()
    session.startShell();
}

private void requestShellPty(Session s) throws IOException {
    try {
        s.requestPTY("xterm-256color", 80, 24, 640, 480, null);
    } catch (IOException e) {
        try { s.requestPTY("xterm", 80, 24, 0, 0, null); }
        catch (IOException e2) { /* fall back to dumb */ }
    }
}
```

附带 fold/unfold 后调 `SSHConnection.resizePty(cols, rows)` 通过 `Session.resizePTY(cols, rows, 0, 0)` 通知服务器(配合 `SshTerminalRenderer.GridSizeListener` 把 cols/rows 变化传到 `SshConnectionInitializer`)。

### 10.17 `authenticateWithPublicKey` 签名变更(build222)

trilead build222 / sshlib 2.2.20 移除了 `(String, KeyPair)` 形式的 `authenticateWithPublicKey`。新签名是 `authenticateWithPublicKey(String, File, String)`(key 文件 + passphrase)。`SSHConnection` 之前从 `PubkeyUtils.decryptAndRecoverKeyPair()` 拿到 `KeyPair`,现在要写一个临时文件再传 File 路径:

```java
private boolean authenticateWithPubKey() throws Exception {
    decryptAndRecoverKey();
    java.io.File keyFile = writeKeyToTempFile(sshPrivKey);
    try {
        String pp = (passphrase != null) ? passphrase : "";
        return connection.authenticateWithPublicKey(user, keyFile, pp);
    } finally {
        try { keyFile.delete(); } catch (Throwable ignored) {}
    }
}

private static java.io.File writeKeyToTempFile(String openSshKey) throws IOException {
    java.io.File f = java.io.File.createTempFile("sshkey-", ".pem");
    f.deleteOnExit();
    try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(openSshKey); }
    return f;
}
```

同样:`Connection.setCompression(false)` 在 build222 已移除,删掉那行调用即可。

### 10.18 `Session.getStdout()/getStdin()` 不抛 `IOException`

build222 的 `Session.getStdout()` / `getStdin()` 是 **no-throws 签名**(底层 try-catch 内部处理)。**`SshShellChannel.readPumpLoop` / `writePumpLoop` 第一行不能套 `try { src = session.getStdout(); } catch (IOException) {}`**——编译会报 "在相应的 try 语句主体中不能抛出异常错误IOException"。改成单行调用 + null check 即可。

### 10.19 paint 必须在 HandlerThread 后台线程跑(★ 最重要)

**症状(都来自一个根因)**:
- 打字时整个屏幕**第一行内容突然消失(只剩底色),然后很快出现**——第一行字符最长(提示符 `$ user@host:path$`),`PaintRenderer.drawTextRun` 的 `canvas.drawRect` 涂 BG_COLOR → `drawTextSegment` 画字 两步在第一行最明显
- **退格键"无效"**——按一下字符不动,按几下后几个字符一起被删
- 远端突发字节流(如 `ls` 输出)只能看到部分内容,后续字节需再敲命令才能看到

**根因**:`SshTerminalRenderer.renderInto(mbitmap)` 在**主线程同步执行**(10-30ms)。期间:
1. `TermSession.setUpdateCallback` 同步 fire,但主线程在 `renderInto` 里,UpdateCallback **排队**;`renderInto` 完成一次性画所有排队事件 → 用户感知"突现" / "闪烁"
2. **键盘写入链路**(`processLocalKeyEvent → termSession.write → mWriteQueue → mWriterThread → terminalOutSink → writePump → trilead`)与 `renderInto` **抢主线程**;Backspace DEL 字节入 mWriteQueue 后,mWriterThread 异步 drain,但主线程被 renderInto 卡住时,**用户感觉 DEL 没生效,连按几下后远端 shell 一次性收到多个 DEL** → 字符"突然被删"
3. `ls` 输出大量字节 → UpdateCallback 一次 fire 画**当前 TermSession 状态**,但 mid-burst 状态被合并,中间帧看不到

**修复**(`SshConnectionInitializer.java`):
```java
private HandlerThread paintThread;        // "SSH-Paint"
private Handler paintHandler;

private void ensurePaintThread() {
    if (paintThread != null && paintThread.isAlive()) return;
    paintThread = new HandlerThread("SSH-Paint");
    paintThread.start();
    paintHandler = new Handler(paintThread.getLooper());
}

private void stopPaintThread() {
    if (paintHandler != null) paintHandler.removeCallbacksAndMessages(null);
    if (paintThread != null) { paintThread.quitSafely(); paintThread = null; }
    paintHandler = null;
}

// 所有 paintAndRedraw() 调用(心跳 + UpdateCallback + onSurfaceCreated +
// rebuildSSHFramebuffer) 走这一条:
private void paintAndRedraw() { postPaintToBackground(); }

private void postPaintToBackground() {
    if (paintHandler == null) return;
    // 合并多次 paint,只渲染最新 TermSession 状态
    paintHandler.removeCallbacks(paintRunnable);
    paintHandler.post(paintRunnable);
}

private final Runnable paintRunnable = new Runnable() {
    @Override public void run() {
        // ... renderInto → canvas.reDraw ...
    }
};
```

**生命周期**:`ensurePaintThread()` 在 `openRenderer()` 调;`stopPaintThread()` 在 `teardown()` 开头 + `rebuildSSHFramebuffer()` 开头都调(fold/unfold 重建 renderer 时换新 thread)。

**效果**:
- 主线程 paintAndRedraw() **<1ms**(只是 post 一个 runnable),不阻塞 UpdateCallback / 键盘输入
- paint thread 串行处理,**多次 paint 合并成一次**(只渲染最新 TermSession 状态)
- 第一行不再"消失-出现",**自然连续**;Backspace 实时生效;`ls` 完整输出

### 10.20 Phase 2 设备验收(2026-06-24,Honor 折叠屏 / 局域网真服务器)

实测路径(用户操作):
1. `ConfigSSH.java` 硬编码 `setSshServer("sefler.site")` / `setSshPort(2222)` / `setSshUser("sefler")` / `setSshPassword("...")`
2. `pm clear com.qihua.aRDP` 清数据库(让硬编码生效,绕过之前 VNC-over-SSH 残留的 SSH 记录)
3. tap SSH tile → 弹 host key → Accept → 走密码认证(无需再弹密码框,因为字段已硬编码)→ 1-2s 连接
4. 看到 zsh prompt(`~ ❯`),oh-my-zsh 的部分 unicode glyph 显示为方框(字体不支持,Phase 3 改)
5. 输入 `ls` + Enter → **完整**输出文件列表(无半截刷新)
6. 输入中文 → IME 投中文到 TermSession(zsh 收到中文 echo,可能乱码,但 **TermSession 状态机收到完整字节流**)
7. 打字/退格 — **实时响应**,无闪烁
8. 折叠→展开 — 画面重建,shell 状态重置(Phase 1 已接受的行为)

**`adb logcat` 关键行**:
```
SshConnectionInitializer: startHeartbeat: 5 FPS heartbeat started
SshConnectionInitializer: paint stats: 12 paints in last 1000 ms (heartbeat)
SshConnectionInitializer: teardown: closing SSH
SshConnectionInitializer: stopHeartbeat
```

### 10.21 Nerd Font + CJK 字体支持(2026-06-25 新增)

**问题**:Phase 2 设备验收第 4 步"看到 zsh prompt(`~ ❯`),oh-my-zsh 的部分 unicode glyph 显示为方框(字体不支持,Phase 3 改)" —— 当时 `PaintRenderer` 走 `Typeface.MONOSPACE`,系统字体不含 Nerd PUA 码点,U+E0B0 / U+F080 / U+E5FA 等图标显示为空白或方框。

**解决**:打包 `SarasaMonoSCNerd-Regular.ttf`(~24MB,48556 码点,含 ASCII + CJK 29666 字 + Nerd PUA-A 1356 图标)到 `bVNC/src/main/assets/fonts/`,`PaintRenderer` 改用资产字体。

**为什么不打包 CaskaydiaMono Nerd 做 fallback**(实测):
- 两份字体 `unitsPerEm` 不一致(Sarasa 1000 vs CaskaydiaMono 2048)
- 同一字符 'X' 的 advance 不同(Sarasa 500/em vs CaskaydiaMono 1200/em)
- `Typeface.Builder.addFont` API 26+ 合并后,Android 按 codepoint 自动选字体,但每个字符按所属字体的 metrics 渲染 → Nerd 图标列宽比 ASCII 列宽 17%,导致 prompt 错列
- **实测结论:只塞 Sarasa 一份,放弃合并族**;2 个冷门图标(kotlin U+E634、emacs U+E632)显示为空,可接受

**新增类** `bVNC/src/main/java/com/qihua/bVNC/ssh/TermFontFactory.java`:
```java
public static Typeface load(AssetManager assets) {
    // 优先 assets/fonts/SarasaMonoSCNerd-Regular.ttf,失败 fallback Typeface.MONOSPACE
    // 双检锁缓存,Android font loader 启动慢但只跑一次
}
```

**链路**:SshConnectionInitializer 拿 ctx → SshTerminalRenderer(density, channel, ctx) → TermFontFactory.load(ctx.getAssets()) → TermRenderHelper.probe/render(..., typeface) → PaintRenderer(fontSize, scheme, typeface) → mTextPaint.setTypeface(typeface)

**Bold 文本**:仍走 `Paint.setFakeBoldText(true)`(Sarasa Regular 没有 Bold 字形),Nerd 图标略发糊,ASCII 几乎看不出。Italic 同理(VT100 italic 在终端几乎不出现)。

**体积影响**:APK 增加 ~24MB(`assets/fonts/SarasaMonoSCNerd-Regular.ttf`)。

## 11. Phase 2 实现文件清单(2026-06-24 状态,2026-06-25 增补字体)

新增:
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshShellChannel.java`(220 行,双 pipe + 后台 read/write pump)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/TermFontFactory.java`(§10.21,~95 行,从 APK assets 加载 SarasaMonoSCNerd-Regular.ttf,缓存单例,fallback Typeface.MONOSPACE)
- `bVNC/src/main/assets/fonts/SarasaMonoSCNerd-Regular.ttf`(~24MB,Sarasa Mono SC + Nerd Font PUA-A + 完整中日韩,48556 码点)

修改:
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SSHConnection.java`(Phase 2 整体迁移到 ssh 子包,package 改 `com.qihua.bVNC.ssh`)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalRenderer.java`(构造器加 Context 参数,从 ctx 取 Typeface;helper.probe/render 透传 typeface)
- `remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java`(probe/render 加 Typeface 参数,透传给 PaintRenderer)
- `remoteClientLib/jni/libs/deps/Android-Terminal-Emulator/emulatorview/src/main/java/jackpal/androidterm/emulatorview/PaintRenderer.java`(构造器加 Typeface 参数,旧构造器保留为 Typeface.MONOSPACE 兼容入口)
- `bVNC/src/main/java/com/qihua/bVNC/connection/SshConnectionInitializer.java`(创建 SshTerminalRenderer 时传 ctx)
- `bVNC/src/main/java/com/qihua/bVNC/communicator/SshCommunicator.java`(`close()` 调 `terminateSSHTunnel`,加 `setSshConnection` public setter)
- `bVNC/src/main/java/com/qihua/bVNC/RemoteCanvas.java`(`import com.qihua.bVNC.ssh.SSHConnection`)
- `bVNC/src/main/java/com/qihua/bVNC/input/RemoteCanvasHandler.java`(import 路径更新)
- `bVNC/src/main/java/com/qihua/bVNC/ConfigSSH.java`(硬编码 `sshServer/sshPort/sshUser/sshPassword`,Phase 3 改为真配置页)
- `bVNC/build.gradle`(+1 行 `api 'org.connectbot:sshlib:2.2.20'`)

删除:
- `bVNC/src/main/java/com/qihua/bVNC/ssh/FakeShellLoopback.java`(115 行,Phase 1 假 tty 桥,Phase 2 由 `SshShellChannel` 接真 trilead 流替代)

无改动:
- `bVNC/src/main/AndroidManifest.xml`、`aRDP-app/src/main/AndroidManifest.xml`(Phase 0 注册的 `ConfigSSH` 已存在)
- 任何资源文件(`config_ssh.xml` / `ssh_terminal.xml` / `ssh_terminal_menu.xml` 留 Phase 3 真配置页)

## 12. Phase 3.1 实现文件清单(2026-06-25,ConfigSSH 真配置页 + SSH 终端独立协议)

**架构改动**:SSH 终端从 `SSHConnection`(VNC-over-SSH 隧道通用层)抽离,新增 `SshTerminalConnection` 直调 trilead。`SSHConnection.java` 0 改动(其它 4 个 feature:VNC-over-SSH / RDP-over-SSH / AutoX / SecureTunnel 继续用它)。

新增:
- `bVNC/src/main/res/layout/main_ssh.xml`(~110 行,只含 SSH terminal 字段:nickname / server / port / user / password / keep-pass)
- `bVNC/src/main/res/layout-large/main_ssh.xml`(大屏变体,textAppearance 改 Large)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalConnection.java`(~140 行,封装 trilead `Connection` + `Session`,密码认证 + xterm-256color PTY + startShell,Phase 3.6 加 pubkey + host key fingerprint)

修改:
- `bVNC/src/main/java/com/qihua/bVNC/ConfigSSH.java`(60 → ~110 行,`extends MainConfiguration`,字段源 VNC-style,显式清空 `SshXxx` 字段避免污染隧道语义)
- `bVNC/src/main/java/com/qihua/bVNC/connection/SshConnectionInitializer.java`(~80 行改:`SSHConnection` → `SshTerminalConnection`;`initialize()` 从 VNC-style 字段读 4 个参数;`doConnect()` 调 `sshTerminal.connect()+openShell()`;`teardown()/rebuildSSHFramebuffer()` 调 `sshTerminal.close()`;`SshCommunicator` setter 同步改)
- `bVNC/src/main/java/com/qihua/bVNC/communicator/SshCommunicator.java`(`setSshConnection(SSHConnection)` → `setSshTerminalConnection(SshTerminalConnection)`,`close()` 调 `sshTerminal.close()`)
- `bVNC/src/main/res/values/strings.xml` + `values-zh-rCN/strings.xml`(+2 行 `ssh_server_empty` / `ssh_user_empty` 提示)

无改动:
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SSHConnection.java`(VNC-over-SSH / RDP-over-SSH / AutoX / SecureTunnel 4 个 feature 继续用,**0 改动**)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshShellChannel.java`(已经是 `SshShellChannel()` + `attach(Session)` 模式,内部 pipe + pump 架构不变,**0 改动**)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalRenderer.java`(渲染层,数据源 = TermSession,不变)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/TermFontFactory.java`(Nerd 字体加载,不变)
- `remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java`(字体透传,不变)
- `remoteClientLib/jni/libs/deps/Android-Terminal-Emulator/emulatorview/src/main/java/jackpal/androidterm/emulatorview/PaintRenderer.java`(字体注入,不变)
- `ConnectionBean` / `AbstractConnectionBean` / `Database` / `MainConfiguration` / `ConnectionInitializer`(基类 0 改动)
- `ConfigVNC` / `ConfigRDP` / `ConfigNVStream` / `ConfigSPICE`(其它 4 个协议的 Config 0 改动)
- `RemoteCanvas` / `RemoteCanvasActivity` / `SshCommunicator` 公共方法(只换 setter 内部类型,公共 API 不变)
- `AndroidManifest.xml` / `R.menu.connectionsetupmenu` / 任何 menu / 任何 color resource

**端到端验收**(2026-06-25 设备验收,等用户跑):
1. tap SSH tile → 进入 ConfigSSH 配置页(不是瞬时跳)
2. 配置页有 nickname / server / port / user / password / keep-pass 字段,无 VNC/RDP/SSH-隧道字段
3. 填错校验:server 留空 + Save → 弹 "SSH server address is required" toast
4. 正常保存 → grid 看到新 SSH 卡片
5. 点卡片 → 真连 SSH(走 `SshTerminalConnection.connect()` 直调 trilead,不再走 `SSHConnection.openShellSession()`)
6. DB 落库验证:杀 app 重开 → grid 仍显示
7. DB 读回验证:点卡片 → 连的是 `selected.getAddress()`,不是硬编码
8. edit 字段回填:长按卡片 → edit → 字段已填
9. 多 profile 隔离:再建 SSH profile → 各自连各自
10. 字段语义隔离:SSH terminal profile 不会污染 VNC-over-SSH 隧道的 SshXxx 字段
11. 回归冒烟:VNC / RDP / NVStream 直连 + VNC-over-SSH 隧道 各自能用
12. fold/unfold:沿用 Phase 1/2 行为
13. logcat:无 NetworkOnMainThreadException / 无 zombie thread

---

## 13. Phase 3.7 实现文件清单(2026-06-26,libvterm 替换 AAR)

**架构改动**:终端状态机从 AAR(Android-Terminal-Emulator 2014 版)替换为 libvterm(TragicWarrior 维护,2026-06-26 活跃)。业务层 0 改动,状态机层整体重写。完整 spec 见 [`libvterm-integration.md`](./libvterm-integration.md)。

新增:
- `specs/libvterm-integration.md`(独立 spec,~500 行,决策/风险/验收)
- `remoteClientLib/jni/CMakeLists.txt`(~30 行,CMake 入口)
- `remoteClientLib/jni/src/vterm_jni.c`(~400 行,JNI 桥实现)
- `remoteClientLib/jni/libs/deps/libvterm/`(完整 vendor,5000+ 行 C,TragicWarrior/libvterm)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/SshTermStateMachine.java`(~150 行,Java 端 handle,包内 native 声明)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/VTermCanvasRenderer.java`(~200 行,grid → Canvas 渲染,字体测量 / CJK 宽字符 / cursor 画法)

修改:
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalRenderer.java`(整体重写 ~200 行,`TermSession` → `SshTermStateMachine`,`TermRenderHelper` → `VTermCanvasRenderer`)
- `bVNC/src/main/java/com/qihua/bVNC/input/RemoteSshKeyboard.java`(~3 行,`termSession.write(cp)` → `renderer.writeCodepoint(cp)`)
- `remoteClientLib/build.gradle`(+CMake / externalNativeBuild 配置,− `api(name: 'emulatorview-release', ext: 'aar')`)
- 根 `build.gradle`(+`android.ndkVersion '27.0.12077973'`)
- `specs/ssh-feature-spec.md`(本节,§3.7 + §5 + §13)

删除:
- `remoteClientLib/emulatorview-release.aar`(2.4MB,Android-Terminal-Emulator AAR)
- `remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java`(AAR 同包 hack)

无改动:
- `bVNC/src/main/java/com/qihua/bVNC/connection/SshConnectionInitializer.java`(`paintAndRedraw` / `ensurePaintThread` / `stopPaintThread` / `rebuildSSHFramebuffer` 已通过 `setGridSizeListener` 解耦,**0 改动**)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshShellChannel.java`(只暴露 `InputStream`/`OutputStream`,已与状态机解耦,**0 改动**)
- `bVNC/src/main/java/com/qihua/bVNC/communicator/SshCommunicator.java`(`setSshTerminalConnection` 接口不变,**0 改动**)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalConnection.java`(`connect`/`openShell`/`resizePty`/`close` API 不变,**0 改动**)
- `bVNC/src/main/java/com/qihua/bVNC/ConfigSSH.java` + `main_ssh.xml` + `layout-large/main_ssh.xml`(**0 改动**)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/TermFontFactory.java`(字体加载不变,继续 Sarasa Mono SC Nerd,**0 改动**)
- 折叠屏 / paint HandlerThread / fold-unfold 业务层全部沿用
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SSHConnection.java`(VNC-over-SSH 隧道类,**0 改动**)
- `ConfigVNC` / `ConfigRDP` / `ConfigNVStream` / `ConfigSPICE`(其它 4 个协议配置页 0 改动)
- `RemoteCanvas` / `RemoteCanvasActivity` / `ConnectionInitializer`(基类 0 改动)

**端到端验收**(详见 `libvterm-integration.md` §10):
- 编译:`./gradlew :remoteClientLib:assembleGplayRelease` + `:bVNC:compileGplayReleaseJavaWithJavac` + `:aRDP-app:assembleGplayDebug` 全成功,产出 `libvterm.so`(arm64-v8a),APK 体积净减少约 1.5MB
- 功能:终端开屏显示 WELCOME banner / 软键盘输入回显 / 中文 IME / `ls` `pwd` 完整输出 / Backspace 实时 / 折叠展开
- 修复:**vim 退出不再双重 prompt** + 残留清空 / less 退出正常 / htop 退出正常 / zsh completion 不错列
- 回归:VNC/RDP/SPICE/NVStream 0 影响 / 折叠屏 fold/unfold 沿用 / logcat 无 UnsatisfiedLinkError / `grep -r "jackpal.androidterm.emulatorview" bVNC/src` 0 命中


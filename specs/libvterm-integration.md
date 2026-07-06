# libvterm 集成 spec — SSH 终端状态机替换

## 0. 背景

SSH 终端 Phase 1/2/3.1 把整个"键盘 → TermSession → mbitmap"链路全跑通,业务层(`ConfigSSH` / `SshTerminalConnection` / `SshShellChannel` / 折叠屏 / 字体 / 认证)都稳了。

**问题**:终端状态机用的是 `Android-Terminal-Emulator` AAR(2014 版 jackpal/androidterm fork,11 年没更新),核心缺陷:

1. `CSI ? 47 h/l`(vim/less/htop 切 alt buffer)只切 `mScreen` 指针,不清 `mAltBuffer`、不 fire `UpdateCallback` → vim 退出后双重 prompt / 残留内容
2. OSC 52(剪贴板)未实现
3. OSC 4(颜色查询)未实现
4. 鼠标 SGR 模式半残
5. `package-private` 限制需要 `TermRenderHelper` 同包 hack

继续修 AAR 越改越大(估计 500+ 行),**半重写陷阱**。

## 1. 目标

把终端状态机换成 **libvterm**(Neovim / tig / wezterm / ConnectBot termlib 都在用),业务层 0 改动,白干风险 0%。

## 2. 为什么是 libvterm

### 2.1 Java 生态无对位
- `jvtterm` 在 GitHub 搜不到任何仓库(2026-06 验证)
- `grahamedgecombe/jterminal`(Swing 集成)2018 年后无更新
- 主流活跃的 Java 终端库:**无**

### 2.2 libvterm 现状
- 原作者 `thestinger/libvterm` 已 404(用户被删)
- 社区维护者 `TragicWarrior/libvterm`(NOASSERTION 协议,允许改源码)
- **2026-06-26 昨天还在推**(OSC UTF-8 fix + double-width glyph fix + wide-char clamp)
- libvterm 是 C 库,**5000+ 行 C 状态机**——Neovim 选它就是因为 VT/xterm 解析这个领域 C 比 Java 跑得快(避免 GC 压力)

### 2.3 libvterm vs connectbot/termlib
| 项 | libvterm + JNI | connectbot/termlib |
|---|---|---|
| 维护活跃度 | 2026-06-26 昨天 | 2026-06-22 2 天前 |
| 协议成熟度 | Neovim 选用 10+ 年 | 6 个月(2025-11 创建) |
| 集成方式 | C + JNI(~400 行桥) | Kotlin + Compose |
| 跟 bVNC 集成 | 加 NDK build,业务层 0 改 | 引 Compose + Kotlin 互操作 |
| 风险 | NDK 第一次跑 | API 仍可能变,Compose 学习成本 |

**结论**:libvterm + 自写 JNI 桥 ROI 更高,connectbot/termlib 留 Phase 4 观察。

## 3. 架构

### 3.1 职责分界
- **libvterm C 库**:ANSI / CSI / OSC 解析、grid 状态、光标、alt buffer、鼠标
- **JNI C 桥**:Java 句柄管理、脏行跟踪(无锁 ring buffer)、数据 marshalling
- **Java 端**:paint 时机(走 "SSH-Paint" HandlerThread)、字体测量、字符 → Canvas 绘制

### 3.2 线程模型
**状态机不主动回调 Java**——Java 端在 paint 线程上 `pollDirty()` 拉取。
**原因**:消除 JNI 跨线程 callback 复杂度。SSH reader 线程只往状态机喂字节,paint 线程每 16ms 拉一次脏行。

### 3.3 文件结构

```
remote-desktop-clients/
├── specs/
│   ├── libvterm-integration.md              # 本文件
│   └── ssh-feature-spec.md                  # §3.7 + §13 引用本文件
│
├── remoteClientLib/
│   ├── jni/                                  # 新增 native build
│   │   ├── CMakeLists.txt                    # 新增
│   │   └── src/
│   │       └── vterm_jni.c                   # 新增 ~400 行 JNI 桥
│   ├── jni/libs/deps/libvterm/               # 新增 vendor
│   │   ├── include/                          # 公共头
│   │   ├── src/                              # 5000 行 C
│   │   └── ...
│   └── build.gradle                          # 改:+CMake / -AAR
│
├── bVNC/src/main/java/com/qihua/bVNC/ssh/
│   ├── libvterm/                             # 新增包
│   │   ├── SshTermStateMachine.java          # 新增 ~150 行
│   │   └── VTermCanvasRenderer.java          # 新增 ~200 行
│   ├── SshTerminalRenderer.java              # 整体重写 ~200 行
│   └── TermFontFactory.java                  # 不改
│
├── bVNC/src/main/java/com/qihua/bVNC/input/
│   └── RemoteSshKeyboard.java                # 改 3 行
│
└── 删除:
    remoteClientLib/emulatorview-release.aar
    remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java
```

## 4. JNI 桥接口

### 4.1 Java 端

```java
// bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/SshTermStateMachine.java
public final class SshTermStateMachine {
    static { System.loadLibrary("vterm"); }
    private long nativeHandle;  // VTerm* 指针

    public SshTermStateMachine(int cols, int rows) { nativeHandle = nativeCreate(cols, rows); }
    public void setSize(int cols, int rows)       { nativeSetSize(nativeHandle, cols, rows); }
    public void write(byte[] data, int off, int len) { nativeWrite(nativeHandle, data, off, len); }
    public void writeInput(int codepoint)         { nativeWriteInput(nativeHandle, codepoint); }
    public int  getCols()                         { return nativeGetCols(nativeHandle); }
    public int  getRows()                         { return nativeGetRows(nativeHandle); }
    public boolean pollDirty()                    { return nativePollDirty(nativeHandle); }
    public void  takeDirtyRows(int[] outRows)     { nativeTakeDirtyRows(nativeHandle, outRows); }
    public TermCell getCell(int row, int col)     { return nativeGetCell(nativeHandle, row, col); }
    public CursorInfo getCursor()                 { return nativeGetCursor(nativeHandle); }
    public void destroy()                         { nativeDestroy(nativeHandle); }

    private static native long  nativeCreate(int cols, int rows);
    private static native void  nativeSetSize(long h, int cols, int rows);
    private static native void  nativeWrite(long h, byte[] data, int off, int len);
    private static native void  nativeWriteInput(long h, int codepoint);
    private static native int   nativeGetCols(long h);
    private static native int   nativeGetRows(long h);
    private static native boolean nativePollDirty(long h);
    private static native void  nativeTakeDirtyRows(long h, int[] outRows);
    private static native TermCell  nativeGetCell(long h, int row, int col);
    private static native CursorInfo nativeGetCursor(long h);
    private static native void  nativeDestroy(long h);

    public static final class TermCell {
        public int codepoint;       // 0 = empty
        public int width;           // 0/1/2 (1=normal, 2=double-width CJK)
        public int fg;              // RGB
        public int bg;              // RGB
        public int attrs;           // bold/italic/underline/reverse/blink bitmask
    }
    public static final class CursorInfo {
        public int row, col;
        public boolean visible;
    }
}
```

### 4.2 C 端

`remoteClientLib/jni/src/vterm_jni.c`(~400 行):

```c
#include <jni.h>
#include <vterm.h>

// Java 句柄的 C 侧结构
typedef struct {
    VTerm *vt;
    VTermScreen *vts;
    int rows, cols;
    // 脏行 ring buffer (无锁,Java 在 paint 线程单消费者)
    int dirtyRows[256];
    int dirtyCount;
} jhandle_t;

static int vterm_damage(VTermRect rect, void *user) {
    jhandle_t *h = (jhandle_t *) user;
    for (int r = rect.start_row; r < rect.end_row; r++) {
        if (h->dirtyCount < 256) h->dirtyRows[h->dirtyCount++] = r;
    }
    return 1;
}
static int vterm_moverect(VTermRect dest, VTermRect src, void *user) { /* same as damage */ return 1; }
static int vterm_resize(int rows, int cols, VTermResizeHandle rs, void *user) {
    jhandle_t *h = (jhandle_t *) user;
    h->rows = rows; h->cols = cols;
    return 1;
}
static int vterm_settermprop(VTermProp prop, VTermValue *val, void *user) { return 1; }
static int vterm_bell(void *user) { return 1; }

// JNI 函数实现:略
```

## 5. 渲染层

`VTermCanvasRenderer.java` 替代 `TermRenderHelper.java`:

- 自己用 `TextPaint` + `Canvas.drawText`,不走 AAR 的 `BaseTextRenderer`/`PaintRenderer`
- 构造时量 `charWidth = paint.measureText("M")` / `charHeight = descent-ascent`
- CJK 宽字符:`cell.width == 2` 时画两个 cell 宽,下一个 col 跳过
- 光标:反色方块(可换下划线 / 竖条)
- **不要做全局 `canvas.drawColor(BG)` 清屏** —— 逐 cell 填自己的 bg。原因见 [§14.1](#141-ssh-输入闪烁--renderinto-与-drawworker-的-bitmap-读写竞态2026-07-05):`renderInto`(SSH-Paint 线程)写的 `mbitmap` 正是 `DrawWorker` 读的目标,全清 + 慢重绘会让 `DrawWorker` 读到空白帧 → 输入闪烁

## 6. 折叠屏 / paint HandlerThread 沿用

`SshConnectionInitializer.paintAndRedraw()` / `ensurePaintThread()` / `stopPaintThread()` **0 改动**。

`SshTerminalRenderer.renderInto(Bitmap)` 检测 cols/rows 变 → `stateMachine.setSize()` + `gridSizeListener.onGridSizeChanged()` 通知 → `SshConnectionInitializer` 调 `SshTerminalConnection.resizePty(cols, rows)` → 远端 server 收到 SIGWINCH。

## 7. 字体

`TermFontFactory.java` 0 改,继续从 `bVNC/src/main/assets/fonts/SarasaMonoSCNerd-Regular.ttf` 加载 Sarasa Mono SC Nerd。

`VTermCanvasRenderer` 构造时收 `Typeface`,注入 `TextPaint.setTypeface()`。

## 8. NDK 启用

**根 `build.gradle`** 加 `android.ndkVersion '27.0.12077973'`(或本机已装版本)。

**`remoteClientLib/build.gradle`** 启用 CMake:
```groovy
android {
    defaultConfig {
        ndk { abiFilters 'arm64-v8a' }
        externalNativeBuild {
            cmake {
                arguments '-DANDROID_STL=c++_shared'
                cppFlags '-std=c++17'
                cFlags   '-std=c11'
            }
        }
    }
    externalNativeBuild {
        cmake { path 'jni/CMakeLists.txt' }
    }
}
```

**新增** `remoteClientLib/jni/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(vterm_jni LANGUAGES C CXX)

add_subdirectory(libs/deps/libvterm)

add_library(vterm SHARED src/vterm_jni.c)
target_link_libraries(vterm PRIVATE vterm_static)
target_include_directories(vterm PRIVATE libs/deps/libvterm/include)
target_link_libraries(vterm PRIVATE log)
```

## 9. 改动文件清单

### 新增
- `remoteClientLib/jni/CMakeLists.txt`(~30 行)
- `remoteClientLib/jni/src/vterm_jni.c`(~400 行)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/SshTermStateMachine.java`(~150 行)
- `bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/VTermCanvasRenderer.java`(~200 行)

### 修改
- `bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalRenderer.java`(整体重写 ~200 行)
- `bVNC/src/main/java/com/qihua/bVNC/input/RemoteSshKeyboard.java`(~3 行)
- `remoteClientLib/build.gradle`(+CMake / -AAR)
- 根 `build.gradle`(+`ndkVersion`)

### 删除
- `remoteClientLib/emulatorview-release.aar`(2.4MB)
- `remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java`

### 不动
- `SshConnectionInitializer` / `SshShellChannel` / `SshCommunicator` / `SshTerminalConnection`
- `ConfigSSH` / `main_ssh.xml` / `main_ssh.xml`(layout-large)
- `TermFontFactory`(字体加载不变)
- 折叠屏 / paint HandlerThread / fold-unfold
- `SSHConnection`(VNC-over-SSH 隧道类)

## 10. 验收标准

### 10.1 编译
- [ ] `./gradlew :remoteClientLib:assembleGplayRelease` BUILD SUCCESSFUL,产出 `libvterm.so`(arm64-v8a)
- [ ] `./gradlew :bVNC:compileGplayReleaseJavaWithJavac` BUILD SUCCESSFUL,无 `jackpal.androidterm.emulatorview.*` import 残留
- [ ] `./gradlew :aRDP-app:assembleGplayDebug` BUILD SUCCESSFUL,APK 体积净减少约 1.5MB
- [ ] `grep -r "jackpal.androidterm.emulatorview" bVNC/src` 0 命中

### 10.2 功能
- [ ] 终端开屏显示 `Welcome to Ubuntu 22.04...`
- [ ] 软键盘输入字符逐个回显;回车换行
- [ ] 中文 IME 输入
- [ ] `ls` / `pwd` / `uname -a` 等命令输出完整
- [ ] Backspace 实时响应
- [ ] zsh completion 不错位
- [ ] 折叠→展开:画面重建,字号物理大小不变
- [ ] 切后台→切回前台:画面保留

### 10.3 修复(Phase 3.1 未解决的 AAR 问题)
- [ ] **vim 退出不再双重 prompt** ← 核心目标
- [ ] vim 退出无 alt buffer 残留
- [ ] less 退出正常
- [ ] htop 退出正常
- [ ] zsh completion 不错列

### 10.4 回归
- [ ] VNC / RDP / SPICE / NVStream 连接不被影响
- [ ] 折叠屏 / paint HandlerThread / Sarasa 字体 / 配置页 / DB 落库读回 全部沿用
- [ ] logcat 无 `UnsatisfiedLinkError` / `ERROR` / `WTF`

## 11. 风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| R1 | 项目首次跑 NDK build | Step 1 先做 hello world .so 验证 NDK 跑通,再加 libvterm |
| R2 | libvterm 是 C 库,团队不熟 C | C 复杂度封装在 JNI 桥内(~400 行),业务层只调 Java API |
| R3 | C 状态机跟 Java 调用的线程模型 | 状态机不主动回调 Java,Java 在 paint 线程上 pollDirty |
| R4 | libvterm 协议 NOASSERTION | 允许改源码,vendored 在 `remoteClientLib/jni/libs/deps/libvterm/` |
| R5 | 字体测量:libvterm 不知道 Android 字体 metrics | VTermCanvasRenderer 构造时一次性量,后续复用 |
| R6 | CJK 宽字符 | `cell.width == 2` 画两个 cell 宽,下一 col 跳过 |
| R7 | libvterm 无 UpdateCallback | vterm_damage callback 把脏 row 写入 nativeHandle->dirtyRows,Java pollDirty 拉 |
| R8 | Sarasa 字体跟 libvterm 的 Unicode 宽度表不一致 | libvterm 用 East Asian Width 标准字符表,与 AAR WcWidth 等价 |
| R9 | 折叠屏 resizePTY 顺序 | 跟 Phase 3.1 一致 |
| R10 | NDK build 慢 | Step 1 hello world / Step 2 vendor libvterm / 后续逐步加 |
| R11 | libvterm 上游有 bug 时打 patch | vendored 模式,改 .c 即可 |
| R12 | JNI 崩溃定位 | 启用 `android:debuggable="true"` + `ndk-stack` + `addr2line` |
| R13 | `?1049` 切 alt buffer + 光标 save/restore | libvterm 标准支持,0 改 |
| R14 | OSC 52(剪贴板) | libvterm 标准支持,后续 Phase 3.3 受益 |
| R15 | 鼠标 SGR 模式 | libvterm 标准支持,后续 Phase 3.3+ 受益 |

## 12. 不做

- OSC 52 剪贴板(Phase 3.3 一起做)
- OSC 4 颜色查询(暂时不需要)
- 鼠标 SGR / 鼠标点击文本选择(Phase 3.3+)
- 真字体回退(双字体合并族,Phase 4 评估)
- 性能 profiling(暂不优化)

## 13. 参考

- [TragicWarrior/libvterm](https://github.com/TragicWarrior/libvterm) - 2026-06-26 活跃维护
- [akermu/emacs-libvterm](https://github.com/akermu/emacs-libvterm) - 1979 ⭐ Emacs 集成
- [connectbot/termlib](https://github.com/connectbot/termlib) - 2026-06-22 活跃,但 Compose + Kotlin
- [libvterm upstream](http://libvterm.github.io/) - 原始文档
- 内部:[`ssh-feature-spec.md`](./ssh-feature-spec.md) §3.7 + §13
- 内部:[`project-structure.md`](./project-structure.md)
- 关键代码位置:
  - `SshTerminalRenderer.java`:`bVNC/src/main/java/com/qihua/bVNC/ssh/SshTerminalRenderer.java`
  - `SshConnectionInitializer.java`:`bVNC/src/main/java/com/qihua/bVNC/connection/SshConnectionInitializer.java`(0 改)
  - `SshShellChannel.java`:`bVNC/src/main/java/com/qihua/bVNC/ssh/SshShellChannel.java`(0 改)

## 14. 实现踩坑记录

### 14.1 SSH 输入闪烁 — renderInto 与 DrawWorker 的 bitmap 读写竞态(2026-07-05)

**现象**:SSH 连接下,每次按键输入时屏幕闪烁;空闲时不闪。

**根因**:`VTermCanvasRenderer.render()` 每帧先 `canvas.drawColor(BG_COLOR)` 把整个 `mbitmap` 清空,再逐 cell 重绘(10–30ms)。`renderInto` 跑在 **SSH-Paint HandlerThread**(`SshConnectionInitializer.paintThread`),而 `DrawWorker` 线程同时通过 `UltraCompactBitmapDrawable.draw()` → `canvas.drawBitmap(data.mbitmap, …)` 读**同一张** `mbitmap`(`DrawWorker.java:127-129`),两者**无任何同步**。

每次按键 → 服务端回显 → reader 线程 `onUpdate.run()` → `sshUpdateRunnable` → `postPaintToBackground()` → `renderInto` 全清 + 重绘。在这个 10–30ms 窗口内,`DrawWorker` 一旦读到刚被 `drawColor` 清空、还没重绘完的 bitmap,就把一帧空白/半残画面 blit 到 SurfaceView → 闪烁。空闲时不闪,是因为心跳(`HEARTBEAT_INTERVAL_MS=200ms`)只在 >1s 无 paint 时才触发(`heartbeatRunnable` 里 `sinceLast > 1000` 判断),而按键时每键都触发一次这个竞态。

为什么 VNC/RDP 同样走 `DrawWorker` 却不闪:它们的 decoder 走 `AbstractBitmapData.imageRect()` 增量写像素(且 `synchronized(mbitmap)` 逐行),不全清;`DrawWorker` 中途读到的最坏只是"部分像素已更新",不会出现整片空白帧。SSH 的 `renderInto` 是**唯一**对 `mbitmap` 做"全清 + 整屏重绘"的写者,所以只有 SSH 闪。

**修复**(`VTermCanvasRenderer.render()`):去掉全局 `canvas.drawColor(BG_COLOR)`,改成**逐 cell 填充自己的 bg**:

- 每个 cell 先填自己的 bg 矩形(清掉上一帧残留),再画字符。一个 cell 只在"填 bg → 画字"之间有微秒级空窗,且是 cell 局部的,不是全屏的。
- 并发读最坏只看到"上一帧 + 本帧"的 cell 混合,**两帧都是完整渲染的**,不再出现空白闪。
- grid 外的 padding 区域由 `SshTerminalRenderer.seedBackground()` 预填 BG,render 不碰,保持 BG。

**配套:gap cell 必须跳过**。libvterm 对双宽 CJK 字符的第二列返回 `chars[0]==0xFFFFFFFF`,JNI `nativeGetCell` 转成 `jint` 即 `-1`(`vterm_jni.c:482`)。原来"只在非默认 bg 时填 bg"的逻辑碰巧没填 gap cell(它的 bg 是默认的),所以没覆盖宽字符右半边;改成"逐 cell 必填 bg"后,必须显式 `if (cell.codepoint == -1) continue;` 跳过 gap cell,否则它的 bg 填充会覆盖前一个 cell 宽字符的右半边。

**通用教训**:`renderInto` 写的 `mbitmap` 正是 `DrawWorker` 读的目标,这是 SurfaceView 双线程渲染的经典 tearing 源。两条出路:

1. **逐 cell 自清**(本方案,0 额外内存,只改 render 内部)——消除全清导致的空白帧,残留最坏是 cell 级 tearing,肉眼基本不可见。
2. **scratch bitmap + 一次性 blit**(更重)——render 到离屏 bitmap,再 `drawBitmap` 一次性 blit 到 `mbitmap`,把竞态窗口从 10–30ms 缩到 ~1-2ms。后续若仍见 tearing 再升级。

**不要**在 `DrawWorker.draw()` 加 `synchronized(mbitmap)` —— 它服务所有协议(VNC/RDP/SPICE/SSH),加锁会拖慢全局绘制路径,且 `imageRect` 已用该锁做逐行写,语义上跟整屏渲染的锁粒度不匹配。

**关键代码**:`bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/VTermCanvasRenderer.java:render()`

**相关 memory**:`project_ssh_paint_off_main_thread.md`(renderInto 必须离主线程)—— 那条解决的是"主线程阻塞丢键",本条解决的是"背景线程仍与 DrawWorker 竞态",两者是同一渲染路径上先后暴露的两层问题。
  - `TermRenderHelper.java`:`remoteClientLib/src/main/java/jackpal/androidterm/emulatorview/TermRenderHelper.java`(待删)
  - 计划:`/home/sefler/.claude/plans/giggly-booping-wigderson.md`

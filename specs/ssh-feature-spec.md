# SSH 终端功能实现规格书

## 基本信息

| 项目 | 内容 |
|------|------|
| 功能名称 | SSH Terminal (纯文字终端) |
| 所属项目 | qidesk (奇花远程桌面) |
| 实现方式 | SSH + Terminal Emulator |
| 当前状态 | 待实现 (Phase 0) |

---

## 背景

奇花远程桌面（qidesk）已实现三种远程连接协议：
- VNC
- RDP
- NVStream（Moonlight/Sunshine）

现需新增第四种：**纯 SSH 终端连接**（纯文字，无图形界面）。

---

## 技术选型

### SSH 库：Apache MINA SSHD

```gradle
implementation group: 'org.apache.sshd', name: 'sshd-core', version: '2.13.0'
```

- 纯 Java 实现，无 native 依赖
- 支持密码认证、密钥认证
- Maven 中央仓库可用

**仓库配置**:
```gradle
// build.gradle (项目根目录)
repositories {
    mavenCentral()
    google()
    maven { url 'https://jitpack.io' }
    flatDir {
        dirs '/home/sefler/remote-desktop-clients/remoteClientLib',
             '/home/sefler/remote-desktop-clients/bVNC/libs'
    }
}
```

### Terminal Emulator：Android-Terminal-Emulator

**来源**: https://github.com/masonscped/Android-Terminal-Emulator

| 优点 | 说明 |
|------|------|
| 纯 Java | 无 JNI、无 Kotlin、无 Compose |
| Android View | 可直接在 XML 布局中使用 |
| Apache 许可证 | 可商用 |
| InputStream/OutputStream | 可对接 Apache MINA SSHD |

**关键类**:
- `EmulatorView` - 终端显示组件（继承 View）
- `TermSession` - 终端会话（需要 InputStream/OutputStream）
- `TerminalEmulator` - VT100 模拟器
- `ColorScheme` - 配色方案

**不采用 termlib 的原因**:
- 使用 Kotlin + Jetpack Compose
- 需要 JNI + libvterm
- 与现有 Java 项目不兼容

---

## 对接架构

```
┌─────────────────────────────────────────────────────────────┐
│                    SshTerminalActivity                       │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌──────────────┐    ┌────────────┐    │
│  │ EmulatorView │ ←  │  TermSession  │ ←  │ ByteQueue  │    │
│  │  (显示终端)  │    │  (VT100模拟)  │    │  (缓冲区)  │    │
│  └─────────────┘    └──────────────┘    └────────────┘    │
│                              ↑                              │
│                    InputStream/OutputStream                 │
│                              ↑                              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Apache MINA SSHD                        │   │
│  │  SshClient → ClientSession → ClientChannel         │   │
│  │  (SSH连接)    (会话)        (Shell Channel)         │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**数据流**:
1. 用户键盘输入 → EmulatorView
2. EmulatorView → TermSession.getTermIn() → PipedOutputStream → SSH Channel In
3. SSH Server → SSH Channel Out → TermSession.getTermOut() → EmulatorView 显示

---

## 文件结构

```
remote-desktop-clients/
├── specs/
│   └── ssh-feature-spec.md          # 本规格文件
├── bVNC/src/main/java/com/qihua/bVNC/
│   ├── ConfigSSH.java               # SSH 配置页面 (新建)
│   ├── SshTerminalActivity.java     # SSH 终端界面 (新建)
│   └── ssh_terminal.xml            # 终端布局 (新建)
├── bVNC/src/main/res/layout/
│   └── config_ssh.xml               # SSH 配置布局 (新建)
├── bVNC/src/main/res/menu/
│   └── ssh_terminal_menu.xml       # 终端菜单 (新建)
├── bVNC/libs/
│   └── emulatorview-release.aar    # 终端模拟器库 (已编译)
└── ~/Android-Terminal-Emulator/    # 终端模拟器源码
    └── emulatorview/                # AAR 源码
```

---

## 实现任务清单

### Phase 1: 依赖和环境准备

- [ ] 添加 Apache MINA SSHD 到 build.gradle
- [ ] 添加 Android-Terminal-Emulator AAR 依赖
- [ ] 配置 flatDir 仓库指向 bVNC/libs

### Phase 2: 配置页面 (ConfigSSH)

- [ ] 创建 `config_ssh.xml` 布局文件
  - 字段：昵称、服务器地址、端口、用户名、密码、保持登录
  - 隐藏 SSH 隧道等无关字段
- [ ] 创建 `ConfigSSH.java`
  - 继承 MainConfiguration
  - 实现 save/load 连接
  - 点击保存后跳转到 SshTerminalActivity

### Phase 3: 路由打通

- [ ] 在 `Utils.getConnectionSetupClass()` 添加 `case "ssh" → ConfigSSH.class`
- [ ] 删除 `ConnectionGridActivity.addNewConnection()` 里的 SSH guard
- [ ] AndroidManifest 注册 ConfigSSH 和 SshTerminalActivity

### Phase 4: 终端界面 (SshTerminalActivity)

- [ ] 创建 `ssh_terminal.xml` 布局，使用 EmulatorView
- [ ] 实现 SSH 连接逻辑
- [ ] 实现终端数据流对接
- [ ] 处理用户输入和服务器输出
- [ ] 添加菜单（重连等）

### Phase 5: 测试与优化

- [ ] 功能测试：密码认证、密钥认证
- [ ] UI 测试：配色、字体、滚动
- [ ] 网络测试：重连、心跳

---

## 界面设计

### ConfigSSH 布局

```
┌─────────────────────────────┐
│ 昵称                        │
│ [________________]          │
├─────────────────────────────┤
│ SSH 服务器                  │
│ [________________]          │
│ 端口                        │
│ [22______________]          │
├─────────────────────────────┤
│ 用户名                      │
│ [________________]          │
│ 密码                        │
│ [________________]          │
│ ☑ 保持密码                  │
├─────────────────────────────┤
│      [ 保存并连接 ]         │
└─────────────────────────────┘
```

### SshTerminalActivity 布局

```
┌─────────────────────────────┐
│ ☰  192.168.1.103           │  ← Toolbar
├─────────────────────────────┤
│                             │
│  sefler@server:~$ ls       │
│  file1.txt  file2.txt      │
│  sefler@server:~$ _        │  ← EmulatorView (终端)
│                             │
│                             │
└─────────────────────────────┘
```

---

## 终端配色方案

Solarized Dark:

| 名称 | 颜色 | 用途 |
|------|------|------|
| foreground | #839496 | 前景文字 |
| background | #002B36 | 背景色 |
| cursor | #839496/#002B36 | 光标 |

---

## Android-Terminal-Emulator 编译说明

### 编译环境

```bash
cd ~/Android-Terminal-Emulator
./gradlew :emulatorview:assembleRelease
```

### 修复兼容性问题

1. **FloatMath → Math**: `PaintRenderer.java`
   ```java
   import android.util.FloatMath;  // 删除
   // FloatMath.ceil() → Math.ceil()
   ```

2. **gradle-wrapper.properties**: 更新到 Gradle 8.4
   ```
   distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-all.zip
   ```

3. **build.gradle**: 更新 Android Gradle Plugin
   ```gradle
   buildscript {
       dependencies {
           classpath 'com.android.tools.build:gradle:8.1.0'
       }
   }
   ```

4. **gradle.properties**: 启用 AndroidX
   ```
   android.useAndroidX=true
   android.enableJetifier=true
   ```

5. **local.properties**: 设置 SDK 路径
   ```
   sdk.dir=/home/sefler/Android/Sdk
   ```

### 输出 AAR

```
~/Android-Terminal-Emulator/emulatorview/build/outputs/aar/emulatorview-release.aar
```

---

## 参考资料

- Apache MINA SSHD: https://mina.apache.org/sshd-project/
- Android-Terminal-Emulator: https://github.com/masonscped/Android-Terminal-Emulator
- ConnectBot (参考): https://github.com/connectbot/connectbot

---

## 注意事项

1. SSH 是**纯文字终端**，不需要分辨率、颜色模式、远程鼠标等图形相关配置
2. ConfigSSH 需要**独立的配置界面**，不能复用 VNC/RDP 的布局
3. SSH 长连接需要处理网络切换（WiFi → 4G）时的重连逻辑
4. 密钥管理：支持 RSA/ED25519 密钥 + 口令短语
5. Android-Terminal-Emulator build.gradle 版本较旧（compileSdk 22），需要适配

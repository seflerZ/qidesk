# CLAUDE.md

项目操作手册 —— 在这个 codebase 里工作时,Claude 应遵循的约定与教训。

## 代码搜索:优先用 LSP,不要 Read + grep 凑

`LSP` 工具(`goToDefinition` / `findReferences` / `documentSymbol` / `hover` / `workspaceSymbol`)在本项目可用,且比 `Read` + `Bash` grep 快得多。

| 想知道的 | 用 |
|---|---|
| 类 X 有哪些成员 / 方法签名 | `LSP documentSymbol` |
| 符号 Y 在哪里被引用 | `LSP findReferences` |
| 这个变量 / 方法是什么类型 | `LSP hover` |
| 符号在哪里定义 | `LSP goToDefinition` |
| 全工作区搜符号 | `LSP workspaceSymbol`(索引完成后) |

`Read` + `Bash` grep 仍然保留给:
- 搜**字符串字面量**(错误信息、log format、注释里的关键字)
- 需要**实现细节**的查看(具体逻辑、多行上下文)

**注意**:LSP 第一次调用可能返回 `LSP server has not finished indexing the project` —— 等一下或换个文件,不要立刻退回 grep。

## SSH terminal: libvterm 不是 AGP module(SSH Phase 3.7)

`remoteClientLib/jni/libs/deps/libvterm/` 在 `settings.gradle` **没有** `include ':...:libvterm'`,虽然 FreeRDP / moonlight-android / Android-Terminal-Emulator 都是 module。这是 by design,不要"修正"它:

- FreeRDP / Moonlight 是**完整 AGP 项目**(`build.gradle` + `AndroidManifest.xml` + Java/Kotlin 类),我们通过 `implementation project(':remoteClientLib:jni:libs:deps:FreeRDP:...')` 拿它们的 Java API
- libvterm **没有 Java API**,纯 C,只通过 `remoteClientLib/src/main/jni/vterm_jni.c` 这一个 JNI 桥暴露 native 方法给 `:bVNC` 调,Java 端在 `bVNC/src/main/java/com/qihua/bVNC/ssh/libvterm/SshTermStateMachine.java`
- 产物路径:`build-deps.sh build_vterm` 触发 ndk-build → `libvterm.so` + `libvterm_jni.so` 落到 `remoteClientLib/src/main/jniLibs/arm64-v8a/` → AGP 当 jniLibs 资源打包
- `remoteClientLib/build.gradle` **没有** `externalNativeBuild` 块——刻意避免 AS 每次打包重跑 ndk-build 时的路径解析坑(见 build.gradle:32-37 注释)

**为什么不是 module**:AGP module 需要 Java surface 暴露给上游,libvterm 没东西暴露——它只是源码,被 ndk-build 直接编,产物走 jniLibs 打包路径。如果以后 libvterm 需要暴露 Java API(比如 Phase 4+ 加 OSC 52 helper),那时再考虑提升为 module。

**编译入口**:
- 一键:`cd remoteClientLib/jni/libs && ./build-deps.sh build`(依次跑 build_freerdp → build_moonlight → build_vterm,各看 `*_BUILT` sentinel 短路)
- 单跑 vterm:`cd remoteClientLib/jni/libs && ./build-deps.sh build_vterm`(若脚本没这 case,用 `bash -c 'set -eE; sed -n 1,690p build-deps.sh | source /dev/stdin && build_vterm'` 临时方案)
- 强制重编:删 `remoteClientLib/jni/libs/VTERM_BUILT` 再跑

**改源码**:`cd remoteClientLib/jni/libs/deps/libvterm && <改> && git commit && git push`,remote = `git@github.com:seflerZ/libvterm.git`(forked from leonerd.org.uk 0.3.3)。**不要**用 master 10.x——它硬依赖 ncurses,Bionic 没装。

**Sentinel 模式**:`MOONLIGHT_BUILT` / `FREERDP_BUILT` / `VTERM_BUILT` 三个空文件,前两个在 `.gitignore`,`VTERM_BUILT` 在 `remoteClientLib/.gitignore`——不要 commit 它们。

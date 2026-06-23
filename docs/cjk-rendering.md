# CJK Rendering Spec

bVNC SSH 终端的 CJK（中/日/韩）字符渲染规格。 描述了为什么需要自定义渲染、几何模型、代码实现，以及踩过的坑。

## 问题

在 Android 上用 `Typeface.MONOSPACE`（如 `DroidSansMono.ttf`）+ `canvas.drawText` 渲染 CJK 字符时，字符会**比 2 个 Latin 单元格窄**，留出半角空白（"half-space"）。 在 Honor foldable 上尤其明显。

### 根本原因

| 字体属性 | 值（典型 16pt DroidSansMono） |
|---|---|
| Latin 'X' advance (`mCharWidth`) | ~0.6 × fontSize ≈ 9.6pt |
| Latin em ascent | ~0.85 × fontSize ≈ 13.6pt |
| Latin em descent | ~0.15 × fontSize ≈ 2.4pt |
| CJK em（来自 fallback 字体，如 Noto Sans CJK） | 1.0 × fontSize ≈ 16pt |

Latin 1 cell = 9.6pt 宽，CJK em = 16pt 宽。 2 cells = 19.2pt，所以 **CJK 字符（16pt）比 2 cells（19.2pt）窄 ~3pt**，这就是 half-space。

不能简单地把 CJK 字体度量改成 2 cells 宽：所有商用 CJK 字体的 em 都是 1.0 × fontSize（这是字体设计的事实）。

## 解决方案

**"2 cells = real square, except for descenders" 模型：**

1. **`mCharAscent` 强制设为 `-2 * mCharWidth`**，让 `|ascent|` 等于 2 个 cell 宽。 此时 `mCharHeight = |ascent| + descent = 2 * mCharWidth + descent`，2 cells 的分配是 `2 * mCharWidth × 2 * mCharWidth` 的**正方形**，descender 区域在正方形下方。
2. **Latin 字符**用自然 advance 渲染（`measureText("X")` 不变），自然位置，不压缩不拉伸。
3. **CJK 字符**用 `canvas.scale(s, s, drawX, baselineY)` 均匀缩放，`s = 2 * mCharWidth / mFullWidthCharWidth ≈ 1.2`（DroidSansMono + Noto Sans CJK 的典型值）。 Scale 锚点在 CJK 字符的左下角，所以：
   - CJK 字符底边对齐 Latin baseline
   - CJK em 缩放到 `s × fontSize` = `2 * mCharWidth` = 2 cells 宽
   - CJK 字符的 1:1 长宽比被保留（uniform scale，不是 horizontal-only）

### 几何示意

```
┌──────────┐ 2 cells = mCharWidth × 2 = 2 * mCharWidth
│ 你       │   ┌────┐
│          │   │ 你 │ CJK em after scale = 2 * mCharWidth
│          │   │    │
│          │   └────┘
├──────────┤ ← Latin baseline
│          │ descent area (g/p/y/q/j)
└──────────┘
   mCharHeight = 2 * mCharWidth + descent
```

## 实现

代码在 `remoteClientLib/jni/libs/deps/Android-Terminal-Emulator/emulatorview/src/main/java/jackpal/androidterm/emulatorview/PaintRenderer.java`。

### 构造时计算指标

```java
mCharWidth = mTextPaint.measureText(EXAMPLE_CHAR, 0, 1);   // 自然 Latin advance
mCharAscent = (int) Math.ceil(-2.0f * mCharWidth);         // 强制 |ascent| = 2 cells
mCharHeight = -mCharAscent + (int) Math.ceil(mTextPaint.descent());
mCharDescent = mCharHeight + mCharAscent;

float[] cjkWidths = new float[1];
mTextPaint.getTextRunAdvances(CJK_SAMPLE, 0, 1, 0, 1, false, cjkWidths, 0);
mFullWidthCharWidth = cjkWidths[0];
mCjkScale = mFullWidthCharWidth > 0.0f
        ? 2.0f * mCharWidth / mFullWidthCharWidth
        : 1.0f;
```

`getTextRunAdvances` 而非 `measureText`：前者走 text shaper（和 `drawText` 同一路径），数值与实际渲染一致；后者在某些设备（特别是 Honor）的 fallback 字体上不可靠。

### 绘制 CJK 字符

```java
int saveCount = canvas.save();
canvas.scale(mCjkScale, mCjkScale, drawX, textOriginY);
mTextPaint.setTextScaleX(1.0f);
canvas.drawText(text, p, cpIncr, drawX, textOriginY, mTextPaint);
canvas.restoreToCount(saveCount);
drawX += 2.0f * mCharWidth;
```

- `canvas.scale(s, s, px, py)` 锚点 `(drawX, textOriginY)`：CJK 字符的左下角不动
- `canvas.save() / restoreToCount()`：scale 限定在这一次 `drawText` 内
- `mTextPaint.setTextScaleX(1.0f)`：确保不是其他遗留状态（防御性）

### Latin 字符

Latin 不变，自然 advance，自然位置：

```java
mTextPaint.setTextScaleX(1.0f);
canvas.drawText(text, p, cpIncr, drawX, textOriginY, mTextPaint);
drawX += mCharWidth;
```

## 拒绝的方案（历史）

1. **v1 (`cellAdvance = max(mCharWidth, mFullWidthCharWidth/2)`)**：在 Honor 上 CJK 比 2 cells 窄，所以 `max()` 退化成 `mCharWidth`，无效。
2. **v2（per-codepoint x shift 把 CJK 右移 `2*mCharWidth - mFullWidthCharWidth`）**：能消除半角空白，但 CJK 字符看起来"贴在"下一格左边，视觉上靠左、右侧空隙感强。
3. **v17/v18 (`setTextScaleX(1.2)` horizontal-only)**：CJK 字符水平拉长，长宽比被破坏，明显拉伸。
4. **v25（`mCharWidth = |ascent|/2` 把 cells 变窄）**：Latin 字符被压扁（"narrowed, not looking good"）。
5. **v63（centering：CJK 画在 cell 中间，shift = `(2*cellW - cjkW)/2`）**：CJK 字符左右都有半角空白，与 Latin 之间的视觉间距不一致。
6. **v65/v66（`setTextScaleX(1.14)`，scale = `1.2 × 0.95`）**：CJK 字符填满 ~95% 的 2 cells，接近 Windows Terminal 视觉；用户评价 "not too bad, but not perfect" — 仍有可见拉伸。
7. **v67（`|ascent| = 2 * mCharWidth`，但 CJK 不缩放）**：cell 是正方形了，但 CJK 字符还是自然宽度（比 2 cells 窄 17%），右侧有可见 gap。

最终采用 v68 = v67 + uniform `canvas.scale` for CJK。

## 行业参考

- **Windows Terminal**（`microsoft/terminal`）：用 `IDWriteFontFallback::MapCharacters` + `IDWriteFontFallbackBuilder::AddMapping(scale=1.2)`，让 CJK fallback 字体在比 Latin 大 1.2× 的尺寸下栅格化。 效果上等同 `canvas.scale(1.2, 1.2)`，但不会在代码里显式看到 scale。
- **alacritty**（`alacritty/alacritty`）：用 HarfBuzz `hb_shape()` 拿到每个字符的实际 advance，然后按 shaper 输出的 advance 定位；CJK fallback 由 crossfont 处理。
- **kitty / iTerm2 / mlterm**：同思路 — 信任 shaper 的实际 advance，font fallback 链负责 CJK 字符的 em 配置。

共同点：**用 shaper-accurate 测量（CJK 字符的实际 advance）+ 信任 CJK 字体的 em 设计，不做水平-only 拉伸**。

## 已知限制

- 行的 vertical pitch 比自然 font line height 高约 35%（cell 高度 = `2 * mCharWidth + descent` ≈ 22pt，vs 自然 16pt）。 终端看起来"行间距大"，但仍能正常使用。
- 如果设备的 CJK fallback 字体不是 Noto Sans CJK（比如某些 ROM 用了别的字体），`mCjkScale` 可能算错；CJK 字符会有可见变形。 解决：在 CJK Paint 上显式 `setTypeface()` 指定已知 CJK 字体。
- 没有处理 emoji（一般走专门的 emoji 字体，em 更大或更小）；保持上游行为。

## 调试

```bash
cd /home/sefler/remote-desktop-clients
./gradlew :emulatorview:assembleRelease
cp -f remoteClientLib/jni/libs/deps/Android-Terminal-Emulator/emulatorview/build/outputs/aar/emulatorview-release.aar \
      remoteClientLib/emulatorview-release.aar
./gradlew :aRDP-app:assembleGplayDebug
adb install -r aRDP-app/build/outputs/apk/gplay/debug/aRDP-app-gplay-debug.apk
```

- AAR 路径：`remoteClientLib/emulatorview-release.aar`（已被 `.gitignore` 忽略）
- APK 路径：`aRDP-app/build/outputs/apk/gplay/debug/aRDP-app-gplay-debug.apk`
- 源文件：`remoteClientLib/jni/libs/deps/Android-Terminal-Emulator/emulatorview/src/main/java/jackpal/androidterm/emulatorview/PaintRenderer.java`

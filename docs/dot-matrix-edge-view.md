# DotMatrixEdgeView 学习要点

## 概述
Tesla 风格的点阵边缘视图，支持触摸渐变高亮效果。

## 核心概念

### 1. 触摸位置计算
- 触摸位置用 0-1 的浮点数表示（沿边缘的比例位置）
- 垂直边缘（LEFT/RIGHT）：`pos = y / viewHeight`
- 水平边缘（TOP/BOTTOM）：`pos = x / viewWidth`

### 2. 渐变效果
- 基于距离的 alpha 渐变：`alpha = max(0, 1 - distance/highlightRadius)`
- 使用 ease-out 曲线：`alpha = pow(alpha, 1.5)`
- 触摸位置处的点最亮，向两边逐渐变暗

### 3. 动画系统
```java
// ValueAnimator 用于平滑动画
ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
animator.setDuration(400);
animator.setInterpolator(new DecelerateInterpolator());
```

### 4. 淡出动画关键点
- `fadeProgress`: 1 = 完全可见，0 = 完全透明
- 动画结束时调用 `onComplete` 回调
- **重要**：动画播放期间不能调用 `reset()`，否则会取消动画并重置状态
- 使用局部变量保存对视图的引用，避免 `activeEdgeSlider` 被提前置空

### 5. 状态管理
- `touchPosition`: 触摸位置（0-1）
- `fadeProgress`: 淡出进度
- `activeEdgeSlider`: 当前活跃的滑块引用
- `reset()`: 重置所有状态
- `resetFade()`: 只重置淡出相关状态

## 代码模式

### 渐变计算
```java
float distance = abs(pos - touchPosition);
float normalizedDist = distance / highlightRadius;
float alpha = max(0f, 1f - normalizedDist);
alpha = pow(alpha, 1.5);
alpha *= fadeProgress; // 应用淡出
```

### 淡出回调模式
```java
// 正确：保存引用后再置空
final DotMatrixEdgeView fadingSlider = activeEdgeSlider;
activeEdgeSlider = null;

if (fadingSlider != null) {
    fadingSlider.fadeOut(() -> {
        fadingSlider.setVisibility(View.INVISIBLE);
        fadingSlider.resetFade();
    });
}

// 错误：引用被置空后再使用
activeEdgeSlider = null;
activeEdgeSlider.fadeOut(...); // NPE 或无效调用
```

### 防止重复触发动画
```java
if (isRippling) return; // 已经在播放中则跳过
```

## 常见问题

### Q: 动画瞬间完成没有过渡效果
A: 检查是否在动画播放期间调用了 `reset()`，这会取消动画并重置状态

### Q: 触摸和抬起效果不连贯
A: 确保触摸和抬起使用相同的计算方式获取 `touchPosition`

### Q: 动画触发多次
A: 添加标志位防止重复触发，如 `if (isRippling) return;`

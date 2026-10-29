> **这是一个诊断版，不是已确认的修复版。** v0.3.1 的修复未能解决「三个按钮完全看不到」的问题，本版用于定位真实原因，并加了自愈兜底。

## 背景

v0.3.1 尝试修复主页面三个圆形按钮（设置 / 模拟长按 / 电脑上屏）不可见的问题，但经反馈**问题依旧存在**。定位后发现 v0.3.1 的归因（布局存档坐标越界）不足以解释「持续完全不可见」，因此本版换用「先取证、再修复」的方式。

## 本次改动

### 1. 自愈兜底（可能已直接解决问题）

新增 `DragLayout.ensureVisible()`：启动后检查三个按钮是否与容器可视区**至少有 8dp 重叠**。只要有一个不满足，就自动清空异常存档、复位为默认规则布局，并提示「按钮位置异常，已自动复位」。

这意味着即使根因未明，按钮也不会再出现「永久消失且无从恢复」的情况。

### 2. 运行时诊断

新增 `DragLayout.dumpGeometry()`，把容器与三个按钮的实际几何（`w/h/x/y/scale/leftMargin/topMargin`）输出到 logcat，tag 为 **`KeyVoiceLayout`**，输出节点为 `restore` / `reset` / `ensureVisible:OK` / `ensureVisible:MISSING`。

抓取方式（手机连电脑后）：

```
adb logcat -s KeyVoiceLayout
```

判读逻辑：

| 日志表现 | 指向的原因 |
|---|---|
| `w=0 h=0` | 按钮未参与测量 —— 父链布局问题 |
| 坐标落在容器外 | 布局存档投影问题 |
| 几何正常且 `ensureVisible:OK` | 属**渲染层**问题（图标/背景绘制失败），需检查 `Ui.iconButton()` |

### 3. 附带修掉的隐患

- `apply()` 增加 `NaN` 防护，避免异常存档产生非法坐标
- 新增 `asRelativeParams()`：不再把 `ViewGroup.LayoutParams` 强转为 `RelativeLayout.LayoutParams`，消除 `ClassCastException` 风险
- 抽出 `clearRules()` 复用布局规则清理逻辑

## 安装

1. 安装 `KeyVoice-v0.3.1-diag.apk`（versionCode 4，与 v0.3.1 一致，可直接覆盖安装，无需卸载）
2. 打开主页即可：若按钮出现，说明自愈兜底生效；若仍未出现，请用上面的 logcat 命令取日志反馈

> 注：APK 为 debug 签名，与历史版本一致。

## 修复倒持手机时提示窗错位、文字方向异常

本次修复主页面弹窗（「等待输入法弹出…」等）在手机倒持时**跑到侧边、文字方向垂直**的问题，并统一了所有页面的屏幕方向。

### 根因

`MainActivity` 是 `reversePortrait`（整体旋转 180°），但提示有两套实现，行为不一致：

| 实现 | 载体 | 方向表现 |
|---|---|---|
| `showCenterTip()` | Activity 内的 `overlay` 视图 | ✅ 随 Activity 旋转，倒持时正常 |
| `Trigger.toast()` | `Toast`（`getApplicationContext()` 创建） | ❌ **系统窗口，按设备物理方向渲染** |

关键点：**Toast 是系统窗口，不继承 Activity 的 180° 旋转覆盖**。倒持手机时 Activity 内容被转正显示给用户，而 Toast 仍按物理屏幕方向渲染，于是文字方向错乱、锚点也漂到侧边。

因此「等待输入法弹出」「已取消本次长按」「已按住空格：请说话」等由 `KeyTriggerService`（无障碍服务）发出的提示全部中招。

### 修复内容

**1. 提示统一改道 Activity 的 overlay**

- `MainActivity` 新增 `resumed` 静态前台标记（`onResume` 置位 / `onPause` 清除）与 `postTip()`
- `Trigger.toast()` 改为**优先投递到主页面 overlay**，仅在主页面不在前台时才回退 Toast
  （锁屏触发、后台听写等场景没有可见 Activity，仍需 Toast 兜底）
- 一次改动即覆盖全部 7 处调用点（`KeyTriggerService` 5 处、`VoiceService` 2 处，均经由 `Trigger.toast`）

**2. 统一屏幕方向**

此前只有 `MainActivity` 声明了 `reversePortrait`，`VoiceInputActivity` 与 `SettingsActivity` 跟随传感器——倒持时从主页进设置页、或弹出输入窗口，屏幕会突然翻转。现已为两者补上 `reversePortrait`，全 App 方向一致。

**3. 设置页的「已保存」不再用 Toast**

`SettingsActivity` 的保存提示同样中招（Activity 上下文的 Toast 依然是系统窗口）。改为直接写入页内已有的 `tvInfo` 文本，随 Activity 旋转，并移除无用的 `Toast` import。

## 覆盖安装支持

为支持**覆盖安装**（升级不丢数据、不提示签名冲突），已核验三项必要条件：

| 条件 | 状态 |
|---|---|
| `versionCode` 单调递增 | ✅ 4(0.3.1) → 6(0.3.3) → **7(0.3.4)** |
| 签名密钥一致 | ✅ 实测签名均为 `3dbde4da7be126a41d9380058befe48feed7d123e4fb5c1d2adc363af4a9bc53`，与在装版本一致 |
| `applicationId` 不变 | ✅ 保持 `com.keyvoice` |

> Android 要求覆盖安装必须使用**同一签名密钥**，否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不匹配）而拒绝安装。本次已用 `apksigner` 逐版本比对确认。

**实测记录**：此前已将 versionCode 6 成功覆盖安装到 versionCode 4 之上（`pm install -r` 返回 `Success`），覆盖安装链路已验证通过。

> vivo 机型注意：`adb install` 流式安装可能挂起；若推到 `/sdcard/` 会被 SELinux 拒绝（`avc: denied ... sdcardfs`）。
> 正确做法是先 `adb push <apk> /data/local/tmp/`，再 `adb shell pm install -r /data/local/tmp/<apk>`。

## 安装

1. 安装 `KeyVoice-v0.3.4.apk`（versionCode 7，可直接覆盖 v0.3.3 / v0.3.2 / v0.3.1，无需卸载）
2. 倒持手机，按「模拟长按」触发听写，提示将正确居中显示、文字方向正常
3. Windows 端运行 `pc/keyvoice_box.py` 并在同一局域网

> 注：APK 为 debug 签名，与历史版本一致；如需正式分发请配置固定 release keystore 并妥善备份（换密钥将导致老用户必须卸载重装）。

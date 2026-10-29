# KeyVoice v0.3.14（versionCode 18）

> 与 v0.3.x 全系列同一 debug 签名，可直接覆盖安装；
> 装过 `v1.1-android15-fix`（另一把证书）的设备需先卸载。

## 重要修复

### Android 13+ 启动即崩溃（魅族 20 Pro / Android 15 实测修复）
- **现象**：Android 15 设备上安装后启动即闪退，无法进入主界面。
- **真因**：Android 13+ 强制要求运行时注册的广播接收器必须显式声明导出性
  （`RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`），否则抛
  `SecurityException` 直接崩溃。Android 9 不做此检查，所以此前未暴露。
- **修复**：MainActivity 与 VoiceInputActivity 共 4 处 `registerReceiver`，
  Android 13+ 使用 `RECEIVER_NOT_EXPORTED`（自有广播均为显式 intent，可正常接收），
  旧版本维持原注册方式。
- **验证**：魅族 20 Pro（Android 15）安装后启动进程稳定存活、零崩溃日志；
  PC→手机消息服务正常（主页面不在前台时自动暂存，回主界面立即入框）。

## 安装提示

- **vivo**：锁屏状态下 ADB 安装会被秒拒（Failure [-200]），解锁手机再装，
  或开发者选项打开「USB 安装」。
- **魅族/Flyme**：需要开发者选项打开「USB 安装」（部分版本需登录 Flyme 账号），
  否则报 `INSTALL_FAILED_USER_RESTRICTED`。
- 装过 `v1.1-android15-fix`（另一把证书）的设备需先卸载再装本版。

## 已知待观察

- Android 14+ 对"后台启动麦克风前台服务"有限制（while-in-use），
  灭屏听写在该版本上的表现待用户实测反馈。

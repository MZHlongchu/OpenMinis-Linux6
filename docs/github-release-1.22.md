# Minis Ultra 1.22-linux

- versionCode **34**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

1. **子代理轮次可配置** — 设置 → 多智能体，默认 12 轮，范围 1–48。
2. **Android 14 广播** — `ContextCompat.registerReceiver` + `RECEIVER_NOT_EXPORTED`，避免启动崩溃。
3. **机内自构建** — `TMPDIR` 无效则 `/tmp`；aapt2 override 替换旧路径。
4. **沙箱加固** — netlog 5MB 轮转、代理 bind 竞态、隧道等双向结束、电池迟滞、通知序号、`HostEventBridge.stop()`。
5. **检查更新** — 同版本重传不提示；`pickUpgrade` 主键 versionName。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog（含历史版本）：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

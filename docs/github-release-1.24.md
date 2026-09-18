# Minis Ultra 1.24-linux

- versionCode **36**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

1. **修复 Scudo OOM 闪退** — 超长聊天/日志不再整段交给 ICU `Matcher.reset`（`utext_openUChars`）。ContentDiag 只扫头尾 8k；markdown 解析 ≤32k。
2. **冷启动 prewarm** — 跳过超过 32k 的碎片，避免一条巨大 fence 打爆 DefaultDispatcher。
3. **日志** — 日文件 8MB 封顶，读/分享不再整文件进堆。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog（含历史版本）：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

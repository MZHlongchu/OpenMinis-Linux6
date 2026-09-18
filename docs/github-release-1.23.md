# Minis Ultra 1.23-linux

- versionCode **35**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

1. **子代理真并行** — 每个 `run_subagent` 使用独立 PersistentShell 通道，不再挤在父会话那一把锁上（假并行 / 闪退）。工作区仍挂父会话目录。
2. **团队模型按槽位选** — 设置 → 多智能体：有几个并发上限就几行「子代理 N」，各自选模型或沿用主会话。
3. **WebApp 钉到主屏** — HTML 预览 / 附件长按 / 文件浏览器恢复「添加到主屏幕」。
4. **BrowserUse SameSite** — `SameSite=None` 自动带 `Secure`，按 cookie 域名种进 WebView。
5. **其它** — `HostEventHooks` 同步 `commit()`；ChatViewModel 拆出子代理 / 工具标题与参数。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog（含历史版本）：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

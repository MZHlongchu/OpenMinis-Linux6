# Minis Ultra 1.26-linux

- versionCode **38**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

对照拾忆 `spawn_agent`，把多智能体调度从多次 `run_subagent` 收成一次调用。

1. **spawn_agent** — 协调者工具改为 `spawn_agent`；`run_subagent` 仍能执行。子代理禁止嵌套派出。
2. **tasks[] 并行** — 一次调用派出多个独立切片，失败互不影响，并发上限 1–8（默认 3）。
3. **四种 kind** — `explore` 只读侦察、`plan` 只读设计、`worker` 可写、`general-purpose` 兜底。
4. **动态轮次** — 简单约 10，复杂 40–60；设置里的数字是硬上限（默认/最大 60）。
5. **write_paths** — 同一波多个 worker 必须声明互不重叠的可写前缀，执行层按协程上下文隔离。
6. **进度** — 状态条「子代理 i/N · kind · turn x/y · 当前工具」。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

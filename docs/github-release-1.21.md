# Minis Ultra 1.21-linux

- versionCode **33**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

1. **结构化子 Agent 任务书** — `## Task / Expected result / Constraints / Workflow / Collaboration`；禁止嵌套 `run_subagent`。
2. **计划讨论 AUTO** — 跳过闲聊；全程白板写入聊天；开关在设置 → 多智能体。
3. **会话装饰** — 外观里可关浮动工具栏 / 完成工具卡 / 子代理芯片 / 讨论横幅。
4. **修复 1.20-linux 构建** — crash_handler 不再链 NDK 主机 `libunwind.so`；固定 NDK r28 + aarch64 `libunwind.a`，并用 `-Wl,--no-dependent-libraries` 避开 Bionic 没有的 `libpthread`。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

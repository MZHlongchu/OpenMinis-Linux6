# Minis Ultra 1.33-linux

- versionCode **48**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

对照 XINCODE 把 Agent 工具面和 SecurityGate 补齐。子代理仍是独立循环，没有换成 AgentCore。

- 权限闸：ASK 默认；argv 风险；sha256 审计链；allow/deny；权威围栏
- 新工具：list_dir / glob / grep / web_fetch / multi_edit、shell_exec / env_exec / su_exec、dispatch_agents（探索者/审查员/编码员/研究员）、wolfpack_run、agent_plan、execute_code（Rhino）、invoke_skill / skill_manage、ask_reasoning
- describe_image 走 read_image。generate_image / transcribe_audio 未配置时会明确失败

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

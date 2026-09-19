# Minis Ultra 1.32-linux

- versionCode **46**
- applicationId `com.openminis.linux`
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版

把 XINCODE 里真正缺的三块接到 1.31 原生 Kotlin 上：定时任务、插件市场、协作角色。记忆仍用本仓库已有的 `MemoryRecallEngine`；子代理检索仍用 `grep_source`；写文件仍走 `ApprovalGate`；代码图仍在。

1. **cronjob（AlarmManager）**
   Agent 可用 `cronjob` 创建 / 列出 / 删除定时任务。写法：`30m`、`2h`、`1d`（一次性延迟）或 `every 30m` / `every 2h` / `every 1d`（间隔重复）。落到现有 `ScheduledTask`，新增 `INTERVAL` 与 `fireAtMs`，不用 WorkManager。explore / plan / 子代理禁止调度，避免套娃闹钟。系统提示词明确优先 `cronjob`，不要用 crontab/at。

2. **插件市场**
   设置 → 插件市场（深链 `minis://settings/plugins`）：
   - MCP 一键预设：Microsoft Learn、Context7、DeepWiki，写入现有 MCP 集成。
   - 远程 OpenAPI 轻量目录，安装后工具名 `online_<id>__<op>`，主会话可直接调用。
   - 出站 URL 经 `FetchUrlGuard` 做 SSRF 校验（拦截私网/回环/元数据地址）。
   - API Key 进加密 SharedPreferences，**不会进入模型上下文**。
   - 未移植 GitHub Token 连接器。

3. **协作角色（spawn_agent role）**
   把下列名字填进 `spawn_agent` 的 `role`，会注入「盯着 / 不管 / 闭嘴 / 该找谁」，并按角色收工具白名单：
   - 产品团队：秘书助理、产品经理、架构师、工程师、前端设计师、测试工程师
   - 逆向小分队：侦察兵、拆解工、分析员
   白名单按 1.31 工具对齐（含 `grep_source` / `code_graph`）。未匹配的 role 仍只当标签。设置 → 多智能体列出角色卡片。

4. **刻意没做的**
   - 不覆盖 1.31 的记忆召回引擎、审批闸、代码图。
   - 不把主会话 `grep`/`glob`/`list_dir`/`web_fetch` 替换掉子代理的 `grep_source`。
   - 不改 SOUL.md / GLOBAL.md，不提交 GitHub PAT。

5. **署名**
   上述 cron / 插件 / 角色改编自 [XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public)（GPL-3.0-or-later），见 `THIRD_PARTY_LICENSES.md`。不是整仓搬运。

安装：允许「安装未知应用」。可与官方 OpenMinis 并排安装。

完整 changelog：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

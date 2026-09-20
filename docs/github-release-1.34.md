# Minis Ultra 1.34-linux

- versionCode **49**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

给 Agent 运行时补了四项可配置能力，**不升 Room、不替换 ChatViewModel / SubAgentRunner、不拆 SecurityGate**。提示词模板和工作区规则在保存时会过滤注入类措辞。

## 新增

### 1. 会话提示词模板

- 设置 → **提示词模板**：维护模板库（名称 + 正文），可设「新会话默认」。
- 对话右上角 ⋮ → **提示词模板**：只改当前会话；**下一轮模型请求**才生效（本轮进行中的生成不受影响）。
- 每会话可单独选模板、选「无」，或跟随默认。选「无」不会被默认模板覆盖。
- 存储：SharedPreferences JSON，不升数据库。
- 深链：`minis://settings/prompt-templates`

### 2. 工具限额

设置 → **工具限额**（深链 `minis://settings/tool-limits`）：

| 项 | 默认 | 范围 | 说明 |
|---|---|---|---|
| Shell 超时 | 600 秒 | 30–1800 秒 | 同时是默认值和代理可请求的上限 |
| `file_read` 字符 | 80000 | 1000–80000 | 仍受 80KB 硬顶 |
| `file_read` 行数 | 0（不限制） | 0，或 100–20000 | 0 表示不按行截断 |
| 子代理 maxTurns | 200 | 10–200 | `spawn_agent` / 批量 spawn 共用 |

### 3. 工作区规则

- 设置 → **工作区规则**（深链 `minis://settings/workspace-rules`）。
- 磁盘：`filesDir/workspace_rules/<id>.md` + `<id>.json` + `state.json`。
- 打开的规则插入**所有会话**系统提示词（身份段之后），并写明不能覆盖权限闸。
- 关闭的规则不注入。删除会清 md/json 并撤掉 active。

### 4. SecurityGate 拦截 / 审批徽标

- 闸拒绝或用户否决后，聊天顶部红条显示工具名和原因，可关掉。
- ASK 模式下待批工具在对话里直接出允许/拒绝卡（不再只靠通知）。
- 1.33 的 SecurityGate 规则、围栏、审计链不变。

## 升级

侧载覆盖安装即可。允许「安装未知应用」。可与官方 OpenMinis 并排安装。会话、记忆、密钥都还在。

完整 changelog：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

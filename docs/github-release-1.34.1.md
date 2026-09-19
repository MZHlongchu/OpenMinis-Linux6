# Minis Ultra 1.34.1-linux

- versionCode **50**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

相对 **1.34-linux** 的行为调整：去掉提示词模板 / 工作区规则在保存时的注入措辞过滤，并且**不再**把已启用的工作区规则写入任何会话的系统提示词。模板库、工具限额、SecurityGate 闸与拦截徽标都还在。

## 变更

### 1. 保存时不再拒绝「注入 / 越狱」措辞

1.34 会在保存提示词模板或工作区规则时扫描正文，命中「忽略之前的指令」一类措辞就返回 `unsafe`，界面提示「不允许注入或越狱措辞」。1.34.1 删除了 `PromptSafetyFilter`：

- `PromptTemplateStore.save` / `WorkspaceRulesStore.save` 只拒绝空白和超长（模板 `PromptTemplateCodec.MAX_TEXT`，规则 65536 字符）。
- 会话模板渲染改为原文 `trim()`，不再 `scrub`。
- 设置页去掉对应拒绝文案；中英文 footer 不再写「会被拒绝」。

权限闸（SecurityGate）、工具审批卡、ASK 红条不受影响。

### 2. 工作区规则不再进入系统提示词

1.34 会把已打开的规则拼进 **每一次** 对话的系统提示词（身份段之后），并附带「不能覆盖工具权限 / 安全闸」说明。1.34.1：

- `ChatViewModel` 不再拼接 `WorkspaceRulesStore.renderActive()`。
- 规则仍可在设置里新建、编辑、删除、开关；磁盘格式仍是 `filesDir/workspace_rules/<id>.md` + `<id>.json` + `state.json`。
- 启用开关只记在 `state.json`，**不会**注入聊天。
- 会话提示词模板仍按会话热切换（下一轮模型请求生效）。

### 3. 1.34 仍保留的能力

- 设置 → 提示词模板（深链 `minis://settings/prompt-templates`）；对话 ⋮ 可为当前会话选模板 / 「无」/ 跟随默认。
- 设置 → 工具限额（`minis://settings/tool-limits`）：Shell 超时、`file_read` 字符/行数、子代理 maxTurns。
- SecurityGate 拦截红条与 ASK 对话内允许/拒绝卡。
- 不升 Room，不替换 ChatViewModel / SubAgentRunner，不拆 SecurityGate。

## 升级

侧载覆盖安装即可。允许「安装未知应用」。可与官方 OpenMinis 并排安装。会话、记忆、密钥都还在。同机已装 1.34（versionCode 49）会被 50 覆盖。

完整 changelog：[docs/RELEASE-NOTES.zh.md](https://github.com/tall-1997/OpenMinis-Linux/blob/main/docs/RELEASE-NOTES.zh.md)

滚动构建（main）：[`android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)

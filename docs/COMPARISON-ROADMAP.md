# OpenMinis-Linux 对照路线图

对照对象：Operit、Operit2、Eta、shiyi-agent、OmniBot。原则：**吸收产品能力，不复制其源码**；不引入 Xposed/LSPosed 系统注入；不捆绑 Shizuku APK；不在 APK 内塞本地 LLM 权重。

---

## 已实现（含 1.14-linux 及本 fork 已有能力）

| 能力 | 来源启发 | OpenMinis-Linux 落地 |
| --- | --- | --- |
| Ubuntu PRoot 沙箱 + `shell_execute` / 文件工具 / 浏览器 | 自身（OpenMinis） | 完整 Agent 循环 |
| 子 Agent `run_subagent` | OpenMinis / 多 Agent | 已有，并行上限可配 |
| 技能系统 + `skill-creator` | Eta / OpenMinis | 已有；另增 `android-sdk-mirrors`、`android-device-ops` |
| 计划讨论 | 自身 | 聊天菜单 Plan discussion |
| 人设 persona | Operit 人设 | 已接入 |
| 后台浮窗看工具状态 | Operit 悬浮窗 | `ToolOverlayController`；点胶囊回对应会话 |
| 浮窗停止任务 | Operit 悬浮窗停止 | 1.14：运行中按钮 = cancel stream + stop command |
| 通知栏 Agent 状态 / Stop | Operit 前台服务 | `AgentForegroundService` |
| Host su | 各 Root 助手 | `minis-su-cli`，设置里显式开关 |
| Shizuku 后端 | Operit / Eta | 检测并使用**用户已装**的 Shizuku，不随包分发 |
| 无障碍 GUI 自动化 | OmniBot / Operit | 已有 a11y 服务与修复引导 |
| 联网搜索 | Operit / shiyi | 1.14：`web_search`（DuckDuckGo，无 Key） |
| 大输出不炸上下文 | shiyi 长任务 | 1.14：tool-spill 到 workspace |
| 对话分享为图 | Operit2 分享卡片 | 1.14：聊天菜单「分享对话卡片」 |
| 国内镜像 | 国内使用场景 | SDK / 下载镜像 |
| 滚动预发布 APK | 自身 CI | `android-latest`；tag `v*` 出正式版说明 |

---

## 待实现（值得做，但本轮没塞进 1.14）

按性价比大致排序：

1. **子 Agent 实时状态条** — 多个 `run_subagent` 时在聊天顶栏显示各成员 running/done（Operit 多 Agent 面板的轻量版）。数据已在 tracker 里，缺 UI。
2. **搜索引擎可配** — 目前写死 DDG HTML；可加 SearXNG / 用户 Bing key，失败自动降级到 `browser_use`。
3. **对话卡片模板** — 浅色/深色、隐藏工具块、导出长图分页。
4. **浮窗展开成迷你聊天** — 现在只能回 App；半屏输入框工作量较大。
5. **技能市场订阅** — Eta 有远程技能源；我们已有导入 URL，缺签名与自动更新。
6. **无障碍「场景脚本」录制** — OmniBot 强项；应做成用户录制的 skill，而不是预置抢红包一类脚本。
7. **工具结果在 UI 里一键打开 spill 文件** — 模型已能 `file_read`，用户气泡还没入口。
8. **正式签名密钥** — CI 仍是 debug-signed release，上架/覆盖旁路包会受影响。

---

## 不建议实现

| 想法 | 原因 |
| --- | --- |
| LSPosed / Xposed / 系统框架注入（Eta 路线） | 高对抗、易封号/变砖、与「用户可感知授权」相反 |
| APK 内捆绑 Shizuku 或 Magisk 模块 | 许可与分发风险；用户应自行从官方渠道装 |
| 设备端 MNN / llama 权重打进包 | 体积爆炸、电量与发热、与云端模型产品定位冲突 |
| 复制 Operit / Operit2 源码或技能包 | LGPL / AGPL 会污染本仓库许可 |
| 静默无障碍连点、抢红包、刷量 | 恶意软件形态，拒绝 |
| 读取短信/通话记录/通知内容默认全开 | 隐私越权；除非用户当轮明确要求且已授权 |
| Google 结果页爬虫 | ToS；用 DDG / 自建 SearXNG 即可 |
| 绕过 Play Protect / 隐藏自身 | 对抗检测，不做 |

---

## 和对照项目的定位差

- **OpenMinis-Linux**：手机上的 Linux Agent（PRoot + 工具循环），Android 权限是「需要时再爬梯子」。
- **Operit / Operit2**：更像全能手机助手（搜索、悬浮窗、人设、分享）。
- **Eta**：深度系统改机（LSPosed），我们不走。
- **shiyi-agent**：工程向长任务；我们用 spill / 沙箱对齐其「别卡死」而不是对齐其全部工具链。
- **OmniBot**：无障碍 GUI 机器人；我们有 a11y，但不当成默认人格。

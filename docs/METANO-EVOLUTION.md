# OpenMinis-Linux × metano 进化层对照

对照对象：[qqzijin/metano](https://github.com/qqzijin/metano) v3.3.2（MIT）。  
原则与 `docs/COMPARISON-ROADMAP.md` 一致：**吸收产品能力，不复制其源码**；不把另一款产品的运行时塞进 APK。

记录日期：2026-09-18。已拍板（实现前）：

- 接入方式：**原生 Kotlin 进化层**（不 vendor metano，不把 FastAPI/Web/消息网关打进包）。
- 第一期范围：**三件一起做**（Be-ACTIVE、定时收割 harvest、技能改进提案），共用一套「提案 → 审批 → 注入」脊柱。
- 默认 **关**，设置里显式打开。

后续实现按本文执行；改范围先改本文。

---

## 1. 结论

metano 是给 **Claude Code 用的 Python 常驻网关**（FastAPI + 多 SQLite + 飞书/QQ/Discord + 可选自改 `.py`）。OpenMinis-Linux 是 **手机上的 Agent 循环**（PRoot + 工具 + SOUL/记忆/技能）。

要搬的是闭环，不是进程：

```
Observe → Reason → Act → Reflect → Maintain
```

本仓库已有半套**被动记忆**（模型自己调用 `memory_write` / 用户手改 `GLOBAL.md`）。缺的是：**主动从会话学习，审批后再写回 system prompt**。

---

## 2. 能力对照

| metano | OpenMinis-Linux 现况 | 差距 |
| --- | --- | --- |
| `CLAUDE.md` 标记区注入，可原子回滚 | `GLOBAL.md` **对 agent 只读**（用户手改）；每日 `YYYY-MM-DD.md` 靠 `memory_write` | 没有「学到的规则」专用注入口 |
| Honcho 信念 DRAFT → ESTABLISHED → CORE | 无 | 观察不会变成稳定规则 |
| SessionStart 按场景 tag 注入记忆 | 开会话注入 GLOBAL + 最近 3 天日志（`MemoryRepository`，最多 200 行） | 有注入，无场景 tag / 无压缩信念 |
| 技能库 + 使用频率 | `SkillRepository` + `SKILL.md` + 订阅 + 使用计数 | 没有「改技能」提案 |
| Be-ACTIVE：纠正信号立即学 | 无 | 用户说「不对」不会沉淀 |
| harvest cron（约 30min） | 无进化定时；只有闹钟/通知 offload | 会话结束即丢 |
| 经验 `DO:` / `AVOID:` + Reflexion | compaction 只压本会话上下文 | 压完不沉淀跨会话 |
| Self-Modify：扫描反模式 → 沙箱测 → git 提交 | PRoot 可改**客户**文件，不能改 APK | **永久不做自改应用代码** |
| MCP **服务端** 60+ 工具 | 我们是 MCP **客户端**（`MCPRepository` + `minis-mcp-cli`） | 不要变成 metano 服务器 |
| Discord / 飞书 / QQ / 微信网关 | 无 | 另一款产品，不进 APK |
| RAG + 知识图谱 + PPR | 无 | 第一期不做 |
| ε-greedy bandit 选模型 | 用户选模型 | 第一期不做 |
| A2A 跨设备 | 无 | 以后再说 |

人设已在 `SOUL.md`（`SoulStore` / `SystemPromptBuilder` / `minis-config` 审批）。**SOUL = 性格，LEARNED = 可回滚的行为规则**，不要混进 SOUL。

关键代码：

- `SoulStore` / `SystemPromptBuilder.identitySection` — 人格注入
- `MemoryRepository` / `MemoryTools` — `GLOBAL.md` + 每日日志 + `memory_get` / `memory_write`
- `ChatViewModel.buildSystemPrompt` — 组装 GLOBAL / 近日日志
- `SkillRepository` — `SKILL.md`、prompt 片段、`importFromContent`
- compaction — 本会话瘦身，**不**代替跨会话进化

---

## 3. 明确不做

| 想法 | 原因 |
| --- | --- |
| vendor `qqzijin/metano` 源码、Python venv、React 面板 | 运行时与手机 Agent 不匹配；体积、电量、进程模型全错 |
| 飞书 / QQ / Discord / 微信网关 | 网关产品，不是进化层 |
| 应用给自己的 Kotlin/APK 打补丁 | 等于关掉验证门；metano 自改的是自己的 `.py`，我们没有等价物 |
| 自动改 `GLOBAL.md` 全文 | 该文件契约是用户维护、agent 只读 |
| 自动改包内 / bundled / pinned 技能 | 无审批、不可回滚 |
| 第一期做本地向量、图谱、bandit、A2A | 范围膨胀；与「审批后写规则」无关 |
| 把整段聊天丢进超长 ICU regex 做收割 | 已有 Scudo/OOM 前车；收割必须走 `BoundedText` |

metano 是 MIT，**可以**借鉴协议与标记区设计；仍然不搬源码。PRoot 客户里「用户自己装 metano」是可选后路（P3），不是 App 功能。

---

## 4. 怎么做（共用一根脊柱）

三件事先做同一套提案流水线，再挂三个观察源。禁止做成三个互相写文件的子系统。

```
会话结束 / 闲时扫描
        │
        ▼
   EvolutionHarvester     观察（纠正、偏好、工具失败）
        │
        ▼
   BeliefStore            DRAFT；同主题 ≥N 次 → ESTABLISHED
        │
        ▼
   Proposal               learned_rule | skill_patch
        │
        ▼
   用户审批（通知 + 设置页）
        │
        ▼
   Act：LEARNED.md 标记区 或 用户技能补丁
        │
        ▼
   下次 buildSystemPrompt 注入（有长度帽）
```

### 4.1 落点（延伸现有模块，不新开常驻 Python）

| 层 | 放哪 |
| --- | --- |
| 观察 / 提案 | `minis-global/evolution/` 或 Room，**不要**五套 `.db` |
| 已批准规则 | `minis-global/memory/LEARNED.md`，用 `<!-- LEARNED-PREFS-START/END -->` 包住，可整段回滚 |
| 注入 | `MemoryRepository.loadLearnedPrefsFragment()`，`ChatViewModel.buildSystemPrompt` 接上 |
| 注入帽 | ≤2KB 或 ≤12 条；超额只留 CORE / 最近批准 |
| 审批 UX | 设置 → 进化（接受 / 拒绝 / 暂缓）；纠正类可弹一次通知 |
| SOUL | 继续 `minis-config` + 用户批准；进化引擎 **不写** `SOUL.md` |
| 技能 | 提案 = diff；通过后 `SkillRepository.importFromContent` 写入**用户技能目录**；bundled 只允许覆盖层 |
| 后台 | `WorkManager` 充电+闲时 harvest；Be-ACTIVE 在会话结束用现有 Provider **一次短调用**，计入用户额度 |

### 4.2 安全（手机版「宪法」）

- 提案默认 **不自动应用**
- 总开关默认 **关**
- 内置 / pinned 技能不可被进化进程改
- LEARNED 只动标记区
- 日成本熔断（次数 / token 上限，设置项）
- 进化 LLM 走现有 Provider，不另起网关
- 收割输入必须截断（对齐 `BoundedText`）

---

## 5. 记忆怎么分工

| 文件 | 谁写 | 作用 |
| --- | --- | --- |
| `SOUL.md` | 用户 / `minis-config`（审批） | 你是谁（短、稳） |
| `GLOBAL.md` | 仅用户 | 手写长期事实；引擎只读 |
| `YYYY-MM-DD.md` | `memory_write` | 发生过什么 |
| `LEARNED.md` | 引擎起草 + 用户批准 | 以后怎么做；可回滚 |
| compaction 摘要 | 系统 | 本会话瘦身，不跨会话 |

`memory_write` 与进化层并存：进化解决的是「模型经常忘了写 / 写了也不升成规则」。

harvest 必须丢掉任务日记（「继续调研」「下一步」）。`MemoryRepository` 已有 `DIAG_TASK_KEYWORDS`，收割侧复用同一词表，避免把未完成任务写成宪法。

---

## 6. 做哪些

### P1（已落地 1.25-linux）

开工顺序：**P1a 骨架 → P1b 纠正 → P1c 收割 → P1d 技能**。同一套 `Proposal`，不是三个独立功能。

**P1a 骨架（必须先做）**

- `Proposal(id, type, evidence, draft_text, status)`
- 设置页审批 + 一键回滚 LEARNED 标记区
- prompt 注入 + 长度帽
- 总开关，默认关

**P1b Be-ACTIVE**

- SessionEnd 扫最近用户句：`不对` / `错了` / `不要再` / `必须` / `记住` 等
- 后台生成 **1 条**规则提案（不当场改 prompt）
- 证据带原句引用，方便拒绝

**P1c harvest**

- 闲时扫未处理会话（已 compact 的摘要优先，省 token）
- 观察 → 同主题重复才升级成提案
- 输入截断，禁止全文 Matcher

**P1d 技能改进提案**

- 信号：同一 skill 连续工具失败，或用户纠正「按 xx 技能做的」
- 产出 `SKILL.md` 补丁提案，审批后写入用户技能目录
- 不自动 `skill-creator` 整份重写；不改 bundled 原文件

### P2（已落地 1.25-linux）

- 信念衰减 / 合并 / 陈旧度
- 周反思（规则有没有被后来对话打脸）
- 场景 tag（`backend` / `workflow`）按会话类型注入

### P3（以后再说）

- PRoot 客户里可选安装 metano（NAS / 长住 Linux 用户）
- RAG / 图谱 / 跨设备 A2A

---

## 7. 风险（P1 就要防）

- **多一次 LLM**：必须可关、有日限额；不要做成 metano 那种 30 分钟 cron。
- **LEARNED 膨胀**：注入硬顶，超额只留 CORE。
- **和 iOS 分叉**：标记区注入是 Linux fork 新契约，写进说明，不要假装 iOS 已有。
- **任务当规则**：收割过滤 `DIAG_TASK_KEYWORDS`。
- **电量**：harvest 仅充电+闲时；Be-ACTIVE 一次短调用。

---

## 8. 实现时不要踩的坑

- 不要新写一套「进化 system prompt」绕过 `buildSystemPrompt`。
- 不要让进化进程 `file_write` 直接改 `SOUL.md` / `GLOBAL.md`。
- 不要为了 Honcho 兼容引入 `honcho.db` 五表照搬；第一期 markdown + 一张提案表足够。
- 不要在热路径 `Log.d` 整段观察原文（日志帽与 OOM 前车）。
- 不要默认开启。

对照入口：`docs/COMPARISON-ROADMAP.md` 待实现第 2 条。

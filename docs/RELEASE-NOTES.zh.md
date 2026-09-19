# OpenMinis-Linux 1.34.1-linux

- versionCode **50**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.34-linux：

- 提示词模板和工作区规则保存时**不再**过滤注入/越狱措辞；删除 `PromptSafetyFilter`。
- 工作区规则**不再**写入任何会话的系统提示词（含「不能覆盖权限闸」那句）。规则仍作为设备上的 Markdown 文件库 + `state.json` 启用开关保存。
- 会话模板、工具限额、SecurityGate 拦截/审批徽标仍在。不升 Room。

---

# OpenMinis-Linux 1.34-linux

- versionCode **49**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

四项中性运行时能力（不升 Room、不替换 ChatViewModel / SubAgentRunner、不拆 SecurityGate）。提示词模板和工作区规则在保存时过滤注入类措辞。

### 会话提示词模板

- 设置 → 提示词模板：模板库 + 新会话默认。
- 对话 ⋮ → 提示词模板：只改当前会话，下一轮模型请求热切换。
- 每会话可独立选模板 / 「无」/ 跟随默认；「无」不被默认覆盖。
- SharedPreferences JSON。深链 `minis://settings/prompt-templates`。

### 工具限额

设置 → 工具限额（`minis://settings/tool-limits`）：

- Shell 超时默认 600s，范围 30–1800s（默认值即代理可请求的上限）。
- `file_read` 字符默认 80000，硬顶仍是 80KB。
- `file_read` 行数默认 0（不限制），或 100–20000。
- 子代理 maxTurns 默认 200，范围 10–200。

### 工作区规则

- 设置 → 工作区规则（`minis://settings/workspace-rules`）。
- `filesDir/workspace_rules/<id>.md` + `<id>.json` + `state.json`。
- 打开的规则插入所有会话系统提示词（身份段之后），并写明不能覆盖权限闸。

### 拦截 / 审批徽标

- SecurityGate 拒绝或用户否决：聊天顶部红条显示工具名和原因。
- ASK 待批：对话内允许/拒绝卡（1.33 闸规则不变）。

---

# OpenMinis-Linux 1.33-linux

- versionCode **48**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

对照 XINCODE/OSS 把 Agent 工具面和权限闸补齐，**不**用 AgentCore 替换 ChatViewModel，子代理仍走独立 `SubAgentRunner`。

- **SecurityGate**：默认 ASK；只读自动放行；写/高风险命令确认；`rm -rf /` 等 FATAL 直接拒绝；allow/deny 规则（deny 优先）；权威围栏（文件前缀 + 网络）；审计 sha256 链式哈希。设置 → 多智能体可改权限模式。
- **工具**：`list_dir` / `glob` / `grep` / `web_fetch` / `multi_edit`；`shell_exec` / `env_exec` 等同 `shell_execute`；`su_exec` 走客户机 `android-su`；`dispatch_agents`（内置探索者/审查员/编码员/研究员，独立工具/技能白名单，SharedPreferences 不升 Room）；`wolfpack_run`；`agent_plan`；`execute_code`（Rhino 1.7.14，仅只读工具）；`invoke_skill` / `skill_manage`；`ask_reasoning`；`describe_image` 等同 `read_image`。`generate_image` / `transcribe_audio` 在未配置时如实失败。
- 路径仍走 PRootKernel，不直接碰主机 File。记忆仍用 MemoryRecallEngine。

---

# OpenMinis-Linux 1.32.1-linux

- versionCode **47**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

热修 1.31/1.32 二次启动被「数据来自更新的版本」拦住的问题。

1.31 把 `AppDatabase` 升到 **14**（`code_symbols`/`code_edges`、`kanban_tasks`），启动前的 `DatabaseVersionGuard.CODE_DB_VERSION` 仍写 12。第一次打开 Room 把 `user_version` 写成 14，第二次守卫认为磁盘比本机构建新，拒绝打开。数据没有删，只是打不开。

1.32.1 把守卫改成 14，并加测试锁 `@Database(version)` 与守卫常量一致。装上即可继续用原来的会话。

---

# OpenMinis-Linux 1.32-linux

- versionCode **46**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

对照 XINCODE 补上 1.31 仍缺的三块：定时任务、插件市场、协作角色。记忆召回、`grep_source`、写文件审批闸、代码图保持 1.31 实现。

1. **cronjob**
   Agent 工具 `cronjob`：create / list / remove。日程 `30m`/`2h`/`1d` 或 `every 30m`/`every 2h`/`every 1d`。底层 `ScheduledTask` + AlarmManager，新增 `INTERVAL`、`intervalMinutes`、`fireAtMs`。explore/plan/子代理禁用，避免嵌套调度。提示词要求优先 `cronjob` 而不是 crontab/at。

2. **插件市场**
   设置 → 插件市场（`minis://settings/plugins`）。MCP 预设（Microsoft Learn / Context7 / DeepWiki）一键写入 MCP 集成。远程 OpenAPI 目录安装后暴露 `online_<id>__<op>`。出站 SSRF 校验（`FetchUrlGuard`）；API Key 加密存储且不进模型上下文。不移植 GitHub Token 连接器。

3. **协作角色**
   `spawn_agent` 的 `role` 可填：秘书助理、产品经理、架构师、工程师、前端设计师、测试工程师、侦察兵、拆解工、分析员。注入「盯着 / 不管 / 闭嘴 / 该找谁」并按角色收工具（含 `grep_source`）。设置 → 多智能体列出角色卡片。非目录名仍只当标签。

4. **边界**
   不覆盖 `MemoryRecallEngine`；不把 `grep_source` 换成主会话 grep 工具；不改 SOUL.md/GLOBAL.md。改编来源 XINCODE-Public（GPL-3.0-or-later），见 `THIRD_PARTY_LICENSES.md`。

---

# OpenMinis-Linux 1.30.2-linux

- versionCode **44**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **浏览器横条拖拽**
   内嵌浏览器顶部横条支持跟手拖拽：上拖展开全屏；下拖松手位置不低于默认高度则弹回收起，拖过则按原判定下拉关闭。全程弹簧动画过渡，触控区加大。

2. **进化开关状态可见**
   设置页“进化”描述从“从纠正中学习偏好（默认关闭）”改为动态显示当前状态：“从纠正中学习偏好（当前开启/当前关闭）”。

---

# OpenMinis-Linux 1.30.1-linux

- versionCode **43**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

检查更新下载图补强（小版本修复）。

1. **镜像加速兑底**
   点下载时并行探测 github.com 直连 + ghproxy.com + gh-proxy.com + mirror.ghproxy.com 四个节点的首字节延迟，选最快健康节点下载，中途某镜像挂了其他兑底。

2. **提示文案**
   下载前显示“正在检测下载节点，优选最快的…”，下载中显示当前节点；避免用户以为卡顿。

3. **后台下载 + 断点续传**
   下载在进程级作用域运行，退出页面/切后台不断；中断后 `.part` 文件留存，下次从断点续下（Range），并保留 sha256 验证 + pending 记录。

4. **旧包清理**
   每次进入检查更新页面自动删除私有目录中的旧版本/已安装的安装包（当前正在下载的保留）。

---

# OpenMinis-Linux 1.30-linux

- versionCode **42**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

多智能体设置与提问卡片的 UI 修复。

1. **步进器可直接输入数值**
   并发数、重试次数两个步进器的数字本身可点击，弹出数字键盘输入框；越界/非数字时“确定”置灰。+/- 仍保留。

2. **轮次上限项降级为说明文案**
   原“子代理轮次上限”独立设置行本就不可调，删掉；说明并入 section 脚注——由协调者按任务分配，固定 200 轮失控保护。少占一行，信息不丢。

3. **提问卡片按钮不再被遮挡**
   原整卡不可滚动，问题多/选项长时把底部“提交/跳过”挤出屏幕且无法滑动。现问题区加 `heightIn(max=420dp)` + 可滚动，按钮固定底部始终可见可点。

---

# OpenMinis-Linux 1.29-linux

- versionCode **41**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

修复 1.28 发现的一个真缺陷。

1. **explore/plan 解锁 grep_source**
   `grep_source`（1.27 新增的源码检索工具）正是为只读侦察类子代理设计，但 1.28 里它没进只读白名单 `READ_ONLY_ALLOW`，导致 explore/plan 被 `filterTools` 屏蔽、反而用不了。本版把 `GrepSourceTool.NAME` 加入白名单，只读子代理恢复可用。

---

# OpenMinis-Linux 1.28-linux

- versionCode **40**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

子代理失败重试韧性（与 1.27 轮次预算合并）。在 1.27 “单个子代理跑几轮”之外，补上“失败重试几次”。

1. **有界重试循环**
   `runOneSubAgent` 改为有界重试：端点临时错误（429 / 截断流 / EOF）按次数重试，退避 2s→5s 带随机抖动，尊重 `RateLimited.retryAfterSeconds`。`CancellationException` 绝不重试。

2. **重试轮换池内端点**
   新增 `pickRetryEntry`：重试时换到**不同 provider 实例** 的池内 entry，避开被限流的中转，不连续打同一端点。新增 `isUpstreamTruncation` 识别上游截断。

3. **重试次数可调**
   设置 → 多智能体新增“子代理重试次数”步进器（`subagent_max_attempts`，1–5，默认 3，1 = 关闭重试）。

4. **失败不连坐兄弟**
   最终失败用 return 不 throw，避免异常逃逸裫 fan-out 的 `awaitAll` 连带取消同批其他 lane；重试成功结果带 `(recovered on attempt N/M via X)` 前缀。

---

# OpenMinis-Linux 1.27-linux

- versionCode **39**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

子代理轮次预算与工具优化。

1. **轮次解钳**
   设置页的「最大轮数」不再硬钳到 60，改为由协调者按任务复杂度分配；`SubAgentRunner` 保留 200 轮绝对上限作为失控保险丝。协调者分配的 `max_turns` 直接生效。

2. **预算预警 + 迫使交稿**
   子代理跑到预算 80% 时注入 `<budget_warning>`；95% 时注入 `force=true` 强令立即交付已有结果。循环结束返回累积的部分报告，不再只留一句「撞上限」。

3. **历史滑窗压缩**
   新增 `SubAgentHistoryCompactor`：发送前对超 120k 预算的对话做滑动窗口压缩，除最新 4 条外，超 12k 的 ToolResult 截为头 1500 + 尾 500，避免长任务把上下文撑爆。

4. **子代理专属 grep_source 工具**
   新增 `GrepSourceTool`（仅子代理可见，explore/plan 只读 kind 也可用）：匹配行±上下文 / 单文件 / 目录递归 / 正则，一次调用替代多轮 file_read 翻页。

---

# OpenMinis-Linux 1.26-linux

- versionCode **38**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

对照拾忆 `spawn_agent`，把原先偏粗的 `run_subagent` 调度收成一次调用、四种角色、执行层隔离。

1. **工具改为 spawn_agent**  
   协调者工具列表只暴露 `spawn_agent`。旧会话里的 `run_subagent` 仍可执行，子代理一律禁止再嵌套派出。

2. **一次 tasks[] 并行**  
   协调者按复杂度决定派出几个队友，放进同一个 `tasks` 数组。它们共享并发上限（1–8，默认 3），一个失败不取消兄弟任务。结果按「子代理 i/N」汇总。顶层 `prompt` 仍可作为单任务写法。

3. **四种 kind**  
   - `explore`：只读侦察（file_read / web_search / 会话检索等白名单）  
   - `plan`：只读设计  
   - `worker`：可写；同一波多个 worker 必须给出互不重叠的 `write_paths`  
   - `general-purpose`：兜底，仍禁止嵌套派出  

4. **动态轮次**  
   未指定 `max_turns` 时按任务推断：简单约 10，中等 20，复杂 40–60。设置 → 多智能体的数字是硬上限（默认/最大 60）。

5. **进度条**  
   聊天顶栏芯片为「子代理 i/N · kind · run · turn x/y · 当前工具」。

6. **schema**  
   `tasks` 在 Anthropic / OpenAI / Gemini 工具定义里是 array of object，避免模型把多队友拆成多次独立调用才并行。

---

# OpenMinis-Linux 1.25-linux

- versionCode **37**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

原生 Kotlin **进化层**（对照 [metano](https://github.com/qqzijin/metano) 的 Observe→提案→审批闭环，**不 vendor** 其 Python 运行时 / FastAPI / 消息网关）。入口在设置 → 进化，**默认关闭**。打开后也只生成待审提案；用户批准前不改系统提示。永远不写 `SOUL.md` / `GLOBAL.md`。设计见 [METANO-EVOLUTION.md](METANO-EVOLUTION.md)。

### P1 提案脊柱

1. **骨架**  
   `Proposal`（学习规则 / 技能补丁 / 撤回）+ 设置页批准 / 拒绝 / 推迟 / 回滚。批准的规则写入 `minis-global/memory/LEARNED.md` 标记区（`<!-- LEARNED-PREFS-START/END -->`），注入系统提示，上限 12 条 / 2KB。回滚恢复标记区快照。

2. **Be-ACTIVE**  
   会话正常结束时扫描最近用户句：`不对` / `错了` / `不要再` / `必须` / `记住` / `don't` / `never` / `remember` 等。命中则生成 **1 条**待审规则，证据带原句。不当场改 prompt。

3. **闲时收割**  
   充电（或电量状态未知）且距上次收割 ≥30 分钟，最多扫 12 个会话、处理 3 个。同一信念至少命中 2 次才升级成提案。输入截断，禁止全文 Matcher。含「任务 / 调研 / 继续 / TODO」等任务日记用词的用户句跳过，避免把待办当成偏好。

4. **技能补丁**  
   同一 skill 路径连续工具失败 3 次才提案，补丁是 SKILL.md 追加而不是整份重写。内置 bundled 技能不改原文件，改写落到 LEARNED（「使用该技能时：…」）。

LLM 提炼日额度 8 次；连续失败 3 次熔断，改用启发式原文。

### P2 信念、周反思、场景

5. **信念生命周期**  
   `draft → established → core`。近义摘要合并（token 重叠）。21 天未命中变陈旧，42 天衰减。Core 只在用户批准「撤回」后降级。注入超额时先留 `[core]`，再留较新条目。

6. **周反思**  
   闲时收割顺带，最多每周一次。对照 LEARNED 与后来用户句：打脸则提案 **撤回**；能抽出不同规则则再提案 **收紧**。42 天未命中的已批准规则提案「撤回闲置」。启发式为空且当日额度未满时，才打一次 LLM 复核。全部待审，不自动落地。

7. **场景 tag**  
   子弹可带 `[backend]` / `[workflow]` / `[writing]`；无标签规则始终注入。场景由会话分类（如 `code`→backend、`productivity`→workflow）、标题和最近用户原文判定，**不占用** `session.category` 存储字段。设置页预览展示全部场景。

---

# OpenMinis-Linux 1.24-linux

- versionCode **36**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **超长文本不再送进 ICU Matcher**  
   2026-09-18 子代理压力测试闪退：`DefaultDispatcher` 上 `Regex` → `Matcher.reset` → `utext_openUChars`，Scudo `internal map failure (Out of memory)`。设备 RAM 充足，是进程 native 地址空间被整段 markdown/日志撑爆。ContentDiag 只扫描头尾 8k 窗口；markdown 解析硬顶 32k；超长行当纯段落；代码高亮只正则前 16k。

2. **冷启动 prewarm 不再吞下整段超大碎片**  
   原先「先加入再看 96k 预算」，一条 5MB fence 仍会被送去 DefaultDispatcher 解析。现在跳过超过 32k 的碎片。

3. **日日志封顶**  
   `minis-yyyy-MM-dd.log` 8MB 后停写；单行 4k；`readLog` / 调试 RPC / 分享兜底不再 `file.readText()` 整文件进堆。

---

# OpenMinis-Linux 1.23-linux

- versionCode **35**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **子代理真并行**  
   每个 `run_subagent` 注入 `SubAgentLane`，`shell_execute` 派到独立 PersistentShell。父会话 Mutex 不再把队友命令排成队。绑定挂载仍指向父会话 `minis-sessions/<id>`，取消/结束时关掉 lane。

2. **团队模型按槽位**  
   并发上限 N 就生成 N 行「子代理 1…N」，可重复选同一模型或留空用主会话。同一回合第 N 个并行子代理用第 N 槽。

3. **WebApp 钉到主屏**  
   打开 `WEBAPP_PIN_ENTRY_ENABLED`；聊天 HTML 附件长按、文件浏览器、Web 预览「…」菜单恢复添加主屏幕。

4. **BrowserUse SameSite**  
   `SameSite=None`（含 `no_restriction`）强制 `Secure`；`CookieManager.setCookie` 用 cookie 自己的域名 URL。

5. **其它**  
   `HostEventHooks.persist` 同步 `commit()`；ChatViewModel 拆出计划讨论 / `run_subagent` / 工具标题与参数。

---

# OpenMinis-Linux 1.22-linux

- versionCode **34**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **子代理轮次可配置**  
   设置 → 多智能体增加步进器，默认 12 轮，范围 1–48。未传 `max_turns` 时用该值；传入则夹在 1…上限。

2. **Android 14 广播注册**  
   `HostEventBridge` / `MinisApp` 改用 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`，避免 targetSdk 35 启动崩溃。粘性 `registerReceiver(null, …)` 未改。

3. **机内自构建**  
   `build_apk_aarch64.sh` / `prepare_android_sandbox.sh` / `deps/build_proot.sh`：`TMPDIR` 无效则落到 `/tmp`。`minis-android-sdk-setup` 与 `RootfsManager` 用替换而不是只追加 `android.aapt2FromMavenOverride`。

4. **沙箱代理与主机事件**  
   netlog 超 5MB 轮转；先 bind 再 `running=true`；CONNECT 隧道等双向结束再关 socket。电池 ≤15% 进 low、≥20% 才 ok。通知 ID / requestCode 用原子序号。`HostEventBridge.stop()` 注销 receiver；rootfs reset 时调用。`HostEventHooks` 读写同一把锁。

5. **检查更新**  
   同 versionName / versionCode 仅刷新时间戳不再提示升级；`pickUpgrade` 主键为 versionName。

---

# OpenMinis-Linux 1.21-linux

- versionCode **33**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **结构化子 Agent 任务书**  
   协调者 `run_subagent` 的 prompt 运行时包成 `## Task / Expected result / Constraints / Workflow / Collaboration`。任何 kind 都去掉并拦截嵌套 `run_subagent`。

2. **计划讨论 AUTO + 可见白板**  
   设置 → 多智能体：关闭 / 自动（跳过闲聊） / 每条消息。自动模式不跑短回复。完整轮次写入聊天 markdown，主会话按 Synthesis 执行。

3. **会话装饰可关**  
   设置 → 外观：浮动工具栏、工具预览、已完成工具卡（默认关）、子代理芯片、计划讨论横幅。进行中的工具仍显示。

4. **修复 1.20-linux CI**  
   `libminis_crash_handler.so` 曾链到 NDK 主机 `linux-x86_64/lib/libunwind.so`（与 aarch64 不兼容）。现固定 `ndkVersion = 28.0.13004108`，CMake 只按绝对路径链接 sysroot 里的 aarch64 `libunwind.a`，并用 `-Wl,--no-dependent-libraries` 忽略 LLVM 写入的 `pthread` 依赖（Bionic 无独立 libpthread）。

1.20-linux 标签仍在，但该次 GitHub Actions 没有产出 APK。请改下 **1.21-linux**。

---

# OpenMinis-Linux 1.20-linux

- versionCode **32**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版（对照 Operit / 拾忆 / OmniBot / Eta 的首批补齐）

1. **跨会话检索工具**  
   模型可直接调用 `search_sessions` / `read_session`（底层仍是原有会话库，不必再绕 `minis-sessions-cli`）。默认不包含当前会话；每条消息 600 字截断。

2. **子 Agent 种类与写路径**  
   `run_subagent` 增加 `kind=worker|explore|plan`、`write_paths`、`max_turns`。explore/plan 只读（无 file_write / file_edit / shell_execute）；worker 的 `write_paths` 限制文件工具前缀。

3. **默认助手入口 + 桌面小组件**  
   可在系统设置里把 Minis Ultra 设为助手（`ACTION_ASSIST`，无 LSPosed）。主屏小组件一点进入新建对话。

4. **browser_use / 内置浏览器内核**  
   目标 Chrome/151，实际跟系统 WebView APK；低于/高于 151 均可运行。聊天与文件里的 HTML 走 BrowserSheet，不伪装 UA。

5. **crash_handler 链接 libunwind**  
   `scripts/build_libunwind_aarch64.sh` 交叉编译 `libunwind.a`，CI 在 assemble 前安装进 NDK sysroot，`_Unwind_Backtrace` 可链接。

未做（下一版或你拍板）：局域网 WebChat、角色卡、本地 MNN/llama、MCP 市场、Operit2 多设备 Space、Eta 的 LSPosed/厂商助手接管。

---

# OpenMinis-Linux 1.19-linux

- versionCode **31**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **主机反向事件通道**  
   电池低电 / 恢复、Doze 进出、网络丢失 / 恢复写入 `/run/android-events.jsonl`，并刷新 `/run/minis-host-status.json`（心跳 60s）。`minis-on-event register battery_low /var/minis/hooks/pause.sh` 注册客户机钩子；命令须通过与通知按钮相同的路径 sanitizer。

2. **动态 minis-notify 按钮**  
   `minis-notify post --title --body --action-label --action-command`。Intent extra 只有 token，命令存在 SharedPreferences。路径必须在 `/var/minis` 或 `/usr/local/bin/minis-*`，拒绝 `;|&$\`()。

3. **任务级能力路由**  
   `CapabilityRouter.neededForTask` 根据文本/附件推断识图或音频，改道原因写到 Live Update / overlay 状态。

4. **沙箱长任务保活**  
   命令开始时拉起 FGS + overlay；Stop 仍取消当前作业。进程被杀后下次启动写 `/run/minis-last-sessions.json`，shell 在下一条命令时重建。

5. **沙箱 http_proxy（无 VpnService）**  
   `minis-firewall log|cut|netlog`：环回 CONNECT 代理记流量到 `/run/minis-netlog.jsonl`，一键切断返回 403。主机 LLM OkHttp 不走该代理。

6. **新设备 WebDAV 恢复向导**  
   备份 → 恢复页顶部三步：选服务器、勾选会话/记忆/技能、口令恢复。

7. **机内自编译入口（实验性）**  
   关于页「沙箱内自编译」需确认；调用 `minis-self-build`。占用磁盘大，产物不能覆盖不同签名安装。

8. **aarch64 一键脚本 + libunwind 资产**  
   `scripts/build_apk_aarch64.sh`。CI 在 NDK 中找到 `libunwind.so` 时作为 release 附件上传。

# OpenMinis-Linux 1.18-linux

- versionCode **30**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a，debug-signed）

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。

## 本版

1. **多智能体团队模型勾选失效**  
   删除服务商后，池子里残留的 UUID 仍计入并发上限，系统提示还会把这些 UUID 打成 Team models。现已：丢掉不存在的条目、并发按仍活着的勾选计算、Checkbox 不再和整行各 toggle 一次。设置页会提示已清除的失效项。

2. **沙箱防火墙 / Doze / procfs（应用内，无 LSPosed）**  
   - `minis-firewall status|set allow|wifi-only|deny`：查询网络与策略。`--strict wifi-only` 会把**整进程**绑到 Wi-Fi（含 LLM）。不会自动对 uid 做 iptables DROP。  
   - `minis-doze status|request|oem`：Doze / 省电 / 忽略电池优化；`request` 弹出系统对话框。  
   - `minis-ps` 与 `/run/minis-proc.json`：只列出本应用能读的 `/proc`（Android hidepid 会藏其他 UID）。  
   - `/run/minis-host-status.json` 增加 firewall / doze / proc 摘要。

## 1.17-linux

1. **关于页 / 检查更新指向本 fork**  
   `ProjectRepo` 改为 `tall-1997/OpenMinis-Linux`。滚动标签 `android-latest` 不再按字符串和 `1.16` 比大小；用 release body 的 `versionName` / `versionCode`，以及 APK 资源 `updated_at` 对比本机 `lastUpdateTime`。

2. **模型组能力路由**  
   当前绑定的是模型组、本轮带了图片、而选中的成员没有视觉时，自动改用组内有 `image` / `image_input` 的成员。组里没人能看图则保持原选择，Vision Group 的 `read_image` 路径不变。

3. **任务完成通知：重试 / 备份 / 清理**  
   后台任务完成通知带三个按钮，点击后由 `ExecutionCoordinator` 在对应会话沙箱执行预设命令（重试上次 shell、打包 workspace、清 `/tmp`）。Intent 只带 action key，不带自由命令。

4. **沙箱控制手机（第一档）**  
   - `minis-toast <text>`：弹出 Android Toast  
   - `minis-clipboard`：等同 `android-clipboard`  
   - `minis-open --system <url>`，以及无 TTY（cron）时的 http(s)：走 `android-open`

5. **沙箱状态文件**  
   客户机 `/run/minis-host-status.json` 约 30 秒刷新：电池温度、剩余空间、应用前台/后台、wakelock、已注册 offload 名。只用 StatFs，不递归扫描 `ubuntu-rootfs`。

## 1.16 已有能力（仍在）

- 子 Agent 工具详情流式步骤；计划讨论横幅 + 同一轮执行；存储页不阻塞扫描完整 rootfs。

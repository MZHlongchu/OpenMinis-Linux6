# Minis Ultra（OpenMinis-Linux）

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-Android%20arm64-lightgrey.svg)](#下载)
[![Release](https://img.shields.io/github/v/release/tall-1997/OpenMinis-Linux?include_prereleases)](https://github.com/tall-1997/OpenMinis-Linux/releases)

**端侧私人 AI Agent。** 把 Claude、GPT、Gemini 等模型接到手机里的一台真 Linux：Ubuntu 24.04 沙箱、浏览器自动化、技能与记忆、多智能体调度。

本仓库是 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 的 **Linux 沙箱 Android 分支**。启动器名称 **Minis Ultra**，包名 `com.openminis.linux`，可与官方 OpenMinis **并排安装**。当前 **1.36.20-linux**（versionCode 73）。检查更新 / 关于页指向本 fork：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)。

## 仓库介绍

- **沙箱即电脑**：客户机 Ubuntu 24.04（PRoot），可装包、跑脚本；主机 `su`、工具链 `minis-dev-setup`、POSIX `/sdcard` 挂载见 [LINUX.md](LINUX.md)。
- **项目工作区（1.36.14）**：一个项目文件夹里可以有多个会话，共享 `workspace` / 附件 / 浏览器缓存；日记仍按会话隔离。未分组会话保持 1.36.13 的一会话一工作区。技能 / 共享 / MCP 仍全局。
- **模型参数自动补全（1.36.1）**：先查 models.dev（去厂商前缀、统一大小写和 `./_` → `-`，再按精确 ID → 归一化 ID → 全库多数票），没有的洞用 DataLearner 补，中转站脏名（如 `GPT-6免费` / `免费GPT-6 Astra`）按命中最多的字匹配。目录和家族都认不出的 id 默认 **256k 上下文 / 128k 输出 / 开启思考（最高 max）/ 文本模态**。
- **多智能体**：主会话当协调者，设置 → 多智能体（`minis://settings/multi-agent`）。
- **签名与国内编译**：[docs/SIGNING.md](docs/SIGNING.md)、[docs/android-sdk-mirrors.md](docs/android-sdk-mirrors.md)（切勿覆盖 aarch64 aapt2）。
- **1.36.20**：工具权限和系统权限不再混成同一个开关；「本会话全部允许」与全局「全部允许」走同一闸门，拒绝规则仍优先。文件工具不能用 `..` 读到别的会话或别的项目。客户机证书改为人人可读，并补上 OpenSSL 哈希文件，非 root 的 curl / git / apt 也能校验证书。
- **1.36.18**：启动时把 Android 系统 CA 注入客户机；`minis-mirror` 不再依赖 curl（`/dev/tcp` + apt 实测）；apt 锁先 flock 再清 dpkg；开机种子安装 curl/wget/python3/git/node；`minis-open` 无 TTY 也走应用内预览。
- **1.36.17**：已归档会话每次启动收敛到项目工作区；移出/解散分组会把共享文件拷回会话；会话列表只留一个「新建文件夹」FAB。
- **1.36.16**：Termux 终端、提供商置顶与并行刷新、dpkg/pip 世界快照、一级设置无返回箭头、选中文字分享、技能 requirements 与平台环境变量；并带上 1.36.15 的工作区归档与原子写文件。
- **1.36.14**：项目工作区、折叠条只显示过程摘要、旧日记迁入会话、multipart 直通。
- **1.36.13**：会话工作区隔离；内置人格重写并在本版安装时覆盖一次 SOUL.md；移除人格扩展。
- **1.36.11**：长会话人格不被历史盖过；AI 过程折叠改为完成后立刻收起且仍能点开工具详情；子代理运行中可看日志；人格页一级返回自动保存身份。
- **1.36.10**：人格提示词显示文件名、点进二级页编辑；可导入 .md/.txt 到私有目录并用下拉选择；不同供应商可绑不同提示词。
- **中文发行说明**：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

## 下载

- **本版发行包（1.36.20-linux / versionCode 73）**：[Releases `1.36.20-linux`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/1.36.20-linux) → `minis-ultra-com.openminis.linux.apk`
- **滚动构建**：[Releases `android-latest`](https://github.com/tall-1997/OpenMinis-Linux/releases/tag/android-latest)（main 每次成功构建都会覆盖）

侧载前允许「安装未知应用」。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本，见 [docs/SIGNING.md](docs/SIGNING.md)。

### 1.36.20 要点

设置 → 权限顶部是 Agent 工具闸门，和下面的无障碍 / Shizuku 不是同一个开关。「本会话全部允许」不再被静默拒绝；拒绝规则仍然优先。文件工具不能越过会话或项目边界。客户机证书对非 root 可读，并带 OpenSSL 哈希文件。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.18 要点

Ubuntu 客户机启动时用 `AndroidCAStore`（Android 14+ 的 CA 在 conscrypt APEX，`/system/etc/security/cacerts` 经常是空目录）写入 `/etc/ssl/certs/ca-certificates.crt`。`minis-mirror auto` 用 bash `/dev/tcp` 做 HTTP 快筛，再用临时 `sources.list` 跑 `apt-get update`；HTTPS 失败会改 HTTP。主机与客户机共用一把 apt 锁，先 flock（失败则 mkdir）再清残留 dpkg 锁。开机种子安装 curl/wget/python3/git 和一次 nodejs。工具里 `minis-open`/`xdg-open` 不再把界面踢到系统浏览器。GitHub 在部分网络仍可能被拦，那是链路问题，不是缺 CA。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.17 要点

已归入项目的会话每次启动都会把仍留在私有目录的共享文件搬进项目工作区（修 1.36.14 只写了 `folder_id`、没搬文件的半归档）。从分组移出或解散分组时，共享文件会拷回该会话私有目录；有冲突则保留项目副本，避免误删。会话列表右下角只留一个「新建文件夹」FAB，新对话从文件夹卡片或长按选模型组进入。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.16 要点

终端改用 Termux VT；提供商可置顶并一键并行刷新；沙箱启动先自愈 apt 镜像再重试 dpkg/pip 世界；重置 Linux 会先快照再还原用户包。一级设置页去掉返回箭头。系统「选择文字」可分享进会话。技能 `requirements.json` 按 Debian `apt` 解析，环境变量页显示平台集成。另含 1.36.15：遗留会话归档进工作区、工具写文件原子化。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.14 要点

会话可归入项目工作区：同一文件夹共享磁盘工作区，日记仍按会话。主页 FAB 改为「新建工作区会话」。折叠的 AI 过程条只显示标题和计数。旧版 `minis-global/memory` 日记会拷进各会话。模型直通支持 multipart。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.13 要点

每个会话是独立工作区：`/var/minis/{workspace,memory,attachments,offloads,browser}` 互不可见；技能 / 共享 / MCP 装在工作区外全局目录。删除会话会删除对应工作区全部文件（含记忆）。内置人格改为详细的 Minis Ultra；本版安装时强制覆盖一次 `SOUL.md`（不论用户是否改过）。设置里的「人格扩展」已移除。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.12 要点

检查更新：没给「安装未知应用」权限时先记住请求再跳设置；从系统设置回来或进程被杀后继续原流程，不卡死、不把确认按钮灰掉。唤起安装器不再清掉已下载的包；磁盘上已有完整 APK 会直接安装。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.11 要点

有人格的已有会话不会再被早期回复的口吻拖走；「AI 过程折叠」改为思考/工具一完成就收，折叠后仍能点开工具详情；子代理运行中可看实时日志，结束输出 Trace / Report；人格页一级去掉保存按钮，返回时自动写身份。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.10 要点

人格页不再把长提示词铺在设置里：只显示文件名，点进二级页查看/编辑。+ 号从手机导入 `.md` / `.txt`，复制进应用私有目录，下拉选择当前提示词；每个供应商可单独绑定。Auto/中文/English 语言 Tad 已去掉。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.9 要点

开启「AI 过程折叠」后，回复结束会把思考和工具收进一条摘要，底部浮动工具条不再挂着已完成工具。release 包对 Rhino 整包 keep，避免 `execute_code` 因 VMBridge 反射被裁而崩。五个 `*ModelsApi` 共用一个 OkHttpClient。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.8 要点

看门狗改单调时钟并在 API 35 解冻重置心跳；流式刷新抽出 `StreamSessionController`；debug/headless 经 `ChatRuntime` 绑定、不再依赖 `ui.chat`；模型类型迁到 `:core:model`；Android models.dev 目录 gzip（约 4.2MB→424KB）；Release 开 `shrinkResources`；CI 跑单测并归档 mapping / native symbols。产品行为相对 1.36.7 不变。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.7 要点

一批小刀口修复/优化：`agentTools` 列表记忆化（此前每次工具调用/流式尝试全量重建）、provider 实例记忆化（此前回退链每回合最多新建 14 个 provider + OkHttpClient）、会话列表 `updated_at` 索引（Room 15→16）、主线程卡顿看门狗冻结伪影不再计数、非流式调用整体 deadline、`AlarmReceiver` 收紧为不可导出、429 永久容量标记分级（弱标记在末位候选改走重试）、`Retry-After` 支持 HTTP-date 并放宽到 1 小时、429 摘要脱敏。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.6 要点

聊天 `generate_image` 真正出图（落到气泡 `minis://attachments/generated/`）。按提供商 Host 走专用协议：豆包/方舟、智谱、DashScope、MiniMax；GPT/OpenAI/Gemini/Codex 原路径保留。深度求索、混元 TC3、可灵会明确报不支持。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.5 要点

应用内浏览器底栏（地址栏 / UA 设置，关闭在左、全屏在右，默认高度 80%）。检查更新会显示同版本 tag 的更新说明。网页搜索可自定义引擎，密钥配在各引擎二级页。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.4 要点

聊天里真正生成视频：纯视频模型把提示词交给 OpenAI 兼容 Videos API；文本 Agent 可调用 `generate_video`。mp4 在气泡里播放。设置增加视频输出开关，保存不再冲掉 catalog 的 video。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.3 要点

热修 1.36.2 发消息崩溃：`Flow invariant is violated`（限流闸门 `withContext` 后不能从普通 `flow { emit }` 往外送）。改为 `channelFlow`。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.2 要点

中转 429 按「host + 密钥 + 模型名」分桶；无渠道/额度不再空打。只有会话选中设置里的模型组才会按组序换人，只选提供商下的某个模型时绝不自动切换（避免切到更贵计价）。设置 → 模型组显示限流桶与重复警告。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36.1 要点

缺参模型（有 ID 没参数）真正套上 256k 上下文 / 128k 输出 / 思考 max / 文本；设置里 − / 数字 / + 点中间数字可输入，确定时按 min/max 夹紧。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.36 要点

中转模型参数自动补全（models.dev 归一化 + DataLearner + 脏名按字重合）。认不出的 id 默认 256k 上下文 / 128k 输出 / 思考 max / 文本模态。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.35.1 要点

人格字数上限真正移除（1.35 只改了注释）；技能每次启动刷新并默认全开；多智能体并发/重试只保留 +/-；`ALLOW_ALL` 不再对常规写入弹确认。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.35 要点

人格扩展、技能启动扫描、权限模式自动放行相关改动。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.34.1 要点

去掉提示词模板 / 工作区规则保存时的注入措辞过滤；工作区规则不再写入系统提示词（仍可在设置里作为 Markdown 文件库开关）。1.34 的模板热切换、工具限额、SecurityGate 徽标仍在。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.34 要点

会话提示词模板（按会话热切换）、工具限额（Shell 超时 / file_read / 子代理轮次）、工作区规则文件库、SecurityGate 拦截红条与 ASK 对话内审批卡。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.33 要点

SecurityGate（ASK 默认；argv 风险；sha256 审计链；allow/deny；权威围栏）；工具面补齐 list_dir/glob/grep/web_fetch/multi_edit、shell_exec/env_exec/su_exec、dispatch_agents（探索者/审查员/编码员/研究员独立白名单）、wolfpack_run、agent_plan、execute_code（Rhino 1.7.14）、invoke_skill/skill_manage、ask_reasoning；describe_image 走 read_image。不换 ChatViewModel，子代理仍是独立循环。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.32.1 要点

热修：1.31 把 Room 升到数据格式 14，启动守卫仍写 12，二次启动误报「数据来自更新的版本」。本版守卫改为 14，会话不用清。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.32 要点

Agent `cronjob`（AlarmManager 一次性延迟 / 间隔重复）；设置 → 插件市场（MCP 预设 + 在线 OpenAPI，SSRF 校验，密钥不进模型上下文）；`spawn_agent` 九个协作角色（盯着/不管/闭嘴/该找谁 + 工具白名单）。记忆仍用 `MemoryRecallEngine`，子代理检索仍用 `grep_source`，写文件仍走审批闸。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.26 要点

拾忆风格 `spawn_agent`：一次 `tasks[]` 并行派出 explore/plan/worker/general-purpose；简单约 10 轮、复杂 40–60 轮；并行 worker 必须 `write_paths`；状态条「子代理 i/N」显示当前轮次与工具。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.25 要点

原生 Kotlin 进化层（设置 → 进化，默认关）：纠正/闲时收割/技能补丁走提案审批，写入 LEARNED.md；信念衰减与合并、周反思撤回/收紧、按会话场景注入 `[backend]`/`[workflow]`/`[writing]`。不改 SOUL.md/GLOBAL.md。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.24 要点

修复子代理压力测试下的 Scudo OOM 闪退：markdown / ContentDiag 不再对整段超长文本跑 ICU `Matcher.reset`；日日志 8MB 封顶，读日志不再整文件进堆。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.23 要点

子代理各自使用独立 PersistentShell 通道（真并行，工作区仍在父会话）；设置 → 多智能体按并发上限生成「子代理 N」选模型；恢复 WebApp 钉到主屏；BrowserUse `SameSite=None` 自动 Secure；`HostEventHooks` 同步落盘。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.22 要点

设置 → 多智能体可配置子代理轮次（默认 12，范围 1–48）；Android 14+ 广播补 `RECEIVER_NOT_EXPORTED`；机内自构建 `TMPDIR` 兜底与 aapt2 override 替换；沙箱代理 netlog 轮转、隧道等双向结束、bind 后再置 running；电池迟滞、通知序号、同版本重传不再提示升级；`HostEventBridge.stop()` 在 rootfs reset 时注销。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.21 要点

结构化子 Agent 任务书（Task / Expected result / Constraints / Workflow / Collaboration）并禁止嵌套 `run_subagent`；计划讨论 AUTO 跳过闲聊、全程白板写入聊天；外观可关浮动工具栏 / 完成工具卡 / 子代理芯片；修复 1.20-linux CI：crash_handler 不再链到 NDK 主机 `libunwind.so`。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.20 要点

跨会话检索（`search_sessions` / `read_session`）、子 Agent `kind=worker|explore|plan` 与 `write_paths`、系统默认助手入口、主屏新建对话小组件、browser_use 跟系统 WebView（目标 Chrome/151）、crash_handler 链接 libunwind。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

### 1.19 要点

主机反向事件通道、动态通知按钮、任务级模型改道、沙箱长任务保活、`http_proxy` 流量日志/一键切断（无 VpnService）、新设备 WebDAV 恢复向导、机内自编译入口、aarch64 一键脚本。完整说明：[docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。

## 从源码构建

本分支只构建 Android arm64。PRoot 与 Ubuntu rootfs 在构建时生成，不进仓库。完整步骤见 [BUILDING.md](BUILDING.md)。

```sh
git clone --recurse-submodules https://github.com/tall-1997/OpenMinis-Linux.git
cd OpenMinis-Linux
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleDebug
```

需要 NDK r28+。国内镜像与签名见 [docs/android-sdk-mirrors.md](docs/android-sdk-mirrors.md)、[docs/SIGNING.md](docs/SIGNING.md)。

## 目录

```
src/android/      Android 应用（Kotlin / Compose）与 JNI
src/shared/       共享资源
deps/             原生依赖构建脚本
docs/             说明与发行注记
scripts/          rootfs 与开发脚本
```

## 许可

本仓库以 **[GNU GPL v3.0](LICENSE)** 分发。沙箱链接 PRoot（GPLv2），合并作品按 GPLv3 分发。第三方许可见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

问题与功能请求请提到本仓库的 [Issues](https://github.com/tall-1997/OpenMinis-Linux/issues)。

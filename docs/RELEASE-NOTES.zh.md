# OpenMinis-Linux 1.16-linux

- versionCode **28**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**

## 本版修复

1. **子 Agent 实时步骤**  
   `run_subagent` 运行中点开工具详情不再只有 “Running...”。父工具块会流式写入 turn / 工具名 / 参数摘要 / 结果片段；顶栏胶囊同步显示当前步骤。

2. **计划讨论可见且会落地**  
   开启后聊天页出现横幅。讨论过程把状态、任务和共享白板写入助手消息（不再只在结束时替换一行字）。讨论结束后**同一轮**按合成方案开始执行。关闭菜单中的「计划讨论」即可跳过开会。

3. **存储页不再卡在扫描**  
   进入存储会立刻列出会话和数据库大小；Shell 容器用缓存值 + 后台 `du`（跳过 proc/sys/dev 等），不再在首屏阻塞走完整 `ubuntu-rootfs`。

## 1.15 已有能力（仍在）

- 子 Agent 实时状态条、可配置网络搜索、对话卡片模板、浮窗迷你对话、技能订阅源、无障碍场景录制、工具 spill 一键打开

## 明确不做

- 不移植 Operit（LGPL）/ Operit2（AGPL）源码
- 不引入 LSPosed / 不捆绑 Shizuku APK / 不内置本地 LLM 权重
- 不预置抢红包等恶意无障碍脚本
- 正式签名密钥仍待配置（CI 仍为 debug-signed release）

## 许可

本 fork 保持 OpenMinis 原许可证。不要把 Operit / Operit2 源码拷进本仓库。

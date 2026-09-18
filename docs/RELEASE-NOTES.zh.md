# OpenMinis-Linux 1.15-linux

- versionCode **27**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**

## 本版新能力

1. **子 Agent 实时状态条**  
   多个 `run_subagent` 并行时，聊天页顶栏下方显示 running / done / fail 胶囊。

2. **可配置网络搜索**  
   设置 → 网络搜索：DuckDuckGo（默认、无密钥）、SearXNG 实例、Bing Web Search API。失败可回退 DDG，仍空则提示用 `browser_use` 打开具体网址。

3. **对话卡片模板**  
   分享前可选深色 / 浅色 / 纸张、隐藏工具输出、长对话分页成多张图。

4. **浮窗迷你对话**  
   后台胶囊可展开输入框，把消息注入当前会话（运行中则排队），不必先点回 App。

5. **技能订阅源**  
   技能页可订阅 SKILL.md URL 或 `{ "skills":[{ "url", "sha256" }] }` 目录；可选 SHA-256 校验；启动后自动更新。这是完整性校验，不是 PKI 签名。

6. **无障碍场景录制**  
   技能「+」菜单可把用户点击/输入录成 skill（`android-a11y-cli` 回放）。**不是**预置抢红包脚本。

7. **工具 spill 一键打开**  
   超长工具输出预览含 `minis://workspace/tool-spill/…` 链接；工具详情页可直接打开完整文件。

## 明确不做

- 不移植 Operit（LGPL）/ Operit2（AGPL）源码
- 不引入 LSPosed / 不捆绑 Shizuku APK / 不内置本地 LLM 权重
- 不预置抢红包等恶意无障碍脚本
- 正式签名密钥仍待配置（CI 仍为 debug-signed release）

## 许可

本 fork 保持 OpenMinis 原许可证。不要把 Operit / Operit2 源码拷进本仓库。

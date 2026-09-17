# OpenMinis-Linux 1.14-linux

`versionCode` 26 · `applicationId` `com.openminis.linux` · 启动器名 **Minis Ultra**

对照 Operit / Operit2 / Eta / shiyi-agent / OmniBot 后，本版落地的是**不抄代码、不越权注入、立刻能用**的能力。许可证：不移植 Operit（LGPL）与 Operit2（AGPL）源码。

## 本版新功能

### 1. `web_search` 联网搜索（无 API Key）
浮窗文案里早就有 Search，但 Agent 工具表没有这个工具。现在模型可以直接搜索（DuckDuckGo HTML），返回标题 / 链接 / 摘要。查资料优先用搜索，只有需要点页面时才 `browser_use`。

### 2. 超长工具输出外溢到工作区
`shell_execute` 等工具一旦吐出超过约 16KB 的文本，不再整段塞进模型上下文（这是聊天卡死、上下文爆掉的常见原因）。全文写入：

`/var/minis/workspace/tool-spill/<toolId>.txt`

模型会看到头尾预览，并可用 `file_read` 续读。

### 3. 后台浮窗：运行中可停止
以前任务跑着时浮窗没有关闭/停止按钮。现在运行中点胶囊上的按钮会取消当前 Agent 循环并停掉正在跑的命令（与通知栏 Stop 同一条路径）；结束后仍是关闭浮窗。点胶囊本体仍会 `minis://session/<id>` 回到对应会话。

### 4. 分享对话卡片
聊天菜单新增「分享对话卡片」：把最近对话渲成一张图片（JPEG），并附带 Markdown 文本，可发到微信 / 相册 / 任意分享目标。

### 5. 内置技能 `android-device-ops`
首次启动写入技能库。教模型按阶梯使用**已经存在**的能力：PRoot → `minis-su-cli` → 用户自备的 Shizuku → 无障碍 → 通知。明确不包含 LSPosed、不捆绑 Shizuku APK。

## 此前 Linux fork 已带上、本仓库一并发布的能力

- 国内镜像 / SDK 镜像技能
- 人设（persona）
- Host su（`minis-su-cli`）与 Shizuku 后端（需用户自行安装授权）
- 通知相关能力
- 计划讨论（Plan discussion）
- 沙箱门闸
- About 指向 OpenMinis-Linux
- rclone 在缺 aar 时的安全降级，避免整包编不过

## 安装注意

- 仅 arm64-v8a
- 若已安装旧包，直接覆盖安装即可（同 `applicationId`）
- 首次打开仍需完成 PRoot 根文件系统准备

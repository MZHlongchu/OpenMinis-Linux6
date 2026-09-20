# Minis Ultra 1.36.4-linux

- versionCode **57**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

聊天里**真正生成视频**，不再只是设置开关。输入提示词（或让 Agent 调用 `generate_video`），mp4 落到气泡里的 `![video](minis://attachments/generated/…)` 播放器。

## 变更

### 聊天：纯视频模型直接出片

会话模型如果是纯视频输出（没有文本输出，例如 Sora / Veo / Kling，或手动打开「视频输出」），发消息会跳过 Agent 循环，把你的文字当提示词调用 Videos API。气泡先显示「正在生成视频…」，完成后自动播放。可取消。失败会把原因写进同一条助手消息。

聊天模型选择器仍会提示「该模型可能无法作为 Agent」——纯视频模型请点 **仍要使用**。

### Agent 工具：`generate_video`

文本模型会话里，Agent 可以调用 `generate_video`。工具会自动找一条已开启视频输出的 OpenAI 兼容模型（当前会话模型优先），生成后返回可播放的 markdown，文件也在沙箱 `/var/minis/attachments/generated/`。

### 设置：视频输出开关会保存

模型详情「输出」增加 **视频输出**。保存时会把 `"video"` 写进模态，不再只根据图片/音频开关重建列表，避免把目录里的视频能力冲掉。页脚说明改为图片 / 音频 / 视频。

### 按模型 id 识别视频生成器

目录没给模态时，id / 名称含 `sora`、`veo`、`kling`、`runway-gen`、`luma-dream`、`hailuo`、`vidu`、`wan-video`、`video-gen`、`cogvideox` 等会推断为视频输出，从而走生成路径而不是聊天补全。

### 接口（OpenAI 兼容 / 中转）

`POST {base}/videos`，body 为 `{model, prompt}`。404/405 再试 `/video/generations`、`/videos/generations`。

支持：

- 同步：`data[].url` 下载，或响应体本身就是 mp4（用 `ftyp` 盒子识别）
- 异步：`id` / `task_id`，状态 `queued|in_progress|completed|failed`（及 success/succeeded），约 1.5s 起、之后约 5s 轮询，最多约 10 分钟
- 完成后 `GET /videos/{id}/content` 拉二进制

非 OpenAI 兼容提供商会明确报错。原生 Gemini Veo `predictLongRunning` 本版未接。

### 测试

- `VideoModalityTest`：sora/veo 推断、gpt-4o 不误判、目录已有模态不覆盖
- `OpenAIProviderVideoTest`：MockWebServer 覆盖同步 url、404 回退、轮询 + `/content`

## 未改动

- `generate_image` 仍为占位（未在本版接线）
- 数据库版本（Room 14）、签名、包名

侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.3（versionCode 56）会被 57 覆盖。

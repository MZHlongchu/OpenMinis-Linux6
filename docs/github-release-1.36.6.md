# Minis Ultra 1.36.6-linux

- versionCode **59**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

聊天里可以真正生成图片（`generate_image`）。豆包/方舟、智谱、DashScope、MiniMax 按官方 Host 走专用生图/生视频协议；GPT、Gemini、Codex 原路径保留。深度求索、混元 TC3、可灵会明确提示不支持。

## 变更

### 聊天生图

`generate_image` 不再是占位。会选用已配置的图像输出模型，把 PNG/JPEG 存到会话附件目录，气泡里用 `minis://attachments/generated/` 显示。

### 厂商路由（按 Base URL 的 Host，不按模型名瞎猜）

| 厂商 | 生图 | 生视频 |
|---|---|---|
| 豆包 / 火山方舟 | `/api/v3/images/generations` | `/api/v3/contents/generations/tasks` 轮询，立刻下载临时 `video_url` |
| 智谱 | `/api/paas/v4/images/generations` | `/videos/generations` + `/async-result/{id}` |
| DashScope | `qwen-image` 走 compatible-mode；Wanx 走原生异步 | 原生 `video-synthesis` + `/api/v1/tasks/{id}` |
| MiniMax | `/v1/image_generation` | `/v1/video_generation` → query → 取文件 |
| OpenAI / GPT / Gemini / Codex | 原路径 | 原路径 |
| Agnes | OpenAI 兼容 | OpenAI 兼容 |
| 深度求索 | 明确不支持 | 明确不支持 |
| 混元 TC3 原生 | 明确不支持（请用 hunyuan.cloud OpenAI 兼容口） | 同上 |
| 可灵 | 明确不支持（需要 JWT） | 同上 |

OpenRouter 等中继即使挂了 Seedance / CogView，仍走该中继的 OpenAI 兼容路径。

豆包文生视频：`doubao-video-gen-01` 使用 `prompt` + 5 秒 + 720p；Seedance 使用官方 `content[]`。方舟视频轮询间隔不少于 8 秒。

### 模型识别

Seedream / CogView / 混元生图 / GLM Image / Qwen-Image 等可被生图工具选中；Seedance / 豆包视频 / 混元视频等可被生视频工具选中。

## 未改动

- 浏览器底栏、检查更新说明、自定义网页搜索（1.36.5）
- 聊天视频工具本身（1.36.4），只是补了多家原生协议
- 原生 Gemini Veo 仍未接
- 数据库版本（Room 14）、签名、包名

侧载覆盖安装即可。可与官方 OpenMinis 并排安装。已装 1.36.5（versionCode 58）会被 59 覆盖。

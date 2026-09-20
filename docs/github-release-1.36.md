# Minis Ultra 1.36-linux

- versionCode **53**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a）

## 本版摘要

中转站模型参数自动补全：models.dev 归一化匹配、DataLearner 补充、脏名按命中最多字兜底；目录和家族都认不出的 id 默认 256k 上下文 / 128k 输出 / 开启思考（最高 max）/ 文本模态。

## 变更

### 1. models.dev 匹配

- 去掉厂商前缀，统一大小写和 `.` / `_` → `-`。
- 自家供应商精确 ID → 归一化 ID → 全库多数票。
- 已落库但 `maxOutputTokens` 仍为空时，发请求会再 enrich 一次。

### 2. DataLearner 补充源

models.dev 没有上下文或最大输出时，后台搜索 DataLearner 并解析详情页 `#basic-info` 与 `thinkingModes`，48 小时缓存。不覆盖目录已有字段。跳过 `llama3.1:8b`、`.gguf` 这类本地标签去网上猜。

### 3. 脏名按字重合

`GPT-6免费`、`免费GPT-6 Astra` 这类中转站名字：去掉「免费 / 中转 / free」后，按命中最多的字匹配目录。没写 Pro 不选 Pro；只写「GPT免费」不会套 gpt-4o。

### 4. 未知模型默认值

家族启发式仍覆盖 Claude / GPT-5 / GPT-6 / Gemini / Grok / GLM / Kimi 等。其余认不出的 id：

- 上下文 **256k**
- 最大输出 **128k**
- 开启思考，档位 low–max（天花板 **max**）
- 输入/输出模态 **文本**

---

**未变更**：数据库版本（Room 14）、签名、包名。

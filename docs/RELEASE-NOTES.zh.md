# OpenMinis-Linux 1.17-linux

- versionCode **29**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a，debug-signed）

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。

## 本版

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

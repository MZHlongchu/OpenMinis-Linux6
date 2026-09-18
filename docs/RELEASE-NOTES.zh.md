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

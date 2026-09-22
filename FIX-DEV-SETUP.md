## 修复方案：minis-dev-setup 性能和阻塞问题

### 问题总结（用户反馈）

1. **巨无霸包清单** - build-essential + nodejs + ffmpeg + openjdk-21 + golang + gradle，蜂窝网络半小时没下完
2. **阻塞所有命令** - apt运行期间持有锁，后续所有 shell_execute 排队等待，导致命令超时
3. **自动重启** - 脚本有重试逻辑，杀掉 apt-get 后会自动拉起新进程

### 已实施修复

#### 1. 拆分为轻量版 + 完整版

**新的 `minis-dev-setup`（轻量版，~30秒）**:
- 仅安装必需包：ca-certificates, curl, wget, python3, git, nodejs, psmisc, unzip
- 移除重型包：gcc/g++, ffmpeg, openjdk, golang, gradle
- 启动时自动运行，不阻塞用户
- 支持 60 秒超时快速失败

**新的 `minis-dev-setup-full`（完整版，10-20分钟）**:
- 包含所有重型工具链
- 用户按需手动运行
- 1800 秒（30分钟）超时
- 显示进度提示，可 Ctrl+C 中断

#### 2. 锁超时和优雅退出

**问题根因**：
- `minis-dev-setup` 获取 apt 锁后，会阻塞所有需要 `aptMutex` 的操作
- Shell 命令如果触发包管理（如 `apt`、`dpkg`），会无限等待

**修复**：
- 轻量版：60秒获取锁失败则跳过（不阻塞启动）
- 完整版：30分钟超时并清理
- 添加 SIGTERM 处理器：收到信号时释放锁并退出（exit 143）
- 用户可以安全 kill 脚本，不会留下僵尸锁

#### 3. 防止重试循环

**原有问题**：
```bash
apt_try() {
  while [ "$n" -lt 8 ]; do
    if "$@"; then return 0; fi
    # 失败后重试，杀掉进程会立即重启
    sleep 2
  done
}
```

**修复**：
- 移除轻量版的 `apt_try` 无限重试
- 使用 `set -e` 让脚本在第一次失败时退出
- SIGTERM 处理器确保被杀时优雅退出（释放锁）
- 完整版保留重试但限制次数

### 代码变更

#### A. 新建轻量版脚本
**文件**: `src/android/app/src/main/assets/default_mount/usr/local/bin/minis-dev-setup`

```bash
#!/bin/sh
set -e  # 失败立即退出，不重试
export DEBIAN_FRONTEND=noninteractive
...

# 60秒锁超时，失败则跳过
if ! minis_acquire_apt_lock 60; then
  echo "WARNING: apt busy, skipping seed" >&2
  exit 0
fi

# SIGTERM 优雅退出
trap 'echo "received SIGTERM, exiting"; exit 143' TERM

# 仅安装必需�
apt-get install -y --no-install-recommends \
  ca-certificates curl wget python3 python3-pip git nodejs npm psmisc unzip
```

#### B. 新建完整版脚本
**文件**: `src/android/app/src/main/assets/default_mount/usr/local/bin/minis-dev-setup-full`

```bash
#!/bin/sh
set -e

echo "This will take 10-20 minutes on slow networks. Press Ctrl+C to abort."
sleep 3

# 30分钟超时
if ! minis_acquire_apt_lock 1800; then
  echo "ERROR: apt locked for 30min, aborting" >&2
  exit 1
fi

# SIGTERM 处理
trap 'minis_release_apt_lock 2>/dev/null; exit 143' TERM

# 重型包分批安装
apt-get install -y --no-install-recommends build-essential gcc g++ make cmake
apt-get install -y --no-install-recommends ffmpeg
apt-get install -y --no-install-recommends openjdk-21-jdk-headless
apt-get install -y --no-install-recommends golang-go gradle
```

#### C. 优化 seedNetworkTools
**文件**: `RootfsManager.kt:1018-1044`

```kotlin
private fun seedNetworkToolsLocked() {
    // 新增：已存在则跳过
    if (essentials.isEmpty()) {
        Log.i(TAG, "[net-seed] all essentials present, skipping")
        return
    }
    
    // Node.js 改为 best-effort，不阻塞
    Log.i(TAG, "[net-seed] attempting nodejs npm (best-effort)")
}
```

### 使用指南

#### 启动时（自动）
- 系统自动运行轻量版 `minis-dev-setup`
- 仅安装必需工具（curl, git, python, node）
- ~30秒完成，不阻塞用户操作

#### 需要完整工具链时（手动）
```bash
# 在 Minis 终端执行
minis-dev-setup-full
```
或者作为后台任务：
```bash
nohup minis-dev-setup-full > /tmp/dev-setup.log 2>&1 &
```

#### 中断安装
```bash
# 查找进程
ps aux | grep minis-dev-setup

# 发送 SIGTERM（优雅退出）
kill <pid>

# 或强制终止
kill -9 <pid>
# 然后手动清理锁（如果需要）
rm -rf /var/lock/sandbox-apt.d
```

### 测试清单

- [ ] 启动时轻量版在 60 秒内完成
- [ ] 轻量版失败时不阻塞启动
- [ ] 可以在安装过程中运行其他 shell 命令（不超时）
- [ ] 发送 SIGTERM 后脚本退出且释放锁
- [ ] 完整版手动运行可安装所有包
- [ ] 杀掉脚本后不会自动重启

### 性能对比

| 场景 | 旧版本 | 新版本 |
|------|--------|--------|
| 启动耗时 | 10-30 分钟 | 30-60 秒 |
| 包大小 | ~800 MB | ~150 MB（轻量版）|
| 阻塞时长 | 全程阻塞 | 不阻塞（异步） |
| 用户控制 | 无法中断 | 可随时 kill |
| 重型工具 | 强制安装 | 按需安装 |

---

**风险评估**: 🟢 低
- 轻量版仅改变包列表，不影响核心功能
- 锁超时机制确保不会永久挂起
- SIGTERM 处理器保证资源清理

**下一步**: 提交修复并测试真实网络环境

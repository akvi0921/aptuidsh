# APTUIDSH

**一个把整套 DeepSeek Harness 装进 APK 的独立安卓应用。**

APTUIDSH 不依赖 Termux、不依赖 root、不依赖任何外部环境。APK 内部自带：

| 层 | 内容 | 体积 |
|---|---|---|
| 运行环境 | **proroot**（rootless Linux runtime，5 个 `.so`） | ~0.7 MB |
| 操作系统 | **Ubuntu 24.04.5 LTS arm64** base rootfs（glibc） | ~78 MB（xz 压缩后） |
| 运行时 | **Node.js 22.22.2** linux-arm64（装在 `/usr/local`） | — |
| 本体 | **@deepseek-ai/dsh 0.1.5-rc.1**（npm 全局安装，含全部插件） | — |
| 前端 | 原生 Compose 界面（继承自 dsh-aui）+ 内嵌官方 Web UI | — |

安装后首次启动，APP 把内置镜像解压到私有目录（约 585 MB、32500 个文件，实测 **10 秒**），
然后在 `127.0.0.1:3081` 上拉起 `dsh web`，全程离线可用。

> 端口特意选 **3081**，与本机 Termux 里跑在 3080 的 dsh 完全隔离，两者可同时运行。

---

## 一、快速开始

1. 安装 APK，打开 APP
2. 首屏「内置运行环境」卡片 → 点 **安装并启动**（约 10~30 秒）
3. 状态变绿后即可用原生界面进入对话；也可点 **官方 Web UI** 打开 dsh 自带界面
4. 首次使用需配置模型密钥：原生界面「设置 → API 密钥」，或官方 Web UI 的 Models 页

工作区默认落在 `/sdcard/APTUIDSH`（手机共享存储已绑定进 guest 的 `/sdcard`），
dsh 可以直接读写手机里的文件。

---

## 二、目录结构

```
aptuidsh/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/rootfs.tar.xz          # 内置 Linux 环境镜像（由 tools/build-rootfs.sh 生成）
│   ├── jniLibs/arm64-v8a/libproroot*.so
│   └── java/com/aptuidsh/kui/
│       ├── AptuidshApp.kt            # Application：鉴权初始化 / DNS 同步 / 自动拉起后端
│       ├── MainActivity.kt           # 首屏（环境控制卡 + 连接状态 + 后端信息）
│       ├── ChatActivity.kt           # 对话主界面（含侧边栏/输入区/审批提问卡片）
│       ├── WebUiActivity.kt          # 内嵌官方 dsh Web UI
│       ├── EnvConsoleActivity.kt     # 环境控制台（状态/启停/重装/原始日志）
│       ├── env/                      # ★ 内置运行环境层（本项目新增）
│       │   ├── ProrootEnv.java       #   路径与常量中心
│       │   ├── RootfsInstaller.java  #   assets 镜像解压安装
│       │   ├── DshBackend.java       #   proroot 进程生命周期 + 健康检查
│       │   ├── DshService.java       #   前台服务守护后端
│       │   └── DshAuth.java          #   dsh >=0.1.5 的 launchToken -> Cookie 鉴权桥
│       ├── net/                      # 协议层（DshClient / EventStream / ApiCompat ...）
│       ├── ui/                       # Compose 界面
│       └── file/                     # 文件浏览/查看/播放
├── docs/                             # 协议参考与继承自 dsh-aui 的历史文档
└── tools/
    ├── build-rootfs.sh               # ★ 可重复构建内置镜像
    └── svg2vd.py
```

---

## 三、镜像与二进制如何重建

`assets/rootfs.tar.xz`（78 MB）与 `jniLibs/*.so` **不入 Git**，由脚本生成：

```bash
bash tools/build-rootfs.sh ~/aptuidsh-rootfs 0.1.5-rc.1
cp ~/aptuidsh-rootfs/dist/rootfs.tar.xz app/src/main/assets/rootfs.tar.xz
cp ~/aptuidsh-rootfs/proroot/*.so app/src/main/jniLibs/arm64-v8a/
```

脚本内已固化下面这些**实测踩坑结论**，改动前请先读：

1. **Node 必须装到 `/usr/local`**（`tar --strip-components=1`）。
   proroot 的 guest 子进程会把 PATH 重置为 Ubuntu 默认值，`/usr/local/node/bin` 这类自定义
   目录不在其中，表现是 `node: not found`、`#!/usr/bin/env node` 直接 ENOENT。
2. **启动 proroot 必须用干净环境变量**。宿主（Termux/Android）的 `PREFIX` 等会透传进 guest，
   把 npm 的全局前缀解析到宿主路径，包装到宿主的 `node_modules` 里去（实测踩过）。
   `DshBackend` 用 `ProcessBuilder.environment().clear()` 后只注入最小集合。
3. **打包必须 `--hard-dereference`**。Android 文件系统禁止硬链接，设备端用 toybox `tar`
   解压带硬链接的归档会报 `Cannot hard link`。
4. **发行镜像里绝不能有 API Key**：构建脚本会删掉 `root/.dsh/.credentials.yaml` 的 refs 段。
5. **跨平台预编译产物要裁掉**：`node-pty/prebuilds/{darwin,win32}*`、`@img/sharp-*`、
   `@koromix/koffi-*` 只保留 `linux-arm64`，省 40 MB 以上。
6. Ubuntu base 的 `usr/bin/perl5.38.2`、`usr/bin/uncompress` 是硬链接，需用副本补齐。

---

## 四、镜像里到底装了什么

```
rootfs/
├── bin, lib, sbin, usr/...                  Ubuntu 24.04.5 LTS arm64 base
├── usr/local/bin/{node,npm,npx,dsh}         Node 22.22.2（在默认 PATH 内）
├── usr/local/lib/node_modules/@deepseek-ai/dsh    dsh 0.1.5-rc.1 本体 + 全部依赖
├── root/.dsh/profiles/...                   预热好的 dsh profile（首启无需联网拉插件）
├── root/workspace                           默认工作区（/sdcard 不可用时回退）
├── etc/resolv.conf                          DNS 兜底（APP 每次启动用当前网络覆写）
└── .aptuidsh-image                          版本标记：distro/node/dsh/proroot
```

---

## 五、运行链路

```
APP 进程
 └─ nativeLibraryDir/libproroot.so -r filesDir/rootfs -b /storage/emulated/0:/sdcard \
      -0 --link2symlink -w /root /bin/sh -c '...'
      └─ exec /usr/local/bin/dsh web --host 127.0.0.1 --port 3081 --no-open
           └─ 监听 127.0.0.1:3081（仅回环，不对外暴露）
```

- 启动器必须从 `nativeLibraryDir` 执行：Android 10+ 禁止 exec 应用数据目录里的文件，
  因此 `jniLibs` 必须开 `useLegacyPackaging = true`（在安装时解包成真实文件）。
- 后端由前台服务 `DshService` 守护，退到后台不会被系统回收。
- 停止时用 `/system/bin/kill` 按 PID 结束 guest 里的 node 进程（guest 与宿主 PID 空间一致）。

---

## 六、协议适配（重要）

内置的是 dsh **最新版**，而原生界面继承自面向 dsh **0.1.1** 的 dsh-aui，
契约发生破坏性变更，统一由 `net/ApiCompat.java` 与 `env/DshAuth.java` 收敛：

| 变更 | 0.1.1（前端原始目标） | 0.1.5（内置版本） | 处理位置 |
|---|---|---|---|
| 鉴权 | 无 | 必须携带签名 Cookie（launchToken -> `GET /?token=` -> `Set-Cookie`） | `DshAuth` |
| 载荷信封 | payload 平铺 | 必须 `payload:{args:{...}}`，键名 = 描述符形参名 | `ApiCompat.buildArgs` |
| 方法名 | `host.listDirectory` | `directoryPicker/list` | `ApiCompat.mapMethod` |
| | `session.history` | `session/page` | 同上 |
| | `agentPreset.*` / `goal.*` | `agentPresets/*` / `goals/*` | 同上 |
| | `host.describe` | **已删除** | `DshClient.syntheticHostDescribe()` 本地合成 |
| 实时流 | `/api/events.mux` | `/api/remote.mux` 单一 WS 多路复用 + `session/follow` 逻辑流 | `EventStream` |

`ApiCompat` 里"新方法名的形参名"全部取自 dsh 自带的 typert 描述符
（`@deepseek-ai/dsh-client-connection/lib/client.js` 的分发器），不是猜的；
升级 dsh 版本后应重新核对这张表。

---

## 七、构建

```bash
export ANDROID_HOME=~/android-sdk
export JAVA_HOME=$PREFIX
~/gradle-8.13/bin/gradle :app:assembleDebug --no-daemon
```

产物约 80 MB（其中 78 MB 是 rootfs 镜像）。`gradle.properties` 里那条
`android.aapt2FromMavenOverride` 是 Termux/arm64 环境必需的（AGP 自带的 aapt2 是 x86_64）。

---

## 八、已知限制

- 仅支持 **arm64-v8a**（proroot 只提供该架构的运行时）。
- 需要 **Android 8.0+**（proroot 要求 API 26+）。
- dsh 自 0.1.5 起引入浏览器鉴权，原生客户端依赖 `launchToken`；
  若 dsh 进程不是由本 APP 拉起（例如手动在 guest 里启动），Cookie 需要重新交换一次。
- rootfs 解压后占用约 585 MB，安装前请确认存储空间。

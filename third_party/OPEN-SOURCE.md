# 第三方组件与许可

APTUIDSH 自身代码为本项目所有；APK 内**打包**了下列第三方组件。分发本 APK 时请一并遵守其许可。

## 1. proroot —— rootless Linux 运行时  ★ 有强制署名条款

- 来源：https://github.com/coderredlab/proroot
- 版权：Copyright (c) 2026 coderred
- 打包内容：`arm64-v8a/` 下的 5 个 `.so`（原样分发，**未做任何修改**）
- 许可全文：[`proroot-LICENSE.txt`](./proroot-LICENSE.txt)

许可要点：

| 条款 | 要求 | APTUIDSH 的处理 |
|---|---|---|
| 1 | 可在任意应用中使用 | ✅ |
| 2 | **不得分发修改版** | ✅ 二进制原样打包，未修改 |
| 3 | 未修改版仅可作为**完整应用包**（APK/AAB）的一部分分发 | ✅ 仅随本 APK 分发 |
| 4 | 须随副本附带本许可声明 | ✅ `third_party/proroot-LICENSE.txt` + APP 内「开源许可与致谢」 |
| 5 | 必须在**应用描述 / 关于页 / 第三方许可声明**中署名 proroot | ✅ APP「环境控制台 → 开源许可与致谢」中署名 |

## 2. DeepSeek Harness（dsh）—— 内置的本体

- 来源：`npm @deepseek-ai/dsh@0.1.5-rc.1`
- 许可：**MIT**
- 打包内容：完整全局安装（含 190 个顶层依赖目录、`@deepseek-ai` 下 240 个官方包）

## 3. Node.js

- 版本：**v22.22.2**（linux-arm64 官方发行版）
- 许可：**MIT**（另含部分第三方组件，见发行版内 `LICENSE`）

## 4. Ubuntu base rootfs

- 版本：**Ubuntu 24.04.5 LTS base arm64**
- 来源：cdimage.ubuntu.com（本项目使用清华镜像）
- 许可：以 GPL / LGPL 为主，及各软件包自身许可（完整清单见 guest 内 `/usr/share/doc/*/copyright`）

## 5. 后端运行时依赖（随 dsh 一起打包）

dsh 的依赖树含大量第三方 npm 包，其中值得单独列出的是带**原生库**的几个：

| 组件 | 许可 | 说明 |
|---|---|---|
| `sharp` + `@img/sharp-linux-arm64`（libvips） | Apache-2.0 | 图像处理，含 arm64 原生二进制 |
| `node-pty` | MIT | 伪终端；镜像中已裁剪掉非 linux-arm64 的预编译产物 |
| `koffi` + `@koromix/koffi-linux-arm64` | MIT | FFI，含 arm64 原生二进制 |

其余为纯 JS 包，完整清单可在 guest 内查看：
`/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/*/package.json`

## 6. APP 前端（Android 侧）

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 |
| Kotlin 标准库 | Apache-2.0 |

## 7. 运行期调用（未打包）

| 组件 | 许可 | 用途 |
|---|---|---|
| AOSP toybox（`/system/bin/tar`、`kill`、`linker64`） | BSD-3-Clause | 解压镜像、结束进程、备用执行路径 |
| PDF.js（位于 dsh 的 `dsh-client-ui-sidebar-documentpreview` 内） | Apache-2.0 | 官方 Web UI 的 PDF 预览（也正是 `Iterator` 兼容问题的来源） |

## 8. 仅用于开发/测试（**不进入 APK**）

| 组件 | 许可 | 用途 |
|---|---|---|
| `org.json:json:20240303` | JSON License | `tools/jvmtest` 适配层测试台 |

---

## 前端来源说明

APP 的原生界面（Compose UI）复制并改造自**用户自有项目 `dsh-aui`**，非第三方开源项目。
改造内容：包名 `com.dshaui.kui` → `com.aptuidsh.kui`、应用名 DSHAUI → APTUIDSH、
后端端口 3080 → 3081、以及面向 dsh 0.1.5 的协议适配层。

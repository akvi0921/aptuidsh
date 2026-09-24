# APTUIDSH

**一个把整套 DeepSeek Harness 装进 APK 的独立安卓应用。**

APTUIDSH 不依赖 Termux、不依赖 root、不依赖任何外部环境。APK 内部自带：

| 层 | 内容 | 体积 |
|---|---|---|
| 运行环境 | **proroot**（rootless Linux runtime，5 个 `.so`） | ~0.7 MB |
| 操作系统 | **Ubuntu 24.04.5 LTS arm64** base rootfs（glibc） | ~203 MB（gzip 后） |
| 运行时 | **Node.js 22.22.2** linux-arm64（装在 `/usr/local`） | — |
| 本体 | **@deepseek-ai/dsh 0.1.7-rc.1**（npm 全局安装，含全部插件） | — |
| 前端 | **官方 dsh Web UI**（内嵌 WebView）—— 唯一的前端 | — |

安装后首次启动，APP 把内置镜像解压到私有目录（约 794 MB、32700 个文件，实测 **24 秒**），
然后在 `127.0.0.1:3081` 上拉起 `dsh web`，全程离线可用。

> 端口特意选 **3081**，与本机 Termux 里跑在 3080 的 dsh 完全隔离，两者可同时运行。

> 版本变化见 [`CHANGELOG.md`](./CHANGELOG.md)；许可见 [`LICENSE`](./LICENSE)（MIT）。

---

## 一、形态：原生侧只管环境，前端全用官方 Web UI

**2026-09-24（1.3.0）起，自研原生前端已全部移除。** 源码从 22,886 行降到 4,389 行。

```
┌──────────────────────── 原生侧（只做两件事）────────────────────────┐
│  MainActivity       首屏 · 环境控制台（装/启/停/重启/更新 + 进入 WebUI）│
│  EnvConsoleActivity 环境控制台（运行日志 / 自检 / 重装）              │
│  WebUiActivity      ← 唯一的前端：内嵌官方 dsh Web UI                 │
│  env/               proroot + rootfs 生命周期、前端服务、鉴权桥        │
└───────────────────────────────────────────────────────────────────┘
```

**为什么删掉自研前端**：官方 Web UI 由 dsh 本体自带，**永远与后端版本严格同步**；
而自研前端要为每一版 dsh 维护一份会漂移的协议适配层（0.1.1→0.1.5→0.1.7 每版都要改
方法名与形参名）。只留一套前端，等于把「协议适配」这件长期负担整体去掉。

### 快速开始

1. 安装 APK，打开 APP
2. 首屏「环境操作」→ 点 **安装并启动**（约 20~30 秒）
3. 状态变绿后点 **打开官方 Web UI** 进入界面
4. 首次使用需配置模型密钥：Web UI 的 Models 页

工作区默认落在 `/sdcard/APTUIDSH`（手机共享存储已绑定进 guest 的 `/sdcard`），
dsh 可以直接读写手机里的文件；未授予「所有文件访问」时自动回退到 guest 内 `/root/workspace`。

---

## 二、目录结构

```
aptuidsh/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/rootfs.img             # 内置 Linux 环境镜像（gzip，由 tools/build-rootfs.sh 生成）
│   ├── assets/image-version.txt      # 内置 dsh 版本标记（同上脚本产出）
│   ├── jniLibs/arm64-v8a/libproroot*.so
│   └── java/com/aptuidsh/kui/
│       ├── AptuidshApp.kt            # Application：崩溃自留地 / DNS 同步 / 自动引导安装
│       ├── MainActivity.kt           # 首屏 · 环境控制台
│       ├── WebUiActivity.kt          # ★ 唯一前端：内嵌官方 Web UI
│       ├── EnvConsoleActivity.kt     # 环境控制台
│       ├── WebPolyfill.kt            # ★ WebView 兼容垫片 + 窄屏布局覆盖层
│       ├── env/                      # ★ 内置运行环境层
│       │   ├── ProrootEnv.java       #   路径与常量中心 + 镜像指纹/版本
│       │   ├── RootfsInstaller.java  #   assets 镜像解压安装
│       │   ├── DshBackend.java       #   proroot 进程生命周期 + 健康检查
│       │   ├── DshService.java       #   前台服务守护后端
│       │   └── DshAuth.java          #   dsh >=0.1.5 的 launchToken -> Cookie 鉴权桥
│       └── ui/                       # 仅 EnvPanel.kt（控制台界面）+ theme/
├── docs/                             # 逐版本交付文档与协议/排障记录
└── tools/
    ├── build-rootfs.sh               # ★ 可重复构建内置镜像
    ├── build-apk.sh                  # 一键构建 APK
    ├── polyfill-test/                # ★ WebView 垫片与布局覆盖层自测（99 项）
    └── svg2vd.py
```

---

## 三、镜像与二进制如何重建

`assets/rootfs.img`（约 203 MB）与 `jniLibs/*.so` **不入 Git**，由脚本生成：

```bash
bash tools/build-rootfs.sh ~/aptuidsh-rootfs 0.1.7-rc.1
cp ~/aptuidsh-rootfs/dist/rootfs.img          app/src/main/assets/rootfs.img
cp ~/aptuidsh-rootfs/dist/image-version.txt   app/src/main/assets/image-version.txt
cp ~/aptuidsh-rootfs/proroot/*.so             app/src/main/jniLibs/arm64-v8a/
```

脚本内已固化下面这些**实测踩坑结论**，改动前请先读：

1. **打包必须是 gzip（`-czf`），绝不能是 xz（`-cJf`）**。Android 的 toybox tar 不支持 xz
   （它会去 exec 外部 `xz`，系统里没有），且**解压失败后退出码仍是 0**，只能靠产物校验兜住。
   脚本已加**魔数自检**：打包后立刻检查头两字节是不是 `1f8b`，不是就报错退出。
   > 这个雷埋过两轮：v1.1.0 当时只手工把镜像换成 gzip、**没改脚本**，于是后来每次重建又产出 xz。
2. **DNS 兜底必须写在 `npm install` 之前**。Ubuntu base 自带的 `/etc/resolv.conf` 是 **0 字节空文件**，
   而 proroot 的 guest 直接沿用宿主网络 —— 没有 DNS 就会 `EAI_AGAIN`，第 5 步必然失败。
3. **Node 必须装到 `/usr/local`**（`tar --strip-components=1`）。
   proroot 的 guest 子进程会把 PATH 重置为 Ubuntu 默认值，自定义目录不在其中，
   表现是 `node: not found`、`#!/usr/bin/env node` 直接 ENOENT。
4. **启动 proroot 必须用干净环境变量**。宿主（Termux/Android）的 `PREFIX` 等会透传进 guest，
   把 npm 的全局前缀解析到宿主路径。`DshBackend` 用 `environment().clear()` 后只注入最小集合。
5. **打包必须 `--hard-dereference`**。Android 文件系统禁止硬链接。
6. **发行镜像里绝不能有 API Key**：脚本会删掉 `root/.dsh` 下的凭据/会话并逐项自检。
7. **跨平台预编译产物要裁掉**：只保留 `linux-arm64`，省 40 MB 以上。

---

## 四、镜像里到底装了什么

```
rootfs/
├── bin, lib, sbin, usr/...                  Ubuntu 24.04.5 LTS arm64 base
├── usr/local/bin/{node,npm,npx,dsh}         Node 22.22.2（在默认 PATH 内）
├── usr/local/lib/node_modules/@deepseek-ai/dsh    dsh 0.1.7-rc.1 本体 + 全部依赖
├── root/.dsh/profiles/...                   预热好的 dsh profile
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
                └─ WebUiActivity 的 WebView 加载它 —— 这就是 APP 的前端
```

- 启动器必须从 `nativeLibraryDir` 执行：Android 10+ 禁止 exec 应用数据目录里的文件，
  因此 `jniLibs` 必须开 `useLegacyPackaging = true`（在安装时解包成真实文件）。
- 后端由前台服务 `DshService` 守护，退到后台不会被系统回收。
- 停止时用 `/system/bin/kill` 按 PID 结束 guest 里的 node 进程（guest 与宿主 PID 空间一致）。

---

## 六、官方 Web UI 在低版本 WebView 上的适配

本机机型系统 WebView 实测只有 **Chrome 114**（`Chrome/114.0.5735.196`），而官方 Web UI 按现代浏览器构建。
`WebPolyfill.kt` 负责下面四件事，全部由 `WebUiActivity` 用 `shouldInterceptRequest` 拦下根文档、
插到 `<head>` 之后（外壳入口是 `type="module"`，默认 defer，所以内联经典脚本必然先执行；
且必须**手动跟完重定向**——`/?token=` 会 303 下发 Cookie，而 WebView 跟随后的那次请求不再经过拦截器）。

### 6.1 JS 兼容垫片

补一批「Chrome > 114 才加入」的标准库 API，缺了会让客户端模块**导入即抛异常**
（典型表现是整页 `Failed to load plugins … Iterator is not defined`）。
全部按「存在则跳过」补齐，新内核上零副作用。

| 缺口 | 版本 | 影响 |
|---|---|---|
| `Iterator` helpers（含 `join`/`concat`/`from`） | 122 | pdf.js 的特性检测直接抛 `ReferenceError`，整页插件加载失败 |
| `Promise.try` / `withResolvers` | 128 / 119 | 多个客户端插件一 apply 就炸 |
| `Set` 的 7 个集合运算、`Object/Map.groupBy`、`Array.fromAsync` | 122~124 | — |
| `RegExp.escape` | 136 | — |
| `Uint8Array` 的 base64/hex 六个方法、`Response/Blob/Request.bytes()` | 132~144 | PDF 预览链路 |
| `Math.sumPrecise` | 147 | pdf.js 内嵌 worker |
| `AbortSignal.any/timeout`、`Symbol.dispose/asyncDispose`、`URL.parse` | 116~126 | — |

除页面作用域外还有一层 **worker 注入守卫**：pdf.js 的 worker 是独立全局作用域
（由 `new Worker(URL.createObjectURL(new Blob([源码])))` 创建），页面垫片到不了它，
而它在 worker 里用了 16 处 `Math.sumPrecise`。守卫只在「type 是 JS 且 parts 全是字符串」时
把垫片源码前置进那段 blob，其余 Blob 原样放行。

### 6.2 `URL` 的非特殊 scheme authority（**这是「文件打不开」的根因**）

真机实测：这个 WebView **不解析非特殊 scheme 的 authority**。

| `new URL('dsh-resource://file/session/sid/a.js')` | `protocol` | `hostname` | `pathname` |
|---|---|---|---|
| 规范实现（Node / 新内核） | `dsh-resource:` | `file` | `/session/sid/a.js`（72 字符）|
| **真机 Chrome 114 WebView** | `dsh-resource:` | **（空）** | **`//file/session/sid/a.js`（78 字符）** |

多出来的 6 个字符正好是 `//file` —— 整段 `//host` 被当成了路径。
而 dsh 的资源模型靠 hostname 判断地址归哪个 provider 管：

```js
// dsh-client-resources
function protocolOf(address) {
  parsed = new URL(address);
  if (parsed.protocol !== 'dsh-resource:') return void 0;
  return parsed.hostname === '' ? void 0 : parsed.hostname.toLowerCase();   // ← 这里返回 undefined
}
```

于是 `providerOf(undefined)` 恒为 `undefined`，记录的 status **永久**是 `none`，
预览就一直显示「文件资源服务不可用」；更麻烦的是 provider 注册时那句补救
`recordsOf(protocol).attach(record)` 也永远匹配不到它（该记录自己的 `protocol` 同样是 `undefined`）。

修法是给 `URL.prototype` 的 `hostname` / `pathname` 打补丁，但**先特性探测**：

```js
var compliant = new U('dsh-probe://host/path').hostname === 'host';
if (compliant) { return; }        // 内核正确，一个字都不改
```

只在三个条件同时成立时才动手（原生 `hostname` 为空、原生 `pathname` 以 `//` 开头、
串里确实写了 `scheme://authority`），因此 `http://a//b` 这种特殊 scheme 的双斜杠路径、
`foo:/bar`、`data:` 都不会被误伤。

### 6.3 窄屏布局覆盖层（设置页 左右 → 上下）

官方设置弹窗是**桌面双栏**设计（固定 `width:800px`，左侧一条 **188px 竖排导航**），
在手机上内容列被压到几十像素；中文的 `min-content` 就是一个汉字，于是标签被逐字换行竖排。
覆盖层把弹窗改成**导航在上、内容在下**，窄屏下设置行也改成标题在上、控件在下。

**注意**：dsh 的插件样式是运行时 `appendChild` 到 `<head>` 末尾的，永远排在我们的 `<style>` 之后，
所以覆盖层的布局属性**必须带 `!important`**；选择器用 dsh 的 CSS Module 类名前缀
（`[class~="VOzbGW_panel"]` 等），**升级 dsh 后需重新核对前缀**（方法见
`docs/只留官方WebUI与首页重做-1.3.0.md` §1.4）。

### 6.4 启动竞态：让 `loader.await()` 等到插件名单收敛

这是本仓库**唯一一处有意改变 dsh 行为**的补丁，因为它不补就必然踩到。
dsh 的 web boot 内核是这样的：

```js
await entries.start(loader, manifest);   // 内部 await loader.await()
await loader.await();                    // 只等「模块加载完成」
nE(ctx, modules);                        // 一次性、无重试：任一 entry 不是 active 就 throw
```

而 `remote.*` 这些命名空间，要等 `dsh-api-remotes.apply` 里**串行**的 22 次
`await ctx.remote.$mount(contribution)` 跑完才会出现。于是「模块都下好了」与
「服务全部就位」之间存在一个窗口；内核恰好在窗口里做全量检查，一旦不通过就抛错，
页面**永久**停在 `Failed to load plugins`。旧内核上这个窗口更大，所以是必现的
（这也是「文件打不开」的同一个根因：roster 没激活完时预览拿不到 provider）。

修法：拿到一个插件的 cordis `ctx` 后，把 `loader.await()` 包成「原有语义 + 等插件名单收敛」。
收敛判据是 `loader.entries()` 里 `fiber.state` 不再是 `PENDING/LOADING`（`2=ACTIVE`、`3=FAILED` 都不再等）。

**有界、可退让**：3 秒无进展或 20 秒总时长即放手，让 dsh 原本的检查照旧执行 ——
它只负责「别检查得太早」，绝不把失败伪装成成功。实测正常收敛只要 **58~231 ms**，
对启动几乎没有额外开销。

为此需要拿到 `ctx`，办法是给 `@deepseek-ai/dsh-client-resources` 这一个插件的 `factory`
包一层（它的 `inject` 只有 `slots`，是最早 apply 的插件之一）。两个必须注意的点：

- `window.__ModuleLoader__` 自始至终是**同一个对象**，但 dsh 的 `create()` 会把队列模式的
  `load` **原地替换**成「活体注册模式」的 `load` —— 所以只能挂访问器自动接住替换后的版本，
  接一次会接空；
- 注册对象只**就地**改 `factory`，绝不新建/替换它（同一性必须保住）。

---

## 七、构建

```bash
bash tools/build-apk.sh debug        # 推荐：内含 pty 包装（见下），并会检查 gradle 退出码
```

它等价于：

```bash
export PATH=~/gradle-8.13/bin:$PATH
export ANDROID_HOME=~/android-sdk
export JAVA_HOME=$PREFIX
TERM=xterm-256color script -q -f -c "gradle :app:assembleDebug --console=rich" /dev/null
```

产物约 **211 MB**（其中约 203 MB 是 rootfs 镜像）。`gradle.properties` 里那条
`android.aapt2FromMavenOverride` 是 Termux/arm64 环境必需的（AGP 自带的 aapt2 是 x86_64）。

> 交付用的是 **debug APK**（沿用 debug 证书，可覆盖安装）。看构建进度必须走
> `script -qf`：非交互环境 Gradle 只在 TTY 下画进度条，且**不能接管道/重定向**。

---

## 八、已知限制

- 仅支持 **arm64-v8a**（proroot 只提供该架构的运行时）。
- 需要 **Android 8.0+**（proroot 要求 API 26+）。
- dsh 自 0.1.5 起引入浏览器鉴权，WebView 依赖 `launchToken`；
  若 dsh 进程不是由本 APP 拉起，Cookie 需要重新交换一次。
- rootfs 解压后占用约 794 MB，安装前请确认存储空间。
- 官方 Web UI 是桌面优先的界面，手机上靠 §6.2 的覆盖层兜住布局；
  它本身没有为手机做完整适配。

---

## 九、首次启动失败时怎么定位

「环境控制台」里有 **运行自检** 按钮，它会真实执行两条命令并把原始输出贴出来：

1. **启动器自检**：直接 exec `nativeLibraryDir/libproroot.so`（不带参数，打印用法后退出）。
   这一步专门验证 Android 10+ 那条硬规则——**只允许 exec nativeLibraryDir 里的文件**。
   装 794MB 之前会先跑它，不通过就直接报错，不会白装。
2. **Guest 自检**：进 rootfs 跑
   `/bin/sh -c 'echo APTUIDSH-SMOKE-OK; uname -m; /usr/local/bin/node -v; /usr/local/bin/dsh --version'`，
   验证 proroot + rootfs + 动态链接 + Node + dsh 整条链。

常见失败与含义：

| 现象 | 含义 / 处理 |
|---|---|
| `无法执行内置 proroot 启动器：Permission denied` | nativeLibraryDir 里没有真实文件。检查 `.so` 是否为 Deflated（安装时才会解包） |
| `zcat: not gzip` 后接 `rootfs 不完整，缺少 bin/sh` | **镜像打包格式不是 gzip**（见 §三.1）。注意 toybox 这时退出码仍是 0，只能靠产物校验发现 |
| `自检未通过：guest 自检失败（exit=…）` | rootfs 不完整；到控制台点「重装环境」 |
| `启动超时（90 秒内端口未就绪）` | 看运行日志尾部；常见原因是 DNS 不通（APP 会自动写入当前网络的 DNS 到 guest 的 `/etc/resolv.conf`） |
| `dsh 后端进程已退出（exit=N）` | 进程起来了又挂了，退出码 + 日志在控制台里 |
| `3081 端口已被另一个 dsh 实例占用，且无法取得其鉴权凭据` | 端口上有别人的 dsh（例如 Termux 环境误起在 3081）；先停掉再点「重启」 |

> APP 用 **HTTP 身份探测**（401 且响应体含 `dsh`，或 200/303）而不是裸 TCP 判断 3081 上是不是自己的后端，
> 避免把恰好占用该端口的其它服务误当自己的实例接管。

---

## 十、改了 WebView 兼容层一定要跑的门禁

```bash
bash tools/polyfill-test/run.sh      # 不需要后端、不需要设备，秒级
```

三套测试都从 `WebPolyfill.kt` **抽真实源码**再执行（单一真源，绝不手抄一份到测试里）：

| 测试 | 断言数 | 内容 |
|---|---|---|
| `check.mjs` | 117 | 垫片的存在性与语义；幂等、原生已存在时不覆盖；**C 组差分对拍**（`RegExp.escape` 675 样本 / base64+hex 各 162 / `URL.parse` 675 —— 能用原生对拍就别手写期望值，`RegExp.escape` 的转义规则就是被它抓出来重写的）；窄屏覆盖层（选择器齐全、布局属性全带 `!important`、花括号配平）；以及「Kotlin 原样字符串不得出现美元符」这类**编译期陷阱**的守卫 |
| `settle-test.mjs` | 9 | §6.4 那个 boot 补丁：用**真实时钟**验证「已收敛就立刻返回」「竞态中会等」「永久卡住约 3 秒放手」，绝不挂死启动 |
| `url-authority-test.mjs` | 15 | §6.2 那个 URL 补丁：**先把 `URL` 换成复刻旧内核缺口的假体**（否则只会得到「Node 本来就对」的假绿），复现故障 → 验证修复 → 再验证特殊 scheme 的双斜杠路径不被误伤、规范内核上零副作用 |

---

## 十一、许可

APTUIDSH 自身代码以 **MIT** 发布，见 [`LICENSE`](./LICENSE)。

APK 内**打包**了若干第三方组件（proroot、dsh、Node.js、Ubuntu rootfs 等），
各有自己的许可，其中 **proroot 有强制署名条款**。分发 APK 前请阅读
[`third_party/OPEN-SOURCE.md`](./third_party/OPEN-SOURCE.md) 与
[`third_party/proroot-LICENSE.txt`](./third_party/proroot-LICENSE.txt)；
APP 内也在「环境控制台 → 开源许可与致谢」中同步署名。

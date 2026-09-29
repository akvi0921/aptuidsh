# 内置 dsh 升级 · 对照工具

**用途**：把「升级内置 dsh 会踩到的东西」变成机器可比的清单，而不是靠回忆。
每次升级 dsh 前后各取一份快照，`diff` 一次就知道**哪些本项目补丁/垫片会失配**。

背景：本项目有两处补丁**直接依赖 dsh 的内部实现**，dsh 一改就可能失效或不再需要：

| 补丁 | 依赖的 dsh 内部实现 | 失配后果 |
|---|---|---|
| `WebPolyfill.BOOT_SETTLE` | shell boot 内核的 `entries.start()` / 状态常量表 / `did not activate` 检查 | 整页停在 `Failed to load plugins`（或补丁变成多余） |
| `WebPolyfill` 的 URL authority 补丁 | `dsh-client-resources` 的 `protocolOf()` 用 `new URL(addr).hostname` 判 provider | 文件预览报「文件资源服务不可用」 |

另外窄屏布局覆盖层依赖 dsh 的 **CSS Module 类名前缀**（失配不报错，只是悄悄恢复左右布局）。

## 用法

```bash
# 0. 先把新版本装到某个 scratch 路径（不要在现有安装上覆盖安装；见下方「覆盖安装的坑」）
#    推荐在 guest 内做，保证 arm64/glibc 与生产一致：
#    npm install -g @deepseek-ai/dsh@<新版本> --prefix /root/g<版本>

# 1. 旧版快照（拿现网安装）
node tools/upgrade-diff/snapshot.mjs /path/to/node_modules/@deepseek-ai old.json
# 2. 新版快照
node tools/upgrade-diff/snapshot.mjs /path/to/新安装/node_modules/@deepseek-ai new.json
# 3. 对照
node tools/upgrade-diff/diff.mjs old.json new.json
```

`snapshot.mjs` 的参数是 **`@deepseek-ai` 目录本身**（不是 `node_modules`）。

## 抽出来的对照面（以及每条要看什么）

| 快照字段 | 含义 | 怎么判 |
|---|---|---|
| `methods` / `methodNamespaces` | 客户端 RPC 方法表，抽自 `dsh-api-remotes/lib/client.js` 的 descriptor `id` | **有删除就是破坏性变更**（0.1.5→0.1.7 删过 `subagents/list`）。注意 id 前缀不都是 `dsh-api-*`，还有 `dsh-plugin-manager`/`dsh-cordis-host-runner`/`dsh-goal`/`dsh-llm` 等，只按 `dsh-api-*` 抽会漏掉一整类 |
| `cssPrefixes` / `cssFeatures` | 设置页的 CSS Module 前缀与三条特征串（`width:188px` / `width:800px` / `_row{…justify-content:space-between…}`） | 覆盖层选中的前缀**被删**就要改选择器；特征串还在说明版面结构没变 |
| `bootKernel` | shell（`dsh-web-frontend/dist/assets/index-*.js`）里的 `did not activate` / `entries.start(` / 状态常量表 / `waiting for service` | 全在 → `BOOT_SETTLE` 挂点未变 |
| `protocolOfExcerpt` | `dsh-client-resources` 的 `protocolOf()` 原文 | 仍出现 `parsed.hostname === "" ? void 0 : …` → URL authority 补丁仍必要且匹配 |
| `versions` | 全部 `@deepseek-ai/*` 包版本 | 看有没有包被移除/新增 |
| `pluginIds` | 带 `lib/client.js` 的客户端插件清单 | 新增插件 = WebView 里多加载一份代码，要重跑垫片体检 |
| `nativePkgs` | 含 `.node` 原生二进制的包 | 新增原生包要确认有 **linux-arm64** 产物（proroot 跑的是 arm64 glibc） |

## 配套（必须一起做，否则只比一半）

**① 垫片缺口体检**——`snapshot` 只比对「本项目已知的挂点」，不负责发现**新的**缺口。
用 `~/aptuidsh-recon/scan/audit_browser_api.py <@deepseek-ai 路径> 114`（BCD 导出「Chrome > 114」全集 → 扫 dsh 真正下发的脚本 → 分类报出），
**然后必须逐条回源码上下文判真伪**：纯正则假阳性很多（实测 `Fence`/`Viewport`/`Observable` 全在**注释**里；`Schema.union(` 被误认成 `Set.prototype.union`）。
若发现新缺口，按 `WebPolyfill.kt` 里「存在则跳过」的既有写法补，并跑门禁 `bash tools/polyfill-test/run.sh`。

**② 镜像重建**——用 `bash tools/build-rootfs.sh <工作目录> <dsh版本>` 做**全量重建**（它会 `rm -rf rootfs` 重新解压 base，顺带清掉 scratch 安装与缓存），
并用 tag 里的 dsh 版本号核对产物（它自己会写 `image-version.txt` 并做 gzip 魔数自检）。

## 覆盖安装的坑（实测）

在**已有**安装上 `rm -rf <pkg 目录>` 再重装到同一前缀，会出现：

```
npm warn tar TAR_ENTRY_ERROR EISDIR: illegal operation on a directory, open '.../xxx.d.ts'
```

每次落在不同的 `.d.ts` 上。**优先用全量重建**（干净安装）。该警告只影响 TypeScript 类型文件、
不影响运行时，但**不要把「`dsh --version` 能打印」当作装对了的证据** —— 要拿硬证据：
直接从 tar 里抽 `dsh/package.json`，例如

```bash
env -i PATH=/system/bin:/system/xbin /system/bin/tar -xzOf rootfs.img \
  ./usr/local/lib/node_modules/@deepseek-ai/dsh/package.json | grep '"version"'
```
（注意归档内是 `./usr/...`，没有 `rootfs/` 前缀。）

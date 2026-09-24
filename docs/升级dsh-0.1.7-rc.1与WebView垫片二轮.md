# APTUIDSH 1.2.0 · 内置 dsh 升级到 0.1.7-rc.1 与 WebView 兼容垫片第二轮

> 版本：1.2.0（versionCode 15）　日期：2026-09-24
> 内置 dsh：**0.1.5-rc.1 → 0.1.7-rc.1**　rootfs 镜像：136,645,275 B → **122,211,356 B**

---

## 一、升级目标版本的选择

`@deepseek-ai/dsh` 在 npm 上有两个「最新」口径：

| dist-tag | 版本 | 发布时间 |
|---|---|---|
| `latest` | 0.1.5-rc.3 | 2026-09-22 |
| `next` | **0.1.7-rc.1** | 2026-09-23 |

内置版本是 0.1.5-rc.1。`latest` 只是同版本线的补丁更新，**真正最新的是 `next` 上的
0.1.7-rc.1**（跨 0.1.6/0.1.7 两条版本线，晚 13 天）。经与用户确认，本次升级到
**0.1.7-rc.1**。`registry.npmmirror.com` 已同步该版本（构建脚本走它）。

---

## 二、重建内置镜像

```bash
bash tools/build-rootfs.sh ~/aptuidsh-rootfs 0.1.7-rc.1
cp ~/aptuidsh-rootfs/dist/rootfs.img app/src/main/assets/rootfs.img
```

### 2.1 首次构建失败：guest 里没有 DNS

第一次构建在第 5 步（`npm install -g`）就挂了：

```
npm error code EAI_AGAIN
npm error request to https://registry.npmmirror.com/@deepseek-ai%2fdsh failed:
        getaddrinfo EAI_AGAIN registry.npmmirror.com
/bin/sh: dsh: not found
[proroot] child exited with code 127
```

**根因**：Ubuntu base 自带的 `/etc/resolv.conf` 是**0 字节空文件**
（`-rw------- 0 Sep 8 08:21 rootfs/etc/resolv.conf`），而 proroot 的 guest 直接沿用宿主网络 ——
没有 DNS 就解析不了 npm registry。而 `build-rootfs.sh` 原先只在**第 6/7 步**（npm 之后的
清理阶段）才写 DNS 兜底，对空 `resolv.conf` 的 base 镜像等于没写。

**修法**：把 DNS 兜底挪到**第 5 步之前**（第 6 步那次保留，幂等）。
先用 `getprop` 尝试取宿主当前 DNS，取不到再用国内公共 DNS 兜底；本机 `getprop` 读不到
`net.dns*`，实测 `223.5.5.5` 可正常解析 `registry.npmmirror.com`。

修完先做了一次**最小验证**再重跑全量构建（省掉一次几十分钟的失败）：

```
$ ./pr.sh "node -e \"require('dns').lookup('registry.npmmirror.com',…)\""
OK 240e:960:c00:9:3::8
$ ./pr.sh "node -e \"https.get('https://registry.npmmirror.com/@deepseek-ai%2fdsh'…)\""
HTTP 200
bytes 181738
```

### 2.2 产物核对

```
$ cat rootfs/.aptuidsh-image
image=aptuidsh-rootfs
distro=ubuntu-24.04.5-base-arm64
node=v22.22.2
dsh=0.1.7-rc.1          ← 已更新
proroot=arm64-v8a

$ ./pr.sh "echo APTUIDSH-SMOKE-OK; uname -m; node -v; dsh --version"
APTUIDSH-SMOKE-OK
aarch64
v22.22.2
0.1.7-rc.1
```

| 项 | 旧 | 新 |
|---|---|---|
| `assets/rootfs.img` | 136,645,275 B | **122,211,356 B** |
| 解压后占用 | 约 585 MB | 约 **794 MB** |
| 发行镜像卫生检查 | —— | 通过（`root/.dsh` 为空，无凭据、无测试会话） |

---

## 三、WebView 兼容性：第二轮（本次重点）

### 3.1 问题回顾

系统 WebView 实测只有 **Chrome 114**，而 dsh 官方 Web UI 按现代浏览器构建。
缺 API 会让客户端模块**导入即抛异常**，整页报
`Failed to load plugins … Iterator is not defined`。
上一轮的解法是「注入纯 JS 垫片」——因为缺的全是标准库 API，补 JS 比换内核代价小得多
（Android 也不允许应用自带 Chromium）。

### 3.2 这一轮怎么查（方法，可复用）

不再靠猜，改成三步穷举：

1. **建基准**：从 `@mdn/browser-compat-data` 导出「Chrome > 114 才加入」的全部 API
   —— JS 内建 327 条、Web API 1545 条、CSS 属性 901 条。
   脚本：`~/aptuidsh-recon/scan/spec_apis.py`
2. **定扫描集合**：把 dsh **真正发给浏览器**的脚本全找出来，共 **84 个 / 22.6 MB**：
   `dsh-web-frontend/dist/**`（Vite 外壳）+ `dsh-client-*/lib/client*.js` + `dsh-client-ui-primitives`。
   脚本：`audit_browser_api.py`（抽出所有 `全局.成员` 访问，逐个查 BCD 版本）
3. **逐条回上下文判真伪**：这一步**不能省**。纯正则必然有假阳性，实测抓到的假阳性形态：
   - `Fence` / `Viewport` / `Observable` —— 全部出现在**注释**里（"Fence by disposal"、"Viewport margin"）；
   - `Schema.union([...])` —— 本地 API，被 `.union(` 误判成 `Set.prototype.union`；
   - `Binary.fromBase64(...)` —— 本地类，被误判成 `Uint8Array.fromBase64`。

### 3.3 结论

| 链路 | 结论 |
|---|---|
| **启动链路**（Web UI 外壳 + 各插件 `lib/client.js`） | **已无缺口** —— 上一轮补的 `Iterator` / `Promise.withResolvers` / `Symbol.dispose` 正好覆盖 |
| **PDF 预览链路**（懒加载的 `client.pdf.js` + 它内嵌的 worker） | **新增一批缺口**，本轮全部补齐 |
| Excel 预览、语法高亮 langs | 只用到 `Iterator` / `Set` 方法，已被上一轮垫片覆盖 |

新增缺口（全部集中在 pdf.js，`pdfjs-dist@6.3.289`）：

| API | Chrome | 出现处 |
|---|---|---|
| `URL.parse` | 126 | pdf.js 12 处 |
| `Promise.try` | 128 | pdf.js 8 处 |
| `Response/Blob.prototype.bytes()` | 132/144 | pdf.js 6 处 |
| `RegExp.escape` | 136 | pdf.js 2 处 |
| `Uint8Array.fromBase64` / `toBase64` / `toHex` | 140 | pdf.js |
| `Math.sumPrecise` | 147 | pdf.js **17 处**（其中 16 处在 worker 里） |

**确认不需要补**（源码里有特性检测，或只是注释里的词）：
`Float16Array`（`typeof … !== "undefined"` 且有 `Float32Array` 兜底）、
`Temporal`（`"Temporal" in globalThis`）、`Sanitizer`（`typeof` 检测）、
`document.caretPositionFromPoint`（`typeof` 检测）。
纯 CSS 的 `@starting-style`(117) 也不补 —— CSS 缺失只降级不报错。

### 3.4 关键发现：pdf.js 的 worker 是**独立全局作用域**

这是本轮最容易漏的一点。pdf.js 的 worker 这样创建：

```js
url = URL.createObjectURL(new Blob([workerSource, `\nself.postMessage({type:…});\n`],
                                  { type: "text/javascript" }));
worker = new Worker(url, { type: "module", name: "dsh-pdf" });
```

worker 里 **`Math.sumPrecise` 出现 16 次**，而页面 `<head>` 注入的垫片**到不了 worker**。
且这个 dsh 集成路径在 worker 出错时**不会**回退到主线程假 worker——
`workerFailed` 直接 `failed.reject()` + `dispose()`，**PDF 预览会整体失败**。

**修法（worker 注入守卫）**：再包一层 `Blob` —— 只在「`type` 是 JS 且 `parts` 全是字符串」时，
把垫片源码**前置**进那段 blob。因为垫片本身幂等且「存在则跳过」，
对任何 JS blob 前置都是安全的；其余 Blob 一律原样交给原生实现；
保留 `prototype` 与静态成员，`instanceof Blob` 继续成立；
任何异常都吞掉，退化成「worker 没有垫片」，绝不影响页面本身。

垫片源码在页面里只写一遍：守卫通过 `document.getElementById("__aptuidsh_polyfill__").textContent`
读同一份源码（避免在 Kotlin 里二次转义）。

### 3.5 自测：89 项，含与原生实现的**差分对拍**

新增门禁 `tools/polyfill-test/`（`bash tools/polyfill-test/run.sh`）：

1. 从 `WebPolyfill.kt` **抽出真实垫片源码**（单一真源，不手抄到测试里）；
2. 在 Node 里**模拟 Chrome 114**：删掉 27 个「Chrome > 114」的 API，并前置断言「确实缺了」
   （否则后面的「补上了」是假绿）；
3. 逐条断言存在性与语义；
4. **差分对拍**：本机 Node 26 自带这批新 API，先记下原生结果，装完垫片再逐条比对
   —— `RegExp.escape` 675 条样本、base64 162 条、hex 162 条、`URL.parse` 675 条。

> **对拍救了一个真 bug**：`RegExp.escape` 我一开始凭记忆只转义「语法字符 + `/`」，
> 对拍立刻报 `RegExp.escape("a.b")` 垫片给 `a\.b`、原生给 `\x61\.b`。
> 于是逐个码点 0–255 在「首位/非首位」两种位置对拍，反推出完整的 6 条规则
> （首字符字母数字 → `\xHH`；语法字符 → `\`+自身；ASCII 单词字符 → 原样；
> `\t\n\v\f\r` → 短转义；其它 ASCII 标点与空白 → `\xHH`；其余含全部非 ASCII → 原样）。
> 教训：**能用原生对拍就别手写期望值**。

结果：**通过 89 / 失败 0**。

---

## 四、协议层：0.1.7-rc.1 的一处真实回归

README 第十节写着「升级 dsh 版本后第一件事就是跑 jvmtest」。照做，抓到一处真回归：

```
subagent.list   →   EXCEPTION java.lang.IllegalStateException: HTTP 404 非 JSON 响应: not found
```

**根因**：`subagents/list` 在 0.1.7-rc.1 里**被删掉了**（0.1.5-rc.3 还在）。
全量方法表里只剩 `subagents/prompt` 与 `subagents/interruptByParent`。

**新数据源**（读官方客户端代码得到，不是猜的）：子代理清单改由
`session/list` 的 **`projections.values.subagentCatalog`** 提供
（`dsh-client-ui-subagent` 就是读它；条目形如 `{id,label,mode,activity}`）。
已用 curl 对该实例实测确认。

**修法**：`subagent.list` / `subagent.history` 改映射到 `session/list`，
再由 `adaptResult` 把新形状还原回旧接口的
`{entries:[{kind,id,activity,hasChildren,mode,label?}], parentAvailable}`（旧形状取自
0.1.5-rc.3 的 typert 结果 schema）。因此 `adaptResult` 增加了一个
**带请求上下文的 4 参重载**（`adaptResult(oldPath, request, value)`）——
还原时需要 `parentSessionId` 才能定位父会话。

**jvmtest 同步加强**：新增 C 段 5 条断言（原测试台**完全没有覆盖 `adaptResult`**）：
真实父会话 `parentAvailable=true`、用不存在的父会话**反证**确实按 id 过滤（而不是恒真）、
以及自证——把上游必需的 `subagentCatalog` 删掉必须不抛异常且 `entries` 为空。

结果：**通过 28 / 失败 0**（升级前 22/23）。

---

## 五、验收表

| 层次 | 方法 | 结果 |
|---|---|---|
| 镜像链 | `proroot + rootfs + node + dsh` 冒烟 | 通过（`dsh --version` = 0.1.7-rc.1） |
| 镜像卫生 | 无凭据 / 无测试会话自检 | 通过 |
| 协议层 | `tools/jvmtest/run.sh`（打真实 0.1.7-rc.1 后端） | **28 / 0** |
| 垫片 | `tools/polyfill-test/run.sh` | **89 / 0**（含差分对拍） |
| 构建 | `gradle :app:assembleDebug` | 见下 |

---

## 六、已知取舍

1. **`entries[].hasChildren` 在 0.1.7-rc.1 没有对应字段**，适配时保守取 `false`。
   该接口目前在 APP 里**没有调用方**（界面是从 `session/list` 的 `origin=='subagent'`
   自己分组的），修它是为了保持协议层正确、让 jvmtest 能全绿。
2. **`mode` 新 catalog 可能是 `"unknown"`**，而旧 schema 只允许 `one-shot|continuable`。
   这里原样透传（不猜、不丢信息），同样因为暂无调用方。
3. **`Float16Array` 不补**：它需要真正的半精度 TypedArray，纯 JS 造不出等价语义；
   好在 pdf.js 对它做了特性检测且有 `Float32Array` 兜底，实测安全。
4. **worker 注入守卫是「前置 JS 源码」的通用做法**：垫片幂等所以安全，
   但如果将来某个 JS blob 的内容不是合法的「模块 + 前置 IIFE」组合，理论上会受影响。
   守卫只对 `type` 明确是 JS、且 `parts` 全是字符串的 Blob 生效，范围已尽量收窄。
5. **镜像解压后从约 585 MB 涨到约 794 MB**，首次安装仍需保证足够的手机存储。

---

## 七、本次改动的文件

| 文件 | 改动 |
|---|---|
| `app/src/main/assets/rootfs.img` | 重建为含 dsh 0.1.7-rc.1 的镜像（未入 Git） |
| `app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt` | 新增 Chrome 126~147 垫片 + worker 注入守卫；`RegExp.escape` 按原生逐码点对齐 |
| `app/src/main/java/com/aptuidsh/kui/net/ApiCompat.java` | `subagent.*` 改走 `session/list`；新增带请求上下文的 `adaptResult` 重载与形状还原 |
| `app/src/main/java/com/aptuidsh/kui/net/DshClient.java` | `adaptResult` 调用点传入原始载荷 |
| `tools/build-rootfs.sh` | **修 DNS 时序 bug**：DNS 兜底移到 `npm install` 之前 |
| `tools/polyfill-test/`（新） | 垫片自测 + 与原生差分对拍（89 项） |
| `tools/jvmtest/Harness.java` | 新增 C 段：`adaptResult` 形状还原 5 条断言 |
| `app/build.gradle` | versionCode 15 / versionName 1.2.0 |
| `README.md` | 同步版本、体积、协议表、工具章节 |

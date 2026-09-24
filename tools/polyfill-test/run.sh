#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# APTUIDSH · WebView 兼容垫片自测
# -----------------------------------------------------------------------------
# 从 WebPolyfill.kt 抽真实垫片源码 → 在 Node 里模拟 Chrome 114（删掉所有
# 「Chrome > 114 才加入」的 API）→ 装垫片 → 逐条断言，并与 Node 自带的原生实现
# 做**差分对拍**（对拍比手写期望值可靠：RegExp.escape 的转义规则就是被它抓出来的）。
#
# 用法：bash tools/polyfill-test/run.sh
# 退出码：0 = 全过；1 = 有失败项（可直接进 CI/交付前门禁）
# =============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

if ! command -v node >/dev/null 2>&1; then
  echo "[!] 找不到 node，无法运行垫片自测" >&2
  exit 1
fi

node "$HERE/check.mjs"
RC1=$?
# loader.await 收敛补丁是唯一一处有意改变 dsh 行为的补丁，单独做真实计时验证
SETTLE_SRC="$(node -e '
const fs=require("fs"),path=require("path");
const kt=fs.readFileSync(path.join(process.argv[1],"..","..","app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt"),"utf8");
X
process.stdout.write(m ? m[1] : "");
' "$HERE")" node "$HERE/settle-test.mjs"
RC2=$?
# 非特殊 scheme authority 补丁：本机 Node 的 URL 是规范实现，必须先把 URL 换成
# 「模拟旧内核」的假体，才能验证补丁真补上了缺口（否则只是假绿）
node "$HERE/url-authority-test.mjs"
RC3=$?
[ "$RC1" -eq 0 ] && [ "$RC2" -eq 0 ] && [ "$RC3" -eq 0 ]

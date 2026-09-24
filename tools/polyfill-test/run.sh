#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# APTUIDSH · WebView 兼容层自测（交付前门禁）
# -----------------------------------------------------------------------------
# 所有测试都从 WebPolyfill.kt **抽真实源码**再执行（单一真源，绝不手抄一份到测试里）。
# 三套：
#   1. check.mjs            —— 垫片本身：存在性/语义、幂等、与 Node 原生实现的差分对拍、
#                              语法、Kotlin 原样字符串不得出现美元符、窄屏布局覆盖层
#   2. settle-test.mjs      —— boot 收敛补丁：用**真实时钟**验证它在竞态下会等、
#                              卡住时会放手（这是唯一一处有意改变 dsh 行为的补丁）
#   3. url-authority-test.mjs —— 非特殊 scheme authority 补丁：先把 URL 换成复刻旧内核
#                              缺口的假体（否则只是「Node 本来就对」的假绿），再验证修复
#
# 用法：bash tools/polyfill-test/run.sh
# 退出码：0 = 全过；非 0 = 有失败项
# =============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

if ! command -v node >/dev/null 2>&1; then
  echo "[!] 找不到 node，无法运行兼容层自测" >&2
  exit 1
fi

node "$HERE/check.mjs"
node "$HERE/settle-test.mjs"
node "$HERE/url-authority-test.mjs"
echo "[v] 兼容层自测全部通过"

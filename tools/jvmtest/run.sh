#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# APTUIDSH · 适配层 JVM 实机测试台
# -----------------------------------------------------------------------------
# 直接编译并运行 APP 里【真实的】net/ApiCompat.java 与 net/MuxClient.java
# （只对 android.util.Base64 与 DshAuth 打桩），对着本机 127.0.0.1:3081 上真实的
# dsh 后端跑一遍，验证「APP 实际生成的请求」能被服务端接受。
#
# 为什么需要它：aptuidsh 的协议层要适配 dsh 0.1.5 的新网关，而请求形状
# （方法名 + args 形参名）非常容易写错，且错误只有到真机上才会暴露。这个测试台
# 把它变成开发机上的秒级反馈 —— 它已经抓出过 `new URL("ws://…")` 这类
# 会让会话历史直接崩溃的缺陷。
#
# 前置：本机 3081 上有 dsh 在跑，且已取得 Cookie 文件（见下）
# 用法：bash tools/jvmtest/run.sh [cookie文件路径]
# =============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/../.." && pwd)"
WORK="${TMPDIR:-$HOME}/aptuidsh-jvmtest"
COOKIE="${1:-$HOME/aptuidsh-rnd/ck.txt}"

mkdir -p "$WORK/lib" "$WORK/stub" "$WORK/src" "$WORK/out"
cp -r "$HERE/stub/." "$WORK/stub/"

# 真实源码（每次运行时同步，避免测试台与产品代码漂移）
cp "$PROJ/app/src/main/java/com/aptuidsh/kui/net/ApiCompat.java" "$WORK/src/"
cp "$PROJ/app/src/main/java/com/aptuidsh/kui/net/MuxClient.java" "$WORK/src/"
cp "$HERE/Harness.java" "$WORK/"

# org.json（JDK 不带，Android 上由系统提供）
if [ ! -s "$WORK/lib/json.jar" ]; then
  echo "==> 下载 org.json"
  curl -fsSL --max-time 90 -o "$WORK/lib/json.jar" \
    "https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar"
fi

if [ ! -s "$COOKIE" ]; then
  echo "[!] 找不到 Cookie 文件: $COOKIE"
  echo "    先启动后端并交换 Cookie："
  echo "      TOKEN=\$(grep -o 'token=[A-Za-z0-9_-]*' <dsh日志> | tail -1 | cut -d= -f2)"
  echo "      curl -s -c $COOKIE -o /dev/null \"http://127.0.0.1:3081/?token=\$TOKEN\""
  exit 1
fi

JAVAC="${JAVA_HOME:-$PREFIX}/bin/javac"
JAVA="${JAVA_HOME:-$PREFIX}/bin/java"
cd "$WORK"
rm -rf out && mkdir -p out
"$JAVAC" -encoding UTF-8 -nowarn -cp lib/json.jar -d out \
  $(find stub src -name '*.java') Harness.java
exec "$JAVA" -cp out:lib/json.jar Harness "$COOKIE"

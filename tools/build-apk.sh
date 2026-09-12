#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# APTUIDSH · APK 构建脚本（Termux / arm64 环境验证通过）
# -----------------------------------------------------------------------------
# 前置条件：
#   1. ~/android-sdk（platform-34 + build-tools 34.0.0）
#   2. ~/gradle-8.13
#   3. JDK 17（$PREFIX 自带）
#   4. app/src/main/assets/rootfs.tar.xz 与 app/src/main/jniLibs/arm64-v8a/*.so
#      已由 tools/build-rootfs.sh 生成（两者不入 Git）
#
# 用法：bash tools/build-apk.sh [debug|release]
# =============================================================================
set -euo pipefail

VARIANT="${1:-debug}"
PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"

export ANDROID_HOME="$HOME/android-sdk"
export JAVA_HOME="${PREFIX:-/data/data/com.termux/files/usr}"
GRADLE="$HOME/gradle-8.13/bin/gradle"

[ -x "$GRADLE" ] || { echo "[!] 找不到 gradle: $GRADLE"; exit 1; }
[ -d "$ANDROID_HOME" ] || { echo "[!] 找不到 Android SDK: $ANDROID_HOME"; exit 1; }

if [ ! -f "$PROJ_DIR/app/src/main/assets/rootfs.tar.xz" ]; then
  echo "[!] 缺少内置镜像 app/src/main/assets/rootfs.tar.xz"
  echo "    请先运行: bash tools/build-rootfs.sh"
  exit 1
fi
if ! ls "$PROJ_DIR"/app/src/main/jniLibs/arm64-v8a/libproroot.so >/dev/null 2>&1; then
  echo "[!] 缺少 proroot 二进制 app/src/main/jniLibs/arm64-v8a/"
  echo "    请从 proroot release 下载 5 个 .so 放进去，或用 tools/build-rootfs.sh 一并生成"
  exit 1
fi

cd "$PROJ_DIR"
case "$VARIANT" in
  release) TASK=":app:assembleRelease" ;;
  *)       TASK=":app:assembleDebug" ;;
esac

echo "==> $GRADLE $TASK --no-daemon"
"$GRADLE" "$TASK" --no-daemon

APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$APK" ] || { echo "[!] 未找到产物 $APK"; exit 1; }

echo "[v] 构建完成: $APK ($(du -h "$APK" | cut -f1))"
echo "    复制到共享下载目录:"
echo "      cp $PROJ_DIR/$APK ~/storage/downloads/APTUIDSH.apk"

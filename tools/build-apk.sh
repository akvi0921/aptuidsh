#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# APTUIDSH · APK 构建脚本（Termux / arm64 环境验证通过）
# -----------------------------------------------------------------------------
# 前置条件：
#   1. ~/android-sdk（platform-34 + build-tools 34.0.0）
#   2. ~/gradle-8.13
#   3. JDK 17（$PREFIX 自带）
#   4. app/src/main/assets/rootfs.img（含 image-version.txt）与
#      app/src/main/jniLibs/arm64-v8a/*.so
#      已由 tools/build-rootfs.sh 生成（均不入 Git）
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

if [ ! -f "$PROJ_DIR/app/src/main/assets/rootfs.img" ]; then
  echo "[!] 缺少内置镜像 app/src/main/assets/rootfs.img"
  echo "    请先运行: bash tools/build-rootfs.sh"
  exit 1
fi
# 镜像必须是 gzip：Android 的 toybox tar 解不了 xz（且解压失败退出码仍是 0）
if [ "$(head -c 2 "$PROJ_DIR/app/src/main/assets/rootfs.img" | od -An -tx1 | tr -d ' \n')" != "1f8b" ]; then
  echo "[!] assets/rootfs.img 不是 gzip（魔数应为 1f8b）—— 手机端会报 zcat: not gzip"
  exit 1
fi
if [ ! -f "$PROJ_DIR/app/src/main/assets/image-version.txt" ]; then
  echo "[!] 缺少 assets/image-version.txt（内置 dsh 版本标记，由 build-rootfs.sh 一并产出）"
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

# 进度可见性：Gradle 的进度条只在 rich 控制台出现，而非交互环境需要 pty 包装；
# 且**绝不能接管道/重定向**（那会让输出不再流经 stdout，进度条就采集不到）。
echo "==> $GRADLE $TASK --console=rich"
TERM=xterm-256color script -q -f -c "$GRADLE $TASK --console=rich" /dev/null
GRADLE_RC=$?

# 必须显式检查退出码：`script` 包装后不会自动中断，
# 曾经出现 BUILD FAILED 却照样打印「构建完成」、把上一版 APK 当新版交付的事故。
if [ "$GRADLE_RC" -ne 0 ]; then
  echo "[!] 构建失败：$GRADLE $TASK 退出码 $GRADLE_RC（上面 BUILD FAILED 的报错才是真相）"
  exit "$GRADLE_RC"
fi

APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$APK" ] || { echo "[!] 未找到产物 $APK"; exit 1; }

echo "[v] 构建完成: $APK ($(du -h "$APK" | cut -f1))"
echo "    复制到共享下载目录:"
echo "      cp $PROJ_DIR/$APK ~/storage/downloads/APTUIDSH.apk"

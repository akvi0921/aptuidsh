#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# APTUIDSH · 内置 rootfs 镜像构建脚本
# -----------------------------------------------------------------------------
# 产出一个可直接放进 app/src/main/assets/ 的离线运行环境镜像：
#
#   Ubuntu 24.04.x arm64 base  （glibc 用户态，proroot 的前提）
#     + Node.js 22 linux-arm64 （装到 /usr/local —— guest 子进程的默认 PATH 只认这里）
#       + @deepseek-ai/dsh      （npm 全局安装，dsh 本体）
#
# 用法：
#   bash tools/build-rootfs.sh [工作目录] [dsh版本]
#   例：bash tools/build-rootfs.sh ~/aptuidsh-rootfs 0.1.5-rc.1
#
# 产物：<工作目录>/dist/rootfs.tar.xz  → 复制到 app/src/main/assets/rootfs.tar.xz
#
# -----------------------------------------------------------------------------
# 踩过的坑（改动本脚本前务必先读）：
#  1. Node 必须解包到 /usr/local（strip-components=1），不能放 /usr/local/node/bin。
#     proroot 的 guest 子进程会重置 PATH 为 Ubuntu 默认值，自定义目录不在其中，
#     表现是 `node` 找不到、npm 的 #!/usr/bin/env node 直接 ENOENT。
#  2. 执行 proroot 时必须用干净环境（env -i 或 ProcessBuilder.environment().clear）。
#     宿主（Termux/Android）的 PREFIX 等变量会透传进 guest，把 npm 全局前缀
#     解析到宿主路径，结果包装到宿主的 node_modules 里去。
#  3. Android 文件系统禁止硬链接，因此打包必须带 --hard-dereference，
#     否则设备上用 toybox tar 解压会报 "Cannot hard link"。
#  4. rootfs 里绝不能残留 API Key（$HOME/.dsh/.credentials.yaml 的 refs 段）
#     与 npm 缓存（$HOME/.npm，实测 164MB）。
#  5. 跨平台预编译二进制（sharp / node-pty / koffi 的其它平台包）只保留
#     linux-arm64，能省下 40MB 以上，且不影响运行。
# =============================================================================
set -euo pipefail

WORK="${1:-$HOME/aptuidsh-rootfs}"
DSH_VERSION="${2:-0.1.5-rc.1}"
NODE_VERSION="22.22.2"
UBUNTU_SERIES="24.04"
UBUNTU_POINT="24.04.5"

PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
UBUNTU_URL="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/${UBUNTU_SERIES}/release/ubuntu-base-${UBUNTU_POINT}-base-arm64.tar.gz"
NODE_URL="https://registry.npmmirror.com/-/binary/node/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.xz"
PROROOT_BASE="https://gh-proxy.com/https://raw.githubusercontent.com/coderredlab/proroot/main/arm64-v8a"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[v]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }

mkdir -p "$WORK" "$WORK/dist"
cd "$WORK"

# ------------------------------------------------------------------ 1/7 proroot
info "1/7 获取 proroot arm64 运行时"
mkdir -p proroot
for f in libproroot.so libproroot-runtime.so libproroot-linker.so \
         libproroot-bridge.so libproroot-stub-loader.so; do
  if [ ! -f "proroot/$f" ]; then
    curl -fL --max-time 300 -o "proroot/$f" "$PROROOT_BASE/$f"
  fi
done
chmod +x proroot/*.so
ok "proroot 就绪（$(du -sh proroot | cut -f1)）"

# ------------------------------------------------------------ 2/7 Ubuntu base
info "2/7 下载 Ubuntu base arm64 rootfs"
if [ ! -f ubuntu-base.tar.gz ]; then
  curl -fL --max-time 1800 -o ubuntu-base.tar.gz "$UBUNTU_URL"
fi
rm -rf rootfs && mkdir -p rootfs
info "解压 Ubuntu base（硬链接会失败，属预期）"
tar -xzf ubuntu-base.tar.gz -C rootfs --numeric-owner 2>&1 | grep -c "Cannot hard link" || true
# Ubuntu base 里 perl/gunzip 是硬链接，Android 上建不了，改用副本补齐
(
  cd rootfs
  [ -e usr/bin/perl5.38.2 ] || cp -a usr/bin/perl usr/bin/perl5.38.2
  [ -e usr/bin/uncompress ] || cp -a usr/bin/gunzip usr/bin/uncompress
)
ok "Ubuntu base 解压完成（$(du -sh rootfs | cut -f1)）"

# ------------------------------------------------------------------- 3/7 Node
info "3/7 安装 Node.js v${NODE_VERSION} 到 /usr/local"
if [ ! -f node.tar.xz ]; then
  curl -fL --max-time 900 -o node.tar.xz "$NODE_URL"
fi
# 关键：strip-components=1 直接铺进 /usr/local，保证 /usr/local/bin/node 在默认 PATH 内
tar -xJf node.tar.xz --strip-components=1 -C rootfs/usr/local
"$WORK/proroot/libproroot.so" >/dev/null 2>&1 || true
ok "Node 就绪（$(du -sh rootfs/usr/local | cut -f1)）"

# ------------------------------------------------------------- 4/7 启动器封装
info "4/7 生成 proroot 启动封装（干净环境 + 绑定工作目录）"
cat > pr.sh <<EOF
#!/data/data/com.termux/files/usr/bin/bash
RND="\$(cd "\$(dirname "\$0")" && pwd)"
mkdir -p "\$RND/tmp"
exec env -i \\
  HOME=/root \\
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\
  TERM=xterm LANG=C.UTF-8 TMPDIR=/tmp \\
  PROROOT_TMP_DIR="\$RND/tmp" \\
  "\$RND/proroot/libproroot.so" -r "\$RND/rootfs" -0 --link2symlink -w /root /bin/sh -c "\$*"
EOF
chmod +x pr.sh
mkdir -p tmp

# ------------------------------------------------------------------- 5/7 dsh
info "5/7 在 guest 内全局安装 @deepseek-ai/dsh@${DSH_VERSION}（需数分钟）"
./pr.sh "export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
npm config set registry https://registry.npmmirror.com
npm install -g @deepseek-ai/dsh@${DSH_VERSION}
dsh --version"
ok "dsh 安装完成"

# --------------------------------------------------------------- 6/7 清理瘦身
info "6/7 清理缓存与跨平台二进制"
NM=rootfs/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules
rm -rf rootfs/root/.npm rootfs/tmp/* rootfs/var/cache/apt rootfs/var/lib/apt/lists
rm -rf rootfs/usr/share/doc rootfs/usr/share/man rootfs/usr/share/info
# 绝不能把调试期的凭据打进发行镜像
rm -rf rootfs/root/.dsh/sessions rootfs/root/.dsh/storages \
       rootfs/root/.dsh/.credentials.yaml rootfs/root/.dsh/.anonymous-user-id
# 只保留 linux-arm64 的预编译产物
rm -rf "$NM"/node-pty/prebuilds/darwin-arm64 "$NM"/node-pty/prebuilds/darwin-x64 \
       "$NM"/node-pty/prebuilds/win32-arm64 "$NM"/node-pty/prebuilds/win32-x64 \
       "$NM"/node-pty/build
for d in "$NM"/@koromix/*/; do
  case "$d" in *linux-arm64*) ;; *) rm -rf "$d" ;; esac
done
for d in "$NM"/@img/*/; do
  case "$d" in *linux-arm64*|*colour*|*sharp-wasm32*) ;; *) rm -rf "$d" ;; esac
done
mkdir -p rootfs/root/workspace rootfs/root/.dsh rootfs/root/.aptuidsh

# DNS 兜底（APP 每次启动还会用 Android 当前网络的 DNS 覆写）
mkdir -p rootfs/etc
cat > rootfs/etc/resolv.conf <<'EOF'
nameserver 223.5.5.5
nameserver 114.114.114.114
options timeout:2 attempts:3
EOF

# 镜像版本标记（APP 读它来展示 dsh 版本）
cat > rootfs/.aptuidsh-image <<EOF
image=aptuidsh-rootfs
distro=ubuntu-${UBUNTU_POINT}-base-arm64
node=v${NODE_VERSION}
dsh=${DSH_VERSION}
proroot=arm64-v8a
EOF

# ------------------------------------------------------------------- 7/7 打包
info "7/7 打包 rootfs.tar.xz（--hard-dereference：Android 不支持硬链接）"
rm -f dist/rootfs.tar.xz
( cd rootfs && tar --hard-dereference --numeric-owner -cJf "$WORK/dist/rootfs.tar.xz" . )

ok "镜像构建完成：$WORK/dist/rootfs.tar.xz（$(du -h "$WORK/dist/rootfs.tar.xz" | cut -f1)）"
ok "复制到项目：cp $WORK/dist/rootfs.tar.xz $PROJ_DIR/app/src/main/assets/rootfs.tar.xz"
ok "同步 proroot 二进制：cp $WORK/proroot/*.so $PROJ_DIR/app/src/main/jniLibs/arm64-v8a/"

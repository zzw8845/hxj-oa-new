#!/bin/bash
# ===========================================================================
#  海峡金 OA · 一键部署（在**本机**执行）
#
#  流程：Maven 打包 → 构建镜像 → 推送仓库 → 同步部署文件到服务器 → 触发服务器蓝绿切换
#
#  用法：
#      bash deploy.sh                 完整部署
#      SKIP_BUILD=1 bash deploy.sh    跳过 Maven 打包（jar 已是最新时）
#      SKIP_PUSH=1  bash deploy.sh    不重建镜像，只把部署文件同步过去
#
#  【为什么部署脚本要管"同步部署文件"】
#  docker-compose.yml / run.sh / .env 都在服务器上执行，改完必须传过去，
#  否则会出现"代码部署了、但服务器跑的还是上一版编排"这种最迷惑人的情况。
# ===========================================================================
set -euo pipefail

# ---------------------------------------------------------------- 坐标
# 改动只需改这一段
REGISTRY="swr.cn-north-4.myhuaweicloud.com/zzw8845"
IMAGE_NAME="oa"
SSH_HOST="root@47.107.79.42"
SSH_PORT="10745"
REMOTE_DIR="/opt/sh/oa/admin"

DIR="$(cd "$(dirname "$0")" && pwd)"          # oa-backend/docker-test
BE_DIR="$(cd "$DIR/.." && pwd)"               # oa-backend
JAR="$BE_DIR/oa-boot/target/oa-boot-1.0.0-SNAPSHOT.jar"
FULL_IMAGE="$REGISTRY/$IMAGE_NAME"
TAG="$(date +%Y%m%d%H%M%S)"

step() { printf '\n==================== %s ====================\n\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }

step "0/5 前置检查"

[ -f "$DIR/.env" ] || die "缺少 $DIR/.env。请先：cp .env.example .env 并填入真实值（该文件不入库）"
if grep -q 'CHANGE_ME' "$DIR/.env"; then
  die "$DIR/.env 里还有 CHANGE_ME 未替换。请先填完再部署 —— 带着占位符发版，结果是一台连不上库的机器。"
fi

JAVA_HOME_17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
if [ -z "$JAVA_HOME_17" ]; then
  die "未找到 JDK 17（本项目要求 17，不能用 11 或 21）。macOS 可执行：brew install openjdk@17"
fi
command -v mvn  >/dev/null 2>&1 || die "未找到 mvn，请先安装 Maven"
command -v docker >/dev/null 2>&1 || die "未找到 docker"

echo "✓ 前置检查通过（JDK17=${JAVA_HOME_17}）"
echo "  镜像：$FULL_IMAGE:$TAG"
echo "  目标：$SSH_HOST:$SSH_PORT  $REMOTE_DIR"

# ---------------------------------------------------------------- 1 打包
if [ "${SKIP_BUILD:-0}" = "1" ]; then
  step "1/5 跳过 Maven 打包（SKIP_BUILD=1）"
  [ -f "$JAR" ] || die "跳过打包但找不到产物：$JAR"
else
  step "1/5 Maven 打包"
  ( cd "$BE_DIR" && JAVA_HOME="$JAVA_HOME_17" PATH="$JAVA_HOME_17/bin:$PATH" \
      mvn -B -DskipTests clean package ) || die "Maven 打包失败"
fi
[ -f "$JAR" ] || die "打包后仍找不到产物：$JAR"
echo "✓ 产物：${JAR}（$(du -h "$JAR" | cut -f1)）"

# ---------------------------------------------------------------- 2 构建镜像
if [ "${SKIP_PUSH:-0}" = "1" ]; then
  step "2/5 跳过镜像构建（SKIP_PUSH=1）"
else
  step "2/5 构建镜像"
  # Dockerfile 里 COPY oa.jar，所以先放到 docker-test 目录下。
  # 该文件被 .gitignore 的 *.jar 规则覆盖，不会入库。
  cp "$JAR" "$DIR/oa.jar"

  ARCH="$(uname -m)"
  if [ "$ARCH" = "x86_64" ]; then
    docker build -t "$FULL_IMAGE:$TAG" "$DIR"
  elif [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then
    # Apple Silicon 开发机：必须构建 amd64 镜像，否则服务器报 exec format error
    docker buildx build --platform linux/amd64 -t "$FULL_IMAGE:$TAG" --load "$DIR" \
      || die "docker buildx 构建失败。若提示 buildx 不可用，请先执行：docker buildx create --use"
  else
    die "未知 CPU 架构：$ARCH"
  fi
  echo "✓ 镜像构建完成：$FULL_IMAGE:$TAG"

  # ------------------------------------------------------------ 3 推送
  step "3/5 推送镜像到仓库"
  docker tag "$FULL_IMAGE:$TAG" "$FULL_IMAGE:latest"
  docker push "$FULL_IMAGE:$TAG"
  docker push "$FULL_IMAGE:latest"
  echo "✓ 已推送 :$TAG 与 :latest"
fi

# ---------------------------------------------------------------- 4 同步部署文件
step "4/5 同步部署文件到服务器"
# 目录可能还不存在（首次部署），先建再传
ssh -p "$SSH_PORT" "$SSH_HOST" "mkdir -p '$REMOTE_DIR'"
scp -P "$SSH_PORT" \
    "$DIR/docker-compose.yml" \
    "$DIR/run.sh" \
    "$DIR/.env" \
    "$SSH_HOST:$REMOTE_DIR/"
# .env 里有库口令、OSS 密钥与 JWT 私钥，权限收到最小
ssh -p "$SSH_PORT" "$SSH_HOST" "chmod 600 '$REMOTE_DIR/.env'"
echo "✓ docker-compose.yml / run.sh / .env 已同步（.env 已设为 600）"

# ---------------------------------------------------------------- 5 蓝绿切换
step "5/5 触发服务器蓝绿切换"
ssh -p "$SSH_PORT" "$SSH_HOST" "cd '$REMOTE_DIR' && bash run.sh"

printf '\n==================== 部署完成 ====================\n\n'
echo "本次镜像 tag：$TAG"
echo "回滚方式（在服务器 $REMOTE_DIR 下执行）："
echo "    OA_IMAGE=$FULL_IMAGE:<上一个tag> docker compose up -d oa"
echo "    OA_IMAGE=$FULL_IMAGE:<上一个tag> docker compose up -d oa-1"
echo "历史 tag 列表：docker images $FULL_IMAGE"

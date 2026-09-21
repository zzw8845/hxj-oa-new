#!/bin/bash
# ===========================================================================
#  海峡金 OA · 一键部署（在**开发机**执行）
#
#  流程：Maven 打包 → 同步产物与编排文件到服务器 → **服务器上构建镜像** → 触发蓝绿切换
#
#  用法：
#      bash deploy.sh                 完整部署
#      SKIP_BUILD=1 bash deploy.sh    跳过 Maven 打包（jar 已是最新时）
#      SKIP_IMAGE=1 bash deploy.sh    不重建镜像，只同步编排文件并做蓝绿切换
#
#  【与上一版的根本差别：不再往镜像仓库推】
#  上一版是"开发机 buildx 构建 amd64 镜像 → push 到 SWR → 服务器 pull"。
#  实际执行时这条路走不通：
#    · 开发机的 Docker 坏了 —— containerd 元数据库损坏，pull/build 一律报
#      `write /var/lib/desktop-containerd/.../meta.db: input/output error`；
#    · 服务器又拉不到 Docker Hub（阿里云加速器已不代理 eclipse-temurin）。
#  改成"把 jar 传上去、在服务器本地 docker build"之后，两边的毛病都绕开了，
#  而且少了一次 600MB 的镜像传输。服务器拉基础镜像走华为云公共源（见 Dockerfile）。
#
#  代价：镜像仓库里没有历史版本，回滚要靠两条路（脚本末尾已内置提示）：
#    · 保留旧容器 docker start —— 秒级，兜底用；
#    · 本地镜像按时间戳打 tag —— 精确到版本。
# ===========================================================================
set -euo pipefail

# ---------------------------------------------------------------- 坐标
SSH_HOST="root@47.107.79.42"
SSH_PORT="10745"
REMOTE_DIR="/opt/sh/oa"
# 【项目名必须显式指定，不能靠目录名推导】
# compose 默认拿**所在目录名**当项目名。这台服务器上 /opt/sh/wms 与 trace 的编排
# 项目名恰好是 `admin`（docker inspect 可见 trace-mng / wms-mng 都带着
# com.docker.compose.project=admin，且 admin_wg-network 网络就是它建的）。
# 如果我们的部署目录也叫 admin，两个项目同名 —— 后果不是"看着别扭"：
#   · `docker compose ps` 会把别人的容器一起列出来；
#   · `docker compose down` 会把 trace-mng / wms-mng **一起停掉并删除**。
# 所以脚本里所有 compose 调用都带 `-p hxj-oa`（v1/v2 都支持），
# 项目名与目录名彻底解耦，将来谁改目录名都不会再撞。
COMPOSE_PROJECT="hxj-oa"
IMAGE="oa"
TAG="$(date +%Y%m%d%H%M%S)"

DIR="$(cd "$(dirname "$0")" && pwd)"          # oa-backend/docker-test
BE_DIR="$(cd "$DIR/.." && pwd)"               # oa-backend
JAR="$BE_DIR/oa-boot/target/oa-boot-1.0.0-SNAPSHOT.jar"

# 【ssh 与 scp 的端口参数**不同名**，必须分开写】
#   ssh 用 -p 10745（小写），scp 用 -P 10745（大写）。
#   复用同一份参数会踩一个很隐蔽的坑：scp 会把 -p 解释成"保留时间戳"，
#   然后把紧随其后的 10745 当成**待复制的源文件**，报
#   "10745: No such file or directory" —— 看起来像路径写错，实际是参数错。
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o BatchMode=yes -p "$SSH_PORT")
SCP_OPTS=(-o StrictHostKeyChecking=accept-new -o BatchMode=yes -P "$SSH_PORT")

step() { printf '\n==================== %s ====================\n\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }

step "0/4 前置检查"

[ -f "$DIR/.env" ] || die "缺少 $DIR/.env。请先：cp .env.example .env 并填入真实值（该文件不入库）"
if grep -q 'CHANGE_ME' "$DIR/.env"; then
  die "$DIR/.env 里还有 CHANGE_ME 未替换。请先填完再部署 —— 带着占位符发版，结果是一台连不上库的机器。"
fi
# 【这条检查是拿真事故换来的】默认 JWT 密钥曾被随公开仓库一起公开，
# 且它能通过长度校验 ⇒ 忘配置时不报错、服务照常对外发令牌。
# 应用侧现在是 fail-fast（未配置即启动失败），但"配了一个和示例一样的值"仍需人工挡住。
if grep -qE '^OA_JWT_SECRET=(CHANGE_ME)?$' "$DIR/.env"; then
  die "$DIR/.env 的 OA_JWT_SECRET 为空。生成方式：head -c 48 /dev/urandom | base64 | tr -d '\\n'"
fi
command -v ssh >/dev/null 2>&1 || die "未找到 ssh"
command -v scp >/dev/null 2>&1 || die "未找到 scp"

JAVA_HOME_17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
echo "✓ 前置检查通过（JDK17=${JAVA_HOME_17:-未找到}）"
echo "  目标：$SSH_HOST:$SSH_PORT  $REMOTE_DIR"
echo "  镜像 tag：$IMAGE:$TAG"

# ---------------------------------------------------------------- 1 打包
if [ "${SKIP_BUILD:-0}" = "1" ]; then
  step "1/4 跳过 Maven 打包（SKIP_BUILD=1）"
  [ -f "$JAR" ] || die "跳过打包但找不到产物：$JAR"
else
  step "1/4 Maven 打包"
  [ -n "$JAVA_HOME_17" ] || die "未找到 JDK 17（本项目要求 17，不能用 11 或 21）"
  command -v mvn >/dev/null 2>&1 || die "未找到 mvn"
  ( cd "$BE_DIR" && JAVA_HOME="$JAVA_HOME_17" PATH="$JAVA_HOME_17/bin:$PATH" \
      mvn -B -o -DskipTests clean package ) || die "Maven 打包失败"
fi
[ -f "$JAR" ] || die "打包后仍找不到产物：$JAR"
echo "✓ 产物：${JAR}（$(du -h "$JAR" | cut -f1)）"

# ---------------------------------------------------------------- 2 同步
step "2/4 同步产物与编排文件到服务器"
ssh "${SSH_OPTS[@]}" "$SSH_HOST" "mkdir -p '$REMOTE_DIR/logs'"

# 先传到临时名再 mv：直接覆盖正在被 docker build 读取的 oa.jar，会让构建读到半个文件；
# 而且这是 67MB 的大文件，中途断线留下的残缺 jar 若直接生效，症状会是
# "应用启动时报 jar 损坏/找不到主类"，排查方向会完全跑偏。
scp -q "${SCP_OPTS[@]}" "$JAR" "$SSH_HOST:$REMOTE_DIR/oa.jar.new"
ssh "${SSH_OPTS[@]}" "$SSH_HOST" "mv -f '$REMOTE_DIR/oa.jar.new' '$REMOTE_DIR/oa.jar'"

scp -q "${SCP_OPTS[@]}" \
    "$DIR/Dockerfile" \
    "$DIR/docker-compose.yml" \
    "$DIR/run.sh" \
    "$DIR/.env" \
    "$SSH_HOST:$REMOTE_DIR/"

# .env 里有库口令、OSS 密钥与 JWT 私钥，权限收到最小
ssh "${SSH_OPTS[@]}" "$SSH_HOST" "chmod 600 '$REMOTE_DIR/.env'"
echo "✓ oa.jar / Dockerfile / docker-compose.yml / run.sh / .env 已同步（.env 已设为 600）"

# ---------------------------------------------------------------- 3 构建镜像
if [ "${SKIP_IMAGE:-0}" = "1" ]; then
  step "3/4 跳过镜像构建（SKIP_IMAGE=1）"
else
  step "3/4 在服务器上构建镜像"
  # 构建放在服务器上，所以 --platform 不用管：服务器本身就是 x86_64，
  # 不存在上一版"Apple Silicon 构建出 arm64 镜像、服务器报 exec format error"的问题。
  #
  # tag 规则：同时打 :latest 和 :$TAG。
  #   :latest 给 compose 用；:$TAG 是**回滚凭据** —— 出问题时可以
  #   OA_IMAGE=oa:<上一个tag> docker compose up -d oa 精确回到某一版。
  ssh "${SSH_OPTS[@]}" "$SSH_HOST" "cd '$REMOTE_DIR' && \
      docker build -t '$IMAGE:$TAG' -t '$IMAGE:latest' ." \
    || die "服务器构建镜像失败。若卡在拉基础镜像，见 Dockerfile 顶部关于镜像源的说明。"
  echo "✓ 镜像构建完成：$IMAGE:$TAG / $IMAGE:latest"
fi

# ---------------------------------------------------------------- 4 蓝绿切换
step "4/4 触发服务器蓝绿切换"
ssh "${SSH_OPTS[@]}" "$SSH_HOST" "cd '$REMOTE_DIR' && bash run.sh"

printf '\n==================== 部署完成 ====================\n\n'
echo "本次镜像 tag：$TAG"
echo "run.sh 已经在上面打印了**本次**的回滚三步（含要改成的端口），照抄即可。"
echo "两条回滚思路的区别："
echo "    · 回滚到上一个容器（秒级，最适合「切上去才发现不对」的场景）"
echo "      —— 旧容器是 docker stop 而非 rm，docker start 就能回来。"
echo "    · 回滚到某个镜像 tag（需要重新创建容器，慢一些）"
echo "      cd $REMOTE_DIR && OA_IMAGE=$IMAGE:<tag> docker compose -p $COMPOSE_PROJECT up -d <实例名>"
echo "历史 tag 列表：docker images $IMAGE"

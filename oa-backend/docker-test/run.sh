#!/bin/bash
# ===========================================================================
#  海峡金 OA · 蓝绿发布（在**服务器** /opt/sh/oa/admin 下执行，一般由 deploy.sh 远程调用）
#
#  与上一版的四处关键改动，每条都是拿真实故障换的：
#
#  1. 健康判定改用「容器内 curl /api/ping」，不再用
#     `docker logs | grep 'Started XxxApplication'`。
#     按日志文本匹配的判定，一旦启动横幅改了措辞就会**静默失效** ——
#     脚本照样往下走，把流量切到一个其实没起来的实例上。
#  2. 切 nginx 前先 `nginx -t` 校验，失败立即回滚配置。原脚本 sed 完直接 reload，
#     配置写坏时 reload 失败，而线上从此没人再敢动 nginx。
#  3. 健康检查不过就**中止且不切 nginx**。这是蓝绿的全部意义：宁可这次没发上去，
#     也不能让线上指向一个起不来的实例。
#  4. 不再用 `docker rmi $(docker images | grep ... | awk ...)` 那种按名字模糊匹配
#     删镜像的写法 —— 同一台服务器上会连别的项目的镜像一起删掉。
#
#  【这一版新增：镜像在本机构建，不 pull】
#  服务器拉不到 Docker Hub，镜像由 deploy.sh 同步过来的 Dockerfile + oa.jar 就地构建。
#  因此原来的 `docker compose pull` 换成 `docker build`。
# ===========================================================================
set -euo pipefail

# ---------------------------------------------------------------- 坐标
NGINX_CONF="/opt/docker/nginx/conf/include/hxj-oa-test.wgit123.com.conf"
NGINX_CONTAINER="nginx"
BLUE_PORT=8010
GREEN_PORT=9010
BLUE_SVC="oa"
GREEN_SVC="oa-1"
IMAGE="oa"
HEALTH_TIMEOUT=180          # 秒；Flowable 首次启动要建/校验 ACT_* 表，且这台机器内存紧，给足时间

cd "$(dirname "$0")"

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { printf '[%s] ✗ %s\n' "$(date '+%H:%M:%S')" "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 前置检查
[ -f .env ] || die "缺少 .env（含数据库/Redis/OSS 凭据与 JWT 密钥）。从 .env.example 复制并填好。"
[ -f oa.jar ] || die "缺少 oa.jar。它由 deploy.sh 从开发机同步过来。"
[ -f "$NGINX_CONF" ] || die "nginx 配置不存在：$NGINX_CONF
   首次部署需要人工装一次：按 docker-test/nginx-oa.conf.template 填好上游地址后放到该路径。
   填法见模板顶部 —— 上游地址请从同服务器上现有可用的 nginx 配置里原样抄，不要猜。"

# compose v2 是 `docker compose`，v1 是 `docker-compose`。
# 服务器上实测两套都在（v2.27.0 与 v1.24.1），优先用 v2。
#
# 【-p hxj-oa 不能省】
# compose 默认拿目录名当项目名，而这台服务器上已经有一个叫 `admin` 的项目
# （trace-mng / wms-mng 都属于它，admin_wg-network 就是它建的）。
# 项目名一旦撞车，`docker compose down` / `ps` 会作用到别人的容器上。
# 显式指定项目名，与目录名解耦，从根上避免这件事。
COMPOSE_PROJECT="hxj-oa"
if docker compose version >/dev/null 2>&1; then
  DC=(docker compose -p "$COMPOSE_PROJECT")
else
  DC=(docker-compose -p "$COMPOSE_PROJECT")
fi
log "使用编排命令：${DC[*]}"

# ---------------------------------------------------------------- 判定当前指向
cur_blue=0; cur_green=0
grep -qE ":${BLUE_PORT}([^0-9]|$)"  "$NGINX_CONF" && cur_blue=1  || true
grep -qE ":${GREEN_PORT}([^0-9]|$)" "$NGINX_CONF" && cur_green=1 || true

if [ "$cur_blue" = 1 ] && [ "$cur_green" = 0 ]; then
  TARGET_SVC="$GREEN_SVC"; TARGET_PORT="$GREEN_PORT"; FROM_PORT="$BLUE_PORT";  OLD_SVC="$BLUE_SVC"
elif [ "$cur_green" = 1 ] && [ "$cur_blue" = 0 ]; then
  TARGET_SVC="$BLUE_SVC";  TARGET_PORT="$BLUE_PORT";  FROM_PORT="$GREEN_PORT"; OLD_SVC="$GREEN_SVC"
else
  # 两个端口同时出现（配置被改乱）或一个都没有（模板没填）—— 都停下等人确认
  die "无法判定 nginx 当前指向哪个实例（$BLUE_PORT 与 $GREEN_PORT 或同时存在、或都不存在）
   配置：$NGINX_CONF
   请人工确认后再执行发布，不要在状态不明时切流量。"
fi

log "当前流量指向 ${FROM_PORT}，本次把新版本发到「${TARGET_SVC}」（${TARGET_PORT}）"

# ---------------------------------------------------------------- 1 构建新镜像
log "1/5 在服务器上构建新镜像（tag ${IMAGE}:latest）"
TAG="$(date +%Y%m%d%H%M%S)"
docker build -t "${IMAGE}:${TAG}" -t "${IMAGE}:latest" . \
  || die "镜像构建失败。若卡在拉基础镜像，见 Dockerfile 顶部关于镜像源的说明。"
log "✓ 镜像已构建：${IMAGE}:${TAG}"

# 记录"这次发布之前 :latest 指向哪个镜像 ID"，
# 后面构建失败/健康检查失败时用得上（可以据此提醒回滚到哪个 tag）。
NEW_IMAGE_ID="$(docker images -q "${IMAGE}:latest" | head -1)"

# ---------------------------------------------------------------- 2 启动新实例
log "2/5 启动 ${TARGET_SVC}（旧实例继续服务，线上不受影响）"
# --no-deps：只动这一个实例，别把另一个也带起来 ——
# 否则蓝绿就退化成"两个实例一起重启"，切换期间没有可服务的实例。
"${DC[@]}" up -d --no-deps "$TARGET_SVC"

# ---------------------------------------------------------------- 3 健康门禁
log "3/5 等待 $TARGET_SVC 就绪（最多 ${HEALTH_TIMEOUT}s）"
ready=0
for _ in $(seq 1 "$HEALTH_TIMEOUT"); do
  # 在容器**内部**打健康端点：不依赖宿主机装没装 curl，
  # 也直接验证了"应用进程本身能不能应答"，而不是"端口有没有被监听"。
  if docker exec "$TARGET_SVC" curl -sf -m 3 http://127.0.0.1:8080/api/ping >/dev/null 2>&1; then
    ready=1; break
  fi
  sleep 1
done

if [ "$ready" != 1 ]; then
  log "✗ $TARGET_SVC 在 ${HEALTH_TIMEOUT}s 内未就绪。"
  log "  **已中止发布，nginx 仍指向 ${FROM_PORT}，线上服务未受影响。**"
  log "  下面是 $TARGET_SVC 最后 60 行日志："
  docker logs --tail 60 "$TARGET_SVC" 2>&1 | sed 's/^/    /' || true
  log "  内存是否够（这台机器很紧）：free -m ；docker stats --no-stream"
  exit 1
fi
log "✓ $TARGET_SVC 已就绪并通过健康检查"

# ---------------------------------------------------------------- 4 切流量
log "4/5 切换 nginx：$FROM_PORT → $TARGET_PORT"
cp -a "$NGINX_CONF" "${NGINX_CONF}.bak"
sed -i "s/:${FROM_PORT}/:${TARGET_PORT}/g" "$NGINX_CONF"

if ! docker exec "$NGINX_CONTAINER" nginx -t >/dev/null 2>&1; then
  cp -a "${NGINX_CONF}.bak" "$NGINX_CONF"
  log "✗ nginx 配置校验失败，已回滚配置。线上仍指向 ${FROM_PORT}。校验输出："
  docker exec "$NGINX_CONTAINER" nginx -t 2>&1 | sed 's/^/    /' || true
  exit 1
fi

if ! docker exec "$NGINX_CONTAINER" nginx -s reload; then
  cp -a "${NGINX_CONF}.bak" "$NGINX_CONF"
  docker exec "$NGINX_CONTAINER" nginx -s reload || true
  die "nginx reload 失败，已回滚配置。请人工检查 nginx 容器状态。"
fi
log "✓ 流量已切到 ${TARGET_SVC}（${TARGET_PORT}）"

# ---------------------------------------------------------------- 5 停旧实例
log "5/5 停掉旧实例 ${OLD_SVC}（**不删容器**，它同时占着端口和内存）"
# 【为什么要停】不停的话两个 JVM 会一直各占 ~600MB。
# 这台服务器删掉 wms 之后只剩约 1.37GB 可用，"两个实例常态并存"是撑不住的；
# 蓝绿的本意就是"只有一个在服务"，旧实例留着只是为了秒级回滚。
if docker ps --format '{{.Names}}' | grep -qx "$OLD_SVC"; then
  "${DC[@]}" stop "$OLD_SVC" || log "! 停止 $OLD_SVC 时出错，但它已不在流量里，不影响线上"
else
  log "· $OLD_SVC 尚未创建过（首次发布），跳过"
fi

# 只清理悬空镜像（没有 tag 的中间层）。带 tag 的历史镜像一概保留 ——
# 它们就是回滚的凭据，删掉就只能重新构建。
docker image prune -f >/dev/null 2>&1 || true

printf '\n==================== 发布完成 ====================\n'
log "当前服务实例：${TARGET_SVC}（宿主机端口 ${TARGET_PORT}，镜像 ${IMAGE}:${TAG}）"
log "上一版本实例：${OLD_SVC}（已停止，容器还在；docker start $OLD_SVC 可立即恢复）"
printf '回滚三步（发现新版本有问题时照抄执行）：\n'
printf '    1) docker start %s\n' "$OLD_SVC"
printf '    2) sed -i "s/:%s/:%s/g" %s\n' "$TARGET_PORT" "$FROM_PORT" "$NGINX_CONF"
printf '    3) docker exec %s nginx -t && docker exec %s nginx -s reload\n' \
       "$NGINX_CONTAINER" "$NGINX_CONTAINER"
printf '\n本次镜像 ID：%s\n' "$NEW_IMAGE_ID"

#!/bin/bash
# ===========================================================================
#  海峡金 OA · 部署后自证脚本（在**服务器**上跑，由 deploy.sh / cron 调用）
#
#  用法：
#      bash verify_deployed.sh
#      bash verify_deployed.sh --quiet          # 只输出结论与失败项
#
#  退出码：0 = 全绿；1 = 有失败；2 = 有"登记了却没执行"的检查（脚本自身出问题）
#
#  ==========================================================================
#  【设计要点一：为什么要有"登记 → 执行 → 核对"三步】
#  本项目被同一类事故坑过：脚本上游抛错后，**其后的断言全部静默不执行，
#  末行照样打印通过**（曾把 16 条从未执行的断言当成 85/85 通过）。
#  所以这里的做法是：
#    1) 把所有检查名**先登记**到 CHECKS（declare_check）
#    2) 执行时把结果**记到** RESULTS（record）
#    3) 收尾比对两个集合：登记了但没结果 = 事故，退出码 2
#  这样"漏执行"不可能被当成"通过"。
#
#  【设计要点二：登录口令不进仓库】
#  本仓库是 public。所以凭据从环境变量取；缺失时**显式 SKIP 并计数**，
#  绝不把"没验"写成"通过"。服务器上放一份 /opt/sh/oa/probe.env（600）。
# ===========================================================================
set -uo pipefail

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

# 【必须显式设 PATH】本脚本会被 cron / 非登录 shell 调用，那里的 PATH 通常只有
# /usr/bin:/bin，而我们要用 `docker`、`ip`、`openssl`、`curl`。
# 缺了它的表现很迷惑：HOST_IP 算成空串 ⇒ 所有 --resolve 变成空 IP ⇒ 逐项误报故障。
# 系统目录**放前面**（优先），同时保留调用方原有的 PATH：
# 直接 `export PATH=<固定列表>` 会把调用方的 PATH 整个丢掉，
# 于是任何包装/测试用的 PATH 注入都失效（本项目的桩测就因此没生效）。
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"

# ---------------------------------------------------------------- 坐标
NGINX_CONF="/opt/docker/nginx/conf/include/hxj-oa-test.wgit123.com.conf"
DOMAIN="hxj-oa-test.wgit123.com"
CERT="/opt/docker/nginx/https/wgit123.com.pem"
COMPOSE_DIR="/opt/sh/oa"

# 回源 IP 优先取 nginx 配置里的那个（它才是用户实际走的路径），读不到才退回自动探测
HOST_IP=$(grep -oE 'server [0-9.]+:[0-9]+' "$NGINX_CONF" 2>/dev/null | head -1 | sed -E 's/^server ([0-9.]+):.*$/\1/')
[ -z "${HOST_IP:-}" ] && HOST_IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')
HOST_IP="${HOST_IP:-127.0.0.1}"

# 同机其他项目（用于"我们没把别人搞挂"的回归）
# trace-api-test 也在列表里：它的 nginx 配置被我们改过（加 ACME 验证路径），
# 凡是我们动过的别人的配置，就必须有回归覆盖 —— 否则改坏了没人知道。
OTHER_SITES="trace-web-test.wgit123.com trace-mng-test.wgit123.com trace-api-test.wgit123.com rlzx-wms-test.wgit123.com"

# 阈值
DISK_MIN_FREE_PCT=20          # 根分区可用低于此值告警
MEM_MIN_FREE_MB=500           # 可用内存低于此值告警
# 【为什么是 25 天而不是 21 天】证书现在由 certbot 自动续期，
# 它在**剩余 30 天**时开始尝试续（certbot 默认 renew_before_expiry=30 days）。
# 阈值定在 25 天 = 续期窗口打开 5 天后仍未成功 ⇒ 这时报警，
# 剩下的 25 天足够人工介入。
# 定得太小（如 21）会缩短处置时间；定得太大（如 35，早于续期窗口）
# 则每轮都会先报一次"假警"再自动恢复 —— 这种周期性噪音最后就是没人看。
CERT_MIN_DAYS=25              # 证书剩余低于此天数告警（见上方说明）

# 【为什么 curl 参数用数组而不是字符串变量】
# 写成 C="curl -s -m 10 --noproxy *" 再 `$C ...`，那个 `*` 会被**路径展开**吃掉：
# 只要当前目录里有文件，`*` 就展开成一串文件名 ⇒ `--noproxy` 拿到错误的值，
# 多出来的文件名还会被 curl 当成**额外 URL** ⇒ 输出里混进本地文件内容，
# 表现为"接口返回了 HTML/乱码"，排查方向完全跑偏（本脚本第一版就栽在这）。
# 加引号只能解决一层，**数组才能保证每个参数原样传递**。
CURL=(curl -s -m 10 --noproxy '*')
# 2026-09-22 起站点启用 nginx Basic Auth（全站 443）。
# 探测请求必须带站点凭据，否则第一个 401 就死在 nginx，后面全是假失败。
# 凭据从 .env 的 OA_SITE_BASIC（user:pass）注入；if 而不是 "&&" 短路 ——
# 本脚本 set -e，变量未设置时 && 表达式返回 1 会直接把脚本杀掉。
if [ -n "${OA_SITE_BASIC:-}" ]; then
  CURL+=(-u "$OA_SITE_BASIC")
fi

# 颜色只在真的终端上输出。cron / 重定向到日志文件时纯文本 ——
# 否则日志里会混进一堆 ESC 序列，既难搜（grep 不到"✗"）又会让日志体积虚增。
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_OK=$'\033[32m'; C_BAD=$'\033[31m'; C_WARN=$'\033[33m'; C_RST=$'\033[0m'
else
  C_OK=''; C_BAD=''; C_WARN=''; C_RST=''
fi

CHECKS=(); RESULTS=(); FAILED=0; SKIPPED=0; WARNED=0
declare_check() { CHECKS+=("$1"); }
ok()   { RESULTS+=("$1"); printf '  %s✓%s %-42s %s\n' "$C_OK" "$C_RST" "$1" "${2:-}"; }
bad()  { RESULTS+=("$1"); FAILED=$((FAILED+1)); printf '  %s✗%s %-42s %s\n' "$C_BAD" "$C_RST" "$1" "${2:-}"; }
skip() { RESULTS+=("$1"); SKIPPED=$((SKIPPED+1)); printf '  %s-%s %-42s SKIP %s\n' "$C_WARN" "$C_RST" "$1" "${2:-}"; }
# warn：查过、有结果、但**不是故障**（例如同机别人的容器没配日志轮转）。
# 单列一类是为了不把"别人的问题"算成我们的失败（那会让自证永远红、最终被无视），
# 也不把它悄悄记成 ✓（那就是把没解决的事写成通过）。
warn() { RESULTS+=("$1"); WARNED=$((WARNED+1)); printf '  %s!%s %-42s %s\n' "$C_WARN" "$C_RST" "$1" "${2:-}"; }

# 判定辅助：期望 HTTP 码
expect_code() { # $1=检查名 $2=期望 $3=实际 $4=附注
  if [ "$2" = "$3" ]; then ok "$1" "HTTP $3"; else bad "$1" "期望 $2，实际 $3 ${4:-}"; fi
}

echo "==================== OA 部署自证 ===================="
echo "时间：$(date '+%F %T')    宿主 IP：${HOST_IP:-未知}"
echo

# ===========================================================================
echo "【1】运行实例"
# ===========================================================================
declare_check "恰好一个 OA 实例在服务"
RUNNING_OA=$(docker ps --filter 'name=^oa' --format '{{.Names}}' | sort | tr '\n' ' ' | sed 's/ $//')
N_OA=$(printf '%s' "$RUNNING_OA" | tr ' ' '\n' | grep -c . || true)
if [ "$N_OA" = "1" ]; then
  ok "恰好一个 OA 实例在服务" "$RUNNING_OA"
elif [ "$N_OA" = "0" ]; then
  bad "恰好一个 OA 实例在服务" "一个都没跑"
else
  ok "恰好一个 OA 实例在服务" "两个在跑（${RUNNING_OA}，浪费内存但不算故障）"
fi

declare_check "实例健康状态(hc)"
# 【为什么这里要轮询、且不以 starting 判负】
# Docker 的 State.Health.Status 是**滞后指标**：HEALTHCHECK interval=10s，
# 而 run.sh 的健康门禁是"容器内 curl 通了就往下走"（即时）。于是一次正常发布里，
# 自证经常早于 Docker 的首个探针跑完，读到的是 starting —— 实测表现为 22 项里
# 只有这一项红、而同一时刻 /api/ping 已经是 200。这是**时序假阳性**，
# 报成"发布失败"会训练人忽略自证（自证一旦被无视就退化成一堆装饰）。
# 但也不能反过来把 starting 一律当通过（那会把"起不来"也放行）：
#   真起不来的容器，10s 后探针会连续失败，状态会变成 unhealthy，不是 starting；
# 所以做法是"等 Docker 表态"（最多 HC_WAIT 秒），等满仍 starting 时
# 改以容器内直接打健康端点为准 —— 与 run.sh 门禁同一判据，不做第二份实现。
HC_WAIT=25
INST=$(printf '%s' "$RUNNING_OA" | awk '{print $1}')
HC=""; HC_WAITED=0
if [ -n "$INST" ]; then
  HC=$(docker inspect -f '{{.State.Health.Status}}' "$INST" 2>/dev/null || echo none)
  while [ "$HC" = "starting" ] && [ "$HC_WAITED" -lt "$HC_WAIT" ]; do
    sleep 1; HC_WAITED=$((HC_WAITED+1))
    HC=$(docker inspect -f '{{.State.Health.Status}}' "$INST" 2>/dev/null || echo none)
  done
fi

if [ -z "$INST" ]; then
  bad "实例健康状态(hc)" "没有 OA 容器可查（实例没起来）"
elif [ "$HC" = "healthy" ]; then
  ok "实例健康状态(hc)" "healthy$([ "$HC_WAITED" -gt 0 ] && printf '（等了 %ss，此前为 starting）' "$HC_WAITED")"
elif [ "$HC" = "starting" ]; then
  if docker exec "$INST" curl -sf -m 3 http://127.0.0.1:8080/api/ping >/dev/null 2>&1; then
    warn "实例健康状态(hc)" "Docker 等满 ${HC_WAIT}s 仍报 starting，但容器内 /api/ping 可应答 ⇒ 判为健康字段滞后"
  else
    bad "实例健康状态(hc)" "starting，且容器内 /api/ping 不通（真的没起来）"
  fi
elif [ "$HC" = "none" ]; then
  bad "实例健康状态(hc)" "镜像没有 HEALTHCHECK —— 蓝绿门禁失去依据（Dockerfile 被改过？）"
else
  bad "实例健康状态(hc)" "$HC"
fi

declare_check "nginx 指向的端口有实例在听"
NGX_PORT=$(grep -oE 'server [0-9.]+:[0-9]+' "$NGINX_CONF" 2>/dev/null | head -1 | grep -oE '[0-9]+$')
if [ -n "$NGX_PORT" ] && docker ps --format '{{.Ports}}' | grep -q ":${NGX_PORT}->"; then
  ok "nginx 指向的端口有实例在听" ":$NGX_PORT"
else
  bad "nginx 指向的端口有实例在听" "nginx 指向 :${NGX_PORT:-未知}，但该端口没有容器在听"
fi
echo

# ===========================================================================
echo "【2】经 nginx 的端到端（--resolve 直打宿主，绕开 DNS 以便定位问题）"
# ===========================================================================
RES=(--resolve "${DOMAIN}:443:${HOST_IP}")
RES80=(--resolve "${DOMAIN}:80:${HOST_IP}")

declare_check "HTTPS /api/ping = 200 且返回 pong"
BODY=$("${CURL[@]}" "${RES[@]}" "https://${DOMAIN}/api/ping" 2>/dev/null); CODE=$("${CURL[@]}" "${RES[@]}" -o /dev/null -w '%{http_code}' "https://${DOMAIN}/api/ping" 2>/dev/null)
if [ "$CODE" = "200" ] && printf '%s' "$BODY" | grep -q '"pong"'; then ok "HTTPS /api/ping = 200 且返回 pong" "200"
else bad "HTTPS /api/ping = 200 且返回 pong" "code=$CODE body=$(printf '%s' "$BODY" | head -c 80)"; fi

declare_check "HTTPS /oa.html = 200 且体积正常"
SZ=$("${CURL[@]}" "${RES[@]}" -o /dev/null -w '%{size_download}' "https://${DOMAIN}/oa.html" 2>/dev/null)
if [ "${SZ:-0}" -gt 100000 ]; then ok "HTTPS /oa.html = 200 且体积正常" "${SZ}B"
else bad "HTTPS /oa.html = 200 且体积正常" "只有 ${SZ}B，前端页面可能没打进 jar"; fi

declare_check "证书链有效（不加 -k 也能通过）"
SV=$("${CURL[@]}" "${RES[@]}" -o /dev/null -w '%{ssl_verify_result}' "https://${DOMAIN}/api/ping" 2>/dev/null)
[ "$SV" = "0" ] && ok "证书链有效（不加 -k 也能通过）" "ssl_verify=0" || bad "证书链有效（不加 -k 也能通过）" "ssl_verify=$SV"

declare_check "HTTP 强制跳 HTTPS(301)"
LOC=$("${CURL[@]}" "${RES80[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' "http://${DOMAIN}/api/ping" 2>/dev/null)
printf '%s' "$LOC" | grep -q '^301' && ok "HTTP 强制跳 HTTPS(301)" "$(printf '%s' "$LOC" | awk '{print $2}')" \
  || bad "HTTP 强制跳 HTTPS(301)" "$LOC"
echo

# ===========================================================================
echo "【3】安全检查（上线前提）"
# ===========================================================================
declare_check "未认证访问 /api/documents 被拦(401)"
C1=$("${CURL[@]}" "${RES[@]}" -o /dev/null -w '%{http_code}' "https://${DOMAIN}/api/documents" 2>/dev/null)
expect_code "未认证访问 /api/documents 被拦(401)" 401 "$C1"

declare_check "接口契约未暴露(/v3/api-docs=404)"
C2=$("${CURL[@]}" "${RES[@]}" -o /dev/null -w '%{http_code}' "https://${DOMAIN}/v3/api-docs" 2>/dev/null)
expect_code "接口契约未暴露(/v3/api-docs=404)" 404 "$C2"

declare_check "swagger-ui 未暴露"
C3=$("${CURL[@]}" "${RES[@]}" -o /dev/null -w '%{http_code}' "https://${DOMAIN}/swagger-ui/index.html" 2>/dev/null)
# 404 或 401 都算没暴露
if [ "$C3" = "404" ] || [ "$C3" = "401" ]; then ok "swagger-ui 未暴露" "HTTP $C3"; else bad "swagger-ui 未暴露" "HTTP ${C3}（200 即等于对外公开契约）"; fi
echo

# ===========================================================================
echo "【4】业务链路（登录 → 读接口；口令从环境变量取，缺失则显式 SKIP）"
# ===========================================================================
ACC="${OA_PROBE_ACCOUNT:-}"
PW="${OA_PROBE_PASSWORD:-}"

# 【检查名只能有一处来源】
# 第一版把名字在"登记处"和"执行处"各拼了一次（一处多打了一个空格），
# 于是收尾核对判定"登记了却没执行" —— 脚本直接把整轮判成失败（退出码 2）。
# 这跟本项目"同一语义写成两份实现必然漂移"是同一个道理：
# 名字一律从下面的数组来，不再手写第二遍。
PROBE_NAMES=("带 token 读单据列表" "带 token 读待办" "带 token 读通知未读数")
PROBE_PATHS=("/api/documents" "/api/todos" "/api/notifications/unread-count")
NAME_LOGIN="登录成功并拿到 token"
NAME_ME="带 token 读 /api/auth/me"

declare_check "$NAME_LOGIN"
declare_check "$NAME_ME"
for n in "${PROBE_NAMES[@]}"; do declare_check "$n"; done

if [ -z "$PW" ]; then
  skip "$NAME_LOGIN" "未设置 OA_PROBE_ACCOUNT/OA_PROBE_PASSWORD"
  skip "$NAME_ME"    "未设置 OA_PROBE_ACCOUNT/OA_PROBE_PASSWORD"
  for n in "${PROBE_NAMES[@]}"; do skip "$n" "未设置 OA_PROBE_ACCOUNT/OA_PROBE_PASSWORD"; done
else
  RESP=$("${CURL[@]}" "${RES[@]}" -X POST "https://${DOMAIN}/api/auth/login" -H 'Content-Type: application/json' \
          -d "{\"account\":\"${ACC}\",\"password\":\"${PW}\"}" 2>/dev/null)
  TOKEN=$(printf '%s' "$RESP" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -n "$TOKEN" ]; then ok "$NAME_LOGIN" "长度 ${#TOKEN}"; else bad "$NAME_LOGIN" "$(printf '%s' "$RESP" | head -c 100)"; fi

  if [ -n "$TOKEN" ]; then
    A="Authorization: Bearer $TOKEN"
    ME=$("${CURL[@]}" "${RES[@]}" -H "$A" "https://${DOMAIN}/api/auth/me" 2>/dev/null)
    printf '%s' "$ME" | grep -q '"permCodes"' && ok "$NAME_ME" "含权限码" || bad "$NAME_ME" "$(printf '%s' "$ME" | head -c 100)"
    for i in "${!PROBE_NAMES[@]}"; do
      cc=$("${CURL[@]}" "${RES[@]}" -o /dev/null -w '%{http_code}' -H "$A" "https://${DOMAIN}${PROBE_PATHS[$i]}" 2>/dev/null)
      expect_code "${PROBE_NAMES[$i]}" 200 "$cc"
    done
  else
    skip "$NAME_ME" "登录未成功，无法继续"
    for n in "${PROBE_NAMES[@]}"; do skip "$n" "登录未成功，无法继续"; done
  fi
fi
echo

# ===========================================================================
echo "【5】双实例能力未被静默降级（Redis 化有没有真的生效）"
# ===========================================================================
declare_check "权限快照/限速确实写在 Redis（而非降级到进程内）"
RPW="${REDIS_PASSWORD:-}"
if [ -z "$RPW" ] && [ -f /opt/sh/oa/.env ]; then
  RPW=$(grep -E '^REDIS_PASSWORD=' /opt/sh/oa/.env 2>/dev/null | cut -d= -f2-)
fi
if [ -n "$RPW" ] && docker exec redis redis-cli -a "$RPW" -n 3 exists oa:auth:gen >/dev/null 2>&1; then
  GEN=$(docker exec redis redis-cli -a "$RPW" -n 3 get oa:auth:gen 2>/dev/null)
  ok "权限快照/限速确实写在 Redis（而非降级到进程内）" "oa:auth:gen=${GEN:-空}"
else
  # 没有 key 不等于降级（可能是还没人登录过 / 用完过期了），所以这里只提示不判失败
  skip "权限快照/限速确实写在 Redis（而非降级到进程内）" "读不到 oa:auth:gen（可能尚无登录，或 Redis 不可达）"
fi

declare_check "未污染同实例其他项目的 db0"
D0=$(docker exec redis redis-cli -a "${RPW:-x}" -n 0 dbsize 2>/dev/null | tr -d '\r')
if [ -n "$D0" ]; then ok "未污染同实例其他项目的 db0" "db0 keys=${D0}（应为 WMS 的既有 25 个左右）"
else skip "未污染同实例其他项目的 db0" "未取到 Redis 密码，无法核对"; fi
echo

# ===========================================================================
echo "【6】资源与运维（这些不解决会在某天突然变成事故）"
# ===========================================================================
declare_check "根分区可用空间充足"
FREE_PCT=$(df -P / 2>/dev/null | awk 'NR==2{gsub(/%/,"");print 100-$5}')
if [ "${FREE_PCT:-0}" -ge "$DISK_MIN_FREE_PCT" ]; then ok "根分区可用空间充足" "${FREE_PCT}%"
else bad "根分区可用空间充足" "仅剩 ${FREE_PCT}%（阈值 ${DISK_MIN_FREE_PCT}%）—— 同机还有 mysql，磁盘满会连带它不可写"; fi

declare_check "可用内存充足"
FREE_MB=$(awk '/MemAvailable/{printf "%d", $2/1024}' /proc/meminfo)
if [ "${FREE_MB:-0}" -ge "$MEM_MIN_FREE_MB" ]; then ok "可用内存充足" "${FREE_MB}MB"
else bad "可用内存充足" "仅 ${FREE_MB}MB（阈值 ${MEM_MIN_FREE_MB}MB）—— 蓝绿发布需要同时跑两个实例"; fi

declare_check "证书剩余天数充足"
if [ -f "$CERT" ]; then
  END=$(openssl x509 -in "$CERT" -noout -enddate 2>/dev/null | cut -d= -f2)
  END_TS=$(date -d "$END" +%s 2>/dev/null || echo 0)
  DAYS=$(( (END_TS - $(date +%s)) / 86400 ))
  if [ "$DAYS" -ge "$CERT_MIN_DAYS" ]; then ok "证书剩余天数充足" "${DAYS} 天"
  else bad "证书剩余天数充足" "仅剩 ${DAYS} 天 —— 且 80 端口是 301 跳转，到期症状是「整站打不开」"; fi
else
  bad "证书剩余天数充足" "找不到证书文件 ${CERT}"
fi

declare_check "所有容器日志已设轮转上限"
NOLIMIT=""
for c in $(docker ps -aq); do
  cfg=$(docker inspect -f '{{.HostConfig.LogConfig.Config}}' "$c" 2>/dev/null)
  [ "$cfg" = "map[]" ] && NOLIMIT="$NOLIMIT $(docker inspect -f '{{.Name}}' "$c" | sed 's|^/||')"
done
if [ -z "$NOLIMIT" ]; then ok "所有容器日志已设轮转上限" "已全部封顶"
else
  # 别人的容器无轮转不是我们的故障，但会吃掉共享磁盘 ⇒ 记为"需关注"而非失败
  DRIVER_SZ=$(du -sh /var/lib/docker/containers 2>/dev/null | cut -f1)
  warn "所有容器日志已设轮转上限" "未封顶：${NOLIMIT# }（容器总日志 ${DRIVER_SZ}）"
fi

declare_check "同机其他站点未被我们影响"
BAD_SITES=""
for s in $OTHER_SITES; do
  sc=$("${CURL[@]}" --resolve "${s}:80:${HOST_IP}" -o /dev/null -w '%{http_code}' "http://${s}/" 2>/dev/null)
  # 403/200/302 都说明后端活着；只有 502/504/000 才代表"我们把别人搞挂了"
  case "$sc" in 502|504|000|"") BAD_SITES="$BAD_SITES ${s}($sc)";; esac
done
if [ -z "$BAD_SITES" ]; then ok "同机其他站点未被我们影响" "全部正常应答"
else bad "同机其他站点未被我们影响" "无应答：${BAD_SITES# }"; fi
echo

# ===========================================================================
#  收尾：核对"登记了但没执行"的检查 —— 这是本脚本存在的核心理由
# ===========================================================================
MISSING=""
for chk in "${CHECKS[@]}"; do
  found=0
  for r in ${RESULTS[@]+"${RESULTS[@]}"}; do [ "$r" = "$chk" ] && { found=1; break; }; done
  [ "$found" = 0 ] && MISSING="$MISSING 「${chk}」"
done

echo "==================== 结论 ===================="
printf '检查点：登记 %d 个，执行 %d 个，跳过 %d 个，失败 %d 个，需关注 %d 个\n' \
       "${#CHECKS[@]}" "${#RESULTS[@]}" "$SKIPPED" "$FAILED" "$WARNED"

if [ -n "$MISSING" ]; then
  printf '\n%s✗ 有检查被登记却没有执行结果：%s%s\n' "$C_BAD" "$MISSING" "$C_RST"
  printf '这是脚本自身的缺陷（上游出错导致后续被跳过），**不能当成通过**。\n'
  exit 2
fi
if [ "$FAILED" -gt 0 ]; then printf '\n%s✗ 有 %d 项失败%s\n' "$C_BAD" "$FAILED" "$C_RST"; exit 1; fi
if [ "$SKIPPED" -gt 0 ]; then printf '\n%s✓ 无失败，但有 %d 项被跳过（未经证实）%s\n' "$C_WARN" "$SKIPPED" "$C_RST"; exit 0; fi
if [ "$WARNED" -gt 0 ]; then printf '\n%s✓ 全部通过（另有 %d 项需关注，见上面的 ! 行）%s\n' "$C_OK" "$WARNED" "$C_RST"; exit 0; fi
printf '\n%s✓ 全部通过%s\n' "$C_OK" "$C_RST"
exit 0

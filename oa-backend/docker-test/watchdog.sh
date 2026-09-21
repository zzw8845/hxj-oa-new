#!/bin/bash
# ===========================================================================
#  海峡金 OA · 服务器巡检看门狗（由 cron 定时调用，只报告、不自动改动）
#
#  用法：
#      bash watchdog.sh              # 正常输出
#      bash watchdog.sh --verbose    # 把每一项都打出来（默认只在异常时打明细）
#
#  退出码：0 = 一切正常；1 = 有需要人工处理的事项
#
#  【为什么是"只报告"】
#  这台服务器上还跑着别人的 mysql / wms / trace。自动去删别人的日志、
#  自动重启别人的容器，风险远大于收益。看门狗只负责"把问题说出来"，
#  动作由人决定 —— 这类脚本的价值在于**让问题不再依赖人想起来看**。
#
#  【安装】crontab 里加一行（见文件末尾）
# ===========================================================================
set -uo pipefail

LOG="/var/log/oa-watchdog.log"
VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

# 【必须显式设 PATH】cron 的 PATH 通常只有 /usr/bin:/bin，
# 而本脚本要用到 `ip`（在 /usr/sbin）与 `docker`。
# 缺 PATH 的后果不是"报命令找不到"这么明显 —— HOST_IP 会算成空字符串，
# 所有 --resolve 变成 `域名:443:`（空 IP），于是**每一项都被判成故障**，
# 而你在终端里手工跑一次却完全正常。这类"只在 cron 里坏"的问题最耗时间。
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

DOMAIN="hxj-oa-test.wgit123.com"
NGINX_CONF="/opt/docker/nginx/conf/include/hxj-oa-test.wgit123.com.conf"
CERT="/opt/docker/nginx/https/wgit123.com.pem"

# 回源 IP **优先从 nginx 配置里读**：那才是 nginx 实际使用的地址，
# 保证"我探测的路径"和"用户走的路径"是同一条；读不到才退回自动探测网卡。
HOST_IP=$(grep -oE 'server [0-9.]+:[0-9]+' "$NGINX_CONF" 2>/dev/null | head -1 | sed -E 's/^server ([0-9.]+):.*$/\1/')
[ -z "${HOST_IP:-}" ] && HOST_IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')
HOST_IP="${HOST_IP:-127.0.0.1}"
# 【curl 参数必须用数组】写成字符串变量再 `$C ...` 时，`--noproxy *` 里的 `*`
# 会被**路径展开**吃掉（当前目录有文件时），多余的文件名被 curl 当成额外 URL，
# 结果是拿本地文件内容当响应来判断 —— 会得出完全错误的结论。
# cron 的工作目录不确定，这类依赖 cwd 的写法在定时任务里尤其危险。
CURL=(curl -s -m 8 --noproxy '*')

DISK_WARN_PCT=20          # 根分区可用低于此值
MEM_WARN_MB=400           # 可用内存低于此值（蓝绿发布要同时跑两个实例）
CERT_WARN_DAYS=21         # 证书剩余低于此天数
CLOG_WARN_MB=2048         # 单个容器日志超过此值

PROBLEMS=()
note() { [ "$VERBOSE" = 1 ] && printf '  %s\n' "$*" || true; }
problem() { PROBLEMS+=("$1"); printf '  ! %s\n' "$1"; }

# ---------------------------------------------------------------- 日志自管理
# 【为什么不让 cron 用 `>> log` 重定向】
# 若由 cron 打开日志的 fd，脚本内部再 mv 轮转，那个 fd 仍指向被改名后的旧 inode：
#   本次输出继续写进 xxx.log.1，而新创建的 xxx.log 是空的 —— 看起来"轮转成功"，
#   实际是日志被劈成两半、以后每次都在写旧文件。
# 所以：非交互（cron）时由脚本**自己**打开日志，且**在打开之前**完成轮转。
if [ ! -t 1 ]; then
  LSZ=$(stat -c %s "$LOG" 2>/dev/null || echo 0)
  [ "$LSZ" -gt 1048576 ] && mv -f "$LOG" "${LOG}.1"
  exec >> "$LOG" 2>&1
fi

TS=$(date '+%F %T')
printf '\n===== %s 巡检 =====\n' "$TS"

# ---------------------------------------------------------------- 1 服务可用
if [ "$("${CURL[@]}" -o /dev/null -w '%{http_code}' --resolve "${DOMAIN}:443:${HOST_IP}" "https://${DOMAIN}/api/ping" 2>/dev/null)" = "200" ]; then
  note "✓ /api/ping 200"
else
  problem "OA 对外不可用：https://${DOMAIN}/api/ping 不是 200。查：docker ps --filter name=^oa；docker logs --tail 80 oa"
fi

RUNNING=$(docker ps --filter 'name=^oa' --format '{{.Names}}' | tr '\n' ' ' | sed 's/ $//')
if [ -z "$RUNNING" ]; then
  problem "没有任何 OA 实例在跑（nginx 会 502）"
else
  note "✓ 运行中的实例：$RUNNING"
fi

# ---------------------------------------------------------------- 2 磁盘
FREE_PCT=$(df -P / 2>/dev/null | awk 'NR==2{gsub(/%/,"");print 100-$5}')
USED_PCT=$((100 - ${FREE_PCT:-0}))
if [ "${FREE_PCT:-0}" -lt "$DISK_WARN_PCT" ]; then
  problem "根分区仅剩 ${FREE_PCT}% 可用（已用 ${USED_PCT}%）。同机还有 mysql，磁盘满会连带它不可写。
      排查：du -sh /var/lib/docker/containers/* | sort -rh | head
      安全回收：docker builder prune -f   （⚠ 绝不要用 docker image prune -a，会删掉别人的 :latest）"
else
  note "✓ 根分区可用 ${FREE_PCT}%"
fi

# ---------------------------------------------------------------- 3 内存
FREE_MB=$(awk '/MemAvailable/{printf "%d", $2/1024}' /proc/meminfo)
if [ "${FREE_MB:-0}" -lt "$MEM_WARN_MB" ]; then
  problem "可用内存仅 ${FREE_MB}MB。蓝绿发布需同时跑两个 OA 实例（各约 300MB），内存不足时发布会在健康检查处中止。
      临时腾内存：docker stop wms-mng（改完记得 docker update --restart=no 再停，否则重启会自动回来）"
else
  note "✓ 可用内存 ${FREE_MB}MB"
fi
SWAP_USED=$(free -m 2>/dev/null | awk '/Swap/{print $3}')
[ "${SWAP_USED:-0}" -gt 500 ] && problem "swap 已用 ${SWAP_USED}MB，说明内存长期吃紧，应考虑扩容或降配"

# ---------------------------------------------------------------- 4 证书
if [ -f "$CERT" ]; then
  END_TS=$(date -d "$(openssl x509 -in "$CERT" -noout -enddate 2>/dev/null | cut -d= -f2)" +%s 2>/dev/null || echo 0)
  DAYS=$(( (END_TS - $(date +%s)) / 86400 ))
  if [ "$DAYS" -lt "$CERT_WARN_DAYS" ]; then
    problem "证书还有 ${DAYS} 天到期（$CERT）。因为 80 端口是 301 强制跳转，到期症状是「整站打不开」而不是「证书报错」。
      续期后：覆盖 $CERT 与同名 .key，再 docker exec nginx nginx -s reload"
  else
    note "✓ 证书剩余 ${DAYS} 天"
  fi
else
  problem "找不到证书 $CERT"
fi

# ---------------------------------------------------------------- 5 容器日志（共享磁盘杀手）
BIG=""
while read -r sz_mb name; do
  [ -z "${sz_mb:-}" ] && continue
  if [ "$sz_mb" -ge "$CLOG_WARN_MB" ]; then
    BIG="$BIG ${name}(${sz_mb}MB)"
  fi
done < <(
  for id in $(docker ps -aq); do
    f=$(docker inspect -f '{{.LogPath}}' "$id" 2>/dev/null); [ -z "$f" ] && continue
    printf '%s %s\n' "$(du -m "$f" 2>/dev/null | cut -f1)" "$(docker inspect -f '{{.Name}}' "$id" | sed 's|^/||')"
  done
)
if [ -n "$BIG" ]; then
  problem "容器日志超过 ${CLOG_WARN_MB}MB：${BIG# }
      根因：/etc/docker/daemon.json 没有 log-opts ⇒ 未显式配置的容器日志永不轮转。
      治根要给 daemon.json 加 \"log-opts\": {\"max-size\":\"50m\",\"max-file\":\"3\"}，
      但**改它需要重启 docker，会重启同机全部容器**（Live Restore Enabled: false）⇒ 必须走维护窗口。"
else
  note "✓ 容器日志均在阈值内"
fi

# ---------------------------------------------------------------- 6 同机其他站点（我们的回归责任）
BROKEN=""
for s in trace-web-test.wgit123.com trace-mng-test.wgit123.com rlzx-wms-test.wgit123.com; do
  sc=$("${CURL[@]}" --resolve "${s}:80:${HOST_IP}" -o /dev/null -w '%{http_code}' "http://${s}/" 2>/dev/null)
  case "$sc" in 502|504|000|"") BROKEN="$BROKEN ${s}($sc)";; esac
done
[ -n "$BROKEN" ] && problem "同机其他站点无应答：${BROKEN# }（若刚做过发版/腾内存，先回想是不是自己干的）" || note "✓ 其他站点正常"

# ---------------------------------------------------------------- 结论与自轮转
if [ "${#PROBLEMS[@]}" -eq 0 ]; then
  printf '%s ✓ 全部正常（磁盘 %s%% 可用 / 内存 %sMB / 证书 %s 天）\n' "$TS" "$FREE_PCT" "$FREE_MB" "${DAYS:-?}"
  RC=0
else
  RC=1
fi

exit $RC

# ===========================================================================
#  安装（crontab -e，加这一行；每 15 分钟一次）
#
#      */15 * * * * /bin/bash /opt/sh/oa/watchdog.sh
#
#  注意：**不要**在 crontab 里写 `>> /var/log/...` —— 脚本自己会写日志
#  （见文件开头的"日志自管理"），由 cron 再重定向一次会破坏轮转。
#
#  只看最近一次结论：      tail -30 /var/log/oa-watchdog.log
#  只找问题：              grep -n '  ! ' /var/log/oa-watchdog.log | tail -20
#  手工跑一次（打屏）：    bash /opt/sh/oa/watchdog.sh --verbose
# ===========================================================================

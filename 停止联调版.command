#!/bin/bash
# 海峡金 OA 审批系统 · 联调版  停止（停掉「启动联调版.command」拉起的服务）
set -u
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"

DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_DIR="$DIR/.run"

kill_one() {   # $1=pid文件  $2=名称
  local f="$1" name="$2" p
  if [ ! -f "$f" ]; then echo "· 未找到 $name 的 PID 记录，跳过"; return; fi
  p="$(cat "$f" 2>/dev/null)"
  if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
    kill "$p" 2>/dev/null
    echo "✓ 已停止 ${name}（pid ${p}）"
  else
    echo "· $name 未在运行"
  fi
  rm -f "$f"
}

echo "正在停止联调版服务…"
# 先停守护进程，否则它会立刻把子服务拉回来
kill_one "$RUN_DIR/launcher.pid" "启动器守护"
sleep 1
kill_one "$RUN_DIR/backend.pid"  "后端"
kill_one "$RUN_DIR/web.pid"      "静态服务"

for p in 8080 5180; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "! 端口 $p 仍被占用（可能不是本脚本启动的进程）："
    lsof -nP -iTCP:"$p" -sTCP:LISTEN | tail -n +2 | awk '{print "    pid=" $2, $1}'
  fi
done

echo "完成。"

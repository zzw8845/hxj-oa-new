#!/bin/bash
# =============================================================
#  海峡金 OA 审批系统 · 联调版  一键启动（自带守护）
#
#  作用：确保后端(8080)在跑；掉了会自动拉起；然后打开页面。
#  默认由后端**同源**提供页面：http://127.0.0.1:8080/oa.html
#  —— 同源请求不触发 CORS，可绕开「CORS 类浏览器扩展改写响应头」导致的拦截。
#  想改回「另起静态服务 + 本地文件」的旧模式：SERVE=1 ./启动联调版.command
#
#  用法：双击本文件；或终端执行 ./启动联调版.command
#        NO_OPEN=1       只启服务不打开浏览器（自动化测试用）
#        NO_SUPERVISE=1  不进入守护循环
#        SERVE=1         另起静态服务并用 http 打开（旧模式）
# =============================================================
set -u
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
# 本机可能设了 HTTP_PROXY，会让 localhost 的探测走代理（死服务返回 502 而非拒绝连接），
# 导致健康判断失真。localhost 一律直连。
export no_proxy="127.0.0.1,localhost,::1"
export NO_PROXY="$no_proxy"

DIR="$(cd "$(dirname "$0")" && pwd)"
BE_DIR="$DIR/oa-backend"
JAR="$BE_DIR/oa-boot/target/oa-boot-1.0.0-SNAPSHOT.jar"
PAGE="$DIR/海峡金OA审批系统-联调版.html"
RUN_DIR="$DIR/.run"
BE_PORT=8080
WEB_PORT="${WEB_PORT:-5180}"
PY="$(command -v python3 || echo /usr/bin/python3)"

BE_PID=""
WEB_PID=""

mkdir -p "$RUN_DIR"

# ---------- 工具函数 ----------
# 判断"服务是否可用"只以 HTTP 健康检查为准，绝不用"端口有人监听"来推断：
#   · 只写 -iTCP:PORT 会把别的进程监听在 [::1]:PORT(IPv6) 也算成已占用，
#     而浏览器走的是 127.0.0.1(IPv4) → 启动器说"在运行"、浏览器却 ERR_CONNECTION_REFUSED；
#   · 写成 -iTCP@127.0.0.1:PORT 又漏掉绑定在 *:PORT 通配地址的正常服务。
#   · curl 不加 -f 时 502/404 也算成功，必须加 -f 并校验响应内容。
port_busy()   { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
alive()       { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }
be_healthy()  { curl -sf --noproxy '*' -m 3 "http://127.0.0.1:${BE_PORT}/v3/api-docs" 2>/dev/null | grep -q '"openapi"'; }
web_healthy() { curl -sf --noproxy '*' -o /dev/null -m 3 "http://127.0.0.1:${WEB_PORT}/" 2>/dev/null; }
# 后端在跑 ≠ 页面能开：老 jar 里没有静态页。用 ASCII 标记校验，避免 locale 影响中文匹配。
page_healthy(){ curl -sf --noproxy '*' -m 3 "http://127.0.0.1:${BE_PORT}/oa.html" 2>/dev/null | grep -q 'hxj_oa_token'; }

cleanup() {
  echo ""
  echo "正在停止本次启动的服务…"
  alive "$BE_PID"  && kill "$BE_PID"  2>/dev/null
  alive "$WEB_PID" && kill "$WEB_PID" 2>/dev/null
  rm -f "$RUN_DIR/launcher.pid" "$RUN_DIR/backend.pid" "$RUN_DIR/web.pid"
  echo "已停止。"
  exit 0
}

# ---------- 启动后端 ----------
start_be() {
  local JAVA_HOME
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "✗ 未找到 JDK 17，请先安装：brew install openjdk@17"
    return 1
  fi
  if [ ! -f "$JAR" ]; then
    echo "· 未找到后端包，开始构建（首次约 1-2 分钟）…"
    ( cd "$BE_DIR" && JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" mvn -q -o -DskipTests package ) \
      || { echo "✗ 后端构建失败，请查看上方报错"; return 1; }
  fi
  mkdir -p "$BE_DIR/logs"
  # 用 exec 让子 shell 被 java 替换，$! 才是 java 的真实 PID（否则 kill 杀不掉）
  ( cd "$BE_DIR" && exec env JAVA_HOME="$JAVA_HOME" "$JAVA_HOME/bin/java" \
      -jar "$JAR" --server.port=${BE_PORT} > logs/app.log 2>&1 ) &
  BE_PID=$!
  echo "$BE_PID" > "$RUN_DIR/backend.pid"
  printf "· 正在启动后端（%s）" "${BE_PORT}"
  for _ in $(seq 1 60); do
    if be_healthy; then echo " ✓ 已就绪"; return 0; fi
    printf "."
    sleep 2
  done
  echo " ✗ 启动超时，日志：$BE_DIR/logs/app.log"
  return 1
}

# ---------- 启动静态服务（可选） ----------
start_web() {
  ( cd "$DIR" && exec "$PY" -m http.server ${WEB_PORT} --bind 127.0.0.1 >/dev/null 2>&1 ) &
  WEB_PID=$!
  echo "$WEB_PID" > "$RUN_DIR/web.pid"
  for _ in $(seq 1 10); do
    if web_healthy; then echo "✓ 静态服务已就绪（${WEB_PORT}）"; return 0; fi
    sleep 0.5
  done
  echo "✗ 静态服务启动失败"; return 1
}

# ---------- 主流程 ----------
echo "=============================================="
echo "  海峡金 OA 审批系统 · 联调版"
echo "=============================================="

if [ ! -f "$PAGE" ]; then
  echo "✗ 找不到页面文件：$PAGE"
  read -r -p "按回车键关闭…" _; exit 1
fi

# 单实例守护：重复双击会让多个守护抢着拉服务，互相打架
OLD_LP="$(cat "$RUN_DIR/launcher.pid" 2>/dev/null || true)"
if alive "$OLD_LP"; then
  echo "✓ 已有守护进程在运行（PID ${OLD_LP}）——本次不再重复启动。"
  echo "  如需重启，请先双击「停止联调版.command」。"
  exit 0
fi

if be_healthy; then
  echo "✓ 后端已就绪（端口 ${BE_PORT}）"
elif port_busy ${BE_PORT}; then
  echo "! 端口 ${BE_PORT} 被其它进程占用但无响应，请先释放该端口"
else
  start_be
fi

# 后端在跑但页面取不到 → 多半是通过「旧 jar」启动的，静态页还没打进去
if be_healthy && ! page_healthy; then
  echo "! 后端在运行，但它没有提供页面（可能是用旧 jar 启动的）。"
  echo "  本次打开的地址可能 404。请先停止服务并重新启动，以加载新构建的 jar。"
fi

USE_HTTP=0
if [ "${SERVE:-0}" = "1" ]; then
  USE_HTTP=1
  if web_healthy; then
    echo "✓ 静态服务已就绪（端口 ${WEB_PORT}）"
  elif port_busy ${WEB_PORT}; then
    echo "! 端口 ${WEB_PORT} 被其它进程占用但无响应，请改用其它端口：WEB_PORT=xxxx"
  else
    start_web
  fi
fi

echo $$ > "$RUN_DIR/launcher.pid"

if [ "$USE_HTTP" = "1" ]; then
  ENC="$(/usr/bin/python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$(basename "$PAGE")" 2>/dev/null || basename "$PAGE")"
  TARGET="http://127.0.0.1:${WEB_PORT}/${ENC}"
else
  # 同源地址：由后端自己提供页面，浏览器不会做跨域校验
  TARGET="http://127.0.0.1:${BE_PORT}/oa.html"
fi

echo ""
echo "----------------------------------------------"
echo "  打开地址：$TARGET"
echo "  演示账号密码：123456（页面上点名字即可切换）"
echo "  停止服务：Ctrl+C，或双击「停止联调版.command」"
echo "----------------------------------------------"

if [ "${NO_OPEN:-0}" != "1" ]; then
  open "$TARGET"
fi

if [ "${NO_SUPERVISE:-0}" = "1" ]; then
  echo "[守护未启用]"
  exit 0
fi

echo "[守护中] 服务掉线会自动拉起，每 5 秒巡检一次…"
trap cleanup INT TERM

while true; do
  sleep 5
  if ! be_healthy && ! port_busy ${BE_PORT} && ! alive "$BE_PID"; then
    echo "[$(date '+%H:%M:%S')] 后端掉线，正在重新拉起…"
    start_be
  fi
  if [ "$USE_HTTP" = "1" ] && ! web_healthy && ! port_busy ${WEB_PORT} && ! alive "$WEB_PID"; then
    echo "[$(date '+%H:%M:%S')] 静态服务掉线，正在重新拉起…"
    start_web
  fi
done

#!/bin/bash
# ===========================================================================
#  海峡金 OA · certbot 部署钩子（--deploy-hook）
#
#  触发时机：**每次成功续期之后**由 certbot 自动调用（全新签发时也会调用）。
#  职责：把 /etc/letsencrypt/live 下的新证书，装到 nginx **实际读取**的路径，
#        校验无误后 reload nginx。
#
#  ── 为什么不直接把 nginx 指向 /etc/letsencrypt ────────────────────────────
#  nginx 跑在容器里，/etc/letsencrypt 没有被挂进去（只挂了三处：
#  /opt/docker/nginx/{https,conf/include,logs} 与 /opt/html）。
#  在挂载目录里放**软链**指向 /etc/letsencrypt 是行不通的：
#  容器内的软链会指向容器里不存在的路径 ⇒ nginx 启动即失败 ⇒
#  这台机器上 3 个站点（hxj-oa / trace-mng / trace-api）一起挂。
#  所以只能**复制内容**。
#
#  ── 为什么装之前要先自证证书覆盖了三个域名 ──────────────────────────────
#  这三个域名共用同一个证书文件。若某次签发只覆盖了其中一个（例如
#  -d 参数漏写、或用了 --cert-name 复用了旧配置），装上去以后另外两个站点
#  会在**下一次 nginx reload 之后**才开始报证书错误 —— 而这次 reload 是
#  我们主动做的，等于自己把另外两个站点搞挂。
#  宁可这次续期失败（certbot 会报警、旧证书还在，站点继续用旧的），
#  也不要装上一张会连带炸掉别人的证书。
#
#  ── 为什么先写 .new 再 mv ───────────────────────────────────────────────
#  mv 是同目录 rename，原子操作。直接 `cp` 覆盖的话，nginx 若恰好在
#  cp 写到一半时 reload，会读到"写了一半的 PEM"，表现为随机性的
#  nginx 启动失败 —— 那种故障排查起来极其痛苦（重跑一次就好了）。
# ===========================================================================
set -uo pipefail

# cron / systemd 的 PATH 比登录 shell 窄，docker 可能不在里面
# 系统目录**放前面**（优先），同时保留调用方原有的 PATH：
# 直接 `export PATH=<固定列表>` 会把调用方的 PATH 整个丢掉，
# 于是任何包装/测试用的 PATH 注入都失效（本项目的桩测就因此没生效）。
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"

LIVE_DIR="${OA_CERT_LIVE_DIR:-/etc/letsencrypt/live/wgit123}"
DEST_DIR="${OA_CERT_DEST_DIR:-/opt/docker/nginx/https}"
DEST_CRT="$DEST_DIR/wgit123.com.pem"
DEST_KEY="$DEST_DIR/wgit123.com.key"
NGINX_CONTAINER="${OA_NGINX_CONTAINER:-nginx}"
# docker 可执行文件路径可覆盖。用处有二：
#   ① 有的机器 docker 不在 PATH 的标准目录里（装到 /opt 或 snap 之类）；
#   ② 让桩测能把它换成假的 —— 靠"往 PATH 前面塞一个假 docker"做不到，
#      因为这个脚本设计上就把系统目录放在 PATH 最前面（防止被污染的 PATH 抢走 docker）。
DOCKER_BIN="${OA_DOCKER_BIN:-docker}"
LOG_FILE="${OA_CERT_LOG:-/var/log/oa-cert-deploy.log}"

# 这三个域名共用同一份证书文件，缺一不可
REQUIRED_DOMAINS="hxj-oa-test.wgit123.com trace-mng-test.wgit123.com trace-api-test.wgit123.com"

# 这台机器上已经出过"某容器日志涨到 33.5GB 把磁盘吃满"的事故，
# 所以钩子自己的日志也要有上限。这里简单粗暴：超过 256KB 就清空重来 ——
# 每次续期只写两三行，正常情况下永远碰不到这个分支。
if [ -f "$LOG_FILE" ] && [ "$(stat -c%s "$LOG_FILE" 2>/dev/null || echo 0)" -gt 262144 ]; then
  : > "$LOG_FILE"
fi

# 同时输出到 certbot 自己的日志（letsencrypt.log）和本文件，两边都留痕
log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG_FILE"; }
bye() { log "✗ $*"; exit 1; }

log "── certbot 部署钩子启动（$*）──"

[ -r "$LIVE_DIR/fullchain.pem" ] || bye "读不到 $LIVE_DIR/fullchain.pem，certbot 没把证书放这儿？"
[ -r "$LIVE_DIR/privkey.pem" ]   || bye "读不到 $LIVE_DIR/privkey.pem"

# ---- 0) 测试模式：按设计**不安装**，但也不判失败 ---------------------------
# certbot 在 --dry-run / --staging 下会设置 CERTBOT_TEST_CERT=1，此时它签发的
# 是测试证书。这里必须区分两种"不装"：
#   · 测试模式下的不装，是**预期行为** → 记一行说明、退出 0。
#     否则 `certbot renew --dry-run`（我们用来验证整条续期链路的命令）
#     每次都因为"deploy hook 失败"而报错 —— 一个永远红的检查，
#     按本项目踩过的教训，最后就是没人再看它。
#   · 正常模式下的不装，是**事故** → 后面照旧大声报错、退出 1。
if [ "${CERTBOT_TEST_CERT:-0}" = "1" ]; then
  log "· certbot 处于测试模式（CERTBOT_TEST_CERT=1）—— 本轮**不安装**证书（预期行为，不算失败）"
  exit 0
fi

# ---- 1) 先落到同目录的 .new，装之前全部校验，不合格就原地退出 ----------------
cp -fL "$LIVE_DIR/fullchain.pem" "${DEST_CRT}.new" || bye "复制 fullchain 失败"
cp -fL "$LIVE_DIR/privkey.pem"   "${DEST_KEY}.new" || bye "复制 privkey 失败"
chmod 644 "${DEST_CRT}.new"
chmod 600 "${DEST_KEY}.new"

# 1a) 能解析成 X.509
openssl x509 -in "${DEST_CRT}.new" -noout >/dev/null 2>&1 || bye "新证书解析失败，拒绝安装（沿用旧证书）"

# 1a-2) 绝不允许把 **ACME 测试证书**装上线上
# Let's Encrypt 建议先用 --dry-run 验证续期链路；而 dry-run 会签发一张
# **staging** 证书（issuer 里带 STAGING），浏览器不信任它。
# 万一 certbot 在 dry-run 时触发了 deploy-hook，装上去的后果就是
# 三个站点立刻全部报证书错误 —— 而且只有真人打开浏览器才发现。
# 所以在装之前就拦掉，代价接近零。
ISSUER=$(openssl x509 -in "${DEST_CRT}.new" -noout -issuer 2>/dev/null)
# 只认"确定是测试"的特征，不要写成 *test* 这种宽泛匹配 ——
# 那会在将来某个 CA 名字里含 test 时把**正常证书**拒之门外，
# 表现是续期成功但装不上（比装错更难查）。
case "$ISSUER" in
  *STAGING*|*Staging*|*staging*|*Fake*|*fake*)
    bye "这是 ACME 测试/staging 证书（issuer: ${ISSUER}），拒绝安装到线上" ;;
esac

# 1b) 三个域名一个都不能少
# 【为什么不用 `openssl x509 -ext subjectAltName`】那是 OpenSSL 专有选项，
# LibreSSL 没有（本项目的开发机就是 LibreSSL）。而一旦它失败返回空，
# 下面的判断会把**正常证书**判成"缺少域名" ⇒ 续期成功却永远装不上，
# 等 60 天后三个站点一起过期。这种"越安全越安静"的失败必须消掉。
# 改成读 `-text` 全量输出再匹配 `DNS:<域名>`：
#   · `-text` 在 OpenSSL 与 LibreSSL 上都有；
#   · 要求带 `DNS:` 前缀 + 后接分隔符，所以"只有 CN 没有 SAN"的证书不会被误判成通过。
CERT_TEXT=$(openssl x509 -in "${DEST_CRT}.new" -noout -text 2>/dev/null)
[ -n "$CERT_TEXT" ] || bye "读不出证书内容（openssl x509 -text 无输出），拒绝安装"
MISSING=""
for d in $REQUIRED_DOMAINS; do
  printf '%s' "$CERT_TEXT" | grep -qE "DNS:${d}([, ]|$)" || MISSING="$MISSING $d"
done
[ -z "$MISSING" ] || bye "新证书缺少域名：${MISSING# } —— 拒绝安装（装上会把用到这份证书的站点一起搞挂）"

# 1c) 私钥与证书是**同一对**。nginx -t 其实也会发现不匹配，但在这里拦下，
#     报错信息里能直接看到是哪一对不匹配，比 nginx 的 "key values mismatch" 好查。
CERT_PUB=$(openssl x509 -in "${DEST_CRT}.new" -noout -pubkey 2>/dev/null | openssl md5)
KEY_PUB=$(openssl pkey -in "${DEST_KEY}.new" -pubout 2>/dev/null | openssl md5)
[ -n "$CERT_PUB" ] && [ "$CERT_PUB" = "$KEY_PUB" ] || bye "证书与私钥不匹配（cert=$CERT_PUB key=${KEY_PUB}）"

NOT_AFTER=$(openssl x509 -in "${DEST_CRT}.new" -noout -enddate 2>/dev/null | cut -d= -f2)

# ---- 2) 原子替换（同目录 rename）-------------------------------------------
mv -f "${DEST_CRT}.new" "$DEST_CRT" || bye "替换 $DEST_CRT 失败"
mv -f "${DEST_KEY}.new" "$DEST_KEY" || bye "替换 $DEST_KEY 失败"

# ---- 3) 校验并 reload ------------------------------------------------------
# nginx -t 会在解析配置阶段就加载证书文件，所以它同时覆盖了
# "配置语法对不对" 和 "新证书能不能被 nginx 读出来" 两件事。
if ! "$DOCKER_BIN" exec "$NGINX_CONTAINER" nginx -t >/dev/null 2>&1; then
  log "✗ nginx -t 失败，新证书已就位但**没有 reload**。"
  log "  当前 nginx 内存里仍是旧证书，站点不受影响；但下次重启会加载新证书。"
  log "  请立刻人工排查：$DOCKER_BIN exec $NGINX_CONTAINER nginx -t"
  exit 1
fi

if "$DOCKER_BIN" exec "$NGINX_CONTAINER" nginx -s reload >/dev/null 2>&1; then
  log "✓ 证书已安装并 reload 成功（notAfter=${NOT_AFTER}）"
else
  log "✗ nginx reload 失败，请人工检查：$DOCKER_BIN exec $NGINX_CONTAINER nginx -s reload"
  exit 1
fi
exit 0

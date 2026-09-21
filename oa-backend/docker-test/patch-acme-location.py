#!/usr/bin/env python3
# ===========================================================================
#  给 nginx vhost 的 :80 server 块准备 ACME HTTP-01 验证路径
#
#  用法：
#      python3 patch-acme-location.py            # 只检查，不改（默认）
#      python3 patch-acme-location.py --apply    # 真的改（先自动备份）
#
#  ── 为什么需要「插 location」这一步 ──────────────────────────────────────
#  certbot 走 HTTP-01 时要让 Let's Encrypt 能从公网取到
#  http://<域名>/.well-known/acme-challenge/<token>。
#  这台机器上 80 端口归 nginx 容器，而三个站点的 :80 写法各不相同：
#    · hxj-oa    → `return 301`（强制跳 HTTPS）
#    · trace-mng → 静态文件，root 指到 /opt/html/traceMng
#    · trace-api → proxy_pass 到 8091
#  三种写法都不会去读放 challenge 文件的目录，所以必须各插一个 location。
#
#  ── 为什么用 location ^~ ─────────────────────────────────────────────────
#  `^~` 是"前缀匹配且不让位给正则"。虽然只写 location 时前缀最长者胜出，
#  但 trace-api 那份配置里将来若有人加了正则 location，`^~` 能保证
#  ACME 这条路径不会被正则抢走 —— 抢走的表现是**续期静默失败**：
#  平时一切正常，60 天后证书过期才被发现。
#
#  ── 为什么还要额外处理 server 级的 `return 301`（本项目踩到的真坑）──────
#  第一版只插了 location，实测 hxj-oa 的验证路径**仍然返回 301**。
#  原因是 `return`（以及 `rewrite`）属于 ngx_http_rewrite_module，
#  在 **rewrite 阶段**执行，而 rewrite 阶段**早于 location 选择**：
#  写在 server 块里的 `return 301` 会先把请求跳走，后面插的 location
#  根本没有机会被匹配到。所以必须把这条 return 收进 `location /` 里面，
#  让 acme 的 `^~` 前缀能因"更长"而胜出。
#  这个坑只有实测才发现得了：`nginx -t` 是过的，配置看着也完全合理。
#
#  ── 为什么是纯插入、不重写文件 ───────────────────────────────────────────
#  只改这两处，其余字节原样保留，这样另外两个站点是别人维护的、
#  git diff / diff 也看得清到底改了什么。
# ===========================================================================
import os
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

# 允许覆盖，便于把服务器上的真实配置拉到本地先演一遍再动手 ——
# 这是要改共享宿主机上别人维护的配置文件，先看结果比先动手便宜得多。
CONF_DIR = Path(os.environ.get("OA_NGINX_CONF_DIR", "/opt/docker/nginx/conf/include"))
BACKUP_DIR = Path(os.environ.get("OA_NGINX_BACKUP_DIR", "/opt/sh/oa/nginx-conf-backup"))

TARGETS = [
    "hxj-oa-test.wgit123.com.conf",
    "trace-mng-test.wgit123.com.conf",
    "trace-api-test.wgit123.com.conf",
]

ACME_BLOCK = """
    # --- ACME HTTP-01 验证路径（certbot 自动续期用）----------------------
    # 由 docker-test/patch-acme-location.py 幂等插入，请勿手改语义。
    # 只服务这一条路径，不参与跳转与反代，对本站点原有业务零影响。
    location ^~ /.well-known/acme-challenge/ {
        root /opt/html;
        default_type text/plain;
        try_files $uri =404;
    }
"""


def is_code(line: str) -> bool:
    s = line.strip()
    return bool(s) and not s.startswith("#")


def locate_p80(lines):
    """:80 的 listen 行与其后的 server_name 行"""
    for i, ln in enumerate(lines):
        if re.match(r"\s*listen\s+80\s*;", ln):
            idx_listen = i
            break
    else:
        return None, None, "找不到 `listen 80;`"

    for j in range(idx_listen + 1, len(lines)):
        if re.match(r"\s*server_name\s", lines[j]):
            return idx_listen, j, None
        if lines[j].strip() == "}":
            break
    return None, None, "在 `listen 80;` 之后找不到 `server_name`（两者可能不在同一 server 块）"


def scan_server_block(lines, idx_sn):
    """从 server_name 之后扫描到该 server 块的结尾。

    返回 (块内 server 级语句行号列表, 结尾行号, err)。
    【必须先按花括号深度算，不能拿"第一个内容是 } 的行"当块尾】——
    server 块里任何一个 location 的闭合括号都长得跟块尾一样，
    第一版就是这么判的，结果把 location 的 `}` 当成了块尾，
    导致 hxj-oa 那条 return 压根没被处理到（nginx -t 还是过的，很难发现）。
    """
    depth = 1  # 已经在 server 块内部
    stmts = []
    for i in range(idx_sn + 1, len(lines)):
        raw = lines[i]
        stripped = raw.strip()
        # 去掉注释再数括号：配置文件里的中文注释含 `}` 的概率不为零
        code = "" if stripped.startswith("#") else raw.split("#", 1)[0]

        if stripped and not stripped.startswith("#") and depth == 1:
            stmts.append(i)

        for ch in code:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return stmts, i, None
    return stmts, None, "无法确定该 server 块的结尾（花括号不配对？）"


def wrap_server_level_return(lines, idx_sn):
    """把 :80 块里 server 级的 `return 301/302` 收进 `location /`。

    【为什么等价】改了以后：命中 acme 前缀的走 acme location，
    其余所有路径都被 `location /` 接住继续 301 —— 除了 ACME 那条路径，
    对外行为一字不变。

    返回 ((替换区间, 新行列表) 或 None, 说明)。
    """
    stmts, end, err = scan_server_block(lines, idx_sn)
    if err:
        return None, err

    # 【顺序不能反】必须先找 server 级 return，再判它的冲突。
    # 第一版把"块内已有 location /"的检查放在前面，结果 trace-mng / trace-api
    # 这两份**根本没有 server 级 return** 的配置也被报成"跳转关系不直观" ——
    # 一个不存在的冲突。这种误报比漏报更坏：看两次没人理，第三次真问题也没人看。
    target_line = None
    for i in stmts:
        if re.match(r"^\s*return\s+30[123478]\s+\S", lines[i]):
            target_line = i
            break
    if target_line is None:
        return None, "没有 server 级 return 跳转（无需处理）"

    # 块内已经有 location / 了，却又存在 server 级 return —— 说明这份配置
    # 的跳转关系不直观（server 级 return 会抢在 location 之前，那个 location /
    # 实际上是死代码）。这不是本次要改的东西，报出来交人工。
    if any(re.match(r"\s*location\s+/\s*\{", lines[i]) for i in stmts):
        return None, "块内同时存在 server 级 return 与 `location /`，跳转关系不直观，交人工确认"

    m = re.match(r"^(\s*)return\s+(30[123478])(\s+)(\S.*?);\s*$", lines[target_line])
    if not m:
        return None, f"`{lines[target_line].strip()}` 形式不标准，交人工确认"
    indent, code, sp, target = m.groups()

    new_block = [
        f"{indent}location / {{\n",
        f"{indent}    return {code}{sp}{target};\n",
        f"{indent}}}\n",
    ]
    return (target_line, target_line + 1, new_block), (
        f"已把 server 级 `return {code}` 收进 `location /`"
        "（它先于 location 执行，不收起来 ACME 路径抢不过）"
    )


def patch_one(path: Path, apply: bool):
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    msgs = []

    # ---- 步骤 1：server 级 return 收进 location /（幂等）------------------
    idx_listen, idx_sn, err = locate_p80(lines)
    if err:
        return [f"✗ {err}"], False

    # 收进 location / 以后就没有"server 级 return"了，所以这步天然幂等
    res, msg = wrap_server_level_return(lines, idx_sn)
    if res is None:
        msgs.append("· " + msg)
    else:
        a, b, new_block = res
        lines = lines[:a] + new_block + lines[b:]
        msgs.append("✓ " + msg)

    # ---- 步骤 2：插入 ACME location（幂等）--------------------------------
    if "acme-challenge" in "".join(lines):
        msgs.append("· ACME location 已存在（跳过）")
    else:
        idx_listen, idx_sn, err = locate_p80(lines)
        if err:
            return [f"✗ 插入阶段：{err}"], False
        lines = lines[: idx_sn + 1] + [ACME_BLOCK] + lines[idx_sn + 1:]
        msgs.append("✓ 已插入 ACME location")

    if apply:
        BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d%H%M%S")
        shutil.copy2(path, BACKUP_DIR / f"{path.name}.{stamp}.bak")
        # 先写临时文件再 replace：避免写到一半被 nginx reload 读到半个文件
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text("".join(lines), encoding="utf-8")
        tmp.replace(path)
    return msgs, True


def main() -> int:
    apply = "--apply" in sys.argv
    print(f"== ACME 验证路径准备 · {'APPLY（会真的改文件）' if apply else 'DRY-RUN（只看不改）'} ==")
    rc = 0
    for name in TARGETS:
        p = CONF_DIR / name
        if not p.exists():
            print(f"  ✗ {name}：不存在")
            rc = 1
            continue
        msgs, ok = patch_one(p, apply)
        print(f"  {name}")
        for m in msgs:
            print(f"      {m}")
        if not ok:
            rc = 1
    if apply:
        print(f"\n备份目录：{BACKUP_DIR}")
        print("下一步：docker exec nginx nginx -t && docker exec nginx nginx -s reload")
    return rc


if __name__ == "__main__":
    sys.exit(main())

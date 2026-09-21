#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""横向越权（IDOR）验证：单据详情 / 附件接口必须与「列表口径」一致。

## 背景

`DocumentService.assertVisible()` 曾用一段**独立的 Java switch** 判定可见性，
而列表查询走 `DataScopeHelper.buildClause()` 生成 SQL。两套实现必然漂移：

    case DEPT, CENTER, CUSTOM_DEPT ->
            Objects.equals(doc.getDeptId(), user.getDeptId())
            || (user.getDeptPath() != null && user.getDeptPath().length() > 1);   // 与 doc 无关，恒真

只要用户有部门（path 形如 `/6/`，长度 > 1），第二个条件恒为 true ⇒ **任何** dept/center
范围的用户都能按 ID 读取**任意**单据的详情、附件列表、下载附件 —— 哪怕这些单据
在他的列表里根本不存在。这就是典型的 IDOR（横向越权）。

## 本脚本证明什么

对同一批「账号 × 单据」组合，同时取三份证据并断言它们互相自洽：

1. `GET /api/documents?pageSize=200`  —— 该单据**是否出现在他的列表里**（列表口径）
2. `GET /api/documents/{id}`          —— 详情是否放行（详情口径）
3. `GET /api/attachments?documentId=` —— 附件接口是否放行（第三个调用点）

期望不变量：

    详情/附件放行  ⟺  单据在列表中可见  或  该用户是本单据的流程参与者

`zhouzh`（周综合，DEPT_HEAD，数据范围 dept，部门 6 `/6/`）对 `doc 1`
（业务一部 dept 8 的草稿，申请人黄小明，无流程参与者）**三份证据必须全部一致**：
列表没有它、详情 403、附件 403。修复前这里是「列表空 + 详情 200」，即漏洞。

`zhouzh` 对 `doc 46` 是流程参与者（assignee 含 user 8），列表里看不到它，
但详情/附件**必须继续放行** —— 这条用来防止「一刀切修过头」，把审批人挡在门外。

## 用法

    python3 scripts/verify_idor_fix.py --phase baseline   # 修复前：断言漏洞存在
    python3 scripts/verify_idor_fix.py                    # 修复后：断言已收敛

只读，不写库、不落文件（下载用例刻意不做，避免往磁盘留垃圾）。
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
PASSWORD = "123456"
PAGE_SIZE = 200

# 注意：BizException 由 GlobalExceptionHandler 转成 R.fail(403, ...)，
# 且该 handler 上没有 @ResponseStatus ⇒ HTTP 状态码仍是 200，业务码在 body.code。
# 所以判定一律读 body.code，不读 HTTP 状态。
# 业务码：0 = 成功放行；403 = 无权（越权被拦）；404 等其它码视为异常。

#           账号       单据   列表里应出现   详情业务码   附件业务码   说明
ROWS_FIXED = [
    ("zhouzh",   1,  False, 403, 403, "dept 范围跨部门单据 —— 漏洞点，必须 403"),
    ("zhouzh",  46,  False, 0,   0,   "流程参与者 —— 列表不含但必须放行"),
    ("linjl",    1,  True,  0,   0,   "本部门（/8/）子树内 —— 正常可见"),
    ("huangxm",  1,  True,  0,   0,   "本人发起（self 范围）"),
    ("admin",    1,  True,  0,   0,   "管理员 —— 放行"),
]
# 修复前：同一个漏洞点应当读到 0（放行）；其余行为完全相同（证明漏洞只影响这一个格）
ROWS_BASELINE = [
    ("zhouzh",   1,  False, 0,   0,   "dept 范围跨部门单据 —— 漏洞点，读到了"),
    ("zhouzh",  46,  False, 0,   0,   "流程参与者 —— 列表不含但放行"),
    ("linjl",    1,  True,  0,   0,   "本部门（/8/）子树内 —— 正常可见"),
    ("huangxm",  1,  True,  0,   0,   "本人发起（self 范围）"),
    ("admin",    1,  True,  0,   0,   "管理员 —— 放行"),
]


def http(method, path, token=None, payload=None):
    """返回 (http_status, body_dict, raw_prefix)。业务码在 body['code']。"""
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        status = e.code
    except Exception as e:  # 连接层失败：服务没起
        raise SystemExit("无法连接 %s：%s\n（先确认服务已在 8080 监听）" % (BASE, e))
    try:
        body = json.loads(raw.decode("utf-8"))
    except Exception:
        body = None
    return status, body, raw[:120].decode("utf-8", "replace")


def biz_code(status, body):
    """统一取业务码：有 R 包装就读 body.code，否则退回 HTTP 状态。"""
    if isinstance(body, dict) and "code" in body:
        return int(body["code"])
    return status


def code_text(code):
    """业务码展示：0 读作 OK，其余原样（403=无权）。"""
    return "OK" if code == 0 else str(code)


def login(account):
    status, body, raw = http("POST", "/api/auth/login",
                             payload={"account": account, "password": PASSWORD})
    if biz_code(status, body) != 0:
        raise SystemExit("登录失败 account=%s http=%s body=%s" % (account, status, raw))
    data = body.get("data") or {}
    token = data.get("token")
    if not token:
        raise SystemExit("登录响应没有 token：%s" % raw)
    user = data.get("user") or {}
    return token, user


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", choices=("baseline", "fixed"), default="fixed",
                    help="baseline=修复前（断言漏洞存在）；fixed=修复后（断言已收敛）")
    args = ap.parse_args()
    rows = ROWS_FIXED if args.phase == "fixed" else ROWS_BASELINE

    os.environ.setdefault("no_proxy", "127.0.0.1,localhost,::1")
    os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost,::1")

    print("=" * 78)
    print("横向越权验证  阶段=%s  目标=%s" % (args.phase, BASE))
    print("=" * 78)

    sessions = {}
    print("\n[1/2] 登录并采集各账号数据范围")
    for account in sorted({r[0] for r in rows}):
        token, user = login(account)
        sessions[account] = token
        print("  %-8s user_id=%-3s dept=%-4s path=%-6s scope=%-8s roles=%s"
              % (account, user.get("userId"), user.get("deptId"), user.get("deptPath"),
                 user.get("dataScope"), ",".join(user.get("roleCodes") or [])))

    print("\n[2/2] 逐格断言：列表 / 详情 / 附件 三份证据必须自洽")
    failures = []
    total = 0
    for account, doc_id, want_in_list, want_doc, want_att, note in rows:
        token = sessions[account]

        st, body, raw = http("GET", "/api/documents?pageNum=1&pageSize=%d" % PAGE_SIZE, token)
        if biz_code(st, body) != 0:
            failures.append("%s 列表查询失败 http=%s body=%s" % (account, st, raw))
            continue
        records = ((body.get("data") or {}).get("records")) or []
        ids = [r.get("id") for r in records]
        got_in_list = doc_id in ids
        list_total = (body.get("data") or {}).get("total")

        st_d, body_d, _ = http("GET", "/api/documents/%d" % doc_id, token)
        got_doc = biz_code(st_d, body_d)

        st_a, body_a, _ = http("GET", "/api/attachments?documentId=%d" % doc_id, token)
        got_att = biz_code(st_a, body_a)

        # 附加不变量：拿到放行就必须真的在列表里，或者是流程参与者。
        # 「列表里没有、也不是参与者、却读到 code=0」= 越权成立。
        leaked = (got_doc == 0 or got_att == 0) and not got_in_list
        is_participant = "参与" in note

        checks = [
            ("列表含该单据", got_in_list, want_in_list),
            ("详情业务码", got_doc, want_doc),
            ("附件业务码", got_att, want_att),
        ]
        bad = [(n, g, w) for n, g, w in checks if g != w]
        total += len(checks)
        status = "OK  " if not bad else "FAIL"
        if bad:
            failures.append("%s doc=%s %s" % (account, doc_id, bad))

        mark = "  ⚠ 越权" if (leaked and args.phase == "baseline" and not is_participant) else ""
        print("  %s %-8s doc=%-3s 列表总数=%-4s 含=%-5s 详情码=%-4s 附件码=%-4s | %s%s"
              % (status, account, doc_id, list_total, got_in_list,
                 code_text(got_doc), code_text(got_att), note, mark))

    print("-" * 78)
    if failures:
        print("断言失败 %d 项：" % len(failures))
        for f in failures:
            print("  - " + f)
        print("\n结论：与阶段 %s 的预期不符。" % args.phase)
        return 1

    if args.phase == "baseline":
        print("结论：漏洞【存在】—— zhouzh 列表里没有 doc 1，却能读到详情与附件（HTTP 200）")
        print("      这正是 assertVisible 的 deptPath 恒真条件造成的横向越权。")
    else:
        print("结论：漏洞【已阻断】—— 跨部门单据的详情与附件均返回 403；")
        print("      同时流程参与者（doc 46）与部门内用户（linjl）未被误伤。")
    print("断言 %d 项全部通过。" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
海峡金 OA —— API 全链路冒烟测试。

真实走一遍：登录 → 发起日常付款(5万) → 部门负责人 → 会计(按部门) → 公司领导(大额分支)
→ 出纳付款 → 办结。验证流程引擎、节点动态指派、金额条件分支、数据范围、状态机回写。

用法：
    python3 scripts/api_smoke_test.py [base_url]
默认 base_url = http://127.0.0.1:8080
"""
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
PASSWORD = "123456"

# 本脚本访问的是本地服务，必须绕过环境中的 HTTP_PROXY / HTTPS_PROXY，
# 否则请求会被代理拦截，返回 "upstream connect failed: Connection refused"
urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({})))

PASS, FAIL = [], []


def call(method, path, payload=None, token=None):
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"code": e.code, "msg": body[:300], "data": None}


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"  {'✓' if cond else '✗'} {name}" + (f"  {detail}" if detail else ""))


def section(title):
    print(f"\n{'=' * 68}\n{title}\n{'=' * 68}")


def main():
    section("1. 登录（BCrypt 校验 + 角色/权限/数据范围装配）")
    tokens = {}
    for account, name in [("admin", "系统管理员"), ("huangxm", "黄小明"), ("linjl", "林经理"),
                          ("wangkj", "王会计"), ("zhangzong", "张总"), ("zhaocs", "赵出纳")]:
        r = call("POST", "/api/auth/login", {"account": account, "password": PASSWORD})
        ok = r.get("code") == 0 and r.get("data", {}).get("token")
        if ok:
            tokens[account] = r["data"]["token"]
            u = r["data"]["user"]
            print(f"  ✓ {name:<6} roles={sorted(u.get('roleCodes', []))} scope={u.get('dataScope')}")
        else:
            print(f"  ✗ {name} 登录失败: {r.get('msg')}")
        check(f"登录 {name}", bool(ok), "" if ok else str(r.get("msg")))

    if "huangxm" not in tokens:
        print("\n登录失败，终止测试")
        return 1

    # 错误密码必须被拒绝
    bad = call("POST", "/api/auth/login", {"account": "huangxm", "password": "wrong-password"})
    check("错误密码被拒绝", bad.get("code") == 401, f"code={bad.get('code')}")

    # 无 token 访问受保护接口必须 401
    unauth = call("GET", "/api/documents/types")
    check("未登录访问被拦截", unauth.get("code") == 401, f"code={unauth.get('code')}")

    section("2. 部署流程到 Flowable 引擎（配置层 → BPMN → 引擎）")
    configs = call("GET", "/api/flows/configs", token=tokens["admin"]).get("data") or []
    check("流程配置可查", len(configs) >= 3, f"{[c['name'] for c in configs]}")
    for cfg in configs:
        if cfg.get("deployStatus") == 1:
            check(f"流程已部署 {cfg['name']}", True, cfg.get("procDefKey"))
            continue
        r = call("POST", f"/api/flows/configs/{cfg['id']}/deploy", None, tokens["admin"])
        d = r.get("data") or {}
        check(f"部署流程 {cfg['name']}", r.get("code") == 0,
              f"procDefKey={d.get('procDefKey')} {str(r.get('msg') or '')[:60]}")

    if configs:
        req = urllib.request.Request(f"{BASE}/api/flows/configs/{configs[0]['id']}/bpmn",
                                     headers={"Authorization": "Bearer " + tokens["admin"]})
        with urllib.request.urlopen(req, timeout=15) as resp:
            bpmn = resp.read().decode("utf-8")
        check("BPMN 已生成且含条件网关",
              "<exclusiveGateway" in bpmn and "conditionExpression" in bpmn, f"{len(bpmn)} 字节")
        check("BPMN 挂载了指派监听器", "assigneeTaskListener" in bpmn)
        print("    BPMN 关键片段：")
        for line in bpmn.split("\n"):
            st = line.strip()
            if st.startswith(("<process", "<userTask", "<exclusiveGateway",
                              "<sequenceFlow", "<conditionExpression")):
                print("      " + st[:118])

    section("3. 单据类型与动态表单 Schema")
    r = call("GET", "/api/documents/types", token=tokens["huangxm"])
    types = r.get("data") or []
    check("单据类型接口可用", len(types) >= 3, f"返回 {len(types)} 种：{[t['name'] for t in types]}")
    daily = next((t for t in types if t["code"] == "DAILY_PAYMENT"), None)
    check("含日常付款类型", daily is not None)
    if not daily:
        return 1

    r = call("GET", f"/api/forms/schema?docTypeId={daily['id']}&nodeKey=n1", token=tokens["huangxm"])
    schema = r.get("data") or {}
    fields = schema.get("fields", [])
    check("表单 Schema 可渲染", len(fields) > 0, f"{len(fields)} 个字段")
    check("发起节点字段可编辑", all(f.get("editable") for f in fields),
          f"可编辑字段数={sum(1 for f in fields if f.get('editable'))}")

    section("4. 流程预览（节点动态指派解析）")
    r = call("POST", f"/api/flows/configs/{daily['flowConfigId']}/preview",
             {"applicantId": 9, "bizCategory": "DAILY", "amount": 50000}, tokens["huangxm"])
    prev = r.get("data") or {}
    for n in prev.get("nodes", []):
        if n.get("nodeType") in (1, 4):
            print(f"    {n['nodeName']:<20} → {n.get('candidateNames')}  规则={n.get('hitRules')}")
    names = {n["nodeName"]: n.get("candidateNames") or [] for n in prev.get("nodes", [])}
    check("直属部门负责人→林经理", "林经理" in names.get("直属部门负责人", []))
    check("会计（按部门）→王会计", "王会计" in names.get("会计（按部门）", []))
    check("公司领导（大额）→张总", "张总" in names.get("公司领导（大额）", []))
    check("出纳付款→赵出纳", "赵出纳" in names.get("出纳付款", []))

    section("5. 发起单据（5 万元，应命中大额分支）")
    r = call("POST", "/api/documents", {
        "docTypeId": daily["id"],
        "formData": {
            "title": "采购服务器设备款",
            "amount": 50000,
            "payType": "GOODS",
            "payeeName": "福建某某科技有限公司",
            "payeeAccount": "3500123456789012",
            "payeeBank": "中国银行福州分行",
            "reason": "采购应用服务器 3 台，用于供应链金融平台建设",
        },
    }, tokens["huangxm"])
    doc = r.get("data") or {}
    doc_id = doc.get("id")
    check("创建草稿", r.get("code") == 0 and doc_id is not None, f"docNo={doc.get('docNo')} 状态={doc.get('status')}")
    if not doc_id:
        print(f"    失败原因: {r.get('msg')}")
        return 1
    check("单据编号规则 FK+日期+序号", str(doc.get("docNo", "")).startswith("FK"), doc.get("docNo"))

    # 服务端二次校验：缺必填字段应被拒绝
    bad_req = call("POST", "/api/documents", {
        "docTypeId": daily["id"],
        "formData": {"title": "缺字段测试"},
    }, tokens["huangxm"])
    bad_doc = bad_req.get("data") or {}
    if bad_doc.get("id"):
        sub = call("POST", f"/api/documents/{bad_doc['id']}/submit", None, tokens["huangxm"])
        check("服务端二次校验拦截缺字段", sub.get("code") != 0, f"msg={str(sub.get('msg'))[:80]}")

    r = call("POST", f"/api/documents/{doc_id}/submit", None, tokens["huangxm"])
    sd = r.get("data") or {}
    check("提交并启动流程", r.get("code") == 0,
          f"状态={sd.get('status')} 当前节点={sd.get('currentNodeName')}"
          + ("" if r.get("code") == 0 else f" msg={str(r.get('msg'))[:100]}"))

    section("6. 逐级审批（每步用真实待办驱动）")
    chain = [("linjl", "林经理"), ("wangkj", "王会计"), ("zhangzong", "张总"), ("zhaocs", "赵出纳")]
    for account, name in chain:
        todos = call("GET", "/api/todos", token=tokens[account]).get("data") or []
        mine = [t for t in todos if t["documentId"] == doc_id]
        if not mine:
            check(f"{name} 收到待办", False, "待办列表为空")
            break
        task = mine[0]
        check(f"{name} 收到待办", True, f"节点={task['nodeName']}")
        r = call("POST", "/api/todos/approve",
                 {"taskId": task["taskId"], "action": "approve", "comment": f"{name}同意"},
                 tokens[account])
        check(f"{name} 审批通过", r.get("code") == 0, str(r.get("msg"))[:80])

    section("7. 办结校验")
    r = call("GET", f"/api/documents/{doc_id}", token=tokens["huangxm"])
    detail = r.get("data") or {}
    d = detail.get("document") or {}
    status_text = {0: "草稿", 1: "待审批", 2: "审批中", 3: "已通过", 4: "已驳回", 5: "已撤回", 6: "已归档"}
    check("单据状态=已通过", d.get("status") == 3, f"status={d.get('status')}({status_text.get(d.get('status'))})")

    history = detail.get("flowHistory") or []
    print("  流转轨迹：")
    for n in history:
        print(f"    {n.get('seqNo'):>2}. {n.get('nodeName'):<20} {str(n.get('assigneeName') or '-'):<8} "
              f"{str(n.get('action') or '-'):<12} {n.get('commentText') or ''}")
    check("流转记录完整（4 个审批节点）",
          len([n for n in history if n.get("action") == "approve"]) >= 4,
          f"approve 记录 {len([n for n in history if n.get('action') == 'approve'])} 条")

    section("8. 数据范围（行级权限）")
    # 让林经理另发一笔，用来验证「员工看不到别人的单据、出纳能看到」
    r_other = call("POST", "/api/documents", {
        "docTypeId": daily["id"],
        "formData": {"title": "业务一部年度审计服务费", "amount": 3000, "payType": "SERVICE",
                     "payeeName": "福州某某服务有限公司", "payeeAccount": "3500987654321",
                     "payeeBank": "工商银行福州分行", "reason": "年度审计服务费"},
    }, tokens["linjl"])
    other = r_other.get("data") or {}
    check("部门负责人可发起单据", r_other.get("code") == 0, f"docNo={other.get('docNo')}")

    emp_docs = call("GET", "/api/documents?scope=all&pageSize=200",
                    token=tokens["huangxm"]).get("data", {}).get("records") or []
    check("普通员工(SELF)只能看到本人单据",
          all(x.get("applicantId") == 9 for x in emp_docs), f"可见 {len(emp_docs)} 条")
    check("普通员工看不到他人单据",
          other.get("id") is not None and all(x.get("id") != other.get("id") for x in emp_docs))

    cashier_docs = call("GET", "/api/documents?scope=all&pageSize=200",
                        token=tokens["zhaocs"]).get("data", {}).get("records") or []
    check("出纳(COMPANY)能看到他人单据",
          other.get("id") is not None and any(x.get("id") == other.get("id") for x in cashier_docs),
          f"出纳可见 {len(cashier_docs)} 条 vs 员工 {len(emp_docs)} 条")

    dept_docs = call("GET", "/api/documents?scope=all&pageSize=200",
                     token=tokens["linjl"]).get("data", {}).get("records") or []
    check("部门负责人(DEPT)可见本部门及下级单据",
          other.get("id") is not None and any(x.get("id") == other.get("id") for x in dept_docs),
          f"可见 {len(dept_docs)} 条")

    section("9. 驳回 / 重新提交 / 撤回（状态机闭合验证）")
    r_d2 = call("POST", "/api/documents", {
        "docTypeId": daily["id"],
        "formData": {"title": "驳回链路测试单", "amount": 1200, "payType": "OTHER",
                     "payeeName": "测试收款单位", "payeeAccount": "6222021234567890",
                     "payeeBank": "建设银行福州分行", "reason": "验证驳回后可否重新提交"},
    }, tokens["huangxm"])
    d2 = r_d2.get("data") or {}
    check("创建驳回测试单据", r_d2.get("code") == 0, f"docNo={d2.get('docNo')}")

    if d2.get("id"):
        check("提交成功", call("POST", f"/api/documents/{d2['id']}/submit", None,
                           tokens["huangxm"]).get("code") == 0)

        todos = call("GET", "/api/todos", token=tokens["linjl"]).get("data") or []
        t2 = next((t for t in todos if t["documentId"] == d2["id"]), None)
        check("负责人收到待办", t2 is not None)

        if t2:
            rej = call("POST", "/api/todos/approve",
                       {"taskId": t2["taskId"], "action": "reject",
                        "comment": "事由不充分，请补充说明"}, tokens["linjl"])
            check("驳回操作成功", rej.get("code") == 0, str(rej.get("msg"))[:60])

        det2 = call("GET", f"/api/documents/{d2['id']}", token=tokens["huangxm"]).get("data") or {}
        check("驳回后单据状态=已驳回", (det2.get("document") or {}).get("status") == 4,
              f"status={(det2.get('document') or {}).get('status')}")
        rej_rec = [n for n in (det2.get("flowHistory") or []) if n.get("action") == "reject"]
        check("驳回意见已落库",
              len(rej_rec) == 1 and "事由不充分" in (rej_rec[0].get("commentText") or ""),
              (rej_rec[0].get("commentText") if rej_rec else "无记录"))
        check("驳回后允许重新编辑提交", "resubmit" in (det2.get("availableActions") or []),
              f"actions={det2.get('availableActions')}")

        rs = call("POST", f"/api/documents/{d2['id']}/submit", None, tokens["huangxm"])
        check("驳回后可重新提交（生成新流程实例）", rs.get("code") == 0,
              f"状态={(rs.get('data') or {}).get('status')} {str(rs.get('msg') or '')[:60]}")

        wd = call("POST", f"/api/documents/{d2['id']}/withdraw", None, tokens["huangxm"])
        check("发起人可撤回", wd.get("code") == 0, str(wd.get("msg"))[:60])
        det3 = call("GET", f"/api/documents/{d2['id']}", token=tokens["huangxm"]).get("data") or {}
        check("撤回后单据状态=已撤回", (det3.get("document") or {}).get("status") == 5,
              f"status={(det3.get('document') or {}).get('status')}")

    section("10. 权限校验")
    other = call("GET", f"/api/documents/{doc_id}", token=tokens["linjl"])
    check("流程参与者可查看单据", other.get("code") == 0, f"code={other.get('code')}")

    bad_submit = call("POST", f"/api/documents/{doc_id}/submit", None, tokens["linjl"])
    check("非本人不能提交他人单据", bad_submit.get("code") != 0, f"msg={str(bad_submit.get('msg'))[:60]}")

    section("测试结果")
    print(f"  通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    if FAIL:
        print("  失败项：")
        for f in FAIL:
            print(f"    - {f}")
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
海峡金 OA — 冷启动造数脚本（**全程走 HTTP 接口，一行 SQL 都不写**）

【它是什么】
  把「只有一个 admin 的空系统」变成「三种单据可提交、审批链有人可审」的可用环境。
  内容等价于旧的 sql/seed_data.sql，但**不直写数据库** —— 这是它存在的全部理由。

【为什么必须走接口】
  · 直写库绕过了应用的全部校验（权限点引用、角色解析、流程版本、BPMN 生成、字段级权限）。
    绕过的后果不是"快一点"，而是**交付路径从来没被跑过** —— 测试全绿、交付就炸的根因。
  · 本脚本跑通的路径 = 客户拿到系统后要走的路径。**它同时就是交付演练脚本。**

【前置】
  1. 库已灌 init_database.sql（结构）+ minimal_seed.sql（引导集：company / admin / ADMIN / 权限点）
  2. 后端在跑（默认 http://127.0.0.1:8080）
  3. admin 的密码是种子里的初始值（默认 123456）

【用法】
  python3 bootstrap_via_api.py                      # 默认 127.0.0.1:8080
  python3 bootstrap_via_api.py --base-url http://127.0.0.1:8080
  python3 bootstrap_via_api.py --verify             # 造完再做一次「能不能提单」自检
  python3 bootstrap_via_api.py --dry-run            # 只打印将要做什么，不发请求

【幂等】
  每个实体创建前先按业务唯一键（code / name / account）查重，命中则复用其 id。
  重复执行不产生重复数据。**但注意：流程配置不是幂等的** —— 每次调用都会新建一个版本
  （版本号 +1，旧版本自动下线），这是服务端的既定语义（改流程 = 新建版本，在途单据不受影响）。
  所以脚本默认**发现该单据类型已有生效流程就跳过**，不重复建版本。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from urllib.parse import urlencode

PASSWORD = "123456"

# ---------------------------------------------------------------------------
# 造数清单 —— 逐项对应 sql/seed_data.sql，顺序即依赖顺序
# ---------------------------------------------------------------------------

# 岗位 (code, name, sort_no)
POSTS = [
    ("P_GM", "总经理", 10),
    ("P_FIN_DIR", "财务总监", 20),
    ("P_ACCOUNTANT", "核算会计", 30),
    ("P_CASHIER", "出纳", 40),
    ("P_INTERNAL", "内控专员", 50),
    ("P_DEPT_HEAD", "部门负责人", 60),
    ("P_STAFF", "普通员工", 70),
    ("P_ADMIN", "行政专员", 80),
]

# 部门 (code, name, parent_code, dept_type, leader_account, sort_no)
# dept_type: 1 中心 / 2 部门。parent_code=None 表示顶级。
DEPTS = [
    ("D001", "总经理室", None, 1, "zhangzong", 10),
    ("D002", "财务中心", None, 1, "lifinance", 20),
    ("D003", "财务核算部", "D002", 2, "lifinance", 21),
    ("D004", "资金结算部", "D002", 2, "zhaocs", 22),
    ("D005", "内控合规中心", None, 1, "chennk", 30),
    ("D006", "综合管理中心", None, 1, "zhouzh", 40),
    ("D007", "行政管理部", "D006", 2, "zhouzh", 41),
    ("D008", "业务一部", None, 1, "linjl", 50),
]

# 角色 (code, name, dept_code, post_name, perms, scope_type)
# dept_code 为 None 表示角色不绑部门（语义=全公司）；照 seed_data 保持一致。
ROLES = [
    ("GM", "公司领导", "D001", "总经理",
     ["document:menu", "todo:menu", "ledger:menu", "dashboard:menu",
      "document:view:company", "document:export",
      "document:approve", "document:approve:leader"], "company"),
    ("FIN_DIRECTOR", "财务总监", "D002", "财务总监",
     ["document:menu", "todo:menu", "ledger:menu", "dashboard:menu",
      "document:view:company", "document:export",
      "document:approve", "document:approve:leader", "document:approve:accountant"], "company"),
    ("ACCOUNTANT", "核算会计", "D003", "核算会计",
     ["document:menu", "todo:menu", "ledger:menu",
      "document:view:company", "document:export",
      "document:approve", "document:approve:accountant", "document:supplement"], "company"),
    ("CASHIER", "出纳", "D004", "出纳",
     ["document:menu", "todo:menu", "ledger:menu",
      "document:view:company", "document:approve", "document:approve:cashier"], "company"),
    ("INTERNAL_CTRL", "内控合规", "D005", "内控专员",
     ["document:menu", "todo:menu", "ledger:menu", "dashboard:menu",
      "document:view:company", "document:approve", "document:supplement"], "company"),
    ("DEPT_HEAD", "部门负责人", None, "部门负责人",
     ["document:menu", "todo:menu", "ledger:menu",
      "document:create", "document:view:self", "document:view:dept",
      "document:approve", "document:approve:leader"], "dept"),
    ("EMPLOYEE", "普通员工", None, "普通员工",
     ["document:menu", "todo:menu",
      "document:create", "document:view:self", "document:approve"], "self"),
]

# 用户 (account, job_no, real_name, dept_code, post_code, role_codes)
USERS = [
    ("zhangzong", "HXJ002", "张总", "D001", "P_GM", ["GM"]),
    ("lifinance", "HXJ003", "李财务", "D002", "P_FIN_DIR", ["FIN_DIRECTOR"]),
    ("wangkj", "HXJ004", "王会计", "D003", "P_ACCOUNTANT", ["ACCOUNTANT"]),
    ("zhaocs", "HXJ005", "赵出纳", "D004", "P_CASHIER", ["CASHIER"]),
    ("chennk", "HXJ006", "陈内控", "D005", "P_INTERNAL", ["INTERNAL_CTRL"]),
    ("linjl", "HXJ007", "林经理", "D008", "P_DEPT_HEAD", ["DEPT_HEAD"]),
    ("zhouzh", "HXJ008", "周综合", "D006", "P_DEPT_HEAD", ["DEPT_HEAD"]),
    ("huangxm", "HXJ009", "黄小明", "D008", "P_STAFF", ["EMPLOYEE"]),
]

# 字典 (dict_type, dict_code, dict_label, sort_no)  —— 全局字典，companyId 恒为 NULL
DICTS = [
    ("seal_type", "OFFICIAL", "公章", 10),
    ("seal_type", "CONTRACT", "合同章", 20),
    ("seal_type", "LEGAL", "法人章", 30),
    ("seal_type", "FINANCE", "财务专用章", 40),
    ("seal_type", "INVOICE", "发票专用章", 50),
    ("pay_type", "GOODS", "货款", 10),
    ("pay_type", "SERVICE", "服务费", 20),
    ("pay_type", "RENT", "租金", 30),
    ("pay_type", "OTHER", "其他", 90),
    ("expense_type", "TRAVEL", "差旅费", 10),
    ("expense_type", "OFFICE", "办公费", 20),
    ("expense_type", "ENTERTAIN", "业务招待费", 30),
    ("expense_type", "TRANSPORT", "交通费", 40),
    ("expense_type", "OTHER", "其他", 90),
    ("project", "HXJ-2026-01", "海峡金数字经济产业园一期", 10),
    ("project", "HXJ-2026-02", "供应链金融平台建设", 20),
    ("project", "HXJ-2026-03", "日常运营", 90),
]

# 单据类型 (code, name, category, sort_no)
DOC_TYPES = [
    ("DAILY_PAYMENT", "日常付款申请", "DAILY", 10),
    ("REIMBURSE_EMPLOYEE", "员工报销", "REIMBURSE", 20),
    ("SEAL_APPLY", "用印申请", "SEAL", 30),
]

# 表单模板 (doc_type_code, name, schema)
# schema 按接口契约传 **JSON 对象**（不是字符串），服务端落库时才序列化。
TEMPLATES = [
    ("DAILY_PAYMENT", "日常付款申请单", {
        "docType": "DAILY_PAYMENT", "layout": "two-column",
        "fields": [
            {"key": "title", "label": "申请事项", "type": "text", "required": True, "maxLength": 128, "colSpan": 2, "placeholder": "请填写本次付款的申请事项"},
            {"key": "amount", "label": "付款金额(元)", "type": "money", "required": True, "min": 0.01, "precision": 2, "colSpan": 1},
            {"key": "payType", "label": "付款类型", "type": "select", "required": True, "dictType": "pay_type", "colSpan": 1},
            {"key": "payeeName", "label": "收款单位", "type": "text", "required": True, "colSpan": 1},
            {"key": "payeeAccount", "label": "收款账号", "type": "text", "required": True, "rule": "bankAccount", "colSpan": 1},
            {"key": "payeeBank", "label": "开户行", "type": "text", "required": True, "colSpan": 1},
            {"key": "expectedDate", "label": "期望付款日期", "type": "date", "colSpan": 1},
            {"key": "project", "label": "对应项目", "type": "select", "dictType": "project", "required": False, "colSpan": 1},
            {"key": "reason", "label": "申请事由", "type": "textarea", "required": True, "maxLength": 1000, "colSpan": 2},
            {"key": "invoices", "label": "发票明细", "type": "invoiceGroup", "required": False, "colSpan": 2},
            {"key": "attachments", "label": "附件", "type": "attachment", "required": False, "maxCount": 10, "colSpan": 2},
        ],
        "rules": [{"when": "payType == 'OTHER'", "then": {"show": ["reason"]}}],
    }),
    ("REIMBURSE_EMPLOYEE", "员工报销单", {
        "docType": "REIMBURSE_EMPLOYEE", "layout": "two-column",
        "fields": [
            {"key": "title", "label": "报销事由", "type": "text", "required": True, "maxLength": 128, "colSpan": 2},
            {"key": "amount", "label": "报销金额(元)", "type": "money", "required": True, "min": 0.01, "precision": 2, "colSpan": 1},
            {"key": "expenseType", "label": "费用类型", "type": "select", "required": True, "dictType": "expense_type", "colSpan": 1},
            {"key": "occurDate", "label": "费用发生日期", "type": "date", "required": True, "colSpan": 1},
            {"key": "project", "label": "对应项目", "type": "select", "dictType": "project", "required": False, "colSpan": 1},
            {"key": "reason", "label": "补充说明", "type": "textarea", "required": False, "maxLength": 1000, "colSpan": 2},
            {"key": "invoices", "label": "发票明细", "type": "invoiceGroup", "required": True, "colSpan": 2},
            {"key": "attachments", "label": "附件(发票影像)", "type": "attachment", "required": True, "maxCount": 20, "colSpan": 2},
        ],
    }),
    ("SEAL_APPLY", "用印申请单", {
        "docType": "SEAL_APPLY", "layout": "two-column",
        "fields": [
            {"key": "sealProject", "label": "用印项目", "type": "text", "required": True, "maxLength": 128, "colSpan": 2},
            {"key": "fileName", "label": "用印文件名称", "type": "text", "required": True, "maxLength": 255, "colSpan": 2},
            {"key": "sealType", "label": "用章类型", "type": "select", "required": True, "dictType": "seal_type", "colSpan": 1},
            {"key": "copies", "label": "份数", "type": "number", "required": True, "min": 1, "max": 999, "colSpan": 1},
            {"key": "isCarryOut", "label": "是否外带", "type": "select", "required": True,
             "options": [{"value": "N", "label": "否"}, {"value": "Y", "label": "是"}], "colSpan": 1},
            {"key": "expectDate", "label": "期望用印日期", "type": "date", "required": False, "colSpan": 1},
            {"key": "reason", "label": "用印事由", "type": "textarea", "required": True, "maxLength": 1000, "colSpan": 2},
            {"key": "attachments", "label": "附件", "type": "attachment", "required": False, "maxCount": 10, "colSpan": 2},
        ],
        "rules": [{"when": "isCarryOut == 'Y'", "then": {"require": ["expectDate"]}}],
    }),
]

# 流程配置。
# ⚠ nodeKey 由服务端生成 = "n" + 顺序号，**条件网关也占一个号**：
#   日常付款 n1发起 n2部门负责人 n3会计 n4金额分支(网关) n5公司领导 n6出纳付款
#   员工报销 n1发起 n2部门负责人 n3会计 n4金额分支(网关) n5财务总监 n6出纳付款
#   用印申请 n1发起 n2部门负责人 n3综合管理部 n4用章类型分支(网关) n5公司领导 n6用印办理
#   —— 字段级权限里的 nodeKey 必须按这个来，否则静默失效（该节点变只读）。
# branches[].target 是「位置号」= nodeItems 下标 + 1。
FLOWS = [
    {
        "doc_type_code": "DAILY_PAYMENT",
        "name": "日常付款审批流程",
        "node_items": [
            {"nodeName": "发起申请", "nodeType": 5, "slaHours": 0, "allowCountersign": False},
            {"nodeName": "直属部门负责人", "nodeType": 1,
             "ruleType": "initiator_leader", "ruleValue": '{"fallbackRoleCode":"DEPT_HEAD"}',
             "slaHours": 24, "allowCountersign": True},
            {"nodeName": "会计（按部门）", "nodeType": 1,
             "ruleType": "dept_role", "ruleValue": '{"roleCode":"ACCOUNTANT","fallback":"company"}',
             "slaHours": 24, "allowCountersign": True},
            {"nodeName": "金额分支", "nodeType": 3, "branches": [
                {"expr": "doc.amount >= 20000", "target": 5},
                {"defaultBranch": True, "target": 6},
            ]},
            {"nodeName": "公司领导（大额）", "nodeType": 1,
             "ruleType": "role", "ruleValue": '{"roleCode":"GM"}',
             "slaHours": 48, "allowCountersign": True},
            {"nodeName": "出纳付款", "nodeType": 4,
             "ruleType": "role", "ruleValue": '{"roleCode":"CASHIER"}',
             "slaHours": 24, "allowCountersign": False},
        ],
        # 出纳节点补充回单信息（nodeKey = n6）
        "field_perms": [
            {"nodeKey": "n6", "fieldKey": "payeeAccount", "visible": True, "editable": True},
            {"nodeKey": "n6", "fieldKey": "payeeBank", "visible": True, "editable": True},
        ],
    },
    {
        "doc_type_code": "REIMBURSE_EMPLOYEE",
        "name": "员工报销审批流程",
        "node_items": [
            {"nodeName": "发起申请", "nodeType": 5, "slaHours": 0, "allowCountersign": False},
            {"nodeName": "直属部门负责人", "nodeType": 1,
             "ruleType": "initiator_leader", "ruleValue": '{"fallbackRoleCode":"DEPT_HEAD"}',
             "slaHours": 24, "allowCountersign": True},
            {"nodeName": "会计（按部门）", "nodeType": 1,
             "ruleType": "dept_role", "ruleValue": '{"roleCode":"ACCOUNTANT","fallback":"company"}',
             "slaHours": 24, "allowCountersign": True},
            {"nodeName": "金额分支", "nodeType": 3, "branches": [
                {"expr": "doc.amount >= 10000", "target": 5},
                {"defaultBranch": True, "target": 6},
            ]},
            {"nodeName": "财务总监", "nodeType": 1,
             "ruleType": "role", "ruleValue": '{"roleCode":"FIN_DIRECTOR"}',
             "slaHours": 48, "allowCountersign": True},
            {"nodeName": "出纳付款", "nodeType": 4,
             "ruleType": "role", "ruleValue": '{"roleCode":"CASHIER"}',
             "slaHours": 24, "allowCountersign": False},
        ],
        "field_perms": [],
    },
    {
        "doc_type_code": "SEAL_APPLY",
        "name": "用印申请审批流程",
        "node_items": [
            {"nodeName": "发起申请", "nodeType": 5, "slaHours": 0, "allowCountersign": False},
            {"nodeName": "直属部门负责人", "nodeType": 1,
             "ruleType": "initiator_leader", "ruleValue": '{"fallbackRoleCode":"DEPT_HEAD"}',
             "slaHours": 24, "allowCountersign": True},
            # 综合管理部核验：deptId 必须是「周综合」所在的部门，否则该节点解析不出人、流程卡死
            {"nodeName": "综合管理部", "nodeType": 1,
             "ruleType": "dept_role", "ruleValue": '{"roleCode":"DEPT_HEAD","deptId":@DEPT_D006@}',
             "slaHours": 24, "allowCountersign": False},
            {"nodeName": "用章类型分支", "nodeType": 3, "branches": [
                {"expr": "doc.sealType == 'OFFICIAL' || doc.sealType == 'CONTRACT'", "target": 5},
                {"defaultBranch": True, "target": 6},
            ]},
            {"nodeName": "公司领导", "nodeType": 1,
             "ruleType": "role", "ruleValue": '{"roleCode":"GM"}',
             "slaHours": 48, "allowCountersign": True},
            {"nodeName": "用印办理", "nodeType": 4,
             "ruleType": "user", "ruleValue": '{"userIds":[@USER_zhouzh@]}',
             "slaHours": 24, "allowCountersign": False},
        ],
        # 综合管理部核验用章类型与份数（nodeKey = n3）
        "field_perms": [
            {"nodeKey": "n3", "fieldKey": "sealType", "visible": True, "editable": True},
            {"nodeKey": "n3", "fieldKey": "copies", "visible": True, "editable": True},
        ],
    },
]


# ---------------------------------------------------------------------------
# HTTP 客户端
# ---------------------------------------------------------------------------

class ApiError(RuntimeError):
    pass


class Client:
    def __init__(self, base_url, token=None):
        self.base = base_url.rstrip("/")
        # 本机探测必须绕开 HTTP_PROXY（有代理时死服务返回 502 而不是拒绝连接）
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.token = token

    def call(self, method, path, body=None, query=None):
        url = self.base + path
        if query:
            clean = {k: v for k, v in query.items() if v is not None}
            if clean:
                url += "?" + urlencode(clean)
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=30) as resp:
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            raise ApiError("%s %s -> HTTP %s: %s" % (method, path, e.code, raw[:400]))
        except urllib.error.URLError as e:
            raise ApiError("%s %s -> 连接失败: %s（后端没起来？）" % (method, path, e.reason))
        if not raw:
            return None
        try:
            payload = json.loads(raw)
        except ValueError:
            raise ApiError("%s %s -> 响应不是 JSON: %s" % (method, path, raw[:200]))
        if isinstance(payload, dict) and "code" in payload:
            if payload["code"] != 0:
                raise ApiError("%s %s -> code=%s msg=%s"
                               % (method, path, payload["code"], payload.get("msg")))
            return payload.get("data")
        return payload

    def login(self, account, password):
        data = self.call("POST", "/api/auth/login",
                         {"account": account, "password": password})
        self.token = data["token"]
        return data


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

def pick(lst, key, value):
    for item in (lst or []):
        if item.get(key) == value:
            return item
    return None


class Plan:
    """收集"将要做的事"，dry-run 时只打印。"""

    def __init__(self, dry_run):
        self.dry_run = dry_run
        self.created = 0
        self.reused = 0

    def log(self, action, detail):
        mark = "·" if self.reused else "+"
        print("  %s %s %s" % (mark, action.ljust(10), detail))


# ---------------------------------------------------------------------------
# 步骤
# ---------------------------------------------------------------------------

def step_posts(c, plan):
    print("[1/9] 岗位")
    existing = c.call("GET", "/api/posts") or []
    id_by_code = {}
    for code, name, sort_no in POSTS:
        hit = pick(existing, "code", code)
        if hit:
            plan.reused += 1
            plan.log("复用岗位", "%s %s (id=%s)" % (code, name, hit["id"]))
            id_by_code[code] = hit["id"]
            continue
        if plan.dry_run:
            plan.log("新建岗位", "%s %s" % (code, name))
            continue
        created = c.call("POST", "/api/posts", {"code": code, "name": name, "sortNo": sort_no})
        plan.created += 1
        plan.log("新建岗位", "%s %s (id=%s)" % (code, name, created["id"]))
        id_by_code[code] = created["id"]
    return id_by_code


def step_depts(c, plan):
    """部门分两轮：先建（不设负责人，负责人用户还不存在），拿到 id 后再回填。"""
    print("[2/9] 部门")
    existing = c.call("GET", "/api/depts") or []
    id_by_code = {d["code"]: d["id"] for d in existing if d.get("code")}
    created_now = []

    for code, name, parent_code, dept_type, leader_acct, sort_no in DEPTS:
        if code in id_by_code:
            plan.reused += 1
            plan.log("复用部门", "%s %s (id=%s)" % (code, name, id_by_code[code]))
            continue
        if plan.dry_run:
            plan.log("新建部门", "%s %s parent=%s" % (code, name, parent_code))
            continue
        # ⚠ DeptSaveReq 里**没有 deptType 字段**（传了会被 Jackson 静默忽略）⇒ 部门类型
        #   一律走库默认值 2（二级部门），接口层造不出一级中心 —— 已知能力边界，记在案。
        body = {"code": code, "name": name, "sortNo": sort_no, "status": 1}
        if parent_code:
            body["parentId"] = id_by_code[parent_code]
        created = c.call("POST", "/api/depts", body)
        plan.created += 1
        plan.log("新建部门", "%s %s (id=%s)" % (code, name, created["id"]))
        id_by_code[code] = created["id"]
        created_now.append(code)
    return id_by_code, created_now


def step_roles(c, plan, dept_id_by_code):
    print("[3/9] 角色（含权限点与数据范围）")
    existing = c.call("GET", "/api/roles") or []
    id_by_code = {}
    for code, name, dept_code, post_name, perms, scope in ROLES:
        hit = pick(existing, "code", code)
        if hit:
            plan.reused += 1
            plan.log("复用角色", "%s %s (id=%s)" % (code, name, hit["id"]))
            id_by_code[code] = hit["id"]
            continue
        if plan.dry_run:
            plan.log("新建角色", "%s %s 权限点=%d 范围=%s" % (code, name, len(perms), scope))
            continue
        body = {"code": code, "name": name, "postName": post_name,
                "permCodes": perms, "scopeType": scope}
        if dept_code:
            body["deptId"] = dept_id_by_code.get(dept_code)
        created = c.call("POST", "/api/roles", body)
        plan.created += 1
        plan.log("新建角色", "%s %s 权限点=%d 范围=%s (id=%s)"
                 % (code, name, len(perms), scope, created["id"]))
        id_by_code[code] = created["id"]
    return id_by_code


def step_users(c, plan, dept_id_by_code, post_id_by_code):
    print("[4/9] 用户（含部门 / 岗位 / 角色）")
    existing = c.call("GET", "/api/users") or []
    id_by_account = {}
    for account, job_no, real_name, dept_code, post_code, role_codes in USERS:
        hit = pick(existing, "account", account)
        if hit:
            plan.reused += 1
            plan.log("复用用户", "%s %s (id=%s)" % (account, real_name, hit["id"]))
            id_by_account[account] = hit["id"]
            continue
        if plan.dry_run:
            plan.log("新建用户", "%s %s dept=%s post=%s 角色=%s"
                     % (account, real_name, dept_code, post_code, ",".join(role_codes)))
            continue
        body = {"account": account, "jobNo": job_no, "realName": real_name,
                "password": PASSWORD, "status": 1,
                "deptId": dept_id_by_code.get(dept_code),
                "postId": post_id_by_code.get(post_code),
                "roleCodes": role_codes}
        created = c.call("POST", "/api/users", body)
        plan.created += 1
        plan.log("新建用户", "%s %s dept=%s 角色=%s (id=%s)"
                 % (account, real_name, dept_code, ",".join(role_codes), created["id"]))
        id_by_account[account] = created["id"]
    return id_by_account


def step_dept_leaders(c, plan, dept_id_by_code, user_id_by_account):
    """回填部门负责人 —— 必须在用户建好之后。

    ⚠ 只在**值确实不同**时才发 PUT：否则在已有数据的库上跑一次，会把 8 个部门的
      updated_at / updated_by 全部无谓改写（对业务值无害，但污染审计痕迹）。
    """
    print("[5/9] 部门负责人回填")
    current = {d["id"]: d.get("leaderId") for d in (c.call("GET", "/api/depts") or [])}
    for code, name, parent_code, dept_type, leader_acct, sort_no in DEPTS:
        if not leader_acct:
            continue
        leader_id = user_id_by_account.get(leader_acct)
        if not leader_id:
            plan.log("跳过", "%s 负责人 %s 不存在" % (code, leader_acct))
            continue
        dept_id = dept_id_by_code[code]
        if current.get(dept_id) == leader_id:
            plan.reused += 1
            plan.log("负责人就位", "%s %s -> %s" % (code, name, leader_acct))
            continue
        if plan.dry_run:
            plan.log("设置负责人", "%s %s -> %s" % (code, name, leader_acct))
            continue
        # 注意：编辑部门时 parentId 不可改（传了且不同会报错）⇒ 只传 name 与 leaderId
        c.call("PUT", "/api/depts/%s" % dept_id,
               {"name": name, "leaderId": leader_id, "sortNo": sort_no, "status": 1})
        plan.created += 1
        plan.log("设置负责人", "%s %s -> %s" % (code, name, leader_acct))


def step_dicts(c, plan):
    print("[6/9] 数据字典")
    grouped = c.call("GET", "/api/dicts") or {}
    for dict_type, dict_code, dict_label, sort_no in DICTS:
        hit = pick(grouped.get(dict_type), "dictCode", dict_code)
        if hit:
            plan.reused += 1
            continue
        if plan.dry_run:
            plan.log("新建字典", "%s.%s %s" % (dict_type, dict_code, dict_label))
            continue
        c.call("POST", "/api/dicts", {"dictType": dict_type, "dictCode": dict_code,
                                      "dictLabel": dict_label, "sortNo": sort_no, "status": 1})
        plan.created += 1
        plan.log("新建字典", "%s.%s %s" % (dict_type, dict_code, dict_label))


def step_doc_types(c, plan):
    print("[7/9] 单据类型")
    existing = c.call("GET", "/api/document-types") or []
    id_by_code = {}
    for code, name, category, sort_no in DOC_TYPES:
        hit = pick(existing, "code", code)
        if hit:
            plan.reused += 1
            plan.log("复用单据类型", "%s %s (id=%s)" % (code, name, hit["id"]))
            id_by_code[code] = hit["id"]
            continue
        if plan.dry_run:
            plan.log("新建单据类型", "%s %s" % (code, name))
            continue
        created = c.call("POST", "/api/document-types",
                         {"code": code, "name": name, "category": category,
                          "mustLinkPrev": 0, "status": 1, "sortNo": sort_no})
        plan.created += 1
        plan.log("新建单据类型", "%s %s (id=%s)" % (code, name, created["id"]))
        id_by_code[code] = created["id"]
    return id_by_code


def step_templates(c, plan, doc_type_id_by_code):
    print("[8/9] 表单模板（新建后必须 activate，否则建单报「未配置生效的表单模板」）")
    for doc_type_code, name, schema in TEMPLATES:
        doc_type_id = doc_type_id_by_code[doc_type_code]
        versions = c.call("GET", "/api/forms/templates/versions",
                          query={"docTypeId": doc_type_id}) or []
        active = [v for v in versions if v.get("status") == 1]
        if active:
            plan.reused += 1
            plan.log("复用模板", "%s 已有生效版本 v%s (id=%s)"
                     % (name, active[0].get("version"), active[0].get("id")))
            continue
        if plan.dry_run:
            plan.log("新建模板", "%s（%d 个字段）+ 启用" % (name, len(schema["fields"])))
            continue
        created = c.call("POST", "/api/forms/templates",
                         {"docTypeId": doc_type_id, "name": name, "schema": schema})
        activated = c.call("POST", "/api/forms/templates/%s/activate" % created["id"])
        plan.created += 1
        plan.log("新建模板", "%s v%s (id=%s) 已启用"
                 % (name, activated.get("version"), created["id"]))


def step_flows(c, plan, doc_type_id_by_code, dept_id_by_code, user_id_by_account):
    print("[9/9] 流程配置（建流程 = 写节点 + 写指派规则 + 自动部署 + 回写单据类型）")
    for flow in FLOWS:
        doc_type_code = flow["doc_type_code"]
        doc_type_id = doc_type_id_by_code[doc_type_code]

        existing = c.call("GET", "/api/flows/configs", query={"docTypeId": doc_type_id}) or []
        if any(x.get("deployStatus") == 1 for x in existing):
            plan.reused += 1
            plan.log("复用流程", "%s 已部署" % flow["name"])
            continue

        # 把清单里的占位符换成运行时拿到的真实 id（走接口生成的 id 是自增的，不能写死）
        node_items = []
        for item in flow["node_items"]:
            item = dict(item)
            rv = item.get("ruleValue")
            if isinstance(rv, str) and "@" in rv:
                rv = rv.replace("@DEPT_D006@", str(dept_id_by_code["D006"]))
                rv = rv.replace("@USER_zhouzh@", str(user_id_by_account["zhouzh"]))
                item["ruleValue"] = rv
            node_items.append(item)

        if plan.dry_run:
            plan.log("新建流程", "%s（%d 个节点，含网关分支）"
                     % (flow["name"], len(node_items)))
            continue
        created = c.call("POST", "/api/flows/configs",
                         {"name": flow["name"], "docTypeId": doc_type_id,
                          "nodeItems": node_items})
        plan.created += 1
        plan.log("新建流程", "%s (id=%s, deployStatus=%s)"
                 % (flow["name"], created["id"], created.get("deployStatus")))

        # 字段级权限：nodeKey 必须与上面第 3 段注释算出来的值一致
        if flow["field_perms"]:
            tpl_versions = c.call("GET", "/api/forms/templates/versions",
                                  query={"docTypeId": doc_type_id}) or []
            active_tpl = next((v for v in tpl_versions if v.get("status") == 1), None)
            if active_tpl:
                c.call("PUT", "/api/forms/templates/%s/field-permissions" % active_tpl["id"],
                       flow["field_perms"])
                plan.log("字段级权限", "%s -> %s"
                         % (",".join(p["nodeKey"] + ":" + p["fieldKey"] for p in flow["field_perms"]),
                            active_tpl["name"]))


def step_verify(c):
    """自检：三种单据类型都必须「有生效模板 + 已部署流程」—— 这两条缺一就提不了单。"""
    print("\n自检：能不能提单（模板生效 + 流程已部署）")
    ok = True
    doc_types = c.call("GET", "/api/document-types") or []
    for code, name, category, sort_no in DOC_TYPES:
        dt = pick(doc_types, "code", code)
        if not dt:
            print("  ✗ %s 单据类型不存在" % name)
            ok = False
            continue
        schema = c.call("GET", "/api/forms/schema", query={"docTypeId": dt["id"], "nodeKey": "n1"})
        fields = (schema or {}).get("fields") or []
        configs = c.call("GET", "/api/flows/configs", query={"docTypeId": dt["id"]}) or []
        deployed = [x for x in configs if x.get("deployStatus") == 1]
        if fields and deployed:
            detail = c.call("GET", "/api/flows/configs/%s" % deployed[0]["id"]) or {}
            node_count = len(detail.get("nodes") or [])
            print("  ✓ %s：模板生效（%d 字段）+ 流程已部署（v%s, %d 个节点）"
                  % (name, len(fields), deployed[0].get("version"), node_count))
        else:
            ok = False
            why = []
            if not fields:
                why.append("无生效模板")
            if not deployed:
                why.append("流程未部署")
            print("  ✗ %s：%s" % (name, " / ".join(why)))
    return ok


def step_smoke(base_url, doc_type_id_by_code):
    """端到端冒烟：新员工建单 → 提交 → 检查待办是否落到部门负责人。

    这几步是「造完数」与「环境真能用」的分界线 —— 前面 9 步只证明数据写进去了，
    提单链路（表单生效 → 校验 → 流程解析审批人 → 待办生成）才是客户真正要走的路。

    ⚠ 会写入**真实单据数据**，只在空库 / 临时库上跑。金额取 25000（≥20000）⇒ 顺带
      走到金额分支的「大额」路径，把网关也覆盖上。
    """
    print("\n[冒烟] 新员工走一遍「建单 → 提交 → 部门负责人收到待办」")
    me = Client(base_url)
    me.login("huangxm", PASSWORD)
    doc = me.call("POST", "/api/documents", {
        "docTypeId": doc_type_id_by_code["DAILY_PAYMENT"],
        "formData": {
            "title": "冷启动冒烟：采购设备付款",
            "amount": 25000,
            "payType": "GOODS",
            "payeeName": "冒烟供应商",
            "payeeAccount": "6222021234567890123",
            "payeeBank": "中国工商银行福州分行",
            "reason": "由 bootstrap_via_api.py --smoke 生成，用于验证冷启动链路可用",
        },
    })
    print("  + 建草稿 id=%s docNo=%s status=%s"
          % (doc.get("id"), doc.get("docNo"), doc.get("status")))

    sub = me.call("POST", "/api/documents/%s/submit" % doc["id"])
    print("  + 已提交 status=%s（1 待审 / 2 审批中）" % sub.get("status"))

    leader = Client(base_url)
    leader.login("linjl", PASSWORD)
    todos = leader.call("GET", "/api/todos") or []
    rows = todos.get("records") if isinstance(todos, dict) else todos
    mine = [t for t in (rows or [])
            if t.get("documentId") == doc.get("id")
            or (doc.get("docNo") and t.get("docNo") == doc.get("docNo"))]
    if mine:
        t = mine[0]
        print("  ✓ 待办已落到部门负责人（林经理）：节点「%s」docNo=%s"
              % (t.get("nodeName") or t.get("node_name"), t.get("docNo")))
        print("  ✓ 冷启动链路打通：空库 → 造数 → 提单 → 待办生成")
        return True
    print("  ✗ 部门负责人待办里没有这张单 —— 指派规则没解析出人（把待办总数 %d 报出来核对）"
          % len(rows or []))
    return False


# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="海峡金 OA 冷启动造数（全程走接口）")
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--account", default="admin")
    ap.add_argument("--password", default=PASSWORD)
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不改动任何数据")
    ap.add_argument("--verify", action="store_true", help="跑完后再做一次可提单性自检")
    ap.add_argument("--smoke", action="store_true",
                    help="再走一遍真实提单（会写入单据数据，仅限空库/临时库）")
    args = ap.parse_args()

    c = Client(args.base_url)
    print("目标：%s" % args.base_url)
    try:
        c.login(args.account, args.password)
    except ApiError as e:
        print("登录失败：%s" % e, file=sys.stderr)
        return 2
    print("已登录：%s\n" % args.account)

    plan = Plan(args.dry_run)
    doc_type_ids = {}
    try:
        post_ids = step_posts(c, plan)
        dept_ids, _ = step_depts(c, plan)
        role_ids = step_roles(c, plan, dept_ids)
        user_ids = step_users(c, plan, dept_ids, post_ids)
        step_dept_leaders(c, plan, dept_ids, user_ids)
        step_dicts(c, plan)
        doc_type_ids = step_doc_types(c, plan)
        step_templates(c, plan, doc_type_ids)
        step_flows(c, plan, doc_type_ids, dept_ids, user_ids)
    except ApiError as e:
        print("\n失败：%s" % e, file=sys.stderr)
        return 1

    print("\n完成：新建 %d 项，复用 %d 项" % (plan.created, plan.reused))
    if not plan.dry_run:
        print("（角色 %d / 部门 %d / 用户 %d）"
              % (len(role_ids), len(dept_ids), len(user_ids)))
        print("所有业务账号初始密码：%s" % PASSWORD)

    if args.verify and not args.dry_run:
        ok_verify = step_verify(c)
    else:
        ok_verify = True

    if args.smoke and not args.dry_run:
        ok_smoke = step_smoke(args.base_url, doc_type_ids)
    else:
        ok_smoke = True

    return 0 if (ok_verify and ok_smoke) else 1


if __name__ == "__main__":
    sys.exit(main())

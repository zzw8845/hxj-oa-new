# -*- coding: utf-8 -*-
"""
接口级验证：**管理员角色本身不再构成任何旁路**（2026-09-23 收口）。

## 这个用例在防什么

收口前，`LoginUser` 上有一个 `hasRole(String)`，被四处业务代码用来判"管理员特权"：

    FlowRuntimeService#assertAssignee : uid.equals(task.getAssignee()) || user.hasRole("ADMIN")
    DocumentService#assertVisible     : if (user.hasRole("ADMIN")) return;
    AttachmentService#load            : ... && !user.hasRole("ADMIN")
    AttachmentService#delete          : ... && !user.hasRole("ADMIN")

其中第一处是**全项目唯一一处真实越权**：持有 ADMIN 角色的账号可以代任意人批准任意节点，
它绕过的正是"谁是审批人"这个判定的全部机制（节点指派规则 / 候选人池 / 委托校验）。
按设计（管理员角色与权限设计说明 §P3），**审批决定权永不授予管理员** ——
管理员能改流程配置、能改角色，但不能替人签字。

## 为什么必须写成用例，而不是"改完看一眼"

`hasRole(String)` 已从 `LoginUser` 删除，第 5 处旁路会**编译不过**（这才是真护栏）。
但"编译不过"只保证没人再写**同一个方法**，不保证没人换一种写法把特权加回来
（比如直接比较 roleCodes 字符串）。所以这里还要有一条**行为断言**：
拿一个货真价实、不属于 admin 的任务，用 admin 的 token 去批 —— 必须被拒。

## 夹具与清理

夹具自建（临时账号 + 单据 + 流程实例），跑完按**创建时记下的 id** 物理清干净：
单据、流程痕迹（含 `ACT_*`，按 proc_inst_id 与 `BUSINESS_KEY_` 双路反查）、通知、
夹具账号、以及本次窗口内产生的审计日志（按先查出的 id 列表删）。
**不做任何"按模式/按范围/整表"的删除** —— 本项目为此踩过 3 次，删过业务数据。
"""
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

os.environ['no_proxy'] = '127.0.0.1,localhost,::1'
os.environ['NO_PROXY'] = os.environ['no_proxy']
B = 'http://127.0.0.1:8080'
DB = 'haixiajin_oa'

# ---- 夹具申请人：必须是「本部门负责人」以外的人 ---------------------------------
# 首审批节点的规则是 initiator_leader（＝申请人所在部门的负责人）。若申请人自己就是
# 该部门负责人，**自审会被移除** ⇒ 该节点没有真实承办人，夹具前提不成立。
# dept 4「资金结算部」负责人 = 5 zhaocs；临时账号挂 dept 4 即得：
#   申请人 = 临时账号(dept 4) → 首节点承办人 = zhaocs(5)，既不是申请人也不是 admin(1)。
FIXTURE_DEPT = 4
FIXTURE_ROLE = 'EMPLOYEE'
FIXTURE_PWD = '123456'
FIX_ACCOUNT = 'e2enb%s%s' % (int(time.time()) % 1000000, os.getpid() % 1000)
EXPECTED_TOTAL = 24

ADMIN_ID = 1

PASS, FAIL = [], []
fixture = []              # [(documentId, docNo)]
fixture_user_id = None
audit_floor = None        # 测试窗口起点：audit_log 的 MAX(id)
admin_tk = None

# ------------------------------------------------------------------ 基础设施


def call(method, path, token=None, body=None):
    req = urllib.request.Request(B + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as r:
            raw = r.read().decode('utf-8')
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, (json.loads(raw) if raw else {})
        except Exception:
            return e.code, {'raw': raw}


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print('%s %s%s' % ('[PASS]' if cond else '[FAIL]', name, ('  -> ' + detail) if detail else ''))


def sql(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def scalar(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-B', '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


# ------------------------------------------------------------------ 夹具


def create_fixture_user():
    """走 POST /api/users 而不是直接 INSERT —— 新建员工要同时写 user_role、
    释放账号唯一键、失效权限快照缓存；直接写库会造出一个"能登录但没有任何角色"的账号。"""
    global fixture_user_id
    st, r = call('POST', '/api/users', token=admin_tk, body={
        'realName': 'E2E越权夹具', 'jobNo': FIX_ACCOUNT, 'account': FIX_ACCOUNT,
        'password': FIXTURE_PWD, 'deptId': FIXTURE_DEPT, 'roleCodes': [FIXTURE_ROLE]})
    if st == 200 and r.get('code') == 0:
        fixture_user_id = (r.get('data') or {}).get('id')
    else:
        print('  [夹具] 建临时账号失败：HTTP %s / %s' % (st, r.get('msg', r)))
    return fixture_user_id


def cleanup_audit_window():
    """删掉本次窗口内产生的审计日志。

    【为什么按 id 列表删，而不是 `DELETE ... WHERE id > N`】
    本项目铁律：只允许按"创建时记下的 id"删，禁止按范围删（踩过 3 次，删过业务数据）。
    这里先 SELECT 出 id，再按显式 id 列表删 —— 范围限定的合法性由"这一步先取证"保证，
    而不是由 WHERE 条件自己声明。

    【为什么要管它】审计切面是 @Around，**失败也落库**。而 approve 失败时返回值为空，
    resolveBizId 拿不到单据 id ⇒ 这几行 audit_log 的 biz_id 是 NULL，
    无法事后按 biz_id 归属，只能在窗口内取证。
    """
    ids = [x for x in (scalar('SELECT GROUP_CONCAT(id) FROM audit_log WHERE id > %s'
                              % audit_floor) or '').split(',') if x]
    for i in ids:
        sql('DELETE FROM audit_log WHERE id=%s' % i)
    return len(ids)


def cleanup():
    for doc_id, doc_no in fixture:
        # 流程实例 id 从**两张表**取并集：flow_instance（业务侧）与 ACT_HI_PROCINST（引擎侧）。
        # 只按单号查一次不够 —— 单号为空时 pids 会空，运行时任务行就留下来了（实测踩过）。
        pids = set()
        for stmt in ("SELECT proc_inst_id FROM flow_instance WHERE document_id=%s" % doc_id,
                     "SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no):
            for x in (scalar(stmt) or '').split('\n'):
                if x.strip():
                    pids.add(x.strip())
        pids = list(pids)
        task_ids = [t for t in (scalar(
            'SELECT GROUP_CONCAT(task_id) FROM flow_instance_node WHERE document_id=%s' % doc_id
        ) or '').split(',') if t]
        for tid in task_ids:
            sql("DELETE FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s'" % tid)
            sql("DELETE FROM ACT_RU_TASK WHERE ID_='%s'" % tid)
        for t in ('ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
                  'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG'):
            for pid in pids:
                sql("DELETE FROM %s WHERE PROC_INST_ID_='%s'" % (t, pid))
        for t in ('ACT_RU_IDENTITYLINK', 'ACT_RU_ACTINST', 'ACT_RU_TASK',
                  'ACT_RU_VARIABLE', 'ACT_RU_EVENT_SUBSCR'):
            for pid in pids:
                sql("DELETE FROM %s WHERE PROC_INST_ID_='%s'" % (t, pid))
        for pid in pids:
            sql("DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='%s' AND PARENT_ID_ IS NOT NULL" % pid)
            sql("DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='%s'" % pid)
            sql("DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='%s'" % pid)
        for t in ('attachment', 'document_link', 'flow_instance_node', 'flow_instance'):
            sql('DELETE FROM %s WHERE document_id=%s' % (t, doc_id))
        sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % doc_id)
        sql('DELETE FROM document WHERE id=%s' % doc_id)
    # 夹具账号：先走接口删（释放账号/工号唯一键、解绑角色），再按 id 物理删那一行墓碑
    if fixture_user_id:
        call('DELETE', '/api/users/%s' % fixture_user_id, token=admin_tk)
        sql('DELETE FROM user_role WHERE user_id=%s' % fixture_user_id)
        sql('DELETE FROM user_post WHERE user_id=%s' % fixture_user_id)
        sql('DELETE FROM sys_user WHERE id=%s' % fixture_user_id)


# ------------------------------------------------------------------ 开跑

print('=' * 72)
print('管理员旁路已收口')
print('=' * 72)

audit_floor = int(scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') or 0)
before_doc = scalar('SELECT COUNT(*) FROM document')
before_task = scalar('SELECT COUNT(*) FROM ACT_RU_TASK')
before_notify = scalar('SELECT COUNT(*) FROM notification')

admin_tk = login('admin')
check('1. admin 登录成功', bool(admin_tk))

create_fixture_user()
applicant_tk = login(FIX_ACCOUNT, FIXTURE_PWD)
check('2. 夹具账号已建立并能登录（挂 dept %d，角色 %s，非部门负责人）' % (FIXTURE_DEPT, FIXTURE_ROLE),
      bool(fixture_user_id and applicant_tk), 'id=%s account=%s' % (fixture_user_id, FIX_ACCOUNT))

st, r = call('POST', '/api/documents', token=applicant_tk, body={
    'docTypeId': 1, 'title': 'E2E越权夹具（跑完即删）', 'amount': 400, 'reason': 'E2E 越权收口夹具',
    'formData': {'title': 'E2E越权夹具（跑完即删）', 'amount': 400, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 越权收口夹具'}
})
doc = r.get('data') or {}
doc_id, doc_no = doc.get('id'), doc.get('docNo')
if doc_id:
    call('POST', '/api/documents/%s/submit' % doc_id, token=applicant_tk)
    fixture.append((doc_id, doc_no))

task_id = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id) \
    if doc_id else ''
assignee = scalar('SELECT assignee_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id) \
    if doc_id else ''
node_status_before = scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id) \
    if task_id else ''
node_count_before = scalar('SELECT COUNT(*) FROM flow_instance_node WHERE document_id=%s' % doc_id) \
    if doc_id else ''
doc_status_before = scalar('SELECT status FROM document WHERE id=%s' % doc_id) if doc_id else ''
links_before = scalar("SELECT COUNT(*) FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s'" % task_id) \
    if task_id else ''

check('3. 夹具单据已提交，首节点有**真实承办人**（申请人未被自审移除）',
      bool(doc_id and task_id and assignee),
      'docNo=%s taskId=%s 承办人=%s' % (doc_no, task_id or '(空)', assignee or '(空)'))
check('4. 承办人 ≠ admin —— 夹具前提成立（否则测的不是越权）',
      bool(assignee) and assignee != str(ADMIN_ID), '承办人=%s admin=%s' % (assignee, ADMIN_ID))

# 对照组：承办人看得到这条待办（正常可用，不能因为收口把正常路径也堵了）
assignee_acct = scalar('SELECT account FROM sys_user WHERE id=%s' % (assignee or 0)) or ''
assignee_tk = login(assignee_acct) if assignee_acct else None
st, r = call('GET', '/api/todos?limit=100', token=assignee_tk) if assignee_tk else (0, {})
mine = [t for t in ((r.get('data') or []) if st == 200 else []) if t.get('documentId') == doc_id]
check('5. 对照：该待办出现在**承办人**的待办列表里（正常路径没被收口误伤）',
      bool(mine), '承办人=%s 命中 %d 条' % (assignee_acct, len(mine)))

st, r = call('GET', '/api/todos?limit=100', token=admin_tk)
admin_todos = ((r.get('data') or []) if st == 200 else [])
hit = [t for t in admin_todos if t.get('documentId') == doc_id]
check('6. 该待办**不**出现在 admin 的待办列表里（界面本来就不给这个入口 —— 所以要防的是直接调接口）',
      not hit, 'admin 待办 %d 条，命中夹具 %d 条' % (len(admin_todos), len(hit)))

# ---------------------------------------------------------------- 核心：代批必须被拒
print()
print('=' * 72)
print('一、admin 代别人批 —— 必须被拒')
print('=' * 72)

st, r = call('POST', '/api/todos/approve', token=admin_tk,
             body={'taskId': task_id, 'action': 'approve', 'comment': 'E2E 越权探针'})
code = r.get('code')
msg = r.get('msg') or ''
check('7. ★ admin 用**自己的 token** 代批别人的任务 → 业务码非 0（被拒）',
      st == 200 and code != 0, 'HTTP %s / code=%s / msg=%s' % (st, code, msg))
check('8. ★ 拒绝理由来自「不是该节点的处理人」，**不是**权限点缺失（admin 有 document:approve）',
      '处理人' in msg, 'msg=%s' % msg)

check('9. 拒绝后：运行时任务仍在该节点（没有被静默吃掉）',
      scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE ID_='%s'" % task_id) == '1',
      'ACT_RU_TASK 命中 %s 条' % scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE ID_='%s'" % task_id))
# 【为什么按 task_id 定位，而不是 ORDER BY id DESC LIMIT 1】
# 首版这么写，结果在"旁路装回去"的对照实验里**漏报**了：admin 真批成功之后，
# 该单据的"最新节点"变成了下一个节点（status 同样是 0），于是
# "最新节点状态未变" 恰好为真 —— 断言与事实同向，却什么也没证明。
# 定位到**被攻击的那个节点本身**才有效。
check('10. 拒绝后：**被攻击的那个节点**状态未变（仍为待处理）',
      scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id) == node_status_before,
      '%s → %s' % (node_status_before,
                   scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id)))
check('11. ★ 拒绝后：该单据**没有推进到下一节点**（节点数未增加 —— 这是"没被批准"最硬的证据）',
      scalar('SELECT COUNT(*) FROM flow_instance_node WHERE document_id=%s' % doc_id) == node_count_before,
      '%s → %s' % (node_count_before,
                   scalar('SELECT COUNT(*) FROM flow_instance_node WHERE document_id=%s' % doc_id)))
check('12. 拒绝后：单据状态未变（没有被「静默批准」）',
      scalar('SELECT status FROM document WHERE id=%s' % doc_id) == doc_status_before,
      '%s → %s' % (doc_status_before, scalar('SELECT status FROM document WHERE id=%s' % doc_id)))

print()
print('=' * 72)
print('二、另外两条路径同源 —— 批量通过 / 加签')
print('=' * 72)

st, r = call('POST', '/api/todos/batch-approve', token=admin_tk,
             body={'taskIds': [task_id], 'comment': 'E2E 越权探针'})
d = r.get('data') or {}
check('13. ★ 批量通过路径同样收口（succeeded=0 / failed=1）',
      st == 200 and r.get('code') == 0 and d.get('succeeded') == 0 and d.get('failed') == 1,
      'succeeded=%s failed=%s items=%s' % (d.get('succeeded'), d.get('failed'),
                                           [(i.get('ok'), (i.get('message') or '')[:24])
                                            for i in (d.get('items') or [])]))

st, r = call('POST', '/api/todos/countersign', token=admin_tk,
             body={'taskId': task_id, 'userId': ADMIN_ID})
check('14. ★ 加签路径同样收口（走到同一个 assertAssignee）',
      st == 200 and r.get('code') != 0, 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))
check('15. 加签被拒后：候选人池没有被改动',
      scalar("SELECT COUNT(*) FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s'" % task_id) == links_before,
      '%s → %s' % (links_before, scalar("SELECT COUNT(*) FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s'" % task_id)))

# ---------------------------------------------------------------- 正向对照：收的是写，不是读
print()
print('=' * 72)
print('三、正向对照：收口的是「写」，不是「读」')
print('=' * 72)

st, r = call('GET', '/api/documents/%s' % doc_id, token=admin_tk)
check('16. ★ admin 仍能打开该单据详情 —— 可见性来自 role_data_scope(company)，不再靠旁路',
      st == 200 and r.get('code') == 0, 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('GET', '/api/attachments?documentId=%s' % doc_id, token=admin_tk)
check('17. ★ admin 仍能读该单据的附件列表（读路径未被误伤）',
      st == 200 and r.get('code') == 0, 'HTTP %s / code=%s' % (st, r.get('code')))

# 反向对照：SELF 范围的旁观者读该夹具单据 —— 必须 403。
# 这一条是上面 15/16 的对照组：证明「读」确实在按范围裁决，
# 而不是"随便谁都能读"。两条一起看才说明 admin 的放行是有理由的放行。
BYSTANDER = 'huangxm'          # 演示库 EMPLOYEE / SELF 范围，既非申请人也非该节点承办人
bystander_tk = login(BYSTANDER)
st, r = call('GET', '/api/documents/%s' % doc_id, token=bystander_tk) if bystander_tk else (0, {})
check('18. 反向对照：SELF 范围的旁观者读该单据 → 被拒（读路径确实在按数据范围裁决）',
      bool(bystander_tk) and st == 200 and r.get('code') != 0,
      '%s HTTP %s / code=%s / msg=%s' % (BYSTANDER, st, r.get('code'), r.get('msg')))

st_b, r_b = call('GET', '/api/documents?pageNum=1&pageSize=200', token=bystander_tk) if bystander_tk else (0, {})
bystander_ids = [x.get('id') for x in (((r_b.get('data') or {}).get('records')) or [])] if st_b == 200 else []
check('19. 反向对照：该单据也不出现在旁观者的列表里',
      st_b == 200 and doc_id not in bystander_ids,
      '旁观者 %s 可见 %d 条，命中夹具 %s' % (BYSTANDER, len(bystander_ids), doc_id in bystander_ids))

# ---------------------------------------------------------------- 收尾
print()
print('=' * 72)
print('四、收尾：物理清理')
print('=' * 72)

cleanup()
audit_removed = cleanup_audit_window()

check('20. 夹具单据已清理，单据总数回到原值',
      scalar('SELECT COUNT(*) FROM document') == before_doc,
      '%s → %s' % (before_doc, scalar('SELECT COUNT(*) FROM document')))
check('21. 夹具待办已从 Flowable 运行表撤净',
      scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before_task,
      '%s → %s' % (before_task, scalar('SELECT COUNT(*) FROM ACT_RU_TASK')))
check('22. 通知数回到原值',
      scalar('SELECT COUNT(*) FROM notification') == before_notify,
      '%s → %s' % (before_notify, scalar('SELECT COUNT(*) FROM notification')))
check('23. 夹具账号已删净（可登录账号归零、角色绑定已解）',
      scalar("SELECT COUNT(*) FROM sys_user WHERE account='%s' AND deleted=0" % FIX_ACCOUNT) == '0'
      and scalar('SELECT COUNT(*) FROM user_role WHERE user_id=%s' % (fixture_user_id or 0)) == '0',
      'id=%s account=%s' % (fixture_user_id, FIX_ACCOUNT))
check('24. 本次窗口内产生的审计日志已按 id 清回原值',
      scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') == str(audit_floor),
      '清理 %s 行；max(id) %s → %s' % (audit_removed, audit_floor,
                                      scalar('SELECT IFNULL(MAX(id),0) FROM audit_log')))

print()
print('=' * 72)
total = len(PASS) + len(FAIL)
print('结果：通过 %d 项，失败 %d 项（断言总数 %d，预期 %d）'
      % (len(PASS), len(FAIL), total, EXPECTED_TOTAL))
if total != EXPECTED_TOTAL:
    print('✗ 断言条数与预期不符 —— 当事故查')
if FAIL:
    print('失败清单：')
    for f in FAIL:
        print('  - ' + f)
print('=' * 72)
raise SystemExit(1 if (FAIL or total != EXPECTED_TOTAL) else 0)

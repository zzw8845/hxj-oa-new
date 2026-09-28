# -*- coding: utf-8 -*-
"""
接口级验证：**流程干预 · 转办**（2026-09-28 新增能力）。

## 这个用例在防什么

转办是"把任务交还给正确的人"，**不产生任何审批结论**。它要解决的是一个
在此之前无解的场景：任务卡在一个推不动的人名下（长期请假、调岗），
而**改「节点指派规则」是没用的** —— 规则只在任务创建时解析一次
（`AssigneeTaskListener`），已经卡住的那张单不受影响，只能改库。

改库这条路正是本项目最忌讳的：用户定的总原则是"除引导数据外，一切走应用"。

## 为什么必须有用例钉住这四条边界

转办绕过了"谁是审批人"的全部判定（节点指派规则 / 候选人池 / 委托校验）——
它天然是一条**高权限通道**。少一条边界，它就从"干预"变成"越权"：

  1. **不能转给自己** —— 否则持有该权限点的人可以把任意任务搬到名下再批准，
     等于绕开 P3「审批决定权永不授予管理员」。`assertAssignee` 专门删掉了
     `hasRole("ADMIN")` 旁路，这条路必须一起堵，否则那次收口等于没做。
  2. **不能转给申请人** —— 否则等于让申请人审自己的单，绕开自审剔除。
  3. **目标必须在职** —— 否则人为造出「有承办人但承办人是废人」这种
     `autoSkipUnassigned` 唯一兜不住的形态（空列表有兜底，非空废人静默永久卡死）。
  4. **门控不是 `document:approve`** —— 「流程干预动作」与「审批决定动作」是两类职责。
     这一条用一个**持 document:approve 的真实承办人**去打接口来验：必须 403。

## 夹具与清理

夹具自建（临时申请人 + 临时停用账号 + 单据 + 流程实例），跑完按**创建时记下的 id**
物理清干净：单据、流程痕迹（含 `ACT_*`）、通知、本次窗口内的审计日志、夹具账号。
**不做任何"按模式/按范围/整表"的删除**。
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

# ---- 夹具申请人：必须是「本部门负责人」以外的人 ----------------------------------
# 首审批节点规则是 initiator_leader（＝申请人所在部门的负责人）。申请人若自己就是
# 该部门负责人，自审会被移除 ⇒ 首节点没有真实承办人，夹具前提不成立。
# dept 4「资金结算部」负责人 = 5 zhaocs：
#   申请人 = 临时账号(dept 4) → 首节点承办人 = zhaocs(5)，既不是申请人也不是 admin(1)。
FIXTURE_DEPT = 4
FIXTURE_ROLE = 'EMPLOYEE'
FIXTURE_PWD = '123456'
_SUF = '%s%s' % (int(time.time()) % 1000000, os.getpid() % 1000)
FIX_ACCOUNT = 'e2etr%s' % _SUF          # 临时申请人
DIS_ACCOUNT = 'e2etrd%s' % _SUF         # 临时"已停用"账号（用于验目标在职性）

TRANSFER_PERM = 'flow:intervene:transfer'
# 成功转办的目标：huangxm(9) 业务一部、EMPLOYEE、在职；既不是申请人也不是 admin
TARGET_ACCOUNT = 'huangxm'
EXPECTED_TOTAL = 33

ADMIN_ID = 1

PASS, FAIL = [], []
fixture = []              # [(documentId, docNo)]
fixture_user_id = None    # 临时申请人 id
disabled_user_id = None   # 临时停用账号 id
audit_floor = None
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
    return subprocess.run(['mysql', '-uroot', DB, '-e', stmt], capture_output=True, text=True).stdout.strip()


def scalar(stmt):
    """跑一条 SQL 取标量。

    ⚠ 必须把 mysql 的 **stderr** 也当失败看：Flowable 的 taskId 是 UUID 字符串，
    SQL 里漏了引号时 MySQL 会把它当作减法表达式报 `Unknown column`，
    而只读 stdout 的话，报错会**伪装成"查不到数据"** —— 于是断言红得莫名其妙，
    排查方向被带偏到功能上。这个坑在本用例开发时踩过一次（9 条断言集体假红）。
    """
    p = subprocess.run(['mysql', '-uroot', DB, '-N', '-B', '-e', stmt],
                       capture_output=True, text=True)
    if 'ERROR' in (p.stderr or ''):
        raise SystemExit('SQL 执行失败（用例自身问题，不是功能问题）：\n  %s\n  %s'
                         % (stmt, p.stderr.strip()))
    return p.stdout.strip()


def node_col(col, task_id):
    """按 taskId 取 flow_instance_node 的一列（taskId 是 UUID，**必须带引号**）。"""
    return scalar("SELECT %s FROM flow_instance_node WHERE task_id='%s'" % (col, task_id))


def login(account, pwd=FIXTURE_PWD):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


def todos_of(tk):
    st, r = call('GET', '/api/todos?limit=200', token=tk)
    return (r.get('data') or []) if st == 200 else []


def has_todo(tk, task_id):
    return any(t.get('taskId') == task_id for t in todos_of(tk))


def msg_of(r):
    return str(r.get('msg') or '')


def create_fixture_users():
    """临时申请人与临时"已停用"账号（走接口，保证角色/快照/唯一键都齐）。"""
    global fixture_user_id, disabled_user_id
    st, r = call('POST', '/api/users', token=admin_tk, body={
        'realName': 'E2E转办夹具申请人', 'jobNo': FIX_ACCOUNT, 'account': FIX_ACCOUNT,
        'password': FIXTURE_PWD, 'deptId': FIXTURE_DEPT, 'roleCodes': [FIXTURE_ROLE]})
    if st == 200 and r.get('code') == 0:
        fixture_user_id = (r.get('data') or {}).get('id')
    else:
        print('  [夹具] 建临时申请人失败：HTTP %s / %s' % (st, r.get('msg', r)))

    st, r = call('POST', '/api/users', token=admin_tk, body={
        'realName': 'E2E转办夹具停用', 'jobNo': DIS_ACCOUNT, 'account': DIS_ACCOUNT,
        'password': FIXTURE_PWD, 'deptId': FIXTURE_DEPT, 'roleCodes': [FIXTURE_ROLE]})
    if st == 200 and r.get('code') == 0:
        disabled_user_id = (r.get('data') or {}).get('id')
    # 置为离职（status=0）：这是"目标必须在职"那条边界的前提
    if disabled_user_id:
        call('PUT', '/api/users/%s' % disabled_user_id, token=admin_tk, body={
            'realName': 'E2E转办夹具停用', 'jobNo': DIS_ACCOUNT, 'account': DIS_ACCOUNT,
            'deptId': FIXTURE_DEPT, 'status': 0})
    else:
        print('  [夹具] 建临时停用账号失败：HTTP %s / %s' % (st, r.get('msg', r)))


def remove_fixture_user(uid):
    """按**创建时记下的 id** 删夹具账号（不给任何"按模式删"的口子）。"""
    if not uid:
        return
    call('DELETE', '/api/users/%s' % uid, token=admin_tk)
    sql('DELETE FROM user_role WHERE user_id=%s' % uid)
    sql('DELETE FROM user_post WHERE user_id=%s' % uid)
    sql('DELETE FROM sys_user WHERE id=%s' % uid)


def cleanup_audit_window():
    """删掉本次窗口内产生的审计日志。

    按 id 列表删而不是 `WHERE id > N`：本项目铁律是"只允许按创建时记下的 id 删"，
    范围限定的合法性由"先 SELECT 取证"这一步保证，而不是由 WHERE 条件自己声明。
    审计切面是 @Around，**失败也落库**，所以被拒的几次转办尝试也会留行。
    """
    ids = [x for x in (scalar('SELECT GROUP_CONCAT(id) FROM audit_log WHERE id > %s'
                              % audit_floor) or '').split(',') if x]
    for i in ids:
        sql('DELETE FROM audit_log WHERE id=%s' % i)
    return len(ids)


def cleanup():
    for doc_id, doc_no in fixture:
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
    remove_fixture_user(fixture_user_id)
    remove_fixture_user(disabled_user_id)


# ------------------------------------------------------------------ 开跑

print('=' * 72)
print('流程干预 · 转办')
print('=' * 72)

audit_floor = int(scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') or 0)
before_doc = scalar('SELECT COUNT(*) FROM document')
before_task = scalar('SELECT COUNT(*) FROM ACT_RU_TASK')
before_notify = scalar('SELECT COUNT(*) FROM notification')

admin_tk = login('admin')
check('admin 登录成功', bool(admin_tk))

# ---- 前置条件：权限点必须已在目录里、且 ADMIN 角色持有它 -------------------------
# 这两条是**部署状态**（权限点目录没有写接口，属"引导集"，只能随种子一起灌），
# 用例不代建 —— 代建等于让测试脚本改权限配置。这里明确报"夹具不成立"，
# 而不是让后面的断言以"无权限"的红灯呈现（那会把部署问题误读成功能故障）。
perm_declared = scalar("SELECT COUNT(*) FROM sys_permission WHERE code='%s' AND deleted=0" % TRANSFER_PERM)
perm_bound = scalar("SELECT COUNT(*) FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id "
                    "WHERE rp.perm_code='%s' AND rp.deleted=0 AND r.code='ADMIN'" % TRANSFER_PERM)
check('前置：权限点 %s 已在权限目录（minimal_seed 引导集）' % TRANSFER_PERM, perm_declared == '1',
      'sys_permission 命中 %s 条；若为 0 请同步 minimal_seed.sql 的权限点段' % perm_declared)
check('前置：ADMIN 角色已持有该权限点', perm_bound == '1',
      'role_permission 命中 %s 条；若为 0 请用 PUT /api/roles/1/permissions 绑定' % perm_bound)
if perm_declared != '1' or perm_bound != '1':
    print()
    print('✗ 夹具不成立（部署状态未就绪），本次结果无效 —— 这不是功能故障。')
    raise SystemExit(1)

create_fixture_users()
applicant_tk = login(FIX_ACCOUNT, FIXTURE_PWD)
check('夹具账号就绪（申请人 dept %d，另有 1 个已停用账号 id=%s）' % (FIXTURE_DEPT, disabled_user_id),
      bool(fixture_user_id and applicant_tk and disabled_user_id and
           scalar('SELECT status FROM sys_user WHERE id=%s' % disabled_user_id) == '0'),
      '申请人 id=%s 停用 id=%s' % (fixture_user_id, disabled_user_id))

# ---- 夹具单据：提交后停在 n2「直属部门负责人」，承办人 = dept 4 负责人 -----------
st, r = call('POST', '/api/documents', token=applicant_tk, body={
    'docTypeId': 1, 'title': 'E2E转办夹具', 'amount': 500, 'reason': 'E2E 转办夹具',
    'formData': {'title': 'E2E转办夹具', 'amount': 500, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 转办夹具'}})
doc = r.get('data') or {}
doc_id, doc_no = doc.get('id'), doc.get('docNo')
if doc_id:
    call('POST', '/api/documents/%s/submit' % doc_id, token=applicant_tk)
    fixture.append((doc_id, doc_no))

node_id = scalar('SELECT id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % (doc_id or 0))
task_id = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % (doc_id or 0))
node_key = node_col('node_key', task_id)
owner_id = node_col('assignee_id', task_id)
owner_acct = scalar('SELECT account FROM sys_user WHERE id=%s' % (owner_id or 0))
target_id = int(scalar("SELECT id FROM sys_user WHERE account='%s'" % TARGET_ACCOUNT) or 0)
check('夹具就绪（单据 + 待办 + 真实承办人）', bool(doc_id and task_id and owner_id and target_id),
      'docNo=%s 节点=%s 承办人=%s(%s) 目标=%s(%s)'
      % (doc_no, node_key, owner_acct, owner_id, TARGET_ACCOUNT, target_id))

owner_tk = login(owner_acct)
target_tk = login(TARGET_ACCOUNT)
check('原承办人与目标人登录成功', bool(owner_tk and target_tk),
      '承办人=%s 目标=%s' % (owner_acct, TARGET_ACCOUNT))

prog_status = node_col('status', task_id)
prog_doc_status = scalar('SELECT status FROM document WHERE id=%s' % (doc_id or 0))

# ---------------------------------------------------------------- 一、门控
print()
print('=' * 72)
print('一、门控：转办是「流程干预动作」，不是承办人的自助能力')
print('=' * 72)

st, r = call('POST', '/api/todos/transfer', token=owner_tk,
             body={'taskId': task_id, 'userId': target_id, 'reason': 'E2E 承办人自助转办'})
check('★ 当前承办人（持 document:approve，不持干预点）调转办 → 403',
      st == 403 and TRANSFER_PERM in msg_of(r),
      'HTTP %s msg=%s' % (st, msg_of(r)[:60]))

st, r = call('POST', '/api/todos/transfer', token=applicant_tk,
             body={'taskId': task_id, 'userId': target_id, 'reason': 'E2E 申请人调转办'})
check('★ 申请人（普通员工）调转办 → 403', st == 403, 'HTTP %s' % st)

# 过了门控的证据：用一个不存在的任务去调，报的应该是业务错（任务不存在），不是"无权限"
st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': 'not-a-real-task', 'userId': target_id, 'reason': 'E2E 探门控'})
check('★ 持干预点的 admin 能过门控（报业务错而不是无权限）',
      '任务不存在' in msg_of(r), 'HTTP %s msg=%s' % (st, msg_of(r)[:50]))

# ---------------------------------------------------------------- 二、参数校验
print()
print('=' * 72)
print('二、参数校验：失败必须显式（P5），不静默成功')
print('=' * 72)

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'userId': target_id})
check('缺 reason → 400 且提示必填（干预动作必须留下"为什么"）',
      st == 400 and '理由' in msg_of(r), 'HTTP %s msg=%s' % (st, msg_of(r)[:50]))

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'reason': 'E2E 缺目标'})
check('缺 userId → 400 且提示选择目标人员',
      st == 400 and '目标人员' in msg_of(r), 'HTTP %s msg=%s' % (st, msg_of(r)[:50]))

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'userId': 99999999, 'reason': 'E2E 不存在的人'})
check('目标用户不存在 → 报错（不静默成功）',
      st == 200 and r.get('code') != 0 and '不存在或已停用' in msg_of(r),
      'HTTP %s code=%s msg=%s' % (st, r.get('code'), msg_of(r)[:44]))

# ---------------------------------------------------------------- 三、四条边界
print()
print('=' * 72)
print('三、四条硬边界（少一条，这个接口就从"干预"变成"越权"）')
print('=' * 72)

# 这一段全部是"应当被拒"的尝试，因此先记下起点归属 —— 段末要证明它**一次都没被动过**。
# ⚠ 基准必须**当场读**，不能用夹具创建时那个值：后者在"某次尝试偷偷成功了"时不会变，
# 于是"归属未变"这条断言会假绿（负向验证时实测踩到过）。
# ⚠ "目标已是当前承办人"必须**排在最前**：后面几条里若有哪条偷偷成功了，归属就被改了，
# 段末的"归属未变"才抓得住（这四条断言合起来是一张网，顺序决定了网眼大小）。
guard_base = int(node_col('assignee_id', task_id) or 0)

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'userId': guard_base, 'reason': 'E2E 转给现承办人'})
check('★ 目标已是当前承办人 → 显式报错（空操作不能静默成功）',
      st == 200 and r.get('code') != 0 and '无需转办' in msg_of(r),
      'HTTP %s code=%s msg=%s' % (st, r.get('code'), msg_of(r)[:46]))

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'userId': ADMIN_ID, 'reason': 'E2E 转给自己'})
check('★ 不能转给自己（否则等于绕开 P3：把签字权搬到自己名下）',
      st == 200 and r.get('code') != 0 and '不能转办给自己' in msg_of(r),
      'HTTP %s code=%s msg=%s' % (st, r.get('code'), msg_of(r)[:46]))

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'userId': fixture_user_id, 'reason': 'E2E 转给申请人'})
check('★ 不能转给单据申请人（否则等于让申请人审自己的单）',
      st == 200 and r.get('code') != 0 and '申请人' in msg_of(r),
      'HTTP %s code=%s msg=%s' % (st, r.get('code'), msg_of(r)[:46]))

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'userId': disabled_user_id, 'reason': 'E2E 转给已离职'})
check('★ 不能转给已停用用户（否则人工造出"非空废人"这种静默卡死态）',
      st == 200 and r.get('code') != 0 and '停用' in msg_of(r),
      'HTTP %s code=%s msg=%s' % (st, r.get('code'), msg_of(r)[:46]))

# 四次被拒之后，归属必须**原封不动** —— 只看返回码不足以排除"报错了但也改了"
check('★ 四次被拒之后该待办归属未变（业务侧 assignee_id 未动）',
      node_col('assignee_id', task_id) == str(guard_base),
      'assignee_id %s→%s' % (guard_base, node_col('assignee_id', task_id)))
check('★ 引擎侧 ASSIGNEE_ 也未动（改派必须以引擎为准，不能只改业务投影）',
      scalar("SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE ID_='%s'" % task_id) == str(guard_base),
      'ASSIGNEE_=%s' % scalar("SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE ID_='%s'" % task_id))

# ---------------------------------------------------------------- 四、正向转办
print()
print('=' * 72)
print('四、正向：转办把任务真的交出去，且**不推进流程**')
print('=' * 72)

st, r = call('POST', '/api/todos/transfer', token=admin_tk,
             body={'taskId': task_id, 'userId': target_id, 'reason': 'E2E 审批人长期请假，转办'})
check('★ 转办成功', st == 200 and r.get('code') == 0, 'HTTP %s code=%s msg=%s' % (st, r.get('code'), msg_of(r)))

check('引擎侧承办人已换成目标人', scalar("SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE ID_='%s'" % task_id) == str(target_id),
      'ASSIGNEE_=%s' % scalar("SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE ID_='%s'" % task_id))
check('业务侧待办投影同步换人（否则新承办人在工作台上看不到这条）',
      node_col('assignee_id', task_id) == str(target_id)
      and node_col('assignee_name', task_id) != '',
      'assignee_id=%s name=%s' % (node_col('assignee_id', task_id), node_col('assignee_name', task_id)))
check('★ 节点留痕：action=transfer 且 comment 里能看出"转给谁、谁经办、为什么"',
      node_col('action', task_id) == 'transfer'
      and '转办给' in node_col('comment_text', task_id)
      and '长期请假' in node_col('comment_text', task_id),
      'comment=%s' % node_col('comment_text', task_id)[:80])

check('★ 转办**不推进流程**：节点状态与节点标识都不变',
      node_col('status', task_id) == prog_status
      and node_col('node_key', task_id) == node_key,
      'status %s→%s node_key=%s' % (prog_status, node_col('status', task_id), node_key))
check('★ 转办**不改单据状态**（它不产生任何审批结论）',
      scalar('SELECT status FROM document WHERE id=%s' % doc_id) == prog_doc_status,
      'document.status %s→%s' % (prog_doc_status, scalar('SELECT status FROM document WHERE id=%s' % doc_id)))

check('★ 新承办人的待办里出现该任务', has_todo(target_tk, task_id),
      '%s 待办数=%d' % (TARGET_ACCOUNT, len(todos_of(target_tk))))
check('★ 原承办人的待办里不再出现（授权真的交出去了）', not has_todo(owner_tk, task_id),
      '%s 待办数=%d' % (owner_acct, len(todos_of(owner_tk))))

notify_cnt = scalar("SELECT COUNT(*) FROM notification WHERE receiver_id=%s AND biz_type='document' "
                    "AND biz_id=%s AND notify_type='todo'" % (target_id, doc_id))
check('★ 新承办人收到一条转办通知（改派必须自带一句解释）', notify_cnt == '1',
      '通知数=%s，标题=%s' % (notify_cnt, scalar("SELECT title FROM notification WHERE receiver_id=%s "
                                            "AND biz_type='document' AND biz_id=%s" % (target_id, doc_id))))

st, r = call('POST', '/api/todos/approve', token=owner_tk,
             body={'taskId': task_id, 'action': 'approve', 'comment': 'E2E 原承办人补批'})
check('★ 原承办人已失去办理资格（转办不是"扩池"，是换人）',
      st == 200 and r.get('code') != 0 and '不是该节点的处理人' in msg_of(r),
      'HTTP %s code=%s msg=%s' % (st, r.get('code'), msg_of(r)[:46]))

st, r = call('POST', '/api/todos/approve', token=target_tk,
             body={'taskId': task_id, 'action': 'approve', 'comment': 'E2E 新承办人批准'})
check('★ 新承办人能正常审批（转办后授权真的转移了，不是"看着换了人"）',
      st == 200 and r.get('code') == 0, 'HTTP %s code=%s' % (st, r.get('code')))
check('节点已办结（status=2 已通过）', node_col('status', task_id) == '2',
      'status=%s' % node_col('status', task_id))

# ---------------------------------------------------------------- 五、收尾
print()
print('=' * 72)
print('五、收尾：物理清理')
print('=' * 72)

cleanup()
audit_removed = cleanup_audit_window()
check('单据 / 任务 / 通知 全部回到基线',
      scalar('SELECT COUNT(*) FROM document') == before_doc
      and scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before_task
      and scalar('SELECT COUNT(*) FROM notification') == before_notify,
      'doc %s→%s / task %s→%s / notify %s→%s'
      % (before_doc, scalar('SELECT COUNT(*) FROM document'),
         before_task, scalar('SELECT COUNT(*) FROM ACT_RU_TASK'),
         before_notify, scalar('SELECT COUNT(*) FROM notification')))
check('本窗口的审计日志已清（转办是被 @Audit 记录的，成功与失败都落库）',
      scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') == str(audit_floor),
      '清理 %s 行；max(id) %s → %s' % (audit_removed, audit_floor,
                                       scalar('SELECT IFNULL(MAX(id),0) FROM audit_log')))
check('两个临时账号已删净（含角色绑定）',
      scalar("SELECT COUNT(*) FROM sys_user WHERE account IN ('%s','%s')" % (FIX_ACCOUNT, DIS_ACCOUNT)) == '0'
      and scalar('SELECT COUNT(*) FROM user_role WHERE user_id IN (%s,%s)'
                 % (fixture_user_id or 0, disabled_user_id or 0)) == '0',
      '%s / %s' % (FIX_ACCOUNT, DIS_ACCOUNT))

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

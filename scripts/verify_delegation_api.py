# -*- coding: utf-8 -*-
"""
接口级验证：审批委托（代理审批）。

设计上要钉住的是"委托**只放行该放行的**"这件事，具体四条：
  1. **待办可见**：受托人能看到委托给他的待办，并且标注了"代谁办理"；
  2. **审批放行**：受托人能真的把这个待办批掉（授权与可见性同源）；
  3. **授权不放宽**：没有委托时，受托人批别人的待办仍被拒（403/业务拒绝）——
     这条是"委托"这个功能最容易出错的地方：做错方向就变成了"谁都能批"；
  4. **撤销即时生效**：撤销后受托人立刻看不到、也批不了。

另外钉住入参：不能委托给自己、时间段倒置被拒、同受托人同时段重叠被拒、
撤销只能撤自己的。

**夹具是自建的**（申请单 + 提交 + 跑完物理清理）：真实待办被批掉会推进单据状态，
而演示库的单据状态与 ACT_RU_TASK 都被 E2E 断言。
"""
import json
import os
import subprocess
import urllib.error
import urllib.request

os.environ['no_proxy'] = '127.0.0.1,localhost,::1'
os.environ['NO_PROXY'] = os.environ['no_proxy']
B = 'http://127.0.0.1:8080'
DB = 'haixiajin_oa'

# 夹具：申请人 admin（综合管理中心 dept 6，部门负责人=周综合）
DELEGATOR = 'zhouzh'      # 周综合（委托人，待办的原本承办人）
DELEGATE = 'linjl'        # 林经理（受托人）
EXPECTED_TOTAL = 24

PASS, FAIL = [], []
fixture = []              # [(documentId, docNo)]
delegation_ids = []


def call(method, path, token=None, body=None):
    req = urllib.request.Request(B + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=20) as r:
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
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-B', '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


def biz_fail(st, r):
    return (st == 200 and r.get('code') != 0) or st in (400, 403)


def todos_of(tk):
    st, r = call('GET', '/api/todos?limit=200', token=tk)
    return (r.get('data') or []) if st == 200 else []


def cleanup():
    for doc_id, doc_no in fixture:
        pids = [p for p in (scalar(
            "SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no) or '').split('\n') if p.strip()]
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
    # 委托记录：只删本次创建的那些 id
    if delegation_ids:
        sql('DELETE FROM flow_delegation WHERE id IN (%s)' % ','.join(str(i) for i in delegation_ids))


print('=' * 72)
print('审批委托（代理审批）')
print('=' * 72)

before_doc = scalar('SELECT COUNT(*) FROM document')
before_task = scalar('SELECT COUNT(*) FROM ACT_RU_TASK')

admin = login('admin')
delegator = login(DELEGATOR)
delegate = login(DELEGATE)
check('三个账号登录成功（申请人 admin / 委托人 zhouzh / 受托人 linjl）',
      bool(admin and delegator and delegate))

delegator_id = scalar("SELECT id FROM sys_user WHERE account='%s'" % DELEGATOR)
delegate_id = scalar("SELECT id FROM sys_user WHERE account='%s'" % DELEGATE)

# ---- 夹具：admin 建单提交（首节点指派给其部门负责人 周综合）----
st, r = call('POST', '/api/documents', token=admin, body={
    'docTypeId': 1, 'title': 'E2E委托夹具', 'amount': 200, 'reason': 'E2E 委托夹具',
    'formData': {'title': 'E2E委托夹具', 'amount': 200, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 委托夹具'}
})
doc = r.get('data') or {}
doc_id, doc_no = doc.get('id'), doc.get('docNo')
st2, _ = call('POST', '/api/documents/%s/submit' % doc_id, token=admin)
fixture.append((doc_id, doc_no))
task_id = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s AND status IN (0,1) '
                 'ORDER BY id DESC LIMIT 1' % doc_id)
check('夹具单据已提交且待办生成', bool(doc_id and task_id), 'docNo=%s taskId=%s' % (doc_no, (task_id or '')[:8]))
check('夹具的待办指派人正是委托人本人（首节点=发起人部门负责人）',
      scalar('SELECT assignee_id FROM flow_instance_node WHERE task_id=%s' % ("'" + task_id + "'")) == delegator_id,
      'assignee=%s 期望=%s' % (scalar('SELECT assignee_id FROM flow_instance_node WHERE task_id=%s' % ("'" + task_id + "'")), delegator_id))

# ---------------------------------------------------------------- 一、未委托时
print()
print('=' * 72)
print('一、没有委托关系时：受托人不能碰这个待办（授权不放宽）')
print('=' * 72)

st, r = call('POST', '/api/todos/approve', token=delegate,
             body={'taskId': task_id, 'action': 'approve', 'comment': '未授权尝试'})
check('★ 无委托时替他人审批 → 被拒（"您不是该节点的处理人"）',
      biz_fail(st, r) and '不是该节点的处理人' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

seen = [t for t in todos_of(delegate) if t.get('taskId') == task_id]
check('★ 无委托时该待办不出现在受托人的待办列表里', not seen, '命中 %d 条' % len(seen))

# ---------------------------------------------------------------- 二、入参
print()
print('=' * 72)
print('二、入参校验')
print('=' * 72)

now_iso = '2026-09-22T00:00:00'
st, r = call('POST', '/api/delegations', token=delegator, body={
    'delegateId': int(delegator_id), 'startAt': now_iso, 'endAt': '2026-09-30T00:00:00'})
check('委托给自己 → 被拒', biz_fail(st, r) and '自己' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:50]))

st, r = call('POST', '/api/delegations', token=delegator, body={
    'delegateId': int(delegate_id), 'startAt': '2026-09-30T00:00:00', 'endAt': '2026-09-22T00:00:00'})
check('生效开始晚于结束 → 被拒', biz_fail(st, r) and '早于' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:50]))

# 正式委托：覆盖夹具所在时段
st, r = call('POST', '/api/delegations', token=delegator, body={
    'delegateId': int(delegate_id), 'startAt': '2026-09-21T00:00:00', 'endAt': '2026-09-30T23:59:59',
    'remark': 'E2E 委托夹具（出差）'})
d1 = r.get('data') or {}
if d1.get('id'):
    delegation_ids.append(d1['id'])
check('新建委托成功且当前生效', st == 200 and d1.get('active') is True,
      'HTTP %d / active=%s' % (st, d1.get('active')))
check('委托条目带出双方姓名（界面可直接展示）',
      d1.get('delegatorName') == '周综合' and d1.get('delegateName') == '林经理',
      '%s → %s' % (d1.get('delegatorName'), d1.get('delegateName')))

st, r = call('POST', '/api/delegations', token=delegator, body={
    'delegateId': int(delegate_id), 'startAt': '2026-09-25T00:00:00', 'endAt': '2026-10-05T00:00:00'})
check('★ 同一受托人、同时段重叠的委托 → 被拒（避免"这条待办为什么在我这儿"无从解释）',
      biz_fail(st, r) and '已有' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

# ---------------------------------------------------------------- 三、可见 + 可批
print()
print('=' * 72)
print('三、委托生效：待办可见 + 审批放行')
print('=' * 72)

mine = (call('GET', '/api/delegations/mine', token=delegator)[1].get('data') or [])
tome = (call('GET', '/api/delegations/to-me', token=delegate)[1].get('data') or [])
check('委托人能在「我设置的」里看到这条', any(x.get('id') == d1.get('id') for x in mine), '共 %d 条' % len(mine))
check('受托人能在「委托给我的」里看到这条', any(x.get('id') == d1.get('id') for x in tome), '共 %d 条' % len(tome))

seen2 = [t for t in todos_of(delegate) if t.get('taskId') == task_id]
check('★ 委托生效后，该待办出现在受托人的待办列表里', len(seen2) == 1, '命中 %d 条' % len(seen2))
check('★ 该待办标注了「代 周综合 办理」',
      bool(seen2) and seen2[0].get('onBehalfOf') == '周综合',
      'onBehalfOf=%s' % (seen2[0].get('onBehalfOf') if seen2 else '(无)'))

st, r = call('POST', '/api/todos/approve', token=delegate,
             body={'taskId': task_id, 'action': 'approve', 'comment': 'E2E 代办通过'})
check('★ 委托生效后，受托人能真的把这个待办批掉', st == 200 and r.get('code') == 0,
      'HTTP %d / %s' % (st, r.get('msg', '')))
check('★ 被审批的节点已办结（status=2 已通过）',
      scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id) == '2',
      'status=%s' % scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id))

# ---------------------------------------------------------------- 四、撤销
print()
print('=' * 72)
print('四、撤销：即时生效 + 只能撤自己的')
print('=' * 72)

st, r = call('DELETE', '/api/delegations/%s' % d1.get('id'), token=delegate)
check('★ 受托人不能撤销委托人的委托（越权被拒）', biz_fail(st, r),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:50]))

st, r = call('DELETE', '/api/delegations/%s' % d1.get('id'), token=delegator)
check('委托人本人撤销成功', st == 200 and r.get('code') == 0, 'HTTP %d / %s' % (st, r.get('msg', '')))

# 撤销后：再造一张夹具的单，确认受托人既看不到也批不了
st, r = call('POST', '/api/documents', token=admin, body={
    'docTypeId': 1, 'title': 'E2E委托夹具2', 'amount': 300, 'reason': 'E2E 委托夹具2',
    'formData': {'title': 'E2E委托夹具2', 'amount': 300, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 委托夹具2'}
})
doc2 = r.get('data') or {}
if doc2.get('id'):
    call('POST', '/api/documents/%s/submit' % doc2['id'], token=admin)
    fixture.append((doc2['id'], doc2.get('docNo')))
task2 = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s AND status IN (0,1) '
               'ORDER BY id DESC LIMIT 1' % doc2.get('id'))
check('撤销后再造一张夹具待办', bool(task2), 'taskId=%s' % (task2 or '')[:8])

seen3 = [t for t in todos_of(delegate) if t.get('taskId') == task2]
check('★ 撤销后受托人不再看到该待办', not seen3, '命中 %d 条' % len(seen3))
st, r = call('POST', '/api/todos/approve', token=delegate,
             body={'taskId': task2, 'action': 'approve', 'comment': '撤销后尝试'})
check('★ 撤销后受托人也不再能批（授权随委托一起失效）',
      biz_fail(st, r) and '不是该节点的处理人' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

# ---------------------------------------------------------------- 五、收尾
print()
print('=' * 72)
print('五、收尾：物理清理')
print('=' * 72)

cleanup()
check('委托记录已清理（回到 0 条）', scalar('SELECT COUNT(*) FROM flow_delegation') == '0',
      '实际 %s' % scalar('SELECT COUNT(*) FROM flow_delegation'))
check('夹具单据已清理，单据总数回到原值',
      scalar('SELECT COUNT(*) FROM document') == before_doc,
      '%s → %s' % (before_doc, scalar('SELECT COUNT(*) FROM document')))
check('夹具待办已从 Flowable 运行表撤净', scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before_task,
      '%s → %s' % (before_task, scalar('SELECT COUNT(*) FROM ACT_RU_TASK')))

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

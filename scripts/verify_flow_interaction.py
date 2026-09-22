# -*- coding: utf-8 -*-
"""
交叉面自检：委托 × 超时升级 × 批量审批 同时作用于同一批待办。

为什么单独做这一轮：这三个功能互不依赖，**各自单测全过**，
但它们动的是同一件事 —— "这条待办谁看得见、谁批得动"。
组合起来才可能暴露单测看不到的问题，例如：

  · 升级是「加签成候选人」，而待办查询按 `assignee_id` 过滤
    ⇒ **被升级的上级可能"能批但看不到"**，功能上等于没升级；
  · 委托让 B 看得见，升级让 C 也能批 —— 两人同时看到同一条，
    谁先批谁成功，另一个应当得到"任务已被处理"而不是 500。

用例自建夹具（申请人所在部门必须有"有负责人的上级部门"），跑完物理清理。
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

APPLICANT = 'zhaocs'
DELEGATE = 'linjl'
EXPECTED_TOTAL = 17

PASS, FAIL = [], []
fixture = []
delegation_ids = []


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
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-B', '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


def todos_of(tk):
    st, r = call('GET', '/api/todos?limit=200', token=tk)
    return (r.get('data') or []) if st == 200 else []


def biz_fail(st, r):
    return (st == 200 and r.get('code') != 0) or st in (400, 403)


def cleanup():
    for doc_id, doc_no in fixture:
        pids = set()
        for stmt in ("SELECT proc_inst_id FROM flow_instance WHERE document_id=%s" % doc_id,
                     "SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no):
            for x in (scalar(stmt) or '').split('\n'):
                if x.strip():
                    pids.add(x.strip())
        task_ids = [t for t in (scalar(
            'SELECT GROUP_CONCAT(task_id) FROM flow_instance_node WHERE document_id=%s' % doc_id
        ) or '').split(',') if t]
        for tid in task_ids:
            sql("DELETE FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s'" % tid)
            sql("DELETE FROM ACT_RU_TASK WHERE ID_='%s'" % tid)
        for t in ('ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
                  'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG',
                  'ACT_RU_IDENTITYLINK', 'ACT_RU_ACTINST', 'ACT_RU_TASK',
                  'ACT_RU_VARIABLE', 'ACT_RU_EVENT_SUBSCR'):
            for pid in pids:
                sql("DELETE FROM %s WHERE PROC_INST_ID_='%s'" % (t, pid))
        for pid in pids:
            sql("DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='%s' AND PARENT_ID_ IS NOT NULL" % pid)
            sql("DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='%s'" % pid)
            sql("DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='%s'" % pid)
        sql('DELETE FROM flow_escalation WHERE document_id=%s' % doc_id)
        for t in ('attachment', 'document_link', 'flow_instance_node', 'flow_instance'):
            sql('DELETE FROM %s WHERE document_id=%s' % (t, doc_id))
        sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % doc_id)
        sql('DELETE FROM document WHERE id=%s' % doc_id)
    # 只删本次创建的那条委托（**不要整表删**）
    if dg.get('id'):
        sql('DELETE FROM flow_delegation WHERE id=%s' % dg['id'])


print('=' * 72)
print('交叉面自检：委托 × 超时升级 × 批量审批')
print('=' * 72)

before = {k: scalar('SELECT COUNT(*) FROM %s' % t)
          for k, t in (('doc', 'document'), ('task', 'ACT_RU_TASK'),
                       ('notify', 'notification'), ('esc', 'flow_escalation'))}

admin = login('admin')
applicant = login(APPLICANT)
check('账号登录成功（执行方 admin / 申请人 %s）' % APPLICANT, bool(admin and applicant))

# ---- 夹具 ----
st, r = call('POST', '/api/documents', token=applicant, body={
    'docTypeId': 1, 'title': 'E2E交叉面夹具', 'amount': 500, 'reason': 'E2E 交叉面夹具',
    'formData': {'title': 'E2E交叉面夹具', 'amount': 500, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 交叉面夹具'}
})
doc = r.get('data') or {}
doc_id, doc_no = doc.get('id'), doc.get('docNo')
if doc_id:
    call('POST', '/api/documents/%s/submit' % doc_id, token=applicant)
    fixture.append((doc_id, doc_no))
node_id = scalar('SELECT id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id)
task_id = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id)
assignee_id = scalar('SELECT assignee_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id)
assignee_acct = scalar('SELECT account FROM sys_user WHERE id=%s' % (assignee_id or 0))
check('夹具就绪（单据 + 待办 + 承办人）', bool(doc_id and task_id and assignee_id),
      'docNo=%s 承办人=%s' % (doc_no, assignee_acct))

owner_tk = login(assignee_acct)          # 原承办人 A
delegate_tk = login(DELEGATE)            # 受托人 B（也是升级引入的候选人之外的第三人）
check('原承办人与受托人登录成功', bool(owner_tk and delegate_tk),
      '承办人=%s 受托人=%s' % (assignee_acct, DELEGATE))

delegate_id = scalar("SELECT id FROM sys_user WHERE account='%s'" % DELEGATE)
leader_id = scalar("SELECT leader_id FROM department WHERE id="
                   "(SELECT parent_id FROM department WHERE id="
                   "(SELECT dept_id FROM sys_user WHERE account='%s'))" % APPLICANT)
leader_acct = scalar('SELECT account FROM sys_user WHERE id=%s' % (leader_id or 0))
leader_tk = login(leader_acct)           # 升级目标 C
check('升级目标（上级部门负责人）登录成功', bool(leader_tk), '目标=%s(id=%s)' % (leader_acct, leader_id))

# ---------------------------------------------------------------- 一、委托生效
print()
print('=' * 72)
print('一、委托：受托人 B 能看到并处理 A 的待办')
print('=' * 72)

st, r = call('POST', '/api/delegations', token=owner_tk, body={
    'delegateId': int(delegate_id), 'startAt': '2026-09-21T00:00:00',
    'endAt': '2026-09-30T23:59:59', 'remark': 'E2E 交叉面'})
dg = r.get('data') or {}
if dg.get('id'):
    delegation_ids.append(dg['id'])
check('A 委托给 B 成功', st == 200 and dg.get('active') is True, 'HTTP %d' % st)

seen_b = [t for t in todos_of(delegate_tk) if t.get('taskId') == task_id]
check('★ B 的待办里出现该任务且标注「代 A 办理」',
      len(seen_b) == 1 and bool(seen_b[0].get('onBehalfOf')),
      'onBehalfOf=%s' % (seen_b[0].get('onBehalfOf') if seen_b else '(未出现)'))

# ---------------------------------------------------------------- 二、超时升级
print()
print('=' * 72)
print('二、超时升级：上级 C 被加签进来')
print('=' * 72)

sql('UPDATE flow_instance_node SET deadline = NOW() - INTERVAL 3 HOUR WHERE id=%s' % node_id)
st, r = call('POST', '/api/flows/escalation/run?documentId=%s' % doc_id, token=admin)
esc = r.get('data') or {}
check('★ 超时升级执行成功且升级了 1 条', esc.get('escalated') == 1,
      'escalated=%s details=%s' % (esc.get('escalated'), (esc.get('details') or [])[:1]))

links = scalar("SELECT COUNT(*) FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s' AND USER_ID_='%s'"
               % (task_id, leader_id))
check('C 被加进该任务的候选人（引擎侧）', links != '0', '命中 %s 条' % links)

seen_c = [t for t in todos_of(leader_tk) if t.get('taskId') == task_id]
check('★ C 的待办列表里也能看到该任务（升级要真的"看得见"，否则等于没升级）',
      len(seen_c) == 1,
      'C(%s) 待办数=%d，命中=%d' % (leader_acct, len(todos_of(leader_tk)), len(seen_c)))

# ---------------------------------------------------------------- 三、批量审批
print()
print('=' * 72)
print('三、批量审批：B 批掉之后 C 再试应当逐条失败')
print('=' * 72)

st, r = call('POST', '/api/todos/batch-approve', token=delegate_tk,
             body={'taskIds': [task_id], 'comment': 'E2E 代办通过'})
res = r.get('data') or {}
check('★ B（受托人）能通过批量接口批掉这条代办的待办',
      st == 200 and res.get('succeeded') == 1,
      'HTTP %d 成功=%s 失败=%s' % (st, res.get('succeeded'), res.get('failed')))

st, r = call('POST', '/api/todos/batch-approve', token=leader_tk,
             body={'taskIds': [task_id], 'comment': 'E2E 升级后补批'})
res2 = r.get('data') or {}
check('★ C 再批同一任务 → 逐条失败（接口本身成功，不是 500）',
      st == 200 and res2.get('failed') == 1 and (res2.get('items') or [{}])[0].get('ok') is False,
      '失败原因=%s' % ((res2.get('items') or [{}])[0].get('message') or '')[:50])

check('★ 该节点已办结（status=2 已通过）',
      scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id) == '2',
      'status=%s' % scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id))
check('待办从 B 的列表里消失（已办结）',
      not [t for t in todos_of(delegate_tk) if t.get('taskId') == task_id])

# ---------------------------------------------------------------- 四、撤销委托
print()
print('=' * 72)
print('四、撤销委托后 B 失去资格')
print('=' * 72)

call('DELETE', '/api/delegations/%s' % dg.get('id'), token=owner_tk)
check('撤销委托成功',
      scalar('SELECT COUNT(*) FROM flow_delegation WHERE status=1') == '0',
      '生效委托数=%s' % scalar('SELECT COUNT(*) FROM flow_delegation WHERE status=1'))

# 再造一张夹具，确认撤销后 B 看不到
st, r = call('POST', '/api/documents', token=applicant, body={
    'docTypeId': 1, 'title': 'E2E交叉面夹具2', 'amount': 600, 'reason': 'E2E 交叉面夹具2',
    'formData': {'title': 'E2E交叉面夹具2', 'amount': 600, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 交叉面夹具2'}
})
doc2 = r.get('data') or {}
if doc2.get('id'):
    call('POST', '/api/documents/%s/submit' % doc2['id'], token=applicant)
    fixture.append((doc2['id'], doc2.get('docNo')))
task2 = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc2.get('id'))
check('再造一张夹具待办', bool(task2), 'taskId=%s' % (task2 or '')[:8])
check('★ 撤销后 B 不再看到新待办（授权随委托一起失效）',
      not [t for t in todos_of(delegate_tk) if t.get('taskId') == task2])

# ---------------------------------------------------------------- 五、收尾
print()
print('=' * 72)
print('五、收尾：物理清理')
print('=' * 72)

cleanup()
check('单据/任务/通知/升级台账 全部回到基线',
      scalar('SELECT COUNT(*) FROM document') == before['doc']
      and scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before['task']
      and scalar('SELECT COUNT(*) FROM notification') == before['notify']
      and scalar('SELECT COUNT(*) FROM flow_escalation') == before['esc']
      and scalar('SELECT COUNT(*) FROM flow_delegation') == '0',
      'doc %s→%s / task %s→%s / notify %s→%s / esc %s→%s'
      % (before['doc'], scalar('SELECT COUNT(*) FROM document'),
         before['task'], scalar('SELECT COUNT(*) FROM ACT_RU_TASK'),
         before['notify'], scalar('SELECT COUNT(*) FROM notification'),
         before['esc'], scalar('SELECT COUNT(*) FROM flow_escalation')))

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

# -*- coding: utf-8 -*-
"""
接口级验证：批量审批。

设计上的两个要点，用例要钉住的就是它们：
  1. **逐条独立事务**：一条失败不能把已成功的回滚掉 ——
     用例故意混入一条"别人的待办"，期望结果是"2 条成功 + 1 条失败"，
     而不是"整批失败"（那是全事务语义）。
  2. **授权没有放宽**：传别人的 taskId 只会让那一条失败（"您不是该节点的处理人"），
     不会因为走了批量接口就替别人批了单。

另外钉住：空列表被拒、超过上限（50）被拒、只支持"通过"。

**夹具是自建的**：真实待办能被审批掉会推进单据状态，而演示库的单据状态、
ACT_RU_TASK 数都被 E2E 断言 —— 所以不能拿现成待办做试验。
本用例自建 2 张单、跑完把单据与流程痕迹**物理清干净**。
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

EXPECTED_TOTAL = 18

PASS, FAIL = [], []
fixture_docs = []   # [(documentId, docNo)]


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
    return st == 200 and r.get('code') != 0


def make_fixture(tk, tag):
    """建一张付款单并提交，返回 (documentId, docNo)。"""
    st, r = call('POST', '/api/documents', token=tk, body={
        'docTypeId': 1, 'title': 'E2E批量审批夹具' + tag, 'amount': 100 + len(tag),
        'reason': 'E2E 批量审批夹具',
        'formData': {'title': 'E2E批量审批夹具' + tag, 'amount': 100 + len(tag), 'payType': 'GOODS',
                     'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                     'payeeBank': '测试银行', 'reason': 'E2E 批量审批夹具'}
    })
    doc = r.get('data') or {}
    if not doc.get('id'):
        return None, None
    st2, _ = call('POST', '/api/documents/%s/submit' % doc['id'], token=tk)
    if not (st2 == 200 and _.get('code') == 0):
        return doc['id'], None
    return doc['id'], doc.get('docNo')


def cleanup():
    """物理清掉夹具单据与全部流程痕迹（含 Flowable 表）。"""
    for doc_id, doc_no in fixture_docs:
        pids = []
        if doc_no:
            out = scalar("SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no)
            pids = [p for p in (out or '').split('\n') if p.strip()]
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
        sql('DELETE FROM attachment WHERE document_id=%s' % doc_id)
        sql('DELETE FROM document_link WHERE document_id=%s' % doc_id)
        sql('DELETE FROM flow_instance_node WHERE document_id=%s' % doc_id)
        sql('DELETE FROM flow_instance WHERE document_id=%s' % doc_id)
        sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % doc_id)
        sql('DELETE FROM document WHERE id=%s' % doc_id)


print('=' * 72)
print('批量审批')
print('=' * 72)

before_doc = scalar('SELECT COUNT(*) FROM document')
before_task = scalar('SELECT COUNT(*) FROM ACT_RU_TASK')
huang = login('huangxm')
check('申请人 huangxm 登录成功', bool(huang))

# ---- 夹具：两张单（待办会落到 huangxm 的部门负责人 林经理）----
for tag in ('A', 'B'):
    did, dno = make_fixture(huang, tag)
    if did:
        fixture_docs.append((did, dno))
check('自建 2 张夹具单据并提交成功', len([d for d in fixture_docs if d[1]]) == 2,
      'docs=%s' % fixture_docs)

task_ids = []
for did, _ in fixture_docs:
    tid = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s AND status IN (0,1) '
                 'ORDER BY id DESC LIMIT 1' % did)
    if tid:
        task_ids.append(tid)
check('夹具的待办已生成（拿到 taskId）', len(task_ids) == 2, 'taskIds=%d' % len(task_ids))

# 找一条**别人的**待办（不是林经理的），用来验证"授权没有放宽"
foreign_task = scalar("SELECT n.task_id FROM flow_instance_node n "
                      "WHERE n.status IN (0,1) AND n.deleted=0 AND n.task_id IS NOT NULL "
                      "AND n.assignee_id <> (SELECT id FROM sys_user WHERE account='linjl') "
                      "AND n.document_id NOT IN (SELECT id FROM document WHERE title LIKE 'E2E批量审批夹具%') "
                      "LIMIT 1")
check('取到一条他人的待办用于越权验证', bool(foreign_task), 'foreignTask=%s' % (foreign_task or '(无)'))

owner = login('linjl')   # 林经理：夹具的待办都归他
check('待办承办人 linjl 登录成功', bool(owner))

# ---------------------------------------------------------------- 一、入参校验
print()
print('=' * 72)
print('一、入参校验')
print('=' * 72)

st, r = call('POST', '/api/todos/batch-approve', token=owner, body={'taskIds': []})
check('空列表 → 被拒（不会静默成功）', biz_fail(st, r) or st == 400,
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:50]))

st, r = call('POST', '/api/todos/batch-approve', token=owner,
             body={'taskIds': ['fake-%d' % i for i in range(51)]})
check('超过 50 条上限 → 被拒（避免一次请求压垮服务）',
      biz_fail(st, r) or st == 400, 'HTTP %d / %s' % (st, (r.get('msg') or '')[:50]))

st, r = call('POST', '/api/todos/batch-approve', token=owner,
             body={'taskIds': ['not-a-real-task']})
items = (r.get('data') or {}).get('items') or []
check('不存在的 taskId → 该条失败但接口本身成功（逐条语义）',
      st == 200 and r.get('code') == 0 and items and items[0].get('ok') is False,
      'items=%s' % json.dumps(items, ensure_ascii=False)[:90])

# ---------------------------------------------------------------- 二、混合批量
print()
print('=' * 72)
print('二、混合批量：2 条自己的 + 1 条别人的')
print('=' * 72)

batch = list(task_ids) + ([foreign_task] if foreign_task else [])
st, r = call('POST', '/api/todos/batch-approve', token=owner,
             body={'taskIds': batch, 'comment': 'E2E 批量通过'})
data = r.get('data') or {}
check('批量接口调用成功（接口层面）', st == 200 and r.get('code') == 0,
      'HTTP %d / %s' % (st, r.get('msg', '')))
check('★ 结果是 2 成功 + 1 失败（不是"全成功"也不是"全回滚"）',
      data.get('succeeded') == 2 and data.get('failed') == 1,
      'total=%s 成功=%s 失败=%s' % (data.get('total'), data.get('succeeded'), data.get('failed')))

by_id = {i.get('taskId'): i for i in (data.get('items') or [])}
ok_items = [i for i in (data.get('items') or []) if i.get('ok')]
bad_items = [i for i in (data.get('items') or []) if not i.get('ok')]
check('★ 成功的那两条就是自己的待办', len(ok_items) == 2 and set(i['taskId'] for i in ok_items) == set(task_ids),
      '成功=%s' % [i['taskId'][:8] for i in ok_items])
check('★ 失败的那一条是别人的待办，原因明确（授权没有放宽）',
      len(bad_items) == 1 and bad_items[0]['taskId'] == foreign_task
      and '不是该节点的处理人' in (bad_items[0].get('message') or ''),
      '失败原因=%s' % (bad_items[0].get('message') if bad_items else '(无)')[:60])

# 真实生效性：**按被审批的那个 taskId 查它对应的节点**是否已办结。
# ⚠ 不能查"该单据最新的节点" —— 审批通过后会**新建下一个节点**，
# 最新那条正好是新的待处理节点（第一版就是这么写错的，断言失败而功能其实是对的）。
advanced = 0
for tid in task_ids:
    st_ = scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % tid)
    if st_ == '2':      # 2 = 已通过
        advanced += 1
check('★ 两条成功的审批确实推进了流程（被审批节点已办结，status=2 已通过）', advanced == 2,
      '已办结 %d/2' % advanced)

check('失败的他人待办没有被改动（仍是待处理）',
      scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % foreign_task) in ('0', '1'),
      'status=%s' % scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % foreign_task))

audit = scalar("SELECT COUNT(*) FROM audit_log WHERE module='flow' AND action='batchApprove' "
               "AND created_at >= NOW() - INTERVAL 10 MINUTE")
check('批量动作进了审计日志', int(audit or 0) >= 1, '近 10 分钟 %s 条' % audit)

# ---------------------------------------------------------------- 三、收尾
print()
print('=' * 72)
print('三、收尾：物理清理夹具与流程痕迹')
print('=' * 72)

cleanup()
check('夹具单据已物理清理，单据总数回到原值',
      scalar('SELECT COUNT(*) FROM document') == before_doc,
      '%s → %s' % (before_doc, scalar('SELECT COUNT(*) FROM document')))
check('夹具的待办已从 Flowable 运行表撤净（ACT_RU_TASK 回到原值）',
      scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before_task,
      '%s → %s' % (before_task, scalar('SELECT COUNT(*) FROM ACT_RU_TASK')))
check('没有留下指向已删单据的孤儿流程实例',
      scalar('SELECT COUNT(*) FROM flow_instance_node WHERE document_id IN '
             '(SELECT id FROM document WHERE title LIKE \'E2E批量审批夹具%\')') == '0',
      '孤儿节点=%s' % scalar('SELECT COUNT(*) FROM flow_instance_node WHERE document_id IN '
                          '(SELECT id FROM document WHERE title LIKE \'E2E批量审批夹具%\')'))

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

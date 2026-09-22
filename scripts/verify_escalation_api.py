# -*- coding: utf-8 -*-
"""
接口级验证：超时升级。

背景：用户明确说过"**目前还不知道有无这个需求**"，所以实现是"装好但默认不生效"：
  · 自动升级默认关闭（`oa.flow.escalation.enabled=false`）—— 不会去打扰任何人；
  · 手动触发（`POST /api/flows/escalation/run`）不受开关限制，用例靠它做确定性验证
    （否则只能等定时器，测不了）。

用例要钉住的是这四件事：
  1. **真的会升级**：超时节点被扫描到，且**上级部门负责人被加签进来**（不是改派）；
  2. **升级对象正确**：沿部门树往上找到有负责人的祖先部门，且**排除承办人本人**
     （升给他等于没升）；
  3. **幂等**：同一节点重复执行只升级一次（靠 `uk_escalation_node` 唯一键，
     不是"先查再插"—— 定时任务与手动触发可能并发）；
  4. **可追溯**：台账记录升级给了谁；上级与原承办人各收到一条通知。

夹具自建（申请人所在部门必须有"有负责人的上级部门"，否则只会走"未找到上级"分支），
跑完把单据、流程痕迹、升级台账、通知全部物理清干净。
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

# 申请人 zhaocs（资金结算部 dept 4，该部门负责人=他自己 5）
#   → 首节点承办人 = 5；沿部门树往上：dept 4 的父 = dept 2（财务中心，负责人=3 李财务）
#   → 升级对象应为 3（且 3 ≠ 承办人 5，不会被排除）
APPLICANT = 'zhaocs'
EXPECTED_TOTAL = 22

PASS, FAIL = [], []
fixture = []       # [(documentId, docNo)]


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


def cleanup():
    for doc_id, doc_no in fixture:
        # 流程实例 id 从**两张表**取并集：flow_instance（业务侧，永远有）与
        # ACT_HI_PROCINST（引擎侧，按单号）。只按单号查过一次就不够 ——
        # 单号为空或历史行被先删时 pids 会空，运行时任务行就留下来了（实测踩过）。
        pids = set()
        for stmt in ("SELECT proc_inst_id FROM flow_instance WHERE document_id=%s" % doc_id,
                     "SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no):
            for x in (scalar(stmt) or '').split('\n'):
                if x.strip():
                    pids.add(x.strip())
        pids = list(pids)
        # 兜底：该单据节点上记录的 task_id 对应的运行时任务与候选人，逐个删干净
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
        sql('DELETE FROM flow_escalation WHERE document_id=%s' % doc_id)
        for t in ('attachment', 'document_link', 'flow_instance_node', 'flow_instance'):
            sql('DELETE FROM %s WHERE document_id=%s' % (t, doc_id))
        sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % doc_id)
        sql('DELETE FROM document WHERE id=%s' % doc_id)


print('=' * 72)
print('超时升级')
print('=' * 72)

before_doc = scalar('SELECT COUNT(*) FROM document')
before_task = scalar('SELECT COUNT(*) FROM ACT_RU_TASK')
before_notify = scalar('SELECT COUNT(*) FROM notification')
before_esc = scalar('SELECT COUNT(*) FROM flow_escalation')

admin = login('admin')
applicant = login(APPLICANT)
check('两个账号登录成功（执行升级 admin / 申请人 %s）' % APPLICANT, bool(admin and applicant))

leader_id = scalar("SELECT leader_id FROM department WHERE id="
                   "(SELECT parent_id FROM department WHERE id="
                   "(SELECT dept_id FROM sys_user WHERE account='%s'))" % APPLICANT)
leader_acct = scalar("SELECT account FROM sys_user WHERE id=%s" % (leader_id or 0))
check('夹具场景成立：申请人部门存在「有负责人的上级部门」',
      bool(leader_id) and leader_acct != '', '升级目标=%s(id=%s)' % (leader_acct, leader_id))

# ---- 夹具 ----
st, r = call('POST', '/api/documents', token=applicant, body={
    'docTypeId': 1, 'title': 'E2E超时升级夹具', 'amount': 400, 'reason': 'E2E 超时升级夹具',
    'formData': {'title': 'E2E超时升级夹具', 'amount': 400, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 超时升级夹具'}
})
doc = r.get('data') or {}
doc_id, doc_no = doc.get('id'), doc.get('docNo')
if doc_id:
    call('POST', '/api/documents/%s/submit' % doc_id, token=applicant)
    fixture.append((doc_id, doc_no))
node_id = scalar('SELECT id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id)
task_id = scalar('SELECT task_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id)
assignee = scalar('SELECT assignee_id FROM flow_instance_node WHERE document_id=%s ORDER BY id DESC LIMIT 1' % doc_id)
check('夹具单据已提交且首节点待办生成', bool(doc_id and node_id and task_id),
      'docNo=%s nodeId=%s' % (doc_no, node_id))

# 造出超时：把该夹具节点的 deadline 改到过去（只动自己的夹具）
sql('UPDATE flow_instance_node SET deadline = NOW() - INTERVAL 2 HOUR WHERE id=%s' % node_id)
check('已把夹具节点的处理时限改到 2 小时前（造出「超时」这一前提）',
      scalar('SELECT deadline < NOW() FROM flow_instance_node WHERE id=%s' % node_id) == '1')

# ---------------------------------------------------------------- 一、执行升级
print()
print('=' * 72)
print('一、执行一次超时升级')
print('=' * 72)

st, r = call('POST', '/api/flows/escalation/run?documentId=%s' % doc_id, token=admin)
data = r.get('data') or {}
check('升级接口调用成功', st == 200 and r.get('code') == 0, 'HTTP %d / %s' % (st, r.get('msg', '')))
check('★ 自动开关默认关闭（需求未确认前不会自行打扰任何人）',
      data.get('autoEnabled') is False, 'autoEnabled=%s' % data.get('autoEnabled'))
# 限定单据：只扫夹具那一条 —— 不限定就会把库里所有超时节点都动一遍（实测踩过）
check('★ 扫到了刚造出来的超时节点，且只扫了这一张单（不碰演示数据）',
      data.get('scanned') == 1, 'scanned=%s' % data.get('scanned'))
check('★ 确实升级了（把上级加签进来，不是只标记一下）', (data.get('escalated') or 0) >= 1,
      'escalated=%s details=%s' % (data.get('escalated'), (data.get('details') or [])[:2]))

st, r = call('GET', '/api/flows/escalation/by-document/%s' % doc_id, token=admin)
hist = r.get('data') or []
check('台账记了这条升级', st == 200 and len(hist) == 1 and hist[0].get('status') == 1,
      '条数=%d status=%s' % (len(hist), hist[0].get('status') if hist else '-'))
check('★ 升级对象 = 上级部门负责人（沿部门树往上找到的那一位）',
      bool(hist) and str(hist[0].get('toAssigneeId')) == str(leader_id),
      'toAssigneeId=%s 期望=%s(%s)' % (hist[0].get('toAssigneeId') if hist else '-', leader_id, leader_acct))
check('★ 原承办人没有被改派（升级是加签，assignee 保持不变）',
      scalar('SELECT assignee_id FROM flow_instance_node WHERE id=%s' % node_id) == assignee,
      'assignee=%s' % scalar('SELECT assignee_id FROM flow_instance_node WHERE id=%s' % node_id))

links = scalar("SELECT COUNT(*) FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s' AND USER_ID_='%s'"
               % (task_id, leader_id))
check('★ 上级确实被加进了该任务的候选人（引擎侧可见）', links != '0', 'identity link 命中 %s 条' % links)

notify_leader = scalar("SELECT COUNT(*) FROM notification WHERE biz_type='document' AND biz_id=%s "
                       "AND receiver_id=%s AND notify_type='todo'" % (doc_id, leader_id))
notify_owner = scalar("SELECT COUNT(*) FROM notification WHERE biz_type='document' AND biz_id=%s "
                      "AND receiver_id=%s" % (doc_id, assignee))
check('上级收到了「已升级给你」的通知', notify_leader == '1', '命中 %s 条' % notify_leader)
check('原承办人也收到了「已超时并被升级」的通知（他该知道）', notify_owner == '1', '命中 %s 条' % notify_owner)

# ---------------------------------------------------------------- 二、幂等
print()
print('=' * 72)
print('二、幂等：再执行一次不应重复升级')
print('=' * 72)

st, r = call('POST', '/api/flows/escalation/run?documentId=%s' % doc_id, token=admin)
data2 = r.get('data') or {}
check('★ 第二次执行：没有新增升级（escalated=0）', (data2.get('escalated') or 0) == 0,
      'escalated=%s' % data2.get('escalated'))
check('★ 且被识别为「已升级过」', (data2.get('alreadyEscalated') or 0) >= 1,
      'alreadyEscalated=%s' % data2.get('alreadyEscalated'))
check('台账仍然只有 1 条（唯一键把幂等做成了结构性事实）',
      scalar('SELECT COUNT(*) FROM flow_escalation WHERE document_id=%s' % doc_id) == '1',
      '实际 %s 条' % scalar('SELECT COUNT(*) FROM flow_escalation WHERE document_id=%s' % doc_id))
check('通知也没有重复发（上级仍只有 1 条）',
      scalar("SELECT COUNT(*) FROM notification WHERE biz_type='document' AND biz_id=%s AND receiver_id=%s"
             % (doc_id, leader_id)) == '1',
      '命中 %s 条' % scalar("SELECT COUNT(*) FROM notification WHERE biz_type='document' AND biz_id=%s "
                        "AND receiver_id=%s" % (doc_id, leader_id)))

# ---------------------------------------------------------------- 三、收尾
print()
print('=' * 72)
print('三、收尾：物理清理')
print('=' * 72)

cleanup()
check('升级台账已清理回原值', scalar('SELECT COUNT(*) FROM flow_escalation') == before_esc,
      '%s → %s' % (before_esc, scalar('SELECT COUNT(*) FROM flow_escalation')))
check('夹具单据已清理，单据总数回到原值',
      scalar('SELECT COUNT(*) FROM document') == before_doc,
      '%s → %s' % (before_doc, scalar('SELECT COUNT(*) FROM document')))
check('夹具待办已从 Flowable 运行表撤净', scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before_task,
      '%s → %s' % (before_task, scalar('SELECT COUNT(*) FROM ACT_RU_TASK')))
check('通知数回到原值（夹具产生的通知已删）',
      scalar('SELECT COUNT(*) FROM notification') == before_notify,
      '%s → %s' % (before_notify, scalar('SELECT COUNT(*) FROM notification')))

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

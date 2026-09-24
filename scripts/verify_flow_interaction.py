# -*- coding: utf-8 -*-
"""
交叉面自检：三组"新老功能交界处"的组合。

  A. 委托 × 超时升级 × 批量审批（都动"谁看得见、谁批得动"）
  B. 用印 × 表单模板（模板改版会不会影响在途单据的表单版本快照）
  C. 登出 × 批量审批（token 吊销后旧 token 是否立刻失效）

为什么单独做这一轮：这三个功能互不依赖，**各自单测全过**，
但它们动的是同一件事 —— "这条待办谁看得见、谁批得动"。
组合起来才可能暴露单测看不到的问题，例如：

  · 升级是「加签成候选人」，而待办查询按 `assignee_id` 过滤
    ⇒ **被升级的上级可能"能批但看不到"**，功能上等于没升级；
  · 委托让 B 看得见，升级让 C 也能批 —— 两人同时看到同一条，
    谁先批谁成功，另一个应当得到"任务已被处理"而不是 500。

用例自建夹具（申请人所在部门必须有"有负责人的上级部门"），跑完物理清理
（含临时夹具申请人账号，按记下的 id 删）。
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

# ---- 夹具申请人：**必须"不是本部门负责人"**（同 verify_escalation_api 的夹具前提）------
# 首审批节点规则是 initiator_leader（＝申请人所在部门的负责人）。申请人若就是该部门
# 负责人，自审被移除 ⇒ 首节点没有真实承办人 ⇒ 本用例后面「原承办人登录」「委托给 B」
# 「升级给 C」全部连锁失败。实测演示组织架构里没有现成账号同时满足
# 「本人≠本部门负责人」与「上级部门负责人≠本部门负责人」，故临时建一个 dept 4 的
# 非负责人账号（dept 4 是唯一满足第二条的部门）。
FIXTURE_DEPT = 4
FIXTURE_ROLE = 'EMPLOYEE'
FIXTURE_PWD = '123456'
FIX_ACCOUNT = 'e2efix%s%s' % (int(time.time()) % 1000000, os.getpid() % 1000)
APPLICANT = FIX_ACCOUNT          # 下面所有按 account 反查的 SQL 都跟着它走
DELEGATE = 'linjl'
EXPECTED_TOTAL = 34

PASS, FAIL = [], []
fixture = []
delegation_ids = []
fixture_user_id = None   # 临时夹具申请人 id（收尾按它删）


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


def create_fixture_applicant(admin_tk):
    """临时建一个「非部门负责人」的申请人账号，返回其 id（走接口，保证角色/快照齐全）。"""
    global fixture_user_id
    st, r = call('POST', '/api/users', token=admin_tk, body={
        'realName': 'E2E夹具申请人', 'jobNo': FIX_ACCOUNT, 'account': FIX_ACCOUNT,
        'password': FIXTURE_PWD, 'deptId': FIXTURE_DEPT, 'roleCodes': [FIXTURE_ROLE]})
    if st == 200 and r.get('code') == 0:
        fixture_user_id = (r.get('data') or {}).get('id')
    else:
        print('  [夹具] 建临时申请人失败：HTTP %s / %s' % (st, r.get('msg', r)))
    return fixture_user_id


def remove_fixture_applicant():
    """按**创建时记下的 id** 删掉夹具账号（不给任何"按模式删"的口子）。"""
    if not fixture_user_id:
        return
    call('DELETE', '/api/users/%s' % fixture_user_id, token=admin)
    sql('DELETE FROM user_role WHERE user_id=%s' % fixture_user_id)
    sql('DELETE FROM user_post WHERE user_id=%s' % fixture_user_id)
    sql('DELETE FROM sys_user WHERE id=%s' % fixture_user_id)


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
    remove_fixture_applicant()


print('=' * 72)
print('交叉面自检：委托 × 超时升级 × 批量审批')
print('=' * 72)

before = {k: scalar('SELECT COUNT(*) FROM %s' % t)
          for k, t in (('doc', 'document'), ('task', 'ACT_RU_TASK'),
                       ('notify', 'notification'), ('esc', 'flow_escalation'))}

admin = login('admin')
create_fixture_applicant(admin)
applicant = login(APPLICANT, FIXTURE_PWD)
check('账号登录成功（执行方 admin / 申请人 %s，dept %s 的临时非负责人账号）'
      % (APPLICANT, FIXTURE_DEPT), bool(admin and applicant and fixture_user_id),
      '夹具 id=%s' % fixture_user_id)

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
check('夹具就绪（单据 + 待办 + **真实承办人**）', bool(doc_id and task_id and assignee_id),
      'docNo=%s 承办人=%s' % (doc_no, assignee_acct or '(空 ⇒ 申请人就是本部门负责人，夹具前提不成立)'))

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

# ---------------------------------------------------------------- 二之一、审批动作白名单
print()
print('=' * 72)
print('二之一、审批动作白名单：未知 action 必须报错，不能静默当"通过"')
print('=' * 72)

# 【为什么必须有用例守住这一条】原实现是「除 reject 外一律走通过分支」，
# 于是 action 拼错（aprove）、传了别的动作名（withdraw）、或将来新增动作而调用方先上线，
# 都会**静默批准** —— 审批是不可逆动作，一个 typo 等于替审批人签了字，且不留痕迹。
# 这类缺陷靠读代码看不出来（代码"看着能跑"），只有断言"必须报错 + 状态不许动"才守得住。
#
# 用**真实承办人 A** 去打：换别人会先被 assertAssignee 拦下，就走不到 action 分派那一步，
# 断言会假绿（验的是"你不是承办人"而不是"action 非法"）。
status_before = scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id)
st, r = call('POST', '/api/todos/approve', token=owner_tk,
             body={'taskId': task_id, 'action': 'aprove', 'comment': 'E2E 拼错的 action'})
check('★ 拼错的 action（aprove）被拒绝，不是静默通过',
      st == 200 and r.get('code') != 0 and '不支持的审批动作' in str(r.get('msg') or ''),
      'HTTP %d code=%s msg=%s' % (st, r.get('code'), (r.get('msg') or '')[:44]))

st, r = call('POST', '/api/todos/approve', token=owner_tk,
             body={'taskId': task_id, 'action': 'withdraw', 'comment': 'E2E 非法 action'})
check('★ 非审批语义的 action（withdraw）同样被拒绝',
      st == 200 and r.get('code') != 0,
      'HTTP %d code=%s' % (st, r.get('code')))

# 两条都被拒之后，节点状态与待办归属都必须**原封不动** ——
# 这一条才是"没有静默推进"的真正证据；只看返回码不足以排除"报错了但也批了"。
check('★ 两次非法 action 之后该节点状态未变、仍是 A 的待办',
      scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id) == status_before
      and bool([t for t in todos_of(owner_tk) if t.get('taskId') == task_id]),
      'status %s→%s' % (status_before,
                        scalar("SELECT status FROM flow_instance_node WHERE task_id='%s'" % task_id)))

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

# ---------------------------------------------------------------- 六、用印 × 表单模板
print()
print('=' * 72)
print('六、用印 × 表单模板：模板改版不影响在途单据的版本快照')
print('=' * 72)

DOC_TYPE = 1
base_tpl = scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0 "
                  "ORDER BY version DESC LIMIT 1" % DOC_TYPE)
base_ver = scalar('SELECT version FROM form_template WHERE id=%s' % (base_tpl or 0))

st, r = call('POST', '/api/documents', token=applicant, body={
    'docTypeId': DOC_TYPE, 'title': 'E2E模板快照夹具', 'amount': 700, 'reason': 'E2E 模板快照夹具',
    'formData': {'title': 'E2E模板快照夹具', 'amount': 700, 'payType': 'GOODS',
                 'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                 'payeeBank': '测试银行', 'reason': 'E2E 模板快照夹具'}
})
doc3 = r.get('data') or {}
doc3_id = doc3.get('id')
if doc3_id:
    call('POST', '/api/documents/%s/submit' % doc3_id, token=applicant)
    fixture.append((doc3_id, doc3.get('docNo')))
ver_snapshot = scalar('SELECT form_template_ver FROM document WHERE id=%s' % doc3_id)
check('★ 在途单据记下了提交时刻的模板版本（%s）' % ver_snapshot,
      bool(ver_snapshot) and ver_snapshot == base_ver,
      '单据 form_template_ver=%s 生效模板 v=%s' % (ver_snapshot, base_ver))

new_tpl_id = None
schema = {'docType': 'DAILY_PAYMENT', 'layout': 'two-column', 'fields': [
    {'key': 'title', 'type': 'text', 'label': '申请事项', 'colSpan': 2, 'required': True},
    {'key': 'amount', 'type': 'money', 'label': '金额', 'colSpan': 1, 'required': True},
    {'key': 'payType', 'type': 'select', 'label': '付款类型', 'colSpan': 1, 'required': True},
    {'key': 'reason', 'type': 'textarea', 'label': '申请事由', 'colSpan': 2, 'required': True}]}
st, r = call('POST', '/api/forms/templates', token=admin,
             body={'docTypeId': DOC_TYPE, 'name': 'E2E 交互夹具模板', 'schema': schema})
new_tpl_id = (r.get('data') or {}).get('id')
check('新建模板草稿成功（用于触发一次"改版"）', bool(new_tpl_id), 'id=%s' % new_tpl_id)
st, r = call('POST', '/api/forms/templates/%s/activate' % new_tpl_id, token=admin)
check('启用新版本成功（原版本被自动废弃）', st == 200 and (r.get('data') or {}).get('status') == 1)

check('★ 在途单据的模板版本快照**没有**被改版影响',
      scalar('SELECT form_template_ver FROM document WHERE id=%s' % doc3_id) == ver_snapshot,
      '仍为 %s（新版本 v=%s）' % (scalar('SELECT form_template_ver FROM document WHERE id=%s' % doc3_id),
                              scalar('SELECT version FROM form_template WHERE id=%s' % new_tpl_id)))
st, r = call('GET', '/api/documents/%s' % doc3_id, token=applicant)
check('★ 改版后该单据详情仍能正常打开（快照没被破坏）',
      st == 200 and r.get('code') == 0, 'HTTP %d' % st)
check('新提交的单据才会用新版本（生效模板已是新版）',
      scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE) == str(new_tpl_id),
      '生效 id=%s' % scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE))

# 还原：把原版本切回生效、物理删除夹具模板（与 verify_form_template_api 同一套做法）
sql("UPDATE form_template SET status=0 WHERE id=%s" % base_tpl)
sql("UPDATE form_template SET status=0 WHERE id=%s" % new_tpl_id)
sql("UPDATE form_template SET status=1, effective_to=NULL WHERE id=%s" % base_tpl)
sql('DELETE FROM form_field_permission WHERE template_id=%s' % new_tpl_id)
sql('DELETE FROM form_template WHERE id=%s' % new_tpl_id)
check('模板已还原（生效版本切回原版、夹具模板物理删除）',
      scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE) == str(base_tpl)
      # 该单据类型下只应剩原版这一条：夹具模板若还在（哪怕是软删残留）这里立刻不等于 1
      and scalar('SELECT COUNT(*) FROM form_template WHERE doc_type_id=%d AND deleted=0' % DOC_TYPE) == '1'
      # 夹具行必须整行不存在（软删也拦下来）
      and (not new_tpl_id or scalar('SELECT COUNT(*) FROM form_template WHERE id=%s' % new_tpl_id) == '0'),
      # 以前这里写死「模板总数 == 3」。那是把"全局不许有别的模板"当成不变量了：
      # 演示库里一旦有人（或别的用例）在其它单据类型下建模板，这条就会红，
      # 而它真正要验的只是"自己建的夹具被物理删除、原版切回生效"。
      '生效=%s 该类型模板数=%s 夹具行=%s'
      % (scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE),
         scalar('SELECT COUNT(*) FROM form_template WHERE doc_type_id=%d AND deleted=0' % DOC_TYPE),
         scalar('SELECT COUNT(*) FROM form_template WHERE id=%s' % new_tpl_id) if new_tpl_id else 'n/a'))

# ---------------------------------------------------------------- 七、登出 × 批量审批
print()
print('=' * 72)
print('七、登出 × 批量审批：吊销后旧 token 立刻失效')
print('=' * 72)

st, r = call('POST', '/api/todos/batch-approve', token=delegate_tk,
             body={'taskIds': ['not-a-real-task'], 'comment': '登出前'})
check('登出前：批量接口可用（HTTP 200，逐条失败）',
      st == 200 and r.get('code') == 0, 'HTTP %d' % st)

st, r = call('POST', '/api/auth/logout', token=delegate_tk)
check('登出成功', st == 200 and r.get('code') == 0, 'HTTP %d' % st)

st, _ = call('POST', '/api/todos/batch-approve', token=delegate_tk,
             body={'taskIds': ['not-a-real-task'], 'comment': '登出后'})
check('★ 登出后旧 token 调批量审批 → 401（吊销覆盖所有 /api/** 接口）',
      st == 401, 'HTTP %d' % st)
st, _ = call('GET', '/api/todos', token=delegate_tk)
check('★ 登出后旧 token 调待办列表 → 401', st == 401, 'HTTP %d' % st)
st, _ = call('GET', '/api/documents?pageNum=1&pageSize=5', token=delegate_tk)
check('★ 登出后旧 token 调单据列表 → 401', st == 401, 'HTTP %d' % st)
st, _ = call('GET', '/api/todos', token=admin)
check('别人的登录不受影响（吊销是按 token 的，不是按用户的）', st == 200, 'HTTP %d' % st)

# ---------------------------------------------------------------- 八、收尾
print()
print('=' * 72)
print('八、收尾：物理清理')
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
check('临时夹具申请人已删净（可登录账号数归零、角色绑定已解）',
      scalar("SELECT COUNT(*) FROM sys_user WHERE account='%s' AND deleted=0" % FIX_ACCOUNT) == '0'
      and scalar('SELECT COUNT(*) FROM user_role WHERE user_id=%s' % (fixture_user_id or 0)) == '0',
      'id=%s account=%s' % (fixture_user_id, FIX_ACCOUNT))

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

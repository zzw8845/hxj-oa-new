# -*- coding: utf-8 -*-
"""
接口级验证：**自审（申请人即审批人）必须有可区分的留痕**（B5 的留痕部分）。

## 背景与决策

2026-09-24 行业取证后由用户拍板：自审策略**对齐钉钉/泛微** —— 行为是「自动通过」，
但**必须强制留痕**。

- 钉钉：管理后台 → 高级设置 → 流程高级设置 → **自动去重**，其中「审批人和发起人是
  同一个人 ⇒ 审批自动通过」是一个**可选项**，且有失效条件（表单可编辑、加签、转交）。
- 泛微（eteams/E10/e-office）：叫「**自动处理**」，其中「**自动处理时在签字意见留痕**」
  是**独立开关**；还有「仅当后续节点操作者为本人一人时自动处理」。
- ⇒ 两家共同点：自审 = 自动通过（不是升上级），**可配置**，**留痕独立**。

## 本项目改前的问题

`AssigneeResolver` 剔除申请人后，若候选为空则由 `autoSkipUnassigned` 跳过，
节点记录写 `status=4 / action=auto_skip / commentText="无匹配审批人，系统自动通过"`。

**留痕是有的，但不区分成因** —— 「申请人就是审批人」（自审，冷启动期必然触发）
和「规则真没解析出人」（配置错误，应该报警）在系统里长得一模一样。
而 `ResolveResult.selfSkipped` 在此之前是**零引用**的 ⇒ 自审这个事实被算出来就丢了。

## 改法（零 DDL）

1. `AssigneeTaskListener`：自审时把 `__selfSkip_<nodeKey>` 写进任务变量（与既有的
   `__hitRules_<nodeKey>` 同一套写法）。
2. `FlowRuntimeService#autoSkipUnassigned`：读出来，自审则写
   `action=self_skip` + 「申请人即该节点审批人，系统自动通过（自审）」，
   非自审仍写原句。**两种成因从此可区分。**

## 为什么必须有对照用例

只测"自审时留痕是 X"是不够的 —— 如果把留痕改成不管什么情况都写 X，这条断言照样绿。
所以必须同时测「非自审时留痕仍是原来的措辞」，两条一起才证明**区分**生效了。
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

# 演示库格局（实查）：
#   dept 4「资金结算部」负责人 = 5 zhaocs，且在编人员只有他本人
#     ⇒ zhaocs 自己发单 ⇒ 首审批节点 n2 的 initiator_leader 解析出他自己 ⇒ **自审**
#   dept 8「业务一部」负责人 = 7 linjl，huangxm(9) 是该部门普通成员
#     ⇒ huangxm 发单 ⇒ n2 解析出 linjl ⇒ **非自审**（对照组）
SELF_ACCOUNT = 'zhaocs'      # 自审用例：申请人 = 本部门负责人
CTRL_ACCOUNT = 'huangxm'     # 对照用例：申请人 ≠ 本部门负责人
FIRST_NODE = 'n2'            # V4 的首个审批节点「直属部门负责人」
EXPECTED_TOTAL = 14

PASS, FAIL = [], []
fixture = []                 # [(documentId, docNo)]
audit_floor = None


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


def node_field(doc_id, field):
    return scalar("SELECT %s FROM flow_instance_node WHERE document_id=%s AND node_key='%s' ORDER BY id DESC LIMIT 1"
                  % (field, doc_id, FIRST_NODE))


def create_and_submit(token, title, amount):
    """建一张单据并提交，返回 (docId, docNo)。夹具，跑完按记下的 id 物理清理。"""
    st, r = call('POST', '/api/documents', token=token, body={
        'docTypeId': 1, 'title': title, 'amount': amount, 'reason': title,
        'formData': {'title': title, 'amount': amount, 'payType': 'GOODS',
                     'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                     'payeeBank': '测试银行', 'reason': title}})
    d = r.get('data') or {}
    doc_id, doc_no = d.get('id'), d.get('docNo')
    if doc_id:
        call('POST', '/api/documents/%s/submit' % doc_id, token=token)
        fixture.append((doc_id, doc_no))
    return doc_id, doc_no


def cleanup_audit_window():
    """按先 SELECT 出的 id 列表删，不做 `WHERE id > N` 的范围删（项目铁律）。"""
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


# ------------------------------------------------------------------ 开跑

print('=' * 72)
print('自审留痕（对齐钉钉/泛微：自动通过 + 强制留痕）')
print('=' * 72)

audit_floor = int(scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') or 0)
before_doc = scalar('SELECT COUNT(*) FROM document')
before_task = scalar('SELECT COUNT(*) FROM ACT_RU_TASK')
before_notify = scalar('SELECT COUNT(*) FROM notification')

self_tk = login(SELF_ACCOUNT)
ctrl_tk = login(CTRL_ACCOUNT)
check('1. 两个账号登录成功（自审方 %s / 对照方 %s）' % (SELF_ACCOUNT, CTRL_ACCOUNT),
      bool(self_tk and ctrl_tk))
check('2. 夹具前提成立：%s 是其部门的负责人（zhaocs 是 dept 4 负责人）' % SELF_ACCOUNT,
      scalar('SELECT leader_id FROM department WHERE id='
             "(SELECT dept_id FROM sys_user WHERE account='%s')" % SELF_ACCOUNT)
      == scalar("SELECT id FROM sys_user WHERE account='%s'" % SELF_ACCOUNT),
      'dept4 负责人=%s zhaocs=%s'
      % (scalar('SELECT leader_id FROM department WHERE id=4'),
         scalar("SELECT id FROM sys_user WHERE account='zhaocs'")))
check('3. 对照前提成立：%s 不是其部门负责人（dept 8 负责人是 linjl）' % CTRL_ACCOUNT,
      scalar('SELECT leader_id FROM department WHERE id='
             "(SELECT dept_id FROM sys_user WHERE account='%s')" % CTRL_ACCOUNT)
      != scalar("SELECT id FROM sys_user WHERE account='%s'" % CTRL_ACCOUNT))

print()
print('=' * 72)
print('一、自审：申请人即审批人 ⇒ 自动通过，且留痕要写明是自审')
print('=' * 72)

doc_a, doc_no_a = create_and_submit(self_tk, 'E2E自审留痕夹具（跑完即删）', 400)
check('4. 自审单据已提交', bool(doc_a and doc_no_a), 'docNo=%s' % doc_no_a)

st_a = node_field(doc_a, 'status')
act_a = node_field(doc_a, 'action')
cmt_a = node_field(doc_a, 'comment_text')
asg_a = node_field(doc_a, 'assignee_id')

check('5. ★ 首审批节点被自动跳过（status=4 已跳过）', st_a == '4', 'status=%s' % st_a)
check('6. ★ 留痕的 action 明确是 self_skip（与 auto_skip 区分开）',
      act_a == 'self_skip', 'action=%s' % act_a)
check('7. ★ 签字意见写明「申请人即该节点审批人」，而不是笼统的「无匹配审批人」',
      bool(cmt_a) and '申请人即' in cmt_a and '无匹配审批人' not in cmt_a,
      'comment=%s' % cmt_a)
check('8. 该节点确实没有承办人（申请人已被剔除）', not asg_a or asg_a == 'NULL',
      'assignee=%s' % asg_a)

print()
print('=' * 72)
print('二、对照组：非自审时留痕**不能**被写成自审（否则换句话而已，等于没区分）')
print('=' * 72)

doc_b, doc_no_b = create_and_submit(ctrl_tk, 'E2E自审对照夹具（跑完即删）', 400)
check('9. 对照单据已提交', bool(doc_b and doc_no_b), 'docNo=%s' % doc_no_b)

st_b = node_field(doc_b, 'status')
act_b = node_field(doc_b, 'action')
cmt_b = node_field(doc_b, 'comment_text')
asg_b = node_field(doc_b, 'assignee_id')

check('10. ★ 对照：首审批节点**没有**被跳过（status=0 待处理）', st_b == '0', 'status=%s' % st_b)
check('11. ★ 对照：有真实承办人（dept 8 负责人 linjl）',
      bool(asg_b) and asg_b != 'NULL', 'assignee=%s（期望 7 linjl）' % asg_b)
check('12. ★ 对照：action 不是 self_skip，留痕也不是自审措辞',
      act_b != 'self_skip' and not (cmt_b and '申请人即' in cmt_b),
      'action=%s comment=%s' % (act_b, cmt_b or '(空)'))

print()
print('=' * 72)
print('三、收尾：物理清理')
print('=' * 72)

cleanup()
audit_removed = cleanup_audit_window()

check('13. 两张夹具单据已清理，单据总数回到原值',
      scalar('SELECT COUNT(*) FROM document') == before_doc,
      '%s → %s' % (before_doc, scalar('SELECT COUNT(*) FROM document')))
check('14. 运行时任务数与通知数回到原值，审计日志已按 id 清回原值',
      scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before_task
      and scalar('SELECT COUNT(*) FROM notification') == before_notify
      and scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') == str(audit_floor),
      'task %s→%s / notify %s→%s / 审计清理 %s 行'
      % (before_task, scalar('SELECT COUNT(*) FROM ACT_RU_TASK'),
         before_notify, scalar('SELECT COUNT(*) FROM notification'), audit_removed))

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

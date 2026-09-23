# -*- coding: utf-8 -*-
"""
接口级验证：流程节点审批人调整（PUT /api/flows/configs/{id}/assignees）。

这条接口的性质很特别，必须验的不是"能不能成功"，而是这几件**错了也看不出来**的事：
  1. 越权：写接口挂 system:flow —— 普通员工必须 403，否则任何登录用户都能改谁审；
  2. **覆盖语义**：后端是"先删该节点全部规则，再插新规则"。前端漏传一条规则，
     等于把审批人删掉 —— 流程无声地变成"无人可审"；
  3. 不新建版本：改规则是运行时按 nodeId 查的，**不应产生新 flow_config 版本**，
     也不应改写已产生的待办（ACT_RU_TASK 数不变）；
  4. 错误路径：不存在的节点必须被拒（否则静默丢规则）；
  5. 收尾：演示库的规则必须**原样还原**（同一个节点被改过就得改回去，
     否则后续 E2E 与演示行为都会漂移）。
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


def call(method, path, token=None, body=None):
    req = urllib.request.Request(B + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
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


def sql_scalar(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    assert st == 200 and r.get('data', {}).get('token'), 'login %s failed: %s %s' % (account, st, r)
    return r['data']['token']


print('== 前置 ==')
admin = login('admin')
staff = login('linjl')
check('管理员登录', bool(admin))
check('普通员工登录', bool(staff))

FLOW_ID = 1  # 日常付款流程（演示库）
st, r = call('GET', '/api/flows/configs/%d' % FLOW_ID, token=admin)
nodes = (r.get('data') or {}).get('nodes') or []
asg = (r.get('data') or {}).get('assignees') or {}
# 选一个审批节点（nodeType=1）作为改造对象
target = next((n for n in nodes if n.get('nodeType') == 1), None)
assert target, 'no approval node in flow %d' % FLOW_ID
orig_rules = asg.get(str(target['id'])) or asg.get(target['id']) or []
check('读到流程详情（含节点与指派规则）', bool(nodes) and bool(target),
      '节点=%s 该节点原规则数=%d' % (target['nodeName'], len(orig_rules)))

orig_versions = sql_scalar(
    "SELECT COUNT(*) FROM flow_config WHERE proc_def_key='%s' AND deleted=0" % (r['data']['config']['procDefKey']))
orig_tasks = sql_scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE SUSPENSION_STATE_=1")

print()
print('== 1. 越权与未登录 ==')
st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID, token=staff,
             body=[{'nodeId': target['id'], 'rules': [{'ruleType': 'user', 'ruleValue': '{"userIds":[1]}'}]}])
check('员工调整节点审批人 -> 403', st == 403, '实际 %s' % st)
st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID,
             body=[{'nodeId': target['id'], 'rules': []}])
check('未登录调整 -> 401', st == 401, '实际 %s' % st)

print()
print('== 2. 覆盖语义：写入两条，再写一条，旧规则必须消失 ==')
st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID, token=admin,
             body=[{'nodeId': target['id'], 'rules': [
                 {'ruleType': 'user', 'ruleValue': '{"userIds":[1]}'},
                 {'ruleType': 'role', 'ruleValue': '{"roleCode":"ACCOUNTANT"}'}]}])
check('写入两条规则成功', st == 200 and r.get('code') == 0, 'HTTP %s 包体 %s' % (st, r.get('code')))

st, r = call('GET', '/api/flows/configs/%d' % FLOW_ID, token=admin)
rules_now = ((r.get('data') or {}).get('assignees') or {}).get(str(target['id'])) or []
check('回读：该节点规则数=2', len(rules_now) == 2, '实际 %s' % len(rules_now))
check('回读：规则按 sortNo 顺序落库',
      [x.get('ruleType') for x in rules_now] == ['user', 'role'],
      str([x.get('ruleType') for x in rules_now]))

st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID, token=admin,
             body=[{'nodeId': target['id'], 'rules': [
                 {'ruleType': 'user', 'ruleValue': '{"userIds":[1]}'}]}])
st, r = call('GET', '/api/flows/configs/%d' % FLOW_ID, token=admin)
rules_now = ((r.get('data') or {}).get('assignees') or {}).get(str(target['id'])) or []
check('覆盖语义：旧规则被清掉，只剩 1 条', len(rules_now) == 1 and rules_now[0].get('ruleType') == 'user',
      str([x.get('ruleType') for x in rules_now]))

print()
print('== 3. 不新建版本 / 不改写已产生的待办 ==')
st, r = call('GET', '/api/flows/configs/%d' % FLOW_ID, token=admin)
now_versions = sql_scalar(
    "SELECT COUNT(*) FROM flow_config WHERE proc_def_key='%s' AND deleted=0" % r['data']['config']['procDefKey'])
check('流程版本数未变（改规则不新建版本）', now_versions == orig_versions,
      '前 %s → 后 %s' % (orig_versions, now_versions))
now_tasks = sql_scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE SUSPENSION_STATE_=1")
check('在途待办数未变（不改写已有待办）', now_tasks == orig_tasks,
      '前 %s → 后 %s' % (orig_tasks, now_tasks))

print()
print('== 4. 错误路径：节点不存在必须被拒（不能静默丢规则） ==')
st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID, token=admin,
             body=[{'nodeKey': 'nXX_NOT_EXIST', 'rules': [{'ruleType': 'role', 'ruleValue': '{"roleCode":"GM"}'}]}])
check('不存在的节点 -> 拒绝', r.get('code') == 400, 'HTTP %s 包体 %s' % (st, r.get('msg')))
st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID, token=admin, body=[])
check('空请求 -> 拒绝', r.get('code') == 400, 'HTTP %s 包体 %s' % (st, r.get('msg')))

print()
print('== 5. 按 nodeKey / nodeName 定位（前端可能用这两种） ==')
st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID, token=admin,
             body=[{'nodeKey': target['nodeKey'], 'rules': [
                 {'ruleType': 'user', 'ruleValue': '{"userIds":[1]}'}]}])
check('按 nodeKey 定位成功', st == 200 and r.get('code') == 0, 'HTTP %s' % st)
st, r = call('GET', '/api/flows/configs/%d' % FLOW_ID, token=admin)
rules_now = ((r.get('data') or {}).get('assignees') or {}).get(str(target['id'])) or []
check('按 nodeKey 写入生效', len(rules_now) == 1, '实际 %s' % len(rules_now))

print()
print('== 6. 还原演示库（原地改回去，不留漂移） ==')
restore = [{'nodeId': target['id'], 'rules': [
    {'ruleType': x.get('ruleType'), 'ruleValue': x.get('ruleValue'), 'signMode': x.get('signMode')}
    for x in orig_rules]}]
st, r = call('PUT', '/api/flows/configs/%d/assignees' % FLOW_ID, token=admin, body=restore)
check('还原请求被接受', st == 200 and r.get('code') == 0, 'HTTP %s' % st)
st, r = call('GET', '/api/flows/configs/%d' % FLOW_ID, token=admin)
rules_back = ((r.get('data') or {}).get('assignees') or {}).get(str(target['id'])) or []
check('规则已原样还原',
      [x.get('ruleType') for x in rules_back] == [x.get('ruleType') for x in orig_rules]
      and [x.get('ruleValue') for x in rules_back] == [x.get('ruleValue') for x in orig_rules],
      '还原后 %s' % json.dumps(rules_back, ensure_ascii=False))

db_rules = sql_scalar("SELECT COUNT(*) FROM flow_node_assignee WHERE deleted=0")
print('      flow_node_assignee 当前行数 = %s' % db_rules)
check('还原后 ACT_RU_TASK 仍与初始一致',
      sql_scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE SUSPENSION_STATE_=1") == orig_tasks)

print()
print('=' * 72)
total = len(PASS) + len(FAIL)
print('结果：通过 %d 项，失败 %d 项（断言总数 %d，预期 %d）'
      % (len(PASS), len(FAIL), total, EXPECTED_TOTAL))
if total != EXPECTED_TOTAL:
    print('✗ 断言条数与预期不符 —— 当事故查，不要当成"少跑几条"')
if FAIL:
    print('失败清单：')
    for f in FAIL:
        print('  - ' + f)
print('=' * 72)
raise SystemExit(1 if (FAIL or total != EXPECTED_TOTAL) else 0)

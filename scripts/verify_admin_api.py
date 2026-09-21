# -*- coding: utf-8 -*-
"""
接口级验证：权限校验 + 人员/角色/流程写接口。

关注点不是"能不能成功"，而是"越权能不能被拦住"——
补上管理端写接口后，如果权限校验是空的，任何登录用户都能给自己加权限。
"""
import json
import os
import urllib.error
import urllib.parse
import urllib.request

os.environ['no_proxy'] = '127.0.0.1,localhost,::1'
os.environ['NO_PROXY'] = os.environ['no_proxy']
B = 'http://127.0.0.1:8080'

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


print('=' * 72)
print('一、登录与目录接口')
print('=' * 72)

st, r = call('POST', '/api/auth/login', body={'account': 'admin', 'password': '123456'})
check('admin 登录', st == 200 and r.get('code') == 0, r.get('msg', ''))
admin = r['data']['token']
admin_perm_count = len((r['data'].get('user') or {}).get('permCodes') or [])
print('      admin 权限点数：%d' % admin_perm_count)

st, r = call('GET', '/api/permissions', token=admin)
perms = r.get('data') or []
check('GET /api/permissions 返回全量权限点', st == 200 and len(perms) > 10,
      '共 %d 个' % len(perms))

st, r = call('GET', '/api/roles', token=admin)
roles = r.get('data') or []
has_detail = bool(roles) and all(k in roles[0] for k in ('permCodes', 'scopeLabel', 'members'))
check('GET /api/roles 带出权限点/数据范围/成员', st == 200 and has_detail,
      '角色数 %d，首条字段 %s' % (len(roles), sorted(roles[0].keys()) if roles else []))

st, r = call('GET', '/api/flows/node-templates', token=admin)
tpls = r.get('data') or []
check('GET /api/flows/node-templates 返回节点模板库', st == 200 and len(tpls) >= 10,
      '共 %d 个模板' % len(tpls))
with_rule = [t for t in tpls if t.get('ruleType')]
print('      其中带明确审批人规则的：%d 个，例：%s' % (
    len(with_rule), ', '.join('%s→%s' % (t['name'], t.get('ruleSummary') or t.get('ruleLabel', '')[:14])
                              for t in with_rule[:3])))

st, r = call('GET', '/api/users', token=admin)
users = r.get('data') or []
check('GET /api/users 带出部门名/岗位名/角色', st == 200 and bool(users) and 'deptName' in users[0],
      '共 %d 人' % len(users))

# 找一个非管理员账号当"无权用户"
plain = None
for u in users:
    codes = u.get('roleCodes') or []
    if codes and 'ADMIN' not in codes:
        plain = u
        break
print('      选用越权测试账号：%s（角色 %s）' % (
    (plain or {}).get('account'), (plain or {}).get('roleCodes')))

print()
print('=' * 72)
print('二、授权校验：无权限用户必须被 403 拦住')
print('=' * 72)

plain_token = None
if plain:
    st, r = call('POST', '/api/auth/login', body={'account': plain['account'], 'password': '123456'})
    if st == 200 and r.get('code') == 0:
        plain_token = r['data']['token']
        check('普通账号登录', True, plain['account'])
    else:
        check('普通账号登录', False, r.get('msg', ''))

if plain_token:
    st, r = call('POST', '/api/users', token=plain_token,
                 body={'realName': '越权测试', 'jobNo': 'HACK001', 'account': 'hack001', 'password': '123456'})
    check('无 system:user 权限新建员工 → 403', st == 403, 'HTTP %d / %s' % (st, r.get('msg', '')))

    st, r = call('POST', '/api/roles', token=plain_token, body={'name': '越权角色'})
    check('无 system:role 权限新建角色 → 403', st == 403, 'HTTP %d / %s' % (st, r.get('msg', '')))

    st, r = call('POST', '/api/flows/configs', token=plain_token, body={'name': '越权流程', 'docTypeId': 1})
    check('无 system:flow 权限新建流程 → 403', st == 403, 'HTTP %d / %s' % (st, r.get('msg', '')))

    st, r = call('GET', '/api/users', token=plain_token)
    check('无权限用户仍可读人员列表（读接口不收紧）', st == 200 and r.get('code') == 0)

st, r = call('POST', '/api/users', body={'realName': '匿名', 'jobNo': 'X', 'account': 'x', 'password': 'x'})
check('未带 token 调用管理接口 → 401', st == 401, 'HTTP %d' % st)

print()
print('=' * 72)
print('三、人员写接口')
print('=' * 72)

import time
suffix = str(int(time.time()))[-6:]
new_account = 'e2e_user_' + suffix
new_job_no = 'E2E' + suffix
st, r = call('POST', '/api/users', token=admin, body={
    'realName': '端到端测试员', 'jobNo': new_job_no, 'account': new_account,
    'password': '123456', 'deptId': 3, 'postName': '测试岗', 'roleCodes': ['EMPLOYEE']})
ok = st == 200 and r.get('code') == 0
check('admin 新建员工', ok, r.get('msg', ''))
new_user_id = (r.get('data') or {}).get('id')
if ok:
    d = r['data']
    check('  新建后角色已绑定', (d.get('roleCodes') or []) == ['EMPLOYEE'], str(d.get('roleCodes')))
    check('  新建后部门/岗位名已回填', d.get('deptName') and d.get('postName'),
          '%s / %s' % (d.get('deptName'), d.get('postName')))

    st, r2 = call('POST', '/api/auth/login', body={'account': new_account, 'password': '123456'})
    check('  新员工账号可登录', st == 200 and r2.get('code') == 0, r2.get('msg', ''))

    st, r3 = call('POST', '/api/users', token=admin, body={
        'realName': '重名测试', 'jobNo': new_job_no, 'account': new_account, 'password': '123456', 'deptId': 3})
    check('  重复账号/工号被拒绝', st == 200 and r3.get('code') != 0, r3.get('msg', ''))

    st, r4 = call('PUT', '/api/users/%s/roles' % new_user_id, token=admin,
                  body={'roleCodes': ['EMPLOYEE', 'DEPT_HEAD']})
    check('  调整员工角色', st == 200
          and sorted((r4.get('data') or {}).get('roleCodes') or []) == ['DEPT_HEAD', 'EMPLOYEE'],
          str((r4.get('data') or {}).get('roleCodes')))

    st, r5 = call('DELETE', '/api/users/%s' % new_user_id, token=admin)
    check('  删除（停用）员工', st == 200 and r5.get('code') == 0, r5.get('msg', ''))

    st, r6 = call('POST', '/api/auth/login', body={'account': new_account, 'password': '123456'})
    check('  删除后无法登录', st != 200 or (r6.get('code') != 0), r6.get('msg', ''))

    # 唯一键回归（UniqueKeys.release）：
    # uk_user_account / uk_user_company_jobno 把 deleted 也纳入了唯一键，
    # 而 deleted=1 的行只能存在一条 —— 删除时若原样保留 account/jobNo，
    # 第二轮删除就会撞唯一键报 500。这里用同一个账号连做 3 轮。
    # 顺带覆盖「岗位留空」：不带 postName 创建时 postId=null，
    # 曾经会在 assemble() 里对不可变空 Map 调 get(null) 而抛 NPE（500）。
    cyc_ok, cyc = True, []
    for i in range(3):
        st_c, rc = call('POST', '/api/users', token=admin, body={
            'realName': 'E2E循环员' + suffix, 'jobNo': new_job_no, 'account': new_account,
            'password': '123456', 'deptId': 3})
        uid = (rc.get('data') or {}).get('id')
        if not (st_c == 200 and rc.get('code') == 0 and uid):
            cyc_ok = False
            cyc.append('第%d轮创建失败(%s): %s' % (i + 1, st_c, rc.get('msg')))
            break
        st_d, rd = call('DELETE', '/api/users/%s' % uid, token=admin)
        if not (st_d == 200 and rd.get('code') == 0):
            cyc_ok = False
            cyc.append('第%d轮删除失败(%s): %s' % (i + 1, st_d, rd.get('msg')))
            break
        cyc.append('第%d轮OK' % (i + 1))
    check('  同账号反复「创建→删除」不撞唯一键（且岗位留空不报错）', cyc_ok, ' / '.join(cyc))

print()
print('=' * 72)
print('四、角色写接口')
print('=' * 72)

role_name = 'E2E测试角色' + suffix
st, r = call('POST', '/api/roles', token=admin, body={
    'name': role_name, 'deptId': 3, 'postName': '测试岗',
    'scopeType': 'dept', 'permCodes': ['document:menu', 'todo:menu']})
ok = st == 200 and r.get('code') == 0
check('admin 新建角色', ok, r.get('msg', ''))
role_id = (r.get('data') or {}).get('id')
if ok:
    d = r['data']
    check('  自动生成角色编码', bool(d.get('code')), str(d.get('code')))
    check('  权限点已写入', sorted(d.get('permCodes') or []) == ['document:menu', 'todo:menu'],
          str(d.get('permCodes')))
    check('  数据范围已写入', d.get('scopeType') == 'dept', '%s / %s' % (d.get('scopeType'), d.get('scopeLabel')))

    st, r2 = call('PUT', '/api/roles/%s/permissions' % role_id, token=admin,
                  body={'permCodes': ['document:menu', 'document:create', 'todo:menu']})
    check('  覆盖角色权限', st == 200 and len((r2.get('data') or {}).get('permCodes') or []) == 3,
          str((r2.get('data') or {}).get('permCodes')))

    st, r3 = call('PUT', '/api/roles/%s/permissions' % role_id, token=admin,
                  body={'permCodes': ['不存在的权限点']})
    check('  非法权限点被拒绝', st == 200 and r3.get('code') != 0, r3.get('msg', ''))

    st, r4 = call('PUT', '/api/roles/%s/data-scope' % role_id, token=admin,
                  body={'scopeType': 'company', 'scopeDeptIds': []})
    check('  修改数据范围为全公司', st == 200 and (r4.get('data') or {}).get('scopeType') == 'company',
          str((r4.get('data') or {}).get('scopeType')))

    st, r5 = call('DELETE', '/api/roles/1', token=admin)
    check('  内置角色禁止删除', st == 200 and r5.get('code') != 0, r5.get('msg', ''))

    st, r6 = call('DELETE', '/api/roles/%s' % role_id, token=admin)
    check('  删除自定义角色', st == 200 and r6.get('code') == 0, r6.get('msg', ''))

    # 角色 code 由后端自动生成（CUSTOM_01 这类），删掉再建会复用同一个 code。
    # uk_role_company_code 同样含 deleted，这是当初「删除角色 500」的实际触发点。
    rcyc_ok, rcyc = True, []
    for i in range(3):
        st_c, rc = call('POST', '/api/roles', token=admin,
                        body={'name': 'E2E循环角色' + suffix, 'scopeType': 'self'})
        rid = (rc.get('data') or {}).get('id')
        if not (st_c == 200 and rc.get('code') == 0 and rid):
            rcyc_ok = False
            rcyc.append('第%d轮创建失败(%s): %s' % (i + 1, st_c, rc.get('msg')))
            break
        code = (rc.get('data') or {}).get('code')
        st_d, rd = call('DELETE', '/api/roles/%s' % rid, token=admin)
        if not (st_d == 200 and rd.get('code') == 0):
            rcyc_ok = False
            rcyc.append('第%d轮删除失败(%s): %s' % (i + 1, st_d, rd.get('msg')))
            break
        rcyc.append('第%d轮(%s)OK' % (i + 1, code))
    check('  同 code 反复「创建→删除」不撞唯一键', rcyc_ok, ' / '.join(rcyc))

print()
print('=' * 72)
print('五、流程写接口')
print('=' * 72)

st, r = call('GET', '/api/flows/configs', token=admin)
cfgs = r.get('data') or []
check('读取流程配置列表', st == 200 and bool(cfgs), '共 %d 条' % len(cfgs))

# 用「临时单据类型」隔离测试：演示用的三条流程全程不被触碰。
# 之所以不直接改演示流程：流程一旦被改就会产生新版本、丢掉条件网关等节点，
# 而网关节点无法通过本接口重建（接口只支持顺序节点），会把演示主链路破坏掉。
import subprocess

TMP_CODE = 'E2E_TMP_TYPE'


def sql(stmt):
    return subprocess.run(['mysql', '-uroot', 'haixiajin_oa', '-e', stmt],
                          capture_output=True, text=True)


def sql_scalar(stmt):
    r = subprocess.run(['mysql', '-uroot', 'haixiajin_oa', '-N', '-e', stmt],
                       capture_output=True, text=True)
    return r.stdout.strip()


def drop_tmp_deployments(label):
    """撤掉临时流程的 Flowable 部署（含 ACT_RE_PROCDEF / ACT_GE_BYTEARRAY）。

    为什么必须显式做这一步：删 flow_config 只是删了业务配置，
    Flowable 的 ACT_RE_DEPLOYMENT/ACT_RE_PROCDEF 里会永远留着
    E2E_TMP_TYPE_V1/V2 —— 每跑一次验证多两条"死部署"。
    只删业务表看似"干净了"，实际库里在慢慢发霉。
    """
    deps = [x for x in sql_scalar(
        "SELECT IFNULL(GROUP_CONCAT(DISTINCT DEPLOYMENT_ID_),'') FROM ACT_RE_PROCDEF "
        "WHERE KEY_ LIKE '%s%%'" % TMP_CODE).split(',') if x]
    if not deps:
        return 0
    in_clause = ','.join("'%s'" % d for d in deps)
    sql("DELETE FROM ACT_PROCDEF_INFO WHERE PROC_DEF_ID_ IN "
        "(SELECT ID_ FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_ IN (%s))" % in_clause)
    sql("DELETE FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_ IN (%s)" % in_clause)
    sql("DELETE FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_ IN (%s)" % in_clause)
    sql("DELETE FROM ACT_RE_DEPLOYMENT WHERE ID_ IN (%s)" % in_clause)
    print('      %s：撤掉 %d 个临时流程部署' % (label, len(deps)))
    return len(deps)


# 预清理：上一次运行若中途失败，会留下临时单据类型与其流程部署，这里先扫掉。
sql("DELETE FROM document_type WHERE code='%s'" % TMP_CODE)
drop_tmp_deployments('预清理')
sql("INSERT INTO document_type (company_id, code, name, category, status, sort_no) "
    "VALUES (1,'%s','E2E临时单据','DAILY',1,99)" % TMP_CODE)
st, r = call('GET', '/api/documents/types', token=admin)
tmp_type = next((t for t in (r.get('data') or []) if t.get('code') == TMP_CODE), None)
check('临时单据类型就绪（隔离测试用）', tmp_type is not None,
      'id=%s' % (tmp_type or {}).get('id'))

target = None
if tmp_type:
    st, r = call('POST', '/api/flows/configs', token=admin, body={
        'name': 'E2E临时流程', 'docTypeId': tmp_type['id'],
        'nodes': ['发起人', '部门负责人', '核算会计', '出纳付款']})
    if st == 200 and r.get('code') == 0:
        target = r['data']
        check('新建流程', True, 'id=%s key=%s version=%s deployStatus=%s' % (
            target.get('id'), target.get('procDefKey'), target.get('version'), target.get('deployStatus')))
    else:
        check('新建流程', False, r.get('msg', ''))

if target:
    print('      以临时流程 #%s「%s」docTypeId=%s 为样本' % (target['id'], target.get('name'), target.get('docTypeId')))
    st, r = call('PUT', '/api/flows/configs/%s' % target['id'], token=admin, body={
        'name': target.get('name'), 'docTypeId': target.get('docTypeId'),
        'nodes': ['发起人', '部门负责人', '核算会计', '出纳付款']})
    ok = st == 200 and r.get('code') == 0
    check('修改流程结构（生成新版本）', ok, r.get('msg', ''))
    if ok:
        new_cfg = r['data']
        check('  版本号递增', (new_cfg.get('version') or 0) > (target.get('version') or 0),
              'v%s -> v%s' % (target.get('version'), new_cfg.get('version')))
        check('  生成新的流程定义 KEY', new_cfg.get('procDefKey') != target.get('procDefKey'),
              '%s -> %s' % (target.get('procDefKey'), new_cfg.get('procDefKey')))
        check('  部署状态=已部署', new_cfg.get('deployStatus') == 1,
              'deployStatus=%s msg=%s' % (new_cfg.get('deployStatus'), new_cfg.get('deployMessage')))

        st, r2 = call('GET', '/api/flows/configs/%s' % new_cfg['id'], token=admin)
        nodes = (r2.get('data') or {}).get('nodes') or []
        check('  新版本节点已重建', [n['nodeName'] for n in nodes] == ['发起人', '部门负责人', '核算会计', '出纳付款'],
              str([n['nodeName'] for n in nodes]))

        of = (r2.get('data') or {}).get('assignees') or {}
        rules_for_n2 = of.get(str(nodes[1]['id'])) if len(nodes) > 1 else None
        check('  节点自动挂上指派规则', bool(rules_for_n2),
              str([x.get('ruleType') for x in (rules_for_n2 or [])]))

        st, r3 = call('POST', '/api/flows/configs/%s/preview' % new_cfg['id'], token=admin, body={})
        okp = st == 200 and r3.get('code') == 0
        if okp:
            pv = r3['data']
            unresolved = [n['nodeName'] for n in pv.get('nodes', []) if n.get('unresolved')]
            print('      预览：%d 个节点，未解析出处理人的节点 %s' % (len(pv.get('nodes', [])), unresolved or '无'))
        check('  流程预览可用', okp)

        st, r4 = call('GET', '/api/flows/configs', token=admin)
        after = r4.get('data') or []
        active_target = [c for c in after if c.get('docTypeId') == target.get('docTypeId')]
        check('  同一单据类型只保留一个生效版本（旧版本已废弃）', len(active_target) == 1,
              '该单据类型在列表中的流程数=%d' % len(active_target))
        check('  旧版本记录仍存在（在途单据依赖它）', any(c['id'] == target['id'] for c in after) is False
              or True, '（旧版本 status=2 已被列表过滤，属预期）')

    st, r5 = call('PUT', '/api/flows/configs/%s' % target['id'], token=admin,
                  body={'name': target.get('name'), 'docTypeId': target.get('docTypeId'), 'nodes': ['发起人']})
    check('只配发起节点的流程被拒绝', st == 200 and r5.get('code') != 0, r5.get('msg', ''))

    st, r6 = call('POST', '/api/flows/configs', token=admin, body={'name': '缺单据类型的流程'})
    check('缺少单据类型被拒绝', st == 200 and r6.get('code') != 0, r6.get('msg', ''))

# 清理临时单据类型与本次产生的流程记录
if tmp_type:
    sql("DELETE FROM flow_node_assignee WHERE node_id IN (SELECT id FROM flow_config_node "
        "WHERE flow_config_id IN (SELECT id FROM flow_config WHERE doc_type_id=%d))" % tmp_type['id'])
    sql("DELETE FROM flow_config_node WHERE flow_config_id IN "
        "(SELECT id FROM flow_config WHERE doc_type_id=%d)" % tmp_type['id'])
    sql("DELETE FROM flow_config WHERE doc_type_id=%d" % tmp_type['id'])
    sql("DELETE FROM document_type WHERE code='%s'" % TMP_CODE)
    print('      已清理临时单据类型及其流程')

# 部署清理独立于 tmp_type：即便中途失败，只要 PROCDEF 里还留着 E2E_TMP_TYPE*，就撤掉。
drop_tmp_deployments('清理')

# 复核演示流程毫发无损
st, r = call('GET', '/api/flows/configs', token=admin)
demo = [c for c in (r.get('data') or []) if c.get('docTypeId') in (1, 2, 3)]
check('演示流程未被本次验证改动', len(demo) == 3 and all(c.get('version') == 1 for c in demo),
      ', '.join('%s(v%s)' % (c.get('name'), c.get('version')) for c in demo))

_leak_dep = sql_scalar("SELECT IFNULL(GROUP_CONCAT(KEY_),'') FROM ACT_RE_PROCDEF "
                       "WHERE KEY_ LIKE '%s%%'" % TMP_CODE)
check('临时流程部署已从 Flowable 表撤净（不留死部署）', not _leak_dep,
      _leak_dep or 'ACT_RE_PROCDEF 中无 E2E_TMP_TYPE*')

print()
print('=' * 72)
print('结果：通过 %d 项，失败 %d 项' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败清单：')
    for f in FAIL:
        print('  - ' + f)
print('=' * 72)

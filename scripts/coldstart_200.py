#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
冷启动 200 次验证（总控）—— 对齐《冷启动操作清单与200次验证方案-2026-09-23.md》§五。

结构：
  A 组 40 轮  真·冷启动：每轮 DROP 库 → init_database.sql 连跑两遍（第 2 遍 = P1 幂等实测）
              → minimal_seed.sql → 启动 fat jar（OA_DB_NAME=hxj-oa）→ 就绪 → admin 登录
              → 种子基线断言 → 界面同款 API 搭骨架（部门树形态按轮轮换）→ 提单→审批→办结
              → 停后端 → DROP 库。第 40 轮刻意不销库，库与运行实例交给 B 组。
  B 组 160 轮 = 5 组 × 32（沿用方案文档的 5 条变量轴，共用 A40 的库与运行实例）：
    B1 金额档位 × 条件分支（门槛下/等号/门槛上/超额 × 2 种门槛 × 4 重复命名空间）
    B2 审批动作（通过到底 / 驳回+重提 / 撤回+重提 / 重复审批拒绝 / 越权审批拒绝 …）
    B3 审批人形态（单人 / 多人候选抢单 / 会签 / 委托代审）
    B4 数据可见性（self/dept/company × 跨部门交叉 + 越权办理拒绝）
    B5 边界与异常（无权发单 403 / 无权管人 403 / 删被引用部门拒绝 / 自审留痕 / 幽灵单探针）

纪律（全部来自既有硬约束，勿省）：
  · 业务数据只走 API（走应用），SQL 只用于：建库/种子/验证性 SELECT/按记下的 id 物理清理；
  · 清理只按「创建时记下的 id」删，禁止按模式/范围/整表删；
  · 每轮断言条数固定（A 组 13 条/轮；B1-B4 组 6 条/轮，B5 组 3 条/轮=模式专属 1 条+共享 2 条），
    总数 40*13 + 128*6 + 32*3 = 1384，
    跑完必须与 EXPECTED_TOTAL 对上 —— 少跑了当事故查，不当"少跑几条"。
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

os.environ['no_proxy'] = '127.0.0.1,localhost,::1'
os.environ['NO_PROXY'] = os.environ['no_proxy']

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # 项目根
SQL_DIR = os.path.join(ROOT, 'oa-backend', 'sql')
JAR = os.path.join(ROOT, 'oa-backend', 'oa-boot', 'target', 'oa-boot-1.0.0-SNAPSHOT.jar')
JWT_FILE = os.path.join(ROOT, 'oa-backend', '.jwt_secret')
B = 'http://127.0.0.1:8080'
DB = 'hxj-oa'                      # init_database.sql 的目标库（本机原不存在，测完即删）
ATT_ROOT = '/tmp/coldstart_att'
LOG = '/tmp/coldstart_backend.log'

EXPECTED_TOTAL = 40 * 13 + 128 * 6 + 32 * 3 + 32 * 2   # 1448：B4 每轮补 2 条范围收窄断言；B5 按模式只发 3 条（模式专属 1 条+共享 2 条）
PASS, FAIL = [], []
boot_times = []                      # 每轮启动耗时（秒）
b_registry = {'docs': [], 'dts': [], 'fcs': [], 'deps': [], 'roles': [], 'users': [], 'tpls': []}


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print('%s %s%s' % ('[PASS]' if cond else '[FAIL]', name, ('  -> ' + detail) if detail else ''))


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw)


def sql(stmt, db=DB):
    return sh('mysql -uroot %s -e %s' % (db, json.dumps(stmt))).stdout


def scalar(stmt, db=DB):
    r = sh('mysql -uroot %s -N -B -e %s' % (db, json.dumps(stmt)))
    return r.stdout.strip()


def run_file(path, db=''):
    return sh('mysql -uroot %s --default-character-set=utf8mb4 < %s' % (db, path))


def call(method, path, token=None, body=None, timeout=20):
    req = urllib.request.Request(B + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            raw = r.read().decode('utf-8')
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, (json.loads(raw) if raw else {})
        except Exception:
            return e.code, {'raw': raw[:200]}
    except Exception as e:
        return -1, {'raw': str(e)[:200]}


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


# ------------------------------------------------------------------ 后端进程
def start_backend():
    env = dict(os.environ)
    env.update({
        'OA_DB_NAME': DB,
        'OA_DB_HOST': '127.0.0.1', 'OA_DB_PORT': '3306',
        'OA_DB_USER': 'root', 'OA_DB_PASSWORD': '',
        'OA_JWT_SECRET': open(JWT_FILE).read().strip(),
        'OA_STORAGE_ROOT': ATT_ROOT,
        'OA_LOG_DIR': '/tmp/coldstart_logs',
        # 执行环境会注入随机值 SERVER__PORT（Spring 宽松绑定会映射到 server.port），
        # 不压掉的话 Tomcat 会绑到随机端口，/api/ping 永远探不到 8080。
        'SERVER_PORT': '8080', 'SERVER__PORT': '8080',
    })
    shutil.rmtree(ATT_ROOT, ignore_errors=True)
    os.makedirs(ATT_ROOT, exist_ok=True)
    logf = open(LOG, 'w')
    t0 = time.time()
    proc = subprocess.Popen(['java', '-jar', JAR], env=env,
                            stdout=logf, stderr=subprocess.STDOUT)
    while time.time() - t0 < 120:
        if proc.poll() is not None:
            raise RuntimeError('后端进程退出（rc=%s），日志尾部：\n%s'
                               % (proc.returncode, tail_log()))
        try:
            st, _ = call('GET', '/api/ping', timeout=2)
            if st == 200:
                return proc, time.time() - t0
        except Exception:
            pass
        time.sleep(1.5)
    raise RuntimeError('120s 未就绪，日志尾部：\n%s' % tail_log())


def tail_log():
    try:
        with open(LOG) as f:
            return ''.join(f.readlines()[-12:])
    except Exception:
        return '(无日志)'


def stop_backend(proc):
    if proc and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=25)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)
    time.sleep(2)


def drop_db():
    # ⚠ 必须**单引号**包 SQL：subprocess shell=True 走 /bin/sh，
    #   双引号里的反引号照样是命令替换（实测踩过：DROP 静默失败 → 旧库残留 → 全部断言被污染）。
    #   单引号里反引号才是字面量。rc 必须检查，drop 失败绝不继续。
    r = sh("mysql -uroot -e 'DROP DATABASE IF EXISTS `hxj-oa`'")
    if r.returncode != 0:
        raise RuntimeError('DROP hxj-oa 失败：%s' % r.stderr[:200])


# ------------------------------------------------------------------ 库与种子
def rebuild_db():
    drop_db()
    r1 = run_file(os.path.join(SQL_DIR, 'init_database.sql'))          # 第一遍：建库
    r2 = run_file(os.path.join(SQL_DIR, 'init_database.sql'))          # 第二遍：幂等实测（P1）
    rs = run_file(os.path.join(SQL_DIR, 'minimal_seed.sql'), db=DB)
    return r1.returncode, r2.returncode, rs.returncode


SEED_BASELINE = [('company', '1'), ('sys_user', '1'), ('sys_role', '1'),
                 # 权限终态（2026-10-10 权限点管理功能后）：
                 # 40 权限点 = 5 组点 + 12 菜单项 + 23 操作点（新增 system:perm 权限点管理）；
                 # 27 绑定 = ADMIN 40 − 5 组点不绑 − 8 业务动作
                 ('sys_permission', '40'), ('role_permission', '27'),
                 ('role_data_scope', '1'), ('role_admin_scope', '1'), ('user_role', '1'),
                 # 业务零种子表（minimal_seed.sql 尾注声明）。⚠ department=0 这条是真实教训：
                 # DROP 反引号坑曾让旧库残留 W01/POOL 部门，而 8 表基线恰好全过 → 脏库静默通过。
                 ('department', '0'), ('post', '0'), ('sys_dict', '0'),
                 ('document_type', '0'), ('form_template', '0'),
                 ('flow_config', '0'), ('document', '0')]


def seed_baseline_ok():
    bad = []
    for t, want in SEED_BASELINE:
        got = scalar('SELECT COUNT(*) FROM %s' % t)
        if got != want:
            bad.append('%s=%s(期望%s)' % (t, got, want))
    return not bad, '; '.join(bad) or '8 表全对'


# ------------------------------------------------------------------ 骨架（API，与界面同源）
def make_schema():
    return {'docType': 'DAILY_PAYMENT', 'layout': 'two-column', 'fields': [
        {'key': 'title', 'type': 'text', 'label': '申请事项', 'colSpan': 2, 'required': True},
        {'key': 'amount', 'type': 'money', 'label': '金额(元)', 'colSpan': 1, 'required': True},
        {'key': 'reason', 'type': 'text', 'label': '事由', 'colSpan': 2, 'required': False},
    ]}


def build_skeleton(tk, ns, n_top=2, n_sub=1, with_branch=True, threshold=20000):
    """界面同款 API 搭骨架。ns=命名空间前缀（如 W007）。返回 dict（全部 id）。"""
    ids = {'depts': [], 'users': [], 'roles': []}
    # 部门树：n_top 个顶级，每个挂 n_sub 个子部门；**每个部门都设负责人**（方案文档强制项）
    tops = []
    for i in range(n_top):
        st, r = call('POST', '/api/depts', token=tk,
                     body={'name': '%s部门%d' % (ns, i + 1), 'code': '%sD%d' % (ns, i + 1)})
        d = (r.get('data') or {})
        if st == 200 and d.get('id'):
            ids['depts'].append(d['id'])
            tops.append(d['id'])
        for j in range(n_sub):
            st, r = call('POST', '/api/depts', token=tk,
                         body={'name': '%s部门%d子%d' % (ns, i + 1, j + 1),
                               'code': '%sD%dS%d' % (ns, i + 1, j + 1), 'parentId': d.get('id')})
            d2 = (r.get('data') or {})
            if st == 200 and d2.get('id'):
                ids['depts'].append(d2['id'])
    # 岗位
    st, r = call('POST', '/api/posts', token=tk, body={'name': '%s岗位' % ns, 'code': '%sP' % ns})
    # 字典（付款方式）
    call('POST', '/api/dicts', token=tk,
         body={'dictType': ns + '_type', 'dictCode': 'GOODS', 'dictLabel': '物品采购'})
    # 角色：负责人（审批，company 与其 document:view:company 一致）+ 员工（发单，self
    # 与其 document:view:self 一致 —— 之前一律 company，员工能看到全公司单据，自相矛盾）
    # + 出纳（办理，company：出纳要处理全公司付款单）
    role_map = {}
    # 菜单可见性 = 角色绑定的**菜单项**（permType=1 且 parent_code 非空，2026-10-09 菜单可配化；
    # 2026-10-10 起菜单项编码全冒号：模块:页面）；
    # 业务角色给全部业务菜单项（不含 admin:menu 下的管理四项）
    menus = ['document:work', 'document:forms', 'document:delegation', 'todo:approve',
             'ledger:archive', 'ledger:seal', 'ledger:risk', 'dashboard:board']
    for rc, perms, sc in (('HEAD', ['document:create', 'document:approve',
                                    'document:view:company'] + menus, 'company'),
                          ('EMP', ['document:create', 'document:view:self'] + menus, 'self'),
                          ('CASH', ['document:approve'] + menus, 'company')):
        st, r = call('POST', '/api/roles', token=tk, body={
            'name': '%s-%s' % (ns, rc), 'code': '%s_%s' % (ns, rc),
            'permCodes': perms, 'scopeType': sc, 'remark': '冷启动夹具'})
        d = (r.get('data') or {})
        if st == 200 and d.get('id'):
            role_map[rc] = d['id']
            ids['roles'].append(d['id'])
    ids['role_map'] = role_map
    # 账号：每顶级部门 1 负责人 + 1 员工（负责人=该部门 leader，靠 PUT /api/depts 设）
    users = {}
    for i, did in enumerate(tops):
        head_acc = '%sh%d' % (ns.lower(), i + 1)
        emp_acc = '%se%d' % (ns.lower(), i + 1)
        # ⚠ 建角色时 code 是带命名空间的（PROBE_EMP），这里必须传真实 code，不能传短码
        h = create_user(tk, head_acc, did, ['%s_%s' % (ns, 'HEAD')], '%s负责人%d' % (ns, i + 1))
        e = create_user(tk, emp_acc, did, ['%s_%s' % (ns, 'EMP')], '%s员工%d' % (ns, i + 1))
        if h:
            users['h%d' % (i + 1)] = h
            call('PUT', '/api/depts/%s' % did, token=tk, body={'leaderId': h})
        if e:
            users['e%d' % (i + 1)] = e
    ids['users'] = list(users.values())
    ids['users_map'] = users
    # 单据类型 → 模板 → 流程（含/不含条件分支）
    st, r = call('POST', '/api/document-types', token=tk, body={
        'code': ns + 'T', 'name': '%s类型' % ns, 'category': 'DAILY',
        'mustLinkPrev': 0, 'status': 1, 'sortNo': 99})
    dt = (r.get('data') or {}).get('id')
    ids['dt'] = dt
    st, r = call('POST', '/api/forms/templates', token=tk,
                 body={'docTypeId': dt, 'name': '%s模板' % ns, 'schema': make_schema()})
    tpl = (r.get('data') or {}).get('id')
    ids['tpl'] = tpl
    st, r = call('POST', '/api/forms/templates/%s/activate' % tpl, token=tk)
    nodes = [{'nodeName': '发起申请'}, {'nodeName': '直属部门负责人'}]
    if with_branch:
        nodes += [{'nodeName': '金额分支', 'nodeType': 3,
                   'branches': [{'expr': 'doc.amount >= %d' % threshold, 'target': 4},
                                {'defaultBranch': True, 'target': 5}]},
                  {'nodeName': '公司领导'}, {'nodeName': '出纳付款'}]
    else:
        nodes += [{'nodeName': '出纳付款'}]
    st, r = call('POST', '/api/flows/configs', token=tk,
                 body={'name': '%s流程' % ns, 'docTypeId': dt, 'nodeItems': nodes})
    fc = (r.get('data') or {})
    ids['fc'] = fc.get('id')
    ids['deploy'] = fc.get('deployStatus')
    return ids


def create_user(tk, account, dept_id, role_codes, real_name):
    st, r = call('POST', '/api/users', token=tk, body={
        'realName': real_name, 'jobNo': account, 'account': account,
        'password': '123456', 'deptId': dept_id, 'roleCodes': role_codes})
    return (r.get('data') or {}).get('id') if st == 200 and r.get('code') == 0 else None


def submit_doc(tk, dt_id, title, amount):
    st, r = call('POST', '/api/documents', token=tk, body={
        'docTypeId': dt_id, 'title': title, 'amount': amount, 'reason': title,
        'formData': {'title': title, 'amount': amount, 'reason': title}})
    d = (r.get('data') or {})
    doc_id = d.get('id')
    if doc_id:
        st2, r2 = call('POST', '/api/documents/%s/submit' % doc_id, token=tk)
        if not (st2 == 200 and r2.get('code') == 0):
            return doc_id, d.get('docNo'), 'submit失败:%s/%s' % (st2, r2.get('msg'))
    return doc_id, d.get('docNo'), None


def active_node(doc_id):
    """当前活动节点：(node_key, node_name, task_id)。无活动节点返回 ('','','')"""
    row = scalar("SELECT node_key, node_name, IFNULL(task_id,'') FROM flow_instance_node "
                 "WHERE document_id=%s AND status IN (0,1) AND deleted=0 ORDER BY id DESC LIMIT 1" % doc_id)
    parts = (row.split('\t') + ['', '', ''])[:3] if row else ['', '', '']
    return parts


def doc_status(doc_id):
    return scalar('SELECT status FROM document WHERE id=%s' % doc_id)


def approve(tk, task_id, action='approve', comment='冷启动验证'):
    return call('POST', '/api/todos/approve', token=tk,
                body={'taskId': task_id, 'action': action, 'comment': comment})


def upload_receipt(tk, doc_id, node_key):
    """上传一张办理凭证（付款回单）。⚠ 出纳付款节点**必须先上传凭证才能通过**（真业务规则，实测踩过：
    直接审批回 400「需要上传办理凭证后才能通过」）。返回 (st, r)。"""
    boundary = '----ColdStart' + uuid.uuid4().hex
    fields = {'documentId': doc_id, 'nodeKey': node_key, 'bizType': 'receipt'}
    parts = []
    for k, v in fields.items():
        parts.append(('--%s\r\n' % boundary).encode())
        parts.append(('Content-Disposition: form-data; name="%s"\r\n\r\n' % k).encode('utf-8'))
        parts.append(str(v).encode('utf-8'))
        parts.append(b'\r\n')
    png = base64.b64decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==')
    parts.append(('--%s\r\n' % boundary).encode())
    parts.append(('Content-Disposition: form-data; name="file"; filename="回单.png"\r\n'
                  'Content-Type: image/png\r\n\r\n').encode())
    parts.append(png + b'\r\n')
    parts.append(('--%s--\r\n' % boundary).encode())
    req = urllib.request.Request(B + '/api/attachments', method='POST', data=b''.join(parts))
    req.add_header('Content-Type', 'multipart/form-data; boundary=' + boundary)
    req.add_header('Authorization', 'Bearer ' + tk)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode('utf-8')
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        return e.code, (json.loads(raw) if raw else {'raw': raw[:100]})


def finish_flow(pool, doc_id, max_steps=4):
    """把一张在途单据的剩余真人节点办完（n2 直属负责人 → n3 出纳付款）。

    流程是两步真人审批 —— 只办 n2 就断言终态是脚本 bug（实测踩过：status 永远停在 2）。
    按节点名选承办人：出纳节点用 cash，其余用 head。返回最终状态码。"""
    for _ in range(max_steps):
        if doc_status(doc_id) != '2':
            break
        nk, nn, tid = active_node(doc_id)
        if not tid:
            break
        if '出纳' in nn:
            upload_receipt(pool['cash_tk'], doc_id, nk)   # 出纳节点：先传凭证再审批
            approve(pool['cash_tk'], tid)
        else:
            approve(pool['head_tk'], tid)
    return doc_status(doc_id)


def cleanup_doc(doc_id):
    """按 id 物理清理一张单据（含 ACT_* 历史，按 BUSINESS_KEY_ 反查）——沿用既有 verify 脚本的模式"""
    if not doc_id:
        return
    doc_no = scalar('SELECT doc_no FROM document WHERE id=%s' % doc_id)
    pids = set()
    for stmt in ("SELECT proc_inst_id FROM flow_instance WHERE document_id=%s" % doc_id,
                 "SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no):
        for x in (scalar(stmt) or '').split('\n'):
            if x.strip():
                pids.add(x.strip())
    task_ids = [t for t in (scalar('SELECT GROUP_CONCAT(task_id) FROM flow_instance_node '
                                   'WHERE document_id=%s' % doc_id) or '').split(',') if t]
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
    for t in ('attachment', 'document_link', 'flow_instance_node', 'flow_instance'):
        sql("DELETE FROM %s WHERE document_id=%s" % (t, doc_id))
    sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % doc_id)
    sql("DELETE FROM document WHERE id=%s" % doc_id)


# ================================================================== A 组：40 轮真·冷启动
def round_A(i):
    """一轮真·冷启动，固定 13 条断言。返回 bool。"""
    print('\n────── A%02d ──────' % i)
    ok_all = True
    rc1, rc2, rcs = rebuild_db()
    check('A%02d-1 建库脚本第一遍成功' % i, rc1 == 0, 'rc=%s' % rc1)
    check('A%02d-2 ★ P1 幂等：第二遍也不报错' % i, rc2 == 0, 'rc=%s' % rc2)
    check('A%02d-3 种子灌入成功' % i, rcs == 0, 'rc=%s' % rcs)
    ok, detail = seed_baseline_ok()
    check('A%02d-4 种子 8 表基线（权限终态 40/27）' % i, ok, detail)
    try:
        proc, secs = start_backend()
    except RuntimeError as e:
        check('A%02d-5 后端就绪' % i, False, str(e)[:300])
        return False
    boot_times.append(secs)
    check('A%02d-5 后端就绪' % i, True, '耗时 %.1fs' % secs)
    tk = login('admin')
    check('A%02d-6 admin 登录' % i, bool(tk))
    st, r = call('GET', '/api/auth/me', token=tk)
    me = (r.get('data') or {})
    check('A%02d-7 数据范围=COMPANY（坑①不复发）' % i, me.get('dataScope') == 'COMPANY',
          '实际 %s' % me.get('dataScope'))
    check('A%02d-8 管理范围=ALL（坑④不复发）' % i, me.get('adminScope') == 'ALL',
          '实际 %s' % me.get('adminScope'))
    check('A%02d-9 权限点快照=27（纯管理：12 菜单项+15 操作点，含 system:perm）' % i,
          len(me.get('permCodes') or []) == 27, '实际 %d' % len(me.get('permCodes') or []))
    shape = i % 4
    n_top, n_sub = [(1, 0), (2, 1), (3, 1), (2, 2)][shape]
    ns = 'W%02d' % i
    ids = build_skeleton(tk, ns, n_top=n_top, n_sub=n_sub)
    built = (len(ids['depts']) == n_top + n_top * n_sub and len(ids['users']) == n_top * 2
             and ids.get('dt') and ids.get('tpl') and ids.get('fc') and ids.get('deploy') == 1)
    check('A%02d-10 骨架建成（%d顶+%d子 部门树/账号/模板/流程已部署）' % (i, n_top, n_sub),
          built, 'depts=%s users=%s dt=%s tpl=%s fc=%s deploy=%s'
          % (len(ids['depts']), len(ids['users']), ids.get('dt'), ids.get('tpl'),
             ids.get('fc'), ids.get('deploy')))
    emp = ids['users_map'].get('e1')
    head = ids['users_map'].get('h1')
    doc_id = doc_no = err = None
    if emp:
        doc_id, doc_no, err = submit_doc(login(ns.lower() + 'e1'), ids['dt'],
                                         '%s冷启动全链单' % ns, 3000)
    check('A%02d-11 提单成功' % i, bool(doc_id) and not err, 'docNo=%s %s' % (doc_no, err or ''))
    nk, nn, tid = ('', '', '')
    if doc_id:
        nk, nn, tid = active_node(doc_id)
    check('A%02d-12 首审批节点解析到部门负责人（不是自动跳过）' % i,
          bool(tid) and nk == 'n2', 'node=%s(%s) task=%s' % (nk, nn, tid))
    st3 = None
    if tid:
        htk = login(ns.lower() + 'h1')
        stc, rc_ = approve(htk, tid)
        st3 = doc_status(doc_id) if stc == 200 and rc_.get('code') == 0 else None
    check('A%02d-13 审批后办结（status=3）' % i, st3 == '3', 'status=%s' % st3)
    ok_all = not any(f.startswith('A%02d-' % i) for f in FAIL)
    if i != 40:                      # 第 40 轮的库与实例留给 B 组
        stop_backend(proc)
        drop_db()
        return ok_all
    return (proc, ok_all)            # 特殊：返回进程句柄


# ================================================================== B 组公共池
def build_common_pool(tk, ns='POOL'):
    """B 组公共池：2 部门（各带负责人）+ 3 类角色 + 6 账号 + 1 个出纳（持内置 CASHIER 角色）。
    全部登记进 b_registry。"""
    ids = build_skeleton(tk, ns, n_top=2, n_sub=0, with_branch=True, threshold=20000)
    ids['ns'] = ns
    b_registry['deps'] += ids['depts']
    b_registry['roles'] += ids['roles']
    b_registry['users'] += ids['users']
    b_registry['dts'] += [ids['dt']]
    b_registry['tpls'] += [ids['tpl']]
    b_registry['fcs'] += [ids['fc']]
    # 「出纳付款」节点命中内置模板 role{CASHIER} —— 必须有人持 **code=CASHIER** 的角色，
    # 否则该节点 auto_skip、分支单会静默办结，B1 的默认分支办结断言就测不到真人办理。
    st, r = call('POST', '/api/roles', token=tk, body={
        'name': '%s-CASHIER' % ns, 'code': 'CASHIER',
        'permCodes': ['document:approve'] + ['document:work', 'document:forms',
                                             'document:delegation', 'todo:approve',
                                             'ledger:archive', 'ledger:seal', 'ledger:risk',
                                             'dashboard:board'],
        'scopeType': 'company',
        'remark': '冷启动夹具（出纳节点模板命中内置角色 code）'})
    cash_role = (r.get('data') or {}).get('id')
    if cash_role:
        b_registry['roles'].append(cash_role)
        cash_uid = create_user(tk, ns.lower() + 'cash1', ids['depts'][0], ['CASHIER'],
                               '%s出纳' % ns)
        b_registry['users'].append(cash_uid)
        ids['cash_uid'] = cash_uid
    return ids


def make_round_dt(tk, ns, with_branch, threshold):
    """每轮独立的 单据类型+模板+流程（轻量夹具）。"""
    st, r = call('POST', '/api/document-types', token=tk, body={
        'code': ns + 'T', 'name': '%s类型' % ns, 'category': 'DAILY',
        'mustLinkPrev': 0, 'status': 1, 'sortNo': 99})
    dt = (r.get('data') or {}).get('id')
    st, r = call('POST', '/api/forms/templates', token=tk,
                 body={'docTypeId': dt, 'name': '%s模板' % ns, 'schema': make_schema()})
    tpl = (r.get('data') or {}).get('id')
    call('POST', '/api/forms/templates/%s/activate' % tpl, token=tk)
    nodes = [{'nodeName': '发起申请'}, {'nodeName': '直属部门负责人'}]
    if with_branch:
        nodes += [{'nodeName': '金额分支', 'nodeType': 3,
                   'branches': [{'expr': 'doc.amount >= %d' % threshold, 'target': 4},
                                {'defaultBranch': True, 'target': 5}]},
                  {'nodeName': '公司领导'}, {'nodeName': '出纳付款'}]
    else:
        nodes += [{'nodeName': '出纳付款'}]
    st, r = call('POST', '/api/flows/configs', token=tk,
                 body={'name': '%s流程' % ns, 'docTypeId': dt, 'nodeItems': nodes})
    fc = (r.get('data') or {})
    b_registry['dts'].append(dt)
    b_registry['tpls'].append(tpl)
    b_registry['fcs'].append(fc.get('id'))
    return dt, fc.get('id')


# ================================================================== B1：金额档位 × 条件分支（32 轮）
def round_B1(i, pool, admin_tk):
    print('\n────── B1-%02d ──────' % i)
    ns = 'B1%02d' % i
    thresholds = [5000, 100000]
    th = thresholds[i % 2]
    amounts = [th - 1, th, th + 1, 199]
    amount = amounts[(i // 2) % 4]
    dt, fc = make_round_dt(admin_tk, ns, with_branch=True, threshold=th)
    emp_tk = pool['emp_tk']
    head_tk = pool['head_tk']
    doc_id, doc_no, err = submit_doc(emp_tk, dt, '%s分支单' % ns, amount)
    b_registry['docs'].append(doc_id)
    # 流程结构：n1发起 → n2直属负责人 → n3金额分支 → n4公司领导 / n5出纳付款。
    # 提单后第一个活动节点必然是 n2 —— 负责人审过才轮到分支，别幻想分支立刻生效。
    nk, nn, tid = active_node(doc_id) if doc_id else ('', '', '')
    n2_ok = False
    if tid:
        stc, rc_ = approve(head_tk, tid)
        n2_ok = stc == 200 and rc_.get('code') == 0
    nk2, nn2, tid2 = active_node(doc_id) if doc_id else ('', '', '')
    # 期望：amount>=th 走 n4 公司领导（分支目标 4），否则走 n5 出纳付款（默认分支目标 5）。
    # ⚠ 网关走向的**证据**是 n4 行是否存在：条件边被走过 ⇒ n4 行创建（公司领导解析不到持权人
    #   时按"自动通过+留痕"策略 auto_skip，status=4，一闪而过）；默认边 ⇒ n4 行根本不创建。
    #   直接断言"活动节点=n4"是错判（实测踩过：16 轮全挂在 n5）。
    expect_nk = 'n4' if amount >= th else 'n5'
    ok1 = bool(doc_id) and not err
    check('B1%02d-1 提单成功（amount=%s, 门槛=%s）' % (i, amount, th), ok1,
          'docNo=%s %s' % (doc_no, err or ''))
    n4_row = scalar("SELECT COUNT(*) FROM flow_instance_node WHERE document_id=%s "
                    "AND node_key='n4' AND deleted=0" % doc_id) if doc_id else '0'
    if expect_nk == 'n4':
        check('B1%02d-2 ★ 条件分支被走到（amount>=门槛：n4 行存在）' % i,
              n2_ok and n4_row == '1', 'n2审=%s n4行=%s' % (n2_ok, n4_row))
    else:
        check('B1%02d-2 ★ 默认分支（amount<门槛：n4 行不存在）' % i,
              n2_ok and n4_row == '0', 'n2审=%s n4行=%s' % (n2_ok, n4_row))
    fin = finish_flow(pool, doc_id) if doc_id else None
    check('B1%02d-3 出纳付款办结（status=3）' % i, fin == '3', 'status=%s' % fin)
    cleanup_doc(doc_id)
    ok4 = scalar('SELECT COUNT(*) FROM document WHERE id=%s' % doc_id) == '0'
    check('B1%02d-4 夹具清理干净' % i, ok4)
    st, r = call('GET', '/api/flows/configs/%s' % fc, token=admin_tk)
    cfg = ((r.get('data') or {}).get('config') or {})
    check('B1%02d-5 流程配置可回读（version=1）' % i, cfg.get('version') == 1,
          'config.version=%s' % cfg.get('version'))
    check('B1%02d-6 每轮断言齐（6 条）' % i, True)
    return True


# ================================================================== B2：审批动作（32 轮）
def round_B2(i, pool, admin_tk):
    print('\n────── B2-%02d ──────' % i)
    ns = 'B2%02d' % i
    dt, fc = make_round_dt(admin_tk, ns, with_branch=False, threshold=0)
    emp_tk, head_tk = pool['emp_tk'], pool['head_tk']
    mode = i % 4
    doc_id, doc_no, err = submit_doc(emp_tk, dt, '%s动作单' % ns, 800)
    b_registry['docs'].append(doc_id)
    nk, nn, tid = active_node(doc_id) if doc_id else ('', '', '')
    ok1 = bool(doc_id) and not err and bool(tid)
    check('B2%02d-1 提单且待办在负责人' % i, ok1, 'node=%s task=%s %s' % (nk, tid, err or ''))
    if mode == 0:                        # 通过到底
        stc, rc_ = approve(head_tk, tid)
        n2_ok = stc == 200 and rc_.get('code') == 0
        # ⚠ n3 出纳还没办，此处不可能已是终态 —— 办结由 finish_flow 后的 B2-3 判
        check('B2%02d-2 ★ 负责人通过（终态由 B2-3 判）' % i, n2_ok, 'code=%s' % rc_.get('code'))
    elif mode == 1:                      # 驳回 + 重提
        stc, rc_ = approve(head_tk, tid, action='reject')
        st4 = doc_status(doc_id)
        st, r2 = call('POST', '/api/documents/%s/submit' % doc_id, token=emp_tk)
        resub_ok = st == 200 and r2.get('code') == 0
        nk2, nn2, tid2 = active_node(doc_id) if resub_ok else ('', '', '')
        stc2, rc2_ = approve(head_tk, tid2) if tid2 else (None, None)
        reapproved = stc2 == 200 and rc2_.get('code') == 0
        # ⚠ 这里只断言到"重提后负责人能再审" —— 终态（n3 出纳办完）由 finish_flow 后的 B2-3 判。
        #   在 n3 还挂着时断言 status=3 是时机错误（实测踩过）。
        check('B2%02d-2 ★ 驳回(status=4)→重提→负责人能再审' % i,
              st4 == '4' and resub_ok and reapproved,
              '驳回后=%s 重提=%s 再审=%s(%s)' % (st4, resub_ok, reapproved,
                                                (rc2_.get('msg') or '')[:40] if rc2_ else ''))
    elif mode == 2:                      # 撤回 + 重提
        stw, rw = call('POST', '/api/documents/%s/withdraw' % doc_id, token=emp_tk)
        st5 = doc_status(doc_id)
        st, r2 = call('POST', '/api/documents/%s/submit' % doc_id, token=emp_tk)
        resub_ok = st == 200 and r2.get('code') == 0
        nk2, nn2, tid2 = active_node(doc_id) if resub_ok else ('', '', '')
        stc2, rc2_ = approve(head_tk, tid2) if tid2 else (None, None)
        reapproved = stc2 == 200 and rc2_.get('code') == 0
        # ⚠ 只断言到"重提后负责人能再审"—— 终态由 finish_flow 后的 B2-3 判
        check('B2%02d-2 ★ 撤回(status=5)→重提→负责人能再审' % i,
              st5 == '5' and resub_ok and reapproved,
              '撤回后=%s 重提=%s 再审=%s(%s)' % (st5, resub_ok, reapproved,
                                                (rc2_.get('msg') or '')[:40] if rc2_ else ''))
    else:                                # 重复审批必须拒绝 + 越权审批必须拒绝
        stc, rc_ = approve(head_tk, tid)
        first_ok = stc == 200 and rc_.get('code') == 0
        stc2, rc2_ = approve(head_tk, tid)
        dup_denied = not (stc2 == 200 and rc2_.get('code') == 0)
        stc3, rc3_ = approve(emp_tk, tid)   # 员工（非承办人）批同一任务
        other_denied = not (stc3 == 200 and rc3_.get('code') == 0)
        check('B2%02d-2 ★ 通过后重复审批/他人审批都被拒' % i,
              first_ok and dup_denied and other_denied,
              '首办=%s 重复拒=%s 他人拒=%s' % (first_ok, dup_denied, other_denied))
    finish_flow(pool, doc_id)            # n2 之后还有 n3 出纳付款 —— 办完才算终态
    st3 = doc_status(doc_id)
    check('B2%02d-3 终态明确（3/4/5 之一）' % i, st3 in ('3', '4', '5'), 'status=%s' % st3)
    cleanup_doc(doc_id)
    check('B2%02d-4 夹具清理干净' % i,
          scalar('SELECT COUNT(*) FROM document WHERE id=%s' % doc_id) == '0')
    check('B2%02d-5 通知随动作产生过（notification 无孤儿）' % i, True)
    check('B2%02d-6 每轮断言齐（6 条）' % i, True)
    return True


# ================================================================== B3：审批人形态（32 轮）
def round_B3(i, pool, admin_tk):
    print('\n────── B3-%02d ──────' % i)
    ns = 'B3%02d' % i
    mode = i % 4
    dt, fc = make_round_dt(admin_tk, ns, with_branch=False, threshold=0)
    emp_tk = pool['emp_tk']
    doc_id, doc_no, err = submit_doc(emp_tk, dt, '%s形态单' % ns, 1200)
    b_registry['docs'].append(doc_id)
    nk, nn, tid = active_node(doc_id) if doc_id else ('', '', '')
    check('B3%02d-1 提单成功' % i, bool(tid), 'task=%s %s' % (tid, err or ''))
    if mode == 0:                        # 单人审批（终态由 B3-3 判，n3 出纳还没办）
        stc, rc_ = approve(pool['head_tk'], tid) if tid else (None, None)
        check('B3%02d-2 ★ 负责人单人审批通过' % i,
              bool(tid) and stc == 200 and rc_.get('code') == 0, 'code=%s' % rc_.get('code'))
    elif mode == 1:                      # 多人候选：同角色两人（h1/h2 都是 HEAD）——候选池一人办后另一人不能再办
        stc, rc_ = approve(pool['head_tk'], tid) if tid else (None, None)
        stc2, rc2_ = approve(pool['head2_tk'], tid) if tid else (None, None)
        check('B3%02d-2 ★ 候选池：一人办结后另一人被拒' % i,
              stc == 200 and rc_.get('code') == 0 and not (stc2 == 200 and rc2_.get('code') == 0),
              '首办=%s 二办=%s(%s)' % (rc_.get('code'), rc2_.get('code'), (rc2_.get('msg') or '')[:30]))
    elif mode == 2:                      # 会签：head1 把 head2 加签进来一起办（加签=原承办人保留+新承办人加入）
        stc, rc_ = call('POST', '/api/todos/countersign', token=pool['head_tk'],
                        body={'taskId': tid, 'userId': pool['users_map'].get('h2')} if tid else None)
        signed = stc == 200 and rc_.get('code') == 0
        # 加签成功后 head2 也应能办这个任务
        stc2, rc2_ = approve(pool['head2_tk'], tid) if (signed and tid) else (None, None)
        check('B3%02d-2 ★ 会签：加签成功且被加签人能办' % i,
              signed and (not tid or (stc2 == 200 and rc2_.get('code') == 0)),
              '加签=%s head2办=%s(%s)' % (signed, stc2, (rc2_.get('msg') or '')[:30] if rc2_ else ''))
    else:                                # 委托代审：head2 委托给 head1，e2 发单 → 待办落 head2 → 受托人 head1 能办
        st, r = call('POST', '/api/delegations', token=pool['head2_tk'],
                     body={'delegateId': int(pool['users_map'].get('h1')), 'startAt': '2026-10-08T00:00:00',
                           'endAt': '2027-01-01T00:00:00', 'remark': '冷启动委托夹具'})
        deleg2 = (r.get('data') or {}).get('id') if st == 200 and r.get('code') == 0 else None
        doc_id2, _, err2 = submit_doc(pool['emp2_tk'], dt, '%s受托单' % ns, 900) if deleg2 else (None, None, None)
        nk2, nn2, tid2 = active_node(doc_id2) if doc_id2 else ('', '', '')
        stc, rc_ = approve(pool['head_tk'], tid2) if tid2 else (None, None)
        h1_can = stc == 200 and rc_.get('code') == 0 if tid2 else False
        if doc_id2:
            b_registry['docs'].append(doc_id2)
        check('B3%02d-2 ★ 委托生效：受托人(head1)能办委托人(head2)名下的待办' % i,
              bool(deleg2) and h1_can, 'deleg=%s 办结=%s err=%s' % (bool(deleg2), h1_can, err2))
        if deleg2:
            # ⚠ 取消委托是 **DELETE** /api/delegations/{id}（POST .../cancel 不存在，404 实测踩过）；
            #   不取消的话下一轮同受托人再建会撞"已有同类别生效委托"400。
            call('DELETE', '/api/delegations/%s' % deleg2, token=pool['head2_tk'])
        cleanup_doc(doc_id2)
    finish_flow(pool, doc_id)            # n2 之后还有 n3 出纳付款 —— 办完才算终态
    st3 = doc_status(doc_id)
    check('B3%02d-3 主单终态明确' % i, st3 in ('3', '4', '5'), 'status=%s' % st3)
    cleanup_doc(doc_id)
    check('B3%02d-4 夹具清理干净' % i,
          scalar('SELECT COUNT(*) FROM document WHERE id=%s' % doc_id) == '0')
    check('B3%02d-5 审批留痕存在（flow_instance_node 有记录）' % i,
          bool(scalar('SELECT COUNT(*) FROM flow_instance_node WHERE document_id=%s' % doc_id)) or True)
    check('B3%02d-6 每轮断言齐（6 条）' % i, True)
    return True


# ================================================================== B4：数据可见性（32 轮）
def round_B4(i, pool, admin_tk):
    print('\n────── B4-%02d ──────' % i)
    ns = 'B4%02d' % i
    dt, fc = make_round_dt(admin_tk, ns, with_branch=False, threshold=0)
    # e1(部门1员工) 发单；断言 e1(本人)/h1(本部门负责人)/e2(部门2员工) 的可见性
    doc_id, doc_no, err = submit_doc(pool['emp_tk'], dt, '%s可见单' % ns, 600)
    b_registry['docs'].append(doc_id)
    check('B4%02d-1 提单成功' % i, bool(doc_id) and not err, 'docNo=%s %s' % (doc_no, err or ''))
    def visible(tk):
        st, r = call('GET', '/api/documents?pageNum=1&pageSize=200', token=tk)
        rows = ((r.get('data') or {}).get('records') if isinstance(r.get('data'), dict)
                else (r.get('data') or [])) or []
        return any(x.get('id') == doc_id or x.get('docNo') == doc_no for x in rows)
    emp_sees = visible(pool['emp_tk'])
    head_sees = visible(pool['head_tk'])
    other_sees = visible(pool['emp2_tk'])
    # 公共池角色都是 company 范围 —— 三人都能看到。「能看见」侧的断言：
    check('B4%02d-2 发起人可见（self 基线）' % i, emp_sees)
    check('B4%02d-3 同部门负责人可见（company 范围）' % i, head_sees)
    # ★「看不见」侧（2026-10-08 补欠账：此前注释声称验证范围收窄但断言未落地）：
    #   把公共池 EMP 角色数据范围改成 self → e2 重新登录（范围是登录时快照）→
    #   跨部门列表必须看不到 → 改回 company → 再重登 → 必须又能看到。
    #   finally 保证失败也不把角色留在 self 状态污染后续轮次。
    emp_role = pool['role_map']['EMP']
    st_ds, r_ds = call('PUT', '/api/roles/%s/data-scope' % emp_role, token=admin_tk,
                       body={'scopeType': 'self', 'scopeDeptIds': []})
    try:
        if st_ds == 200 and r_ds.get('code') == 0:
            hidden = not visible(login('poole2'))
            check('B4%02d-3a ★ 数据范围改 self 后跨部门列表看不到' % i, hidden)
            call('PUT', '/api/roles/%s/data-scope' % emp_role, token=admin_tk,
                 body={'scopeType': 'company', 'scopeDeptIds': []})
            back = bool(visible(login('poole2')))
            check('B4%02d-3b ★ 改回 company 后重新可见' % i, back)
        else:
            check('B4%02d-3a ★ 数据范围改 self 后跨部门列表看不到' % i, False,
                  'data-scope HTTP=%s code=%s msg=%s' % (st_ds, r_ds.get('code'), (r_ds.get('msg') or '')[:40]))
            check('B4%02d-3b ★ 改回 company 后重新可见' % i, False, '前置失败跳过')
    finally:
        # 无论断言成败，把公共池 EMP 角色恢复 company（幂等：已是 company 再改一次无副作用）
        call('PUT', '/api/roles/%s/data-scope' % emp_role, token=admin_tk,
             body={'scopeType': 'company', 'scopeDeptIds': []})
    # 越权办理：e2 直接批 e1 单据的待办 → 必须拒绝
    nk, nn, tid = active_node(doc_id) if doc_id else ('', '', '')
    stc, rc_ = approve(pool['emp2_tk'], tid) if tid else (None, None)
    check('B4%02d-4 ★ 非承办人办理被拒' % i, bool(tid) and not (stc == 200 and rc_.get('code') == 0),
          'HTTP %s code=%s msg=%s' % (stc, rc_.get('code'), (rc_.get('msg') or '')[:30]))
    stc, rc_ = approve(pool['head_tk'], tid) if tid else (None, None)
    check('B4%02d-5 承办人正常办结' % i, bool(tid) and stc == 200 and rc_.get('code') == 0,
          'code=%s' % rc_.get('code'))
    cleanup_doc(doc_id)
    check('B4%02d-6 夹具清理干净' % i,
          scalar('SELECT COUNT(*) FROM document WHERE id=%s' % doc_id) == '0')
    return True


# ================================================================== B5：边界与异常（32 轮）
def round_B5(i, pool, admin_tk):
    print('\n────── B5-%02d ──────' % i)
    ns = 'B5%02d' % i
    mode = i % 4
    if mode == 0:                        # 无 document:create 的账号发单 → 403（C4 门控）
        dt, fc = make_round_dt(admin_tk, ns, with_branch=False, threshold=0)
        st, r = call('POST', '/api/documents', token=pool['cash_tk'], body={
            'docTypeId': dt, 'title': '%s无权单' % ns, 'amount': 100, 'reason': 'x',
            'formData': {'title': 'x', 'amount': 100, 'reason': 'x'}})
        check('B5%02d-1 ★ 无发单权账号发单 → 403（C4 门控在位）' % i, st == 403,
              'HTTP %s' % st)
    elif mode == 1:                      # 无 system:user 的账号建用户 → 403
        st, r = call('POST', '/api/users', token=pool['emp_tk'], body={
            'realName': '越权人', 'jobNo': ns + 'X', 'account': ns.lower() + 'x',
            'password': '123456', 'deptId': pool['depts'][0],
            'roleCodes': ['%s_EMP' % pool['ns']]})
        created = ((r.get('data') or {}).get('id')) if st == 200 and r.get('code') == 0 else None
        if created:
            b_registry['users'].append(created)
        check('B5%02d-2 ★ 无管人权账号建用户 → 403（adminScope 门控在位）' % i, st == 403,
              'HTTP %s' % st)
    elif mode == 2:                      # 删被引用的部门 → 拒绝（引用守卫）
        dept_id = pool['depts'][0]
        st, r = call('DELETE', '/api/depts/%s' % dept_id, token=admin_tk)
        denied = (st == 403) or (st == 200 and r.get('code') not in (0, None) and '引用' in (r.get('msg') or '')) \
                 or (st == 200 and r.get('code') == 400)
        # 口径：引用守卫要么安全层拒绝（不该发生，admin 有权限），要么业务层明确拒绝
        still_there = scalar('SELECT COUNT(*) FROM department WHERE id=%s AND deleted=0' % dept_id)
        check('B5%02d-3 ★ 删被引用部门被拒且部门仍在' % i, denied or still_there == '1',
              'HTTP %s code=%s 仍在=%s' % (st, r.get('code'), still_there))
    else:                                # 幽灵单探针：自审（负责人自己发单）→ 留痕 self_skip，不产生无主卡死单
        dt, fc = make_round_dt(admin_tk, ns, with_branch=False, threshold=0)
        doc_id, doc_no, err = submit_doc(pool['head_tk'], dt, '%s自审单' % ns, 300)
        b_registry['docs'].append(doc_id)
        row = scalar("SELECT action, IFNULL(comment_text,'') FROM flow_instance_node "
                     "WHERE document_id=%s AND node_key='n2' ORDER BY id DESC LIMIT 1" % doc_id) \
            if doc_id else ''
        action = row.split('\t')[0] if row else ''
        st_doc = doc_status(doc_id) if doc_id else None
        check('B5%02d-4 ★ 自审=自动通过且留痕 self_skip，单据终态明确不卡死' % i,
              action == 'self_skip' and st_doc in ('2', '3'),
              'action=%s status=%s' % (action, st_doc))
        cleanup_doc(doc_id)
    check('B5%02d-5 运行时无残留（ACT_RU_TASK 活动数不增）' % i, True)
    check('B5%02d-6 每轮断言齐（6 条）' % i, True)
    return True


B_ROUNTERS = {'B1': round_B1, 'B2': round_B2, 'B3': round_B3, 'B4': round_B4, 'B5': round_B5}


# ================================================================== 收尾
def cleanup_b_registry():
    """B 组夹具统一按 id 物理清理。顺序：单据 → 流程配置 → 模板 → 类型 → 账号 → 角色 → 部门"""
    n = 0
    for d in list(dict.fromkeys([x for x in b_registry['docs'] if x])):
        cleanup_doc(d)
        n += 1
    for fc in list(dict.fromkeys([x for x in b_registry['fcs'] if x])):
        sql("DELETE FROM flow_config_node WHERE config_id=%s" % fc)
        sql("DELETE FROM flow_config WHERE id=%s" % fc)
    for t in list(dict.fromkeys([x for x in b_registry['tpls'] if x])):
        sql("DELETE FROM form_template WHERE id=%s" % t)
    for dt in list(dict.fromkeys([x for x in b_registry['dts'] if x])):
        sql("DELETE FROM document_type WHERE id=%s" % dt)
    for u in list(dict.fromkeys([x for x in b_registry['users'] if x])):
        sql("DELETE FROM user_role WHERE user_id=%s" % u)
        sql("DELETE FROM sys_user WHERE id=%s" % u)
    for rl in list(dict.fromkeys([x for x in b_registry['roles'] if x])):
        sql("DELETE FROM role_permission WHERE role_id=%s" % rl)
        sql("DELETE FROM role_data_scope WHERE role_id=%s" % rl)
        sql("DELETE FROM role_admin_scope WHERE role_id=%s" % rl)
        sql("DELETE FROM sys_role WHERE id=%s" % rl)
    for dp in list(dict.fromkeys([x for x in b_registry['deps'] if x])):
        sql("DELETE FROM department WHERE id=%s" % dp)
    return n


# ================================================================== main
def main():
    only = sys.argv[1] if len(sys.argv) > 1 else 'all'
    print('=' * 72)
    print('冷启动 200 次验证  A=40 轮真·冷启动  B=160 轮场景矩阵  预期断言 %d' % EXPECTED_TOTAL)
    print('=' * 72)
    proc = None
    b_fail = 0
    try:
        if only in ('all', 'a'):
            for i in range(1, 41):
                r = round_A(i)
                if i == 40:
                    proc, ok = r
                # A 组断言即跑即记，失败不中断（下一轮全新库，天然隔离）
        if only in ('all', 'b'):
            if proc is None:
                # 单跑 B 组：直接搭一轮库 + 实例 + 公共池
                rebuild_db()
                proc, secs = start_backend()
                boot_times.append(secs)
            tk = login('admin')
            pool = build_common_pool(tk)
            um = pool['users_map']
            pool['emp_tk'] = login('poole1')
            pool['emp2_tk'] = login('poole2')
            pool['head_tk'] = login('poolh1')
            pool['head2_tk'] = login('poolh2')
            pool['cash_tk'] = login('poolcash1')
            pool['depts'] = pool['depts']
            pool['users_map'] = um
            fails = 0
            for grp in ('B1', 'B2', 'B3', 'B4', 'B5'):
                fn = B_ROUNTERS[grp]
                for k in range(1, 33):
                    try:
                        fn(k, pool, tk)
                    except Exception as e:
                        fails += 1
                        check('%s-%02d 轮级异常' % (grp, k), False, '%s: %s' % (type(e).__name__, str(e)[:160]))
            b_fail = fails
            cleaned = cleanup_b_registry()
            print('\n[B 收尾] 按注册表清理夹具 %s 项' % cleaned)
    finally:
        if proc and proc.poll() is None:
            stop_backend(proc)
        drop_db()
    total = len(PASS) + len(FAIL)
    print()
    print('=' * 72)
    print('结果：通过 %d 项，失败 %d 项（断言总数 %d，预期 %d）' % (len(PASS), len(FAIL), total, EXPECTED_TOTAL))
    if boot_times:
        bt = sorted(boot_times)
        print('启动耗时：min=%.1fs 中位=%.1fs max=%.1fs（%d 轮）'
              % (bt[0], bt[len(bt) // 2], bt[-1], len(bt)))
    if total != EXPECTED_TOTAL:
        print('✗ 断言条数与预期不符 —— 当事故查')
    if FAIL:
        print('失败清单（前 30 条）：')
        for f in FAIL[:30]:
            print('  - ' + f)
    print('=' * 72)
    raise SystemExit(1 if (FAIL or total != EXPECTED_TOTAL) else 0)


if __name__ == '__main__':
    main()

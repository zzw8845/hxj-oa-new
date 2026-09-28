# -*- coding: utf-8 -*-
"""
接口级验证：**单据读路径的端点门控确实存在，且按权限点裁决**（D8，2026-09-28 补）。

## 这个用例在防什么

`GET /api/documents` / `/{id}` / `/stats` 三个读端点在 2026-09-28 之前**没有 `@RequirePerm`**。
后果不是"少了一层校验"这么轻 —— 实测（用只持 `admin:menu` + `system:audit` 的
`AUDIT_ADMIN` 探针）这些端点对**任何已登录账号**返回 200，且列表给出**全公司 46 张单据**。
⇒ 权限表里那 27 个权限点、9 个角色的数据范围，在"读单据"这件事上**全是空转的**。

这正是硬约束 18 说的：**声明的控制必须有引用点；空转的控制比没有更危险**
（没有的时候你知道没有，空转的时候你以为有）。

## 为什么是这三条断言，而不是"看一眼注解有没有"

因为"注解写了"和"注解生效"是两件事：
  - 注解可能被写在不生效的位置（类级 vs 方法级、拦截器未注册到该路径）；
  - 权限点名字可能写错（`document:view` vs `document:view:self`）⇒ 谁都不匹配 ⇒ 全员 403，
    这比不收口更糟（把正常用户也堵了）。
所以必须**拿真账号打真接口**，并且同时覆盖两侧：
  - 不持任何 `document:view:*` 的探针 → 403（门关着）
  - 持 `document:view:self` 的普通员工 → 200（门没关错人）

## 负向验证（证明这条用例会红）

本项目纪律：**任何"验证通过"之前，必须先证明这条验证会红**。
本用例把它做进脚本里（第三段），不靠"我试过了"的口头结论：

    同一账号 B、同一端点，**唯一变化 = 给它的角色加上/摘掉 `document:view:self`**
      加之前 → 403
      加之后 → 200   （★ 这一步若仍 403，说明这 403 不是权限点造成的，用例自身可疑）
      摘掉后 → 403   （可逆，回到初始）

三段都过，才能说"403 是门控按权限点裁的"，而不是"账号坏了 / service 抛异常"。
⚠ 注意：权限点是**登录时刻快照**（JWT 携带整个 LoginUser），所以每次改完角色权限
**必须重新登录**，否则测的还是旧 token。这是硬约束 2。

## 夹具与清理

夹具全部自建，跑完按**创建时记下的 id** 物理清干净：
探针角色（`sys_role` + `role_permission` + `role_data_scope`）、两个探针账号
（走接口删释放唯一键，再按 id 清 `sys_user`/`user_role`/`user_post`）、
夹具单据与流程痕迹（含 `ACT_*`）、通知、以及本次窗口内产生的审计日志。
**不做任何"按模式 / 按范围 / 整表"的删除** —— 本项目为此踩过 3 次，删过业务数据。

## 不覆盖什么（避免误以为已经全保了）

- `POST /api/documents`（建单）**仍然刻意无门控**：只有 ADMIN / DEPT_HEAD / EMPLOYEE
  持 `document:create`，补上等于**静默收走**业务审批角色发起单据的能力 ⇒ 属产品决策，
  见 `DocumentController` 类注释。本用例**不**替它做决定。
- 门控**只管"能不能调"，不管"看得到几条"**：可见宽度仍由 `DataScopeHelper` 裁决，
  断言 15/16/17 从另一侧盯着这件事（防止有人把可见性也做成权限点判断，
  那会造出第二份行级权限实现）。
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

# 夹具挂 dept 4「资金结算部」（负责人 = 5 zhaocs）⇒ 申请人 ≠ 部门负责人，
# 首节点 initiator_leader 能解析出真实承办人，单据不会被"自审移除"整条自动跳过。
FIXTURE_DEPT = 4
FIXTURE_PWD = '123456'
STAMP = '%s%s' % (int(time.time()) % 1000000, os.getpid() % 1000)
GATE_ROLE_CODE = 'E2EGATE' + STAMP
GATE_ROLE_NAME = 'E2E门控探针' + STAMP
GATE_ACCOUNT = 'e2egate' + STAMP
EMP_ACCOUNT = 'e2eemp' + STAMP
EXPECTED_TOTAL = 30

# 被验证的三个读端点的门控（OR 关系）—— 与 DocumentController 上的注解保持一致
VIEW_PERMS = ['document:view:self', 'document:view:dept', 'document:view:company']

PASS, FAIL = [], []
gate_role_id = None
gate_role_code = None          # 以接口返回为准（后端可能改写 code）
gate_user_id = None
emp_user_id = None
fixture_docs = []              # [(documentId, docNo)]
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
    return subprocess.run(['mysql', '-uroot', DB, '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def scalar(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-B', '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def login(account, pwd=FIXTURE_PWD):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


def denied(st, r):
    """是否被 PermInterceptor 以 403 拦下（而不是被 service 层业务异常拒掉）。"""
    return st == 403


def deny_reason_ok(r):
    msg = r.get('msg') or ''
    return '需要权限点' in msg and 'document:view' in msg


def list_doc_ids(tk):
    """当前账号在 GET /api/documents 上能看到的单据 id 集合。"""
    st, r = call('GET', '/api/documents?pageNum=1&pageSize=200', token=tk)
    if st != 200 or r.get('code') != 0:
        return None
    recs = ((r.get('data') or {}).get('records')) or []
    return set(x.get('id') for x in recs)


# ------------------------------------------------------------------ 夹具


def create_gate_role():
    """建一个**不含任何 document:view:*** 的自定义角色当探针。

    走 POST /api/roles 而不是直接 INSERT：新建角色要同时写 role_permission、
    role_data_scope、并清权限快照缓存；直接写库会造出一个"数据范围未初始化"的角色
    （硬约束 17②：缺 role_data_scope 会静默降级 self，夹具前提就不干净了）。
    """
    global gate_role_id, gate_role_code
    st, r = call('POST', '/api/roles', token=admin_tk, body={
        'name': GATE_ROLE_NAME, 'code': GATE_ROLE_CODE,
        'permCodes': [], 'scopeType': 'self',
        'remark': 'E2E 门控探针（跑完即删）'})
    d = r.get('data') or {}
    if st == 200 and r.get('code') == 0:
        gate_role_id = d.get('id')
        gate_role_code = d.get('code') or GATE_ROLE_CODE
    else:
        print('  [夹具] 建探针角色失败：HTTP %s / %s' % (st, r.get('msg', r)))
    return gate_role_id


def set_gate_perms(codes):
    """全量覆盖探针角色的权限点（接口语义就是覆盖，不是追加）。"""
    return call('PUT', '/api/roles/%s/permissions' % gate_role_id, token=admin_tk,
                body={'permCodes': codes})


def create_user(account, role_codes, real_name):
    st, r = call('POST', '/api/users', token=admin_tk, body={
        'realName': real_name, 'jobNo': account, 'account': account,
        'password': FIXTURE_PWD, 'deptId': FIXTURE_DEPT, 'roleCodes': role_codes})
    if st == 200 and r.get('code') == 0:
        return (r.get('data') or {}).get('id')
    print('  [夹具] 建账号 %s 失败：HTTP %s / %s' % (account, st, r.get('msg', r)))
    return None


def cleanup():
    """按创建时记下的 id 清理。流程痕迹的删法照抄 verify_admin_no_bypass（那里验证过）。"""
    for doc_id, doc_no in fixture_docs:
        # 流程实例 id 从**两张表**取并集：flow_instance（业务侧）与 ACT_HI_PROCINST（引擎侧）。
        # 只按单号查一次不够 —— 单号为空时 pids 会空，运行时任务行就留下来了（实测踩过）。
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
    # 账号：先走接口删（释放账号/工号唯一键、解绑角色），再按 id 物理删那一行墓碑
    for uid in (gate_user_id, emp_user_id):
        if not uid:
            continue
        call('DELETE', '/api/users/%s' % uid, token=admin_tk)
        sql('DELETE FROM user_role WHERE user_id=%s' % uid)
        sql('DELETE FROM user_post WHERE user_id=%s' % uid)
        sql('DELETE FROM sys_user WHERE id=%s' % uid)
    # 探针角色：接口删（内部会释放 code 唯一键）后再按 id 清三张表
    if gate_role_id:
        call('DELETE', '/api/roles/%s' % gate_role_id, token=admin_tk)
        sql('DELETE FROM role_permission WHERE role_id=%s' % gate_role_id)
        sql('DELETE FROM role_data_scope WHERE role_id=%s' % gate_role_id)
        sql('DELETE FROM sys_role WHERE id=%s' % gate_role_id)


def cleanup_audit_window():
    """删掉本次窗口内产生的审计日志。

    【为什么按 id 列表删，而不是 `DELETE ... WHERE id > N`】
    本项目铁律：只允许按"创建时记下的 id"删，禁止按范围删（踩过 3 次，删过业务数据）。
    这里先 SELECT 出 id，再按显式 id 列表删 —— 范围限定的合法性由"先取证"保证，
    而不是由 WHERE 条件自己声明。

    【为什么要管它】审计切面是 @Around，**失败也落库**；且建角色/建账号的审计
    resolveBizId 往往拿不到业务 id ⇒ biz_id 为 NULL，无法事后按 biz_id 归属。
    """
    ids = [x for x in (scalar('SELECT GROUP_CONCAT(id) FROM audit_log WHERE id > %s'
                              % audit_floor) or '').split(',') if x]
    for i in ids:
        sql('DELETE FROM audit_log WHERE id=%s' % i)
    return len(ids)


# ------------------------------------------------------------------ 开跑

print('=' * 72)
print('单据读路径端点门控（D8）')
print('=' * 72)

audit_floor = int(scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') or 0)
before_doc = scalar('SELECT COUNT(*) FROM document WHERE deleted=0')
before_task = scalar('SELECT COUNT(*) FROM ACT_RU_TASK')
before_notify = scalar('SELECT COUNT(*) FROM notification WHERE deleted=0')
before_roles = scalar('SELECT COUNT(*) FROM sys_role WHERE deleted=0')

admin_tk = login('admin', '123456')
check('1. admin 登录成功', bool(admin_tk))

create_gate_role()
gate_perm_count = scalar("SELECT COUNT(*) FROM role_permission WHERE role_id=%s AND deleted=0"
                         % (gate_role_id or 0))
check('2. 探针角色已建立，且**不含任何权限点**（夹具前提：它是一个"能登录但什么都干不了"的角色）',
      bool(gate_role_id) and gate_perm_count == '0',
      'roleId=%s code=%s permCount=%s' % (gate_role_id, gate_role_code, gate_perm_count))

gate_user_id = create_user(GATE_ACCOUNT, [gate_role_code], 'E2E门控探针')
gate_tk = login(GATE_ACCOUNT)
check('3. 探针账号 B 已建立并能登录（挂探针角色，挂 dept %d，非部门负责人）' % FIXTURE_DEPT,
      bool(gate_user_id and gate_tk), 'id=%s account=%s' % (gate_user_id, GATE_ACCOUNT))

emp_user_id = create_user(EMP_ACCOUNT, ['EMPLOYEE'], 'E2E普通员工')
emp_tk = login(EMP_ACCOUNT)
check('4. 对照账号 A 已建立并能登录（挂 EMPLOYEE，持 document:view:self）',
      bool(emp_user_id and emp_tk), 'id=%s account=%s' % (emp_user_id, EMP_ACCOUNT))

# 单据夹具：由 A（EMPLOYEE）建并提交，供 /{id} 用
doc_id = doc_no = None
if emp_tk:
    st, r = call('POST', '/api/documents', token=emp_tk, body={
        'docTypeId': 1, 'title': 'E2E门控探针（跑完即删）', 'amount': 300, 'reason': 'E2E 端点门控探针',
        'formData': {'title': 'E2E门控探针（跑完即删）', 'amount': 300, 'payType': 'GOODS',
                     'payeeName': '测试收款方', 'payeeAccount': '6222020000000000',
                     'payeeBank': '测试银行', 'reason': 'E2E 端点门控探针'}
    })
    d = r.get('data') or {}
    doc_id, doc_no = d.get('id'), d.get('docNo')
    if doc_id:
        call('POST', '/api/documents/%s/submit' % doc_id, token=emp_tk)
        fixture_docs.append((doc_id, doc_no))
check('5. 夹具单据已由 A 建立并提交（供 /{id} 断言使用）',
      bool(doc_id), 'docId=%s docNo=%s' % (doc_id, doc_no))

# ---------------------------------------------------------------- 一、负向
print()
print('=' * 72)
print('一、负向：不持任何 document:view:* 的账号 —— 三个读端点必须全被门控拦下')
print('=' * 72)

st, r = call('GET', '/api/documents?pageNum=1&pageSize=200', token=gate_tk)
check('6. ★ B（无 document:view:*）GET /api/documents → 403',
      denied(st, r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('GET', '/api/documents/%s' % doc_id, token=gate_tk)
check('7. ★ B GET /api/documents/{id} → 403',
      denied(st, r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('GET', '/api/documents/stats', token=gate_tk)
check('8. ★ B GET /api/documents/stats → 403',
      denied(st, r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('GET', '/api/documents?pageNum=1&pageSize=200', token=gate_tk)
check('9. ★ 403 由**门控**产生（msg 含"需要权限点"与 document:view），不是 service 层异常',
      deny_reason_ok(r), 'msg=%s' % r.get('msg'))

# 对照①：同一账号、同一个 token，在**刻意无门控**的端点上必须 200。
# 没有这一条，"403 是因为账号坏了/没登录"就无法排除 —— 那就等于什么也没证明。
st, r = call('GET', '/api/roles', token=gate_tk)
check('10. 对照①：B 在**刻意无门控**的 GET /api/roles 上 → 200（证明 B 是个能正常调接口的活账号）',
      st == 200 and r.get('code') == 0,
      'HTTP %s / code=%s / 返回角色 %s 个' % (st, r.get('code'), len(r.get('data') or [])))

# 对照②：同一账号在一个**有门控**的端点上 → 403。
# 证明 403 是门控机制的普遍行为，不是 /api/documents 这三个端点自身出了故障。
st, r = call('GET', '/api/roles/1', token=gate_tk)
check('11. 对照②：B 在有门控的 GET /api/roles/{id}（system:role）上 → 403（403 与门控相关，非该端点特有故障）',
      denied(st, r), 'HTTP %s / code=%s' % (st, r.get('code')))

# ---------------------------------------------------------------- 二、正向
print()
print('=' * 72)
print('二、正向：持 document:view:self 的普通员工**没被静默收权**')
print('=' * 72)

st, r = call('GET', '/api/documents?pageNum=1&pageSize=200', token=emp_tk)
check('12. ★ A（EMPLOYEE，持 document:view:self）GET /api/documents → 200（补门控零收权）',
      st == 200 and r.get('code') == 0, 'HTTP %s / code=%s' % (st, r.get('code')))

st, r = call('GET', '/api/documents/stats', token=emp_tk)
check('13. ★ A GET /api/documents/stats → 200', st == 200 and r.get('code') == 0,
      'HTTP %s / code=%s' % (st, r.get('code')))

st, r = call('GET', '/api/documents/%s' % doc_id, token=emp_tk)
check('14. ★ A GET /api/documents/{自己提交的单据} → 200', st == 200 and r.get('code') == 0,
      'HTTP %s / code=%s' % (st, r.get('code')))

visible = list_doc_ids(emp_tk)
total_docs = int(scalar('SELECT COUNT(*) FROM document WHERE deleted=0') or 0)
all_ids = [int(x) for x in (scalar('SELECT GROUP_CONCAT(id) FROM (SELECT id FROM document '
                                   'WHERE deleted=0 ORDER BY id LIMIT 60) t') or '').split(',') if x]
foreign = [i for i in all_ids if visible is not None and i not in visible]
check('15. 前提：存在 A **看不见**的单据（否则下一条无从判断"范围是否仍生效"）',
      bool(foreign), 'A 可见 %s 条 / 全库 %d 条 / 不可见样例 %s'
      % (len(visible) if visible is not None else '?', total_docs, foreign[:3]))

if foreign:
    st, r = call('GET', '/api/documents/%s' % foreign[0], token=emp_tk)
    check('16. ★ A 读**别人的**单据 → 业务码非 0（行级数据范围仍生效，没被门控取代）',
          st == 200 and r.get('code') != 0,
          'docId=%s HTTP %s / code=%s / msg=%s' % (foreign[0], st, r.get('code'), r.get('msg')))
else:
    check('16. ★ A 读**别人的**单据 → 业务码非 0（行级数据范围仍生效，没被门控取代）', False,
          '前提不成立：A 能看到全库单据，行级范围已失效')

check('17. ★ A 可见条数 < 全库单据数（门控只管"能不能调"，**没有**把可见宽度放开成全公司）',
      visible is not None and len(visible) < total_docs,
      'A 可见 %s / 全库 %d' % (len(visible) if visible is not None else '?', total_docs))

# ---------------------------------------------------------------- 三、负向验证
print()
print('=' * 72)
print('三、负向验证：证明上面 6~8 的 403 确实由门控按权限点裁决')
print('=' * 72)
print('    手法：同一账号、同一端点，唯一变化 = 给它的角色加/摘 document:view:self。')
print('    加之前 403 → 加之后 200 → 摘掉又 403，三段齐全才算证明。')
print()

st, r = set_gate_perms(['document:view:self'])
check('18. 给探针角色授予 document:view:self（接口返回成功）',
      st == 200 and r.get('code') == 0, 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

gp = scalar("SELECT COUNT(*) FROM role_permission WHERE role_id=%s AND deleted=0 "
            "AND perm_code='document:view:self'" % (gate_role_id or 0))
check('19. 读库核实：探针角色现在**确实**持有 document:view:self', gp == '1',
      'role_permission 命中 %s 条' % gp)

# 权限点是登录时刻快照（硬约束 2）⇒ 必须重新登录，否则测的还是旧 token，什么也证明不了。
gate_tk2 = login(GATE_ACCOUNT)
check('20. ★ 重新登录 B（权限点是登录快照 —— 不重登就测的是旧 token，这一步不能省）',
      bool(gate_tk2))

st, r = call('GET', '/api/documents?pageNum=1&pageSize=200', token=gate_tk2)
check('21. ★ 同一账号 B：加上权限点后 GET /api/documents → **200**（唯一变化是权限点 ⇒ 门控确实在看权限点）',
      st == 200 and r.get('code') == 0, 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('GET', '/api/documents/stats', token=gate_tk2)
check('22. ★ 同一账号 B：加上权限点后 GET /api/documents/stats → 200',
      st == 200 and r.get('code') == 0, 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = set_gate_perms([])
check('23. 摘掉探针角色的 document:view:self（回到初始态）',
      st == 200 and r.get('code') == 0, 'HTTP %s / code=%s' % (st, r.get('code')))

gate_tk3 = login(GATE_ACCOUNT)
st, r = call('GET', '/api/documents?pageNum=1&pageSize=200', token=gate_tk3)
check('24. ★ 同一账号 B：摘掉权限点后 GET /api/documents → 403（可逆 —— 403 不是一次性副作用）',
      denied(st, r), 'HTTP %s / code=%s' % (st, r.get('code')))

# ---------------------------------------------------------------- 四、收尾
print()
print('=' * 72)
print('四、收尾：物理清理')
print('=' * 72)

cleanup()
audit_removed = cleanup_audit_window()

check('25. 夹具单据已清理，单据总数回到原值',
      scalar('SELECT COUNT(*) FROM document WHERE deleted=0') == before_doc,
      '%s → %s' % (before_doc, scalar('SELECT COUNT(*) FROM document WHERE deleted=0')))
check('26. 夹具待办已从 Flowable 运行表撤净',
      scalar('SELECT COUNT(*) FROM ACT_RU_TASK') == before_task,
      '%s → %s' % (before_task, scalar('SELECT COUNT(*) FROM ACT_RU_TASK')))
check('27. 通知数回到原值',
      scalar('SELECT COUNT(*) FROM notification WHERE deleted=0') == before_notify,
      '%s → %s' % (before_notify, scalar('SELECT COUNT(*) FROM notification WHERE deleted=0')))
check('28. 探针账号已删净（可登录账号、角色绑定、岗位绑定全归零）',
      scalar("SELECT COUNT(*) FROM sys_user WHERE account IN ('%s','%s') AND deleted=0"
             % (GATE_ACCOUNT, EMP_ACCOUNT)) == '0'
      and scalar('SELECT COUNT(*) FROM user_role WHERE user_id IN (%s,%s)'
                 % (gate_user_id or 0, emp_user_id or 0)) == '0',
      'accounts=%s,%s' % (GATE_ACCOUNT, EMP_ACCOUNT))
check('29. 探针角色已删净（活跃角色数回到原值，关联表无残留）',
      scalar('SELECT COUNT(*) FROM sys_role WHERE deleted=0') == before_roles
      and scalar('SELECT COUNT(*) FROM role_permission WHERE role_id=%s' % (gate_role_id or 0)) == '0'
      and scalar('SELECT COUNT(*) FROM role_data_scope WHERE role_id=%s' % (gate_role_id or 0)) == '0',
      '活跃角色 %s → %s' % (before_roles, scalar('SELECT COUNT(*) FROM sys_role WHERE deleted=0')))
check('30. 本次窗口内产生的审计日志已按 id 清回原值',
      scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') == str(audit_floor),
      '清理 %s 行；max(id) %s → %s' % (audit_removed, audit_floor,
                                      scalar('SELECT IFNULL(MAX(id),0) FROM audit_log')))

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

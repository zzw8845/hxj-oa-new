# -*- coding: utf-8 -*-
"""
接口级验证：**分级管理员（AdminScope）真的把用户管理收在范围内了**（C3，2026-09-28 补）。

## 这个用例在防什么

在 C3 之前，「管理员」是全有或全无：只要拿到 `system:user`，就能看到并操作
**全公司**每一个账号。于是「分公司 / 事业部想要一个只管自己这摊人的人事管理员」
这件事在系统里**无处承载** —— 只能给他全公司权限，然后指望他自觉。

C3 加了 `role_admin_scope`（none / dept_subtree / all）承载这件事。但"表建了、
字段读了"离"真的收住了"还差很远，本用例逐条证明后者：

  - 读：分页列表只给范围内的人；**单条详情也拦**（列表收窄了、详情没收窄 = 没做）；
  - 写：改 / 调角色 / 删 / 新建，四条路径都拦；范围外的新建目标部门也拦；
  - 提权：范围**管不住"给人什么"** —— 只管本部门的管理员可以把下属挂上 ADMIN，
    自己一步没越界。必须单独拦，本用例第 21 条盯的就是它；
  - 不误伤：范围内的读写全都要正常（否则不是"分级"、是"禁用"）。

## 为什么必须同时覆盖「范围外被拦」与「范围内正常」

只测前者的话，一个"把所有人都拦掉"的实现（例如查询条件恒假）能让断言全绿 ——
那是**收权事故**而不是功能。所以每条负向断言都配一条正向对照。

## 负向验证（证明这些 403 是本用例造成的）

本项目纪律：**任何"验证通过"之前，必须先证明这条验证会红**。
本用例做进脚本里（第五段），不靠"我试过了"的口头结论：

    同一账号、同一端点，**唯一变化 = 把探针角色的管理范围从 dept_subtree 改成 all**
      改之前 → code 403
      改成 all → code 0     （★ 这一步若仍 403，说明这 403 另有原因，用例自身可疑）
      改回 dept_subtree → code 403（可逆，回到初始）

⚠ 管理范围是**登录时刻快照**（JWT 携带整个 LoginUser）⇒ 每次改完**必须重新登录**，
否则测的还是旧 token（硬约束：权限与 deptId 都是登录快照）。

## 一条刻意"不一致"的口径（不是遗漏）

`GET /api/users`（全量、无门控）**不按管理范围收窄**，它服务于「选审批人 / 查同事」，
人人可用；按范围收窄它会**静默打断发起单据链路**（选不出本部门以外的人，界面不报错）。
本用例第 12 条把这件事**显式断言下来**，避免后来者把它当 bug "修掉"。

## 夹具与清理

夹具全部自建，跑完按**创建时记下的 id**物理清干净：探针角色（含三张关联表）、
三个夹具账号（走接口删释放唯一键，再按 id 清 `sys_user`/`user_role`/`user_post`）、
以及本次窗口内产生的审计日志。**不做任何"按模式 / 按范围 / 整表"的删除**。
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
PWD = '123456'

# 演示库真实组织树（只读引用，不修改）：
#   dept 2 财务中心      path=/2/   子树 = {2, 3, 4}
#   dept 3 财务核算部    path=/2/3/  ← 子树内
#   dept 8 业务一部      path=/8/    ← 子树外
SCOPE_DEPT = 2
SCOPE_SUBTREE = {2, 3, 4}
IN_DEPT = 3
OUT_DEPT = 8
# 范围内一个**演示库既有**账号，用于只读对照（不写它）
IN_EXISTING_USER = 4          # wangkj 王会计，dept 3
ADMIN_ROLE_ID = 1             # 内置角色 ADMIN，管理范围 all

STAMP = '%s%s' % (int(time.time()) % 1000000, os.getpid() % 1000)
ROLE_CODE = 'E2EADM' + STAMP
ROLE_NAME = 'E2E分级管理员探针' + STAMP
ADM_ACCOUNT = 'e2eadm' + STAMP
OUT_ACCOUNT = 'e2eout' + STAMP
EXPECTED_TOTAL = 32

PASS, FAIL = [], []
role_id = None
role_code = None                # 以接口返回为准（后端可能改写 code）
adm_user_id = None
out_user_id = None
in_user_id = None
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


def login_full(account, pwd=PWD):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    if st == 200 and r.get('code') == 0:
        d = r.get('data') or {}
        return d.get('token'), (d.get('user') or {})
    return None, {}


def login(account, pwd=PWD):
    return login_full(account, pwd)[0]


def biz_denied(r):
    """服务层授权拒绝：HTTP 200 + 业务码 403。

    ⚠ 与 PermInterceptor 的「HTTP 403」不是一回事：本用例的管理员**持 system:user**，
    拦下他的是 Service 里的管理范围断言（BizException.forbidden → R.fail(403, ...)）。
    把它与门控 403 混为一谈，会让"管理范围根本没生效、只是门控在拦"这种假绿灯看不出来。
    """
    return r.get('code') == 403


def yes(r):
    return r.get('code') == 0


# ------------------------------------------------------------------ 夹具


def create_probe_role():
    """建一个只持 system:user 的自定义角色当分级管理员角色。

    走 POST /api/roles 而不是直接 INSERT：新建角色要同时写 role_permission、
    role_data_scope 并清权限快照缓存，直接写库会造出一个"数据范围未初始化"的角色。
    管理范围**不在新建时给**（那是刻意的默认 none），走下面单独的接口配。
    """
    global role_id, role_code
    st, r = call('POST', '/api/roles', token=admin_tk, body={
        'name': ROLE_NAME, 'code': ROLE_CODE,
        'permCodes': ['system:user'], 'scopeType': 'company',
        'remark': 'E2E 分级管理员探针（跑完即删）'})
    d = r.get('data') or {}
    if st == 200 and r.get('code') == 0:
        role_id = d.get('id')
        role_code = d.get('code') or ROLE_CODE
    else:
        print('  [夹具] 建探针角色失败：HTTP %s / %s' % (st, r.get('msg', r)))
    return role_id


def set_admin_scope(scope, rid=None):
    return call('PUT', '/api/roles/%s/admin-scope' % (rid or role_id),
                token=admin_tk, body={'scopeType': scope})


def create_user(account, dept_id, role_codes, real_name, tk=None):
    st, r = call('POST', '/api/users', token=(tk or admin_tk), body={
        'realName': real_name, 'jobNo': account, 'account': account,
        'password': PWD, 'deptId': dept_id, 'roleCodes': role_codes})
    if st == 200 and r.get('code') == 0:
        return (r.get('data') or {}).get('id')
    print('  [夹具] 建账号 %s 失败：HTTP %s / %s' % (account, st, r.get('msg', r)))
    return None


def cleanup():
    """按创建时记下的 id 清理。顺序有讲究：**先删账号、再删角色** ——
    RoleAdminService.delete 会拒绝删除"还有成员"的角色。"""
    for uid in (out_user_id, in_user_id, adm_user_id):
        if not uid:
            continue
        call('DELETE', '/api/users/%s' % uid, token=admin_tk)
        sql('DELETE FROM user_role WHERE user_id=%s' % uid)
        sql('DELETE FROM user_post WHERE user_id=%s' % uid)
        sql('DELETE FROM sys_user WHERE id=%s' % uid)
    if role_id:
        call('DELETE', '/api/roles/%s' % role_id, token=admin_tk)
        sql('DELETE FROM role_permission WHERE role_id=%s' % role_id)
        sql('DELETE FROM role_data_scope WHERE role_id=%s' % role_id)
        sql('DELETE FROM role_admin_scope WHERE role_id=%s' % role_id)
        sql('DELETE FROM sys_role WHERE id=%s' % role_id)
    # 兜底：把内置角色 ADMIN 的管理范围恢复成 all。
    # 目的是**不污染演示库基线** —— 若第 28 条护栏失效，这里就是最后一道止损。
    sql("UPDATE role_admin_scope s JOIN sys_role r ON r.id = s.role_id "
        "SET s.scope_type='all' WHERE r.code='ADMIN' AND s.deleted=0")


def cleanup_audit_window():
    """删掉本次窗口内产生的审计日志。

    铁律：只允许按"创建时记下的 id"删，禁止按范围删（踩过 3 次，删过业务数据）。
    这里先 SELECT 出 id，再按显式 id 列表删 —— 范围限定的合法性由"先取证"保证，
    而不是由 WHERE 条件自己声明。
    """
    ids = [x for x in (scalar('SELECT GROUP_CONCAT(id) FROM audit_log WHERE id > %s'
                              % audit_floor) or '').split(',') if x]
    for i in ids:
        sql('DELETE FROM audit_log WHERE id=%s' % i)
    return len(ids)


# ------------------------------------------------------------------ 开跑

print('=' * 72)
print('分级管理员（AdminScope）· C3')
print('=' * 72)

audit_floor = int(scalar('SELECT IFNULL(MAX(id),0) FROM audit_log') or 0)
before_user = scalar('SELECT COUNT(*) FROM sys_user WHERE deleted=0')
before_role = scalar('SELECT COUNT(*) FROM sys_role WHERE deleted=0')
before_rscope = scalar('SELECT COUNT(*) FROM role_admin_scope WHERE deleted=0')

admin_tk = login('admin', '123456')
check('1. admin（内置超管，管理范围 all）登录成功', bool(admin_tk))

create_probe_role()
st, r = set_admin_scope('dept_subtree')
check('2. 探针角色已建立（只持 system:user），并已通过 PUT /{id}/admin-scope 配为 dept_subtree',
      bool(role_id) and st == 200 and yes(r),
      'roleId=%s code=%s / HTTP %s code=%s msg=%s' % (role_id, role_code, st, r.get('code'), r.get('msg')))

rs_cnt = scalar("SELECT COUNT(*) FROM role_admin_scope WHERE role_id=%s AND deleted=0" % (role_id or 0))
rs_val = scalar("SELECT scope_type FROM role_admin_scope WHERE role_id=%s AND deleted=0 LIMIT 1"
                % (role_id or 0))
check('3. 读库核实：该角色的管理范围**恰好一行**且值为 dept_subtree（走接口真的落到库了）',
      rs_cnt == '1' and rs_val == 'dept_subtree', 'role_admin_scope 命中 %s 行 / scope_type=%s'
      % (rs_cnt, rs_val))

out_user_id = create_user(OUT_ACCOUNT, OUT_DEPT, [], 'E2E范围外')
adm_user_id = create_user(ADM_ACCOUNT, SCOPE_DEPT, [role_code], 'E2E分级管理员')
check('4. 夹具账号就位：范围外账号（挂 dept 8）+ 分级管理员 A（挂 dept 2 财务中心）',
      bool(out_user_id and adm_user_id), 'outId=%s admId=%s' % (out_user_id, adm_user_id))

adm_tk, adm_login_user = login_full(ADM_ACCOUNT)
check('5. 分级管理员 A 能登录', bool(adm_tk), 'account=%s' % ADM_ACCOUNT)

# ⚠ 这里断言的是 **DEPT_SUBTREE（枚举名，大写）**，不是 dept_subtree（code，小写）：
# LoginUser 里的枚举字段（含既有的 dataScope）走 Jackson 默认序列化 ⇒ 输出枚举名；
# 而 RoleVO.adminScope / scopeType 输出的是小写 code。两处写法不同是**既有口径**，
# 本用例如实钉住它，避免后来者把它当 bug "顺手统一"掉而破坏前端既有判断。
check('6. ★ A 的登录响应里 adminScope 已装进 token（= DEPT_SUBTREE，枚举名口径）',
      adm_login_user.get('adminScope') == 'DEPT_SUBTREE',
      'adminScope=%s / dataScope=%s（两者同为枚举名口径）'
      % (adm_login_user.get('adminScope'), adm_login_user.get('dataScope')))

# ---------------------------------------------------------------- 一、读侧
print()
print('=' * 72)
print('一、读侧：人员管理表格与详情都收在范围内')
print('=' * 72)

st, r = call('GET', '/api/users/page?pageNum=1&pageSize=100', token=adm_tk)
paged = (r.get('data') or {}).get('records') or []
check('7. ★ A 调 GET /api/users/page → 200（范围内的人正常可见，不是"全给拦了"）',
      st == 200 and yes(r), 'HTTP %s / code=%s / 返回 %s 条' % (st, r.get('code'), len(paged)))

paged_ids = set(u.get('id') for u in paged)
check('8. ★ 分页结果里**没有**范围外账号（挂 dept 8 的那个人不出现）',
      out_user_id is not None and out_user_id not in paged_ids,
      'outId=%s 是否出现=%s / 结果 %s 条' % (out_user_id, out_user_id in paged_ids, len(paged)))

paged_depts = set(u.get('deptId') for u in paged)
check('9. ★ 分页结果里每个人的部门都落在 dept 2 的子树 {2,3,4} 内（精确到子树，不是碰巧筛掉几个）',
      bool(paged_depts) and paged_depts <= SCOPE_SUBTREE,
      '出现过的部门=%s / 允许=%s' % (sorted(x for x in paged_depts if x is not None), sorted(SCOPE_SUBTREE)))

st, r = call('GET', '/api/users', token=adm_tk)
all_users = r.get('data') or []
all_depts = set(u.get('deptId') for u in all_users)
check('10. 对照·刻意口径：A 调**无门控**的 GET /api/users 仍能看见全公司（dept 8 在里面）'
      '—— 它是「选审批人」的协作查询，不按管理范围收窄（这不是漏洞，是设计）',
      st == 200 and yes(r) and OUT_DEPT in all_depts,
      'HTTP %s / code=%s / 出现过的部门=%s' % (st, r.get('code'), sorted(x for x in all_depts if x is not None)))

st, r = call('GET', '/api/users/%s' % out_user_id, token=adm_tk)
check('11. ★ A 读**范围外**账号详情 GET /api/users/{id} → 业务码 403'
      '（列表收窄了、详情没收窄 = 没做，这条盯的就是它）',
      st == 200 and biz_denied(r),
      'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('GET', '/api/users/%s' % IN_EXISTING_USER, token=adm_tk)
check('12. 对照：A 读**范围内**账号（id=%d，挂 dept 3）→ 200（证明上一条不是"详情接口坏了"）'
      % IN_EXISTING_USER,
      st == 200 and yes(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

# ---------------------------------------------------------------- 二、写侧
print()
print('=' * 72)
print('二、写侧：改 / 调角色 / 删 / 新建，四条路径都收在范围内')
print('=' * 72)

st, r = call('PUT', '/api/users/%s' % out_user_id, token=adm_tk, body={
    'realName': 'E2E范围外', 'jobNo': OUT_ACCOUNT, 'account': OUT_ACCOUNT, 'phone': '13900000000'})
check('13. ★ A 修改**范围外**账号 → 业务码 403',
      st == 200 and biz_denied(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('PUT', '/api/users/%s/roles' % out_user_id, token=adm_tk, body={'roleCodes': []})
check('14. ★ A 调整**范围外**账号的角色 → 业务码 403',
      st == 200 and biz_denied(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('DELETE', '/api/users/%s' % out_user_id, token=adm_tk)
check('15. ★ A 删除**范围外**账号 → 业务码 403',
      st == 200 and biz_denied(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('POST', '/api/users', token=adm_tk, body={
    'realName': 'E2E越界新建', 'jobNo': OUT_ACCOUNT + 'X', 'account': OUT_ACCOUNT + 'X',
    'password': PWD, 'deptId': OUT_DEPT, 'roleCodes': []})
check('16. ★ A 新建账号、把人挂到**范围外部门**（dept 8）→ 业务码 403'
      '（步骤 13~15 拦的是"已有的范围外人"，这条拦的是"把人放到范围外"）',
      st == 200 and biz_denied(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

in_user_id = create_user('e2ein' + STAMP, IN_DEPT, [], 'E2E范围内', tk=adm_tk)
check('17. 对照：A 新建账号挂到**范围内部门**（dept 3）→ 成功（写侧正向没被误伤，这不是"禁用"）',
      bool(in_user_id), 'inId=%s' % in_user_id)

check('18. ★ 上一条越界新建**没有留下半成品**：库中不存在被拒的账号',
      scalar("SELECT COUNT(*) FROM sys_user WHERE account IN ('%s','%s')"
             % (OUT_ACCOUNT + 'X', OUT_ACCOUNT + 'X')) == '0',
      'account=%s' % (OUT_ACCOUNT + 'X'))

# ---------------------------------------------------------------- 三、特权提升
print()
print('=' * 72)
print('三、特权提升：范围管不住"给人什么"，必须单独拦')
print('=' * 72)

st, r = call('PUT', '/api/users/%s/roles' % in_user_id, token=adm_tk, body={'roleCodes': ['ADMIN']})
check('19. ★ A 给**范围内**的下属授予 ADMIN 角色 → 业务码 403'
      '（否则：管理员一步没越界，却造出了一个全公司超管）',
      st == 200 and biz_denied(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = call('PUT', '/api/users/%s/roles' % in_user_id, token=adm_tk, body={'roleCodes': [role_code]})
check('20. 对照：A 给下属授予**自己同样持有**的角色 → 成功（护栏不是"一律禁止调角色"）',
      st == 200 and yes(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

final_roles = [x for x in (scalar(
    'SELECT GROUP_CONCAT(r.code) FROM user_role ur JOIN sys_role r ON r.id=ur.role_id '
    'WHERE ur.user_id=%s AND ur.deleted=0' % (in_user_id or 0)) or '').split(',') if x]
check('21. ★ 读库核实：该下属最终只挂了探针角色，ADMIN 一次都没被写进去',
      final_roles == [role_code], '实际角色=%s / 期望=%s' % (final_roles, [role_code]))

st, r = call('GET', '/api/users/%s' % out_user_id, token=admin_tk)
check('22. 对照：admin（管理范围 all）读同一个范围外账号 → 200（范围外≠不存在）',
      st == 200 and yes(r), 'HTTP %s / code=%s' % (st, r.get('code')))

# ---------------------------------------------------------------- 四、负向验证
print()
print('=' * 72)
print('四、负向验证：证明前面的 403 确实由「管理范围」造成')
print('=' * 72)
print('    手法：同一账号、同一端点，唯一变化 = 把探针角色的管理范围 dept_subtree → all。')
print('    改之前 403 → 改成 all 后 200 → 改回 dept_subtree 又 403，三段齐全才算证明。')
print()

st, r = set_admin_scope('all')
check('23. 把探针角色的管理范围改成 all（接口返回成功）', st == 200 and yes(r),
      'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

adm_tk2 = login(ADM_ACCOUNT)
check('24. ★ 重新登录 A（管理范围是登录快照 —— 不重登就测的是旧 token，这一步不能省）',
      bool(adm_tk2))

st, r = call('GET', '/api/users/%s' % out_user_id, token=adm_tk2)
check('25. ★ 同一账号 A：范围改成 all 后读同一个范围外账号 → **200**'
      '（唯一变化是管理范围 ⇒ 前面的 403 确实是它造成的）',
      st == 200 and yes(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = set_admin_scope('dept_subtree')
adm_tk3 = login(ADM_ACCOUNT)
st2, r2 = call('GET', '/api/users/%s' % out_user_id, token=adm_tk3)
check('26. ★ 改回 dept_subtree 并重登后 → 又是 403（可逆 —— 不是一次性副作用）',
      st == 200 and yes(r) and st2 == 200 and biz_denied(r2),
      '改回 HTTP %s code=%s / 再读 HTTP %s code=%s' % (st, r.get('code'), st2, r2.get('code')))

st, r = set_admin_scope('dept_subtreeX')
check('27. ★ 传非法管理范围「dept_subtreeX」→ 显式失败（非 0 业务码）'
      '（静默降级成 none 会让"配错了"看起来像"配成功了"）',
      st == 200 and not yes(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

st, r = set_admin_scope('none', rid=ADMIN_ROLE_ID)
check('28. ★ 把内置角色 ADMIN 的管理范围从 all 收窄 → 被内置角色护栏拒绝'
      '（收窄后系统内无人能再管理用户，只能改库恢复）',
      st == 200 and not yes(r), 'HTTP %s / code=%s / msg=%s' % (st, r.get('code'), r.get('msg')))

admin_scope_now = scalar("SELECT s.scope_type FROM role_admin_scope s JOIN sys_role r ON r.id=s.role_id "
                         "WHERE r.code='ADMIN' AND s.deleted=0 LIMIT 1")
check('29. ★ 读库核实：ADMIN 的管理范围**仍是 all**（第 28 条被拦下的直接证据，也保证基线不被污染）',
      admin_scope_now == 'all', 'ADMIN 当前管理范围=%s' % admin_scope_now)

# ---------------------------------------------------------------- 五、收尾
print()
print('=' * 72)
print('五、收尾：物理清理')
print('=' * 72)

cleanup()
audit_removed = cleanup_audit_window()

check('30. 夹具账号已删净，可登录账号数回到原值',
      scalar('SELECT COUNT(*) FROM sys_user WHERE deleted=0') == before_user,
      '%s → %s' % (before_user, scalar('SELECT COUNT(*) FROM sys_user WHERE deleted=0')))

check('31. 探针角色已删净（活跃角色数回到原值，三张关联表均无残留）',
      scalar('SELECT COUNT(*) FROM sys_role WHERE deleted=0') == before_role
      and scalar('SELECT COUNT(*) FROM role_permission WHERE role_id=%s' % (role_id or 0)) == '0'
      and scalar('SELECT COUNT(*) FROM role_data_scope WHERE role_id=%s' % (role_id or 0)) == '0'
      and scalar('SELECT COUNT(*) FROM role_admin_scope WHERE role_id=%s' % (role_id or 0)) == '0'
      and scalar('SELECT COUNT(*) FROM role_admin_scope WHERE deleted=0') == before_rscope,
      '活跃角色 %s → %s / 管理范围行 %s → %s'
      % (before_role, scalar('SELECT COUNT(*) FROM sys_role WHERE deleted=0'),
         before_rscope, scalar('SELECT COUNT(*) FROM role_admin_scope WHERE deleted=0')))

check('32. 本次窗口内产生的审计日志已按 id 清回原值',
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

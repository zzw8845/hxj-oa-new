# -*- coding: utf-8 -*-
"""
接口级验证：主数据写接口（部门 / 岗位 / 字典）。

关注点不是"能不能成功"，而是这几件**错了也看不出来**的事：
  1. 越权：写接口若只靠登录态，任何能登录的账号都能改组织架构；
  2. 部门物化路径 path 的正确性 —— 行级数据权限的 LIKE '/2/%' 全靠它，
     格式错了不会报错，只会"某些人看不到某些单据"；
  3. 引用守卫：删掉被引用的部门/岗位，逻辑删除不会触发外键，
     库里悄无声息地留下悬空引用；
  4. 逻辑删除的唯一键让位（"建→删→再建→再删"第二次删除会撞键报 500）；
  5. 字典查重不能指望索引 —— uk_dict 里 company_id 可为 NULL，
     而 MySQL 唯一索引认为多个 NULL 互不相同，重复项能插进去。

自建数据最后**物理删除**：逻辑删除的行会留在表里，演示库快照就对不上了。
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

# 断言条数自证：脚本若在上游抛错，后面的断言会静默不执行、末行照样打印"通过"。
# 本项目被这个坑过一次，所以每个套件都必须声明预期条数。
EXPECTED_TOTAL = 37

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


def sql(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def sql_scalar(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


# 自建数据的统一前缀，便于**物理**清理（含上一次跑挂了留下的残骸）
D_CODE = 'ZZT_DEPT'
P_CODE = 'ZZT_POST'
DICT_TYPE = 'zztest_type'


def physical_cleanup(label):
    """物理删除本脚本造的数据。逻辑删除的行留着会让演示库快照对不上。"""
    sql("DELETE FROM department WHERE code LIKE 'ZZT%'")
    sql("DELETE FROM post WHERE code LIKE 'ZZT%'")
    sql("DELETE FROM sys_dict WHERE dict_type = '%s'" % DICT_TYPE)
    print('      （已物理清理自建主数据：%s）' % label)


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


print('=' * 72)
print('一、准备：登录 + 读接口')
print('=' * 72)

physical_cleanup('开跑前，清掉上次残骸')

admin = login('admin')
check('admin 登录成功', bool(admin))

st, r = call('GET', '/api/posts', token=admin)
posts = r.get('data') or []
check('GET /api/posts 返回岗位列表（此前无此接口）',
      st == 200 and len(posts) >= 7, '共 %d 个岗位' % len(posts))

st, r = call('GET', '/api/depts', token=admin)
check('GET /api/depts 仍可读', st == 200 and len(r.get('data') or []) >= 8,
      '共 %d 个部门' % len(r.get('data') or []))

# 找一个没有 system:dept 的账号（库里只有 admin 有）
weak = login('linjl')
check('弱权限账号 linjl 登录成功（用于越权验证）', bool(weak))

print()
print('=' * 72)
print('二、授权校验：无 system:dept / system:dict 的账号必须被 403 拦住')
print('=' * 72)

if weak:
    st, _ = call('POST', '/api/depts', token=weak, body={'name': '越权部门'})
    check('无 system:dept 新建部门 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('POST', '/api/posts', token=weak, body={'name': '越权岗位'})
    check('无 system:dept 新建岗位 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('PUT', '/api/depts/1', token=weak, body={'name': '越权改名'})
    check('无 system:dept 编辑部门 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('DELETE', '/api/depts/1', token=weak)
    check('无 system:dept 删除部门 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('POST', '/api/dicts', token=weak,
                 body={'dictType': DICT_TYPE, 'dictCode': 'X', 'dictLabel': 'x'})
    check('无 system:dict 新建字典项 → 403', st == 403, 'HTTP %d' % st)
    st, r = call('GET', '/api/posts', token=weak)
    check('弱权限账号仍可读岗位（读接口不收紧）', st == 200, 'HTTP %d' % st)
    # 越权尝试不应留下任何数据
    leaked = sql_scalar("SELECT COUNT(*) FROM department WHERE name LIKE '越权%'")
    check('越权尝试没有落库（403 之前就拦住了）', leaked == '0', '落库 %s 行' % leaked)
else:
    for _ in range(6):
        check('（跳过）弱权限账号不可用', False)

print()
print('=' * 72)
print('三、部门写接口：物化路径 / 局部更新 / 不支持移动')
print('=' * 72)

st, r = call('POST', '/api/depts', token=admin, body={'name': 'ZZT 顶级中心', 'code': D_CODE})
top = r.get('data') or {}
check('新建顶级部门成功', st == 200 and top.get('id'), 'HTTP %d / %s' % (st, r.get('msg', '')))
top_id = top.get('id')

check('顶级部门 path 自包含且格式为 /{id}/',
      top.get('path') == '/%s/' % top_id, 'path=%s（期望 /%s/）' % (top.get('path'), top_id))
check('顶级部门 level=1 且 dept_type=1（由"有无上级"推导，不由调用方指定）',
      top.get('level') == 1 and top.get('deptType') == 1,
      'level=%s dept_type=%s' % (top.get('level'), top.get('deptType')))

st, r = call('POST', '/api/depts', token=admin,
             body={'name': 'ZZT 子部门', 'code': D_CODE + '_SUB', 'parentId': top_id})
sub = r.get('data') or {}
sub_id = sub.get('id')
check('新建子部门成功', st == 200 and sub_id, 'HTTP %d / %s' % (st, r.get('msg', '')))
check('子部门 path = 父 path + 自己 id（父路径已带尾斜杠，不能拼成 //）',
      sub.get('path') == '/%s/%s/' % (top_id, sub_id),
      'path=%s（期望 /%s/%s/）' % (sub.get('path'), top_id, sub_id))
check('子部门 level=2 且 dept_type=2',
      sub.get('level') == 2 and sub.get('deptType') == 2,
      'level=%s dept_type=%s' % (sub.get('level'), sub.get('deptType')))

st, r = call('POST', '/api/depts', token=admin, body={'name': 'ZZT 撞码', 'code': D_CODE})
check('部门编码重复 → 业务拒绝（200+code≠0，不是 500）',
      st == 200 and r.get('code') != 0 and '已存在' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, r.get('msg', '')))

# 顺带把"业务拒绝 ≠ 参数校验失败"这个约定钉住：超长 name 才是真正的 400
st, r = call('POST', '/api/depts', token=admin, body={'name': 'Z' * 100, 'code': D_CODE + '_LONG'})
check('名称超长 → 400（@Valid 参数校验走的是 400，与上面的 200+code 不是一回事）',
      st == 400, 'HTTP %d' % st)

st, r = call('PUT', '/api/depts/%s' % sub_id, token=admin, body={'name': 'ZZT 子部门改名'})
d = r.get('data') or {}
check('PUT 只传 name 就能改（验证 DTO 不加 @NotBlank 的决定）',
      st == 200 and d.get('name') == 'ZZT 子部门改名',
      'HTTP %d / name=%s' % (st, d.get('name')))
check('局部更新没有把未传的字段抹掉（path/level 保持原值）',
      d.get('path') == '/%s/%s/' % (top_id, sub_id) and d.get('level') == 2,
      'path=%s level=%s' % (d.get('path'), d.get('level')))

st, r = call('PUT', '/api/depts/%s' % sub_id, token=admin, body={'parentId': 1})
check('试图移动部门 → 被拒且说明是刻意不支持',
      st == 200 and r.get('code') != 0 and '不支持' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:40]))

st, r = call('DELETE', '/api/depts/%s' % top_id, token=admin)
check('删除有子部门的部门 → 被拦',
      st == 200 and r.get('code') != 0 and '子部门' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:40]))

busy_dept = sql_scalar("SELECT dept_id FROM sys_user WHERE deleted=0 AND dept_id IS NOT NULL LIMIT 1")
st, r = call('DELETE', '/api/depts/%s' % busy_dept, token=admin)
check('删除有员工的部门 → 被拦',
      st == 200 and r.get('code') != 0 and '员工' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:40]))

print()
print('=' * 72)
print('四、岗位 / 字典写接口 + 逻辑删除的唯一键让位')
print('=' * 72)

st, r = call('POST', '/api/posts', token=admin, body={'name': 'ZZT 岗位', 'code': P_CODE})
p = r.get('data') or {}
check('新建岗位成功', st == 200 and p.get('id'), 'HTTP %d / %s' % (st, r.get('msg', '')))
p_id = p.get('id')

st, r = call('POST', '/api/posts', token=admin, body={'name': 'ZZT 岗位2', 'code': P_CODE})
check('岗位编码重复 → 业务拒绝', st == 200 and r.get('code') != 0,
      'HTTP %d / %s' % (st, r.get('msg', '')))

st, r = call('POST', '/api/posts', token=admin, body={'name': 'ZZT 无编码岗位'})
auto_code = (r.get('data') or {}).get('code')
check('岗位编码留空则自动生成 P0xx', st == 200 and (auto_code or '').startswith('P'),
      'code=%s' % auto_code)
auto_id = (r.get('data') or {}).get('id')

busy_post = sql_scalar("SELECT post_id FROM sys_user WHERE deleted=0 AND post_id IS NOT NULL LIMIT 1")
st, r = call('DELETE', '/api/posts/%s' % busy_post, token=admin)
check('删除有人在任的岗位 → 被拦',
      st == 200 and r.get('code') != 0 and '员工' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:40]))

# 建→删→再建→再删：唯一键含 deleted，不让位的话第二次删除会撞键报 500
st, r = call('POST', '/api/posts', token=admin, body={'name': 'ZZT 让位岗', 'code': P_CODE + '_K'})
k1 = (r.get('data') or {}).get('id')
st1, _ = call('DELETE', '/api/posts/%s' % k1, token=admin)
st, r = call('POST', '/api/posts', token=admin, body={'name': 'ZZT 让位岗2', 'code': P_CODE + '_K'})
k2 = (r.get('data') or {}).get('id')
st2, r2 = call('DELETE', '/api/posts/%s' % k2, token=admin)
check('同一编码「建→删→再建→再删」两次删除都成功（UniqueKeys.release）',
      st1 == 200 and st2 == 200, '第一次=%s 第二次=%s %s' % (st1, st2, (r2.get('msg') or '')[:30]))

st, r = call('POST', '/api/dicts', token=admin,
             body={'dictType': DICT_TYPE, 'dictCode': 'A', 'dictLabel': '甲'})
d1 = r.get('data') or {}
check('新建字典项成功', st == 200 and d1.get('id'), 'HTTP %d / %s' % (st, r.get('msg', '')))

st, r = call('POST', '/api/dicts', token=admin,
             body={'dictType': DICT_TYPE, 'dictCode': 'A', 'dictLabel': '甲重复'})
check('同一 (dictType, dictCode) 重复 → 被拦（uk_dict 里 NULL 不参与唯一，必须自己查）',
      st == 200 and r.get('code') != 0 and '已存在' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, r.get('msg', '')))

st, r = call('PUT', '/api/dicts/%s' % d1.get('id'), token=admin, body={'dictLabel': '甲改名'})
check('PUT 只改字典名称 → 200（不多传 dictCode 也不报"不能为空"）',
      st == 200 and (r.get('data') or {}).get('dictLabel') == '甲改名',
      'HTTP %d / %s' % (st, r.get('msg', '')))

st, r = call('POST', '/api/dicts', token=admin,
             body={'dictType': DICT_TYPE, 'dictCode': 'B', 'dictLabel': '乙'})
b1 = (r.get('data') or {}).get('id')
st1, _ = call('DELETE', '/api/dicts/%s' % b1, token=admin)
st, r = call('POST', '/api/dicts', token=admin,
             body={'dictType': DICT_TYPE, 'dictCode': 'B', 'dictLabel': '乙2'})
b2 = (r.get('data') or {}).get('id')
st2, _ = call('DELETE', '/api/dicts/%s' % b2, token=admin)
check('字典项同样支持「建→删→再建→再删」', st1 == 200 and st2 == 200,
      '第一次=%s 第二次=%s' % (st1, st2))

print()
print('=' * 72)
print('五、留痕与收尾')
print('=' * 72)

audit = sql_scalar("SELECT COUNT(*) FROM audit_log WHERE module='system' "
                   "AND action IN ('createDept','updateDept','deleteDept','createPost','createDict') "
                   "AND created_at >= NOW() - INTERVAL 10 MINUTE")
check('主数据写操作已进审计日志（@Audit module=system）', int(audit or 0) >= 8, '近 10 分钟 %s 条' % audit)

# 收尾：删掉本次自建部门/岗位（先子后父），再物理清理
if sub_id:
    call('DELETE', '/api/depts/%s' % sub_id, token=admin)
if top_id:
    call('DELETE', '/api/depts/%s' % top_id, token=admin)
if p_id:
    call('DELETE', '/api/posts/%s' % p_id, token=admin)
if auto_id:
    call('DELETE', '/api/posts/%s' % auto_id, token=admin)
physical_cleanup('收尾')

left_dept = sql_scalar("SELECT COUNT(*) FROM department WHERE code LIKE 'ZZT%'")
left_post = sql_scalar("SELECT COUNT(*) FROM post WHERE code LIKE 'ZZT%'")
left_dict = sql_scalar("SELECT COUNT(*) FROM sys_dict WHERE dict_type='%s'" % DICT_TYPE)
check('自建主数据已物理清干净（不留逻辑删除残骸）',
      left_dept == '0' and left_post == '0' and left_dict == '0',
      'dept=%s post=%s dict=%s' % (left_dept, left_post, left_dict))

demo_dept = sql_scalar("SELECT COUNT(*) FROM department WHERE deleted=0")
demo_post = sql_scalar("SELECT COUNT(*) FROM post WHERE deleted=0")
check('演示部门数未变（8）', demo_dept == '8', '实际 %s' % demo_dept)
check('演示岗位数未变（10，含既有 POST_001/002）', demo_post == '10', '实际 %s' % demo_post)

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

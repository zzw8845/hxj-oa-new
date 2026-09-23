# -*- coding: utf-8 -*-
"""
接口级验证：单据类型管理（/api/document-types）。

关注点同样是"错了也看不出来"的事：
  1. 越权：写接口挂 system:docType —— 普通员工（林安然）必须 403，不能只靠登录态；
  2. category 枚举白名单：管理员发明新大类（如 PURCHASE）必须被后端拒绝
     —— 业务类型是系统级枚举，这是整个前端"不写死映射"改造的前提；
  3. 编码/名称查重 + 格式（字母开头）；
  4. 删除守卫：绑定过模板/流程的类型删除必须被拒（演示库 3 个类型全有绑定，天然守卫样本）；
  5. 逻辑删除的唯一键让位：「建→删→再建→再删」第二次删除撞 uk_doctype_code 报 500；
  6. categoryLabel 由后端下发（前端不写死映射的改造点）；
  7. 自建数据最后**物理删除**，演示库 document_type 必须回到 3 条。
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

EXPECTED_TOTAL = 24

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


print('== 前置：登录（admin 重登拿到新权限点 system:docType 的 JWT 快照） ==')
admin = login('admin')
staff = login('linjl')
check('管理员登录成功', bool(admin))
check('普通员工登录成功', bool(staff))
# 单据类型总数在跑前必须记下来：守恒判据是"回到跑前"，不是"等于某个写死的数"
base_dt_count = sql_scalar("SELECT COUNT(*) FROM document_type WHERE deleted=0")

CODE = 'ZZT_DOCTYPE'

print()
print('== 1. 越权：写接口必须挂权限点 ==')
st, r = call('POST', '/api/document-types', token=staff,
             body={'code': CODE, 'name': '越权测试', 'category': 'DAILY'})
check('员工创建单据类型 -> 403', st == 403, '实际 %s %s' % (st, r.get('message', '')))
# 注意口径：业务层 BizException 由全局异常处理器转成 **HTTP 200 + 包体 code:400**（与前端 http() 同口径）；
# 只有安全层（401/403）与 Bean Validation（@Pattern 等）才返回真实 HTTP 4xx。
st, r = call('POST', '/api/document-types',
             body={'code': CODE, 'name': '未登录', 'category': 'DAILY'})
check('未登录创建 -> 401', st == 401, '实际 %s' % st)

print()
print('== 2. category 白名单与格式校验 ==')
st, r = call('POST', '/api/document-types', token=admin,
             body={'code': CODE, 'name': '自创大类测试', 'category': 'PURCHASE'})
check('发明新业务大类 -> 拒绝', r.get('code') == 400,
      'HTTP %s 包体 %s' % (st, r.get('msg') or r.get('message', '')))
st, r = call('POST', '/api/document-types', token=admin,
             body={'code': '9BAD', 'name': '坏编码', 'category': 'DAILY'})
check('编码不以字母开头 -> 拒绝', st == 400, '实际 %s' % st)

print()
print('== 3. 正常创建 + 查重 + categoryLabel 下发 ==')
st, r = call('POST', '/api/document-types', token=admin,
             body={'code': CODE, 'name': '自动化测试类型', 'category': 'BIZ',
                   'mustLinkPrev': 0, 'status': 1, 'sortNo': 99})
check('创建成功', st == 200, '实际 %s %s' % (st, r.get('message', '')))
dt_id = (r.get('data') or {}).get('id') if st == 200 else None
check('返回 categoryLabel=业务付款（后端下发）',
      st == 200 and r.get('data', {}).get('categoryLabel') == '业务付款',
      '实际 %s' % r.get('data', {}).get('categoryLabel'))

st, r = call('POST', '/api/document-types', token=admin,
             body={'code': CODE, 'name': '重名测试', 'category': 'DAILY'})
check('重复编码 -> 拒绝', r.get('code') == 400, 'HTTP %s 包体 %s' % (st, r.get('msg', '')))
st, r = call('POST', '/api/document-types', token=admin,
             body={'code': CODE + '_2', 'name': '自动化测试类型', 'category': 'DAILY'})
check('重复名称 -> 拒绝', r.get('code') == 400, 'HTTP %s 包体 %s' % (st, r.get('msg', '')))

st, r = call('GET', '/api/document-types', token=admin)
lst = r.get('data') or []
hit = [x for x in lst if x.get('code') == CODE]
check('管理列表包含新类型', st == 200 and len(hit) == 1)
check('业务列表（/documents/types）同样下发 categoryLabel',
      all('categoryLabel' in x for x in (call('GET', '/api/documents/types', token=admin)[1].get('data') or [])))
st, r = call('GET', '/api/document-types/categories', token=admin)
cats = r.get('data') or {}
check('类别字典接口返回 4 个白名单大类',
      st == 200 and set(cats.keys()) == {'DAILY', 'BIZ', 'REIMBURSE', 'SEAL'},
      '实际 %s' % sorted(cats.keys()))

print()
print('== 4. 建模板/流程前的类型可删（再建验证唯一键让位） ==')
st, r = call('DELETE', '/api/document-types/%s' % dt_id, token=admin)
check('未绑定任何配置时删除成功', st == 200, '实际 %s %s' % (st, r.get('message', '')))
st, r = call('POST', '/api/document-types', token=admin,
             body={'code': CODE, 'name': '自动化测试类型', 'category': 'BIZ',
                   'mustLinkPrev': 0, 'status': 1, 'sortNo': 99})
check('删除后同编码可重建', st == 200, '实际 %s %s' % (st, r.get('message', '')))
dt_id = (r.get('data') or {}).get('id') if st == 200 else None
st, r = call('DELETE', '/api/document-types/%s' % dt_id, token=admin)
check('第二次删除成功（唯一键已让位，不撞 uk_doctype_code）', st == 200, '实际 %s' % st)

print()
print('== 5. 删除守卫：绑定模板/流程/单据的类型必须拒绝 ==')
# ⚠ 只对**演示库这 3 个已知类型**断言，不要遍历全表。
#   原写法是 `SELECT ... FROM document_type WHERE deleted=0` 全表逐个 DELETE 试守卫，
#   有两个后果：
#     ① 断言条数随库里类型数漂移（有人新建一个类型，EXPECTED_TOTAL 闸门就误报）；
#     ② **真会删数据** —— 遇到一个没有任何绑定的类型，守卫放行，它就被真删了。
#   守卫本身仍由服务端保证，用例只需钉住"有绑定时必拒绝"这个事实。
DEMO_DT_CODES = ('DAILY_PAYMENT', 'REIMBURSE_EMPLOYEE', 'SEAL_APPLY')
guard_rows = sql_scalar("SELECT id, name FROM document_type WHERE deleted=0 AND code IN "
                        "('DAILY_PAYMENT','REIMBURSE_EMPLOYEE','SEAL_APPLY') ORDER BY id").splitlines()
check('演示库 3 个单据类型都在（守卫断言的对象）', len(guard_rows) == 3, '实际 %s 个' % len(guard_rows))
for row in guard_rows:
    did, name = row.split('\t')
    st, r = call('DELETE', '/api/document-types/%s' % did, token=admin)
    check('删除「%s」-> 拒绝' % name, r.get('code') == 400,
          'HTTP %s 包体 %s' % (st, r.get('msg', '')))

print()
print('== 6. 停用对发起菜单的影响 ==')
st, r = call('POST', '/api/document-types', token=admin,
             body={'code': CODE, 'name': '停用测试类型', 'category': 'DAILY',
                   'mustLinkPrev': 0, 'status': 0, 'sortNo': 99})
dt_id = (r.get('data') or {}).get('id') if st == 200 else None
check('创建停用状态类型成功', st == 200)
st, r = call('GET', '/api/documents/types', token=admin)
enabled_codes = [x.get('code') for x in (r.get('data') or [])]
check('停用类型不出现在启用清单', CODE not in enabled_codes)
call('DELETE', '/api/document-types/%s' % dt_id, token=admin)

print()
print('== 7. 清理与演示库守恒 ==')
left = sql_scalar("SELECT COUNT(*) FROM document_type WHERE code LIKE 'ZZT%' AND deleted=0")
check('无 ZZT 残留（逻辑删除前已物理核对）', left == '0', '实际 %s' % left)
subprocess.run(['mysql', '-uroot', DB, '-e',
                "DELETE FROM document_type WHERE code LIKE 'ZZT%'"], capture_output=True)
# 守恒 = 回到跑前那个数，而不是"必须等于 3"。
# 演示库里被人工加过测试类型（code=TEST），那是演示数据不是污染，
# 用例没资格要求它消失；本用例真正的不变量是"没多也没少"。
demo_dt = sql_scalar("SELECT COUNT(*) FROM document_type WHERE deleted=0")
check('演示库单据类型数守恒（回到跑前）', demo_dt == base_dt_count,
      '跑前 %s -> 跑后 %s' % (base_dt_count, demo_dt))

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

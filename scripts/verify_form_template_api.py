# -*- coding: utf-8 -*-
"""
接口级验证：表单模板维护（原型 §9「表单模板驱动」的落地入口）。

此前 `/api/forms/*` 只有 schema(读)、templates(读)、validate(校验) 三个接口，
**模板只能改库**。本用例钉住的不是"能不能存"，而是这几条错了很难反查的不变量：

  1. **改生效版本必须被拒** —— 单据只存了 form_template_ver 快照、没存 schema，
     就地改会让**在途单据的表单跟着变**；字段被删时旧数据里那些键变成
     "没人认识的孤儿"，详情页渲染不出来还查不出原因；
  2. **同一单据类型同时只能有一个生效版本** —— 否则 getEffective 取 version 最大的那条，
     界面上看到的与以为生效的不是同一个；
  3. **schema 结构化校验** —— key 唯一 / type 在白名单 / 规则引用的字段确实存在；
  4. **字段权限必须引用 schema 里存在的字段** —— 否则配了永远不生效，界面上却像配好了；
  5. **版本号由服务端分配**，不接受调用方指定（并发/手滑会撞 uk_form_tpl）。
  6. **回退能力**：废弃版本必须能重新启用（新版有问题时得退得回去）。
  7. **"建草稿 → 删 → 再建 → 再删"两次删除都要成功**
     （uk_form_tpl 含 deleted 的老坑；本实现用"草稿物理删除"规避）。

**收尾**：测试模板全部物理清理，并把该单据类型的生效版本切回原来那一版 ——
用例不能改掉演示环境的有效配置。
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
DOC_TYPE = 1  # 日常付款申请：原生效模板 id=1 v1

EXPECTED_TOTAL = 32

PASS, FAIL = [], []
created_ids = []


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
    return subprocess.run(['mysql', '-uroot', DB, '-e', stmt], capture_output=True, text=True).stdout.strip()


def scalar(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-B', '-e', stmt],
                          capture_output=True, text=True).stdout.strip()


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


def biz_fail(st, r):
    return st == 200 and r.get('code') != 0


def make_schema(extra_fields=None, rules=None):
    fields = [
        {'key': 'title', 'type': 'text', 'label': '申请事项', 'colSpan': 2, 'required': True},
        {'key': 'amount', 'type': 'money', 'label': '金额(元)', 'colSpan': 1, 'required': True},
    ]
    if extra_fields:
        fields.extend(extra_fields)
    s = {'docType': 'DAILY_PAYMENT', 'layout': 'two-column', 'fields': fields}
    if rules is not None:
        s['rules'] = rules
    return s


print('=' * 72)
print('表单模板维护')
print('=' * 72)

admin = login('admin')
check('admin 登录成功', bool(admin))
orig_active = scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0 "
                     "ORDER BY version DESC LIMIT 1" % DOC_TYPE)
check('取到该单据类型当前的生效模板', bool(orig_active), 'id=%s' % orig_active)
orig_version = scalar("SELECT version FROM form_template WHERE id=%s" % (orig_active or 0))
tpl_count_before = scalar('SELECT COUNT(*) FROM form_template WHERE deleted=0')

# ---------------------------------------------------------------- 一、权限
print()
print('=' * 72)
print('一、权限：没有 system:form 的账号一律 403')
print('=' * 72)
weak = login('linjl')
check('弱权限账号 linjl 登录成功', bool(weak))
if weak:
    st, _ = call('GET', '/api/forms/templates/versions?docTypeId=%d' % DOC_TYPE, token=weak)
    check('无权限看版本列表 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('POST', '/api/forms/templates', token=weak,
                 body={'docTypeId': DOC_TYPE, 'name': '越权', 'schema': make_schema()})
    check('无权限新建模板 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('PUT', '/api/forms/templates/%s' % orig_active, token=weak,
                 body={'docTypeId': DOC_TYPE, 'name': '越权改', 'schema': make_schema()})
    check('无权限改模板 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('POST', '/api/forms/templates/%s/activate' % orig_active, token=weak)
    check('无权限启用模板 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('DELETE', '/api/forms/templates/%s' % orig_active, token=weak)
    check('无权限删模板 → 403', st == 403, 'HTTP %d' % st)
    check('越权尝试没有落库', scalar('SELECT COUNT(*) FROM form_template WHERE deleted=0') == tpl_count_before,
          '模板数=%s' % scalar('SELECT COUNT(*) FROM form_template WHERE deleted=0'))
else:
    for _ in range(6):
        check('（跳过）弱权限账号不可用', False)

# ---------------------------------------------------------------- 二、新建
print()
print('=' * 72)
print('二、新建模板：落草稿 + 版本号由服务端分配')
print('=' * 72)

st, r = call('POST', '/api/forms/templates', token=admin,
             body={'docTypeId': DOC_TYPE, 'name': 'E2E 测试模板 v2', 'schema': make_schema()})
d = r.get('data') or {}
new_id = d.get('id')
if new_id:
    created_ids.append(new_id)
check('新建模板成功', st == 200 and bool(new_id), 'HTTP %d / %s' % (st, r.get('msg', '')))
check('★ 新建一律是草稿状态（不会直接生效）',
      d.get('status') == 0 and d.get('statusText') == '草稿', 'status=%s' % d.get('statusText'))
check('★ 版本号由服务端分配（原 v%s → v%s）',
      str(d.get('version')) == str(int(orig_version or 1) + 1),
      'version=%s' % d.get('version'))
check('草稿可编辑（editable=true）', d.get('editable') is True)

# ---------------------------------------------------------------- 三、schema 校验
print()
print('=' * 72)
print('三、schema 结构化校验（写坏了会"渲染崩 / 提交永远过不了"）')
print('=' * 72)

st, r = call('POST', '/api/forms/templates', token=admin,
             body={'docTypeId': DOC_TYPE, 'name': '坏模板1',
                   'schema': make_schema(extra_fields=[{'key': 'title', 'type': 'text', 'label': '重复key'}])})
check('字段 key 重复 → 业务拒绝', biz_fail(st, r) and '重复' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:50]))

st, r = call('POST', '/api/forms/templates', token=admin,
             body={'docTypeId': DOC_TYPE, 'name': '坏模板2',
                   'schema': make_schema(extra_fields=[{'key': 'x', 'type': 'unknownType', 'label': 'X'}])})
check('字段 type 不在白名单 → 业务拒绝且提示可用类型',
      biz_fail(st, r) and '不被支持' in (r.get('msg') or '') and 'money' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

st, r = call('POST', '/api/forms/templates', token=admin,
             body={'docTypeId': DOC_TYPE, 'name': '坏模板3',
                   'schema': make_schema(rules=[{'when': "payType == 'OTHER'", 'then': {'show': ['不存在字段']}}])})
check('显隐规则引用不存在的字段 → 业务拒绝',
      biz_fail(st, r) and '不存在的字段' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

# ---------------------------------------------------------------- 四、改
print()
print('=' * 72)
print('四、改模板：草稿可改，生效版本必须被拒')
print('=' * 72)

st, r = call('PUT', '/api/forms/templates/%s' % new_id, token=admin,
             body={'docTypeId': DOC_TYPE, 'name': 'E2E 测试模板 v2（改名）', 'schema': make_schema()})
check('改草稿成功', st == 200 and r.get('code') == 0
      and (r.get('data') or {}).get('name') == 'E2E 测试模板 v2（改名）',
      'HTTP %d / name=%s' % (st, (r.get('data') or {}).get('name')))

st, r = call('PUT', '/api/forms/templates/%s' % orig_active, token=admin,
             body={'docTypeId': DOC_TYPE, 'name': '试图改生效版', 'schema': make_schema()})
check('★ 改【生效中】的模板 → 业务拒绝，且提示新建版本',
      biz_fail(st, r) and '不允许修改' in (r.get('msg') or '') and '新建版本' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:70]))

# ---------------------------------------------------------------- 五、字段权限
print()
print('=' * 72)
print('五、字段级权限')
print('=' * 72)

st, r = call('PUT', '/api/forms/templates/%s/field-permissions' % new_id, token=admin,
             body=[{'nodeKey': 'n3', 'fieldKey': '不存在的字段', 'visible': True, 'editable': False}])
check('★ 字段权限引用 schema 里不存在的字段 → 业务拒绝',
      biz_fail(st, r) and '不在该模板' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

st, r = call('PUT', '/api/forms/templates/%s/field-permissions' % new_id, token=admin,
             body=[{'nodeKey': 'n3', 'fieldKey': 'amount', 'visible': False, 'editable': False},
                   {'nodeKey': '*', 'fieldKey': 'title', 'visible': True, 'editable': True}])
perms = (r.get('data') or {}).get('fieldPermissions') or []
check('配置字段权限成功且能读回',
      st == 200 and len(perms) == 2
      and any(p.get('fieldKey') == 'amount' and p.get('visible') == 0 for p in perms),
      'perms=%s' % json.dumps(perms, ensure_ascii=False)[:90])

st, r = call('PUT', '/api/forms/templates/%s/field-permissions' % new_id, token=admin,
             body=[{'nodeKey': 'n3', 'fieldKey': 'amount', 'visible': False, 'editable': False}])
check('★ 重复覆盖配置不撞唯一键（全量覆盖前物理清空）',
      st == 200 and len((r.get('data') or {}).get('fieldPermissions') or []) == 1,
      'perms 数=%s' % len((r.get('data') or {}).get('fieldPermissions') or []))

# ---------------------------------------------------------------- 六、启用与回退
print()
print('=' * 72)
print('六、启用新版本 / 回退旧版本')
print('=' * 72)

st, r = call('POST', '/api/forms/templates/%s/activate' % new_id, token=admin)
check('启用草稿成功', st == 200 and (r.get('data') or {}).get('status') == 1,
      'status=%s' % (r.get('data') or {}).get('status'))

check('★ 同单据类型的旧生效版本已被置为「废弃」',
      scalar("SELECT status FROM form_template WHERE id=%s" % orig_active) == '2',
      '旧版本状态=%s（2=废弃）' % scalar("SELECT status FROM form_template WHERE id=%s" % orig_active))

check('★ 同一单据类型同时只有一个生效版本',
      scalar("SELECT COUNT(*) FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE) == '1',
      '生效版本数=%s' % scalar("SELECT COUNT(*) FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE))

check('生效接口按 docType 取到的就是新版本',
      scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE) == str(new_id),
      '生效 id=%s' % scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE))

st, r = call('POST', '/api/forms/templates/%s/activate' % orig_active, token=admin)
check('★ 废弃版本可以重新启用（回退能力：新版有问题时退得回去）',
      st == 200 and (r.get('data') or {}).get('status') == 1,
      'HTTP %d / status=%s' % (st, (r.get('data') or {}).get('status')))
check('回退后新版本变为废弃，生效版本仍只有一个',
      scalar("SELECT status FROM form_template WHERE id=%s" % new_id) == '2'
      and scalar("SELECT COUNT(*) FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE) == '1',
      'v2=%s' % scalar("SELECT status FROM form_template WHERE id=%s" % new_id))

# ---------------------------------------------------------------- 七、删除
print()
print('=' * 72)
print('七、删除：只允许删草稿 + 反复建删不撞唯一键')
print('=' * 72)

st, r = call('DELETE', '/api/forms/templates/%s' % orig_active, token=admin)
check('★ 删【生效中】的模板 → 业务拒绝（历史凭据不能删）',
      biz_fail(st, r) and '只有草稿可删' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

st, r = call('POST', '/api/forms/templates', token=admin,
             body={'docTypeId': DOC_TYPE, 'name': 'E2E 待删草稿', 'schema': make_schema()})
draft_id = (r.get('data') or {}).get('id')
check('再建一张草稿用于删除验证', bool(draft_id), 'id=%s' % draft_id)
st1, _ = call('DELETE', '/api/forms/templates/%s' % draft_id, token=admin)

st, r = call('POST', '/api/forms/templates', token=admin,
             body={'docTypeId': DOC_TYPE, 'name': 'E2E 待删草稿（第二次）', 'schema': make_schema()})
draft_id2 = (r.get('data') or {}).get('id')
if draft_id2:
    created_ids.append(draft_id2)
st2, r2 = call('DELETE', '/api/forms/templates/%s' % draft_id2, token=admin)
check('★ 同一单据类型「建草稿 → 删 → 再建 → 再删」两次删除都成功（不撞唯一键）',
      st1 == 200 and st2 == 200,
      '第一次=%s 第二次=%s %s' % (st1, st2, (r2.get('msg') or '')[:30]))

# ---------------------------------------------------------------- 八、收尾
print()
print('=' * 72)
print('八、收尾：还原演示环境')
print('=' * 72)

# 只清理**本次创建的那些 id**，并把生效版本切回原来那一版。
#
# ⚠ 这里必须按"创建时记下的 id"删，绝不能按模式删（例如 `WHERE id > 原生效id`）——
# 第一版就是这么写的，把同表里的另外两个业务模板（员工报销单 / 用印申请单）一起删掉了，
# 只能从 sql/seed_data.sql 恢复。**测试的清理语句只允许碰自己造的数据。**
if created_ids:
    id_list = ','.join(str(i) for i in set(created_ids))
    sql('DELETE FROM form_field_permission WHERE template_id IN (%s)' % id_list)
    sql('DELETE FROM form_template WHERE id IN (%s)' % id_list)
sql("UPDATE form_template SET status=0 WHERE id=%s" % orig_active)  # 先置草稿，避免出现两个生效
sql("UPDATE form_template SET status=1, effective_to=NULL WHERE id=%s" % orig_active)

check('测试模板已全部物理清理（回到 %s 条）' % tpl_count_before,
      scalar('SELECT COUNT(*) FROM form_template WHERE deleted=0') == tpl_count_before,
      '实际 %s' % scalar('SELECT COUNT(*) FROM form_template WHERE deleted=0'))
check('该单据类型的生效版本已切回原来那一版（id=%s）' % orig_active,
      scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE) == str(orig_active)
      and scalar('SELECT COUNT(*) FROM form_template WHERE deleted=0') == tpl_count_before,
      '生效 id=%s' % scalar("SELECT id FROM form_template WHERE doc_type_id=%d AND status=1 AND deleted=0" % DOC_TYPE))

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

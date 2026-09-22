# -*- coding: utf-8 -*-
"""
接口级验证：用印台账与归还闭环。

这条链路此前**只有表和 Mapper，没有任何 service/controller**（两张表 0 行），
所以用例要钉住的不是"能不能登记"，而是这几件错了看不出来的事：
  1. **只有 SEAL 类单据能登记用印** —— 否则一张付款单也能写出一条用印记录，
     而台账是给审计看的，错一条就污染一整类数据的可信度；
  2. **状态机单向**：待用印 → 已用印 → 已归还，不能跳步、不能重复；
     每个越级动作都要得到**明确的业务提示**（200 + code≠0），不是 500；
  3. **每次动作写台账**（谁、何时、做了什么、备注）—— "可查"的价值就在这里；
  4. **草稿/已驳回/已撤回不允许用印**（没走完审批就盖章是内控红线）；
  5. **权限**：没有 `document:approve:seal` 的账号一律 403。

收尾：本次产生的 seal_apply / seal_record 行**物理删除**，让演示库回到 0/0
（逻辑删除的行会留在表里，把台账基线污染成"看不出原样"）。
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

EXPECTED_TOTAL = 30

PASS, FAIL = [], []
created_draft_id = None


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


def sql(statement):
    return subprocess.run(['mysql', '-uroot', DB, '-e', statement],
                          capture_output=True, text=True).stdout.strip()


def scalar(statement):
    return subprocess.run(['mysql', '-uroot', DB, '-N', '-B', '-e', statement],
                          capture_output=True, text=True).stdout.strip()


def login(account, pwd='123456'):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': pwd})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


def biz_fail(st, r):
    """业务规则拒绝：HTTP 200 + code≠0（不是 500，也不是 400）"""
    return st == 200 and r.get('code') != 0


print('=' * 72)
print('用印台账与归还闭环')
print('=' * 72)

admin = login('admin')
check('admin 登录成功', bool(admin))

# 选一张已通过（status=3）的 SEAL 单做闭环
seal_doc = scalar("SELECT id FROM document WHERE deleted=0 AND business_category='SEAL' "
                  "AND status=3 ORDER BY id LIMIT 1")
seal_doc2 = scalar("SELECT id FROM document WHERE deleted=0 AND business_category='SEAL' "
                   "AND status=3 ORDER BY id LIMIT 1 OFFSET 1")
non_seal = scalar("SELECT id FROM document WHERE deleted=0 AND business_category<>'SEAL' "
                  "AND status=3 ORDER BY id LIMIT 1")
check('取到两张已通过的用印单与一张非用印单',
      bool(seal_doc and seal_doc2 and non_seal),
      'seal=%s,%s 非seal=%s' % (seal_doc, seal_doc2, non_seal))

# ---------------------------------------------------------------- 一、权限
print()
print('=' * 72)
print('一、权限：没有 document:approve:seal 的账号一律 403')
print('=' * 72)
weak = login('linjl')
check('弱权限账号 linjl 登录成功（用于越权验证）', bool(weak))
if weak:
    st, _ = call('GET', '/api/seals?pageNum=1&pageSize=20', token=weak)
    check('无权限读用印台账 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('GET', '/api/seals/by-document/%s' % seal_doc, token=weak)
    check('无权限看某单据的用印状态 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('POST', '/api/seals/use', token=weak, body={'documentId': int(seal_doc)})
    check('无权限登记用印 → 403', st == 403, 'HTTP %d' % st)
    st, _ = call('POST', '/api/seals/return', token=weak, body={'documentId': int(seal_doc)})
    check('无权限归还 → 403', st == 403, 'HTTP %d' % st)
    leaked = scalar('SELECT COUNT(*) FROM seal_apply')
    check('越权尝试没有落库', leaked == '0', 'seal_apply 行数=%s' % leaked)
else:
    for _ in range(5):
        check('（跳过）弱权限账号不可用', False)

# ---------------------------------------------------------------- 二、负例
print()
print('=' * 72)
print('二、负例：非用印单 / 未用印就归还 / 草稿用印')
print('=' * 72)

st, r = call('POST', '/api/seals/use', token=admin, body={'documentId': int(non_seal)})
check('★ 对非 SEAL 类单据登记用印 → 业务拒绝',
      biz_fail(st, r) and '用印类' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

st, r = call('POST', '/api/seals/return', token=admin, body={'documentId': int(seal_doc2)})
check('★ 没登记用印就直接归还 → 业务拒绝',
      biz_fail(st, r) and '没有登记用印' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

# 新建一张 SEAL 草稿（不提交）来验证"草稿不允许用印"，跑完物理删掉
st, r = call('POST', '/api/documents', token=admin, body={
    'docTypeId': 3,
    'formData': {'title': 'E2E用印草稿（跑完即删）', 'sealType': 'OFFICIAL',
                 'sealProject': 'E2E用印草稿（跑完即删）', 'sealReason': 'E2E 夹具'},
    'title': 'E2E用印草稿（跑完即删）', 'amount': 0, 'reason': 'E2E 夹具', 'priority': 0
})
created_draft_id = (r.get('data') or {}).get('id') if st == 200 and r.get('code') == 0 else None
check('建一张用印草稿做负例', bool(created_draft_id), 'docId=%s' % created_draft_id)

if created_draft_id:
    st, r = call('POST', '/api/seals/use', token=admin, body={'documentId': created_draft_id})
    check('★ 草稿单据登记用印 → 业务拒绝（没走完审批不许盖章）',
          biz_fail(st, r) and '不允许用印' in (r.get('msg') or ''),
          'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))
else:
    check('（跳过）草稿未建成', False)

# ---------------------------------------------------------------- 三、闭环
print()
print('=' * 72)
print('三、闭环：待用印 → 登记用印 → 归还')
print('=' * 72)

st, r = call('GET', '/api/seals/by-document/%s' % seal_doc, token=admin)
d0 = r.get('data') or {}
check('未登记时返回「待用印」视图（而不是 404）',
      st == 200 and d0.get('id') is None and d0.get('returnStatus') == 0,
      'HTTP %d / status=%s id=%s' % (st, d0.get('returnStatus'), d0.get('id')))
check('  视图带出单据可读信息（单号/申请人/用章类型中文）',
      bool(d0.get('docNo')) and bool(d0.get('applicantName')) and d0.get('sealTypeName') == '合同章',
      'docNo=%s applicant=%s sealTypeName=%s' % (d0.get('docNo'), d0.get('applicantName'), d0.get('sealTypeName')))

st, r = call('POST', '/api/seals/use', token=admin,
             body={'documentId': int(seal_doc), 'remark': 'E2E 登记用印'})
d1 = r.get('data') or {}
check('登记用印成功', st == 200 and r.get('code') == 0, 'HTTP %d / %s' % (st, r.get('msg', '')))
check('★ 状态变为已用印且写了 seal_time', d1.get('returnStatus') == 1 and bool(d1.get('sealTime')),
      'status=%s sealTime=%s' % (d1.get('returnStatus'), d1.get('sealTime')))
check('★ 台账落了一条 use 记录（含操作人）',
      any(x.get('action') == 'use' and x.get('operatorName') for x in (d1.get('records') or [])),
      'records=%s' % [(x.get('action'), x.get('operatorName')) for x in (d1.get('records') or [])])

st, r = call('POST', '/api/seals/use', token=admin, body={'documentId': int(seal_doc)})
check('★ 重复登记用印 → 业务拒绝', biz_fail(st, r) and '已登记用印' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

st, r = call('POST', '/api/seals/return', token=admin,
             body={'documentId': int(seal_doc), 'remark': 'E2E 归还'})
d2 = r.get('data') or {}
check('归还成功', st == 200 and r.get('code') == 0, 'HTTP %d / %s' % (st, r.get('msg', '')))
check('★ 状态变为已归还且写了 return_at',
      d2.get('returnStatus') == 2 and bool(d2.get('returnAt')),
      'status=%s returnAt=%s' % (d2.get('returnStatus'), d2.get('returnAt')))
check('★ 台账共两条（use + return），闭环可追溯',
      len(d2.get('records') or []) == 2
      and [x.get('action') for x in (d2.get('records') or [])] == ['use', 'return'],
      'actions=%s' % [x.get('action') for x in (d2.get('records') or [])])

st, r = call('POST', '/api/seals/return', token=admin, body={'documentId': int(seal_doc)})
check('★ 重复归还 → 业务拒绝', biz_fail(st, r) and '已归还' in (r.get('msg') or ''),
      'HTTP %d / %s' % (st, (r.get('msg') or '')[:60]))

# ---------------------------------------------------------------- 四、台账
print()
print('=' * 72)
print('四、用印台账查询')
print('=' * 72)

st, r = call('GET', '/api/seals?pageNum=1&pageSize=20', token=admin)
pg = r.get('data') or {}
check('台账能查到刚闭环的那条', st == 200 and (pg.get('total') or 0) >= 1,
      'HTTP %d total=%s' % (st, pg.get('total')))
check('★ 台账行带出单号与用章类型', any(x.get('docNo') and x.get('sealTypeName')
                                       for x in (pg.get('records') or [])),
      '首行=%s' % json.dumps((pg.get('records') or [{}])[0], ensure_ascii=False)[:110])

st, r = call('GET', '/api/seals?returnStatus=2&pageNum=1&pageSize=20', token=admin)
pg2 = r.get('data') or {}
check('按「已归还」筛选生效', st == 200 and (pg2.get('total') or 0) >= 1
      and all(x.get('returnStatus') == 2 for x in (pg2.get('records') or [])),
      'total=%s' % pg2.get('total'))

st, r = call('GET', '/api/seals?returnStatus=0&pageNum=1&pageSize=20', token=admin)
pg3 = r.get('data') or {}
check('按「待用印」筛选生效（已归还的不该出现在这里）',
      st == 200 and all(x.get('returnStatus') == 0 for x in (pg3.get('records') or [])),
      'total=%s' % pg3.get('total'))

audit = scalar("SELECT COUNT(*) FROM audit_log WHERE module='seal' "
               "AND action IN ('useSeal','returnSeal') AND created_at >= NOW() - INTERVAL 10 MINUTE")
check('★ 用印/归还动作也进了审计日志（@Audit module=seal）', int(audit or 0) >= 2,
      '近 10 分钟 %s 条' % audit)

# ---------------------------------------------------------------- 五、收尾
print()
print('=' * 72)
print('五、收尾：物理清理本次产生的数据')
print('=' * 72)

sql('DELETE FROM seal_record')
sql('DELETE FROM seal_apply')
if created_draft_id:
    sql('DELETE FROM document WHERE id=%s' % created_draft_id)
    sql("DELETE FROM document_link WHERE document_id=%s" % created_draft_id)
    sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % created_draft_id)

check('seal_apply 已清空（演示库回到 0 行）', scalar('SELECT COUNT(*) FROM seal_apply') == '0')
check('seal_record 已清空（演示库回到 0 行）', scalar('SELECT COUNT(*) FROM seal_record') == '0')
check('测试草稿已删除，单据总数未变（46）',
      scalar('SELECT COUNT(*) FROM document') == '46',
      '实际 %s' % scalar('SELECT COUNT(*) FROM document'))

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

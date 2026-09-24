# -*- coding: utf-8 -*-
"""
接口级验证：流程条件分支可配置（POST/PUT /api/flows/configs 的 nodeItems[].branches）。

这块能力以前是**只能看不能改**：条件网关的 condition_expr 只有种子 SQL 写得进去，
界面上不显示、也传不回来。补上写路径之后，风险不在"能不能存"，而在下面这几件
**错了也照样返回 200** 的事：

  1. **表达式是代码**：它最终进 Flowable 的 UEL（JUEL）求值，JUEL 能调方法、能引用类型。
     把用户输入直接拼进条件表达式 = 开了一个任意代码执行入口。
     所以这里必须有一组"注入必须全部被拒"的断言，而不是只测正常值。
  2. **生成的 BPMN 必须是合法的**：网关之后第一个节点的入边**完全依赖分支**
     （BpmnGenerator 在网关处会把"上一条边"置空）。少一条覆盖，那个 userTask
     就没有 incoming sequenceFlow，部署时会失败。断言必须落到"部署成功"这个硬事实上，
     而不只是"库里多了一行"。
  3. **位置号语义**：分支目标用「nodeItems 位置号（1 开始）」，服务端负责翻译成 nodeKey。
     位置错一位不会报错，只会让流程悄悄走错分支 —— 必须断言 target 落到了预期的 node_key。
  4. **校验失败不能留半截配置**：失败矩阵跑完之后，版本数必须一条不涨。
  5. **改名不毁结构**：update 只传 name 时，以前是按节点名重新解析，
     网关会被解析成审批节点、分支和定制规则全丢。这是静默毁数据的老路，必须有回归断言。
  6. **收尾**：临时单据类型/流程/节点/规则按"创建时记下的 id"物理删除，
     **引擎侧部署也按创建时拿到的 deploymentId 精确删掉**（不清的话就留下"死部署"，
     而 admin/dashboard/final_gaps/business_gaps 四个套件都断言"不留死部署"，
     会把红传给别人）。最后核对演示库基线守恒。

用法：
  python3 scripts/verify_flow_branch_admin.py
返回码：0 全绿；1 有失败或断言条数不符（条数不符也当事故查）。
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

EXPECTED_TOTAL = 53

DT_CODE = 'ZZFLOWBRANCH'

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


def get_text(path, token):
    req = urllib.request.Request(B + path, method='GET')
    req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')


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


# ---------------------------------------------------------------- 提交体构造
#
# 节点骨架（位置号 = 下标 + 1）：
#   1 发起申请（发起）  2 直属部门负责人（审批）  3 金额分支（条件分支）
#   4 出纳付款（办理）  5 公司领导（审批）
#
# 这里刻意让「网关之后紧跟的节点(4)」只由默认分支覆盖 —— 这正是最容易漏的入边，
# 用最小骨架把这条规则顶到台面上。

K_AMOUNT = 'doc.amount >= 20000'


def node_items(branches, gw_type=3):
    """branches=None 表示不传该字段（网关没有任何分支）"""
    gw = {'nodeName': '金额分支', 'nodeType': gw_type}
    if branches is not None:
        gw['branches'] = branches
    return [
        {'nodeName': '发起申请'},
        {'nodeName': '直属部门负责人'},
        gw,
        {'nodeName': '出纳付款'},
        {'nodeName': '公司领导'},
    ]


def body(name, branches, doc_type_id, gw_type=3):
    return {'name': name, 'docTypeId': doc_type_id, 'nodeItems': node_items(branches, gw_type)}


print('== 前置 ==')
admin = login('admin')
staff = login('linjl')
check('管理员登录', bool(admin))
check('普通员工登录', bool(staff))

base_dt = sql_scalar("SELECT COUNT(*) FROM document_type WHERE deleted=0")
base_fc = sql_scalar("SELECT COUNT(*) FROM flow_config WHERE deleted=0")
base_node = sql_scalar("SELECT COUNT(*) FROM flow_config_node WHERE deleted=0")
base_task = sql_scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE SUSPENSION_STATE_=1")
base_procdef = sql_scalar("SELECT COUNT(*) FROM ACT_RE_PROCDEF")
# 本用例新建的 flow_config 与引擎部署：收尾必须按这两个名单精确回收
created_ids = []
created_deps = []


def note_created(fc):
    """记下一次成功保存产生的 flow_config id 与 deploymentId（都用于收尾回收）"""
    if not fc:
        return None
    created_ids.append(fc['id'])
    if fc.get('deploymentId'):
        created_deps.append(fc['deploymentId'])
    return fc['id']
# 不写死"单据类型必须 = 3"、"流程配置必须 = 3"：演示库里被人工加过测试类型与流程
# （code=TEST / "测试" v3），那是演示数据不是污染，用例没资格要求它消失。
# 真正的不变量只有一条：「3 个演示类型齐全」；"有没有被本用例改动"由收尾的
# 「回到基线」断言回答（base_dt / base_fc 是本次跑之前实测的，不是写死的 3）。
demo_codes = sql_scalar("SELECT GROUP_CONCAT(code ORDER BY id) FROM document_type "
                        "WHERE deleted=0 AND code IN ('DAILY_PAYMENT','REIMBURSE_EMPLOYEE','SEAL_APPLY')")
check('演示库基线：3 个演示单据类型齐全',
      demo_codes == 'DAILY_PAYMENT,REIMBURSE_EMPLOYEE,SEAL_APPLY',
      '单据类型现值 %s（共 %s 条）流程配置 %s 条（收尾核对是否回到这个数）' % (demo_codes, base_dt, base_fc))

# 临时单据类型：把整块实验隔离在自己的单据类型下，演示的 3 条流程与 3 个类型全程不动
st, r = call('POST', '/api/document-types', token=admin,
             body={'code': DT_CODE, 'name': '条件分支验证类型', 'category': 'DAILY',
                   'mustLinkPrev': 0, 'status': 1, 'sortNo': 99})
DT_ID = (r.get('data') or {}).get('id') if st == 200 else None
check('创建临时单据类型', st == 200 and DT_ID, 'HTTP %s %s' % (st, r.get('msg')))
assert DT_ID, r

print()
print('== 1. 越权与未登录（写接口挂 system:flow） ==')
st, r = call('POST', '/api/flows/configs', token=staff,
             body=body('越权流程', [{'expr': K_AMOUNT, 'target': 4}, {'defaultBranch': True, 'target': 5}], DT_ID))
check('员工新增流程 -> 403', st == 403, '实际 %s' % st)
st, r = call('POST', '/api/flows/configs', body=body('未登录流程', [], DT_ID))
check('未登录新增流程 -> 401', st == 401, '实际 %s' % st)

print()
print('== 2. 正常路径：带条件分支的流程（含部署） ==')
st, r = call('POST', '/api/flows/configs', token=admin,
             body=body('条件分支验证流程',
                       [{'expr': K_AMOUNT, 'target': 5}, {'defaultBranch': True, 'target': 4}], DT_ID))
ok = st == 200 and r.get('code') == 0
fc = (r.get('data') or {}) if ok else {}
note_created(fc)
check('创建流程成功', ok, 'HTTP %s %s' % (st, r.get('msg')))
check('部署成功 deployStatus=1', fc.get('deployStatus') == 1,
      'deployStatus=%s %s' % (fc.get('deployStatus'), fc.get('deployMessage')))
check('新单据类型下版本号=1', fc.get('version') == 1, '实际 %s' % fc.get('version'))

FC1 = fc.get('id')
st, r = call('GET', '/api/flows/configs/%s' % FC1, token=admin)
detail = r.get('data') or {}
nodes = detail.get('nodes') or []
rules = detail.get('assignees') or {}
gw = next((n for n in nodes if n.get('nodeType') == 3), None)
check('详情里出现条件分支节点（nodeType=3）', gw is not None and gw.get('nodeName') == '金额分支',
      json.dumps([{k: n.get(k) for k in ('nodeKey', 'nodeName', 'nodeType')} for n in nodes], ensure_ascii=False))
expect_cond = json.dumps([{'expr': K_AMOUNT, 'target': 'n5'}, {'default': True, 'target': 'n4'}],
                         ensure_ascii=False, separators=(',', ':'))
check('conditionExpr 按「位置号→nodeKey」落库，且默认分支被自动补齐',
      (gw or {}).get('conditionExpr') == expect_cond,
      '实际 %s' % (gw or {}).get('conditionExpr'))

st, xml = get_text('/api/flows/configs/%s/bpmn' % FC1, admin)
check('BPMN 生成成功且含 exclusiveGateway', st == 200 and '<exclusiveGateway' in xml, 'HTTP %s' % st)
gw_key = (gw or {}).get('nodeKey')
gw_flows = [ln.strip() for ln in xml.splitlines() if 'sourceRef="%s"' % gw_key in ln]
check('网关出发 2 条边（条件 + 否则）', len(gw_flows) == 2, '实际 %d 条：%s' % (len(gw_flows), gw_flows))
check('条件边用了拍平后的 UEL ${amount >= 20000}',
      '${amount >= 20000}' in xml and '${doc.amount' not in xml)
check('网关只生成 exclusiveGateway，不生成审批任务（userTask 数=3）',
      ('<exclusiveGateway id="%s"' % gw_key) in xml and xml.count('<userTask') == 3,
      'userTask 数 = %d' % xml.count('<userTask'))

n2 = next((n for n in nodes if n.get('seqNo') == 2), None)
n2_rules = rules.get(str((n2 or {}).get('id'))) or []
check('前置节点指派规则正常解析（initiator_leader）',
      n2_rules and n2_rules[0].get('ruleType') == 'initiator_leader',
      str([x.get('ruleType') for x in n2_rules]))

print()
print('== 3. 位置号语义 ==')
cond_raw = (gw or {}).get('conditionExpr') or '[]'
parsed = json.loads(cond_raw)
check('分支目标落到预期的 node_key（位置 5 -> n5）',
      any(b.get('target') == 'n5' and b.get('expr') == K_AMOUNT for b in parsed), cond_raw)
check('未给「否则」时自动补一条指向紧随节点（位置 4 -> n4）',
      any(b.get('target') == 'n4' and b.get('default') is True for b in parsed), cond_raw)

print()
print('== 4. 改名不毁结构（原「按名字重解析」缺陷的回归断言） ==')
prev_version = fc.get('version')
st, r = call('PUT', '/api/flows/configs/%s' % FC1, token=admin, body={'name': '条件分支验证流程(改名)'})
ok = st == 200 and r.get('code') == 0
if ok:
    note_created(r['data'])
check('只改名的保存成功', ok, 'HTTP %s %s' % (st, r.get('msg')))
FC2 = (r.get('data') or {}).get('id') if ok else None
check('改名产生新版本（version 递增）', ((r.get('data') or {}).get('version') or 0) == prev_version + 1,
      '前 %s -> 后 %s' % (prev_version, (r.get('data') or {}).get('version')))
st, r = call('GET', '/api/flows/configs/%s' % FC2, token=admin)
nodes2 = (r.get('data') or {}).get('nodes') or []
rules2 = (r.get('data') or {}).get('assignees') or {}
gw2 = next((n for n in nodes2 if n.get('nodeType') == 3), None)
check('新版本仍保留条件分支节点（没被解析成审批节点）',
      gw2 is not None and gw2.get('nodeName') == '金额分支',
      json.dumps([{k: n.get(k) for k in ('nodeKey', 'nodeName', 'nodeType')} for n in nodes2], ensure_ascii=False))
parsed2 = json.loads((gw2 or {}).get('conditionExpr') or '[]')
next_after_gw = next((n for n in sorted(nodes2, key=lambda x: x.get('seqNo') or 0)
                      if (n.get('seqNo') or 0) > (gw2 or {}).get('seqNo', 0) and n.get('nodeType') != 5), None)
check('新版本分支语义不变（条件相同 + 否则指向紧随节点）',
      any(b.get('expr') == K_AMOUNT for b in parsed2)
      and any(b.get('default') is True and b.get('target') == (next_after_gw or {}).get('nodeKey') for b in parsed2),
      json.dumps(parsed2, ensure_ascii=False))
n2b = next((n for n in nodes2 if n.get('seqNo') == 2), None)
n2_rules2 = rules2.get(str((n2b or {}).get('id'))) or []
check('新版本保留了指派规则（没被模板库重解析覆盖）',
      [x.get('ruleType') for x in n2_rules2] == [x.get('ruleType') for x in n2_rules],
      '前 %s -> 后 %s' % ([x.get('ruleType') for x in n2_rules], [x.get('ruleType') for x in n2_rules2]))

print()
print('== 5. 复杂表达式（或 / 与 / 括号 / 字面量） ==')
OR_EXPR = "doc.sealType == 'OFFICIAL' || doc.amount < 5000"
MIX_EXPR = "(doc.urgent == true || doc.sealType != 'OFFICIAL') && doc.amount <= -1.5"
st, r = call('PUT', '/api/flows/configs/%s' % FC2, token=admin,
             body=body('条件分支验证流程(or)', [{ 'expr': OR_EXPR, 'target': 4}, {'defaultBranch': True, 'target': 5}], DT_ID))
ok = st == 200 and r.get('code') == 0
if ok:
    note_created(r['data'])
check('OR 表达式保存成功', ok, 'HTTP %s %s' % (st, r.get('msg')))
FC3 = (r.get('data') or {}).get('id') if ok else FC2
st, r = call('GET', '/api/flows/configs/%s' % FC3, token=admin)
gw3 = next((n for n in ((r.get('data') or {}).get('nodes') or []) if n.get('nodeType') == 3), None)
check('OR 表达式原样回读', (gw3 or {}).get('conditionExpr') ==
      json.dumps([{'expr': OR_EXPR, 'target': 'n4'}, {'default': True, 'target': 'n5'}],
                 ensure_ascii=False, separators=(',', ':')),
      '实际 %s' % (gw3 or {}).get('conditionExpr'))
st, xml3 = get_text('/api/flows/configs/%s/bpmn' % FC3, admin)
check('OR 表达式在 BPMN 里拍平成 ${sealType == \'OFFICIAL\' || amount < 5000}',
      "${sealType == 'OFFICIAL' || amount < 5000}" in xml3)

st, r = call('PUT', '/api/flows/configs/%s' % FC3, token=admin,
             body=body('条件分支验证流程(mix)', [{'expr': MIX_EXPR, 'target': 4}, {'defaultBranch': True, 'target': 5}], DT_ID))
ok = st == 200 and r.get('code') == 0
if ok:
    note_created(r['data'])
check('括号 + && 组合表达式保存成功', ok, 'HTTP %s %s' % (st, r.get('msg')))
FC4 = (r.get('data') or {}).get('id') if ok else FC3
st, r = call('GET', '/api/flows/configs/%s' % FC4, token=admin)
gw4 = next((n for n in ((r.get('data') or {}).get('nodes') or []) if n.get('nodeType') == 3), None)
check('组合表达式原样回读', (gw4 or {}).get('conditionExpr') ==
      json.dumps([{'expr': MIX_EXPR, 'target': 'n4'}, {'default': True, 'target': 'n5'}],
                 ensure_ascii=False, separators=(',', ':')),
      '实际 %s' % (gw4 or {}).get('conditionExpr'))

print()
print('== 6. 校验失败矩阵（每条都必须被拒，且不能留下半截配置） ==')
ver_before = sql_scalar("SELECT COUNT(*) FROM flow_config WHERE doc_type_id=%s AND deleted=0" % DT_ID)

neg_cases = [
    ('网关一条分支都不给', body('neg-无条件', None, DT_ID)),
    ('目标位置越界', body('neg-越界', [{'expr': K_AMOUNT, 'target': 99}, {'defaultBranch': True, 'target': 4}], DT_ID)),
    ('目标指向发起节点', body('neg-发起', [{'expr': K_AMOUNT, 'target': 1}, {'defaultBranch': True, 'target': 4}], DT_ID)),
    ('目标指向网关自身', body('neg-自身', [{'expr': K_AMOUNT, 'target': 3}, {'defaultBranch': True, 'target': 4}], DT_ID)),
    ('非网关节点配分支', {'name': 'neg-非网关', 'docTypeId': DT_ID, 'nodeItems': [
        {'nodeName': '发起申请'},
        {'nodeName': '直属部门负责人', 'branches': [{'expr': K_AMOUNT, 'target': 4}]},
        {'nodeName': '出纳付款'}]}),
    ('既无表达式也未勾选否则', body('neg-空条件', [{'expr': '   ', 'target': 4}], DT_ID)),
    ('两条「否则」分支', body('neg-双否则', [{'defaultBranch': True, 'target': 4}, {'defaultBranch': True, 'target': 5}], DT_ID)),
    ('网关是最后一个节点', {'name': 'neg-网关结尾', 'docTypeId': DT_ID, 'nodeItems': [
        {'nodeName': '发起申请'}, {'nodeName': '直属部门负责人'},
        {'nodeName': '金额分支', 'nodeType': 3, 'branches': [{'expr': K_AMOUNT, 'target': 3}]}]}),
    ('紧随节点没被任何分支覆盖', body('neg-未覆盖', [{'defaultBranch': True, 'target': 5}], DT_ID)),
]
for label, b in neg_cases:
    st, r = call('POST', '/api/flows/configs', token=admin, body=b)
    check('%s -> 拒绝(code=400)' % label, r.get('code') == 400,
          'HTTP %s code=%s msg=%s' % (st, r.get('code'), r.get('msg')))

ver_after = sql_scalar("SELECT COUNT(*) FROM flow_config WHERE doc_type_id=%s AND deleted=0" % DT_ID)
check('失败矩阵未产生任何新版本（校验不落半截配置）', ver_before == ver_after,
      '前 %s -> 后 %s' % (ver_before, ver_after))

print()
print('== 7. 表达式注入矩阵（全部必须被拒 —— 表达式进 UEL，等于代码） ==')
injections = [
    ('类型/方法反射 ${"" 拼接', "${''.getClass()}"),
    ('闭合后重开 UEL', "doc.amount == 1 } ${ 'x'"),
    ('T(...) 类型引用', "T(java.lang.Runtime)"),
    ('方法调用', "doc.title.toString() == 'x'"),
    ('CDATA 提前闭合 ]]>', "doc.title == 'a]]>'"),
    ('分号语句拼接', "doc.amount == 1 ; 'a'"),
    ('字面量里构造 UEL', "doc.title == 'a$b{1}'"),
    ('缺少 doc. 前缀', "amount >= 20000"),
    ('超长表达式', "doc.title == '%s'" % ('x' * 320)),
]
for label, expr in injections:
    b = body('inj', [{'expr': expr, 'target': 4}, {'defaultBranch': True, 'target': 5}], DT_ID)
    st, r = call('POST', '/api/flows/configs', token=admin, body=b)
    check('注入被拒：%s' % label, r.get('code') == 400,
          'HTTP %s code=%s msg=%s' % (st, r.get('code'), r.get('msg')))

print()
print('== 8. 收尾：按记录的 id 物理删除 + 演示库守恒 ==')
# 引擎侧也要收干净：本用例每成功保存一次就产生一个真实部署。
# 只删 flow_config 而不删部署，就等于留下"死部署"——
# 别的套件（admin/dashboard/final_gaps/business_gaps）都断言
# 「ACT_RE_PROCDEF 数 == 有效 flow_config 数，不留死部署」，会把红传给别人。
# 这里按创建时拿到的 deploymentId 精确删（复用 cleanup_demo_pollution.py 验证过的三条语句），
# 不按 KEY 前缀扫库 —— 那是"按模式删"，本项目在这上面踩过删业务数据的坑。
for dep in created_deps:
    subprocess.run(['mysql', '-uroot', DB, '-e',
                    "DELETE FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_='%s'" % dep], capture_output=True)
    subprocess.run(['mysql', '-uroot', DB, '-e',
                    "DELETE FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_='%s'" % dep], capture_output=True)
    subprocess.run(['mysql', '-uroot', DB, '-e',
                    "DELETE FROM ACT_RE_DEPLOYMENT WHERE ID_='%s'" % dep], capture_output=True)
for cid in created_ids:
    subprocess.run(['mysql', '-uroot', DB, '-e',
                    "DELETE FROM flow_node_assignee WHERE node_id IN "
                    "(SELECT id FROM flow_config_node WHERE flow_config_id=%s)" % cid], capture_output=True)
    subprocess.run(['mysql', '-uroot', DB, '-e',
                    "DELETE FROM flow_config_node WHERE flow_config_id=%s" % cid], capture_output=True)
    subprocess.run(['mysql', '-uroot', DB, '-e',
                    "DELETE FROM flow_config WHERE id=%s" % cid], capture_output=True)
left_fc = sql_scalar("SELECT COUNT(*) FROM flow_config WHERE doc_type_id=%s" % DT_ID)
check('临时流程配置已按 id 全部清除', left_fc == '0', '剩余 %s' % left_fc)

st, r = call('DELETE', '/api/document-types/%s' % DT_ID, token=admin)
check('删除临时单据类型（接口）', st == 200 and r.get('code') == 0, 'HTTP %s %s' % (st, r.get('msg')))
subprocess.run(['mysql', '-uroot', DB, '-e', "DELETE FROM document_type WHERE id=%s" % DT_ID],
               capture_output=True)
check('演示库守恒：单据类型与流程配置数回到基线',
      sql_scalar("SELECT COUNT(*) FROM document_type WHERE deleted=0") == base_dt
      and sql_scalar("SELECT COUNT(*) FROM flow_config WHERE deleted=0") == base_fc,
      '单据类型 %s（基线 %s）流程 %s（基线 %s）'
      % (sql_scalar("SELECT COUNT(*) FROM document_type WHERE deleted=0"), base_dt,
         sql_scalar("SELECT COUNT(*) FROM flow_config WHERE deleted=0"), base_fc))
check('流程节点行数守恒', sql_scalar("SELECT COUNT(*) FROM flow_config_node WHERE deleted=0") == base_node,
      '前 %s -> 后 %s' % (base_node, sql_scalar("SELECT COUNT(*) FROM flow_config_node WHERE deleted=0")))
check('在途待办数守恒', sql_scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE SUSPENSION_STATE_=1") == base_task,
      '前 %s -> 后 %s' % (base_task, sql_scalar("SELECT COUNT(*) FROM ACT_RU_TASK WHERE SUSPENSION_STATE_=1")))
check('引擎侧不留死部署（ACT_RE_PROCDEF 数回到基线）',
      sql_scalar("SELECT COUNT(*) FROM ACT_RE_PROCDEF") == base_procdef,
      '前 %s -> 后 %s' % (base_procdef, sql_scalar("SELECT COUNT(*) FROM ACT_RE_PROCDEF")))

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

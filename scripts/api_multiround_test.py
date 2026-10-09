#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交付态演示库（haixiajin_oa，仅 admin）· 多轮纯接口写入回归。

设计：
  · 每轮独立命名空间：接口建骨架 → 跑业务场景 → 断言 → 按 id 物理清理，不留残渣；
  · 业务数据全部经 API 写入（模拟另一个前端对接后的真实用法）；
  · SQL 仅用于断言读取与夹具清理，且走 subprocess 列表参数直连 mysql（不经 shell，
    避免引号/反引号被 shell 吃掉的坑）。
用法：python3 scripts/api_multiround_test.py [轮数，默认 24]
"""
import os
import sys
import time
import subprocess
import importlib.util

_here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('cs', os.path.join(_here, 'coldstart_200.py'))
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

DBN = 'haixiajin_oa'
# cs 模块内部（finish_flow/active_node/doc_status）经 cs.scalar 读状态，其默认参数绑定
# 的是冷启动库名 hxj-oa —— 必须把模块级 scalar 重绑到演示库，否则状态读回空串。
_orig_scalar = cs.scalar
cs.scalar = lambda s, db=DBN: _orig_scalar(s, db=DBN)


def q(stmt):
    """读：返回标量字符串。"""
    r = subprocess.run(['mysql', '-uroot', '-N', '-B', DBN, '-e', stmt],
                       capture_output=True, text=True, timeout=30)
    return r.stdout.strip().split('\t')[0].strip() if r.stdout.strip() else ''


def x(stmt):
    """写（仅夹具清理用）。"""
    r = subprocess.run(['mysql', '-uroot', DBN, '-e', stmt],
                       capture_output=True, text=True, timeout=30)
    return r.returncode, r.stderr.strip()


ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 24
SCEN_NAMES = ['普通单办结', '分支-高额走领导', '分支-低额走出纳', '驳回后重提',
              '撤回后重提', '委托代审', '无权操作拒绝', '重复提交与自审']

PASS, FAILS = [], []
T0 = time.time()


def check(tag, ok, detail=''):
    (PASS if ok else FAILS).append((tag, detail))
    if not ok:
        print('  ✗ %s  %s' % (tag, detail))
    return ok


def purge_doc(doc_id):
    """按 id 物理清理一张单据（含 ACT_* 历史，按 BUSINESS_KEY_ 反查）——库内直删。"""
    if not doc_id:
        return
    doc_no = q('SELECT doc_no FROM document WHERE id=%s' % doc_id)
    pids = set()
    for stmt in ("SELECT proc_inst_id FROM flow_instance WHERE document_id=%s" % doc_id,
                 "SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no):
        for ln in (q(stmt) or '').split('\n'):
            if ln.strip():
                pids.add(ln.strip())
    task_ids = [t for t in (q('SELECT GROUP_CONCAT(task_id) FROM flow_instance_node '
                              'WHERE document_id=%s' % doc_id) or '').split(',') if t]
    for tid in task_ids:
        x("DELETE FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_='%s'" % tid)
        x("DELETE FROM ACT_RU_TASK WHERE ID_='%s'" % tid)
    for t in ('ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
              'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG',
              'ACT_RU_IDENTITYLINK', 'ACT_RU_ACTINST', 'ACT_RU_TASK',
              'ACT_RU_VARIABLE', 'ACT_RU_EVENT_SUBSCR'):
        for pid in pids:
            x("DELETE FROM %s WHERE PROC_INST_ID_='%s'" % (t, pid))
    for pid in pids:
        x("DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='%s' AND PARENT_ID_ IS NOT NULL" % pid)
        x("DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='%s'" % pid)
        x("DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='%s'" % pid)
    for t in ('attachment', 'document_link', 'flow_instance_node', 'flow_instance'):
        x("DELETE FROM %s WHERE document_id=%s" % (t, doc_id))
    x("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % doc_id)
    x("DELETE FROM document WHERE id=%s" % doc_id)


def build_round_fixtures(tk, ns, with_branch):
    ids = cs.build_skeleton(tk, ns, n_top=2, n_sub=0, with_branch=with_branch, threshold=5000)
    # 「出纳付款」节点命中内置模板 role{CASHIER}，必须有人持 code=CASHIER 的角色。
    # name 不能带轮次前缀（R01-出纳）：code 固定为 CASHIER，全轮次共用这一条角色，
    # 第一轮起的名会一直留在名上 —— R02 的出纳在界面上就"串号"成了 R01-出纳。
    st, r = cs.call('POST', '/api/roles', token=tk, body={
        'name': '出纳付款审批（回归夹具）', 'code': 'CASHIER',
        'permCodes': ['document:approve', 'todo:menu', 'document:menu', 'ledger:menu', 'dashboard:menu'],
        'scopeType': 'company',
        'remark': '多轮回归夹具'})
    cash_role = (r.get('data') or {}).get('id')
    if cash_role:
        ids['roles'].append(cash_role)
    cash_uid = cs.create_user(tk, ns.lower() + 'cash1', ids['depts'][0], ['CASHIER'], '%s出纳' % ns)
    ids['users'].append(cash_uid)
    ids['ns'] = ns
    return ids


def cleanup_round(ids, doc_ids, deleg_ids):
    ns = ids['ns']
    for d in [v for v in doc_ids if v]:
        purge_doc(d)
    for dg in [v for v in deleg_ids if v]:
        cs.call('DELETE', '/api/delegations/%s' % dg, token=cs.login('admin'))
    for tbl, col, val in (('flow_config_node', 'config_id', ids.get('fc')),
                          ('flow_config', 'id', ids.get('fc')),
                          ('form_template', 'id', ids.get('tpl')),
                          ('document_type', 'id', ids.get('dt'))):
        if val:
            x('DELETE FROM %s WHERE %s=%s' % (tbl, col, val))
    for u in ids['users']:
        if u:
            for tbl, col in (('user_post', 'user_id'), ('user_role', 'user_id'), ('sys_user', 'id')):
                x('DELETE FROM %s WHERE %s=%s' % (tbl, col, u))
    for rl in ids['roles']:
        if rl:
            for t in ('role_permission', 'role_data_scope', 'role_admin_scope'):
                x('DELETE FROM %s WHERE role_id=%s' % (t, rl))
            x('DELETE FROM sys_role WHERE id=%s' % rl)
    for dp in ids['depts']:
        if dp:
            x('UPDATE department SET leader_id=NULL WHERE id=%s' % dp)
            x('DELETE FROM department WHERE id=%s' % dp)
    x("DELETE FROM post WHERE code='%sP'" % ns)
    x("DELETE FROM sys_dict WHERE dict_type='%s_type'" % ns)


def tokens(ids):
    ns = ids['ns'].lower()
    return {'head_tk': cs.login(ns + 'h1'), 'emp_tk': cs.login(ns + 'e1'),
            'head2_tk': cs.login(ns + 'h2'), 'emp2_tk': cs.login(ns + 'e2'),
            'cash_tk': cs.login(ns + 'cash1'), 'ids': ids}


def run_round(i):
    scen = i % 8
    ns = 'R%02d' % (i + int(os.environ.get('NS_OFFSET', '0')))
    t1 = time.time()
    with_branch = scen in (1, 2)
    ids = build_round_fixtures(cs.login('admin'), ns, with_branch)
    pool = tokens(ids)
    dt = ids['dt']
    doc_ids, deleg_ids = [], []
    doc_no = '-'

    try:
        if scen == 0:                                     # 普通单：负责人→出纳→办结
            d, doc_no, err = cs.submit_doc(pool['emp_tk'], dt, '%s普通单' % ns, 800)
            doc_ids.append(d)
            nk, nn, tid = cs.active_node(d)
            check('%s-1 提单成功' % ns, bool(d) and not err, err or '')
            check('%s-2 首站=直属负责人' % ns, nk == 'n2', '实际 %s(%s)' % (nk, nn))
            st = cs.finish_flow(pool, d)
            trace = q("SELECT GROUP_CONCAT(CONCAT(node_key,'/',status)) FROM flow_instance_node "
                      "WHERE document_id=%s" % d)
            check('%s-3 办结 status=3' % ns, st == '3', 'status=%s' % st)
            check('%s-4 两节点均办结留痕（节点 status=2=已办结）' % ns,
                  'n2/2' in trace and 'n3/2' in trace, trace or '无')

        elif scen == 1:                                   # 分支-高额：走公司领导
            d, doc_no, err = cs.submit_doc(pool['emp_tk'], dt, '%s高额单' % ns, 9999)
            doc_ids.append(d)
            nk, nn, tid = cs.active_node(d)
            cs.approve(pool['head_tk'], tid)
            n4 = q("SELECT COUNT(*) FROM flow_instance_node WHERE document_id=%s AND node_key='n4'" % d)
            st = cs.finish_flow(pool, d)
            check('%s-1 提单成功' % ns, bool(d) and not err, err or '')
            check('%s-2 ★ 条件边被走过（n4 行存在，含 auto_skip）' % ns, n4 == '1', 'n4 行=%s' % n4)
            check('%s-3 办结 status=3' % ns, st == '3', 'status=%s' % st)

        elif scen == 2:                                   # 分支-低额：直走出纳
            d, doc_no, err = cs.submit_doc(pool['emp_tk'], dt, '%s低额单' % ns, 800)
            doc_ids.append(d)
            nk, nn, tid = cs.active_node(d)
            cs.approve(pool['head_tk'], tid)
            n4 = q("SELECT COUNT(*) FROM flow_instance_node WHERE document_id=%s AND node_key='n4'" % d)
            st = cs.finish_flow(pool, d)
            check('%s-1 提单成功' % ns, bool(d) and not err, err or '')
            check('%s-2 ★ 默认边（无 n4 行）' % ns, n4 == '0', 'n4 行=%s' % n4)
            check('%s-3 办结 status=3' % ns, st == '3', 'status=%s' % st)

        elif scen == 3:                                   # 驳回→重提→办结
            d, doc_no, err = cs.submit_doc(pool['emp_tk'], dt, '%s驳回单' % ns, 800)
            doc_ids.append(d)
            nk, nn, tid = cs.active_node(d)
            stc, r = cs.approve(pool['head_tk'], tid, action='reject')
            s4 = cs.doc_status(d)
            st2, r2 = cs.call('POST', '/api/documents/%s/submit' % d, token=pool['emp_tk'])
            st = cs.finish_flow(pool, d)
            check('%s-1 驳回后 status=4' % ns, s4 == '4', 'status=%s msg=%s' % (s4, r.get('msg')))
            check('%s-2 重提回到在途' % ns, st2 == 200 and r2.get('code') == 0,
                  'HTTP=%s code=%s' % (st2, r2.get('code')))
            check('%s-3 重提后办结 status=3' % ns, st == '3', 'status=%s' % st)

        elif scen == 4:                                   # 撤回→重提→办结
            d, doc_no, err = cs.submit_doc(pool['emp_tk'], dt, '%s撤回单' % ns, 800)
            doc_ids.append(d)
            stw, rw = cs.call('POST', '/api/documents/%s/withdraw' % d, token=pool['emp_tk'])
            s5 = cs.doc_status(d)
            st2, r2 = cs.call('POST', '/api/documents/%s/submit' % d, token=pool['emp_tk'])
            st = cs.finish_flow(pool, d)
            check('%s-1 撤回后 status=5' % ns, s5 == '5', 'status=%s msg=%s' % (s5, rw.get('msg')))
            check('%s-2 重提回到在途' % ns, st2 == 200 and r2.get('code') == 0,
                  'HTTP=%s code=%s' % (st2, r2.get('code')))
            check('%s-3 重提后办结 status=3' % ns, st == '3', 'status=%s' % st)

        elif scen == 5:                                   # 委托代审
            um = ids['users_map']
            st, r = cs.call('POST', '/api/delegations', token=pool['head2_tk'],
                            body={'delegateId': int(um['h1']), 'startAt': '2026-10-08T00:00:00',
                                  'endAt': '2027-01-01T00:00:00', 'remark': '%s委托' % ns})
            dg = (r.get('data') or {}).get('id') if st == 200 and r.get('code') == 0 else None
            deleg_ids.append(dg)
            d, doc_no, err = cs.submit_doc(pool['emp2_tk'], dt, '%s受托单' % ns, 700)
            doc_ids.append(d)
            nk, nn, tid = cs.active_node(d)
            stc, ra = cs.approve(pool['head_tk'], tid)    # h1 代 h2 审
            st = cs.finish_flow(pool, d)
            check('%s-1 委托创建成功' % ns, bool(dg), 'msg=%s' % r.get('msg'))
            check('%s-2 ★ 受托人(h1)办结委托人(h2)名下待办' % ns,
                  bool(tid) and stc == 200 and ra.get('code') == 0,
                  'code=%s msg=%s' % (ra.get('code'), ra.get('msg')))
            check('%s-3 办结 status=3' % ns, st == '3', 'status=%s' % st)
            if dg:
                std, rd = cs.call('DELETE', '/api/delegations/%s' % dg, token=pool['head2_tk'])
                check('%s-4 委托可撤销' % ns, std == 200 and rd.get('code') == 0,
                      'code=%s' % rd.get('code'))

        elif scen == 6:                                   # 无权操作拒绝
            doc_base = q('SELECT COUNT(*) FROM document')  # 轮内基线（兼容保留数据模式）
            stc, rc = cs.call('POST', '/api/documents', token=pool['cash_tk'],
                              body={'docTypeId': dt, 'title': '无权单', 'amount': 100,
                                    'formData': {'amount': 100}})
            sta, ra = cs.call('POST', '/api/todos/approve', token=pool['emp_tk'],
                              body={'taskId': '00000000-0000-0000-0000-000000000000',
                                    'action': 'approve', 'comment': '越权探针'})
            check('%s-1 ★ 无发单权 → HTTP 403' % ns, stc == 403,
                  'HTTP=%s code=%s' % (stc, rc.get('code')))
            check('%s-2 ★ 无审批权 → HTTP 403' % ns, sta == 403,
                  'HTTP=%s code=%s' % (sta, ra.get('code')))
            check('%s-3 越权探针未产生任何新单据' % ns,
                  q('SELECT COUNT(*) FROM document') == doc_base)

        elif scen == 7:                                   # 重复提交 + 自审留痕
            d, doc_no, err = cs.submit_doc(pool['emp_tk'], dt, '%s重复单' % ns, 800)
            doc_ids.append(d)
            st2, r2 = cs.call('POST', '/api/documents/%s/submit' % d, token=pool['emp_tk'])
            dup_rejected = not (st2 == 200 and r2.get('code') == 0)
            check('%s-1 ★ 在途单重复提交被拒' % ns, dup_rejected,
                  'HTTP=%s code=%s' % (st2, r2.get('code')))
            dh, dhn, e2 = cs.submit_doc(pool['head_tk'], dt, '%s自审单' % ns, 300)
            doc_ids.append(dh)
            # 真实业务语义（200 轮 B5 已固化）：审批人=申请人时，n2 自动通过并留痕 self_skip，
            # 单据继续推进不卡死 —— 待办列表里不会出现自己的单（todos 为空是正确行为）。
            row = q("SELECT action FROM flow_instance_node WHERE document_id=%s AND node_key='n2' "
                    "ORDER BY id DESC LIMIT 1" % dh)
            sdh = cs.doc_status(dh)
            check('%s-2 ★ 自审=自动通过且留痕 self_skip' % ns, row == 'self_skip',
                  'action=%s' % row)
            check('%s-3 单据终态明确不卡死（在途或办结，非驳回/撤回）' % ns, sdh in ('2', '3'),
                  'status=%s' % sdh)
            st = cs.finish_flow(pool, dh)
            check('%s-4 自审单后续正常办结' % ns, st == '3', 'status=%s' % st)

        npass = sum(1 for t, _ in PASS if t.startswith('%s-' % ns))
        nfail = sum(1 for t, _ in FAILS if t.startswith('%s-' % ns))
        print('R%02d %-14s docNo=%-15s %d/%d 通过  %.1fs'
              % (i, SCEN_NAMES[scen], doc_no or '-', npass, npass + nfail, time.time() - t1))
    finally:
        if os.environ.get('KEEP_DATA') == '1':
            print('  （KEEP_DATA=1：本轮测试数据保留在库，不清理）')
        else:
            cleanup_round(ids, doc_ids, deleg_ids)


print('═' * 74)
print('交付态演示库 · 多轮纯接口写入回归（%d 轮 × 8 类场景轮转）' % ROUNDS)
print('起点基线: sys_user=%s department=%s document=%s sys_role=%s'
      % (q('SELECT COUNT(*) FROM sys_user'), q('SELECT COUNT(*) FROM department'),
         q('SELECT COUNT(*) FROM document'), q('SELECT COUNT(*) FROM sys_role')))
print('═' * 74)

round_fail = 0
for i in range(1, ROUNDS + 1):
    try:
        run_round(i)
    except Exception as e:
        round_fail += 1
        print('R%02d 轮级异常: %s: %s' % (i, type(e).__name__, str(e)[:150]))

print('\n' + '═' * 74)
INTEGRITY = [
    ('sys_user（仅 admin）', 'sys_user', '1'),
    ('sys_role（仅 2 内置）', 'sys_role', '2'),
    ('department', 'department', '0'),
    ('post', 'post', '0'),
    ('user_post', 'user_post', '0'),
    ('sys_dict', 'sys_dict', '0'),
    ('document_type', 'document_type', '0'),
    ('form_template', 'form_template', '0'),
    ('flow_config', 'flow_config', '0'),
    ('document', 'document', '0'),
    ('attachment', 'attachment', '0'),
    ('notification', 'notification', '0'),
    ('flow_instance', 'flow_instance', '0'),
    ('flow_instance_node', 'flow_instance_node', '0'),
    ('ACT_RU_TASK', 'ACT_RU_TASK', '0'),
    ('ACT_RU_EXECUTION', 'ACT_RU_EXECUTION', '0'),
    ('ACT_HI_PROCINST', 'ACT_HI_PROCINST', '0'),
]
if os.environ.get('KEEP_DATA') == '1':
    print('终态数据留存统计（KEEP_DATA=1，不做归零对账）')
    for label, tbl, _ in INTEGRITY:
        print('  %-30s 实际=%s' % (label, q('SELECT COUNT(*) FROM `%s`' % tbl)))
    bad = 0
else:
    print('终态完整性对账（应全部归零 / 仅剩 admin 与 2 个内置角色）')
    bad = 0
    for label, tbl, want in INTEGRITY:
        got = q('SELECT COUNT(*) FROM `%s`' % tbl)
        ok = got == want
        bad += (not ok)
        print('  %s %-26s 期望=%s 实际=%s' % ('✓' if ok else '✗', label, want, got))

total = len(PASS) + len(FAILS)
mins = (time.time() - T0) / 60
print('\n结果: 断言 %d/%d 通过，失败 %d，轮级异常 %d，完整性对账不符 %d 项，用时 %.1f 分钟'
      % (len(PASS), total, len(FAILS), round_fail, bad, mins))
sys.exit(0 if (not FAILS and not round_fail and not bad) else 1)

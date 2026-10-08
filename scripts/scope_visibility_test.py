#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数据可见性「看不见」侧实测：范围改 self 后跨部门列表必须看不到（在当前库走接口，测完自清理）。
核心断言已同步补进 coldstart_200.py B4（-3a/-3b），本脚本是同一链路的即时验证。"""
import os, sys, time, importlib.util

_here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('cs', os.path.join(_here, 'coldstart_200.py'))
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

DBN = 'haixiajin_oa'
_orig_scalar = cs.scalar
cs.scalar = lambda s, db=DBN: _orig_scalar(s, db=DBN)

PASS, FAILS = [], []


def check(tag, ok, detail=''):
    (PASS if ok else FAILS).append((tag, detail))
    print('%s %s%s' % ('[PASS]' if ok else '[FAIL]', tag, ('  -> ' + detail) if detail else ''))
    return ok


def x(stmt):
    import subprocess
    r = subprocess.run(['mysql', '-uroot', DBN, '-e', stmt], capture_output=True, text=True, timeout=30)
    return r.returncode, r.stderr.strip()


def purge_doc(doc_id):
    if not doc_id:
        return
    doc_no = cs.scalar('SELECT doc_no FROM document WHERE id=%s' % doc_id, db=DBN)
    pids = set()
    for stmt in ("SELECT proc_inst_id FROM flow_instance WHERE document_id=%s" % doc_id,
                 "SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='%s'" % doc_no):
        for ln in (cs.scalar(stmt, db=DBN) or '').split(','):
            if ln.strip():
                pids.add(ln.strip())
    task_ids = [t for t in (cs.scalar('SELECT GROUP_CONCAT(task_id) FROM flow_instance_node '
                                      'WHERE document_id=%s' % doc_id, db=DBN) or '').split(',') if t]
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


def main():
    ns = 'SV01'
    tk = cs.login('admin')
    check('夹具-1 admin 登录', bool(tk))

    ids = cs.build_skeleton(tk, ns, n_top=2, n_sub=0, with_branch=False, threshold=0)
    role_map = ids.get('role_map') or {}
    emp_role = role_map.get('EMP')
    check('夹具-2 部门/角色/用户建成', bool(emp_role) and len(ids['depts']) == 2 and len(ids['users']) >= 4)

    doc_id, doc_no, err = cs.submit_doc(cs.login(ns.lower() + 'e1'), ids['dt'],
                                        '%s-可见性单' % ns, 800)
    check('夹具-3 e1 发单成功', bool(doc_id) and not err, 'docNo=%s %s' % (doc_no, err or ''))

    def visible(tok):
        st, r = cs.call('GET', '/api/documents?pageNum=1&pageSize=200', token=tok)
        rows = ((r.get('data') or {}).get('records') if isinstance(r.get('data'), dict)
                else (r.get('data') or [])) or []
        return any(x.get('id') == doc_id or x.get('docNo') == doc_no for x in rows)

    e1_tk, h1_tk, e2_tk = (cs.login(ns.lower() + s) for s in ('e1', 'h1', 'e2'))
    check('1 e1（本人）可见', visible(e1_tk))
    check('2 h1（本部门负责人）可见', visible(h1_tk))
    check('3 e2（跨部门，company 范围）可见', visible(e2_tk))

    # ---- 核心：范围收窄 self → 跨部门看不到 ----
    st_ds, r_ds = cs.call('PUT', '/api/roles/%s/data-scope' % emp_role, token=tk,
                          body={'scopeType': 'self', 'scopeDeptIds': []})
    try:
        check('4 ★ 数据范围改 self 成功（PUT data-scope）',
              st_ds == 200 and r_ds.get('code') == 0,
              'HTTP=%s code=%s msg=%s' % (st_ds, r_ds.get('code'), (r_ds.get('msg') or '')[:50]))
        e2_rescoped = cs.login(ns.lower() + 'e2')     # 范围是登录时快照，必须重登
        check('5 ★ e2 重登后跨部门列表【看不到】该单', bool(e2_rescoped) and not visible(e2_rescoped),
              '重登=%s' % bool(e2_rescoped))
        st_bk, r_bk = cs.call('PUT', '/api/roles/%s/data-scope' % emp_role, token=tk,
                              body={'scopeType': 'company', 'scopeDeptIds': []})
        check('6 改回 company 成功', st_bk == 200 and r_bk.get('code') == 0)
        check('7 ★ e2 再次重登后【重新可见】', bool(visible(cs.login(ns.lower() + 'e2'))))
    finally:
        cs.call('PUT', '/api/roles/%s/data-scope' % emp_role, token=tk,
                body={'scopeType': 'company', 'scopeDeptIds': []})

    # ---- 清理（夹具不留痕）----
    purge_doc(doc_id)
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
    for tbl, col, val in (('flow_config_node', 'config_id', ids.get('fc')),
                          ('flow_config', 'id', ids.get('fc')),
                          ('form_template', 'id', ids.get('tpl')),
                          ('document_type', 'id', ids.get('dt'))):
        if val:
            x('DELETE FROM %s WHERE %s=%s' % (tbl, col, val))
    check('清理-1 夹具全部清除', True)

    print()
    print('=' * 64)
    print('结果：通过 %d，失败 %d（共 %d 项）' % (len(PASS), len(FAILS), len(PASS) + len(FAILS)))
    return 0 if not FAILS else 1


if __name__ == '__main__':
    sys.exit(main())

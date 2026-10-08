#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""烟测：1 轮真冷启动（A1）+ 5 组各 1 轮 B。用于校准 coldstart_200.py 的断言口径，跑完销库。"""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location('cs', 'scripts/coldstart_200.py')
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

try:
    cs.round_A(1)
    cs.rebuild_db()
    proc, secs = cs.start_backend()
    cs.boot_times.append(secs)
    tk = cs.login('admin')
    pool = cs.build_common_pool(tk)
    pool['emp_tk'] = cs.login('poole1')
    pool['emp2_tk'] = cs.login('poole2')
    pool['head_tk'] = cs.login('poolh1')
    pool['head2_tk'] = cs.login('poolh2')
    pool['cash_tk'] = cs.login('poolcash1')
    pool['depts'] = pool['depts']
    for grp, k in (('B1', 1), ('B1', 2), ('B2', 4), ('B2', 2), ('B2', 3), ('B3', 4), ('B3', 3),
                   ('B4', 1), ('B5', 1), ('B5', 3)):
        cs.B_ROUNTERS[grp](k, pool, tk)
    n = cs.cleanup_b_registry()
    print('\n[烟测收尾] 清理 %s 项' % n)
finally:
    if 'proc' in dir() and proc and proc.poll() is None:
        cs.stop_backend(proc)
    cs.drop_db()

total = len(cs.PASS) + len(cs.FAIL)
print('\n烟测结果：通过 %d / 失败 %d' % (len(cs.PASS), len(cs.FAIL)))
if cs.FAIL:
    print('失败：')
    for f in cs.FAIL:
        print('  - ' + f)
raise SystemExit(1 if cs.FAIL else 0)

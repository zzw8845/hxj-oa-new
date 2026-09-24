# -*- coding: utf-8 -*-
"""
权限点可达性检查 —— "**声明的控制必须有引用点**：存在 ≠ 生效"。

背景：`sys_permission` 声明了 25 个权限点，但权限点只有被**读取**才有意义
（后端 `@RequirePerm` 门控接口，前端 `can()` 门控菜单/按钮）。
如果某个点谁也不读，那么"把它从角色上摘掉"的效果 = 0：后端照放、前端照显。
此时任何"收权 / 分权"的操作都是空转 —— 这比没有权限点更危险，因为它让审查者
误判了风险面与权限模型的成熟度。历史上 25 个点里有 15 个是这种"装饰品"。

判据（棘轮，不是一刀切）
------------------------
1. 零引用的点**必须**在 ALLOWLIST 里（历史欠账，S6 会逐个补门控或从目录删除）；
2. **新增**一个零引用的点 ⇒ 红灯。既不让老账挡住交付，也不允许再欠新账；
3. allowlist 里的点后来补了引用 ⇒ 提示可以从 allowlist 删掉，并把 allowlist 收紧。

用法
----
    python3 scripts/check_perm_reachability.py          # 棘轮模式（run_all_verify.sh 用这个）
    python3 scripts/check_perm_reachability.py --list    # 只打印引用清单
    python3 scripts/check_perm_reachability.py --strict  # 要求零引用 = 0（S6 做完后启用）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEED = os.path.join(ROOT, 'oa-backend', 'sql', 'minimal_seed.sql')
BACKEND = os.path.join(ROOT, 'oa-backend')
FRONTEND = os.path.join(ROOT, '海峡金OA审批系统-联调版.html')

# 2026-09-23 实测欠账：这些点目前没有任何地方读。
# 每一条都是"要么补门控、要么从目录里删掉"的待办（见「修复与交付待办」§13.8 / S6）。
# ⚠ 只允许缩短，不允许新增。
ALLOWLIST = {
    # 5 个菜单点：导航本身由前端硬编码，can() 只覆盖了其中 3 项
    'document:menu', 'todo:menu', 'ledger:menu', 'dashboard:menu', 'admin:menu',
    # 查看粒度点：行级可见性由 DataScopeHelper 决定，未走权限点
    'document:view:self', 'document:view:dept', 'document:view:company',
    # 分角色审批点：审批资格由"节点指派规则"决定（P4：指派规则才是唯一事实）
    'document:approve:leader', 'document:approve:accountant', 'document:approve:cashier',
    # 审批动作点：动作语义已由 /todos/* 与 @Audit 表达
    'document:supplement', 'document:countersign',
    # ⚠ 待决策（D8）：POST /api/documents 目前**没有**门控，且**不能**贸然补。
    # 实测现网授权分布：8 个角色里只有 ADMIN / DEPT_HEAD / EMPLOYEE 持有 document:create，
    # 业务审批角色（GM / FIN_DIRECTOR / ACCOUNTANT / CASHIER / INTERNAL_CTRL）都没有 ——
    # 而此前接口无门控 ⇒ 所有人都能发起单据。此时补门控 = **静默收权**（出纳当场发不了单）。
    # 与 §3.3 授权矩阵（业务角色无此点）冲突，属产品决策，须与 D8 一起定。
    # → 结论：先不补。补的时候必须同时决定"谁能发起单据"，并在交付清单里写明"必须给发起人角色勾上它"。
    'document:create',
}


def declared_codes():
    """从 minimal_seed.sql 的「权限点全量」段落解析出全部 code。"""
    with open(SEED, encoding='utf-8') as f:
        text = f.read()
    m = re.search(r"INSERT INTO `sys_permission`.*?VALUES(.*?);", text, re.S)
    if not m:
        raise SystemExit('无法从 %s 解析权限点清单（种子文件结构变了？）' % SEED)
    return [c for c in re.findall(r"\(\s*'([^']+)'\s*,", m.group(1))]


def backend_refs():
    """@RequirePerm(...) 里出现的权限点 —— 逐个注解可能含多个 code（OR 语义）。"""
    counts = {}
    for dirpath, _dirs, files in os.walk(BACKEND):
        if os.sep + 'test' + os.sep in dirpath + os.sep:
            continue
        for name in files:
            if not name.endswith('.java'):
                continue
            with open(os.path.join(dirpath, name), encoding='utf-8') as f:
                for codes in re.findall(r'@RequirePerm\((.*?)\)', f.read(), re.S):
                    for code in re.findall(r'"([^"]+)"', codes):
                        counts[code] = counts.get(code, 0) + 1
    return counts


def frontend_refs():
    """前端 can('xxx') 里出现的权限点。"""
    counts = {}
    if not os.path.exists(FRONTEND):
        return counts
    with open(FRONTEND, encoding='utf-8') as f:
        text = f.read()
    for code in re.findall(r"can\(\s*'([^']+)'", text):
        counts[code] = counts.get(code, 0) + 1
    return counts


def main():
    only_list = '--list' in sys.argv
    strict = '--strict' in sys.argv

    codes = declared_codes()
    back, front = backend_refs(), frontend_refs()

    rows = []
    for code in codes:
        b, f = back.get(code, 0), front.get(code, 0)
        rows.append((code, b, f, b + f))

    dead = [r for r in rows if r[3] == 0]
    alive = [r for r in rows if r[3] > 0]

    print('=' * 78)
    print('权限点可达性检查（声明的控制必须有引用点）')
    print('=' * 78)
    print('声明 %d 个权限点，有引用 %d 个，零引用 %d 个' % (len(rows), len(alive), len(dead)))
    print()
    for code, b, f, total in alive:
        print('  [有引用] %-32s 后端 %-2d 前端 %-2d' % (code, b, f))
    for code, b, f, total in dead:
        mark = '欠账  ' if code in ALLOWLIST else '新增!!'
        print('  [%s] %-32s 后端 %-2d 前端 %-2d' % (mark, code, b, f))

    if only_list:
        raise SystemExit(0)

    problems = []
    new_dead = [c for c, _b, _f, t in dead if t == 0 and c not in ALLOWLIST]
    for code in new_dead:
        problems.append('权限点「%s」零引用（后端无 @RequirePerm、前端无 can()）：'
                        '它不会产生任何效果，请补门控或从 sys_permission 目录里删除' % code)
    for code in [c for c, _b, _f, t in alive if c in ALLOWLIST]:
        print()
        print('  ↑「%s」已经有引用了，请把它从 ALLOWLIST 里删掉（收紧棘轮）' % code)

    if strict:
        for code, _b, _f, t in dead:
            if t == 0:
                problems.append('严格模式：权限点「%s」零引用' % code)

    print()
    if problems:
        print('结果：失败 %d 项' % len(problems))
        for p in problems:
            print('  - ' + p)
        raise SystemExit(1)

    print('结果：通过。零引用的 %d 个点全部在 ALLOWLIST 里（历史欠账，未新增）。' % len(dead))
    print('      ALLOWLIST 只允许缩短：补了门控就把对应行删掉。')
    raise SystemExit(0)


if __name__ == '__main__':
    main()

# -*- coding: utf-8 -*-
"""
管理端联调版 · 首轮 UI E2E 暴露问题的修正集合（幂等）。

这些问题是 frontend_admin_e2e.js 暴露出来的。每一条都先经代码定位确认是真缺陷，
不是测试假阳性；测试脚本自身的问题另行在 e2e 脚本里修。

【缺陷 1】人员弹窗「所属部门」字段错配（功能性，直接导致新增员工不可用）
  patch_frontend_admin.py 把模型从 department(中文名) 改成 deptId(后端主键)，
  addPerson 也改成读 newPerson.deptId；但弹窗模板是运行时 innerHTML 注入的，
  那一段仍绑 newPerson.department → deptId 恒为 null → 保存必然报「请选择所属部门」。
  下拉选项也要从 departments(字符串数组) 换成 deptOptions({id,name})。

【缺陷 2】侧边栏菜单文案与页面标题不一致（可发现性）
  pageNames.risk = '流程管理'（顶栏 h1、面包屑都用它），
  但 index="risk" 的菜单项写的是「风险预警」，用户找不到流程管理入口。
  顺带去掉那个写死的红色角标 3（页面并无告警数）。

【缺陷 3】角色树看不到「对应成员」（信息缺失）
  角色面板被运行时改写成 role-tree-card 树，而「对应成员」那一行原本插在
  .role-scope 后面——该节点在同一批脚本里已被覆盖掉，插入静默失效。
  把成员信息挂到树上真正渲染的节点里。

【缺陷 4】清理上述失效插入（死代码）
  保留 const rolePermissionTemplate 声明（同一行后面 peopleTable 还在用它），
  只删掉 roleScope 那两句无效操作。

【缺陷 5】流程管理页根本打不开（功能性，最严重的一条）
  运行时脚本把「流程管理」菜单项的 index 从 risk 改写成了 flow，
  但页面模板的分支条件是 page==='risk'，且 pageNames.risk / pageNames.flow 都叫「流程管理」，
  于是点击菜单不会有任何分支命中，只会落到最后的空态分支（el-empty「流程管理功能区」）。
  修法是让 index 留在 risk，与模板对齐。

写法沿用「短且唯一锚点 + 命中数校验」：
  锚点仍在  -> 执行替换
  新串已在  -> SKIP（说明这个补丁跑过了）
  两者都无  -> FAIL（可能文件被别处改过，整体不写入，避免改错）
"""
import io
import sys

PATHS = [
    '/Users/zhouzewei/WorkBuddy/2026-09-18-15-53-12/海峡金OA审批系统-联调版.html',
]

FIXES = [
    (
        '1. 人员弹窗·所属部门改绑 deptId + 选项改 deptOptions',
        r"""<el-form-item label="所属部门"><el-select v-model="newPerson.department"><el-option v-for="d in departments" :label="d" :value="d"></el-option></el-select></el-form-item>""",
        r"""<el-form-item label="所属部门"><el-select v-model="newPerson.deptId" placeholder="请选择部门" style="width:100%"><el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id"></el-option></el-select></el-form-item>""",
    ),
    (
        '2. 侧边栏·风险预警菜单改名为流程管理（对齐 pageNames.risk）',
        r"""<el-menu-item index="risk"><span class="mi">△</span>风险预警<el-badge :value="3" type="danger" class="menu-badge"></el-badge></el-menu-item>""",
        r"""<el-menu-item index="risk"><span class="mi">△</span>流程管理</el-menu-item>""",
    ),
    (
        '3. 角色树节点补「对应成员」',
        r"""<template v-else><i>角</i><div><b>{{data.role.name}}</b><span>{{data.role.post}} · {{data.role.scope}}</span></div>""",
        r"""<template v-else><i>角</i><div><b>{{data.role.name}}</b><span>{{data.role.post}} · {{data.role.scope}}</span><span class="role-members-line">对应成员：{{data.role.members && data.role.members.length ? data.role.members.join('、') : '暂无'}}</span></div>""",
    ),
    (
        '4. 删除失效的 .role-scope 成员插入（死代码）',
        r"""const roleScope=rolePermissionTemplate?.content.querySelector('.role-scope');roleScope?.insertAdjacentHTML('afterend',`<p class="role-members"><b>对应成员：</b>{{r.members?.join('、')||'暂未配置'}}</p>`);""",
        r"""/* 「对应成员」已挪进角色树节点（见 rolePane 的 role-tree-card 注入）；\n     .role-scope 那个容器在本次改写里已不存在，原先插在它后面的写法是静默失效的死代码。 */""",
    ),
    (
        '5. 流程管理菜单项 index 必须留在 risk（页面模板分支就是 page===\'risk\'）',
        r"""if (idx === 'risk'){ item.setAttribute('index','flow'); rename('风险预警','流程管理'); }""",
        r"""/* 流程管理菜单：index 必须保持 "risk"。
       页面模板的分支条件是 page==='risk'，而 pageNames.risk / pageNames.flow 都叫「流程管理」，
       所以一旦把 index 改成 'flow'，点击菜单不会有任何分支命中，只会落到最后的空态分支
       （表现为「流程管理功能区」的 el-empty）——流程管理页等于完全打不开。
       文案改名在静态 HTML 里已经做好，这里的 rename 保留作为兜底。 */
    if (idx === 'risk') rename('风险预警','流程管理');""",
    ),
]

rc = 0
for path in PATHS:
    s = io.open(path, encoding='utf-8').read()
    before = len(s)
    print('=' * 72)
    print(path)
    print('=' * 72)

    failed = []
    touched = 0
    for name, old, new in FIXES:
        c_old, c_new = s.count(old), s.count(new)
        if c_old == 1:
            s = s.replace(old, new, 1)
            touched += 1
            print('[ OK ] %s' % name)
        elif c_new >= 1:
            print('[SKIP] %s  -> 已应用过' % name)
        else:
            failed.append(name)
            print('[FAIL] %s  -> 锚点与目标串都不存在，文件可能被别处改过' % name)

    if failed:
        print('!! 存在无法定位的条目，本文件不写入（保持原样）')
        rc = 1
        continue

    if touched == 0:
        print('无需改动。')
    else:
        io.open(path, 'w', encoding='utf-8').write(s)
        print('写入完成：%d -> %d 字符' % (before, len(s)))

sys.exit(rc)

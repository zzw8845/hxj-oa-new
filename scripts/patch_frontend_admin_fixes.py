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

【缺陷 6】导出文件名丢日期（功能性；随 #34/#35 回退被带回来的）
  toDate() 只处理字符串/数字：v 是 Date 实例时 String(v) 得到
  "Mon Sep 21 2026 10:36:47 GMT+0800 (China Standard Time)"，经两处 replace 后
  V8 解析为 Invalid Date → toDate 返回 null → ymd() 返回空串。
  调用点 L832 是 ymd(new Date())，于是文件名成了「台账档案_.csv」。
  实测复现：node -e 用同一份 toDate 实现跑 ymd(new Date()) → ""。

【缺陷 7】非管理员登录白打 5 次 /api/permissions 的 403（噪音，非安全漏洞）
  权限点目录只服务于「角色配置」面板，而 loadBase() 的 Promise.all 无条件拉它。
  普通员工无 system:role / system:user → 403（.catch 吞掉了结果，但浏览器网络面板
  与 E2E 的 netErrors 都会留痕）。
  注意时序：loadBase() 在 afterLogin() 里被调用，而三条进入路径
  （doLogin L996 / switchAccount L1010 / onMounted L1823）都是**先**赋值 me.value
  再 afterLogin()，所以 loadBase 执行时 can() 已可读，守卫是安全的。

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
    # 【条目 2 已退役】原文是「把 risk 菜单文案改成流程管理以对齐 pageNames.risk」。
    # 该设计后来被取代：现在「流程管理」是**独立**菜单项 index="flow"，「风险预警」保留 index="risk"，
    # 两者各有自己的页面分支（frontend_business_gaps_e2e.js 第 310 行正断言这个形态）。
    # 旧锚点（带 <el-badge :value="3"> 的版本）与旧目标串在文件里都已不存在，
    # 继续保留只会让本脚本永远 FAIL 从而整体不写入 —— 故删除该条目，仅留本说明。
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
    # 【条目 5 已退役】原文是「流程管理菜单项 index 必须留在 risk」，因为当时模板只有 page==='risk' 分支。
    # 现在模板同时有 risk（风险预警）与 flow（流程管理）两个分支，那句按 index 改文案的兜底代码
    # 也已从文件里删除（frontend_business_gaps_e2e.js 第 313 行断言它不存在）。
    # 旧锚点与旧目标串在文件里都不存在，保留只会让本脚本永远 FAIL 从而整体不写入 —— 故删除该条目。
    (
        '6. toDate 兼容 Date 实例（修导出文件名「台账档案_.csv」丢日期）',
        r"""  const toDate = function(v){
    if (!v) return null;
    const d = new Date(String(v).replace('T',' ').replace(/-/g,'/'));""",
        r"""  const toDate = function(v){
    if (!v) return null;
    /* 【修复】v 可能是 Date 实例：String(Date) 得到 "Mon Sep 21 2026 10:36:47 GMT+0800 (...)"，
       经下面两处 replace 后 V8 解析为 Invalid Date → 返回 null → 所有走 toDate 的格式化都成空串。
       实测症状：导出文件名成了「台账档案_.csv」（调用点 ymd(new Date())）。Date 实例直接放行。 */
    if (v instanceof Date) return isNaN(v.getTime()) ? null : v;
    const d = new Date(String(v).replace('T',' ').replace(/-/g,'/'));""",
    ),
    (
        '7. /api/permissions 按权限点守卫（普通员工不再白打 403）',
        r"""      api.allPermissions().catch(function(){ return []; }),""",
        r"""      /* 【修复】权限点目录只服务于「角色配置」面板，普通员工没有 system:role / system:user，
         原先无条件拉取会让每次登录白打一个 403（.catch 吞掉了结果，但网络面板与 E2E 的
         netErrors 都会留痕）。loadBase 执行时 me.value 已由三条进入路径先赋值，can() 可读。 */
      (can('system:role') || can('system:user'))
        ? api.allPermissions().catch(function(){ return []; })
        : Promise.resolve([]),""",
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

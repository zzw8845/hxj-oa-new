#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
联调版前端补丁（业务缺口批）：风险预警页 + 台账导出 + 首页假数字。

幂等：目标串已在则 SKIP；锚点找不到或命中多次则整体不写入并报错。
改完自动同步到 jar 内的 static 副本（两份必须字节一致）。

本脚本处理 3 组事：

  E1 恢复「风险预警」页（它此前被流程管理页顶掉了，没有任何入口）
     - 菜单：index="risk" 的文案被改成「流程管理」、pageNames.risk 也叫「流程管理」，
       而模板分支 v-else-if="page==='risk'" 里渲染的却是流程管理内容
       ⇒ 原型的风险预警页在界面上彻底消失（不是打不开，是无处可点）。
     - 拆开：流程管理 → index="flow"（模板分支 page==='flow'）；
       风险预警 → index="risk"（新建分支 page==='risk'）
     - 页面数据来自后端 /api/risks（超期/临期节点盘点），不在前端重算
     - 顺带删掉运行时注入里那句 `if (idx === 'risk') rename('风险预警','流程管理')`：
       它当年是为了兜底文案，现在两页各占一个 index，再按 index 改文案会把名字改错

  E2 「导出台账」按钮接真实导出（此前是个无 handler 的死按钮）
     - 后端 /api/documents/export 出 CSV（UTF-8 BOM，Excel 打开不乱码）
     - 按钮按权限点 document:export 显隐（ADMIN/GM/财务总监/会计持有）
     - 导出条件与列表筛选一致，保证「导出的就是所见的」

  E3 修首页写死的假数字 —— 本轮发现的真实缺陷
     - 「今日有 **5 项审批**待处理，3 项风险预警需要关注」整句写死在模板里，
       既没有运行时补丁覆盖它，数字也与任何后端统计无关
     - 改为 {{todoCount}} / {{riskOverview.overdue}}（真实值）

  E4 修台账档案的日期筛选是死控件 —— 本轮发现的真实缺陷
     - `archiveDate` 绑了 v-model，但 archiveDocs 的过滤条件里从没用过它，
       页面上写着「支持多条件组合查询」，实际日期范围选了什么都不会变
     - 统一按「归档时间」(updatedAt) 过滤，与列表那一列、以及后端导出同一口径
"""

import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HTML = os.path.join(ROOT, '海峡金OA审批系统-联调版.html')
STATIC = os.path.join(ROOT, 'oa-backend/oa-boot/src/main/resources/static/oa.html')


def sha(path, n=16):
    return subprocess.run(['shasum', '-a', '256', path], capture_output=True, text=True
                          ).stdout.split()[0][:n]


# ================================================================ 精确串替换
# (说明, 旧串, 新串, 幂等标记)
EXACT_EDITS = [

    # ---------------- E3：首页那句写死的假数字 ----------------
    ("首页问候语：待办数与风险数改真实值（原为写死的 5 / 3）",
     "<p>今日有 <b>5 项审批</b>待处理，3 项风险预警需要关注。</p>",
     "<p>今日有 <b>{{todoCount}} 项审批</b>待处理，{{riskOverview.overdue}} 项风险预警需要关注。</p>",
     "{{riskOverview.overdue}} 项风险预警需要关注"),

    # ---------------- E1-1：菜单拆成 flow / risk 两项 ----------------
    ("菜单：流程管理改 index=flow，并补出「风险预警」入口",
     '<el-menu-item index="risk"><span class="mi">△</span>流程管理</el-menu-item>',
     '<el-menu-item index="flow"><span class="mi">▷</span>流程管理</el-menu-item>'
     '<el-menu-item index="risk"><span class="mi">△</span>风险预警</el-menu-item>',
     '<el-menu-item index="risk"><span class="mi">△</span>风险预警</el-menu-item>'),

    # ---------------- E1-2：pageNames 拆开 ----------------
    ("pageNames：risk 改回「风险预警」（flow 仍为「流程管理」）",
     "risk:'流程管理', flow:'流程管理'}",
     "risk:'风险预警', flow:'流程管理'}",
     "risk:'风险预警', flow:'流程管理'}"),

    # ---------------- E1-3 + E1-4：改分支条件 + 插入风险预警页 ----------------
    # 同一处一次改完：把原「流程管理」分支的条件改成 page==='flow'，
    # 并在它**前面**插入新的 page==='risk' 分支。
    # 必须插在链内（不能插到链前）：这一串是 v-if / v-else-if 兄弟节点，
    # 把一个 v-else-if 插到 v-if 之前，Vue 编译器会直接报错。
    ("模板链：流程管理分支改 page==='flow'，并插入新的风险预警分支",
     '<template v-else-if="page===\'risk\'"><div class="page-head"><div><h2>流程管理</h2>',
     '<template v-else-if="page===\'risk\'">'
     '<div class="page-head"><div><h2>风险预警</h2>'
     '<p>处理时限已超期或即将到期的审批节点，仅含你数据范围内可见的单据</p></div>'
     '<el-button type="primary" plain @click="refreshRisks">刷新</el-button></div>'
     '<div class="stats">'
     '<el-card v-for="s in riskStats" :key="s.name" shadow="never">'
     '<i :class="s.color">{{s.icon}}</i>'
     '<div><span>{{s.name}}</span><b>{{s.value}}</b><small>{{s.note}}</small></div>'
     '</el-card>'
     '</div>'
     '<el-card shadow="never"><template #header><div class="card-title">'
     '<div><b>超期 / 临期明细</b><span>按处理时限升序，点行可打开单据</span></div>'
     '<el-tag type="info">{{riskOverview.runningTotal}} 个进行中节点</el-tag>'
     '</div></template>'
     '<el-table :data="riskRows" @row-click="openDetail" class="click-table"'
     ' empty-text="当前数据范围内没有超期或临期的审批节点">'
     '<el-table-column prop="docNo" label="单据编号" width="165"></el-table-column>'
     '<el-table-column prop="title" label="申请事项" min-width="190"></el-table-column>'
     '<el-table-column prop="docTypeName" label="单据类型" width="110"></el-table-column>'
     '<el-table-column prop="applicant" label="申请人" width="90"></el-table-column>'
     '<el-table-column prop="department" label="申请部门" width="120"></el-table-column>'
     '<el-table-column prop="nodeName" label="卡在节点" width="140"></el-table-column>'
     '<el-table-column prop="assignee" label="承办人" width="95"></el-table-column>'
     '<el-table-column prop="deadlineText" label="处理时限" width="150"></el-table-column>'
     '<el-table-column label="风险" width="130"><template #default="s">'
     '<el-tag :type="s.row.tag">{{s.row.riskText}}</el-tag>'
     '</template></el-table-column>'
     '</el-table></el-card></template>'
     '<template v-else-if="page===\'flow\'"><div class="page-head"><div><h2>流程管理</h2>',
     "v-else-if=\"page==='flow'\"><div class=\"page-head\"><div><h2>流程管理</h2>"),

    # ---------------- E1-5：删掉运行时那句按 index 改文案的兜底 ----------------
    # 保留 rename 函数本身（approve 那一项还在用）。
    ("运行时注入：删掉 `if (idx === 'risk') rename('风险预警','流程管理')`",
     "    /* 流程管理菜单：index 必须保持 \"risk\"。\n"
     "       页面模板的分支条件是 page==='risk'，而 pageNames.risk / pageNames.flow 都叫「流程管理」，\n"
     "       所以一旦把 index 改成 'flow'，点击菜单不会有任何分支命中，只会落到最后的空态分支\n"
     "       （表现为「流程管理功能区」的 el-empty）——流程管理页等于完全打不开。\n"
     "       文案改名在静态 HTML 里已经做好，这里的 rename 保留作为兜底。 */\n"
     "    if (idx === 'risk') rename('风险预警','流程管理');\n",
     "    /* 菜单 index 与页面分支的对应关系（改之前先看这里）：\n"
     "       · flow → pageNames.flow='流程管理'，模板分支 v-else-if=\"page==='flow'\"\n"
     "       · risk → pageNames.risk='风险预警'，模板分支 v-else-if=\"page==='risk'\"\n"
     "       两页曾经共用 index='risk'（风险预警页因此没有入口），现已拆开。\n"
     "       这里**不要**再加按 index 改文案的兜底：静态标记里的文案已经是对的，\n"
     "       任何按 index 匹配的 rename 都会把其中一页的名字改错。 */\n",
     "两页曾经共用 index='risk'（风险预警页因此没有入口），现已拆开。"),

    # ---------------- E2-1：导出台账按钮接线 ----------------
    ("台账档案：「导出台账」按钮接真实导出（并按权限点显隐）",
     "<el-button>导出台账</el-button>",
     "<el-button v-if=\"can('document:export')\" :loading=\"exporting\" @click=\"exportLedger\">"
     "导出台账</el-button>",
     "v-if=\"can('document:export')\" :loading=\"exporting\" @click=\"exportLedger\""),

    # ---------------- E2-2：api 补 risks ----------------
    ("api：补 risks（风险预警数据源）",
     "  docStats:     function(){ return http('/api/documents/stats'); },",
     "  docStats:     function(){ return http('/api/documents/stats'); },\n"
     "  /* 风险预警：后端按「进行中节点 + deadline」盘点，受行级数据范围约束。\n"
     "     导出用的是 httpBlob（要带 Authorization 头），不走这里的 http()。 */\n"
     "  risks:        function(){ return http('/api/risks'); },",
     "risks:        function(){ return http('/api/risks'); },"),

    # ---------------- E4：台账日期筛选是死控件 ----------------
    ("台账档案：日期范围参与过滤（此前 archiveDate 绑了 v-model 却从没被用过）",
     "  const archiveDocs = computed(function(){",
     "  /* 台账时间范围：按「归档时间」(updatedAt) 筛 —— 与列表那一列、以及后端导出同一口径。\n"
     "     此前 archiveDate 绑了 v-model 却从没参与过滤，日期选择器是个死控件，\n"
     "     而页面文案写着「支持多条件组合查询」。 */\n"
     "  const inArchiveRange = function(d){\n"
     "    const range = archiveDate.value;\n"
     "    if (!Array.isArray(range) || range.length !== 2 || !range[0] || !range[1]) return true;\n"
     "    const t = toDate(d.updatedAt);\n"
     "    if (!t) return false;\n"
     "    const from = new Date(range[0]); from.setHours(0, 0, 0, 0);\n"
     "    const to = new Date(range[1]); to.setHours(23, 59, 59, 999);\n"
     "    return t >= from && t <= to;\n"
     "  };\n"
     "  const archiveDocs = computed(function(){",
     "const inArchiveRange = function(d){"),

    ("台账档案：archiveDocs 过滤条件里接上日期范围",
     "        && (!archiveCode.value || d.id.indexOf(archiveCode.value) >= 0);\n"
     "    });\n"
     "  });",
     "        && (!archiveCode.value || d.id.indexOf(archiveCode.value) >= 0)\n"
     "        && inArchiveRange(d);\n"
     "    });\n"
     "  });",
     "&& inArchiveRange(d);"),

    # ---------------- E1-6 + E2-3：状态、加载函数与暴露 ----------------
    ("补状态与函数：riskOverview / refreshRisks / riskStats / riskRows / can / exportLedger",
     "  /* ---------- 加载 ---------- */",
     "  /* ---------- 风险预警 ---------- */\n"
     "  /* 只读后端 /api/risks 的结果，**不在前端重算超期**：deadline 是节点创建时按节点配置的\n"
     "     sla_hours 写进 flow_instance_node.deadline 的，前端再算一遍就会与待办页的 overdue\n"
     "     标记、以及首页那句汇总对不上（同一屏两个数字各说各话）。\n"
     "     接口不可用时保留上一次的值 —— 宁可显示旧值，也不凭空编一个 0 出来。 */\n"
     "  const riskOverview = ref({overdue:0, dueSoon:0, runningTotal:0, noDeadline:0,\n"
     "                            riskTotal:0, dueSoonHours:24, items:[]});\n"
     "  const refreshRisks = async function(){\n"
     "    try {\n"
     "      const r = await api.risks();\n"
     "      if (r) riskOverview.value = r;\n"
     "    } catch(e){ /* 保留上一次的值 */ }\n"
     "  };\n"
     "  const riskStats = computed(function(){\n"
     "    const r = riskOverview.value;\n"
     "    return [\n"
     "      {name:'已超期', value:r.overdue, note: r.overdue ? '已超出处理时限' : '暂无超期',\n"
     "       icon:'超', color:'red'},\n"
     "      {name:'即将到期', value:r.dueSoon, note: r.dueSoonHours + ' 小时内到期',\n"
     "       icon:'临', color:'orange'},\n"
     "      {name:'进行中节点', value:r.runningTotal,\n"
     "       note:'其中无时限 ' + r.noDeadline + ' 个', icon:'行', color:'blue'}\n"
     "    ];\n"
     "  });\n"
     "  const riskRows = computed(function(){\n"
     "    return (riskOverview.value.items || []).map(function(it){\n"
     "      const overdue = it.level === 'overdue';\n"
     "      return {\n"
     "        id: it.docNo, docId: it.documentId, docNo: it.docNo, title: it.title || '',\n"
     "        docTypeName: it.docTypeName || '', applicant: it.applicantName || '',\n"
     "        department: it.deptName || '', nodeName: it.nodeName || '',\n"
     "        assignee: it.assigneeName || '多人候选',\n"
     "        deadlineText: fmtDateTime(it.deadline),\n"
     "        riskText: overdue\n"
     "          ? ('已超期 ' + (it.overdueHours || 0) + ' 小时')\n"
     "          : ('剩 ' + (it.remainHours || 0) + ' 小时'),\n"
     "        tag: overdue ? 'danger' : 'warning'\n"
     "      };\n"
     "    });\n"
     "  });\n"
     "\n"
     "  /* ---------- 台账导出 ---------- */\n"
     "  /* 权限点来自登录时的快照（me.permCodes）——后端把权限点编在 JWT 里、校验零查库，\n"
     "     所以改了角色必须重新登录才生效，这里读到的也永远是登录那一刻的快照。 */\n"
     "  const can = function(code){\n"
     "    const p = me.value && me.value.permCodes;\n"
     "    return Array.isArray(p) && p.indexOf(code) >= 0;\n"
     "  };\n"
     "  const exporting = ref(false);\n"
     "  const ymd = function(v){\n"
     "    const d = toDate(v);\n"
     "    if (!d) return '';\n"
     "    const p = function(n){ return String(n).padStart(2, '0'); };\n"
     "    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());\n"
     "  };\n"
     "  /* 导出条件与页面筛选保持一致，保证「导出的就是所见的」；\n"
     "     后端会再按行级数据范围收一次，且不带 status 参数（台账口径固定已通过/已归档）。 */\n"
     "  const exportLedger = async function(){\n"
     "    if (exporting.value) return;\n"
     "    exporting.value = true;\n"
     "    try {\n"
     "      const qs = [];\n"
     "      if (archiveApplicant.value) qs.push('applicant=' + encodeURIComponent(archiveApplicant.value));\n"
     "      if (archiveDepartment.value) qs.push('department=' + encodeURIComponent(archiveDepartment.value));\n"
     "      if (archiveCode.value) qs.push('docNo=' + encodeURIComponent(archiveCode.value));\n"
     "      const range = archiveDate.value;\n"
     "      if (Array.isArray(range) && range.length === 2 && range[0] && range[1]){\n"
     "        qs.push('dateFrom=' + ymd(range[0]));\n"
     "        qs.push('dateTo=' + ymd(range[1]));\n"
     "      }\n"
     "      const blob = await httpBlob('/api/documents/export' + (qs.length ? '?' + qs.join('&') : ''));\n"
     "      saveBlob(blob, '台账档案_' + ymd(new Date()) + '.csv');\n"
     "      ElementPlus.ElMessage.success('台账已导出');\n"
     "    } catch(e){\n"
     "      ElementPlus.ElMessage.error('导出失败：' + e.message);\n"
     "    } finally {\n"
     "      exporting.value = false;\n"
     "    }\n"
     "  };\n"
     "\n"
     "  /* ---------- 加载 ---------- */",
     "const riskOverview = ref({overdue:0, dueSoon:0, runningTotal:0, noDeadline:0,"),

    # ---------------- E1-7：登录后与切页时刷新风险数据 ----------------
    ("afterLogin：一并拉取风险预警数据",
     "    await loadBase();\n"
     "    await Promise.all([refreshDocs(), refreshTodos(), refreshStats(), refreshUnread()]);",
     "    await loadBase();\n"
     "    await Promise.all([refreshDocs(), refreshTodos(), refreshStats(), refreshUnread(), refreshRisks()]);",
     "refreshUnread(), refreshRisks()]);"),

    ("切页 watch：进入首页/看板/审批与风险预警页时刷新风险数据",
     "    if (v === 'home' || v === 'board' || v === 'approve'){\n"
     "      refreshTodos().catch(function(){});\n"
     "      refreshStats().catch(function(){});\n"
     "      refreshUnread().catch(function(){});\n"
     "    }\n"
     "  });",
     "    if (v === 'home' || v === 'board' || v === 'approve'){\n"
     "      refreshTodos().catch(function(){});\n"
     "      refreshStats().catch(function(){});\n"
     "      refreshUnread().catch(function(){});\n"
     "      /* 首页那句「N 项风险预警需要关注」用的是这份数据，必须一起刷 */\n"
     "      refreshRisks().catch(function(){});\n"
     "    }\n"
     "    if (v === 'risk'){\n"
     "      refreshRisks().catch(function(){});\n"
     "    }\n"
     "  });",
     "if (v === 'risk'){\n      refreshRisks().catch(function(){});"),

    ("setup 暴露新增的状态与函数",
     "    bizTypeOptions, docTypeOptions, sealTypeChoices,",
     "    bizTypeOptions, docTypeOptions, sealTypeChoices,\n"
     "    riskOverview, riskStats, riskRows, refreshRisks, can, exporting, exportLedger,",
     "riskOverview, riskStats, riskRows, refreshRisks, can, exporting, exportLedger,"),
]


def _already_applied(src, mark):
    """幂等判定；mark 以 `!` 开头表示「不含该串才算已应用」。"""
    if mark.startswith('!'):
        return mark[1:] not in src
    return mark in src


def apply():
    with open(HTML, encoding='utf-8') as f:
        src = f.read()
    original = src

    print('=' * 76)
    print('前端补丁（业务缺口批）：风险预警页 + 台账导出 + 首页假数字 + 台账日期筛选')
    print('=' * 76)

    failed = False
    for name, old, new, mark in EXACT_EDITS:
        if _already_applied(src, mark):
            print(f'  SKIP  {name}（已应用）')
            continue
        cnt = src.count(old)
        if cnt == 0:
            print(f'  FAIL  {name}：锚点未找到，整体不写入')
            failed = True
            continue
        if cnt > 1:
            print(f'  FAIL  {name}：锚点命中 {cnt} 次（应唯一），整体不写入')
            failed = True
            continue
        src = src.replace(old, new, 1)
        print(f'  OK    {name}')

    if failed:
        print('\n  有锚点未命中 —— 未写入任何改动')
        return 1

    # ---- 先校验、通过了才落盘 ----
    print()
    ok = True
    checks = [
        ("首页假数字已改真实值",
         "{{todoCount}} 项审批</b>待处理，{{riskOverview.overdue}} 项风险预警需要关注" in src),
        ("首页 5/3 写死数字已不存在", "5 项审批" not in src and "3 项风险预警" not in src),
        ("风险预警菜单项已存在且 index=risk",
         '<el-menu-item index="risk"><span class="mi">△</span>风险预警</el-menu-item>' in src),
        ("流程管理菜单项已改 index=flow",
         '<el-menu-item index="flow"><span class="mi">▷</span>流程管理</el-menu-item>' in src),
        ("pageNames：risk=风险预警 / flow=流程管理",
         "risk:'风险预警', flow:'流程管理'}" in src),
        ("模板分支：page==='flow' 渲染流程管理",
         "v-else-if=\"page==='flow'\"><div class=\"page-head\"><div><h2>流程管理</h2>" in src),
        ("模板分支：page==='risk' 渲染风险预警",
         "v-else-if=\"page==='risk'\"><div class=\"page-head\"><div><h2>风险预警</h2>" in src),
        ("风险表已接 riskRows", '<el-table :data="riskRows" @row-click="openDetail"' in src),
        ("运行时那句改文案的兜底已删",
         "if (idx === 'risk') rename('风险预警','流程管理');" not in src),
        # rename 函数本身要留着（approve 那一项还在用）
        ("rename 函数仍在（approve 仍依赖它）",
         "if (idx === 'approve') rename('审批中心','待我审批');" in src),
        ("导出台账按钮已接 exportLedger", '@click="exportLedger"' in src),
        ("导出按钮受 document:export 权限控制",
         "v-if=\"can('document:export')\"" in src),
        ("api.risks 已补", "risks:        function(){ return http('/api/risks'); }," in src),
        ("台账日期筛选已接线", "&& inArchiveRange(d);" in src),
        ("inArchiveRange 已定义", "const inArchiveRange = function(d){" in src),
        ("风险状态已定义", "const riskOverview = ref({overdue:0, dueSoon:0, runningTotal:0, noDeadline:0," in src),
        ("首页/风险页刷新已接", "refreshUnread(), refreshRisks()]);" in src),
        ("setup 已暴露新名字",
         "riskOverview, riskStats, riskRows, refreshRisks, can, exporting, exportLedger," in src),
        # 防止误删同批语句
        ("台账其它筛选仍在", "&& (!archiveDepartment.value || d.department === archiveDepartment.value)" in src),
        ("流程配置网格仍在（流程管理页内容没被删）", 'class="flow-config-grid"' in src),
        ("通知抽屉仍在", 'title="通知中心" size="520px"' in src),
        ("铃铛仍常驻（:hidden 而非 v-if）", "bell.setAttribute(':hidden','!unreadCount')" in src),
    ]
    for label, good in checks:
        print(f'  {"✓" if good else "✗"} {label}')
        ok = ok and good

    # v-if / v-else-if 链完整性：新插的 v-else-if 必须排在 v-if 之后
    i_if = src.find("v-if=\"page==='home'\"")
    i_flow = src.find("v-else-if=\"page==='flow'\"")
    i_risk = src.find("v-else-if=\"page==='risk'\"")
    chain_ok = 0 < i_if < i_flow and 0 < i_if < i_risk
    print(f'  {"✓" if chain_ok else "✗"} 页面分支链顺序合法（home → flow / risk）'
          f'[{i_if} {i_flow} {i_risk}]')
    ok = ok and chain_ok

    scripts = re.findall(r'<script>(.*?)</script>', src, re.S)
    inline = [s for s in scripts if len(s) > 5000]
    js_ok = True
    if inline:
        tmp = '/tmp/_oa_inline_check_gaps.js'
        with open(tmp, 'w', encoding='utf-8') as f:
            f.write(max(inline, key=len))
        r = subprocess.run(['/Users/zhouzewei/.workbuddy/binaries/node/versions/22.22.2-3/bin/node',
                            '--check', tmp], capture_output=True, text=True)
        js_ok = r.returncode == 0
        print(f'  {"✓" if js_ok else "✗"} 内联脚本语法检查'
              + ('' if js_ok else '\n' + r.stderr[:1500]))

    if not (ok and js_ok):
        print('\n  校验未通过 —— 未写入任何改动（原文件保持不动）')
        return 1

    if src == original:
        print('\n  全部已应用，无需改动')
    else:
        with open(HTML, 'w', encoding='utf-8') as f:
            f.write(src)
        print(f'\n  已写入 {os.path.basename(HTML)}')

    shutil.copyfile(HTML, STATIC)
    a, b = sha(HTML), sha(STATIC)
    print(f'  两份副本 sha256[:16]：{a} / {b}  →  {"一致" if a == b else "不一致!"}')
    return 0 if a == b else 1


if __name__ == '__main__':
    sys.exit(apply())

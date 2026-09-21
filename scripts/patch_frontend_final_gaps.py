#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
联调版前端补丁（收口批）：首页图表接后端权威统计 + 通知中心 + 清死代码/修写死的筛选清单。

幂等：目标串已在则 SKIP；锚点找不到或命中多次则整体不写入并报错。
改完自动同步到 jar 内的 static 副本（两份必须字节一致）。

本脚本处理 4 组事：

  D1 首页「近7天发起量」改后端权威统计
     - weekBars/weekTotal 优先取 /api/documents/stats 的 dailyCounts（数据库聚合，
       不受列表 500 条上限影响），接口不可用时降级为按已加载列表统计
     - 清掉运行时注入里那段**被覆盖的写死图表内容**（26/14/9/3 + 7 天假柱状图）。
       它虽然不渲染，但留着是雷：谁把两段补丁的顺序一动，假数字就复活。

  D2 通知中心（顶部铃铛 → 抽屉）
     - 用起此前「定义了却零调用」的 api.notifications / api.readAllNotify，并补 api.readNotify
     - 列出通知（标题/内容/时间/未读态）、点击单条标记已读、底部「全部已读」、分页
     - 铃铛角标由待办数改为**真实未读通知数**（原先挂 todoCount，与「待我审批」重复）
     - 点通知能在当前列表里定位到那张单据就直接打开详情

  D3 清死代码
     - 删死函数 notImplemented（无任何调用点）
     - 删重复接口 api.permissions（与 allPermissions 同用途，前者零调用）
     - 删零调用的 api.attachments（附件随 docDetail 一起返回）

  D4 修「写死的筛选清单」——这是本轮发现的真实缺陷
     - 「单据类型」写死 ['日常申请单','付款申请单','用印申请单']，而筛选比对的是
       documentType(d) 的合成名（只有 付款/报销/用印 三种申请单）→「日常申请单」永远匹配不到
     - 「业务类型」写死 ['日常付款','业务付款','用印申请']，而列表里 d.type 是真实类型名
       （「日常付款申请」）→「日常付款」「业务付款」永远匹配不到
     - 两者均改为从 documentTypes 推导，保证选项与比对口径一致、每个选项都可达
     - 顺带把 toDoc 补上 bizCategory 映射（此前根本没人填，业务类型筛选无从比对）
     - 「用章类型」由写死 5 项改回字典驱动（带静态兜底），消除同一份清单两处维护
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
    # ---------------- D4-0：模块级加业务类型映射（放 STATUS_TEXT 旁边） ----------------
    ("模块级常量：业务类型中英文映射",
     "const STATUS_TEXT = {0:'草稿',1:'待审批',2:'审批中',3:'已通过',4:'已驳回',5:'已撤回',6:'已归档'};\n",
     "const STATUS_TEXT = {0:'草稿',1:'待审批',2:'审批中',3:'已通过',4:'已驳回',5:'已撤回',6:'已归档'};\n"
     "/* 后端 document.business_category（DAILY/BIZ/REIMBURSE/SEAL）→ 界面业务类型。\n"
     "   此前 toDoc 压根没映射这个字段，导致「业务类型」筛选拿不到可比对的值。 */\n"
     "const BIZ_CATEGORY_TEXT = {DAILY:'日常付款', BIZ:'业务付款', REIMBURSE:'员工报销', SEAL:'用印申请'};\n",
     "const BIZ_CATEGORY_TEXT = {DAILY:'日常付款'"),

    # ---------------- D4-1：toDoc 补 bizCategory ----------------
    ("toDoc：补上 bizCategory（业务类型筛选的比对依据）",
     "      updated: relTime(r.updatedAt || r.createdAt),\n",
     "      bizCategory: BIZ_CATEGORY_TEXT[r.businessCategory] || '',\n"
     "      updated: relTime(r.updatedAt || r.createdAt),\n",
     "bizCategory: BIZ_CATEGORY_TEXT[r.businessCategory]"),

    # ---------------- D1-1：weekBars 优先后端 ----------------
    ("weekBars：优先后端 dailyCounts，降级为按列表统计",
     "  /* 近7天发起量：按 submittedAt 真实统计 */\n"
     "  const weekBars = computed(function(){\n"
     "    const names = ['周日','周一','周二','周三','周四','周五','周六'];\n"
     "    const now = new Date(), rows = [];\n"
     "    for (let i = 6; i >= 0; i--){\n"
     "      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);\n"
     "      const key = d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0');\n"
     "      const cnt = documents.value.filter(function(x){ return String(x.submittedAt || '').slice(0,10) === key; }).length;\n"
     "      rows.push({label:names[d.getDay()], day:(d.getMonth()+1)+'/'+d.getDate(), count:cnt});\n"
     "    }\n"
     "    const max = Math.max(1, rows.reduce(function(a,b){ return Math.max(a, b.count); }, 0));\n"
     "    return rows.map(function(r){ return {label:r.label, day:r.day, count:r.count, height:Math.round(r.count*100/max)}; });\n"
     "  });\n",
     "  /* 近7天发起量：优先后端统计（stats.dailyCounts）——\n"
     "     它是数据库聚合、且与圆环同一道行级范围，不受列表 500 条上限影响；\n"
     "     接口不可用时降级为「按已加载列表统计」：可能不全，但不会凭空编数字。 */\n"
     "  const weekBars = computed(function(){\n"
     "    const names = ['周日','周一','周二','周三','周四','周五','周六'];\n"
     "    const withHeights = function(rows){\n"
     "      const max = Math.max(1, rows.reduce(function(a,b){ return Math.max(a, b.count); }, 0));\n"
     "      return rows.map(function(r){\n"
     "        return {label:r.label, day:r.day, count:r.count, height:Math.round(r.count*100/max)};\n"
     "      });\n"
     "    };\n"
     "    const s = docStats.value;\n"
     "    if (s && s.dailyCounts && s.dailyCounts.length){\n"
     "      return withHeights(s.dailyCounts.map(function(x){\n"
     "        const parts = String(x.date).split('-');\n"
     "        const d = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));\n"
     "        return {label:names[d.getDay()], day:Number(parts[1]) + '/' + Number(parts[2]),\n"
     "                count:Number(x.count || 0)};\n"
     "      }));\n"
     "    }\n"
     "    const now = new Date(), rows = [];\n"
     "    for (let i = 6; i >= 0; i--){\n"
     "      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);\n"
     "      const key = d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0');\n"
     "      const cnt = documents.value.filter(function(x){ return String(x.submittedAt || '').slice(0,10) === key; }).length;\n"
     "      rows.push({label:names[d.getDay()], day:(d.getMonth()+1)+'/'+d.getDate(), count:cnt});\n"
     "    }\n"
     "    return withHeights(rows);\n"
     "  });\n",
     "s.dailyCounts && s.dailyCounts.length"),

    # ---------------- D1-2：weekTotal 优先后端 ----------------
    ("weekTotal：优先后端统计",
     "  const weekTotal = computed(function(){\n"
     "    return weekBars.value.reduce(function(a,b){ return a + b.count; }, 0);\n"
     "  });\n",
     "  const weekTotal = computed(function(){\n"
     "    const s = docStats.value;\n"
     "    if (s && typeof s.weekTotal === 'number') return s.weekTotal;\n"
     "    return weekBars.value.reduce(function(a,b){ return a + b.count; }, 0);\n"
     "  });\n",
     "if (s && typeof s.weekTotal === 'number') return s.weekTotal;"),

    # ---------------- D2-1：api 补 readNotify ----------------
    ("api：补 readNotify（单条标记已读）",
     "  readAllNotify:function(){ return http('/api/notifications/read-all', {method:'POST'}); },\n",
     "  readAllNotify:function(){ return http('/api/notifications/read-all', {method:'POST'}); },\n"
     "  readNotify:   function(id){ return http('/api/notifications/' + id + '/read', {method:'POST'}); },\n",
     "readNotify:   function(id){"),

    # ---------------- D3-1：删死接口 ----------------
    # 删除类条目的幂等标记用 `!xxx` 形式：表示「src 里已不含 xxx 就算已应用」。
    # 不能用「存在某串」当标记 —— 删除前后都可能存在，第一次就会被误判成已应用。
    ("删死接口：api.permissions（与 allPermissions 重复，零调用）",
     "  permissions:  function(){ return http('/api/auth/permissions'); },\n",
     "", "!function(){ return http('/api/auth/permissions'); }"),

    ("删死接口：api.attachments（零调用，附件随 docDetail 返回）",
     "  attachments:  function(documentId){ return http('/api/attachments', {params:{documentId:documentId}}); },\n",
     "", "!http('/api/attachments', {params:{documentId:documentId}})"),

    # ---------------- D3-2：删死函数 notImplemented ----------------
    ("删死函数：notImplemented（无任何调用点）",
     "  /* ---------- 后端尚未提供的写接口：明确告知，不做本地伪造 ---------- */\n"
     "  const notImplemented = function(what, apiHint){\n"
     "    ElementPlus.ElMessageBox.alert(\n"
     "      '后端暂未提供该接口，前端不做本地伪造。\\n\\n缺失能力：' + what + '\\n建议新增：' + apiHint,\n"
     "      '接口缺口', {confirmButtonText:'知道了', type:'warning'}\n"
     "    ).catch(function(){});\n"
     "  };\n"
     "  /* ---------- 管理端写操作：已接入真实后端 ---------- */\n",
     "  /* ---------- 管理端写操作：已接入真实后端 ---------- */\n",
     "!const notImplemented"),

    # ---------------- D2-2：refs ----------------
    ("refs：通知抽屉状态",
     "  const docStats = ref(null), unreadCount = ref(0);\n",
     "  const docStats = ref(null), unreadCount = ref(0);\n"
     "  /* 通知中心（铃铛抽屉）：列表走 /api/notifications 分页，未读数走 unread-count */\n"
     "  const notifyVisible = ref(false), notifyList = ref([]), notifyTotal = ref(0);\n"
     "  const notifyLoading = ref(false), notifyPage = ref(1);\n"
     "  const NOTIFY_PAGE_SIZE = 20;\n",
     "const notifyVisible = ref(false), notifyList = ref([]), notifyTotal = ref(0);"),

    # ---------------- D2-3：通知相关函数 ----------------
    ("新增 refreshNotify / openNotify / markNotifyRead / markAllNotifyRead",
     "  const refreshUnread = async function(){\n"
     "    try { unreadCount.value = (await api.unreadCount()) || 0; } catch(e){ unreadCount.value = 0; }\n"
     "  };\n",
     "  const refreshUnread = async function(){\n"
     "    try { unreadCount.value = (await api.unreadCount()) || 0; } catch(e){ unreadCount.value = 0; }\n"
     "  };\n"
     "  /* ---------- 通知中心 ---------- */\n"
     "  const toNotify = function(n){\n"
     "    return {id:n.id, title:n.title, content:n.content,\n"
     "            isRead:Number(n.isRead) === 1, time:fmtDateTime(n.createdAt),\n"
     "            bizType:n.bizType, bizId:n.bizId};\n"
     "  };\n"
     "  const refreshNotify = async function(){\n"
     "    notifyLoading.value = true;\n"
     "    try {\n"
     "      const res = await api.notifications({pageNum:notifyPage.value, pageSize:NOTIFY_PAGE_SIZE});\n"
     "      notifyList.value = ((res && res.records) || []).map(toNotify);\n"
     "      notifyTotal.value = (res && res.total) || notifyList.value.length;\n"
     "    } catch(e){\n"
     "      notifyList.value = []; notifyTotal.value = 0;\n"
     "      ElementPlus.ElMessage.error('通知加载失败：' + (e.message || ''));\n"
     "    } finally { notifyLoading.value = false; }\n"
     "  };\n"
     "  const openNotify = async function(){\n"
     "    notifyVisible.value = true; notifyPage.value = 1;\n"
     "    await refreshNotify();\n"
     "  };\n"
     "  /** 标记单条已读：已是已读就不打接口（避免无意义写库）；顺带刷新未读角标 */\n"
     "  const markNotifyRead = async function(n){\n"
     "    if (!n.isRead){\n"
     "      try { await api.readNotify(n.id); n.isRead = true; refreshUnread().catch(function(){}); }\n"
     "      catch(e){ ElementPlus.ElMessage.error('标记已读失败：' + (e.message || '')); return; }\n"
     "    }\n"
     "    // 能在这张单上定位到就直接打开详情，省得用户自己再去搜\n"
     "    if (n.bizType === 'document' && n.bizId){\n"
     "      const hit = documents.value.find(function(d){ return Number(d.docId) === Number(n.bizId); });\n"
     "      if (hit){ notifyVisible.value = false; openDetail(hit); }\n"
     "    }\n"
     "  };\n"
     "  const markAllNotifyRead = async function(){\n"
     "    notifyLoading.value = true;\n"
     "    try {\n"
     "      await api.readAllNotify();\n"
     "      ElementPlus.ElMessage.success('已全部标记为已读');\n"
     "      notifyPage.value = 1;\n"
     "      await refreshNotify(); await refreshUnread();\n"
     "    } catch(e){\n"
     "      ElementPlus.ElMessage.error('操作失败：' + (e.message || ''));\n"
     "    } finally { notifyLoading.value = false; }\n"
     "  };\n"
     "  const changeNotifyPage = async function(p){ notifyPage.value = p; await refreshNotify(); };\n",
     "const markAllNotifyRead = async function(){"),

    # ---------------- D1-3：刷新链（stats 已在链里，dailyCounts 随之更新） ----------------
    # 注：这里没有改动 —— refreshStats 已经在登录/切页时被调用，dailyCounts 一并刷新。
    # 保留一行注释说明，避免后人以为漏了。

    # ---------------- D4-2：用章类型选项（字典驱动 + 静态兜底） ----------------
    ("新增 sealTypeChoices（字典驱动，带静态兜底）",
     "  const sealOptions = computed(function(){ return dictList('seal_type'); });\n",
     "  const sealOptions = computed(function(){ return dictList('seal_type'); });\n"
     "  /* 用章类型选项：优先用字典，字典未就绪时退回静态清单。\n"
     "     原先运行时补丁把它写死成 5 项、与字典各维护一份，字典改了前端不会跟着变。 */\n"
     "  const sealTypeChoices = computed(function(){\n"
     "    const fromDict = sealOptions.value.map(function(o){ return o.dictLabel; }).filter(Boolean);\n"
     "    return fromDict.length ? fromDict : ['公章','合同章','法人章','财务专用章','发票专用章'];\n"
     "  });\n",
     "const sealTypeChoices = computed(function(){"),

    # ---------------- D4-3：业务类型 / 单据类型筛选项来自真实数据 ----------------
    ("新增 bizTypeOptions / docTypeOptions（从 documentTypes 推导）",
     "  const stampQuick     = computed(function(){ return quickOf('SEAL', ['用印申请']); });\n",
     "  const stampQuick     = computed(function(){ return quickOf('SEAL', ['用印申请']); });\n"
     "\n"
     "  /* 筛选项必须与「比对口径」同源，否则是摆设：\n"
     "     · 单据类型筛选比对的是 documentType(d) 的**合成名**（付款/报销/用印 三种申请单），\n"
     "       而原先写死的选项里有「日常申请单」——它永远匹配不到任何单据；\n"
     "     · 业务类型筛选比对的是 d.type（真实类型名，如「日常付款申请」），\n"
     "       而原先写死的「日常付款」「业务付款」同样永远匹配不到。\n"
     "     现改为从 documentTypes 推导：选项与口径同源，且每个选项都保证可达。 */\n"
     "  const uniqText = function(arr){\n"
     "    return arr.filter(function(v,i,a){ return !!v && a.indexOf(v) === i; });\n"
     "  };\n"
     "  const bizTypeOptions = computed(function(){\n"
     "    return uniqText(documentTypes.value.map(function(t){ return BIZ_CATEGORY_TEXT[t.category]; }));\n"
     "  });\n"
     "  const docTypeOptions = computed(function(){\n"
     "    return uniqText(documentTypes.value.map(function(t){ return documentType({type:t.name}); }));\n"
     "  });\n",
     "const bizTypeOptions = computed(function(){"),

    # ---------------- D4-4：模板改绑真实选项 ----------------
    ("筛选：业务类型改绑 bizTypeOptions",
     "<el-option v-for=\"x in ['日常付款','业务付款','用印申请']\" :label=\"x\" :value=\"x\"></el-option>",
     "<el-option v-for=\"x in bizTypeOptions\" :label=\"x\" :value=\"x\"></el-option>",
     "v-for=\"x in bizTypeOptions\""),

    ("筛选：单据类型改绑 docTypeOptions",
     "<el-option v-for=\"x in ['日常申请单','付款申请单','用印申请单']\" :label=\"x\" :value=\"x\"></el-option>",
     "<el-option v-for=\"x in docTypeOptions\" :label=\"x\" :value=\"x\"></el-option>",
     "v-for=\"x in docTypeOptions\""),

    # ---------------- D2-4：铃铛接通知中心 ----------------
    # 关键：用 :hidden 而不是 v-if。
    # v-if="unreadCount" 在未读为 0 时会把**整个铃铛（含按钮）**从 DOM 移除，
    # 于是"没有未读通知"的用户根本没有入口能打开通知中心 —— 这是上一版踩的坑：
    # 自检只断言了字符串 `bell.setAttribute('@click','openNotify')` 存在，
    # 没断言铃铛元素真的还在，所以注入了抽屉却没人打得开。
    # :hidden 只隐藏角标红点，铃铛常驻。
    ("铃铛：改为未读通知数 + 点击打开通知抽屉",
     "  const bell = qOne('.top-actions el-badge');\n"
     "  /* 铃铛＝通知中心入口。角标由「待办数」改为「未读通知数」——\n"
     "     原先它和侧栏「待我审批」挂的是同一个 todoCount，重复且语义不对。 */\n"
     "  if (bell){ bell.setAttribute(':value','unreadCount'); bell.setAttribute(':max','99');\n"
     "             bell.setAttribute('v-if','unreadCount'); bell.setAttribute('@click','openNotify');\n"
     "             bell.setAttribute('style','cursor:pointer'); }\n",
     "  /* 铃铛＝通知中心入口，必须**常驻**：角标红点用 :hidden 控制，\n"
     "     不能用 v-if —— 否则未读为 0 时铃铛整个从 DOM 消失，用户再也打不开通知中心。 */\n"
     "  const bell = qOne('.top-actions el-badge');\n"
     "  if (bell){ bell.setAttribute(':value','unreadCount'); bell.setAttribute(':max','99');\n"
     "             bell.setAttribute(':hidden','!unreadCount'); bell.setAttribute('@click','openNotify');\n"
     "             bell.setAttribute('style','cursor:pointer'); }\n",
     "bell.setAttribute(':hidden','!unreadCount')"),

    # ---------------- 暴露给模板 ----------------
    ("return：暴露通知中心与筛选项",
     "completionRate, weekBars, weekTotal, statusDist, statusBoard, docStats, unreadCount,",
     "completionRate, weekBars, weekTotal, statusDist, statusBoard, docStats, unreadCount,\n"
     "    notifyVisible, notifyList, notifyTotal, notifyLoading, openNotify, markNotifyRead,\n"
     "    markAllNotifyRead, changeNotifyPage,\n"
     "    bizTypeOptions, docTypeOptions, sealTypeChoices,",
     "notifyVisible, notifyList, notifyTotal, notifyLoading, openNotify, markNotifyRead,"),

    # ---------------- D4-5：用章类型改字典驱动 ----------------
    # 只做**子串**替换，绝不能整行替换：「用章类型」那句和
    # `querySelectorAll('[v-if*="盖章"]')` 那句、以及一个跨行注释挤在同一行，
    # 整行替换会把它们一起删掉（第一次就是这么把 JS 语法搞坏的）。
    ("运行时注入：用章类型改为字典驱动（带兜底）",
     "v-for=\"t in ['公章','合同章','法人章','财务专用章','发票专用章']\"",
     "v-for=\"t in sealTypeChoices\"",
     "v-for=\"t in sealTypeChoices\""),

    # ---------------- D4-6：待办投影的业务类型要与筛选口径一致 ----------------
    # 「业务类型」筛选用中文文案比对（如 '用印申请'），但 todoRows 里塞的是后端原始码
    # （'SEAL'/'DAILY'）⇒ 永远匹配不上，只能靠 `d.type !== filterValue` 兜底，
    # 而兜底只对「文案恰好等于单据类型名」的项有效（用印申请/员工报销），
    # 「日常付款」则两头都撞不上 → 选了必然筛空。
    # 修法与 toDoc 保持同一口径：走 BIZ_CATEGORY_TEXT 翻译。
    ("待办行：bizCategory 由原始码改中文文案（与 toDoc/筛选项同口径）",
     "        type: t.docTypeName || '', bizCategory: t.businessCategory,",
     "        type: t.docTypeName || '', bizCategory: BIZ_CATEGORY_TEXT[t.businessCategory] || '',",
     "BIZ_CATEGORY_TEXT[t.businessCategory] || ''"),
]


# ================================================================ 整行替换（用于超长运行时注入行）
# (说明, 行内可识别标记, 新整行内容, 幂等标记)
LINE_EDITS = [
    # D1：清掉被覆盖的写死图表内容，只保留容器（容器由后面的补丁填充真实内容）
    ("运行时注入：清掉写死的首页图表，只保留容器",
     "const homeTemplate=[...document.querySelectorAll('template')]",
     "const homeTemplate=[...document.querySelectorAll('template')]"
     ".find(t=>t.getAttribute('v-if')===\"page==='home'\");"
     "/* 这里只建容器：真实内容由后面「首页右下：近7天发起量」那段补丁填充。"
     "原先此处内联了一整套写死的图表（共 26 单 / 26-14-9-3 / 七天假柱状图），虽然会被"
     "后面那段整体替换掉、并不渲染，但它是个雷：两段补丁顺序一变，假数字就复活。 */"
     "if(homeTemplate){homeTemplate.innerHTML+=`<div class=\"home-analytics\"></div>`}",
     "homeTemplate.innerHTML+=`<div class=\"home-analytics\"></div>`"),
]


# ================================================================ 在指定行之前插入
# (说明, 行内可识别标记, 待插入文本, 幂等标记)
INSERT_BEFORE = [
    ("通知抽屉样式",
     "title=\"通知中心\" size=\"520px\"",
     "document.head.insertAdjacentHTML('beforeend',`<style>"
     ".notify-list{max-height:calc(100vh - 200px);overflow:auto}"
     ".notify-item{position:relative;padding:12px 14px;border:1px solid #ebeef5;border-radius:10px;"
     "margin-bottom:10px;cursor:pointer;background:#fff;transition:background .15s}"
     ".notify-item:hover{background:#f5f7fa}"
     ".notify-item.is-unread{border-left:3px solid #f56c6c}"
     ".notify-item.is-read{opacity:.72}"
     ".notify-head{display:flex;justify-content:space-between;align-items:baseline;gap:10px}"
     ".notify-head b{font-size:14px;color:#1f2d3d}"
     ".notify-head span{font-size:12px;color:#909399;white-space:nowrap}"
     ".notify-item p{margin:6px 0 0;font-size:13px;color:#5a6a7a;line-height:1.5}"
     "</style>`);\n",
     ".notify-item.is-unread{border-left:3px solid #f56c6c}"),

    ("运行时注入：通知中心抽屉",
     "/* 调整员工角色（行内入口） */",
     "document.querySelector('#app').insertAdjacentHTML('beforeend',`"
     "<el-drawer v-model=\"notifyVisible\" title=\"通知中心\" size=\"520px\" append-to-body>"
     "<div v-loading=\"notifyLoading\" class=\"notify-list\">"
     "<el-empty v-if=\"!notifyList.length\" description=\"暂无通知\" :image-size=\"80\"></el-empty>"
     "<div v-for=\"n in notifyList\" :key=\"n.id\" :class=\"['notify-item', n.isRead ? 'is-read' : 'is-unread']\""
     " @click=\"markNotifyRead(n)\">"
     "<div class=\"notify-head\"><b>{{n.title}}</b><span>{{n.time}}</span></div>"
     "<p>{{n.content}}</p>"
     "<el-tag v-if=\"!n.isRead\" size=\"small\" type=\"danger\" effect=\"plain\">未读</el-tag>"
     "</div>"
     "</div>"
     "<el-pagination v-if=\"notifyTotal > 20\" small layout=\"prev, pager, next\""
     " :current-page=\"1\" :page-size=\"20\" :total=\"notifyTotal\""
     " @current-change=\"changeNotifyPage\"></el-pagination>"
     "<template #footer>"
     "<el-button @click=\"notifyVisible=false\">关闭</el-button>"
     "<el-button type=\"primary\" :loading=\"notifyLoading\" @click=\"markAllNotifyRead\">全部已读</el-button>"
     "</template>"
     "</el-drawer>`);\n",
     "title=\"通知中心\" size=\"520px\""),
]


def _already_applied(src, mark):
    """幂等判定。

    mark 以 `!` 开头表示「反向标记」：src 里**不含**该串才算已应用。
    删除类改动必须用它 —— 被删掉的串在应用前/后都不适合当正向标记。
    """
    if mark.startswith('!'):
        return mark[1:] not in src
    return mark in src


def apply():
    with open(HTML, encoding='utf-8') as f:
        src = f.read()
    original = src

    print('=' * 76)
    print('前端补丁（收口批）：首页真数据 + 通知中心 + 清死代码/修筛选')
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

    lines = src.split('\n')
    for name, marker, newline, mark in LINE_EDITS:
        if _already_applied(src, mark):
            print(f'  SKIP  {name}（已应用）')
            continue
        idx = [i for i, l in enumerate(lines) if marker in l]
        if len(idx) != 1:
            print(f'  FAIL  {name}：整行锚点命中 {len(idx)} 行（应唯一），整体不写入')
            failed = True
            continue
        lines[idx[0]] = newline
        print(f'  OK    {name}')
    src = '\n'.join(lines)

    lines = src.split('\n')
    for name, marker, text, mark in INSERT_BEFORE:
        if _already_applied(src, mark):
            print(f'  SKIP  {name}（已应用）')
            continue
        idx = [i for i, l in enumerate(lines) if marker in l]
        if len(idx) != 1:
            print(f'  FAIL  {name}：插入锚点命中 {len(idx)} 行（应唯一），整体不写入')
            failed = True
            continue
        lines.insert(idx[0], text.rstrip('\n'))
        print(f'  OK    {name}')
    src = '\n'.join(lines)

    if failed:
        print('\n  有锚点未命中 —— 未写入任何改动')
        return 1

    # ---- 先校验、通过了才落盘 ----
    # 这些补丁要动的是「一行里塞了好几条语句 + 跨行注释」的超长运行时注入行，
    # 一次没改对就可能把 JS 拆坏。先写后校验的话，文件已经被破坏了（第一次就踩了），
    # 所以这里一律在内存里校验完，全绿才写文件。
    print()
    ok = True
    checks = [
        ("写死的 26/14/9/3 图表已清", "['周一',42,3]" not in src and "<b>26</b>" not in src),
        ("首页图表容器保留（供真实内容填充）", 'class="home-analytics"></div>' in src),
        ("weekBars 已改为优先后端统计", "s.dailyCounts && s.dailyCounts.length" in src),
        ("铃铛已接通知抽屉（且常驻：用 :hidden 而非 v-if）",
         "bell.setAttribute(':hidden','!unreadCount')" in src
         and "bell.setAttribute('v-if','unreadCount')" not in src),
        ("通知抽屉已注入", 'title="通知中心" size="520px"' in src),
        ("死函数 notImplemented 已删除", "const notImplemented" not in src),
        ("死接口 api.permissions 已删除", "function(){ return http('/api/auth/permissions'); }" not in src),
        ("死接口 api.attachments 已删除", "http('/api/attachments', {params:{documentId:documentId}})" not in src),
        ("业务类型筛选已改真实数据", 'v-model="businessTypeFilter" clearable placeholder="业务类型"><el-option v-for="x in bizTypeOptions"' in src),
        ("单据类型筛选已改真实数据", 'v-model="docTypeFilter" clearable placeholder="单据类型"><el-option v-for="x in docTypeOptions"' in src),
        ("用章类型已改字典驱动", 'v-for="t in sealTypeChoices"' in src),
        ("待办行业务类型已与筛选同口径（不再是原始码 SEAL/DAILY）",
         "bizCategory: BIZ_CATEGORY_TEXT[t.businessCategory] || ''" in src),
        ("筛选选项已暴露", "bizTypeOptions, docTypeOptions, sealTypeChoices," in src),
        # 防止整行替换误删同一行里的其他语句（第一次就是这么坏的）
        ("同行的「盖章→用印」语句仍在", "replaceAll('盖章','用印')" in src),
        ("同行的 peopleTable 语句仍在", "peopleTable?.querySelector" in src),
    ]
    for label, good in checks:
        print(f'  {"✓" if good else "✗"} {label}')
        ok = ok and good

    scripts = re.findall(r'<script>(.*?)</script>', src, re.S)
    inline = [s for s in scripts if len(s) > 5000]
    js_ok = True
    if inline:
        tmp = '/tmp/_oa_inline_check2.js'
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

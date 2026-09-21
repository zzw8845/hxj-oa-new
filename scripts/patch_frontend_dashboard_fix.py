#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
联调版前端补丁：把界面上的「假数字」接成后端真值，并修掉用印申请传不了附件的缺陷。

幂等：锚点还在就替换；目标串已在就 SKIP；都不在则整体不写入并报错。
改完自动同步到 jar 内的 static 副本（两份必须字节一致）。

本脚本处理 5 件事：
  1. api 增加 docStats / notifications / unreadCount / readAllNotify
  2. 工作看板的状态分布卡片：模板里写死的 [['审批中',27],['已通过',154],['已驳回',5]]
     → 取后端 /api/documents/stats（受行级数据范围约束）
     首页 donut 的 statusDist 同样改为优先后端统计（保留降级）
  3. 两个菜单角标 el-badge :value="8" / :value="5" → 真实未读通知数 / 真实待办数
  4. 资料清单「关联前置单据」由「下标 == 0」改为「按规则名判定」。
     起因：用印申请的资料清单是 ['用印文件附件']，按下标写会把首项渲染成关联按钮，
     导致用印文件附件没有上传控件、根本传不上去。
  5. 刷新时机：登录后与切到 home/board/approve 时一起刷新统计与未读数
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


# ---------------------------------------------------------------- 替换清单
# (说明, 旧串, 新串, 幂等标记)
EDITS = [
    # ---- 1. api ----
    ("api：单据统计",
     "  docs:         function(params){ return http('/api/documents', {params:params}); },\n",
     "  docs:         function(params){ return http('/api/documents', {params:params}); },\n"
     "  docStats:     function(){ return http('/api/documents/stats'); },\n"
     "  notifications:function(params){ return http('/api/notifications', {params:params||{}}); },\n"
     "  unreadCount:  function(){ return http('/api/notifications/unread-count'); },\n"
     "  readAllNotify:function(){ return http('/api/notifications/read-all', {method:'POST'}); },\n",
     "docStats:     function(){ return http('/api/documents/stats'); },"),

    # ---- 2. 状态分布 ----
    ("refs：docStats / unreadCount",
     "  const linkCandidates = ref([]), attachmentRules = ref([]), previewNodes = ref(null);\n",
     "  const linkCandidates = ref([]), attachmentRules = ref([]), previewNodes = ref(null);\n"
     "  /* 看板/首页统计与菜单角标一律取后端真值；接口不可用时为 null，由计算属性降级 */\n"
     "  const docStats = ref(null), unreadCount = ref(0);\n",
     "const docStats = ref(null), unreadCount = ref(0);"),

    ("statusDist：优先后端统计 + 新增 statusBoard",
     "  const statusDist = computed(function(){\n"
     "    const total = documents.value.length;\n"
     "    const done  = documents.value.filter(function(d){ return d.statusCode === 3 || d.statusCode === 6; }).length;\n"
     "    const doing = documents.value.filter(function(d){ return d.statusCode === 1 || d.statusCode === 2; }).length;\n"
     "    const back  = documents.value.filter(function(d){ return d.statusCode === 4; }).length;\n"
     "    return {total:total, done:done, doing:doing, back:back};\n"
     "  });\n",
     "  /* 状态分布：优先用后端统计 —— 它不受列表分页/条数上限影响，且已按行级数据范围收敛。\n"
     "     接口不可用时降级为「按已加载列表现算」：可能不全，但绝不会是写死的假数字。 */\n"
     "  const statusDist = computed(function(){\n"
     "    const s = docStats.value;\n"
     "    if (s) return {total:s.total, done:s.approved, doing:s.running, back:s.rejected};\n"
     "    const total = documents.value.length;\n"
     "    const done  = documents.value.filter(function(d){ return d.statusCode === 3 || d.statusCode === 6; }).length;\n"
     "    const doing = documents.value.filter(function(d){ return d.statusCode === 1 || d.statusCode === 2; }).length;\n"
     "    const back  = documents.value.filter(function(d){ return d.statusCode === 4; }).length;\n"
     "    return {total:total, done:done, doing:doing, back:back};\n"
     "  });\n"
     "  /* 工作看板的状态分布卡片（原模板里写死 27 / 154 / 5，与库里真实数据完全不沾边） */\n"
     "  const statusBoard = computed(function(){\n"
     "    const d = statusDist.value;\n"
     "    return [['审批中', d.doing], ['已通过', d.done], ['已驳回', d.back]];\n"
     "  });\n",
     "const statusBoard = computed(function(){"),

    # ---- 3. 模板：看板状态分布 ----
    ("模板：看板状态分布改绑 statusBoard",
     "<div class=\"status-grid\"><div v-for=\"s in [['审批中',27],['已通过',154],['已驳回',5]]\">",
     "<div class=\"status-grid\"><div v-for=\"s in statusBoard\">",
     "v-for=\"s in statusBoard\""),

    # ---- 4. 菜单角标 ----
    ("菜单角标：工作台 → 真实未读通知数",
     "<el-menu-item index=\"work\"><span class=\"mi\">▦</span>工作台<el-badge :value=\"8\" class=\"menu-badge\"></el-badge></el-menu-item>",
     "<el-menu-item index=\"work\"><span class=\"mi\">▦</span>工作台<el-badge :value=\"unreadCount\" :max=\"99\" :hidden=\"!unreadCount\" class=\"menu-badge\"></el-badge></el-menu-item>",
     ":value=\"unreadCount\" :max=\"99\""),

    ("菜单角标：审批中心 → 真实待办数",
     "<el-menu-item index=\"approve\"><span class=\"mi\">✓</span>审批中心<el-badge :value=\"5\" class=\"menu-badge\"></el-badge></el-menu-item>",
     "<el-menu-item index=\"approve\"><span class=\"mi\">✓</span>审批中心<el-badge :value=\"todos.length\" :max=\"99\" :hidden=\"!todos.length\" class=\"menu-badge\"></el-badge></el-menu-item>",
     ":value=\"todos.length\" :max=\"99\""),

    # ---- 5. 资料清单：按规则名判定，而不是下标 0 ----
    ("资料清单：关联说明按规则名判定",
     "<span v-if=\"i===0\">{{linkedDocument?'已关联：'",
     "<span v-if=\"isLinkRule(i)\">{{linkedDocument?'已关联：'",
     "<span v-if=\"isLinkRule(i)\">"),

    ("资料清单：关联按钮按规则名判定（修掉用印申请传不了附件）",
     "<el-button v-if=\"i===0\" type=\"primary\" plain @click=\"openLink\">",
     "<el-button v-if=\"isLinkRule(i)\" type=\"primary\" plain @click=\"openLink\">",
     "<el-button v-if=\"isLinkRule(i)\" type=\"primary\" plain @click=\"openLink\">"),

    # ---- 6. 详情抽屉：把关联单据显示出来 ----
    # 起因：openDetail 一直有 base.links = dto.links || []，但模板里从没渲染过 ——
    # 数据到了界面却不显示，用户「关联了前置单据」在详情里完全看不出来。
    ("详情抽屉：新增「关联单据」tab",
     "</el-tab-pane><el-tab-pane label=\"申请表信息\" name=\"form\">",
     "</el-tab-pane><el-tab-pane :label=\"'关联单据 (' + ((selected.links||[]).length) + ')'\" name=\"links\">"
     "<el-empty v-if=\"!selected.links||!selected.links.length\" description=\"未关联前置单据\" :image-size=\"70\"></el-empty>"
     "<div class=\"file-card\" v-for=\"l in selected.links\" :key=\"l.id\"><i>⇄</i><div>"
     "<b>{{l.linkedDocNo||('#'+l.linkedId)}} · {{l.linkedTitle||'关联单据已不存在'}}</b>"
     "<span>{{l.linkType==='prev_doc'?'前置单据':(l.linkType||'关联')}}</span></div></div></el-tab-pane>"
     "<el-tab-pane label=\"申请表信息\" name=\"form\">",
     "name=\"links\">"),

    # ---- 9. 运行时「模板补丁」：源码里的属性会被它覆盖，必须一起改 ----
    # 这是本轮最关键的发现：页面自己有一段挂载前运行的 IIFE，用 setAttribute('v-if', ...)
    # 改写 in-DOM 模板。Vue 在 mount 时才从 DOM 编译模板，所以**运行时的属性才是最终生效的**，
    # 只改源码属性等于白改。下面两处就是被它覆盖掉的地方。
    ("运行时补丁：资料清单关联项改为按规则名判定",
     "const linkButton=submitTemplate.querySelector('.attachment-item > el-button');"
     "linkButton?.setAttribute('v-if','i===0');"
     "const upload=submitTemplate.querySelector('.attachment-item > el-upload');"
     "upload?.removeAttribute('v-else');"
     "upload?.setAttribute('v-if',\"i>0 || submitForm.type.includes('盖章')\");"
     "const spans=submitTemplate.querySelectorAll('.attachment-item span');"
     "spans[0]?.setAttribute('v-if',\"i===0 && !submitForm.type.includes('盖章')\");"
     "spans[1]?.removeAttribute('v-else');"
     "spans[1]?.setAttribute('v-if',\"i>0 || submitForm.type.includes('盖章')\");",
     # 原逻辑用「下标 0」+「类型名含盖章」判关联项。但后端单据类型名是「用印申请」，
     # '用印申请'.includes('盖章') 永远为假 ⇒ 用印的首项既渲染关联按钮、又渲染上传控件，
     # 且『关联单据』按钮排在前面，用户根本不知道该点哪个。统一改成按资料清单规则名判定。
     "const linkButton=submitTemplate.querySelector('.attachment-item > el-button');"
     "linkButton?.setAttribute('v-if','isLinkRule(i)');"
     "const upload=submitTemplate.querySelector('.attachment-item > el-upload');"
     "upload?.removeAttribute('v-else');"
     "upload?.setAttribute('v-if','!isLinkRule(i)');"
     "const spans=submitTemplate.querySelectorAll('.attachment-item span');"
     "spans[0]?.setAttribute('v-if','isLinkRule(i)');"
     "spans[1]?.removeAttribute('v-else');"
     "spans[1]?.setAttribute('v-if','!isLinkRule(i)');",
     "linkButton?.setAttribute('v-if','isLinkRule(i)')"),

    ("运行时补丁：工作台角标接未读数（原来被强制 v-if=false）",
     "if (idx === 'approve'){ b.setAttribute(':value','todoCount'); b.setAttribute('v-if','todoCount'); }\n"
     "      else { b.setAttribute('v-if','false'); }",
     # 原逻辑只让「待我审批」显示数字，其余一律 v-if="false" —— 所以「工作台」角标
     # 永远不渲染，源码里写什么都没用。这里给 work 挂上真实未读数。
     "if (idx === 'approve'){ b.setAttribute(':value','todoCount'); b.setAttribute('v-if','todoCount'); }\n"
     "      else if (idx === 'work'){ b.setAttribute(':value','unreadCount'); b.setAttribute(':max','99');"
     " b.setAttribute('v-if','unreadCount'); }\n"
     "      else { b.setAttribute('v-if','false'); }",
     "else if (idx === 'work'){ b.setAttribute(':value','unreadCount')"),

    # ---- 10. 刷新函数 ----
    ("新增 refreshStats / refreshUnread / isLinkRule",
     "  const refreshTodos = async function(){\n"
     "    todos.value = (await api.todos()) || [];\n"
     "  };\n",
     "  const refreshTodos = async function(){\n"
     "    todos.value = (await api.todos()) || [];\n"
     "  };\n"
     "  const refreshStats = async function(){\n"
     "    try { docStats.value = await api.docStats(); } catch(e){ docStats.value = null; }\n"
     "  };\n"
     "  const refreshUnread = async function(){\n"
     "    try { unreadCount.value = (await api.unreadCount()) || 0; } catch(e){ unreadCount.value = 0; }\n"
     "  };\n"
     "  /* 资料清单里哪一项是「关联前置单据」必须按规则名判定，不能按下标 0：\n"
     "     用印申请的资料清单是 ['用印文件附件']，按下标写会把上传控件换成关联按钮，\n"
     "     结果「用印文件附件」根本没有地方上传。 */\n"
     "  const isLinkRule = function(i){\n"
     "    const rule = (attachmentRules.value || [])[i];\n"
     "    return typeof rule === 'string' && rule.indexOf('关联前置单据') >= 0;\n"
     "  };\n",
     "const isLinkRule = function(i){"),

    # ---- 7. 刷新时机 ----
    ("登录后一并刷新统计与未读数",
     "    await loadBase();\n    await Promise.all([refreshDocs(), refreshTodos()]);",
     "    await loadBase();\n    await Promise.all([refreshDocs(), refreshTodos(), refreshStats(), refreshUnread()]);",
     "refreshDocs(), refreshTodos(), refreshStats(), refreshUnread()"),

    ("切页时一并刷新统计与未读数",
     "    if (v === 'home' || v === 'board' || v === 'approve'){\n      refreshTodos().catch(function(){});\n    }",
     "    if (v === 'home' || v === 'board' || v === 'approve'){\n"
     "      refreshTodos().catch(function(){});\n"
     "      refreshStats().catch(function(){});\n"
     "      refreshUnread().catch(function(){});\n"
     "    }",
     "refreshStats().catch(function(){});"),

    # ---- 8. 暴露给模板 ----
    ("return：暴露 statusBoard / docStats / unreadCount",
     "completionRate, weekBars, weekTotal, statusDist,",
     "completionRate, weekBars, weekTotal, statusDist, statusBoard, docStats, unreadCount,",
     "statusDist, statusBoard, docStats, unreadCount,"),

    ("return：暴露 isLinkRule",
     "submitForm, attachmentRules, people,",
     "submitForm, attachmentRules, isLinkRule, people,",
     "attachmentRules, isLinkRule, people,"),
]


def apply():
    with open(HTML, encoding='utf-8') as f:
        src = f.read()
    original = src

    print('=' * 74)
    print('前端补丁：假数字接真值 + 修复用印申请附件上传')
    print('=' * 74)

    for name, old, new, mark in EDITS:
        if mark in src:
            print(f'  SKIP  {name}（已应用）')
            continue
        cnt = src.count(old)
        if cnt == 0:
            print(f'  FAIL  {name}：锚点未找到，整体不写入')
            return 1
        if cnt > 1:
            print(f'  FAIL  {name}：锚点命中 {cnt} 次（应唯一），整体不写入')
            return 1
        src = src.replace(old, new, 1)
        print(f'  OK    {name}')

    if src == original:
        print('\n  全部已应用，无需改动')
    else:
        with open(HTML, 'w', encoding='utf-8') as f:
            f.write(src)
        print(f'\n  已写入 {os.path.basename(HTML)}')

    # ---- 同步 static 副本 ----
    shutil.copyfile(HTML, STATIC)
    a, b = sha(HTML), sha(STATIC)
    print(f'  两份副本 sha256[:16]：{a} / {b}  →  {"一致" if a == b else "不一致!"}')
    if a != b:
        return 1

    # ---- 自证：不该再有写死的假数字 ----
    with open(HTML, encoding='utf-8') as f:
        out = f.read()
    checks = [
        ("写死的 ['审批中',27]", "[['审批中',27]" not in out),
        ('写死的角标 :value="8"', ':value="8" class="menu-badge"' not in out),
        ('写死的角标 :value="5"', ':value="5" class="menu-badge"' not in out),
        ('资料清单仍按下标判定', 'v-if="i===0" type="primary" plain @click="openLink"' not in out),
        ("statusBoard 已暴露", 'statusBoard, docStats, unreadCount,' in out),
        ("isLinkRule 已暴露", 'attachmentRules, isLinkRule, people,' in out),
    ]
    print()
    ok = True
    for label, good in checks:
        print(f'  {"✓" if good else "✗"} {label}')
        ok = ok and good

    # JS 语法自检：抽出内联 <script> 跑 node --check
    scripts = re.findall(r'<script>(.*?)</script>', out, re.S)
    inline = [s for s in scripts if len(s) > 5000]
    js_ok = True
    if inline:
        tmp = '/tmp/_oa_inline_check.js'
        with open(tmp, 'w', encoding='utf-8') as f:
            f.write(max(inline, key=len))
        r = subprocess.run(['/Users/zhouzewei/.workbuddy/binaries/node/versions/22.22.2-3/bin/node',
                            '--check', tmp], capture_output=True, text=True)
        js_ok = r.returncode == 0
        print(f'  {"✓" if js_ok else "✗"} 内联脚本语法检查' + ('' if js_ok else '\n' + r.stderr[:800]))
    return 0 if (ok and js_ok) else 1


if __name__ == '__main__':
    sys.exit(apply())

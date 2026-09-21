#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
补丁：让「审批凭证上传」真正渲染出来（修复本轮附件改造被历史补丁吃掉的问题）

背景
----
详情抽屉里有一段"模板补丁"IIFE，在 createApp 挂载之前直接改写 DOM 模板：

    /* ---- 详情抽屉：去掉无后端支撑的上传，按可执行动作渲染按钮 ---- */
    qAll('.approval-action').forEach(function(block){
      const up = block.querySelector('el-upload');
      if (up && up.parentNode) up.parentNode.removeChild(up);   // ← 就是这行
      ...
    });

当年这么写是对的：那时的 el-upload 只有 action="#" + :auto-upload="false"，
点了什么都不会发生，"删掉假控件"好过"留一个点不动的按钮"。

但本轮把附件后端（存储抽象 / 上传 / 下载 / 预览 / 删除）补齐之后，
审批凭证上传已经是真实能力，而且「办理节点凭证必填」的前端入口就是它。
这行 removeChild 会在挂载前把它从模板里摘掉 —— 结果是：
后端校验拦得住（接口层能验证），界面上却根本没有上传凭证的地方，
用户点了「通过」只会看到一句"需要上传办理凭证后才能通过"，无处可传。

修复
----
删掉 removeChild 那一行，控件保留；显隐交给外层已有的 v-if(canApprove...)。

幂等
----
命中锚点才写入；重复执行会提示"已是修复后状态"。
只改前端 HTML（根目录 + 后端 static 副本），不动任何 Java 代码。
"""
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE = os.path.join(ROOT, '海峡金OA审批系统-联调版.html')

OLD = """  /* ---- 详情抽屉：去掉无后端支撑的上传，按可执行动作渲染按钮 ---- */
  qAll('.approval-action').forEach(function(block){
    const up = block.querySelector('el-upload');
    if (up && up.parentNode) up.parentNode.removeChild(up);
    block.setAttribute('v-if','canApprove||canReject||canWithdraw||loading');
"""

NEW = """  /* ---- 详情抽屉：按可执行动作渲染按钮 ----
     这里曾经会顺手把 .approval-action 里的 el-upload 删掉（当年它只是 action="#" 的摆设）。
     附件后端补齐后，审批凭证上传是真实能力、也是「办理节点凭证必填」的唯一上传入口，
     挂载前删掉它等于把该规则在界面上废掉，所以不再移除；显隐仍由下面的 v-if(canApprove) 控制。 */
  qAll('.approval-action').forEach(function(block){
    block.setAttribute('v-if','canApprove||canReject||canWithdraw||loading');
"""

NEW_MARK = '附件后端补齐后，审批凭证上传是真实能力'

# ---- 第二处：把接口下发的 requireAttachment 真正接到视图上 --------------
# 后端 DocumentDetailVO 已经算好了 requireAttachment（按「单据 + 当前视角节点」查流程节点配置），
# 模板里的「（必填）」文案、提示行、以及 approve() 的本地预检都读 selected.requireAttachment，
# 但 openDetail() 把 docDetail 的结果往 selected 上搬的时候偏偏漏了这个字段：
#   base.viewingNodeKey = dto.viewingNodeKey;   ← 有
#   base.requireAttachment = dto.requireAttachment;  ← 没有
# 结果就是后端算得再对，界面上也永远不显示「（必填）」、预检也永远不触发，
# 用户只有在点「通过审批」被服务端拦下来时才知道要传凭证。
OLD2 = """      base.viewingNodeKey= dto.viewingNodeKey;"""

NEW2 = """      base.viewingNodeKey= dto.viewingNodeKey;
      /* 必须搬过来：模板的「（必填）」与 approve() 的本地预检都读它。
         漏了这一行，后端算得再对，界面上也不会提示（这一版就是漏了）。 */
      base.requireAttachment = !!dto.requireAttachment;"""

NEW2_MARK = 'base.requireAttachment = !!dto.requireAttachment;'


def apply(path):
    if not os.path.exists(path):
        return 'missing'
    s = open(path, encoding='utf-8').read()
    changed = False

    for mark, old, new, name, expect in (
        (NEW_MARK, OLD, NEW, '保留审批凭证上传控件', 1),
        (NEW2_MARK, OLD2, NEW2, '把 requireAttachment 接到视图上', 1),
    ):
        if mark in s:
            print('  · 已是修复后状态：%s' % name)
            continue
        n = s.count(old)
        if n != expect:
            return 'anchor-hit-%d:%s' % (n, name)
        s = s.replace(old, new, 1)
        changed = True
        print('  · 已修复：%s' % name)

    if not changed:
        return 'already'
    open(path, 'w', encoding='utf-8').write(s)
    return 'patched'


def sha(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()[:16]


def main():
    static = os.path.join(ROOT, 'oa-backend', 'oa-boot', 'src', 'main',
                          'resources', 'static', 'oa.html')

    # 根目录文件是唯一真源；先确保它与后端 static 副本一致再动手，
    # 否则可能出现"改了根目录、页面却还是旧的"这种最难查的偏差。
    if os.path.exists(static) and sha(PAGE) != sha(static):
        print('✗ 两份前端文件内容不一致，拒绝在不知哪份为准的情况下打补丁：')
        print('    根目录 : %s' % sha(PAGE))
        print('    static : %s' % sha(static))
        print('  请先确认以哪份为准并同步，再重新执行本脚本。')
        return 1

    r = apply(PAGE)
    print('根目录 HTML：%s' % r)
    if r == 'missing':
        print('✗ 找不到文件：%s' % PAGE)
        return 1
    if r.startswith('anchor-hit-'):
        print('✗ %s，拒绝写入。' % r)
        return 1

    # 同步到后端静态目录（页面由后端同源提供，不复制过去等于没改）
    open(static, 'w', encoding='utf-8').write(open(PAGE, encoding='utf-8').read())
    print('已同步到 static/oa.html（sha=%s）' % sha(static))
    print('提醒：页面是从 jar 内的 static 提供的，需要重新打包后端才能生效。')
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
补丁：修好三个"点了没反应/弹错单据类型"的发起入口

背景（都是靠浏览器实测发现的，不是靠读代码猜的）
------------------------------------------------
1) 首页「＋ 发起报销」「＋ 付款申请」
   按钮的 @click 用的是原型期的展示名 '报销申请单' / '付款申请单'，
   而 document_type 表里只有「日常付款申请 / 员工报销 / 用印申请」三种。
   resolveDocType() 做别名映射后仍然匹配不上 → 弹窗标题显示 '报销申请单'，
   同时弹黄条「后端未配置『报销申请单』这一单据类型」，docTypeId 为 null，
   点「提交审批」直接被这里拦住：if (!submitForm.typeId) return。

   原代码里其实有一段兜底（patchShell 里按按钮文案改写 @click），但它写的是：

       qAll('.hero .el-button').forEach(...)

   而挂载前自定义标签 <el-button> 还没有 class（同一函数开头 ① 就写明"要用标签选择器"），
   所以这个选择器永远匹配不到元素 —— 是一段**永远不执行的死代码**。
   这也解释了为什么它"看起来修过了"却完全没生效。

2) 「全部表单」页的「＋ 新建申请」
   同一个 '付款申请单' 问题，而且没有任何兜底。

3) 工作台「员工报销」区的快捷提交按钮
   区域标题/说明被改成「员工报销」，quick 列表取的是 REIMBURSE 类型，
   但按钮的 @click 还是 quickSubmit(q,'业务付款') → 别名落到「日常付款申请」。
   标题写员工报销、点下去建日常付款单，单据类型对不上。

修法
----
直接在静态标记里把三处键改成正确的别名键（'员工报销' / '日常付款'），
并删掉那段永远不执行的死代码、留注释说明为什么不能那样写。
命中数不符就整体拒绝写入，避免静默改错。

幂等：全部锚点都已消失则提示"已是修复后状态"。
"""
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE = os.path.join(ROOT, '海峡金OA审批系统-联调版.html')

# (说明, 原文, 替换文, 期望命中次数)
EDITS = [
    (
        "首页 / 全部表单：'报销申请单' 改成真实别名键 '员工报销'",
        "openSubmit('报销申请单')",
        "openSubmit('员工报销')",
        2,
    ),
    (
        "首页 / 全部表单：'付款申请单' 改成真实别名键 '日常付款'",
        "openSubmit('付款申请单')",
        "openSubmit('日常付款')",
        3,
    ),
    (
        "工作台「员工报销」区快捷按钮：'业务付款' 会落到日常付款单，改成 '员工报销'",
        "quickSubmit(q,'业务付款')",
        "quickSubmit(q,'员工报销')",
        1,
    ),
    (
        "删掉那段永远匹配不到元素的兜底（类选择器选未挂载的自定义标签）",
        """  qAll('.hero .el-button').forEach(function(btn){
    const t = btn.textContent || '';
    if (t.indexOf('报销') >= 0) btn.setAttribute('@click',"openSubmit('员工报销')");
    else if (t.indexOf('付款') >= 0) btn.setAttribute('@click',"openSubmit('日常付款')");
  });""",
        """  /* 首页两个发起按钮的 @click 已在静态标记里改成正确的别名键（'员工报销' / '日常付款'）。
     这里原本有一段"按按钮文案改写 @click"的兜底，但它用的是 .hero .el-button ——
     挂载前自定义标签还没有 class（见本函数开头 ①），选择器恒为空，
     属于永远不会执行的死代码，反而让人误以为首页按钮已经修过了。已删除。 */""",
        1,
    ),
]


def main():
    static = os.path.join(ROOT, 'oa-backend', 'oa-boot', 'src', 'main',
                          'resources', 'static', 'oa.html')

    if not os.path.exists(PAGE):
        print('✗ 找不到页面文件：%s' % PAGE)
        return 1

    def sha(p):
        return hashlib.sha256(open(p, 'rb').read()).hexdigest()

    # 两份必须一致：不一致时不知道该以哪份为准，硬改会造成"改了本地、页面还是旧的"
    if os.path.exists(static) and sha(PAGE) != sha(static):
        print('✗ 根目录 HTML 与 static 副本不一致，先同步再打补丁：')
        print('    根目录 : %s' % sha(PAGE)[:16])
        print('    static : %s' % sha(static)[:16])
        return 1

    s = open(PAGE, encoding='utf-8').read()

    pending = []
    for desc, old, new, expect in EDITS:
        n = s.count(old)
        if n == 0:
            print('· 已处理：%s' % desc)
            continue
        if n != expect:
            print('✗ 锚点命中 %d 次（期望 %d 次），拒绝写入：%s' % (n, expect, desc))
            return 1
        pending.append((desc, old, new, expect))

    if not pending:
        print('已是修复后状态，无需改动。')
        return 0

    for desc, old, new, expect in pending:
        s = s.replace(old, new)
        print('· 已修复：%s' % desc)

    open(PAGE, 'w', encoding='utf-8').write(s)
    open(static, 'w', encoding='utf-8').write(s)
    print('已同步 static/oa.html（sha=%s）' % sha(static)[:16])
    print('提醒：页面从 jar 内的 static 提供，需要重新打包后端才能生效。')
    return 0


if __name__ == '__main__':
    sys.exit(main())

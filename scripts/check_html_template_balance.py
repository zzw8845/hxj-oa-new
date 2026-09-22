#!/usr/bin/env python3
"""检查 oa.html 的 <template> 开/闭标签配平。

背景（2026-09-22 实际事故）：某次前端补丁给「表单模板」页的表格列少写了一个
</template>，后果远比"局部渲染错误"严重 —— HTML 解析器把其后**所有内容**
（包括 4 个 <script> 与 </body>）全部吞进 template.content，
页面表现为：Vue 未挂载、模板原样显示 {{...}}、DOM 里查不到任何 script、
且无任何 console/pageerror 报错 —— 是最难排查的一类静默故障。

规则：<script> 之前区域里 <template> 与 </template> 必须配平。
此检查对 Vue 单文件页面是廉价的保命闸门，run_all_e2e.sh 的前置自检会调用它。
"""
import re
import sys
import pathlib

TARGET = pathlib.Path(__file__).resolve().parent.parent / "海峡金OA审批系统-联调版.html"
if not TARGET.exists():
    # 兼容从 oa-backend/scripts 运行的情况
    alt = pathlib.Path(__file__).resolve().parent.parent.parent / "海峡金OA审批系统-联调版.html"
    TARGET = alt


def check(path: pathlib.Path) -> list:
    problems = []
    s = path.read_text(encoding="utf-8")
    # 只查脚本区之前（模板区）；脚本里出现的字符串不受 HTML 解析影响
    limit = s.rfind("<script")
    head = s[:limit]
    opens = len(re.findall(r"<template[\s>]", head))
    closes = head.count("</template>")
    if opens != closes:
        problems.append(
            f"<template> 配平失败：开 {opens} vs 闭 {closes}（差 {opens - closes}）\n"
            "  ⚠ 未闭合的 <template> 会把其后所有内容（含 <script>）吞进 template.content，\n"
            "     表现为页面白渲染、无任何报错。逐块核对本批改动里每个 <template> 都有收尾。"
        )
    # 附带检查：引号未闭合的标签（同类静默事故的另一来源）
    i = 0
    n = len(head)
    while i < n:
        if head[i] == "<" and i + 1 < n and (head[i + 1].isalpha() or head[i + 1] == "/"):
            j = i + 1
            q = 0
            while j < n:
                ch = head[j]
                if ch == '"':
                    q += 1
                elif ch == ">" and q % 2 == 0:
                    break
                j += 1
            else:
                problems.append(f"标签未在文件内闭合 @ {i}: {head[i:i+120]!r}")
                break
            if q % 2 != 0:
                problems.append(f"标签内引号不闭合 @ {i}: {head[i:i+160]!r}")
                if len(problems) > 5:
                    break
            i = j + 1
        else:
            i += 1
    return problems


def main():
    files = [a for a in sys.argv[1:]] or [str(TARGET)]
    failed = False
    for f in files:
        p = pathlib.Path(f)
        if not p.exists():
            print(f"  [模板配平] ⚠ 找不到 {f}，跳过")
            continue
        problems = check(p)
        if problems:
            failed = True
            print(f"  [模板配平] ✗ {p.name}")
            for msg in problems:
                print("     " + msg)
        else:
            print(f"  [模板配平] ✓ {p.name}（<template> 配平、无未闭合引号）")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

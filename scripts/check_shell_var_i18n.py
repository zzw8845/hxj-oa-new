#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Shell 脚本的两项静态自检（挂在 E2E 统一入口的前置自检里）。

【为什么要专门做这个脚本】
项目里同一个坑**反复踩了 5 次**：`$VAR` 后面紧跟中文字符时，
bash 会把那些多字节字节**当成变量名的一部分**，运行时报
`VAR<乱码>: unbound variable` 并直接中止脚本。

  真实案例：`echo "共 $COUNT 张（…）"` → 报 `COUNT<乱码>: unbound variable`
  另一例：`echo "使用 node：$NODE（$($NODE --version)）"` → 报 `NODE<乱码>: unbound variable`

危险之处在于它们**全都落在罕见分支或收尾行**上：平时跑不到，偏偏在出错、
正需要它报错的时候把脚本带崩。而且 `bash -n` 查不出来（这是运行时错误）。

所以：**必须写成 `${VAR}`**；这个脚本负责在开跑前扫一遍。

它同时跑 `bash -n`：语法错误虽然 bash 自己会报，但让它在开头就报出来，
比在跑了 3 分钟用例之后才炸要好。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP_PARTS = {'target', 'node_modules', '.git'}

# `$VAR` 后面紧跟非 ASCII 字符（中文、全角括号等）
BAD = re.compile(rb'\$([A-Za-z_][A-Za-z0-9_]*)(?=[^\x00-\x7f])')


def script_files():
    seen = set()
    for pattern in ('*.sh', '*.command'):
        for f in list(ROOT.glob(pattern)) + list((ROOT / 'oa-backend').rglob(pattern)):
            if any(p in SKIP_PARTS for p in f.parts):
                continue
            if f not in seen and f.is_file():
                seen.add(f)
                yield f


def main():
    files = sorted(script_files())
    bad = []
    syntax = []

    for f in files:
        for i, line in enumerate(f.read_bytes().split(b'\n'), 1):
            m = BAD.search(line)
            if m:
                bad.append((f, i, m.group(1).decode()))
        if f.suffix == '.sh':
            r = subprocess.run(['bash', '-n', str(f)], capture_output=True, text=True)
            if r.returncode != 0:
                syntax.append((f, (r.stderr or '').strip().split('\n')[0]))

    print('  [脚本自检] 扫描 %d 个 shell 脚本' % len(files))

    if bad:
        print()
        print('✗ 有 %d 处 `$VAR` 紧跟非 ASCII 字符 —— 运行时会报 `<乱码>: unbound variable`：' % len(bad))
        for f, i, name in bad:
            print('    %s:%d  $%s' % (f.relative_to(ROOT), i, name))
        print()
        print('  修法：写成 ${%s}（加花括号）。' % bad[0][2])
        print('  这是运行时错误，`bash -n` 查不出来；且往往藏在罕见分支/收尾行上，')
        print('  平时跑不到，偏偏在出错需要它报错的时候把脚本带崩。')
        return 1

    if syntax:
        print()
        print('✗ 有 %d 个脚本语法错误：' % len(syntax))
        for f, msg in syntax:
            print('    %s  %s' % (f.relative_to(ROOT), msg))
        return 1

    print('  [脚本自检] ✓ 无「$VAR 紧跟中文」写法，语法全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())

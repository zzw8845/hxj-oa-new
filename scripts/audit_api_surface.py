# -*- coding: utf-8 -*-
"""
接口面一致性审计：前端调用的路径 vs 后端实际提供的路径。

为什么需要它：
  "某个按钮点了没反应/报 404"这类问题，靠人点页面碰运气发现。
  而根因往往就是前端写了一个后端根本没有的路径（或改名后只改了一边）。
  这个脚本把两边都抽出来对一遍，把这类问题变成一次可重复运行的检查。

局限（必须知道，否则会被"全绿"误导）：
  · 只做**路径+方法**的文本比对，不校验参数与语义；
  · 路径里的动态段统一归一成 {}，所以 /a/{}/b 与 /a/1/b 视为同一形状；
  · 后端路径若来自多级拼接或正则，可能抽不全；
  · 前端路径由模板串拼接的（'/a/' + id + '/b'）会被归一，但变量拼在段中间的写法会漏。
  所以它是"发现问题的工具"，不是"没问题"的证明。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HTML = ROOT / '海峡金OA审批系统-联调版.html'
JAVA_ROOTS = [ROOT / 'oa-backend']

METHODS = ('GET', 'POST', 'PUT', 'DELETE', 'PATCH')


def norm(path: str) -> str:
    """把路径归一成可比较的形状：动态段 → {}，去掉重复斜杠与尾斜杠。"""
    p = path.strip()
    p = re.sub(r"'\s*\+\s*[^+']+\+\s*'", '{}', p)   # '/a/' + id + '/b' → '/a/{}/b'
    p = re.sub(r'\$\{[^}]+\}', '{}', p)             # 模板串里的 ${x}
    p = re.sub(r'\{[^/}]+\}', '{}', p)              # {id} / {dictType}
    p = re.sub(r'/+', '/', p)
    if len(p) > 1 and p.endswith('/'):
        p = p[:-1]
    return p


def first_arg(args: str) -> str:
    """取出调用的第一个参数表达式（按顶层逗号切，忽略引号与括号内部的逗号）。"""
    depth, out, quote = 0, [], None
    for ch in args:
        if quote:
            out.append(ch)
            if ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
            out.append(ch)
        elif ch in '([{':
            depth += 1
            out.append(ch)
        elif ch in ')]}':
            depth -= 1
            out.append(ch)
        elif ch == ',' and depth == 0:
            break
        else:
            out.append(ch)
    return ''.join(out)


def extract_path(args: str):
    """从一次 http(...) 调用里抽出路径形状。

    走过的三个假报坑（都记在这里，免得下次又以为是真故障）：
      ① 把 '/api/users/' + id 归一成 '/api/users' —— 动态段整段丢了，
         于是报出"前端在调 /api/users 后端没有"这种**假故障**；
      ② 只处理"变量夹在两个引号段中间"（'/a/' + id + '/b'），
         漏了"变量在末尾"（'/api/users/' + id）。
      ③ **查询串挂在末尾表达式里**时（'/api/x' + (cond ? '?a=' + a : '')），
         末尾那个表达式属于查询串、不是路径段，不能补成 {}；
         否则会造出 /api/x/{} 这种不存在的形状 —— 又一条假故障。

    做法：按顶层逗号切出第一个参数，再按引号切成 字面量/表达式 交替序列。
    字面量原样拼，每个表达式补一个 {}。一旦遇到含 ? 或 & 的片段就**截断**
    （查询串不是路径的一部分），并丢掉紧邻的那个表达式。
    """
    expr = first_arg(args)
    if '/api' not in expr:
        return None
    segs = []      # ('lit', text) / ('expr',)
    cut = False
    for tok in re.findall(r"'[^']*'|\"[^\"]*\"|[^'\"]+", expr):
        if tok[0] in "'\"":
            lit = tok[1:-1]
            if '?' in lit or '&' in lit:
                cut = True
                break
            if not segs and not lit.startswith('/api'):
                continue
            segs.append(('lit', lit))
        else:
            if '?' in tok or '&' in tok:
                cut = True
                break
            segs.append(('expr',))
    if cut and segs and segs[-1][0] == 'expr':
        segs.pop()
    path = ''
    for seg in segs:
        if seg[0] == 'lit':
            path += seg[1]
        elif path:
            path += '{}'
    return path.split('?')[0] or None


def balanced_args(src: str, open_idx: int):
    """返回 open_idx 处 '(' 所配对 ')' 之内的文本（不含最外层括号）。不配平则 None。

    为什么不能直接用 `http\\((.*?)\\)`：参数里的括号（三元表达式、嵌套调用）会让
    非贪婪正则**在第一个 ')' 就收口**，把 `{method:'POST'}` 这类选项截在参数之外 ⇒
    方法被默认成 GET ⇒ 报出"前端在调 GET、后端只有 POST"这种**假故障**。
    """
    depth, quote, i = 0, None, open_idx
    start = open_idx + 1
    while i < len(src):
        ch = src[i]
        if quote:
            if ch == quote and src[i - 1] != '\\':
                quote = None
        elif ch in "'\"":
            quote = ch
        elif ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return src[start:i]
        i += 1
    return None


def frontend_calls():
    """从前端抽出所有 /api 调用（api 对象里的 + 直接 http()/httpBlob() 的）。"""
    src = HTML.read_text(encoding='utf-8')
    out = set()
    for m in re.finditer(r"http(?:Blob)?\(", src):
        args = balanced_args(src, m.end() - 1)
        if args is None:
            continue
        path = extract_path(args)
        if not path:
            continue
        mm = re.search(r"method\s*:\s*'([A-Z]+)'", args)
        out.add((mm.group(1) if mm else 'GET', norm(path)))
    return out


def backend_routes():
    """从 Java controller 抽出 (方法, 路径)。"""
    out = set()
    for root in JAVA_ROOTS:
        for f in root.rglob('*Controller.java'):
            if 'target' in f.parts:
                continue
            src = f.read_text(encoding='utf-8')
            base_m = re.search(r'@RequestMapping\(\s*"([^"]*)"', src)
            base = base_m.group(1) if base_m else ''
            for m in re.finditer(
                    r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?', src):
                verb = m.group(1).upper()
                sub = m.group(2) or ''
                out.add((verb, norm(base + sub), f.name))
    return out


def main():
    fe = frontend_calls()
    be = backend_routes()
    be_shapes = {(v, p) for v, p, _ in be}

    print('=' * 74)
    print('接口面审计：前端调用 %d 个，后端提供 %d 个' % (len(fe), len(be_shapes)))
    print('=' * 74)

    missing = sorted((v, p) for v, p in fe if (v, p) not in be_shapes)
    print()
    print('一、前端在调、后端没有（点了必然 404，优先修）')
    if not missing:
        print('    （无）')
    for v, p in missing:
        print('    ✗ %-6s %s' % (v, p))

    used = set(fe)
    unused = sorted([(v, p, fn) for v, p, fn in be if (v, p) not in used])
    print()
    print('二、后端提供、前端没在调（可能是"没人用的功能"，也可能是给别的客户端/脚本用的）')
    if not unused:
        print('    （无）')
    for v, p, fn in unused:
        print('    · %-6s %-46s %s' % (v, p, fn))

    print()
    print('=' * 74)
    if missing:
        print('结论：有 %d 个前端路径后端不存在 —— 这些按钮/页面必然是坏的。' % len(missing))
        return 1
    print('结论：前端调用的路径后端全部存在。（注意本脚本只比路径形状，不代表语义正确）')
    return 0


if __name__ == '__main__':
    sys.exit(main())

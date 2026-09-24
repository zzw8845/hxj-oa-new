#!/usr/bin/env python3
"""
Apifox Helper（IDEA 插件）解析效果模拟器。

目的：不改一行代码、不安装 IDEA，就能知道**公司前端在 Apifox 里看到的是什么**。

Apifox Helper 的解析依据（据官方帮助文档）：
  1. 控制器类 javadoc        -> 接口分组/模块说明
  2. 方法 javadoc 首行        -> 接口名称（summary）
  3. 方法 javadoc 其余部分    -> 接口描述（description）
  4. @param <名字> 说明       -> 参数说明（按**形参名**匹配；名字对不上 = 说明丢失且不报错）
  5. 数据模型的**类 javadoc** -> 模型说明；**字段 javadoc** -> 字段说明
  6. Bean Validation 注解     -> 必填/约束
  7. @Deprecated              -> 废弃标记

本脚本按上述规则做**静态模拟**，输出：
  - 覆盖率（接口说明 / 参数说明 / 模型字段说明）
  - **风险清单**：Apifox 会产出空白或误导的位置
  - 一份 JSON 与一份 HTML 预览

用法：python3 scripts/apifox_doc_emulate.py
"""
from __future__ import annotations

import json
import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "oa-backend")
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "接口契约")

MAPPING_RE = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\s*(\(|$)")
CLASS_MAPPING_RE = re.compile(r"@RequestMapping\s*\(([^)]*)\)")
CLASS_DECL_RE = re.compile(
    r"\b(?:(?:public|final|abstract|static)\s+)*(class|interface|enum|record)\s+(\w+)"
)
FIELD_RE = re.compile(
    r"^\s*(?:(?:private|protected|public)\s+)?(?:static\s+)?(?:final\s+)?"
    r"([A-Za-z_][\w\.]*(?:\s*<[^;=]*?>)?(?:\[\])?)\s+(\w+)\s*(?:=[^;]*)?;\s*$"
)
# 解析不出结构、只能人工看的类型
OPAQUE_TYPES = {"Object", "byte", "Map", "MultipartFile", "HttpServletRequest",
                "HttpServletResponse", "InputStream", "Resource", "void", "Void"}
# JDK / 第三方类型，不是"未索引"，不报风险
JDK_TYPES = {
    "String", "Long", "Integer", "Boolean", "Double", "Float", "BigDecimal",
    "LocalDate", "LocalDateTime", "Date", "Time", "byte", "int", "long",
    "boolean", "double", "float", "short", "char", "Number", "Object",
    "Map", "HashMap", "List", "Set", "Collection", "Iterable", "T", "E", "K", "V",
    "ResponseEntity", "MultipartFile", "Resource", "Void", "void", "InputStream",
}


# ---------------------------------------------------------------- 预处理

def mask(src: str) -> str:
    """把注释与字符串字面量替换成空格（保留换行与长度），用于花括号/圆括号配平。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' :
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            for k in range(i, min(j + 1, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            for k in range(i, min(j + 1, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
        elif c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def depth_at_line_start(masked: str) -> list[int]:
    depths, d = [], 0
    for line in masked.split("\n"):
        depths.append(d)
        d += line.count("{") - line.count("}")
    return depths


def javadoc_above(lines: list[str], idx: int) -> str:
    """取 idx 行上方紧邻的 javadoc 文本（跳过注解行/空行）。idx 之前的注释块。"""
    j = idx - 1
    while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip() == ""):
        j -= 1
    if j < 0:
        return ""
    if not lines[j].strip().endswith("*/"):
        # 也接受单行 // 注释
        if lines[j].strip().startswith("//"):
            buf = []
            while j >= 0 and lines[j].strip().startswith("//"):
                buf.insert(0, lines[j].strip()[2:].strip())
                j -= 1
            return "\n".join(buf)
        return ""
    end = j
    k = j
    while k >= 0 and "/**" not in lines[k] and "/*" not in lines[k]:
        k -= 1
    if k < 0:
        return ""
    return "\n".join(lines[k:end + 1])


def clean_doc(raw: str) -> str:
    """把 javadoc 块清洗成纯文本。"""
    if not raw:
        return ""
    t = raw
    t = re.sub(r"^\s*/\*\*?", "", t)
    t = re.sub(r"\*/\s*$", "", t)
    t = re.sub(r"^\s*\*<?\s?", "", t, flags=re.M)
    t = re.sub(r"<br\s*/?>", "\n", t, flags=re.I)
    t = re.sub(r"</p>", "\n", t, flags=re.I)
    t = re.sub(r"<li>", "\n- ", t, flags=re.I)
    t = re.sub(r"<[^>]+>", "", t)
    t = t.replace("{@code ", "`").replace("{@link ", "`").replace("}", "`" if "`" in t else "}")
    lines = [ln.rstrip() for ln in t.split("\n")]
    # 去掉行首的孤立 ` 造成的噪声
    out = []
    for ln in lines:
        ln = re.sub(r"^\s*`+", "", ln)
        out.append(ln)
    return "\n".join(out).strip()


def doc_parts(raw: str):
    """拆出 (summary, description, {param: desc})。"""
    text = clean_doc(raw)
    if not text:
        return "", "", {}
    params = {}
    body_lines, cur, buf = [], None, []
    for ln in text.split("\n"):
        m = re.match(r"\s*@param\s+(\S+)\s*(.*)$", ln)
        if m:
            if cur:
                params[cur] = " ".join(buf).strip()
            cur, buf = m.group(1), [m.group(2)]
            continue
        if re.match(r"\s*@\w+", ln):
            if cur:
                params[cur] = " ".join(buf).strip()
                cur, buf = None, []
            continue
        if cur is not None:
            buf.append(ln.strip())
        else:
            body_lines.append(ln)
    if cur:
        params[cur] = " ".join(buf).strip()
    body = "\n".join(body_lines).strip()
    lines = [l for l in body.split("\n") if l.strip()]
    summary = lines[0].strip() if lines else ""
    desc = "\n".join(lines[1:]).strip()
    return summary, desc, params


# ---------------------------------------------------------------- 类索引

class Klass:
    def __init__(self, fqn, simple, kind, javadoc, fields, sup=""):
        self.fqn, self.simple, self.kind = fqn, simple, kind
        self.javadoc = javadoc
        self.fields = fields  # list[dict(name,type,comment)]
        self.sup = sup        # 直接父类 simple name（Apifox 会展开继承字段）


def index_classes() -> dict[str, Klass]:
    index: dict[str, Klass] = {}
    for dirpath, _, names in os.walk(ROOT):
        if "/target/" in dirpath + "/":
            continue
        for fn in names:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            src = open(path, encoding="utf-8").read()
            m = mask(src)
            lines = src.split("\n")
            mlines = m.split("\n")
            depths = depth_at_line_start(m)
            pkg = re.search(r"^\s*package\s+([\w\.]+)\s*;", src, re.M)
            pkg = pkg.group(1) if pkg else ""
            # 逐个类声明定位 body 范围
            for cm in CLASS_DECL_RE.finditer(m):
                kind, simple = cm.group(1), cm.group(2)
                decl_line = m[:cm.start()].count("\n")
                # 找 body 起点
                brace = m.find("{", cm.end())
                if brace < 0:
                    continue
                body_start_line = m[:brace].count("\n")
                # 配平
                d, i, n = 0, brace, len(m)
                while i < n:
                    if m[i] == "{":
                        d += 1
                    elif m[i] == "}":
                        d -= 1
                        if d == 0:
                            break
                    i += 1
                body_end_line = m[:i].count("\n")
                base_depth = depths[body_start_line] + 1
                fields = []
                for ln_no in range(body_start_line + 1, min(body_end_line, len(lines) - 1) + 1):
                    if depths[ln_no] != base_depth:
                        continue
                    code = mlines[ln_no]
                    if "(" in code or "{" in code or "}" in code:
                        continue
                    fm = FIELD_RE.match(mlines[ln_no])
                    if not fm:
                        continue
                    ftype = re.sub(r"\s+", "", fm.group(1))
                    fname = fm.group(2)
                    raw = javadoc_above(lines, ln_no)
                    fields.append({"name": fname, "type": ftype,
                                   "comment": clean_doc(raw)})
                fqn = f"{pkg}.{simple}" if pkg else simple
                # 父类名字（继承字段会被 Apifox 展开）
                sup = ""
                tail = m[cm.end():brace]
                sm = re.search(r"\bextends\s+(\w+)", tail)
                if sm:
                    sup = sm.group(1)
                index[fqn] = Klass(fqn, simple, kind,
                                   clean_doc(javadoc_above(lines, decl_line)), fields, sup)
                index.setdefault(simple, index[fqn])
    return index


# ---------------------------------------------------------------- 控制器解析

def parse_params(sig: str) -> list[dict]:
    """sig 形如 '(@Valid @RequestBody X req, @PathVariable Long id)'。"""
    inner = sig[sig.find("(") + 1: sig.rfind(")")]
    mm = mask(inner)
    parts, depth, cur = [], 0, 0
    for i, ch in enumerate(mm):
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(inner[cur:i])
            cur = i + 1
    if inner[cur:].strip():
        parts.append(inner[cur:])
    out = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        kind = "pojo"
        if "@PathVariable" in p:
            kind = "path"
        elif "@RequestParam" in p:
            kind = "query"
        elif "@RequestBody" in p:
            kind = "body"
        elif "@RequestHeader" in p:
            kind = "header"
        elif re.search(r"\b(HttpServletRequest|HttpServletResponse|Principal)\b", p):
            kind = "skip"
        # 去掉注解
        bare = re.sub(r"@\w+(?:\([^)]*\))?", " ", p)
        bare = re.sub(r"\s+", " ", bare).strip()
        toks = bare.split(" ")
        toks = [t for t in toks if t and t not in ("final",)]
        if len(toks) < 2:
            continue
        ptype, pname = toks[-2], toks[-1]
        required = None
        rm = re.search(r"@RequestParam\s*\(([^)]*)\)", p)
        if rm:
            if "required = false" in rm.group(1).replace(" ", "") or \
               "required=false" in rm.group(1).replace(" ", ""):
                required = False
            else:
                required = True
        out.append({"name": pname, "type": ptype, "kind": kind,
                    "required": required, "raw": p.strip()})
    return out


def find_method_sig(text: str, start: int):
    """从 start 起找第一个 'public ...' 的方法签名，返回 (sig, 结束偏移)。"""
    m = re.compile(r"\bpublic\s+").search(text, start)
    if not m:
        return None, None
    open_paren = text.find("(", m.end())
    if open_paren < 0:
        return None, None
    mm = mask(text)
    depth, i = 0, open_paren
    while i < len(mm):
        if mm[i] == "(":
            depth += 1
        elif mm[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    return text[m.start(): i + 1], i + 1


def split_types(type_expr: str) -> list[str]:
    """从 R<PageResult<Document>> 里抽出所有类型名。"""
    return [t for t in re.findall(r"[A-Za-z_][\w\.]*", type_expr)
            if t not in ("R", "List", "Set", "Collection", "Iterable")]


def parse_controllers(index: dict) -> list[dict]:
    ops = []
    for dirpath, _, names in os.walk(ROOT):
        if "/target/" in dirpath + "/" or "/controller/" not in dirpath + "/":
            continue
        for fn in sorted(names):
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            src = open(path, encoding="utf-8").read()
            lines = src.split("\n")
            mm = mask(src)
            # 每行在 src 中的起始偏移（重复注解行必须靠偏移定位，不能用 find）
            offsets, acc = [], 0
            for ln in lines:
                offsets.append(acc)
                acc += len(ln) + 1
            class_doc = ""
            cm = CLASS_MAPPING_RE.search(mm)
            base = ""
            if cm:
                # ⚠ 字符串字面量在 mask 后已被抹掉，路径值必须回原文本取
                base = src[cm.start(1):cm.end(1)].strip().strip('"')
            # 类 javadoc：@RequestMapping 行上方
            if cm:
                cl = mm[:cm.start()].count("\n")
                mlines = mm.split("\n")
                for k in range(cl, -1, -1):
                    if k < len(mlines) and re.search(r"\bclass\s+\w+", mlines[k]):
                        class_doc = clean_doc(javadoc_above(lines, k))
                        break
            # 逐方法
            for i, ln in enumerate(lines):
                if not MAPPING_RE.search(ln):
                    continue
                verb = MAPPING_RE.search(ln).group(1).upper()
                sub = ""
                pm = re.search(r'\(\s*"([^"]*)"', ln)
                if pm:
                    sub = pm.group(1)
                elif "(" in ln and ")" in ln and "value" in ln:
                    vm = re.search(r'value\s*=\s*"([^"]*)"', ln)
                    sub = vm.group(1) if vm else ""
                # javadoc
                summary, desc, params = doc_parts(javadoc_above(lines, i))
                # 注解（@RequirePerm / @Audit）
                annos, j = [], i + 1
                while j < len(lines) and (lines[j].strip().startswith("@") or lines[j].strip() == ""):
                    annos.append(lines[j].strip())
                    j += 1
                sig, _ = find_method_sig(src, offsets[i] + len(ln))
                if sig is None:
                    continue
                names_in_sig = re.search(r"\b(\w+)\s*\(", sig)
                mname = names_in_sig.group(1) if names_in_sig else "?"
                ret = sig[len("public "): sig.find(mname)].strip()
                ps = parse_params(sig[sig.find("("):])
                require = ""
                for a in annos:
                    rm = re.search(r'@RequirePerm\s*\(\s*([^)]*)\)', a)
                    if rm:
                        require = rm.group(1)
                audit = ""
                for a in annos:
                    am = re.search(r'@Audit\s*\(([^)]*)\)', a)
                    if am:
                        audit = am.group(1)
                raw_annos = "\n".join(annos)
                ops.append({
                    "controller": fn[:-5],
                    "group_doc": class_doc,
                    "method": mname,
                    "verb": verb,
                    "path": (base + sub) if sub else base,
                    "path_tmpl": (base + sub) if sub else base,
                    "summary": summary,
                    "desc": desc,
                    "params": ps,
                    "javadoc_params": params,
                    "return": ret,
                    "require_perm": require,
                    "audit": audit,
                    "deprecated": "@Deprecated" in raw_annos,
                    "file": os.path.relpath(path, os.path.dirname(ROOT)),
                    "line": i + 1,
                })
    return ops


# ---------------------------------------------------------------- 模型解析

def flatten_fields(k, index: dict, _depth=0) -> list[dict]:
    """展开继承链的字段（父类字段在前），并去重同名。"""
    if _depth > 6:
        return list(k.fields)
    out = []
    if k.sup:
        p = index.get(k.sup)
        if p is not None:
            out += flatten_fields(p, index, _depth + 1)
    names = {f["name"] for f in out}
    out += [f for f in k.fields if f["name"] not in names]
    return out


def build_models(ops: list[dict], index: dict) -> OrderedDict:
    models: OrderedDict[str, dict] = OrderedDict()
    queue: list[str] = []
    for op in ops:
        for p in op["params"]:
            if p["kind"] in ("body", "pojo"):
                queue += split_types(p["type"])
        queue += split_types(op["return"])
    seen = set()
    while queue:
        t = queue.pop(0)
        if t in seen:
            continue
        seen.add(t)
        k = index.get(t)
        if k is None and "." in t:
            # LoginResp.MenuItem / FormTemplateSaveReq.FieldPerm 这类嵌套类引用
            k = index.get(t.split(".")[-1])
        if k is None:
            models.setdefault(t, {"fqn": None, "kind": "?", "javadoc": "",
                                  "fields": [], "resolved": False})
            continue
        fields = flatten_fields(k, index)
        models[t] = {"fqn": k.fqn, "kind": k.kind, "javadoc": k.javadoc,
                     "fields": fields, "resolved": True,
                     "super": k.sup or None}
        for f in fields:
            queue += split_types(f["type"])
    return models


# ---------------------------------------------------------------- 报告

# ---------------------------------------------------------------- HTML 预览

def esc(s: str) -> str:
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def render_html(report: dict) -> str:
    c = report["counts"]
    ops = report["operations"]
    models = report["models"]

    by_group: dict[str, list] = OrderedDict()
    for o in ops:
        g = (o["group_doc"].split("\n")[0].strip() if o["group_doc"] else o["controller"])
        by_group.setdefault(g, []).append(o)

    risks = [r for r in report["risks"] if r["level"] in ("P0", "P1")]
    p2 = [r for r in report["risks"] if r["level"] == "P2"]
    p3 = [r for r in report["risks"] if r["level"] == "P3"]

    def kv_class(v):
        return "get" if v == "GET" else ("post" if v == "POST" else
                                         "put" if v == "PUT" else
                                         "del" if v == "DELETE" else "pat")

    parts = []
    parts.append(f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>接口文档预览 · 海峡金 OA</title>
<style>
:root{{--bg:#f6f7f9;--card:#fff;--fg:#1f2328;--muted:#656d76;--line:#d8dee4;
--accent:#0969da;--ok:#1a7f37;--warn:#9a6700;--bad:#cf222e;--chip:#eff2f5;}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0d1117;--card:#161b22;--fg:#e6edf3;
--muted:#9198a1;--line:#30363d;--accent:#4493f8;--ok:#3fb950;--warn:#d29922;--bad:#f85149;--chip:#21262d;}}}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--fg);
font:14px/1.65 -apple-system,"PingFang SC","Microsoft YaHei",Helvetica,Arial,sans-serif}}
.wrap{{max-width:1100px;margin:0 auto;padding:32px 20px 80px}}
h1{{font-size:24px;margin:0 0 4px}}
h2{{font-size:17px;margin:36px 0 12px;padding-bottom:6px;border-bottom:1px solid var(--line)}}
h3{{font-size:15px;margin:22px 0 8px}}
.sub{{color:var(--muted);margin:0 0 20px}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px 18px;margin:14px 0}}
.stats{{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}}
.stat{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px}}
.stat .n{{font-size:22px;font-weight:600}}
.stat .l{{color:var(--muted);font-size:12px;margin-top:2px}}
table{{width:100%;border-collapse:collapse;font-size:13px}}
th,td{{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}}
th{{color:var(--muted);font-weight:600;font-size:12px;background:var(--chip)}}
code{{background:var(--chip);padding:1px 5px;border-radius:4px;font-size:12px;
font-family:ui-monospace,SFMono-Regular,Menlo,monospace}}
.m{{font-weight:700;font-size:11px;padding:2px 6px;border-radius:4px;color:#fff;white-space:nowrap}}
.get{{background:#1a7f37}}.post{{background:#0969da}}.put{{background:#9a6700}}
.del{{background:#cf222e}}.pat{{background:#6639ba}}
.tag{{font-size:11px;padding:1px 6px;border-radius:20px;background:var(--chip);color:var(--muted);white-space:nowrap}}
.pill{{display:inline-block;font-size:11px;padding:1px 7px;border-radius:20px;
border:1px solid var(--line);color:var(--muted);margin-right:4px}}
ul{{margin:6px 0;padding-left:20px}}li{{margin:3px 0}}
details{{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:10px 0}}
summary{{cursor:pointer;padding:12px 16px;font-weight:600}}
summary .cnt{{color:var(--muted);font-weight:400;font-size:12px;margin-left:6px}}
.body{{padding:0 16px 16px}}
.empty{{color:var(--ok)}}
.warn{{color:var(--warn)}}.bad{{color:var(--bad)}}
.desc{{color:var(--muted);font-size:12px;white-space:pre-wrap}}
</style></head><body><div class="wrap">
<h1>接口文档预览 · 海峡金 OA</h1>
<p class="sub">这是按 Apifox Helper（IDEA 插件）的解析规则，对现有源码做<b>静态模拟</b>得到的「同步后前端会看到的内容」。<br>
不是手写的，也不是从 Apifox 导出的 —— 用它来检查注释够不够、好不好懂。</p>

<div class="stats">
  <div class="stat"><div class="n">{c['operations']}</div><div class="l">接口（{c['controllers']} 个目录）</div></div>
  <div class="stat"><div class="n">{c['ops_with_summary']}/{c['operations']}</div><div class="l">接口有说明</div></div>
  <div class="stat"><div class="n">{c['fields_documented']}/{c['fields_total']}</div><div class="l">模型字段有说明</div></div>
  <div class="stat"><div class="n">{c['params_documented']}/{c['params_total']}</div><div class="l">参数有说明</div></div>
  <div class="stat"><div class="n">{c['ops_with_perm']}</div><div class="l">带接口权限门控</div></div>
  <div class="stat"><div class="n">{c['ops_with_audit']}</div><div class="l">带审计留痕</div></div>
</div>
""")

    # 同步前检查清单
    parts.append("""<h2>同步前请先确认（4 件事）</h2>
<div class="card"><ul>
<li><b>项目内配置已就位</b>：工程根目录的 <code>.apifox-helper.properties</code> 负责把时间字段渲染成字符串。
没有它，前端会看到 <code>{year,month,day…}</code> 这种和真实响应不符的结构。</li>
<li><b>只改注释、别在 Apifox 上手工补</b>：插件当前是「覆盖所有字段」模式，
手工在 Apifox 上补的说明会在下次同步时被覆盖。要补说明 → 补到代码注释里。</li>
<li><b>别配 <code>method.return</code>，也别给查询对象参数加 <code>@ModelAttribute</code></b>：
插件的内置规则是「无注解 = query 参数，<code>@ModelAttribute</code> = form-data 参数」，
本项目的 <code>DocumentQuery</code> / <code>SealQuery</code> / <code>LedgerExportQuery</code>
现在恰好正确识别为 query；加上注解反而变成 form-data。
<code>method.return</code> 则会让响应变成双层包装 <code>R&lt;R&lt;T&gt;&gt;</code>。</li>
<li><b>同步范围按模块勾选</b>：插件已绑定 <code>oa-system / oa-flow / oa-document / oa-boot / oa-backend</code> 五个模块，
全勾会重复同步；按需勾选即可。</li>
</ul></div>

<h2>同步后请逐项自查（这些只有真跑一遍插件才能确认）</h2>
<div class="card"><ul>
<li>任意 <code>R&lt;PageResult&lt;X&gt;&gt;</code> 接口（如 <code>GET /api/documents</code>）：响应里应能看到 <code>R</code> 的 4 个字段，
且 <code>data</code> 里能看到 <code>PageResult</code> 的 5 个字段。
<b>若 <code>data</code> 只显示成空对象 <code>{}</code></b>，说明泛型没被展开 ——
把配置里第 4 节注释掉的两行 <code>json.rule.convert</code> 放开后重新 Sync。</li>
<li><code>GET /api/documents</code>：应展开出 <code>DocumentQuery</code> 的字段名作为 query 参数，
而不是一个名字叫 <code>query</code> 的单个参数。</li>
<li>目录名：应与各控制器类注释的首行一致（如「部门管理」「待办与审批动作」）。</li>
</ul></div>""")

    # 风险
    parts.append("<h2>当前已知的「文档表达不出来」的部分</h2>")
    if not risks:
        parts.append('<div class="card empty">无 P0 / P1 级缺口。</div>')
    else:
        parts.append('<div class="card"><ul>')
        for r in risks[:40]:
            parts.append(f'<li><span class="pill">{esc(r["kind"])}</span><code>{esc(r["detail"])}</code></li>')
        parts.append("</ul></div>")
    if p2:
        parts.append(f'<details><summary>另有 {len(p2)} 条 P2（多为类型无法生成结构等）</summary><div class="body"><ul>')
        for r in p2[:80]:
            parts.append(f'<li>{esc(r["kind"])}：<code>{esc(r["detail"])}</code></li>')
        parts.append("</ul></div></details>")
    if p3:
        parts.append(f'<details><summary>另有 {len(p3)} 条 P3（路径参数为简单 ID，无说明也不影响对接）</summary>'
                     f'<div class="body"><ul>')
        for r in p3[:60]:
            parts.append(f'<li><code>{esc(r["detail"])}</code></li>')
        parts.append("</ul></div></details>")

    # 接口
    parts.append("<h2>接口（按目录分组）</h2>")
    for g, items in by_group.items():
        parts.append(f'<details open><summary>{esc(g)}<span class="cnt">{len(items)} 个接口</span></summary>'
                     '<div class="body"><table><thead><tr>'
                     '<th style="width:60px">方法</th><th style="width:34%">路径</th>'
                     '<th>接口说明（方法注释首行）</th><th style="width:20%">参数</th>'
                     '<th style="width:14%">门控 / 留痕</th></tr></thead><tbody>')
        for o in items:
            ps = [p for p in o["params"] if p["kind"] != "skip"]
            pdoc = o["javadoc_params"]
            ptxt = "、".join(
                f'<code>{esc(p["name"])}</code>' + ("（无说明）" if p["kind"] in ("body", "pojo") and p["name"] not in pdoc else "")
                for p in ps) or "—"
            tags = []
            if o["require_perm"]:
                tags.append(f'<span class="tag">权限 {esc(o["require_perm"])}</span>')
            if o["audit"]:
                tags.append('<span class="tag">写审计</span>')
            parts.append(
                f'<tr><td><span class="m {kv_class(o["verb"])}">{o["verb"]}</span></td>'
                f'<td><code>{esc(o["path"] or "/")}</code></td>'
                f'<td>{esc(o["summary"])}'
                + (f'<div class="desc">{esc(o["desc"][:220])}</div>' if o["desc"] else "")
                + f'</td><td>{ptxt}</td><td>{" ".join(tags) or "—"}</td></tr>')
        parts.append("</tbody></table></div></details>")

    # 模型
    parts.append("<h2>数据模型（字段说明）</h2>")
    resolved = [(n, m) for n, m in models.items() if m["resolved"]]
    resolved.sort(key=lambda x: (-len(x[1]["fields"]), x[0]))
    for n, m in resolved:
        nf = len(m["fields"])
        parts.append(f'<details><summary>{esc(n)}<span class="cnt">{nf} 个字段'
                     + (f' · 继承自 {esc(m["super"])}' if m.get("super") else "")
                     + '</summary><div class="body">')
        if m["javadoc"]:
            parts.append(f'<div class="desc">{esc(m["javadoc"])}</div>')
        parts.append('<table><thead><tr><th style="width:22%">字段</th><th style="width:20%">类型</th>'
                     '<th>说明</th></tr></thead><tbody>')
        for f in m["fields"]:
            cm = esc(f["comment"]) if f["comment"] else '<span class="bad">（无说明）</span>'
            parts.append(f'<tr><td><code>{esc(f["name"])}</code></td>'
                         f'<td><span class="tag">{esc(f["type"])}</span></td>'
                         f'<td>{cm}</td></tr>')
        parts.append("</tbody></table></div></details>")

    parts.append("</div></body></html>")
    return "\n".join(parts)


def main():
    index = index_classes()
    ops = parse_controllers(index)
    models = build_models(ops, index)

    risks = []

    def add(level, kind, detail):
        risks.append((level, kind, detail))

    SIMPLE = {"Long", "Integer", "String", "int", "long", "boolean", "Boolean",
              "LocalDate", "LocalDateTime"}

    # 1) 端点说明覆盖
    no_summary = [o for o in ops if not o["summary"]]
    for o in no_summary:
        add("P1", "接口无说明（Apifox 会显示成方法名）",
            f"{o['verb']} {o['path']} [{o['controller']}.{o['method']}]")
    # 2) @param 名字对不上 / 缺说明
    for o in ops:
        jp = o["javadoc_params"]
        for p in o["params"]:
            if p["kind"] == "skip":
                continue
            if p["name"] in jp:
                continue
            if p["kind"] in ("body", "pojo"):
                add("P1", "请求体/查询对象参数无说明（前端看不出这是干嘛的）",
                    f"{o['verb']} {o['path']} 参数 {p['type']} {p['name']}")
            elif p["type"] in SIMPLE and re.match(r"^(id|.*Id)$", p["name"]):
                add("P3", "路径/查询参数为简单 ID，无说明（可接受）",
                    f"{o['verb']} {o['path']} {p['name']}: {p['type']}")
            else:
                add("P2", "参数无说明",
                    f"{o['verb']} {o['path']} {p['name']}: {p['type']} ({p['kind']})")
        for name in jp:
            if name not in {p["name"] for p in o["params"]}:
                add("P0", "@param 名字与形参不一致（说明会丢失且不报错）",
                    f"{o['controller']}.{o['method']} 写了 @param {name}，"
                    f"实际形参 {[p['name'] for p in o['params']]}")
    # 3) 裸 POJO 参数
    for o in ops:
        for p in o["params"]:
            if p["kind"] == "pojo":
                add("P2", "查询对象参数无注解（官方规则=按 query 识别；同步后确认已展开成字段名而非单个 query）",
                    f"{o['verb']} {o['path']} -> {p['type']} {p['name']}")
    # 4) 模型字段说明
    for name, mv in models.items():
        if not mv["resolved"]:
            continue
        if not mv["javadoc"]:
            add("P1", "模型无类说明",
                f"{name} ({mv['fqn']})")
        for f in mv["fields"]:
            if not f["comment"]:
                add("P1", "响应/请求模型字段无说明",
                    f"{name}.{f['name']} ({f['type']})")
            base = f["type"].split("<")[0]
            if base in OPAQUE_TYPES or base.startswith("Map"):
                add("P2", "字段类型无法生成结构（Apifox 只能给 Object/示例）",
                    f"{name}.{f['name']} : {f['type']}")
    # 5) 未索引到的类型（排除 JDK）
    for name, mv in models.items():
        if not mv["resolved"] and name not in JDK_TYPES and name[0].isupper():
            add("P2", "类型未在本仓库索引到（多为泛型变量/第三方）", name)

    # 6) 与离线契约交叉校验路径集合（证明模拟器是忠实的）
    contract_paths = set()
    cp = os.path.join(OUT_DIR, "openapi.json")
    if os.path.exists(cp):
        try:
            spec = json.load(open(cp, encoding="utf-8"))
            contract_paths = set(spec.get("paths", {}).keys())
        except Exception as e:  # noqa
            add("P2", "离线契约读取失败", str(e))
    mine = set()
    for o in ops:
        p = o["path"].rstrip("/") or "/"
        mine.add(re.sub(r"\{[^}]*\}", "{id}", p))
    theirs = {re.sub(r"\{[^}]*\}", "{id}", p) for p in contract_paths}
    only_mine = sorted(mine - theirs)
    only_theirs = sorted(theirs - mine)

    total_params = sum(len([p for p in o["params"] if p["kind"] != "skip"]) for o in ops)
    documented_params = 0
    for o in ops:
        jp = o["javadoc_params"]
        documented_params += len([p for p in o["params"]
                                  if p["kind"] != "skip" and p["name"] in jp])
    total_fields = sum(len(m["fields"]) for m in models.values() if m["resolved"])
    documented_fields = sum(len([f for f in m["fields"] if f["comment"]])
                            for m in models.values() if m["resolved"])

    report = {
        "counts": {
            "operations": len(ops),
            "controllers": len({o["controller"] for o in ops}),
            "ops_with_summary": len(ops) - len(no_summary),
            "params_total": total_params,
            "params_documented": documented_params,
            "models": len([m for m in models.values() if m["resolved"]]),
            "fields_total": total_fields,
            "fields_documented": documented_fields,
            "ops_with_perm": len([o for o in ops if o["require_perm"]]),
            "ops_with_audit": len([o for o in ops if o["audit"]]),
        },
        "risks": [{"level": l, "kind": k, "detail": d} for l, k, d in risks],
        "cross_check": {
            "contract_paths": len(contract_paths),
            "emulator_paths": len(mine),
            "only_in_emulator": only_mine,
            "only_in_contract": only_theirs,
        },
        "operations": ops,
        "models": {k: v for k, v in models.items()},
    }

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "apifox-preview.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    with open(os.path.join(OUT_DIR, "apifox-preview.html"), "w", encoding="utf-8") as f:
        f.write(render_html(report))

    c = report["counts"]
    print("=" * 68)
    print("Apifox Helper 解析效果模拟")
    print("=" * 68)
    print(f"接口操作        {c['operations']} 个 / {c['controllers']} 个控制器")
    print(f"接口有说明      {c['ops_with_summary']}/{c['operations']}")
    print(f"参数有说明      {c['params_documented']}/{c['params_total']}")
    print(f"模型（可达）    {c['models']} 个")
    print(f"模型字段有说明  {c['fields_documented']}/{c['fields_total']}")
    print(f"带接口门控      {c['ops_with_perm']}   带审计  {c['ops_with_audit']}")
    print("-" * 68)
    x = report["cross_check"]
    print(f"与离线契约交叉校验：契约 {x['contract_paths']} 路径 / 模拟器 {x['emulator_paths']} 路径")
    print(f"  只在模拟器里: {x['only_in_emulator']}")
    print(f"  只在契约里  : {x['only_in_contract']}")
    print("-" * 68)
    from collections import Counter
    by = Counter(r["kind"] for r in report["risks"])
    for k, v in by.most_common():
        print(f"  {v:>4}  {k}")
    print("-" * 68)
    for r in report["risks"]:
        if r["level"] == "P0":
            print(f"  [P0] {r['kind']}\n       {r['detail']}")
    print(f"\nJSON -> 接口契约/apifox-preview.json")
    return report


if __name__ == "__main__":
    main()

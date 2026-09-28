#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重新生成离线接口契约 `接口契约/openapi.json`。

**为什么要重生成（而不是手工改）**
契约必须与源码同构（路径 / 操作 / 模型字段）。本文档历史上是手工维护的，结果是
**漏字段**：`NodeItem.allowReject` / `NodeTemplate.allowReject` 两个真实存在、前端
也在用的字段，在契约里整整缺了一轮（2026-09-28 复核发现）。手工维护必然会再漏，
所以结构一律以**活实例实抓**为准。

**为什么要合并**
springdoc **不解析 javadoc**（解析 javadoc 的是 Apifox），所以实抓结果里
`summary` / 字段 `description` 一律为空。而本文件历史上被人工补过 9 条说明
（`/api/company` 的 2 条 summary + `CompanyVO`/`CompanySaveReq` 共 7 个字段说明）。
重生成若不合并，这些说明会被静默抹掉。
⇒ 策略：**结构以实抓为准；人工说明按「路径+方法」「模型+字段」为 key 合并保留。**
   只合并实抓里**仍然存在**的对象——已被删除的端点/字段/模型不会被复活。

**用法（不要改配置文件，用环境变量临时开）**
    # 1) 另起一个临时实例（换端口，避免撞上正在跑的联调实例）
    cd oa-backend && OA_OPENAPI_ENABLED=true OA_JWT_SECRET="$(cat .jwt_secret)" \\
        "$(/usr/libexec/java_home -v 17)/bin/java" \\
        -jar oa-boot/target/oa-boot-1.0.0-SNAPSHOT.jar --server.port=8090 &
    # 2) 重生成
    python3 scripts/gen_openapi_contract.py --base http://127.0.0.1:8090
    # 3) 关掉临时实例；线上/联调实例必须保持 springdoc 关闭（/v3/api-docs 要 404）

**格式硬约束**：输出必须是**单行紧凑**（`wc -l` 为 0）、`separators=(",",":")`、
中文不转义。理由见项目记忆硬约束 32：把紧凑 JSON 写成 indent=2 会制造上万行无意义 diff。

退出码：0 成功；非 0 表示抓取失败或自检不通过（脚本自身问题，不是被测功能问题）。
"""
import argparse
import json
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "接口契约", "openapi.json")
HTTP_METHODS = ("get", "post", "put", "delete", "patch")


def fetch(base: str) -> dict:
    url = base.rstrip("/") + "/v3/api-docs"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310 本地地址
        return json.loads(resp.read().decode("utf-8"))


def operations(spec: dict) -> set:
    return {p + " " + m
            for p, ms in spec.get("paths", {}).items()
            for m in ms if m in HTTP_METHODS}


def schemas(spec: dict) -> dict:
    return spec.get("components", {}).get("schemas", {}) or {}


def merge_human_text(new: dict, old: dict) -> int:
    """把旧契约里的人工说明合并回实抓结果（结构不动）。返回保留条数。"""
    kept = 0
    # 1) 端点级 summary / description
    for path, methods in new.get("paths", {}).items():
        old_methods = old.get("paths", {}).get(path)
        if not isinstance(old_methods, dict):
            continue
        for method, op in methods.items():
            old_op = old_methods.get(method)
            if not isinstance(old_op, dict):
                continue
            for field in ("summary", "description"):
                if not op.get(field) and old_op.get(field):
                    op[field] = old_op[field]
                    kept += 1
    # 2) 模型字段 description（只合并实抓里仍存在的字段）
    new_schemas, old_schemas = schemas(new), schemas(old)
    for name, model in new_schemas.items():
        old_model = old_schemas.get(name)
        if not isinstance(old_model, dict):
            continue
        old_props = old_model.get("properties") or {}
        for field, prop in (model.get("properties") or {}).items():
            old_prop = old_props.get(field)
            if isinstance(old_prop, dict) and not prop.get("description") and old_prop.get("description"):
                prop["description"] = old_prop["description"]
                kept += 1
    # 3) servers：实抓拿到的是临时端口，保留旧值（否则契约里会写进 8090）
    if old.get("servers") and new.get("servers"):
        new["servers"] = old["servers"]
    return kept


def report(new: dict, old: dict) -> None:
    old_ops, new_ops = operations(old), operations(new)
    old_sch, new_sch = schemas(old), schemas(new)
    for tag, added, removed in (
        ("操作", new_ops - old_ops, old_ops - new_ops),
        ("模型", set(new_sch) - set(old_sch), set(old_sch) - set(new_sch)),
    ):
        if added:
            print("  新增%s：%s" % (tag, ", ".join(sorted(added))))
        if removed:
            print("  移除%s：%s" % (tag, ", ".join(sorted(removed))))
    print("  路径 %d → %d ｜ 操作 %d → %d ｜ 模型 %d → %d"
          % (len(old.get("paths", {})), len(new.get("paths", {})),
             len(old_ops), len(new_ops), len(old_sch), len(new_sch)))


def main() -> int:
    ap = argparse.ArgumentParser(description="重新生成离线接口契约")
    ap.add_argument("--base", default="http://127.0.0.1:8090",
                    help="临时实例地址（须以 OA_OPENAPI_ENABLED=true 启动）")
    ap.add_argument("--out", default=DEFAULT_OUT, help="契约输出路径")
    ap.add_argument("--dry-run", action="store_true", help="只对比，不落盘")
    args = ap.parse_args()

    if os.path.exists(args.out):
        with open(args.out, encoding="utf-8") as f:
            old = json.load(f)
    else:
        old = {"paths": {}, "components": {"schemas": {}}}

    try:
        new = fetch(args.base)
    except Exception as exc:  # noqa: BLE001 抓不到就先说人话
        print("抓取 %s/v3/api-docs 失败：%s" % (args.base.rstrip("/"), exc), file=sys.stderr)
        print("排查：实例是否以 OA_OPENAPI_ENABLED=true 启动？端口对不对？"
              "（线上/联调实例必须保持 springdoc 关闭，别为了抓契约去改配置文件）", file=sys.stderr)
        return 2

    kept = merge_human_text(new, old)
    report(new, old)
    print("  保留人工说明 %d 条" % kept)

    text = json.dumps(new, ensure_ascii=False, separators=(",", ":"))
    if "\n" in text or "\r" in text:
        print("自检失败：输出含换行，会破坏「单行紧凑」约束", file=sys.stderr)
        return 3

    if args.dry_run:
        print("  --dry-run：未写入（实抓 %d 字节）" % len(text.encode("utf-8")))
        return 0

    with open(args.out, "w", encoding="utf-8") as f:
        f.write(text)
    print("  已写入 %s（%d 字节，单行紧凑）" % (args.out, len(text.encode("utf-8"))))
    print("  提醒：契约只是一份快照，改完接口记得重跑本脚本；"
          "给前端的正式文档是 Apifox（从 javadoc 生成）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

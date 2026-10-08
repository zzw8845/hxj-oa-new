#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Maven 本地仓库的 Flowable 依赖中提取官方 MySQL 建表脚本，
汇总并重排为可直接执行的 sql/flowable_schema_mysql.sql。

为什么需要这个脚本：
    Flowable 7.0.0 的 eventregistry 模块改用 Liquibase 管理 schema，
    其前置检查会读 ACT_GE_PROPERTY 判断 common schema 是否就绪；
    当表不存在时判定逻辑会走错分支，执行
        insert into ACT_GE_PROPERTY values ('common.schema.version', ...)
    而不是建表，导致应用启动失败：
        FlowableException: couldn't upgrade db schema: insert into ACT_GE_PROPERTY ...
    规避方式：先用本脚本生成并执行建表 SQL，再启动应用。

为什么需要重排：
    官方脚本中「建表」与「建索引」是混排的，且跨文件存在依赖
    （例如 engine.sql 里给 ACT_RU_VARIABLE 建索引，而该表由 variable.sql 创建）。
    因此必须按 建表 → 索引 → 数据 三段式重排。

为什么要做幂等（2026-09-29，P1 收口）：
    MySQL 8 对 CREATE INDEX / ALTER TABLE ADD CONSTRAINT **没有 IF NOT EXISTS 语法**，
    只给建表补 IF NOT EXISTS，二次执行仍会死在索引（ERROR 1061）和版本标记
    （ERROR 1062）上 —— 那是"假幂等"。本脚本的解法是把索引和外键约束**内联进
    对应的 CREATE TABLE**：表已存在时整条建表语句跳过，索引/约束随之一起跳过，
    语义恰好是头部承诺的"要么整套建、要么整套不动"。版本标记用 INSERT IGNORE
    （已存在则跳过，正是幂等想要的语义）。

用法：
    1) 先构建过一次项目，确保 Flowable 依赖已下载到 ~/.m2
    2) python3 scripts/gen_flowable_ddl.py
    3) mysql -uroot <库名> < sql/flowable_schema_mysql.sql
"""
import glob
import os
import re
import sys
import zipfile

FLOWABLE_VERSION = "7.0.0"
OUTPUT = "sql/flowable_schema_mysql.sql"

# 依赖顺序：common 必须最先（ACT_GE_PROPERTY 是其余 schema 的版本锚点）
ORDER = ["common", "engine", "history", "identity", "task", "variable",
         "identitylink", "entitylink", "eventsubscription", "batch", "job"]


def rank(name):
    base = name.rsplit("/", 1)[-1]
    for i, key in enumerate(ORDER):
        if f".create.{key}.sql" in base:
            return i
    return 99


def collect():
    pattern = os.path.expanduser(
        f"~/.m2/repository/org/flowable/**/flowable-*-{FLOWABLE_VERSION}.jar")
    jars = sorted(glob.glob(pattern, recursive=True))
    if not jars:
        sys.exit(f"未找到 Flowable {FLOWABLE_VERSION} 依赖，请先执行 mvn install 下载依赖")

    avail = {}
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as z:
                for n in z.namelist():
                    if ("/db/create/" in n and n.endswith(".sql")
                            and "mysql.create" in n and "mysql55" not in n):
                        avail[n] = jar
        except Exception:
            continue
    if not avail:
        sys.exit("未在依赖中找到 MySQL 建表脚本")
    return sorted(avail.items(), key=lambda kv: (rank(kv[0]), kv[0]))


def split_statements(sql):
    # 去掉整行注释后再按分号切分
    cleaned = "\n".join(l for l in sql.split("\n") if not l.strip().startswith("--"))
    return [s.strip() for s in cleaned.split(";") if s.strip()]


# ---------------------------------------------------------------- 幂等改写
# 「这条语句属于哪张表」：create table T / create index X on T(...) / alter table T add ...
_TABLE_OF = [
    re.compile(r"(?is)^create\s+table\s+(?:if\s+not\s+exists\s+)?`?(\w+)`?"),
    re.compile(r"(?is)^create\s+(?:unique\s+|fulltext\s+)?index\s+\S+\s+on\s+`?(\w+)`?"),
    re.compile(r"(?is)^alter\s+table\s+`?(\w+)`?"),
]


def table_of(st):
    for p in _TABLE_OF:
        m = p.match(st)
        if m:
            return m.group(1).lower()
    return None


def to_inline_clause(st):
    """把独立索引/约束语句改写成可放进 CREATE TABLE 的子句。

    create [unique|fulltext] index NAME on T (cols)
        → INDEX|UNIQUE|FULLTEXT NAME (cols)
    alter table T add constraint CN <rest>
        → CONSTRAINT CN <rest>          （foreign key / unique 原样保留）
    """
    m = re.match(
        r"(?is)^create\s+(unique\s+|fulltext\s+)?index\s+(\S+)\s+on\s+`?\w+`?\s*\((.*)\)\s*$",
        st.strip())
    if m:
        kind = (m.group(1) or "").strip().upper()   # '' / UNIQUE / FULLTEXT
        kw = "INDEX" if not kind else kind
        return f"{kw} {m.group(2)} ({m.group(3)})"
    m = re.match(r"(?is)^alter\s+table\s+`?\w+`?\s+add\s+(constraint\s+.*)$", st.strip())
    if m:
        return m.group(1)
    return None


def make_idempotent_table(st):
    """create table T (...) → create table if not exists T (...)"""
    return re.sub(r"(?i)^create\s+table\s+(?!if\s+not\s+exists)",
                  "CREATE TABLE IF NOT EXISTS ", st.strip(), count=1)


def inject_clause(tbl_st, clause):
    """把子句插进建表语句的收尾括号前：') ENGINE=...' → ', <clause>) ENGINE=...'。
    每条建表语句只有一个 ENGINE=（官方 DDL 如此），count=1 即尾部。"""
    new, n = re.subn(r"(?i)\)\s*ENGINE=",
                     ",\n  " + clause + "\n) ENGINE=", tbl_st, count=1)
    return new if n == 1 else None


def main():
    picked = collect()
    tables, indexes, inserts, others = [], [], [], []

    for name, jar in picked:
        with zipfile.ZipFile(jar) as z:
            sql = z.read(name).decode("utf-8", errors="replace")
        for st in split_statements(sql):
            low = st.lower()
            first_line = low.split("\n")[0]
            if low.startswith("create table"):
                tables.append(st)
            elif low.startswith("create") and "index" in first_line:
                indexes.append(st)
            elif low.startswith("insert"):
                inserts.append(st)
            else:
                others.append(st)

    # ---- 内联：索引与约束全部归位到所属建表语句，任何一条归位失败立即报错 ----
    # 为什么不做静默容错：漏一条索引 = 该查询全表扫描；漏一条外键 = 引用完整性缺口。
    # 这类丢失在"建库成功"的表象下毫无感知，是最危险的失败模式（本项目踩过同类：
    # 手工补表被重新生成静默抹掉，所以 gen_init_database.sh 里也有一条同款守卫）。
    inline_total, by_table = 0, {}
    for st in indexes + others:
        clause = to_inline_clause(st)
        tbl = table_of(st)
        if not clause or not tbl:
            sys.exit(f"✗ 幂等改写失败（宁停不漏）：无法解析目标表或改写子句 →\n{st[:200]}")
        by_table.setdefault(tbl, []).append(clause)
        inline_total += 1

    placed = 0
    idem_tables = []
    for tbl in tables:
        tname = table_of(tbl)
        st = make_idempotent_table(tbl)
        for clause in by_table.pop(tname, []):
            st = inject_clause(st, clause)
            if st is None:
                sys.exit(f"✗ 子句注入失败（找不到收尾括号）：表 {tname}，子句 {clause[:120]}")
            placed += 1
        idem_tables.append(st)
    if by_table:
        # 索引/约束引用了不存在的建表语句 —— 来源脚本变动导致的错位，必须暴露
        sys.exit(f"✗ 以下表的索引/约束没有归属的 CREATE TABLE：{sorted(by_table)}")

    # ---- 版本标记：INSERT IGNORE（表已存在时版本行已在，重复插入必撞主键） ----
    idem_inserts = [re.sub(r"(?i)^insert\s+into", "INSERT IGNORE INTO", s.strip(), count=1)
                    for s in inserts]

    header = f"""-- =============================================================================
-- Flowable {FLOWABLE_VERSION} 官方 MySQL 建表脚本（自动汇总并重排，请勿手工编辑）
--
-- 由 scripts/gen_flowable_ddl.py 生成。
--
-- 背景：Flowable {FLOWABLE_VERSION} 的 eventregistry 用 Liquibase 管理 schema，
--       其前置检查读取 ACT_GE_PROPERTY 判断 common schema 是否就绪；表不存在时
--       判定逻辑走错分支，执行 insert 而非建表，导致启动失败：
--         FlowableException: couldn't upgrade db schema: insert into ACT_GE_PROPERTY ...
--       因此需先用本脚本预建全部 ACT_* 表。
--
-- 幂等（2026-09-29，P1 收口）：建表带 IF NOT EXISTS；官方后置的索引与外键约束
--       已内联进对应建表语句（表已存在时随之整体跳过）；版本标记为 INSERT IGNORE。
--       重复执行不会报错、不会动已有数据。MySQL 8 没有 CREATE INDEX IF NOT EXISTS，
--       内联是纯语句级幂等的唯一干净路径。
--
-- 用法：mysql -uroot <库名> < flowable_schema_mysql.sql
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ========== 建表（{len(idem_tables)} 张，索引/约束已内联 {inline_total} 条）=========="""

    parts = [header, ";\n".join(idem_tables) + ";",
             f"\n-- ========== 版本与初始化数据（INSERT IGNORE，{len(idem_inserts)} 条）==========",
             ";\n".join(idem_inserts) + ";"]
    parts.append("\nSET FOREIGN_KEY_CHECKS = 1;")

    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))

    print(f"已生成 {OUTPUT}")
    print(f"  建表 {len(idem_tables)}（幂等）/ 索引+约束内联 {placed}/{inline_total} / 数据 {len(idem_inserts)}（IGNORE）")
    print(f"  来源 {len(picked)} 个官方脚本")


if __name__ == "__main__":
    main()

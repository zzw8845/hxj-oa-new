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
-- 重排原因：官方脚本中建表与建索引混排，且跨文件存在依赖
--       （engine.sql 会给 variable.sql 创建的表建索引）。
--
-- 用法：mysql -uroot <库名> < flowable_schema_mysql.sql
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ========== 第 1 段：建表（{len(tables)} 条）=========="""

    parts = [header, ";\n".join(tables) + ";",
             f"\n-- ========== 第 2 段：索引（{len(indexes)} 条）==========",
             ";\n".join(indexes) + ";",
             f"\n-- ========== 第 3 段：版本与初始化数据（{len(inserts)} 条）==========",
             ";\n".join(inserts) + ";"]
    if others:
        parts.append(f"\n-- ========== 其他语句（{len(others)} 条）==========")
        parts.append(";\n".join(others) + ";")
    parts.append("\nSET FOREIGN_KEY_CHECKS = 1;")

    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))

    print(f"已生成 {OUTPUT}")
    print(f"  建表 {len(tables)} / 索引 {len(indexes)} / 数据 {len(inserts)} / 其他 {len(others)}")
    print(f"  来源 {len(picked)} 个官方脚本")


if __name__ == "__main__":
    main()

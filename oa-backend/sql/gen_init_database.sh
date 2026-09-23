#!/bin/bash
# ===========================================================================
#  生成 sql/init_database.sql —— 测试/生产库的完整表结构（不含业务种子数据）
#
#  为什么用脚本生成而不是手写一份大 SQL：
#    schema.sql（业务表）与 flowable_schema_mysql.sql（引擎表）各自都是独立维护的
#    权威来源。再手抄第三份，等于给"改了 schema 却忘了同步建库脚本"埋一个必然会踩的坑。
#    本脚本只做拼装，两个来源始终是唯一事实。
#
#  用法：bash gen_init_database.sh
# ===========================================================================
set -euo pipefail
cd "$(dirname "$0")"

TARGET_DB="hxj-oa"
OUT="init_database.sql"
# 先写临时文件，自检通过后再覆盖 OUT。
# 为什么：本脚本最初是「先 > OUT 再自检」，自检失败虽然 exit 1，但**坏脚本已经落在磁盘上**。
# 调用方（部署脚本/人手）只要没检查退出码，就会拿着缺表的建库脚本去建空库，
# 表现是"能启动、一提交就报错"，且现场没有任何报错提示。先写 tmp 能让失败零残留。
TMP="${OUT}.tmp"
trap 'rm -f "$TMP"' EXIT

[ -f schema.sql ]                 || { echo "✗ 缺少 schema.sql" >&2; exit 1; }
[ -f flowable_schema_mysql.sql ]  || { echo "✗ 缺少 flowable_schema_mysql.sql" >&2; exit 1; }

{
  cat <<'HEADER'
-- =============================================================================
-- 海峡金 OA 审批系统 — 建库脚本（**只有表结构，不含任何业务种子数据**）
--
--   目标库：MySQL 8.0.16+（5.7 不支持递归 CTE / 窗口函数，禁止使用）
--   库  名：hxj-oa       字符集：utf8mb4 / utf8mb4_0900_ai_ci
--
--   用法：
--     mysql -h <host> -P <port> -u<user> -p --default-character-set=utf8mb4 < init_database.sql
--     （本机：mysql -uroot < init_database.sql）
--
--   内容：① 建库  ② 29 张业务表  ③ 41 张 Flowable 引擎表（ACT_* / FLW_*）
--
--   【幂等】全部为 CREATE DATABASE/TABLE IF NOT EXISTS，重复执行不会报错，也不会动已有数据。
--           但注意：它**不会**升级已存在的表结构。改了 schema 后要更新线上库，
--           仍然需要单独的 ALTER 脚本（例如 sql/migration_*.sql 那种），不要指望重跑本脚本。
-- =============================================================================
--
--  【为什么必须连 Flowable 的 ACT_* 表一起建】
--    Flowable 7.0.0 的 eventregistry 改用 Liquibase 管理 schema，其前置检查会读
--    ACT_GE_PROPERTY 判断 common schema 是否就绪。表不存在时它走错分支，
--    执行的是 insert into ACT_GE_PROPERTY 而不是建表，启动直接失败：
--      FlowableException: couldn't upgrade db schema: insert into ACT_GE_PROPERTY ...
--    也就是说 flowable.database-schema-update=true **不能**替代这个脚本 ——
--    它只在"表已经存在"的前提下才是安全的。先建表是硬前提。
--
--  【关于下面那些 INSERT 不是种子数据】
--    它们是 Flowable 自己的 schema 版本标记（common.schema.version / schema.history 等），
--    少了这些行引擎会认为库没初始化过、直接拒绝启动。业务表（company / sys_user /
--    document / flow_config …）一行数据都不会插。
--
--  【本文件是生成物，不要手改】
--    由 sql/gen_init_database.sh 拼装：schema.sql + flowable_schema_mysql.sql。
--    要改表结构，请改那两个来源文件后重新执行 bash gen_init_database.sh。
-- =============================================================================

SET NAMES utf8mb4;

HEADER

  printf 'CREATE DATABASE IF NOT EXISTS `%s`\n  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n\n' "$TARGET_DB"
  printf 'USE `%s`;\n\n' "$TARGET_DB"

  cat <<'BIZ_HEADER'
-- =============================================================================
-- 第 1 部分：业务表（29 张）
--   来源：sql/schema.sql
-- =============================================================================

BIZ_HEADER

  # 丢掉 schema.sql 自己的建库/USE 语句，避免它把库切回 haixiajin_oa ——
  # 这是本脚本唯一需要"动"来源文件的地方，其余一律原样拼接。
  #
  # 第一条表达式用"到第一个分号为止"的区间删除，而不是只删 CREATE DATABASE 那一行：
  # schema.sql 里的建库语句是**跨两行**的（第二行是 DEFAULT CHARACTER SET … COLLATE …;），
  # 只删首行会留下一个孤儿的续行，执行时报
  #   ERROR 1064 ... near 'DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'
  # 而这个错误指向的是拼接后的行号，几乎无法一眼看出是这两行没删干净。
  sed -e '/^CREATE DATABASE IF NOT EXISTS/,/;/d' \
      -e '/^USE `haixiajin_oa`;/d' \
      schema.sql

  cat <<'FLW_HEADER'

-- =============================================================================
-- 第 2 部分：Flowable 7.0.0 引擎表（41 张）
--   来源：sql/flowable_schema_mysql.sql（由 scripts/gen_flowable_ddl.py 从官方 DDL 汇总）
-- =============================================================================

FLW_HEADER

  cat flowable_schema_mysql.sql
} > "$TMP"

# ------------------------------------------------------------------ 自检
# 拼装脚本最容易出的错是"删漏半条语句"，而那种错误的报错位置在拼接后的文件里，
# 与真实原因隔着几百行。这里把已知的失败模式直接做成断言。
fail=0
check() { # check <说明> <期望值> <实际值>
  if [ "$2" != "$3" ]; then
    printf '✗ %s：期望 %s，实际 %s\n' "$1" "$2" "$3" >&2
    fail=1
  else
    printf '  ✓ %s = %s\n' "$1" "$3"
  fi
}

db_count=$(grep -cE '^CREATE DATABASE' "$TMP" || true)
legacy_count=$(grep -c 'haixiajin_oa' "$TMP" || true)
# 建库语句自带一行 DEFAULT CHARACTER SET，所以正确值是 1。
# 若来源文件里的续行没删干净，这里会变成 2 —— 那条孤立语句正是上一版生成器的真实故障。
charset_count=$(grep -cE '^ +DEFAULT CHARACTER SET utf8mb4 COLLATE' "$TMP" || true)
table_count=$(grep -ciE '^create table' "$TMP" || true)

check "CREATE DATABASE 语句数" "1" "$db_count"
check "遗留的旧库名 haixiajin_oa" "0" "$legacy_count"
check "DEFAULT CHARACTER SET 行数（只应有本脚本自己的一行）" "1" "$charset_count"
check "CREATE TABLE 总数（应为 29 业务 + 41 引擎 = 70）" "70" "$table_count"

# ---- 通用守卫：schema.sql 里的每一张表都必须出现在产出中 --------------------
# 这条是为一次真实事故加的：flow_delegation / flow_escalation 当初是**手工补进
# init_database.sql** 的（schema.sql 里没有），于是下一次重新生成本脚本时，
# 它们被静默抹掉 —— 表数校验虽然也能拦住，但报的只是"68≠66"，
# 看不出是哪两张表、也不提示"schema.sql 已不是全量事实"。
# 逐表比对能直接点名缺了谁，把根因（来源文件不是权威事实）暴露在输出里。
missing=""
while IFS= read -r t; do
  grep -q "CREATE TABLE IF NOT EXISTS \`${t}\`" "$TMP" || missing="${missing} ${t}"
done < <(grep -oE '^CREATE TABLE IF NOT EXISTS `[A-Za-z_0-9]+`' schema.sql | sed 's/.*`\(.*\)`/\1/')
if [ -n "$missing" ]; then
  printf '✗ 以下表在 schema.sql 中存在、但未进入产出：%s\n' "$missing" >&2
  printf '  多因来源文件与拼接逻辑不一致（例如表是手工补进生成物的，下次重构就丢）。\n' >&2
  fail=1
else
  printf '  ✓ schema.sql 中的业务表已全部进入产出\n'
fi

[ "$fail" = 0 ] || { rm -f "$TMP"; echo "✗ 自检未通过，已丢弃 ${TMP}，${OUT} 保持原样" >&2; exit 1; }

mv "$TMP" "$OUT"

# 注意 ${OUT} 的写法：紧跟其后的 "（" 是全角括号，bash 会把它前面的字节
# 一并当作变量名去解析，在 set -u 下直接报 "unbound variable"。
# 变量名后面接中文标点时，一律用 ${VAR} 显式闭合。
echo "✓ 已生成 ${OUT}（$(wc -l < "$OUT" | tr -d ' ') 行，$(du -h "$OUT" | cut -f1)）"

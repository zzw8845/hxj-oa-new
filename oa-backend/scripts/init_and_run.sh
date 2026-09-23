#!/usr/bin/env bash
# =============================================================================
# 海峡金 OA 后端 —— 一键初始化并启动
#
# 做的事：建库 → 建业务表 → 灌种子数据 → 建 Flowable 引擎表 → 编译 → 启动
#
# 前置：本地 MySQL 8.0 可连（默认 root 免密）、JDK 17 已安装
# 用法：./scripts/init_and_run.sh [端口，默认 8080]
# =============================================================================
set -euo pipefail

PORT="${1:-8080}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="${OA_DB:-haixiajin_oa}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_CMD=(mysql -u"$MYSQL_USER")
[ -n "${MYSQL_PASSWORD:-}" ] && MYSQL_CMD+=("-p${MYSQL_PASSWORD}")
[ -n "${MYSQL_HOST:-}" ] && MYSQL_CMD+=("-h" "$MYSQL_HOST")

cd "$ROOT"

# ---- 0. JDK 17：Spring Boot 3.2 / Flowable 7 必需，且 JDK 21+ 会与 Lombok 冲突 ----
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17'; then
  if [ "$(uname)" = "Darwin" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "[错误] 未找到 JDK 17。请设置 JAVA_HOME 后重试。" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "[1/6] JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ---- 1. 建库 ----
echo "[2/6] 重建数据库 $DB"
"${MYSQL_CMD[@]}" -e "DROP DATABASE IF EXISTS \`$DB\`; \
  CREATE DATABASE \`$DB\` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# ---- 2. 业务表 + 种子数据 ----
echo "[3/6] 导入业务表结构（29 张）与种子数据"
"${MYSQL_CMD[@]}" "$DB" < sql/schema.sql
"${MYSQL_CMD[@]}" "$DB" < sql/seed_data.sql

# ---- 3. Flowable 引擎表 ----
# 必须预建：Flowable 7.0.0 的 eventregistry 用 Liquibase 管 schema，
# 其前置检查读不到 ACT_GE_PROPERTY 时会走错分支，执行 insert 而非建表，导致启动失败。
if [ ! -f sql/flowable_schema_mysql.sql ]; then
  echo "      未找到 flowable_schema_mysql.sql，从 Maven 依赖重新生成…"
  python3 scripts/gen_flowable_ddl.py
fi
echo "[4/6] 导入 Flowable 引擎表（39 张 ACT_*）"
"${MYSQL_CMD[@]}" "$DB" < sql/flowable_schema_mysql.sql

# ---- 4. 编译 ----
echo "[5/6] 编译（跳过测试）"
mvn -B -q -pl oa-boot -am install -DskipTests

# ---- 5. 启动 ----
JAR="oa-boot/target/oa-boot-1.0.0-SNAPSHOT.jar"
echo "[6/6] 启动服务，端口 $PORT"
echo "      Swagger:  http://127.0.0.1:$PORT/swagger-ui.html"
echo "      冒烟测试: python3 scripts/api_smoke_test.py http://127.0.0.1:$PORT"
echo "      可用账号: admin / huangxm / linjl / wangkj / zhangzong / zhaocs （密码均 123456）"
echo ""
exec "$JAVA_HOME/bin/java" -jar "$JAR" --server.port="$PORT"

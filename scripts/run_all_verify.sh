#!/bin/bash
# =============================================================================
# 接口级用例的统一入口：**随机顺序**跑全部用例 + 前后比对演示库基线。
#
# 【为什么要有"随机顺序"】
# 2026-09-22 实测过一次真实的互相干扰：`verify_seal_api` 的收尾写了
# `DELETE FROM seal_apply`（整表清空），把演示库 9 条「待用印」基线删掉了，
# 直接导致 admin E2E 的用印断言 4 条失败。**固定顺序跑时它恰好排在后面，
# 问题一直被掩盖**。随机顺序 + 前后基线比对，才能把这类问题暴露成红灯。
#
# 【为什么还要比对基线】
# 用例的收尾原则是「只还原自己动过的」。基线前后不一致，就说明有脚本
# 动了不属于它的数据 —— 这条检查与"每个脚本自己的断言"是互补的：
# 脚本自己的断言只看自己关心的表，管不了别人。
#
# 用法：
#   bash scripts/run_all_verify.sh              # 随机顺序
#   bash scripts/run_all_verify.sh --order      # 顺带打印本次的随机顺序（便于复现）
#   OA_VERIFY_SEED=12345 bash scripts/run_all_verify.sh   # 固定随机种子（复现某次失败）
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
PY="${OA_VERIFY_PYTHON:-python3}"
DB="haixiajin_oa"
MYSQL="mysql -uroot $DB -N -B"

# ------------------------------------------------------------------ 演示库基线
# 这些数字是「用例跑完后必须一模一样」的演示库口径。
# 表名与口径改动时，请同步更新这里，并在提交说明里写清原因。
baseline() {
  $MYSQL -e "
    SELECT CONCAT(
      'document=',        (SELECT COUNT(*) FROM document WHERE deleted=0),
      ' user=',           (SELECT COUNT(*) FROM sys_user WHERE deleted=0),
      ' post=',           (SELECT COUNT(*) FROM post WHERE deleted=0),
      ' dept=',           (SELECT COUNT(*) FROM department WHERE deleted=0),
      ' template=',       (SELECT COUNT(*) FROM form_template WHERE deleted=0),
      ' tpl_active=',     (SELECT COUNT(*) FROM form_template WHERE deleted=0 AND status=1),
      ' seal_apply=',     (SELECT COUNT(*) FROM seal_apply WHERE deleted=0),
      ' seal_pending=',   (SELECT COUNT(*) FROM seal_apply WHERE deleted=0 AND return_status=0),
      ' seal_record=',    (SELECT COUNT(*) FROM seal_record WHERE deleted=0),
      ' delegation=',     (SELECT COUNT(*) FROM flow_delegation WHERE deleted=0),
      ' escalation=',     (SELECT COUNT(*) FROM flow_escalation WHERE deleted=0),
      ' act_task=',       (SELECT COUNT(*) FROM ACT_RU_TASK),
      ' notify=',         (SELECT COUNT(*) FROM notification WHERE deleted=0)
    );" 2>/dev/null
}

SUITES=(
  verify_admin_api
  verify_attachment_api
  verify_idor_fix
  verify_master_data_api
  verify_logout_revoke
  verify_seal_api
  verify_form_template_api
  verify_batch_approve_api
  verify_delegation_api
  verify_escalation_api
  verify_flow_interaction
)

# ------------------------------------------------------------------ 随机顺序
ORDER_FILE="$(mktemp)"
for s in "${SUITES[@]}"; do
  [ -f "$ROOT/scripts/$s.py" ] && echo "$s"
done > "$ORDER_FILE"
TOTAL_SUITES=$(wc -l < "$ORDER_FILE" | tr -d ' ')

if [ -n "${OA_VERIFY_SEED:-}" ]; then
  # 固定种子时用 awk 做可复现的确定性洗牌（sort -R 的随机源无法指定种子）
  awk -v seed="$OA_VERIFY_SEED" 'BEGIN{srand(seed)} {print rand() "\t" $0}' "$ORDER_FILE" \
    | sort -k1,1n | cut -f2- > "$ORDER_FILE.shuf"
else
  sort -R "$ORDER_FILE" > "$ORDER_FILE.shuf"
fi
ORDER=$(cat "$ORDER_FILE.shuf" | tr '\n' ' ')

echo "==================== 接口级用例（随机顺序） ===================="
echo "  共 $TOTAL_SUITES 个用例"
if [ "${1:-}" = "--order" ]; then
  echo "  本次顺序：$ORDER"
fi

BASE_BEFORE="$(baseline)"
echo "  跑前基线：$BASE_BEFORE"
echo

FAILED=()
PASS_COUNT=0
for s in $ORDER; do
  LOG="/tmp/verify_${s}.log"
  if (cd "$ROOT" && "$PY" "scripts/$s.py") > "$LOG" 2>&1; then
    SUMMARY=$(grep -aE "结果：|断言 .* 项全部通过" "$LOG" | tail -1)
    printf '  ✓ %-30s %s\n' "$s" "${SUMMARY:-（通过）}"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    printf '  ✗ %-30s 退出码非 0\n' "$s"
    grep -aE "\[FAIL\]|失败清单" -A 5 "$LOG" | head -8 | sed 's/^/       /'
    FAILED+=("$s")
  fi
done

echo
echo "==================== 基线比对 ===================="
BASE_AFTER="$(baseline)"
echo "  跑后基线：$BASE_AFTER"
BASE_OK=1
if [ "$BASE_BEFORE" != "$BASE_AFTER" ]; then
  BASE_OK=0
  echo
  echo "✗ 演示库基线与跑前不一致 —— 有用例动了不属于它的数据。"
  echo "  跑前：$BASE_BEFORE"
  echo "  跑后：$BASE_AFTER"
  echo "  排查方向：最近改动/新增的用例脚本，收尾是否写了整表删或按模式删。"
fi

echo
echo "==================== 汇总 ===================="
echo "  用例：通过 $PASS_COUNT / $TOTAL_SUITES"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "  失败清单："
  for s in "${FAILED[@]}"; do echo "    - $s（日志 /tmp/verify_$s.log）"; done
fi
if [ "$BASE_OK" = "1" ] && [ ${#FAILED[@]} -eq 0 ]; then
  echo "✓ 全部通过，且演示库基线前后一致"
  rm -f "$ORDER_FILE" "$ORDER_FILE.shuf"
  exit 0
fi
# 非 0 退出时保留顺序文件，便于复现
echo "  （本次随机顺序已留在 $ORDER_FILE.shuf，可用 OA_VERIFY_SEED 复现）"
exit 1

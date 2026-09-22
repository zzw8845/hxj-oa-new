#!/bin/bash
# ===========================================================================
#  一次跑完 5 个前端 E2E 套件（含前置自检与"预期条数"核对）
#
#  用法：
#      bash oa-backend/scripts/e2e/run_all_e2e.sh
#      NODE_PATH=/path/to/node_modules bash .../run_all_e2e.sh      # 依赖不在默认位置时
#      OA_E2E_SKIP_PREFLIGHT=1 bash .../run_all_e2e.sh             # 跳过前置自检（不推荐）
#
#  退出码：0 全绿；1 有用例失败；2 前置自检未通过（环境问题，不是代码问题）
#
#  ── 为什么需要这个入口，而不是让人挨个 node 一下 ──────────────────────────
#  2026-09-21 实测踩到两个"环境问题伪装成用例失败"的情况：
#    ① 本机后端「假活」：服务运行时依赖的 fat jar 被重新构建覆盖 ⇒
#       /api/** 全部毫秒级正常，静态资源 0 字节响应；套件报的是 `fetch failed`，
#       看着像网络问题。真因在日志里：NoClassDefFoundError: ThrowableProxy。
#    ② 卫生模块「盯错库」：本机后端用 test profile 起（连远端 hxj-oa），
#       而 _hygiene 看的是本机 haixiajin_oa ⇒ 污染一条也检测不出来，输出全绿。
#  这两种都不该让人从一堆用例失败里反推，所以前置自检必须**先跑、单独报**。
#
#  ── 为什么每个套件的预期条数要写在这里 ────────────────────────────────────
#  套件自己已经带"预期总数"自证（数量对不上会非 0 退出），这一层只是**再核一遍**
#  并汇总。本项目被坑过：脚本上游抛错导致其后断言全部静默不执行、末行照样打印通过。
#  所以"跑了几条"必须与声明的数对上，只报 pass/total 不算数。
# ===========================================================================
set -uo pipefail
cd "$(dirname "$0")" || exit 1

# 依赖（puppeteer）通常在受管 node 工作区里；换机器时用 NODE_PATH 覆盖。
if [ -z "${NODE_PATH:-}" ]; then
  for c in \
      "$HOME/.workbuddy/binaries/node/workspace/node_modules" \
      "$PWD/node_modules" \
      "$PWD/../../../node_modules"; do
    [ -d "$c" ] && { export NODE_PATH="$c"; break; }
  done
fi
# node 选型：优先受管版本（套件的基线是在它上面验的），再退回 PATH 里的 node。
# 不写死 `node` 是因为这台机器上 PATH 里的 node 是 homebrew 的另一版本，
# 而"换个 node 版本跑出不同结果"这种事最难查。
if [ -z "${OA_E2E_NODE:-}" ]; then
  for c in "$HOME/.workbuddy/binaries/node/versions/22.22.2-3/bin/node" \
           "$(command -v node 2>/dev/null || true)"; do
    [ -n "${c:-}" ] && [ -x "$c" ] && { OA_E2E_NODE="$c"; break; }
  done
fi
NODE="${OA_E2E_NODE:-}"
if [ -z "$NODE" ] || ! "$NODE" --version >/dev/null 2>&1; then
  echo "✗ 找不到可用的 node（可用 OA_E2E_NODE=/绝对路径/node 指定）"; exit 2
fi
echo "使用 node：${NODE}（$("$NODE" --version)）"

# 套件:预期断言数 —— 改动套件时这里必须同步改，否则下面的核对会失败（故意的）
SUITES="
frontend_admin_e2e:61
frontend_attachment_e2e:56
frontend_dashboard_e2e:44
frontend_final_gaps_e2e:65
frontend_business_gaps_e2e:79
"

# ---------------------------------------------------------------- 前置自检
# 单独跑一次 _hygiene.js：它会在 require 时打印监控目标并判掉"环境问题"。
# 注意：不是所有套件都 require 了 _hygiene（admin 套件就没有），
# 所以不能指望"跑用例时顺带检查"。
if [ "${OA_E2E_SKIP_PREFLIGHT:-0}" != "1" ]; then
  echo "==================== 前置自检 ===================="

  # 脚本静态自检：`$VAR` 紧跟中文字符会被 bash 当成变量名的一部分，
  # 运行时报 `<乱码>: unbound variable`。这个坑项目里反复踩过，
  # 且 `bash -n` 查不出来，所以放在这里开跑前扫一遍。
  PY_BIN="${OA_E2E_PYTHON:-python3}"
  if command -v "$PY_BIN" >/dev/null 2>&1 && [ -f ../../../scripts/check_shell_var_i18n.py ]; then
    if ! "$PY_BIN" ../../../scripts/check_shell_var_i18n.py; then
      echo
      echo "✗ 脚本自检未通过 —— 先修掉上面的写法再跑用例。"
      exit 2
    fi
  fi
  if ! "$NODE" -e "require('./_hygiene.js')"; then
    echo
    echo "✗ 前置自检未通过 —— 这是**环境问题**，不是代码问题，用例没有必要跑。"
    echo "  上面已给出判据与修法。修好后重跑本脚本。"
    exit 2
  fi
fi

# ---------------------------------------------------------------- 逐套件
echo "==================== E2E 套件 ===================="
TOTAL_WANT=0; TOTAL_GOT=0; BAD=0
for item in $SUITES; do
  s="${item%%:*}"; want="${item##*:}"
  log="/tmp/e2e_${s}.log"
  "$NODE" "$s.js" > "$log" 2>&1
  rc=$?
  # 套件自己打印的"X/X 通过（预期 N 条）"就是它的自证结论
  summary=$(grep -aoE '[0-9]+/[0-9]+ 通过（预期 [0-9]+ 条）' "$log" | tail -1)
  got=$(printf '%s' "$summary" | grep -oE '^[0-9]+/[0-9]+' | cut -d/ -f1)
  declared=$(printf '%s' "$summary" | grep -oE '预期 [0-9]+ 条' | grep -oE '[0-9]+')

  if [ "$rc" != "0" ]; then
    printf '  ✗ %-30s 退出码=%s  %s\n' "$s" "$rc" "$summary"
    BAD=$((BAD+1))
  elif [ -z "$summary" ]; then
    # 没有自证结论 = 不能当通过（正是本项目栽过的那类事故）
    printf '  ✗ %-30s 退出码=0 但**没有打印预期条数自证**，不能当通过\n' "$s"
    BAD=$((BAD+1))
  elif [ "$got" != "$want" ] || [ "$declared" != "$want" ]; then
    printf '  ✗ %-30s 实际 %s 条 / 套件声明 %s 条 / 本脚本预期 %s 条 —— 三者必须一致\n' \
      "$s" "${got:-?}" "${declared:-?}" "$want"
    BAD=$((BAD+1))
  else
    printf '  ✓ %-30s %s\n' "$s" "$summary"
    TOTAL_GOT=$((TOTAL_GOT + got))
  fi
  TOTAL_WANT=$((TOTAL_WANT + want))
done

echo
# ---------------------------------------------------------------- 残渣清扫
# 【为什么需要】套件各自都有清理段，但**清理段在用例中途抛错时就跑不到**，
# 于是夹具留在演示库里。实测遇到过一次：`E2E草稿夹具（跑完即删）` 多出一张，
# 而那张单据的标题本身就写着"跑完即删"。
# 这类残渣的危险不在于占空间，而在于**会累积**：每失败一次多一条，
# 最终把演示库基线撑到没人认得出原样。
#
# 刻意"报出来 + 清掉"，而不是静默清掉：静默的话，套件的清理缺陷永远不会被发现。
if command -v mysql >/dev/null 2>&1; then
  RESIDUE=$(mysql -uroot haixiajin_oa -N -B \
    -e "SELECT id FROM document WHERE title LIKE 'E2E草稿夹具%'" 2>/dev/null | tr '\n' ' ')
  if [ -n "${RESIDUE:-}" ]; then
    COUNT=$(printf '%s' "$RESIDUE" | wc -w | tr -d ' ')
    echo
    echo "! 发现 ${COUNT} 张 E2E 残留夹具单据（ids: ${RESIDUE}）——已物理清理。"
    echo "  频繁出现说明某个套件的清理段没跑到（用例中途抛错），值得去查那个套件，别只靠这道兜底。"
    for rid in $RESIDUE; do
      mysql -uroot haixiajin_oa -e "\
        DELETE FROM attachment WHERE document_id=$rid; \
        DELETE FROM document_link WHERE document_id=$rid; \
        DELETE FROM flow_instance_node WHERE document_id=$rid; \
        DELETE FROM flow_instance WHERE document_id=$rid; \
        DELETE FROM notification WHERE biz_type='document' AND biz_id=$rid; \
        DELETE FROM document WHERE id=$rid;" 2>/dev/null
    done
  fi
fi

echo "==================== 汇总 ===================="
printf '套件：5 个，预期断言 %s 条，实跑通过 %s 条，异常套件 %s 个\n' \
  "$TOTAL_WANT" "$TOTAL_GOT" "$BAD"
if [ "$BAD" != "0" ]; then
  echo "✗ 有 $BAD 个套件异常（详见 /tmp/e2e_*.log）"
  exit 1
fi
if [ "$TOTAL_GOT" != "$TOTAL_WANT" ]; then
  echo "✗ 通过条数与预期不符 —— 当事故查，不要当成「少跑几条」"
  exit 1
fi
echo "✓ 全部通过（${TOTAL_GOT}/${TOTAL_WANT}）"
echo
echo "别忘了复核演示库污染：python3 scripts/cleanup_demo_pollution.py --dry-run"
exit 0

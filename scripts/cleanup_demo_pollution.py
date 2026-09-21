#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清理演示库里的「测试遗留污染」，把库收敛回演示基线。

三类污染：
  1) 死流程部署：ACT_RE_PROCDEF 里存在、但 flow_config 没有任何一行引用它的 KEY_。
     正常流程配置一定会把 proc_def_key 写进 flow_config（FlowConfigAdminService 部署时回填），
     所以「没有配置引用的部署」必然是探针/早期测试留下的残骸。
     已知例子：DAILY_PAYMENT_V2（v2 探针）、E2E_TMP_TYPE_V1/V2（早期 E2E 临时流程）。
  2) 孤儿流程实例：ACT_HI_PROCINST.BUSINESS_KEY_ = 单据编号，单据已被删但实例还在。
     （BUSINESS_KEY_ 存的是 doc_no，不是 document.id，别 JOIN 错。）
  3) 孤儿运行时实例：ACT_RU_* 同上。
  4) 孤儿通知：notification.biz_type='document' 且 biz_id 指向已删除的单据。
     TodoService 会在每次审批动作后给发起人写一条「您的单据有新进展/已通过」，
     用例删了单据却没删通知 ⇒ 通知表只增不减，且前端一旦显示未读数就会变成"幽灵角标"。

安全约束：
  - 只删「没有任何运行中/历史实例引用」的流程定义（有实例的一律跳过并告警）。
  - 演示的 3 条定义（日常付款/员工报销/用印申请）由 flow_config 保护，天然不会被删。
  - 支持 --dry-run，默认先跑 dry-run 看清单。
  - 幂等：重复执行第二次应报「无需清理」。

用法：
  python3 scripts/cleanup_demo_pollution.py --dry-run
  python3 scripts/cleanup_demo_pollution.py --apply
"""

import os
import subprocess
import sys

os.environ['no_proxy'] = '127.0.0.1,localhost,::1'

DB = 'haixiajin_oa'

# ACT_HI_* / ACT_RU_* 里能用 PROC_INST_ID_ 关联的表。
# 注意：ACT_HI_ENTITYLINK 与 ACT_RU_ENTITYLINK 没有 PROC_INST_ID_ 列，不能放进来。
HI_BY_PROC = ['ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
              'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG']
RU_BY_PROC = ['ACT_RU_IDENTITYLINK', 'ACT_RU_ACTINST', 'ACT_RU_TASK',
              'ACT_RU_VARIABLE', 'ACT_RU_EVENT_SUBSCR']


def q(stmt):
    """执行并返回 TSV（含表头）。"""
    r = subprocess.run(['mysql', '-uroot', DB, '-e', stmt],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f'mysql 失败：{stmt}\n{r.stderr.strip()}')
    return r.stdout


def qn(stmt):
    """执行并返回裸值。"""
    r = subprocess.run(['mysql', '-uroot', DB, '-N', '-e', stmt],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f'mysql 失败：{stmt}\n{r.stderr.strip()}')
    return r.stdout.strip()


def exec_(stmt):
    subprocess.run(['mysql', '-uroot', DB, '-e', stmt],
                   capture_output=True, text=True, check=True)


# ---------------------------------------------------------------- 基线

def snapshot():
    return {
        'document':          qn('SELECT COUNT(*) FROM document'),
        'attachment':        qn('SELECT COUNT(*) FROM attachment WHERE deleted=0'),
        'notification':      qn('SELECT COUNT(*) FROM notification WHERE deleted=0'),
        'ACT_RU_TASK':       qn('SELECT COUNT(*) FROM ACT_RU_TASK'),
        'ACT_RU_EXECUTION':  qn('SELECT COUNT(*) FROM ACT_RU_EXECUTION'),
        'ACT_HI_PROCINST':   qn('SELECT COUNT(*) FROM ACT_HI_PROCINST'),
        'ACT_RE_PROCDEF':    qn('SELECT COUNT(*) FROM ACT_RE_PROCDEF'),
        'orphan_hi_inst':    qn('SELECT COUNT(*) FROM ACT_HI_PROCINST p '
                                'LEFT JOIN document d ON d.doc_no = p.BUSINESS_KEY_ '
                                'WHERE d.id IS NULL'),
        'orphan_notify':     qn("SELECT COUNT(*) FROM notification n WHERE n.deleted=0 "
                                "AND n.biz_type='document' "
                                "AND NOT EXISTS (SELECT 1 FROM document d WHERE d.id=n.biz_id)"),
    }


def show(label, snap):
    items = ' '.join(f'{k}={v}' for k, v in snap.items())
    print(f'  [{label}] {items}')


# ---------------------------------------------------------------- 1) 死流程部署

def dead_deployments():
    """flow_config 没有引用的流程定义 —— 探针/测试残骸。

    额外保护：如果该定义下还有运行中或历史实例，说明可能并非残骸，跳过。
    """
    rows = qn(
        "SELECT p.KEY_, p.VERSION_, p.ID_, p.DEPLOYMENT_ID_ "
        "FROM ACT_RE_PROCDEF p "
        "WHERE NOT EXISTS (SELECT 1 FROM flow_config c "
        "                  WHERE c.proc_def_key = p.KEY_ AND c.deleted = 0) "
        "ORDER BY p.KEY_, p.VERSION_"
    )
    out = []
    for line in rows.split('\n'):
        if not line.strip():
            continue
        key, ver, pid, dep = line.split('\t')
        ru = int(qn(f"SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_DEF_ID_='{pid}'"))
        hi = int(qn(f"SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE PROC_DEF_ID_='{pid}'"))
        out.append({'key': key, 'ver': ver, 'procd_id': pid,
                    'deployment_id': dep, 'ru': ru, 'hi': hi})
    return out


def drop_deployments(items):
    removed = 0
    for it in items:
        if it['ru'] or it['hi']:
            print(f"    ! 跳过 {it['key']} v{it['ver']}：仍被 "
                  f"{it['ru']} 个运行中 / {it['hi']} 个历史实例引用")
            continue
        dep = it['deployment_id']
        # 顺序有讲究：DEPLOYMENT 被 PROCDEF / BYTEARRAY 外键引用，必须后删
        exec_(f"DELETE FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_='{dep}'")
        exec_(f"DELETE FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_='{dep}'")
        exec_(f"DELETE FROM ACT_RE_DEPLOYMENT WHERE ID_='{dep}'")
        removed += 1
        print(f"    - 已移除死部署 {it['key']} v{it['ver']} (deployment={dep})")
    return removed


# ---------------------------------------------------------------- 2/3) 孤儿实例

def orphan_proc_insts():
    rows = qn(
        "SELECT p.PROC_INST_ID_, p.BUSINESS_KEY_, p.PROC_DEF_ID_ "
        "FROM ACT_HI_PROCINST p "
        "LEFT JOIN document d ON d.doc_no = p.BUSINESS_KEY_ "
        "WHERE d.id IS NULL"
    )
    return [l.split('\t') for l in rows.split('\n') if l.strip()]


def drop_orphan_instances(rows):
    for pid, bkey, _ in rows:
        exec_(f"DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='{pid}'")
        for t in HI_BY_PROC:
            exec_(f"DELETE FROM {t} WHERE PROC_INST_ID_='{pid}'")
        # 运行时表：用例中途失败会留下"活着"的实例，不清就会污染演示待办数
        for t in RU_BY_PROC:
            exec_(f"DELETE FROM {t} WHERE PROC_INST_ID_='{pid}'")
        # ACT_RU_EXECUTION 是自引用的，先删子再删父
        exec_(f"DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='{pid}' AND PARENT_ID_ IS NOT NULL")
        exec_(f"DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='{pid}'")
        print(f"    - 已移除孤儿实例 {pid} (business_key={bkey or '(空)'})")
    return len(rows)


# ---------------------------------------------------------------- 4) 孤儿通知

def orphan_notifications():
    rows = qn(
        "SELECT n.id, n.receiver_id, n.biz_id, n.title "
        "FROM notification n "
        "WHERE n.deleted=0 AND n.biz_type='document' "
        "AND NOT EXISTS (SELECT 1 FROM document d WHERE d.id = n.biz_id) "
        "ORDER BY n.id"
    )
    return [l.split('\t') for l in rows.split('\n') if l.strip()]


def drop_orphan_notifications(rows):
    for nid, rid, biz, title in rows:
        exec_(f"DELETE FROM notification WHERE id={nid}")
        print(f"    - 已移除孤儿通知 #{nid} (receiver={rid} 指向已删单据 biz_id={biz} 「{title}」)")
    return len(rows)


# ---------------------------------------------------------------- main

def main():
    apply_ = '--apply' in sys.argv
    if not apply_:
        print('（dry-run 模式；加 --apply 才会真的删）\n')

    print('=' * 74)
    print('演示库污染清理')
    print('=' * 74)
    before = snapshot()
    show('清理前', before)
    print()

    # 1) 死部署
    print('一、无配置支撑的流程部署（探针/旧测试残骸）')
    dead = dead_deployments()
    if not dead:
        print('    无需清理')
    else:
        for it in dead:
            print(f"    · {it['key']} v{it['ver']}  deployment={it['deployment_id']}  "
                  f"实例(ru/hi)={it['ru']}/{it['hi']}")
        if apply_:
            n = drop_deployments(dead)
            print(f'  已移除 {n} 个死部署')
    print()

    # 2) 孤儿实例
    print('二、孤儿流程实例（单据已删、实例还在）')
    orph = orphan_proc_insts()
    if not orph:
        print('    无需清理')
    elif apply_:
        n = drop_orphan_instances(orph)
        print(f'  已移除 {n} 个孤儿实例')
    else:
        for pid, bkey, pdef in orph:
            print(f"    · {pid}  business_key={bkey or '(空)'}  def={pdef}")
    print()

    # 3) 孤儿通知
    print('三、孤儿通知（biz_type=document 且单据已删）')
    orph_n = orphan_notifications()
    if not orph_n:
        print('    无需清理')
    elif apply_:
        n = drop_orphan_notifications(orph_n)
        print(f'  已移除 {n} 条孤儿通知')
    else:
        per = {}
        for _, rid, _, _ in orph_n:
            per[rid] = per.get(rid, 0) + 1
        print(f'    共 {len(orph_n)} 条，按接收人：' +
              '、'.join(f'receiver={k}×{v}' for k, v in sorted(per.items())))
    print()

    if apply_:
        print('=' * 74)
        after = snapshot()
        show('清理后', after)
        print('=' * 74)

        # 复核：流程定义必须与 flow_config 一一对应，且无孤儿
        cfg = qn('SELECT COUNT(*) FROM flow_config WHERE deleted=0')
        leak = dead_deployments()
        ok = (not leak) and after['orphan_hi_inst'] == '0' and after['orphan_notify'] == '0' \
            and after['ACT_RE_PROCDEF'] == cfg
        print(f"  flow_config 有效配置数 = {cfg}；ACT_RE_PROCDEF = {after['ACT_RE_PROCDEF']}")
        print(f"  残留死部署 = {len(leak)}；孤儿流程实例 = {after['orphan_hi_inst']}；"
              f"孤儿通知 = {after['orphan_notify']}")
        print()
        print('结果：' + ('PASS 演示库已收敛到基线' if ok else 'FAIL 仍有残留，见上文清单'))
        return 0 if ok else 1

    print('（dry-run 结束，未改动任何数据）')
    return 0


if __name__ == '__main__':
    sys.exit(main())

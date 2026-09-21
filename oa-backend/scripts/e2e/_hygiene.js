/* ============================================================================
 * E2E 共用「演示数据卫生」模块
 * ----------------------------------------------------------------------------
 * 为什么要有这个文件：曾经三个 E2E 各自复制粘贴一份 sqlSafe / snapshot，
 * 结果快照字段悄悄漂移（看板脚本多了 document_link、附件脚本少一段），
 * 按正则写的断言 match 返回 null 就**静默跳过**，少跑一条还显示全绿。
 * 所以快照格式只能有一处定义 —— 就是这个文件。
 *
 * 使用：
 *   const H = require('./_hygiene.js');
 *   const before = H.snapshot('跑测试前');
 *   ... 跑用例 ...
 *   const after = H.snapshot('清理后');
 *   check('快照一致', after === before, ...);
 *   H.assertInvariants(after, check);      // 绝对不变量（不靠前后对比）
 * ==========================================================================*/

const { execFileSync } = require('child_process');

const DB = 'haixiajin_oa';

/** 执行 SQL，失败抛错。 */
function sql(stmt) {
  return execFileSync('mysql', ['-uroot', DB, '-N', '-B', '-e', stmt], { encoding: 'utf8' }).trim();
}

/** 执行 SQL，失败时把错误当字符串返回（用于快照，避免一次失败打断整个用例）。 */
function sqlSafe(stmt) {
  try { return sql(stmt); } catch (e) { return '(sql-error: ' + e.message.split('\n')[0] + ')'; }
}

function uidOf(account) {
  return sqlSafe(`SELECT id FROM sys_user WHERE account='${account}' AND deleted=0`);
}

/* ---------------------------------------------------------------- 快照
 * 字段顺序即「规范」，任何脚本都不许自定义顺序 —— 新增指标一律加在这里。
 * 指标分两类：
 *   ① 计数：document / attachment / notification / document_link / ACT_*
 *   ② 不变量：orphan_hi_inst、orphan_notify、ACT_RE_PROCDEF==flow_config
 *      —— 只有 ② 是绝对断言，因为 before/after 对比证明不了基线本身干净
 *         （before=after=脏 时对比照样「一致」，历史上 orphan_notify=7 就是这么藏住的）。
 */
function snapshot(label) {
  const cfg = sqlSafe('SELECT COUNT(*) FROM flow_config WHERE deleted=0');
  const line = 'document=' + sqlSafe('SELECT COUNT(*) FROM document WHERE deleted=0')
    + ' attachment=' + sqlSafe('SELECT COUNT(*) FROM attachment WHERE deleted=0')
    + ' notification=' + sqlSafe('SELECT COUNT(*) FROM notification WHERE deleted=0')
    + ' notify_unread=' + sqlSafe('SELECT COUNT(*) FROM notification WHERE deleted=0 AND is_read=0')
    + ' document_link=' + sqlSafe('SELECT COUNT(*) FROM document_link')
    + ' ACT_RU_TASK=' + sqlSafe('SELECT COUNT(*) FROM ACT_RU_TASK')
    + ' ACT_RU_EXECUTION=' + sqlSafe('SELECT COUNT(*) FROM ACT_RU_EXECUTION')
    + ' ACT_HI_PROCINST=' + sqlSafe('SELECT COUNT(*) FROM ACT_HI_PROCINST')
    + ' ACT_RE_PROCDEF=' + sqlSafe('SELECT COUNT(*) FROM ACT_RE_PROCDEF')
    + '(flow_config=' + cfg + ')'
    + ' orphan_hi_inst=' + sqlSafe('SELECT COUNT(*) FROM ACT_HI_PROCINST p '
      + 'LEFT JOIN document d ON d.doc_no=p.BUSINESS_KEY_ WHERE d.id IS NULL')
    + ' orphan_notify=' + sqlSafe("SELECT COUNT(*) FROM notification n WHERE n.deleted=0 "
      + "AND n.biz_type='document' AND NOT EXISTS (SELECT 1 FROM document d WHERE d.id=n.biz_id)");
  if (label) console.log('  [快照·' + label + '] ' + line);
  return line;
}

/** 从快照串里取某个指标的值。取不到返回 null（调用方应显式断言，不要 if 守卫静默跳过）。 */
function field(line, name) {
  const m = new RegExp('(?:^|\\s)' + name + '=(-?\\d+)').exec(' ' + line);
  return m ? m[1] : null;
}

/**
 * 绝对不变量断言 —— 与「前后一致」无关，任何一次快照都必须成立。
 * 这几条才是真正能抓到污染的东西。
 */
function assertInvariants(line, check) {
  const orphanNote = field(line, 'orphan_notify');
  const orphanHi = field(line, 'orphan_hi_inst');
  const defs = field(line, 'ACT_RE_PROCDEF');
  const cfgM = /ACT_RE_PROCDEF=(\d+)\(flow_config=(\d+)\)/.exec(line);
  const cfg = cfgM ? cfgM[2] : null;

  check('无孤儿通知（指向已删单据的通知）', orphanNote === '0', 'orphan_notify=' + orphanNote);
  check('无孤儿流程实例', orphanHi === '0', 'orphan_hi_inst=' + orphanHi);
  check('快照含 (flow_config=) 段（保证下一条断言真的执行）', !!cfgM, cfgM ? 'ok' : '快照格式缺段');
  check('流程定义数与有效流程配置一一对应（不留死部署）', cfgM && defs === cfg,
    'ACT_RE_PROCDEF=' + defs + ' flow_config=' + cfg);
}

/* ---------------------------------------------------- 通知已读态：快照 / 还原
 * 通知抽屉用例会点「全部已读」，那会**永久**改掉 is_read。
 * 演示库的未读数是菜单角标的真值，改完不还原 = 污染演示状态。
 * 所以：跑前记下哪些是已读，跑后先全置 0 再把已读的置回 1（精确还原）。
 */
function grabReadIds(receiverId) {
  const rows = sqlSafe(`SELECT id FROM notification WHERE receiver_id=${receiverId} `
    + 'AND deleted=0 AND is_read=1');
  if (!rows || rows.startsWith('(sql-error')) return '';
  return rows.split(/\s+/).filter(Boolean).join(',');
}

function restoreReadState(receiverId, readIdsCsv) {
  sqlSafe(`UPDATE notification SET is_read=0 WHERE receiver_id=${receiverId} AND deleted=0`);
  if (readIdsCsv) {
    sqlSafe(`UPDATE notification SET is_read=1 WHERE receiver_id=${receiverId} `
      + `AND deleted=0 AND id IN (${readIdsCsv})`);
  }
  const now = grabReadIds(receiverId);
  return now === readIdsCsv;
}

function unreadOf(receiverId) {
  return sqlSafe(`SELECT COUNT(*) FROM notification WHERE receiver_id=${receiverId} `
    + 'AND deleted=0 AND is_read=0');
}

module.exports = {
  DB, sql, sqlSafe, uidOf,
  snapshot, field, assertInvariants,
  grabReadIds, restoreReadState, unreadOf,
};

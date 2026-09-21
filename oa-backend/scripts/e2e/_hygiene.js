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

/* ============================================================================
 * 前置自检（preflight）—— 为什么要在 require 时就做
 * ----------------------------------------------------------------------------
 * 这一天内被两个"环境问题"各坑了一次，而两次的表现都不是"环境报错"，
 * 而是**一堆看不懂的用例失败**，排查方向完全跑偏：
 *
 *  ① 「假活」：服务正在从某个 fat jar 跑时那个 jar 被重新构建覆盖了。
 *     症状是 /api/** 全部毫秒级正常，唯独**静态资源 0 字节响应、curl 挂到超时**。
 *     套件报的是 `fetch failed`，看着像网络问题，实际要先看应用日志里的
 *     NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy。
 *
 *  ② 「盯错库」：本机后端用 --spring.profiles.active=test 起（连远端 hxj-oa），
 *     而本文件原先写死本机 haixiajin_oa。两个库不是同一个 ⇒ 快照"前后一致"
 *     恒成立、不变量恒成立，**污染一条也检测不出来，输出却是全绿**。
 *
 * 所以这两条都必须在**开跑前**判掉、并且判据要写人话（直接给出修法），
 * 而不是让它们伪装成用例失败。用 OA_E2E_SKIP_PREFLIGHT=1 可跳过。
 * ==========================================================================*/

/* ------------------------------------------------------------ 监控库目标
 * 顺序：OA_E2E_MYSQL_* → MYSQL_*（与后端同源，最理想）→ 本机默认。
 * 目标每次都会打印 —— 让"盯错库"变成看得见的事，而不是靠人记得。
 */
function resolveTarget() {
  const e = process.env;
  return {
    host: e.OA_E2E_MYSQL_HOST || e.MYSQL_HOST || '127.0.0.1',
    port: String(e.OA_E2E_MYSQL_PORT || e.MYSQL_PORT || '3306'),
    database: e.OA_E2E_MYSQL_DATABASE || e.MYSQL_DATABASE || 'haixiajin_oa',
    user: e.OA_E2E_MYSQL_USER || e.MYSQL_USER || 'root',
    password: e.OA_E2E_MYSQL_PASSWORD || e.MYSQL_PASSWORD || '',
    explicit: !!(e.OA_E2E_MYSQL_HOST || e.MYSQL_HOST),
  };
}
const TARGET = resolveTarget();
const DB = TARGET.database;
const BASE = process.env.OA_E2E_BASE || 'http://127.0.0.1:8080';

function mysqlEnv() {
  // 用 MYSQL_PWD 而不是 -p<口令>：后者会在 stderr 打"命令行里有口令不安全"的告警，
  // 混进测试输出里很干扰；而且口令会出现在进程列表里。
  return TARGET.password ? Object.assign({}, process.env, { MYSQL_PWD: TARGET.password }) : process.env;
}

function mysqlArgs(stmt) {
  return ['-h', TARGET.host, '-P', TARGET.port, '-u', TARGET.user, DB, '-N', '-B', '-e', stmt];
}

/** 执行 SQL，失败抛错。 */
function sql(stmt) {
  return execFileSync('mysql', mysqlArgs(stmt), { encoding: 'utf8', env: mysqlEnv() }).trim();
}

/** 执行 SQL，失败时把错误当字符串返回（用于快照，避免一次失败打断整个用例）。 */
function sqlSafe(stmt) {
  try { return sql(stmt); } catch (e) { return '(sql-error: ' + e.message.split('\n')[0] + ')'; }
}

/** 用 curl 探一个地址，返回 {code, size}；网络层失败（超时/拒绝）抛错。 */
function probe(url, timeoutSec) {
  const out = execFileSync('curl', [
    '-s', '-o', '/dev/null', '-m', String(timeoutSec), '--noproxy', '*',
    '-w', '%{http_code} %{size_download}', url,
  ], { encoding: 'utf8' });
  const [code, size] = out.trim().split(/\s+/);
  return { code: code, size: Number(size) };
}

function preflight() {
  console.log('');
  console.log('  [前置自检] 被测地址 ' + BASE);
  console.log('  [前置自检] 监控库  ' + TARGET.host + ':' + TARGET.port + '/' + DB
    + (TARGET.explicit ? '（来自 MYSQL_* 环境变量）' : '（未设置 MYSQL_*，用本机默认）'));
  console.log('  [前置自检] 上述库必须是**被测后端实际写入**的那个库，否则卫生断言恒等于"没变化"。');

  // ① 监控库要连得上
  try {
    sql('SELECT 1');
  } catch (e) {
    console.error('\n✗ 连不上监控库 ' + TARGET.host + ':' + TARGET.port + '/' + DB);
    console.error('  卫生断言会全部失去意义，所以直接停在这里而不是继续跑出一堆假结果。');
    console.error('  ' + e.message.split('\n')[0]);
    console.error('  若本机后端是用 MYSQL_* 环境变量（docker-test/.env）起的，'
      + '请把这些变量也导出给本脚本，或用 OA_E2E_MYSQL_* 指定。');
    process.exit(2);
  }

  // ② 后端要既答 API、又能开页面 —— 这一条专门用来拆穿"假活"
  //    必须把三种情形分开报，因为它们修法完全不同：
  //      · 两个都不通 → 后端没起来（最常见，也最好修）
  //      · API 通、页面不通 → **假活**（jar 被覆盖），报错最难懂的那种
  //      · 页面通、API 不通 → 说明静态资源在、接口链坏了，另一类问题
  const ping = probeQuiet(BASE + '/api/ping', 5);
  const page = probeQuiet(BASE + '/oa.html', 8);
  const pingOk = !!ping && ping.code === '200';
  const pageOk = !!page && page.code === '200' && page.size > 100000;

  if (pageOk && pingOk) {
    console.log('  [前置自检] ✓ API 与页面均可访问（/oa.html ' + page.size + ' 字节）');
    console.log('');
    return;
  }

  console.error('');
  if (!pingOk && !pageOk) {
    console.error('✗ 后端没有起来：' + BASE + '（/api/ping 与 /oa.html 都不通）');
    console.error('  最常见的原因就是没启动。启动：cd 项目根 && NO_OPEN=1 bash 启动联调版.command');
    console.error('  注意："/api/ping 通了"才叫起来，光看端口有人监听不算（端口可能被别的进程占着）。');
  } else if (pingOk && !pageOk) {
    console.error('✗ 后端在应答 API，但**没有提供页面**'
      + (page ? '（/oa.html HTTP ' + page.code + '，' + page.size + ' 字节）' : '（/oa.html 请求无响应/超时）'));
    console.error('  判据：/api/ping 正常，而 /oa.html 不通 —— 这是典型的「假活」。');
    console.error('  原因：**服务正在运行时，它依赖的那个 fat jar 被重新构建覆盖了**。');
    console.error('  此时 JVM 惰性加载类会失败，日志里是 '
      + 'NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy；');
    console.error('  表现就是"TCP 连得上、API 秒回、静态资源永久挂住（0 字节响应）"。');
    console.error('  修法：停掉并重启本机后端，没有任何热修办法。日志：oa-backend/logs/app-test.log');
  } else {
    console.error('✗ 页面能打开，但 /api/ping 不通'
      + (ping ? '（HTTP ' + ping.code + '）' : '（无响应）'));
    console.error('  静态页在、接口链不通 —— 看后端日志里的启动异常与应用上下文是否完整。');
    console.error('  日志：oa-backend/logs/app-test.log');
  }
  process.exit(2);
}

/** 探测但不抛错：失败返回 null，交给调用方按情形分派提示。 */
function probeQuiet(url, timeoutSec) {
  try { return probe(url, timeoutSec); } catch (e) { return null; }
}

if (process.env.OA_E2E_SKIP_PREFLIGHT !== '1') {
  preflight();
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
  DB, BASE, TARGET, preflight,
  sql, sqlSafe, uidOf,
  snapshot, field, assertInvariants,
  grabReadIds, restoreReadState, unreadOf,
};

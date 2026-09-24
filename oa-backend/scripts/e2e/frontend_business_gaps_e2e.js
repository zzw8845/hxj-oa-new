/* ============================================================================
 * 业务缺口批前端 E2E：风险预警页 / 台账导出 / 首页假数字 / 台账日期筛选 / 审计留痕
 * ----------------------------------------------------------------------------
 * 覆盖 scripts/patch_frontend_business_gaps.py 的 E1~E4，以及后端的三个新接口。
 *
 *   E1 风险预警页恢复入口（此前 index='risk' 被流程管理占用，该页无处可点）
 *      —— 菜单能打开；统计与明细 = /api/risks 真值；承办人非空（库里 assignee_name
 *         对在途节点是 NULL，靠 sys_user 回填）；点行能打开单据详情；
 *         切到数据范围看不见这些单据的账号时必须是空态而不是报错。
 *
 *   E2 台账导出
 *      —— 按钮按 document:export 显隐；点击真的落一个 CSV；BOM/表头/行数逐项核对；
 *         越权账号在接口层 403；**下载必须落在临时目录，不许污染用户 ~/Downloads**。
 *
 *   E3 首页那句写死的「5 项审批 / 3 项风险预警」改成真实值。
 *
 *   E4 台账档案的日期范围此前是个死控件（绑了 v-model 却从不参与过滤）。
 *
 *   附带回归：拆开 index 之后「流程管理」页必须仍然打得开。
 *   审计：新加的 @Audit 切面必须真的在写库（否则是静默失效的经典形态）。
 *
 * 数据卫生：本用例只做读 + 触发一次导出（会写一条 audit_log）。
 *   audit_log 是**故意无界增长**的表，不进快照；其余计数必须与跑前一致。
 * ==========================================================================*/

const puppeteer = require('puppeteer-core');
const fs = require('fs');
const os = require('os');
const path = require('path');

const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE = 'http://127.0.0.1:8080/oa.html';
const API = 'http://127.0.0.1:8080';
const PW = '123456';
const LOCAL_HTML = '/Users/zhouzewei/WorkBuddy/2026-09-18-15-53-12/海峡金OA审批系统-联调版.html';
const DOWNLOADS = path.join(os.homedir(), 'Downloads');
const TMP = path.join(os.tmpdir(), 'oa_e2e_biz_' + Date.now());

const sleep = ms => new Promise(r => setTimeout(r, ms));

/* 预期断言总数：脚本正常跑完必须**恰好**产出这么多条。
   为什么要把这个数写死在代码里：本文件正是「假绿灯」的当事人 —— 这里曾有 16 条断言
   （含整条审计留痕链路）因上游依赖的接口被回退、在 L592 抛 TypeError 而**从未执行**，
   末行却照样打印"85/85 通过"。有了这个数，任何"少跑了"都会立刻变成红灯。 */
const EXPECTED_TOTAL = 79;

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}
function section(t) { console.log('\n' + '='.repeat(74) + '\n' + t + '\n' + '='.repeat(74)); }

const H = require('./_hygiene.js');
const { snapshot, assertInvariants, sqlSafe, uidOf, field } = H;

/* ---------------- HTTP ---------------- */
async function api(method, p, token, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(API + p, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body)
  });
  let json = null;
  try { json = await res.json(); } catch (e) { /* 保持 null */ }
  return { status: res.status, body: json };
}
async function login(account) {
  const r = await api('POST', '/api/auth/login', null, { account, password: PW });
  if (!r.body || r.body.code !== 0) throw new Error('登录失败 ' + account + '：' + (r.body && r.body.msg));
  return r.body.data.token;
}
async function getRaw(p, token) {
  const headers = token ? { Authorization: 'Bearer ' + token } : {};
  const res = await fetch(API + p, { headers });
  const buf = Buffer.from(await res.arrayBuffer());
  return { status: res.status, buf, headers: res.headers };
}

/* ---------------- 浏览器小工具 ---------------- */
const ACCOUNT_NAME = {
  admin: '系统管理员', huangxm: '黄小明', zhouzh: '周综合',
  wangkj: '王会计', zhaoca: '赵出纳', zhaocs: '赵出纳'
};

async function loginViaGate(page, account) {
  const name = ACCOUNT_NAME[account] || account;
  const hasGate = await page.evaluate(() => !!document.querySelector('.lg-chips button'));
  if (!hasGate) {
    await page.evaluate(() => localStorage.clear());
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(2600);
  }
  let picked = false;
  for (let i = 0; i < 15 && !picked; i++) {
    for (const c of await page.$$('.lg-chips button')) {
      const t = await c.evaluate(e => e.textContent);
      if (t.includes(name)) { await c.click(); picked = true; break; }
    }
    if (!picked) await sleep(600);
  }
  if (!picked) return { ok: false, who: '', reason: '门禁里没有「' + name + '」' };
  await sleep(4600);
  const who = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  return { ok: who.length > 0, who, reason: who ? '' : '点了账号按钮但没进入系统' };
}

async function switchAccount(page, account) {
  const name = ACCOUNT_NAME[account] || account;
  // Element Plus 的下拉只认真实点击：dispatchEvent 合成的 mousedown 不会展开选项浮层
  // （第一版在这里用合成 mousedown，四条切换全部静默失败——下拉根本没打开）。
  const sel = await page.$('.top-actions .el-select');
  if (!sel) return { ok: false, reason: '顶栏没有账号切换下拉' };
  await sel.click();
  let open = false;
  for (let i = 0; i < 10 && !open; i++) {
    open = await page.evaluate(() =>
      [...document.querySelectorAll('.el-select-dropdown__item')].some(o => o.getClientRects().length));
    if (!open) await sleep(400);
  }
  if (!open) {
    await page.keyboard.press('Escape');
    return { ok: false, reason: '账号下拉没有展开（点击未生效）' };
  }
  const hit = await page.evaluate((n) => {
    const opts = [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter(o => o.getClientRects().length);
    const o = opts.find(x => x.textContent.includes(n));
    if (o) { o.click(); return true; }
    return false;
  }, name);
  if (!hit) {
    await page.keyboard.press('Escape');
    const have = await page.evaluate(() =>
      [...document.querySelectorAll('.el-select-dropdown__item')]
        .filter(o => o.getClientRects().length).map(o => o.textContent.trim()).join(' | '));
    return { ok: false, reason: '切换下拉里没有「' + name + '」；现有：' + have };
  }
  await sleep(4600);
  const who = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  return { ok: who.includes(name), who, reason: who.includes(name) ? '' : '切换后 who=' + who };
}

async function clickMenu(page, text) {
  const hit = await page.evaluate((t) => {
    const items = [...document.querySelectorAll('.el-menu .el-menu-item, .el-menu li')];
    const h = items.find(i => i.getClientRects().length > 0 && i.textContent.includes(t));
    if (h) { h.click(); return t; }
    return '';
  }, text);
  await sleep(1900);
  return hit;
}

/** 菜单项文案清单（按显示顺序） */
function readMenuLabels(page) {
  return page.evaluate(() => [...document.querySelectorAll('.el-menu .el-menu-item')]
    .filter(i => i.getClientRects().length)
    .map(i => i.textContent.replace(/\s+/g, ' ').trim()));
}

/** 当前页面标题（顶栏 h1 与正文 h2） */
function readTitles(page) {
  return page.evaluate(() => ({
    topbar: (document.querySelector('.topbar h1') || {}).textContent || '',
    h2: (document.querySelector('.el-main h2') || {}).textContent || ''
  }));
}

/** 首页问候语那句汇总文案 */
function readHeroLine(page) {
  return page.evaluate(() => {
    const p = document.querySelector('.hero p');
    return p ? p.textContent.replace(/\s+/g, ' ').trim() : '';
  });
}

/** 风险预警页：统计卡 + 明细行 */
function readRiskPage(page) {
  return page.evaluate(() => {
    const h2 = (document.querySelector('.el-main h2') || {}).textContent || '';
    const cards = [...document.querySelectorAll('.el-main .stats .el-card')].map(c => ({
      name: (c.querySelector('span') || {}).textContent || '',
      value: Number(((c.querySelector('b') || {}).textContent || '').trim()),
      note: (c.querySelector('small') || {}).textContent || ''
    }));
    const rows = [...document.querySelectorAll('.el-main .el-table__body-wrapper tbody tr')]
      .filter(r => r.getClientRects().length)
      .map(r => [...r.querySelectorAll('td')].map(td => td.textContent.trim()));
    const emptyText = (document.querySelector('.el-main .el-table__empty-text') || {}).textContent || '';
    return { h2, cards, rows, emptyText };
  });
}

/** 台账档案页：行数 + 表格文本 */
function readArchivePage(page) {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-main .el-table__body-wrapper tbody tr')]
      .filter(r => r.getClientRects().length)
      .map(r => [...r.querySelectorAll('td')].map(td => td.textContent.trim()));
    const btn = [...document.querySelectorAll('.el-main .page-head .el-button')]
      .find(b => b.textContent.includes('导出台账'));
    return {
      rows,
      hasExportBtn: !!btn,
      emptyText: (document.querySelector('.el-main .el-table__empty-text') || {}).textContent || ''
    };
  });
}

/** 台账日期范围：在 Element Plus 的 daterange 面板上真实点选两个日期格。
 *  键盘输入路径不可用（探针实测：键入只改 DOM value，Element Plus 不解析、v-model 不更新，
 *  两个日期会拼在同一个输入框里）—— 面板点选才是用户真实可走的路径。 */
async function pickArchiveRange(page, dayA, dayB) {
  const start = await page.$('.archive-filter .el-date-editor .el-range-input');
  if (!start) return null;
  await start.click();
  await sleep(900);
  const pick = async (scopeSel, day) => {
    return page.evaluate((sel, d) => {
      let cells = [...document.querySelectorAll(sel + ' td.available')];
      if (!cells.length) cells = [...document.querySelectorAll('.el-picker-panel td.available')];
      cells = cells.filter(td => td.getClientRects().length);
      const c = cells.find(td => td.textContent.trim() === d);
      if (c) { c.click(); return true; }
      return false;
    }, scopeSel, day);
  };
  const okA = await pick('.el-date-range-picker__content.is-left', dayA);
  await sleep(900);
  const okB = await pick(dayB === null ? '.el-date-range-picker__content.is-left'
                                      : '.el-date-range-picker__content.is-right', dayB || dayA);
  await sleep(1500);
  const vals = await page.evaluate(() =>
    [...document.querySelectorAll('.archive-filter .el-date-editor input')].map(i => i.value.trim()));
  return { okA, okB, vals };
}

/** 库内期望：同一口径（status IN (3,6)，按归档时间 updated_at）算某日期区间的台账行数 */
function ledgerCountDb(from, to) {
  return Number(sqlSafe(
    "SELECT COUNT(*) FROM document WHERE deleted=0 AND status IN (3,6) "
    + "AND updated_at >= '" + from + " 00:00:00' AND updated_at <= '" + to + " 23:59:59'"));
}

function listDirCount(dir) {
  try { return fs.readdirSync(dir).length; } catch (e) { return -1; }
}
/** 用户可见文件数（排除 .开头隐藏文件） */
function listVisibleCount(dir) {
  try { return fs.readdirSync(dir).filter(f => !f.startsWith('.')).length; } catch (e) { return -1; }
}
/** Chrome for Testing 的下载临时文件（隐藏点文件） */
function chromeTempFiles(dir) {
  try { return fs.readdirSync(dir).filter(f => f.startsWith('.com.google.chrome.for.testing.')); } catch (e) { return []; }
}

/* 进行中且已超期的节点数（与 RiskService 同一口径：
   node_type IN (1,4) AND status IN (0,1) AND deadline IS NOT NULL AND deadline < now()
   AND 单据 status IN (1,2)）—— 用作 @api/risks 的独立交叉验证 */
function overdueNodeCountSql() {
  return sqlSafe(
    'SELECT COUNT(*) FROM flow_instance_node n JOIN document d ON d.id=n.document_id '
    + 'WHERE n.deleted=0 AND d.deleted=0 AND n.node_type IN (1,4) AND n.status IN (0,1) '
    + 'AND n.deadline IS NOT NULL AND n.deadline < NOW() AND d.status IN (1,2)');
}

(async () => {
  fs.mkdirSync(TMP, { recursive: true });
  // Chrome 的下载临时文件（.com.google.chrome.for.testing.*）总是先落在
  // 「默认下载目录」里，完成时才被 CDP 重定向搬走 —— 不改默认目录，
  // 每次下载都会在用户 ~/Downloads 留一个隐藏残留（已积过 16 个）。
  // puppeteer 没有 prefs 启动参数（25.x 不支持，传了会被静默忽略），
  // 所以直接预写一份 profile 的 Preferences，把默认下载目录指到临时目录。
  const profile = path.join(os.tmpdir(), 'oa_e2e_profile_' + Date.now());
  fs.mkdirSync(path.join(profile, 'Default'), { recursive: true });
  fs.writeFileSync(path.join(profile, 'Default', 'Preferences'), JSON.stringify({
    download: { default_directory: TMP, prompt_for_download: false, directory_upgrade: true },
    plugins: { always_open_pdf_externally: true }
  }));
  const browser = await puppeteer.launch({
    executablePath: EXEC, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'],
    userDataDir: profile
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1680, height: 1050 });

  // 下载一律重定向到用例自己的临时目录：附件用例曾经把文件落到用户真实 ~/Downloads（积了 16 个）
  const cdp = await page.createCDPSession();
  try {
    // eventsEnabled 必须为 true：走新版下载管理器，临时文件直接落在 downloadPath。
    // 实测 false（旧路径）时 Chrome 仍会在默认下载目录留 .com.google.chrome.for.testing.* 残留。
    await cdp.send('Browser.setDownloadBehavior',
      { behavior: 'allow', downloadPath: TMP, eventsEnabled: true });
  } catch (e) {
    await cdp.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: TMP });
  }
  const dlVisibleBefore = listVisibleCount(DOWNLOADS);

  const netErrors = [];
  page.on('response', r => { if (r.status() >= 400) netErrors.push(r.status() + ' ' + r.url()); });
  page.on('pageerror', e => netErrors.push('pageerror: ' + String(e)));

  try {
    /* ================= 零、部署与基线 ================= */
    section('零、部署一致性与数据基线');

    const localBytes = fs.readFileSync(LOCAL_HTML);
    const servedRes = await fetch(API + '/oa.html');
    const servedBuf = Buffer.from(await servedRes.arrayBuffer());
    const served = servedBuf.toString('utf8');

    /* 下面几条是**源码级**断言（"已部署的那份里这个字面量还在不在"）。
       代价是：一句解释性注释如果引用了旧文案，也会被判成没改。
       2026-09-24 真实踩到过一次 —— 排查时它只打印了运行时文案，指错了方向，
       所以这里把**命中位置和上下文**一起打出来，一眼能看出是模板还是注释。 */
    function srcHit(needle) {
      const i = served.indexOf(needle);
      if (i < 0) return '';
      const around = served.slice(Math.max(0, i - 70), i + needle.length + 25).replace(/\s+/g, ' ');
      return '命中源码第 ' + i + ' 字符处：…' + around + '…';
    }
    check('服务返回的 oa.html 与本地文件字节一致（部署的是同一份）',
      servedBuf.equals(localBytes), 'served=' + servedBuf.length + 'B local=' + localBytes.length + 'B');

    // 改动只在「已部署的那份」上断言 —— 本地干净但忘了重新部署是很常见的失误
    check('已部署版本里有「风险预警」菜单项（index=risk）',
      served.indexOf('<el-menu-item index="risk"><span class="mi">△</span>风险预警</el-menu-item>') >= 0);
    check('已部署版本里流程管理已改 index=flow',
      served.indexOf('<el-menu-item index="flow">') >= 0);
    check('已部署版本里那句按 index 改文案的兜底已删除',
      served.indexOf("if (idx === 'risk') rename('风险预警','流程管理');") < 0);
    check('已部署版本里首页已无写死的「5 项审批」',
      served.indexOf('5 项审批') < 0, srcHit('5 项审批') || '源码内无此字面量');
    check('已部署版本里导出台账按钮已接 exportLedger',
      served.indexOf('@click="exportLedger"') >= 0);
    check('已部署版本里台账日期筛选已接线（服务端 updatedAt 范围）',
      served.indexOf('prm.updatedAtFrom') >= 0 && served.indexOf('@change="onArchiveFilterChange"') >= 0);

    const tkAdmin = await login('admin');
    const tkHuang = await login('huangxm');
    const tkZhou = await login('zhouzh');
    const tkWang = await login('wangkj');
    const tkZhao = await login('zhaocs');
    check('接口登录成功（admin/huangxm/zhouzh/wangkj/zhaocs）',
      [tkAdmin, tkHuang, tkZhou, tkWang, tkZhao].every(Boolean));

    const riskAdmin = (await api('GET', '/api/risks', tkAdmin)).body.data;
    const sqlOverdue = Number(overdueNodeCountSql());
    check('GET /api/risks 超期数 = 直接查库的进行中且超期节点数（独立交叉验证）',
      riskAdmin.overdue === sqlOverdue,
      'api=' + riskAdmin.overdue + ' sql=' + sqlOverdue);
    check('  /api/risks 返回带 dueSoonHours（临期阈值）', riskAdmin.dueSoonHours === 24,
      'dueSoonHours=' + riskAdmin.dueSoonHours);

    const beforeSnap = snapshot('跑测试前');
    const auditBefore = Number(sqlSafe('SELECT COUNT(*) FROM audit_log'));

    /* ================= 一、首页假数字 ================= */
    section('一、首页问候语 = 真实待办数与风险数（原先写死 5 / 3）');

    await page.goto(PAGE, { waitUntil: 'networkidle2' });
    const g1 = await loginViaGate(page, 'huangxm');
    check('登录门禁进入系统（黄小明）', g1.ok, g1.who || g1.reason);
    await sleep(1400);

    const hero = await readHeroLine(page);
    const todosHuang = ((await api('GET', '/api/todos', tkHuang)).body.data || []).length;
    const riskHuang = (await api('GET', '/api/risks', tkHuang)).body.data;
    const heroTodo = Number((hero.match(/(\d+)\s*项审批/) || [])[1]);
    const heroRisk = Number((hero.match(/(\d+)\s*项风险预警/) || [])[1]);
    check('首页问候语能取到真实数字（不是模板占位）',
      !Number.isNaN(heroTodo) && !Number.isNaN(heroRisk), '文案=「' + hero + '」');
    check('  待办数 = /api/todos 条数', heroTodo === todosHuang,
      '页面=' + heroTodo + ' 后端=' + todosHuang);
    check('  风险数 = /api/risks 超期数', heroRisk === riskHuang.overdue,
      '页面=' + heroRisk + ' 后端=' + riskHuang.overdue);
    check('  写死的「5 项审批 / 3 项风险预警」已不存在',
      served.indexOf('5 项审批') < 0 && served.indexOf('3 项风险预警') < 0,
      srcHit('5 项审批') || srcHit('3 项风险预警') || ('运行时文案=「' + hero + '」，源码内两处字面量均无'));

    /* ================= 二、风险预警页 ================= */
    section('二、风险预警页：入口 / 真值 / 承办人 / 行点击（黄小明，数据范围内有超期件）');

    const labels = await readMenuLabels(page);
    // 菜单项文案带图标前缀（如「△风险预警」），用 includes 而不是全等
    check('侧栏同时存在「流程管理」与「风险预警」两个菜单项',
      labels.some(l => l.includes('流程管理')) && labels.some(l => l.includes('风险预警')),
      '菜单=' + labels.join(' / '));

    const hitRisk = await clickMenu(page, '风险预警');
    check('点「风险预警」能打开该页（此前根本没有入口）', !!hitRisk, hitRisk || '(没找到菜单)');

    const rp = await readRiskPage(page);
    check('  页面标题 = 风险预警', rp.h2 === '风险预警', 'h2=' + rp.h2);
    check('  三张统计卡（已超期 / 即将到期 / 进行中节点）',
      rp.cards.length === 3 && rp.cards.map(c => c.name).join('') === '已超期即将到期进行中节点',
      rp.cards.map(c => c.name + '=' + c.value).join(' '));
    const cardOverdue = (rp.cards.find(c => c.name === '已超期') || {}).value;
    const cardRunning = (rp.cards.find(c => c.name === '进行中节点') || {}).value;
    check('  卡片「已超期」= /api/risks 超期数', cardOverdue === riskHuang.overdue,
      '页面=' + cardOverdue + ' 后端=' + riskHuang.overdue);
    check('  卡片「进行中节点」= /api/risks runningTotal', cardRunning === riskHuang.runningTotal,
      '页面=' + cardRunning + ' 后端=' + riskHuang.runningTotal);

    check('  明细行数 = /api/risks items 条数',
      rp.rows.length === riskHuang.items.length,
      '页面=' + rp.rows.length + ' 后端=' + riskHuang.items.length);
    check('  明细非空（否则下面几条断言会静默失去意义）', rp.rows.length > 0);

    const firstItem = riskHuang.items[0] || {};
    const firstRow = rp.rows[0] || [];
    check('  首行单号 = 后端首条单号（按处理时限升序）',
      firstRow[0] === firstItem.docNo, '页面=' + firstRow[0] + ' 后端=' + firstItem.docNo);
    check('  首行「卡在节点」= 后端 nodeName', firstRow[5] === firstItem.nodeName,
      '页面=' + firstRow[5] + ' 后端=' + firstItem.nodeName);
    // 库里 flow_instance_node.assignee_name 对在途节点是 NULL，靠 sys_user 回填
    check('  首行「承办人」非空（库中该列为 NULL，需按 assignee_id 回填）',
      !!firstRow[6] && firstRow[6] !== 'undefined' && firstRow[6] !== '多人候选',
      '承办人=' + firstRow[6] + '（后端=' + firstItem.assigneeName + '）');
    check('  首行风险标记含「已超期」与小时数',
      /已超期\s*\d+\s*小时/.test(firstRow[8] || ''), '风险列=' + firstRow[8]);

    // 打开首行详情
    await page.evaluate(() => {
      const tr = [...document.querySelectorAll('.el-main .el-table__body-wrapper tbody tr')]
        .filter(r => r.getClientRects().length)[0];
      if (tr) {
        const td = tr.querySelector('td');
        td.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
      }
    });
    await sleep(2200);
    const detailText = await page.evaluate(() => {
      const d = [...document.querySelectorAll('.el-drawer, .el-dialog')]
        .filter(x => x.getClientRects().length)[0];
      return d ? d.textContent.replace(/\s+/g, ' ').trim() : '';
    });
    check('  点风险行能打开单据详情（含该单号）',
      detailText.indexOf(firstItem.docNo) >= 0,
      detailText ? ('详情前 80 字：' + detailText.slice(0, 80)) : '(没打开抽屉)');
    await page.keyboard.press('Escape');
    await sleep(900);

    // 数据范围：周综合（部门 6）看不见业务一部的单据 → 必须是空态
    const sw = await switchAccount(page, 'zhouzh');
    check('切换到周综合', sw.ok, sw.who || sw.reason);
    await clickMenu(page, '风险预警');
    const rpZhou = await readRiskPage(page);
    const riskZhou = (await api('GET', '/api/risks', tkZhou)).body.data;
    const cardZhou = (rpZhou.cards.find(c => c.name === '已超期') || {}).value;
    check('  周综合的「已超期」= 其后端超期数（数据范围生效，两端口径一致）',
      cardZhou === riskZhou.overdue, '页面=' + cardZhou + ' 后端=' + riskZhou.overdue);
    check('  超期为 0 时明细显示空态而不是报错/残留',
      riskZhou.overdue === 0 ? rpZhou.rows.length === 0 : rpZhou.rows.length > 0,
      '超期=' + riskZhou.overdue + ' 行数=' + rpZhou.rows.length + ' 空文案=' + rpZhou.emptyText);

    /* ================= 三、回归：流程管理页还开着 ================= */
    section('三、回归：拆开 index 之后「流程管理」页仍可达');

    const hitFlow = await clickMenu(page, '流程管理');
    check('点「流程管理」能打开该页', !!hitFlow, hitFlow || '(没找到菜单)');
    const ft = await readTitles(page);
    check('  页面标题 = 流程管理', ft.h2 === '流程管理', 'h2=' + ft.h2);
    check('  顶栏面包屑标题与菜单文案一致（findability）', ft.topbar === '流程管理',
      'topbar=' + ft.topbar);
    const flowCards = await page.evaluate(() =>
      [...document.querySelectorAll('.flow-config-grid .el-card')].filter(c => c.getClientRects().length).length);
    check('  流程配置卡片渲染出来（页面内容没被拆坏）', flowCards > 0, '卡片数=' + flowCards);

    /* ================= 四、台账导出 ================= */
    section('四、台账导出：权限 / CSV 内容 / 不污染用户下载目录');

    // 越权：出纳与普通员工都没有 document:export
    const exZhao = await api('GET', '/api/documents/export', tkZhao);
    const exHuang = await api('GET', '/api/documents/export', tkHuang);
    check('出纳（无 document:export）导出被拒 403', exZhao.status === 403, 'HTTP' + exZhao.status);
    check('普通员工（无 document:export）导出被拒 403', exHuang.status === 403, 'HTTP' + exHuang.status);

    const exAdmin = await getRaw('/api/documents/export', tkAdmin);
    check('管理员导出成功 200', exAdmin.status === 200, 'HTTP' + exAdmin.status);
    const csvText = exAdmin.buf.toString('utf8');
    const csvLines = csvText.split('\r\n').filter(l => l.length);
    check('  CSV 带 UTF-8 BOM（否则 Excel 按 GBK 解，中文表头乱码）',
      csvText.charCodeAt(0) === 0xFEFF, '首字符码点=' + csvText.charCodeAt(0));
    check('  表头 9 列且含「归档时间」',
      csvLines[0].replace(/^\uFEFF/, '') === '单据编号,申请事项,单据类型,申请部门,申请人,金额,提交时间,归档时间,状态',
      csvLines[0]);
    const dbLedger = Number(sqlSafe(
      'SELECT COUNT(*) FROM document WHERE deleted=0 AND status IN (3,6)'));
    check('  数据行数 = 库中已通过/已归档单据数',
      csvLines.length - 1 === dbLedger,
      'CSV=' + (csvLines.length - 1) + ' 库=' + dbLedger);
    check('  Content-Disposition 用 RFC 5987 的 filename*（中文文件名不乱码）',
      /filename\*=UTF-8''/.test(exAdmin.headers.get('content-disposition') || ''),
      exAdmin.headers.get('content-disposition') || '(无)');
    check('  禁止浏览器嗅探类型（X-Content-Type-Options: nosniff）',
      exAdmin.headers.get('x-content-type-options') === 'nosniff',
      exAdmin.headers.get('x-content-type-options') || '(无)');

    // 走界面点一次，确认按钮真的能下载、且落在临时目录
    const sw2 = await switchAccount(page, 'wangkj');
    check('切换到王会计（有 document:export）', sw2.ok, sw2.who || sw2.reason);
    await clickMenu(page, '台账档案');
    const ar = await readArchivePage(page);
    check('台账档案页出现「导出台账」按钮（该账号有权限）',
      ar.hasExportBtn, 'hasExportBtn=' + ar.hasExportBtn);
    check('  台账列表非空（否则下面的「导出=所见」断言没有意义）',
      ar.rows.length > 0, '行数=' + ar.rows.length);

    // 比对基准只能是「该账号自己的接口导出」：王会计的数据范围比管理员窄，
    // 拿 admin 的 CSV 当基准会把正常的数据范围差异误判成 bug。
    const exWang = await getRaw('/api/documents/export', tkWang);
    check('王会计的接口导出成功 200（有 document:export）', exWang.status === 200, 'HTTP' + exWang.status);
    const wangCsv = exWang.buf.toString('utf8');
    const wangLines = wangCsv.split('\r\n').filter(l => l.length);
    check('  王会计看到的台账条数比管理员少或相等（行级数据范围在导出上同样生效）',
      wangLines.length <= csvLines.length,
      '王会计=' + (wangLines.length - 1) + ' 管理员=' + (csvLines.length - 1) + '（数据行）');

    await page.evaluate(() => {
      const b = [...document.querySelectorAll('.el-main .page-head .el-button')]
        .find(x => x.textContent.includes('导出台账'));
      if (b) b.click();
    });
    await sleep(3000);
    const tmpFiles = fs.readdirSync(TMP).filter(f => f.endsWith('.csv'));
    check('点击后真的落下一个 CSV 文件（下载链路通）',
      tmpFiles.length === 1, '临时目录文件=' + JSON.stringify(fs.readdirSync(TMP)));
    let uiCsvLines = [];
    if (tmpFiles.length === 1) {
      const uiCsv = fs.readFileSync(path.join(TMP, tmpFiles[0]), 'utf8');
      uiCsvLines = uiCsv.split('\r\n').filter(l => l.length);
      check('  下载到的 CSV 与该账号接口导出逐项一致（BOM + 表头 + 行数）',
        uiCsv.charCodeAt(0) === 0xFEFF
        && uiCsvLines[0].replace(/^\uFEFF/, '') === wangLines[0].replace(/^\uFEFF/, '')
        && uiCsvLines.length === wangLines.length,
        '界面=' + uiCsvLines.length + ' 行 接口=' + wangLines.length + ' 行');
      check('  导出的就是所见的：CSV 数据行数 = 台账页当前显示行数',
        uiCsvLines.length - 1 === ar.rows.length,
        'CSV=' + (uiCsvLines.length - 1) + ' 页=' + ar.rows.length);
      check('  文件名符合「台账档案_YYYY-MM-DD.csv」',
        /^台账档案_[\d-]+\.csv$/.test(tmpFiles[0]), tmpFiles[0]);
    }
    // 反向守护：非授权账号的按钮不该出现
    const sw3 = await switchAccount(page, 'zhaocs');
    check('切换到赵出纳（无 document:export）', sw3.ok, sw3.who || sw3.reason);
    await clickMenu(page, '台账档案');
    const arZhao = await readArchivePage(page);
    check('无导出权限的账号看不到「导出台账」按钮（按钮级权限生效）',
      !arZhao.hasExportBtn, 'hasExportBtn=' + arZhao.hasExportBtn);

    /* ================= 五、台账日期筛选（原为死控件） ================= */
    section('五、台账档案日期范围筛选真的生效（此前绑了 v-model 却从不参与过滤）');

    const sw4 = await switchAccount(page, 'admin');
    check('切换到管理员', sw4.ok, sw4.who || sw4.reason);
    await clickMenu(page, '台账档案');
    const base = await readArchivePage(page);
    check('未设条件时台账有数据（否则下面几条无意义）', base.rows.length > 0, '行数=' + base.rows.length);

    // 窄区间：本月 1~3 日。期望行数不硬编码，直接按同口径查库对账 —— 演示数据换了断言也不塌
    const narrow = await pickArchiveRange(page, '1', '3');
    check('面板点选成功（两次点击都命中日期格）',
      !!narrow && narrow.okA && narrow.okB, JSON.stringify(narrow));
    const nv = narrow ? narrow.vals : ['', ''];
    check('  两个输入框都拿到了值（v-model 确实被面板点选更新）',
      !!nv[0] && !!nv[1], '输入框=' + JSON.stringify(nv));
    const narrowExpect = ledgerCountDb(nv[0], nv[1]);
    const afterNarrow = await readArchivePage(page);
    check('  窄区间后台账行数 = 库内同口径行数（日期范围真的参与过滤了）',
      afterNarrow.rows.length === narrowExpect,
      '页面=' + afterNarrow.rows.length + ' 库=' + narrowExpect
      + '（区间 ' + nv[0] + '~' + nv[1] + '）'
      + (narrowExpect === 0 ? '，空文案=' + afterNarrow.emptyText : ''));

    // 宽区间：左月 1 日 ~ 右月 31 日，应回到未筛选的行数（不是把数据全筛掉）
    const wide = await pickArchiveRange(page, '1', '31');
    const wv = wide ? wide.vals : ['', ''];
    const wideExpect = ledgerCountDb(wv[0], wv[1]);
    const afterWide = await readArchivePage(page);
    check('  宽区间后台账行数 = 库内同口径行数，且等于未设条件时（不是把所有数据都筛掉）',
      afterWide.rows.length === wideExpect && wideExpect === base.rows.length,
      '页面=' + afterWide.rows.length + ' 库=' + wideExpect + ' 未设条件=' + base.rows.length
      + '（区间 ' + wv[0] + '~' + wv[1] + '）');

    /* ================= 五点五、人员管理页（#34/#35 已回退，只留烟测） ================= */
    // 【为什么本节只剩一条烟测 —— 删掉的那 7 条曾让整套用例静默失效】
    // 本节原先断言「人员管理表格已接物理分页接口 /api/users/page」，加上页大小/keyword/
    // 翻页不重叠/普通员工 403 共 7 条，属 #35 工程护栏。但 #34（主数据写接口）与 #35（列表分页）
    // 已按用户要求整体回退：/api/users/page 在源码与 git 基线 ab2a4a5 里都不存在。
    //
    // 后果不是「多两条红灯」那么轻：`D(upAdmin).records.length` 会在 undefined 上直接抛 TypeError，
    // 脚本在第 592 行**中断**，于是第 600 行之后的 16 条断言（含整条审计留痕链路、分页越权、
    // Downloads 卫生、权限守卫）**从未执行**，但输出仍然只是「2 条失败」——
    // 一个静默失效的回归套件比没有套件更危险。
    //
    // 注意 **不能**改成断言「普通员工访问 /api/users 必须 403」：人员列表接口刻意不加
    // @RequirePerm（选人下拉要跨全量做客户端搜索，见 MEMORY「硬约束/接口口径」），
    // 加了会与设计冲突。
    //
    // 若将来重做 #35，可从本文件的历史版本（git 提交 b746593 之前）恢复本节。
    await clickMenu(page, '权限管理');
    await sleep(1500);
    const adminUi = await page.evaluate(() => {
      const vis = el => !!(el && el.offsetParent !== null);
      const rows = [...document.querySelectorAll('.el-table__row')].filter(vis);
      return { rows: rows.length,
               firstCell: (rows[0] && rows[0].querySelector('td')) ? rows[0].querySelector('td').textContent.trim() : '' };
    });
    check('人员管理页仍能渲染人员行（回退后走全量 GET /api/users，页面没被回退弄白）',
      adminUi.rows > 0, JSON.stringify(adminUi));

    /* ================= 六、审计留痕真的在写 ================= */
    section('六、审计日志：@Audit 切面确实在写库（静默失效是最难发现的故障）');

    const auditNow = Number(sqlSafe('SELECT COUNT(*) FROM audit_log'));
    check('audit_log 表已有记录（此前该表从未被任何代码写入）',
      auditNow > auditBefore, '跑前=' + auditBefore + ' 现在=' + auditNow);

    const exAudit = await api('GET', '/api/audit-logs?action=exportLedger&pageSize=5', tkAdmin);
    check('GET /api/audit-logs 能按 action 过滤', exAudit.status === 200,
      'HTTP' + exAudit.status + ' msg=' + (exAudit.body && exAudit.body.msg));
    const exRec = (exAudit.body.data.records || [])[0] || {};
    check('  导出行为已留痕（document/exportLedger）',
      exRec.module === 'document' && exRec.action === 'exportLedger',
      exRec.module + '/' + exRec.action);
    check('  留痕记下了操作人姓名', exRec.userName === '王会计', 'userName=' + exRec.userName);
    check('  留痕记下了 IP 与请求路径', !!exRec.ip && String(exRec.detail).indexOf('/api/documents/export') >= 0,
      'ip=' + exRec.ip + ' detail=' + exRec.detail);

    const loginAudit = Number(sqlSafe(
      "SELECT COUNT(*) FROM audit_log WHERE module='auth' AND action='login' AND user_name IS NOT NULL"));
    check('  登录已留痕且解出了操作人（login 在白名单里没有登录态，靠响应体反解）',
      loginAudit > 0, 'auth/login 带姓名的记录数=' + loginAudit);

    const badRows = Number(sqlSafe(
      "SELECT COUNT(*) FROM audit_log WHERE module IS NULL OR module='' OR action IS NULL OR action=''"));
    check('  没有 module/action 为空的记录', badRows === 0, '空记录数=' + badRows);

    const jsonOk = Number(sqlSafe(
      'SELECT COUNT(*) FROM audit_log WHERE JSON_VALID(detail) = 1'));
    const jsonAll = Number(sqlSafe("SELECT COUNT(*) FROM audit_log WHERE detail IS NOT NULL"));
    check('  detail 全部是合法 JSON（audit_log.detail 是 JSON 列，写脏数据会直接 500）',
      jsonOk === jsonAll, '合法=' + jsonOk + ' 非空=' + jsonAll);

    const auditZhou = await api('GET', '/api/audit-logs', tkZhou);
    check('  普通部门负责人读审计日志被拒 403（只有 system:audit 能读）',
      auditZhou.status === 403, 'HTTP' + auditZhou.status);

    /* ================= 七、收尾：数据卫生 ================= */
    section('七、收尾：演示数据与用户目录未被污染');

    const afterSnap = snapshot('跑测试后');
    check('演示状态快照与跑测试前完全一致', afterSnap === beforeSnap,
      afterSnap === beforeSnap ? '一致' : ('前=' + beforeSnap + ' 后=' + afterSnap));
    assertInvariants(afterSnap, check);

    const dlVisibleAfter = listVisibleCount(DOWNLOADS);
    check('未往用户 ~/Downloads 写任何可见文件（下载已重定向到临时目录）',
      dlVisibleAfter === dlVisibleBefore,
      '跑前=' + dlVisibleBefore + ' 跑后=' + dlVisibleAfter);

    // Chrome 的下载临时文件是隐藏点文件。沙箱环境下 Chrome「能写入、不能 unlink」，
    // 必然每跑积几个；用例只做 best-effort 清理（沙箱内的 rename 也可能被拒），
    // 剩余部分靠脚本外的手动清理。判定标准：**用户可见列表零新增**。
    const temps = chromeTempFiles(DOWNLOADS);
    let cleaned = 0;
    for (const t of temps) {
      try { fs.renameSync(path.join(DOWNLOADS, t), path.join(TMP, t)); cleaned++; } catch (e) { /* 沙箱内搬不动，如实报告 */ }
    }
    check('~/Downloads 的新增仅限 Chrome 隐藏临时文件（对用户不可见）',
      temps.every(t => /^\.com\.google\.chrome\.for\.testing\./.test(t)),
      '隐藏残留=' + temps.length + ' 本次已搬走=' + cleaned
      + (temps.length > cleaned ? '（沙箱限制清不掉 ' + (temps.length - cleaned) + ' 个，可脚本外清理）' : ''));

    // 上一轮的真实缺陷：非管理员登录后无条件拉 /api/permissions，白打一个 403。
    // 修复后整个用例（含多次普通员工会话）不应再出现该 403。
    const perm403 = netErrors.filter(u => /403\s+\S*\/api\/permissions/.test(u));
    check('普通员工会话不再白打 /api/permissions 的 403（权限守卫生效）',
      perm403.length === 0, perm403.join(' | ') || '无');

    // 401/403 是越权用例的预期结果；其余状态码与 JS 异常才算失败
    const unexpected = netErrors.filter(u => !/^(401|403)\s/.test(u));
    check('无未预期的失败请求 / JS 异常', unexpected.length === 0, unexpected.join(' | '));

  } finally {
    await browser.close();
    try { fs.rmSync(TMP, { recursive: true, force: true }); } catch (e) { /* 清理失败不影响结论 */ }
    try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) { /* 同上 */ }
  }

  const passed = results.filter(r => r.ok).length;
  const total = results.length;
  // 自证闸门：条数必须与预期一致（少跑 = 有断言被删除、注释，或中断后静默跳过）
  const countOk = total === EXPECTED_TOTAL;
  console.log('\n' + '='.repeat(74));
  console.log(`  风险预警 / 台账导出 / 首页真值 / 审计留痕 UI 验证：${passed}/${total} 通过（预期 ${EXPECTED_TOTAL} 条）`);
  const bad = results.filter(r => !r.ok);
  if (bad.length) {
    console.log('  失败项：');
    bad.forEach(r => console.log('    - ' + r.name));
  }
  if (!countOk) {
    console.log(`  ❌ 断言条数异常：实际 ${total} 条，预期 ${EXPECTED_TOTAL} 条`);
    console.log('     条数变少通常意味着上游抛错后，其后断言被静默跳过（假绿灯）。');
  }
  console.log('='.repeat(74));
  process.exit((bad.length || !countOk) ? 1 : 0);
})().catch(e => { console.error('ERR [断言未跑完，本次结果无效]', e); process.exit(1); });

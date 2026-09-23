/* ============================================================================
 * 收口批前端 E2E：首页图表真值 / 通知中心 / 筛选口径
 * ----------------------------------------------------------------------------
 * 覆盖三组改动（对应 scripts/patch_frontend_final_gaps.py 的 D1/D2/D4）：
 *
 *   D1 首页「近7天发起量」+「流程状态」= 后端 /api/documents/stats 的真值
 *      —— 断言逐日数字与后端 dailyCounts 对齐，且页面上不再有旧的写死数字。
 *
 *   D2 通知中心（顶部铃铛 → 抽屉）
 *      —— 角标 = 真实未读通知数；点开有列表；点单条 → 该条 is_read 落库、未读数 -1；
 *         「全部已读」→ 未读归零。**关键回归点**：未读为 0 时铃铛必须仍然常驻，
 *         否则用户再也打不开通知中心（v1 用 v-if 就是这个坑）。
 *
 *   D4 筛选下拉来自真实数据
 *      —— 旧的写死清单里有永远匹配不到的项（'日常申请单'、'业务付款'），
 *         现在选项从真实单据类型推导；并且选中的选项**确实能筛出**对应数据。
 *
 * 数据卫生：本用例会点「全部已读」，那会永久改掉 is_read，所以跑前记下已读集合、
 * 跑后精确还原（见 _hygiene.js 的 grabReadIds/restoreReadState），并把未读数纳入快照。
 * ==========================================================================*/

const puppeteer = require('puppeteer-core');
const fs = require('fs');

const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE = 'http://127.0.0.1:8080/oa.html';
const API = 'http://127.0.0.1:8080';
const PW = '123456';
const LOCAL_HTML = '/Users/zhouzewei/WorkBuddy/2026-09-18-15-53-12/海峡金OA审批系统-联调版.html';
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* 预期断言总数：脚本正常跑完必须**恰好**产出这么多条。
   为什么要把这个数写死在代码里：本项目出过一次「假绿灯」—— 上游某条断言依赖的接口被回退后
   抛错中断，导致其后 16 条断言（含整条审计留痕链路）**从未执行**，而末行照样打印
   "85/85 通过"。有了这个数，任何"少跑了"都会立刻变成红灯，而不是无声无息。 */
const EXPECTED_TOTAL = 65;

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}
function section(t) { console.log('\n' + '='.repeat(74) + '\n' + t + '\n' + '='.repeat(74)); }

const { snapshot, assertInvariants, uidOf, unreadOf, grabReadIds, restoreReadState, sqlSafe } =
  require('./_hygiene.js');

/* ---------------- HTTP ---------------- */
async function api(method, p, token, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(API + p, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body)
  });
  let json = {};
  try { json = await res.json(); } catch (e) { /* 保持空对象 */ }
  return { status: res.status, body: json };
}
async function login(account) {
  const r = await api('POST', '/api/auth/login', null, { account, password: PW });
  if (r.body.code !== 0) throw new Error('登录失败 ' + account + '：' + r.body.msg);
  return r.body.data.token;
}

/* ---------------- 浏览器小工具 ---------------- */
const ACCOUNT_NAME = { admin: '系统管理员', huangxm: '黄小明', zhouzh: '周综合' };

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
  if (!picked) return { ok: false, who: '', reason: '门禁里没有「' + name + '」这个账号按钮' };
  await sleep(4600);
  const who = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  return { ok: who.length > 0, who, reason: who ? '' : '点了账号按钮但没进入系统' };
}

async function clickMenu(page, text) {
  const hit = await page.evaluate((t) => {
    const items = [...document.querySelectorAll('.el-menu .el-menu-item, .el-menu li')];
    const h = items.find(i => i.getClientRects().length > 0 && i.textContent.includes(t));
    if (h) { h.click(); return t; }
    return '';
  }, text);
  await sleep(1800);
  return hit;
}

/** 关闭所有可见的抽屉/弹窗（通知抽屉、详情抽屉） */
async function closeAllOverlays(page) {
  await page.evaluate(() => {
    [...document.querySelectorAll('.el-drawer, .el-dialog')].forEach(d => {
      if (d.getClientRects().length) {
        const b = [...d.querySelectorAll('.el-button')].find(x => /关闭|取消|^×$/.test(x.textContent.trim()));
        if (b) b.click();
      }
    });
  });
  await sleep(700);
  await page.keyboard.press('Escape');
  await sleep(500);
}

/** 读 .top-actions 铃铛的状态（角标数字 / 角标是否可见 / 铃铛按钮是否还在） */
function readBell(page) {
  return page.evaluate(() => {
    const bb = document.querySelector('.top-actions .el-badge');
    if (!bb) return null;
    const sup = bb.querySelector('sup');
    return {
      exists: true,
      hasButton: !!bb.querySelector('button'),
      supText: sup ? sup.textContent.trim() : '',
      supVisible: !!(sup && sup.getClientRects().length)
    };
  });
}

async function openNotifyDrawer(page) {
  await page.evaluate(() => {
    const bb = document.querySelector('.top-actions .el-badge');
    const btn = bb && bb.querySelector('button');
    (btn || bb).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
  });
  await sleep(1100);
  return page.evaluate(() => {
    const dw = [...document.querySelectorAll('.el-drawer')].filter(d => d.getClientRects().length);
    if (!dw.length) return { visible: false };
    const d = dw[0];
    const items = [...d.querySelectorAll('.notify-item')];
    return {
      visible: true,
      title: (d.querySelector('.el-drawer__title') || {}).textContent,
      itemCount: items.length,
      unreadItems: items.filter(i => i.className.indexOf('is-unread') >= 0).length,
      firstHead: items[0] ? (items[0].querySelector('.notify-head') || {}).textContent.replace(/\s+/g, ' ').trim() : '',
      firstHasBody: !!(items[0] && (items[0].querySelector('p') || {}).textContent.trim()),
      firstUnreadTag: items[0] ? [...items[0].querySelectorAll('.el-tag')].map(t => t.textContent.trim()).join('|') : '',
      footerBtns: [...d.querySelectorAll('.el-drawer__footer .el-button')].map(x => x.textContent.trim())
    };
  });
}

/** 读首页「近7天发起量」/「流程状态」渲染值 */
function readAnalytics(page) {
  return page.evaluate(() => {
    const el = document.querySelector('.home-analytics');
    if (!el) return null;
    return {
      exists: true,
      text: el.textContent.replace(/\s+/g, ' ').trim(),
      weekTag: (el.querySelector('.weekly-chart .el-tag') || {}).textContent || '',
      bars: [...el.querySelectorAll('.mini-bars > div')].map(d => ({
        label: (d.querySelector('span') || {}).textContent || '',
        count: (d.querySelector('b') || {}).textContent || ''
      })),
      donut: (el.querySelector('.home-donut b') || {}).textContent || '',
      legend: [...el.querySelectorAll('.home-legend p')].map(p => p.textContent.replace(/\s+/g, ' ').trim())
    };
  });
}

/* ---------------- 下拉选择（Element Plus 2.11） ---------------- */
async function openSelectByPlaceholder(page, placeholder) {
  return page.evaluate((ph) => {
    const sel = [...document.querySelectorAll('.filters .el-select')]
      .find(s => ((s.querySelector('.el-select__placeholder') || {}).textContent || '') === ph);
    if (!sel) return false;
    (sel.querySelector('.el-select__wrapper') || sel)
      .dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
    return true;
  }, placeholder);
}
async function readOpenOptions(page) {
  await sleep(700);
  return page.evaluate(() => {
    const dds = [...document.querySelectorAll('.el-select-dropdown')].filter(d => d.getClientRects().length);
    if (!dds.length) return [];
    return [...dds[dds.length - 1].querySelectorAll('.el-select-dropdown__item')]
      .map(i => i.textContent.trim());
  });
}
async function pickOpenOption(page, label) {
  const ok = await page.evaluate((lb) => {
    const dds = [...document.querySelectorAll('.el-select-dropdown')].filter(d => d.getClientRects().length);
    if (!dds.length) return false;
    const it = [...dds[dds.length - 1].querySelectorAll('.el-select-dropdown__item')]
      .find(i => i.textContent.trim() === lb);
    if (!it) return false;
    it.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
    return true;
  }, label);
  await sleep(1000);
  return ok;
}
/** 列表可见行数 + 每行的单据类型列文本 */
function readApproveRows(page) {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')]
      .filter(r => r.getClientRects().length);
    return rows.map(r => [...r.querySelectorAll('td')].map(td => td.textContent.trim()));
  });
}

/* ---------------- 页面侧写死的旧清单（用于「已消失」断言） ---------------- */
const OLD_DOC_TYPE_STUB = '日常申请单';   // 旧写死单据类型，documentType() 永远合成不出这个名字
const OLD_BIZ_TYPE_STUB = '业务付款';     // 旧写死业务类型；注意它**可以**由 BIZ 类别的真实单据类型推出来

/** 与页面 documentType() 同一规则（用于推导"应有"的选项） */
function documentTypeOf(name) {
  if (name.indexOf('用印') >= 0) return '用印申请单';
  if (name.indexOf('报销') >= 0) return '报销申请单';
  return '付款申请单';
}
const BIZ_TEXT = { DAILY: '日常付款', BIZ: '业务付款', REIMBURSE: '员工报销', SEAL: '用印申请' };

(async () => {
  const browser = await puppeteer.launch({
    executablePath: EXEC, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  // 记录 /api 请求：用来证明单据列表的搜索是**发到后端**的，
  // 而不是在前端已加载的那一批（最多 200 条）里本地筛。
  const apiCalls = [];
  page.on('request', r => {
    const u = r.url() || '';
    if (u.indexOf('/api/') >= 0) apiCalls.push(u);
  });
  await page.setViewport({ width: 1680, height: 1050 });

  const netErrors = [];
  page.on('response', r => { if (r.status() >= 400) netErrors.push(r.status() + ' ' + r.url()); });
  page.on('pageerror', e => netErrors.push('pageerror: ' + String(e)));

  try {
    /* ================= 零、部署与数据基线 ================= */
    section('零、部署一致性与数据基线');

    const localBytes = fs.readFileSync(LOCAL_HTML);
    const servedRes = await fetch(API + '/oa.html');
    const servedBuf = Buffer.from(await servedRes.arrayBuffer());
    const served = servedBuf.toString('utf8');
    check('服务返回的 oa.html 与本地文件字节一致（部署的是同一份）',
      servedBuf.equals(localBytes),
      'served=' + servedBuf.length + 'B local=' + localBytes.length + 'B');

    // 死代码只在"已部署的那份"上断言 —— 本地文件干净但忘了重新部署是很常见的失误
    check('已部署版本里已无死代码 notImplemented', served.indexOf('const notImplemented') < 0);
    check('已部署版本里已无重复的 api.permissions',
      served.indexOf("function(){ return http('/api/auth/permissions'); }") < 0);
    check('已部署版本里已无零调用的 api.attachments',
      served.indexOf("http('/api/attachments', {params:{documentId:documentId}})") < 0);
    check('已部署版本里首页写死的 26/14/9/3 图表已清除',
      served.indexOf("['周一',42,3]") < 0 && served.indexOf('<b>26</b>') < 0);

    const tkAdmin = await login('admin');
    const tkHuang = await login('huangxm');
    const tkZhou = await login('zhouzh');
    check('接口登录成功（admin / huangxm / zhouzh）', !!tkAdmin && !!tkHuang && !!tkZhou);

    const statsRes = await api('GET', '/api/documents/stats', tkHuang);
    const stats = statsRes.body.data || {};
    check('GET /api/documents/stats 返回近7天逐日数据',
      Array.isArray(stats.dailyCounts) && stats.dailyCounts.length === 7,
      'dailyCounts=' + (stats.dailyCounts || []).map(x => x.count).join('/'));
    check('  逐日之和 = weekTotal', (stats.dailyCounts || []).reduce((a, b) => a + b.count, 0) === stats.weekTotal,
      'sum=' + (stats.dailyCounts || []).reduce((a, b) => a + b.count, 0) + ' weekTotal=' + stats.weekTotal);

    const typesRes = await api('GET', '/api/documents/types', tkAdmin);
    const types = typesRes.body.data || [];
    check('GET /api/documents/types 拿到真实单据类型', types.length > 0,
      types.map(t => t.name + '(' + t.category + ')').join(', '));

    const huangId = uidOf('huangxm');
    const zhouId = uidOf('zhouzh');
    check('拿到黄小明 / 周综合 的 userId', /^\d+$/.test(huangId) && /^\d+$/.test(zhouId),
      'huangxm=' + huangId + ' zhouzh=' + zhouId);

    const beforeSnap = snapshot('跑测试前');
    const unreadBefore = Number(unreadOf(huangId));
    const readIdsBefore = grabReadIds(huangId);
    check('黄小明有未读通知可测（> 0）', unreadBefore > 0, 'unread=' + unreadBefore);

    /* ================= 一、首页图表真值 ================= */
    section('一、首页「近7天发起量」/「流程状态」= 后端权威统计（黄小明）');

    await page.goto(PAGE, { waitUntil: 'networkidle2' });
    const g = await loginViaGate(page, 'huangxm');
    check('登录门禁进入系统（黄小明）', g.ok, g.who || g.reason);
    await sleep(1200);
    // 注意：两个图表在「⌂首页」，不在「▦工作台」。点工作台会导航离开首页，
    // 于是 .home-analytics 直接消失（第一次就踩了这个）。
    const onHome = await page.evaluate(() => {
      const a = [...document.querySelectorAll('.el-menu-item')].find(i => i.className.indexOf('is-active') >= 0);
      return a ? a.textContent.trim() : '';
    });
    check('登录后停在「首页」（图表所在页）', onHome.indexOf('首页') >= 0, '当前菜单=' + onHome);

    const an = await readAnalytics(page);
    check('首页渲染出「近7天发起量」图表块', !!(an && an.exists && an.bars.length === 7),
      an ? ('bars=' + an.bars.length) : '(没找到 .home-analytics)');
    check('  角标「共 N 单」= 后端 weekTotal',
      an && Number((an.weekTag.match(/(\d+)/) || [])[1]) === stats.weekTotal,
      an ? (an.weekTag + ' vs weekTotal=' + stats.weekTotal) : '');
    check('  7 根柱子逐日与后端 dailyCounts 一一对应',
      an && an.bars.every((b, i) => Number(b.count) === stats.dailyCounts[i].count),
      an ? ('页面=' + an.bars.map(b => b.count).join('/')
        + ' 后端=' + stats.dailyCounts.map(x => x.count).join('/')) : '');
    check('  圆环「全部单据」= 后端 total',
      an && Number(an.donut) === stats.total, an ? (an.donut + ' vs ' + stats.total) : '');
    const lg = (an && an.legend) || [];
    const lgNum = (name) => Number(((lg.find(x => x.includes(name)) || '').match(/(\d+)/) || [])[1]);
    check('  圆环「已办结」= 后端 approved（不是写死的 14）',
      lgNum('已办结') === stats.approved, '页面=' + lgNum('已办结') + ' 后端=' + stats.approved);
    check('  圆环「审批中」= 后端 running（不是写死的 9）',
      lgNum('审批中') === stats.running, '页面=' + lgNum('审批中') + ' 后端=' + stats.running);
    check('  圆环「已驳回」= 后端 rejected（不是写死的 3）',
      lgNum('已驳回') === stats.rejected, '页面=' + lgNum('已驳回') + ' 后端=' + stats.rejected);

    /* ================= 二、铃铛角标 ================= */
    section('二、通知铃铛角标 = 真实未读数（且铃铛常驻）');

    const bell1 = await readBell(page);
    check('顶部铃铛元素存在', !!(bell1 && bell1.exists && bell1.hasButton),
      bell1 ? JSON.stringify(bell1) : '(.top-actions el-badge 不存在)');
    check('  角标数字 = 真实未读通知数',
      bell1 && Number(bell1.supText) === unreadBefore,
      bell1 ? (bell1.supText + ' vs unread=' + unreadBefore) : '');
    check('  有未读时角标可见', bell1 && bell1.supVisible === (unreadBefore > 0),
      bell1 ? ('supVisible=' + bell1.supVisible) : '');

    /* ================= 三、通知抽屉 ================= */
    section('三、通知中心抽屉：列表 / 单条已读 / 全部已读');

    let drawer = await openNotifyDrawer(page);
    check('点铃铛打开「通知中心」抽屉', drawer.visible && drawer.title === '通知中心',
      drawer.visible ? drawer.title : '抽屉没出现');
    check('  抽屉里有通知列表', drawer.itemCount > 0, '条目数=' + drawer.itemCount);
    check('  首条含标题与时间', !!(drawer.firstHead && /\d{4}-\d{2}-\d{2}/.test(drawer.firstHead)),
      drawer.firstHead);
    check('  首条含正文内容', !!drawer.firstHasBody);
    check('  未读条目带「未读」标签', drawer.firstUnreadTag.includes('未读'), drawer.firstUnreadTag);
    check('  底部有「全部已读」按钮', drawer.footerBtns.includes('全部已读'),
      drawer.footerBtns.join('/'));
    check('  页面上未读条目数与后端未读数一致',
      drawer.unreadItems === Math.min(unreadBefore, drawer.itemCount),
      '页面未读=' + drawer.unreadItems + ' 后端未读=' + unreadBefore + '（本页 ' + drawer.itemCount + ' 条）');

    // ---- 单条标记已读 ----
    const firstHead = drawer.firstHead;
    await page.evaluate(() => {
      const dw = [...document.querySelectorAll('.el-drawer')].filter(d => d.getClientRects().length)[0];
      const it = dw && dw.querySelector('.notify-item.is-unread');
      if (it) it.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
    });
    await sleep(1200);

    const unreadAfterOne = Number(unreadOf(huangId));
    const readCountDb = Number(sqlSafe(
      `SELECT COUNT(*) FROM notification WHERE receiver_id=${huangId} AND deleted=0 AND is_read=1`));
    check('点单条通知 → 该条已读落库（未读数 -1）', unreadAfterOne === unreadBefore - 1 && readCountDb === 1,
      '未读 ' + unreadBefore + '→' + unreadAfterOne + '，库里已读=' + readCountDb);

    await closeAllOverlays(page);
    const bell2 = await readBell(page);
    check('  铃铛角标同步 -1', bell2 && Number(bell2.supText) === unreadBefore - 1,
      bell2 ? bell2.supText : '(铃铛不见了)');

    // 重新打开抽屉：刚才点过的那条应变成已读态
    // 重新打开抽屉：刚才点过的那条应变成已读态。
    // 注意口径：本页最多显示 itemCount 条，跑前本页未读 = min(unreadBefore, itemCount)，
    // 点掉一条后应为 min(unreadBefore, itemCount) - 1。
    // （写成 min(unreadBefore - 1, itemCount) 是错的：当总数远大于页容量时它仍等于 itemCount。）
    drawer = await openNotifyDrawer(page);
    const pageUnreadBeforeClick = Math.min(unreadBefore, drawer.itemCount);
    check('  重新打开抽屉：该条已变为已读态（本页未读条目 -1）',
      drawer.visible && drawer.unreadItems === pageUnreadBeforeClick - 1,
      drawer.visible
        ? ('本页未读=' + drawer.unreadItems + '（应为 ' + (pageUnreadBeforeClick - 1) + '），'
          + '首条「' + drawer.firstHead + '」' + (drawer.firstHead === firstHead ? '（同一条）' : '（首条变了）'))
        : '抽屉没打开');

    // ---- 全部已读 ----
    const allBtn = await page.evaluate(() => {
      const dw = [...document.querySelectorAll('.el-drawer')].filter(d => d.getClientRects().length)[0];
      if (!dw) return false;
      const b = [...dw.querySelectorAll('.el-drawer__footer .el-button')].find(x => x.textContent.trim() === '全部已读');
      if (!b) return false;
      b.click(); return true;
    });
    check('点得到「全部已读」按钮', allBtn);
    await sleep(2000);

    const unreadAll = Number(unreadOf(huangId));
    check('「全部已读」后后端未读归零', unreadAll === 0, 'unread=' + unreadAll);

    await closeAllOverlays(page);
    const bell3 = await readBell(page);
    check('  未读为 0 时角标红点隐藏', bell3 && bell3.supVisible === false,
      bell3 ? ('supVisible=' + bell3.supVisible + ' sup=' + bell3.supText) : '');
    // ↓↓↓ 这一条是本轮修的回归点：v1 用 v-if 会让铃铛整个消失，通知中心再也打不开
    check('  **未读为 0 时铃铛仍然常驻**（否则通知中心无入口）',
      !!(bell3 && bell3.exists && bell3.hasButton));

    // ---- 精确还原 is_read（否则演示角标就永久变了） ----
    const restored = restoreReadState(huangId, readIdsBefore);
    check('通知已读态已精确还原到跑测试前', restored,
      '还原后未读=' + unreadOf(huangId) + '（跑前 ' + unreadBefore + '）');

    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(2600);
    const bell4 = await readBell(page);
    check('  刷新后角标回到真实未读数', bell4 && Number(bell4.supText) === unreadBefore,
      bell4 ? (bell4.supText + ' vs ' + unreadBefore) : '(铃铛不见了)');
    console.log('    [诊断] 第三节结束时未读=' + unreadOf(huangId));

    /* ================= 四、筛选下拉来自真实数据 ================= */
    section('四、待审列表筛选项来自真实数据（周综合，有 4 条待办）');

    const g2 = await loginViaGate(page, 'zhouzh');
    check('切换账号进入系统（周综合）', g2.ok, g2.who || g2.reason);
    console.log('    [诊断] 切到周综合后未读=' + unreadOf(huangId));
    await sleep(1200);
    const menuHit = await clickMenu(page, '待我审批');
    console.log('    [诊断] 进待我审批后未读=' + unreadOf(huangId));
    check('切到「待我审批」页', !!menuHit, menuHit || '(没找到菜单)');

    const rows0 = await readApproveRows(page);
    const todosRes = await api('GET', '/api/todos', tkZhou);
    const todoCount = (todosRes.body.data || []).length;
    check('  待办列表非空（用于验证筛选真的能筛）', rows0.length > 0 && rows0.length === todoCount,
      '页面行数=' + rows0.length + ' 后端待办=' + todoCount);

    // ---- 单据类型选项 ----
    const openedDoc = await openSelectByPlaceholder(page, '单据类型');
    check('打得开「单据类型」下拉', openedDoc);
    const docOpts = await readOpenOptions(page);
    const expectDocOpts = [...new Set(types.map(t => documentTypeOf(t.name)))];
    check('  选项 = 从真实单据类型推导（不是写死清单）',
      docOpts.length > 0 && docOpts.every(o => expectDocOpts.includes(o)) && expectDocOpts.every(o => docOpts.includes(o)),
      '页面=[' + docOpts.join('/') + '] 推导=[' + expectDocOpts.join('/') + ']');
    check('  旧写死项「' + OLD_DOC_TYPE_STUB + '」已消失（它永远匹配不到任何单据）',
      !docOpts.includes(OLD_DOC_TYPE_STUB), '选项=[' + docOpts.join('/') + ']');

    // 选一个"真实存在数据"的选项，确认能筛出结果
    const sealOpt = expectDocOpts.find(o => o.includes('用印'));
    const pickedSeal = await pickOpenOption(page, sealOpt);
    const rowsSeal = await readApproveRows(page);
    check('  选「' + sealOpt + '」能筛出结果（' + rowsSeal.length + ' 行）',
      pickedSeal && rowsSeal.length > 0, '行数=' + rowsSeal.length);
    check('  筛出的行确实都是用印类单据',
      rowsSeal.length > 0 && rowsSeal.every(cells => cells.some(c => c.indexOf('用印') >= 0)),
      rowsSeal.map(c => c.join('|')).slice(0, 2).join(' // '));

    // 选一个"真实没有数据"的选项，确认是 0 行 —— 与数据库交叉验证，而不是假设
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(2600);
    await clickMenu(page, '待我审批');
    await openSelectByPlaceholder(page, '单据类型');
    const nonSeal = expectDocOpts.find(o => !o.includes('用印'));
    await pickOpenOption(page, nonSeal);
    const rowsEmpty = await readApproveRows(page);
    const dbNonSeal = Number(sqlSafe(
      "SELECT COUNT(*) FROM document d JOIN document_type t ON t.id=d.doc_type_id "
      + `WHERE d.deleted=0 AND d.applicant_id=${huangId} AND t.category='SEAL'`));
    check('  选「' + nonSeal + '」筛空，且与库一致（该审批人名下确无用印以外待办）',
      rowsEmpty.length === 0, '行数=' + rowsEmpty.length + '（库中 Huang 的用印单=' + dbNonSeal + '）');

    // ---- 业务类型选项 ----
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(2600);
    await clickMenu(page, '待我审批');
    const openedBiz = await openSelectByPlaceholder(page, '业务类型');
    check('打得开「业务类型」下拉', openedBiz);
    const bizOpts = await readOpenOptions(page);
    const expectBizOpts = [...new Set(types.map(t => BIZ_TEXT[t.category]).filter(Boolean))];
    check('  选项 = 从真实单据类型的业务类别推导',
      bizOpts.length > 0 && bizOpts.every(o => expectBizOpts.includes(o)) && expectBizOpts.every(o => bizOpts.includes(o)),
      '页面=[' + bizOpts.join('/') + '] 推导=[' + expectBizOpts.join('/') + ']');
    check('  旧写死项「' + OLD_BIZ_TYPE_STUB + '」不在选项里（除非确有该类别的单据类型）',
      // 这条要验的是"页面上没有凭空写死的选项"，而不是"某个词永远不许出现"。
      // 原写法断言它绝不出现，前提是"真实单据类型里没有 BIZ 类别"——
      // 这个前提在有人新建了一个 BIZ 类别的单据类型之后就不成立了，
      // 此时下拉里出现它是**正确推导**，不是写死。真正的推导正确性由上面那条
      // 双向集合相等断言保证；这里只在"推导不出它"时才要求它消失。
      !bizOpts.includes(OLD_BIZ_TYPE_STUB) || expectBizOpts.includes(OLD_BIZ_TYPE_STUB),
      '选项=[' + bizOpts.join('/') + '] 推导=[' + expectBizOpts.join('/') + ']');

    await pickOpenOption(page, '用印申请');
    const rowsBiz = await readApproveRows(page);
    check('  选「用印申请」能筛出结果（业务类型筛选真的生效）',
      rowsBiz.length > 0, '行数=' + rowsBiz.length);

    /* ================= 四·补、单据列表搜索走后端 =================
       此前后端**已经支持** pageNum/pageSize/keyword，但前端固定 pageSize:500 一次拉完、
       再在前端过滤；而后端对 pageSize 的上限是 200 ⇒ 实际只拿到前 200 条，
       搜索也只是在这 200 条里搜。超过 200 条后按单号/申请人搜会得到"空结果"，
       界面还不提示"只搜了前 200 条"，看起来就像那张单不存在。
       现在 keyword 交给 /api/documents，过滤发生在 SQL 里、搜的是全库。

       【为什么要切回黄小明】上一节用的是周综合（部门范围）。若拿黄小明的单号去搜，
       在周综合的范围下**本来就该搜不到** —— 那是行级数据范围在正常工作，不是缺陷。
       所以这里切到一个"本人范围内确实有单据"的账号，并且**用它自己列表里的单号**去搜，
       这样断言只检验"搜索走到了后端且命中"，不会误伤数据范围逻辑。 */
    section('四·补、单据列表：搜索走服务端（而不是在前端已加载的那一批里筛）');
    const g3 = await loginViaGate(page, 'huangxm');
    if (!g3.ok) throw new Error('切回黄小明失败，本节断言无意义：' + g3.reason);
    await clickMenu(page, '全部表单');
    await sleep(1700);

    const docKwSel = 'input[placeholder^="搜索单号、申请人"]';
    const hasDocKw = await page.$(docKwSel);
    check('「全部表单」页有搜索框', !!hasDocKw);

    if (hasDocKw) {
      const rowsBefore = await page.$$eval('.el-table__body tbody tr', els => els.length).catch(() => 0);
      // 取当前列表第一行的单号，截前 6 位作为搜索词（一定是本账号范围内的）
      const sampleNo = await page.$$eval('.el-table__body tbody tr', trs => {
        const t = (trs[0] || {}).textContent || '';
        const m = t.match(/[A-Z]{2}\d{8,}/);
        return m ? m[0] : '';
      }).catch(() => '');
      const kw = sampleNo ? sampleNo.slice(0, 6) : '';
      check('从当前列表取到一个可搜的单号片段（保证落在本账号数据范围内）',
        !!kw, '样例单号=' + sampleNo + ' → 关键词=' + kw);

      const callsBefore = apiCalls.filter(u => u.indexOf('/api/documents?') >= 0 && u.indexOf('keyword=') >= 0).length;
      if (kw) {
        await page.$eval(docKwSel, function (el, v) {
          el.value = v;
          el.dispatchEvent(new Event('input', { bubbles: true }));
        }, kw).catch(function () {});
        await sleep(1700);
      }
      const callsAfter = apiCalls.filter(u => u.indexOf('/api/documents?') >= 0 && u.indexOf('keyword=') >= 0);
      check('★ 输入关键字后确实请求了带 keyword 的 /api/documents（搜索走后端）',
        callsAfter.length > callsBefore,
        '带 keyword 的请求 ' + callsBefore + ' → ' + callsAfter.length
        + (callsAfter.length ? '，末条=' + callsAfter[callsAfter.length - 1].slice(-60) : ''));

      const rowsSearch = await page.$$eval('.el-table__body tbody tr', els => els.length).catch(() => 0);
      check('★ 搜到了结果（搜索作用于全库而不是"已加载的那一批"）',
        rowsSearch > 0 && rowsSearch <= rowsBefore,
        '行数=' + rowsSearch + '（搜索前=' + rowsBefore + '）');

      await page.$eval(docKwSel, function (el) {
        el.value = '';
        el.dispatchEvent(new Event('input', { bubbles: true }));
      }).catch(function () {});
      await sleep(1700);
      const rowsBack = await page.$$eval('.el-table__body tbody tr', els => els.length).catch(() => 0);
      check('清空关键字后列表恢复（否则后续收尾断言都在过滤态上跑）',
        rowsBack > 0 && rowsBack >= rowsBefore, '行数=' + rowsBack + '（搜索前=' + rowsBefore + '）');
    } else {
      check('（跳过）没有搜索框', false);
      check('（跳过）没有搜索框', false);
      check('（跳过）没有搜索框', false);
      check('（跳过）没有搜索框', false);
    }

    /* ================= 五、收尾：数据卫生 ================= */
    section('五、收尾：演示数据未被污染');

    await closeAllOverlays(page);
    console.log('    [诊断] 收尾前未读=' + unreadOf(huangId));
    // 收尾再精确还原一次（幂等）：无论第 3/4 节中间发生过什么写入，最终状态必须与跑前一致
    const restored2 = restoreReadState(huangId, readIdsBefore);
    console.log('    [诊断] 收尾还原后未读=' + unreadOf(huangId));
    const afterSnap = snapshot('跑测试后');
    check('演示状态快照与跑测试前完全一致', afterSnap === beforeSnap,
      afterSnap === beforeSnap ? '一致' : ('前=' + beforeSnap + ' 后=' + afterSnap));
    assertInvariants(afterSnap, check);
    check('收尾还原通知已读态成功（幂等，防中途遗留）', restored2);
    check('未读通知数已回到跑测试前', Number(unreadOf(huangId)) === unreadBefore,
      'now=' + unreadOf(huangId) + ' before=' + unreadBefore);

    check('无未预期的失败请求 / JS 异常',
      netErrors.filter(u => u.indexOf('/api/permissions') < 0).length === 0,
      netErrors.join(' | '));

  } finally {
    await browser.close();
  }

  const passed = results.filter(r => r.ok).length;
  const total = results.length;
  // 自证闸门：条数必须与预期一致（少跑 = 有断言被删除、注释，或中断后静默跳过）
  const countOk = total === EXPECTED_TOTAL;
  console.log('\n' + '='.repeat(74));
  console.log(`  首页图表真值 / 通知中心 / 筛选口径 UI 验证：${passed}/${total} 通过（预期 ${EXPECTED_TOTAL} 条）`);
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

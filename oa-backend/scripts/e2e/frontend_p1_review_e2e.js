/*
 * 交付视角 P1 三处改动 · UI 端到端验证
 *
 * 为什么必须走 UI（接口层证明不了）：
 *   本轮改的三件事，后端接口全都是对的，坏的是"界面有没有用上它、用得对不对"：
 *
 *   A. 首页「我的待办」卡片：原先数据源是 documents.slice(0,4)（全部可见单据的前 4 条），
 *      与同一屏 hero 里的 todoCount（来自 /api/todos）**口径不同** —— 一屏两个数字互相矛盾。
 *      改成 todos 之后，"接口对"依然证明不了"卡片列的是待办"，更证明不了"点得开"。
 *
 *   B. 驳回弹窗「指定驳回层级」：原先是写死的 5 项（提交人/直属部门负责人/核算会计/内控合规/出纳），
 *      只在「日常付款」那条流程上碰巧对得上；换个单据类型（用印、报销）节点完全不同。
 *      断言必须对比**该单据实际在跑的那一版流程**的节点，而不是"看起来有 5 项"。
 *
 *   C. 「提交审批」弹窗的「审批流程」行：原先是原型文案（提交人 → 部门负责人 → 核算会计 → …），
 *      现在接 /api/flows/configs/{id}/preview。这里最容易犯的错是"接上了但字段名读错" ——
 *      读错的表现不是报错，而是每一站都显示"待解析"，把"解析出来了"显示成"没解析出来"。
 *      所以断言必须逐站核对**真实审批人姓名**（本脚本就是靠这条抓到了 candidateNames/assignees 写错）。
 *
 * 数据卫生：
 *   全程只读（打开抽屉、打开驳回弹窗、填金额看预览），不提交、不驳回、不建草稿。
 *   结束时对比演示状态快照，并跑绝对不变量断言。
 */
const puppeteer = require('puppeteer-core');

const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE = 'http://127.0.0.1:8080/oa.html';
const API = 'http://127.0.0.1:8080';
const PW = '123456';
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* 预期断言总数：跑完必须**恰好**这么多条。少跑 = 红灯（防上游抛错导致后续断言从未执行的假绿灯）。 */
const EXPECTED_TOTAL = 44;

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}
function section(t) { console.log('\n' + '='.repeat(74) + '\n' + t + '\n' + '='.repeat(74)); }

const { sqlSafe, snapshot, assertInvariants } = require('./_hygiene.js');

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
  const u = (r.body.data || {}).user || {};
  return { token: r.body.data.token, userId: u.userId, deptName: u.deptName };
}

/* ---------------- 浏览器小工具 ---------------- */
/* ⚠ 菜单文案与原型不一致，必须用**运行时**的名字：
   原型里 index="approve" 那一项写的是「审批中心」，但注入脚本按 pageNames 把它改成了「待我审批」
   （`pageNames.approve='待我审批'`）。用原型文案点 = 点了个空，而且不会报错 ——
   后续所有断言会集体失败，看着像"功能坏了"，其实是"没走到那一页"。 */
const MENU_APPROVE = '待我审批';
const MENU_HOME = '首页';
const MENU_WORK = '工作台';
async function clickMenu(page, text) {
  const hit = await page.evaluate((t) => {
    const h = [...document.querySelectorAll('.el-menu-item, .el-sub-menu__title')]
      .find(i => i.textContent.includes(t));
    if (h) { h.click(); return t; }
    return '';
  }, text);
  await sleep(1800);
  return hit;
}

const VIS = 'e => e.getClientRects().length > 0';
async function visibleDialogs(page) {
  return page.evaluate((vis) => {
    const f = eval(vis);
    return [...document.querySelectorAll('.el-dialog, .el-drawer')].filter(f).length;
  }, VIS);
}
/** 在最后一个可见弹窗里按文字点按钮；点不到返回 false */
async function clickInDialog(page, text) {
  const ok = await page.evaluate((t) => {
    const vis = e => e.getClientRects().length > 0;
    const dlgs = [...document.querySelectorAll('.el-dialog, .el-drawer')].filter(vis);
    const dlg = dlgs[dlgs.length - 1];
    if (!dlg) return false;
    const b = [...dlg.querySelectorAll('.el-button')].find(x => x.textContent.replace(/\s+/g, '').includes(t));
    if (!b) return false;
    b.click();
    return true;
  }, text.replace(/\s+/g, ''));
  await sleep(700);
  return ok;
}
async function closeEverything(page) {
  for (let i = 0; i < 4; i++) {
    if (await visibleDialogs(page) === 0) return true;
    await page.keyboard.press('Escape');
    await sleep(600);
    if (await visibleDialogs(page) === 0) return true;
    await clickInDialog(page, '取消');
    await clickInDialog(page, '关闭');
  }
  return await visibleDialogs(page) === 0;
}

const ACCOUNT_NAME = {
  admin: '系统管理员', huangxm: '黄小明', linjl: '林经理', wangkj: '王会计',
  zhangzong: '张总', zhaocs: '赵出纳', chennk: '陈内控', zhouzh: '周综合', lifinance: '李财务'
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
  if (!picked) return { ok: false, who: '', reason: '门禁里没有「' + name + '」这个账号按钮' };
  await sleep(4600);
  const who = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  return { ok: who.length > 0, who, reason: who ? '' : '点了账号按钮但没进入系统' };
}

/* --------- 首页「我的待办」卡片 ---------
 * 卡片外层是 el-card，标题在 .card-title 里（运行时被改成「我的待办」）。
 * 注意必须**按卡片定位**再数 .todo：.todo 这个 class 在页面上不止一处。 */
async function readHomeTodoCard(page) {
  return page.evaluate(() => {
    const vis = e => e.getClientRects().length > 0;
    const cards = [...document.querySelectorAll('.el-card')].filter(vis);
    const card = cards.find(c => {
      const b = c.querySelector('.card-title b');
      return b && /待办/.test(b.textContent);
    });
    if (!card) return { found: false };
    const items = [...card.querySelectorAll('.todo')].filter(vis)
      .filter(e => !e.textContent.includes('当前没有待你处理的单据'));
    return {
      found: true,
      title: (card.querySelector('.card-title b') || {}).textContent,
      count: items.length,
      titles: items.map(e => (e.querySelector('b') || {}).textContent || ''),
      metas: items.map(e => (e.querySelector('span') || {}).textContent || ''),
      empty: !!card.querySelector('.todo') && items.length === 0
    };
  });
}
async function clickHomeTodoCard(page) {
  return page.evaluate(() => {
    const vis = e => e.getClientRects().length > 0;
    const cards = [...document.querySelectorAll('.el-card')].filter(vis);
    const card = cards.find(c => {
      const b = c.querySelector('.card-title b');
      return b && /待办/.test(b.textContent);
    });
    if (!card) return false;
    const it = [...card.querySelectorAll('.todo')].filter(vis)
      .find(e => !e.textContent.includes('当前没有待你处理的单据'));
    if (!it) return false;
    it.click();
    return true;
  });
}
async function drawerText(page) {
  return page.evaluate(() => {
    const vis = e => e.getClientRects().length > 0;
    const dlgs = [...document.querySelectorAll('.el-drawer, .el-dialog')].filter(vis);
    const d = dlgs[dlgs.length - 1];
    return d ? d.textContent.replace(/\s+/g, ' ').trim() : '';
  });
}
/** 驳回弹窗里「指定驳回层级」下拉的所有选项文本（下拉挂在 body 上的 popper 里） */
async function readRejectTargets(page) {
  const opened = await page.evaluate(() => {
    const vis = e => e.getClientRects().length > 0;
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(vis);
    const dlg = dlgs.find(d => d.textContent.includes('指定驳回层级'));
    if (!dlg) return 'no-dialog';
    const it = [...dlg.querySelectorAll('.el-form-item')].find(i => {
      const l = i.querySelector('.el-form-item__label');
      return l && l.textContent.trim().replace(/[:：]/g, '') === '指定驳回层级';
    });
    if (!it) return 'no-item';
    const w = it.querySelector('.el-select__wrapper') || it.querySelector('.el-select');
    if (!w) return 'no-select';
    w.click();
    return 'ok';
  });
  if (opened !== 'ok') return { opened, options: [] };
  await sleep(900);
  const options = await page.evaluate(() => {
    const vis = e => e.getClientRects().length > 0;
    const dd = [...document.querySelectorAll('.el-select-dropdown')].filter(vis);
    const last = dd[dd.length - 1];
    if (!last) return null;
    return [...last.querySelectorAll('.el-select-dropdown__item')].filter(vis)
      .map(e => e.textContent.replace(/\s+/g, ' ').trim());
  });
  return { opened, options: options || [] };
}
/** 把下拉关掉（点一次别处）——Element Plus 的 click-outside 会吃掉第一次点击 */
async function dismissDropdown(page) {
  await page.mouse.click(12, 300);
  await sleep(500);
}

/** 打开某个工作台分区里的「新建」按钮对应的发起弹窗（分区里两个按钮文案都是「新建」，只能按分区定位） */
async function openSubmitByZone(page, zoneClass) {
  return page.evaluate((zc) => {
    const vis = e => e.getClientRects().length > 0;
    const zone = [...document.querySelectorAll(zc)].filter(vis)[0];
    if (!zone) return false;
    const b = [...zone.querySelectorAll('.el-button')].filter(vis)[0];
    if (!b) return false;
    b.click();
    return true;
  }, zoneClass);
}
async function readSubmitFlowTip(page) {
  return page.evaluate(() => {
    const vis = e => e.getClientRects().length > 0;
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(vis);
    const dlg = dlgs.find(d => d.querySelector('.submit-form .flow-tip'));
    if (!dlg) return '';
    const tip = dlg.querySelector('.submit-form .flow-tip span');
    return tip ? tip.textContent.replace(/\s+/g, ' ').trim() : '';
  });
}
async function fillDialogField(page, label, value) {
  return page.evaluate((label, value) => {
    const vis = e => e.getClientRects().length > 0;
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(vis);
    const dlg = dlgs[dlgs.length - 1];
    if (!dlg) return false;
    const it = [...dlg.querySelectorAll('.el-form-item')].find(i => {
      const l = i.querySelector('.el-form-item__label');
      return l && l.textContent.trim().replace(/[:：]/g, '') === label;
    });
    if (!it) return false;
    const el = it.querySelector('input, textarea');
    if (!el) return false;
    const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('blur', { bubbles: true }));
    return true;
  }, label, value);
}

/* ========================================================================== */
(async () => {
  const browser = await puppeteer.launch({
    executablePath: EXEC, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1100 });

  const failures = [];
  page.on('pageerror', e => failures.push('pageerror: ' + e.message));

  /* 预览请求计数：用来证明"改金额会重新拉一次预览"（金额分支按 amount 判，只取一次会误导人） */
  let previewReqs = 0;
  page.on('request', r => {
    if (r.method() === 'POST' && /\/api\/flows\/configs\/\d+\/preview$/.test(r.url())) previewReqs++;
  });

  const baseline = snapshot('跑测试前');

  try {
    /* ================= 一、准备 ================= */
    section('一、准备');
    const lin = await login('linjl');
    const linTodos = (await api('GET', '/api/todos', lin.token)).body.data || [];
    const sealTodo = linTodos.find(t => t.docNo === 'YY202609180004');
    check('接口登录成功（linjl）', !!lin.token && /^\d+$/.test(String(lin.userId)), 'userId=' + lin.userId);
    check('林经理有 1 条用印待办 YY202609180004，且停在「直属部门负责人」',
      !!sealTodo && sealTodo.nodeKey === 'n2', sealTodo ? (sealTodo.nodeKey + '/' + sealTodo.nodeName) : '未找到');

    /* 该单据跑的是哪一版流程 —— 后面断言驳回目标要靠它 */
    const dtFlow = sqlSafe("SELECT flow_config_id FROM document_type WHERE id=3 AND deleted=0");
    const flowMeta = sqlSafe(`SELECT CONCAT(name,' v',version) FROM flow_config WHERE id=${dtFlow} AND deleted=0`);
    check('用印申请指向的流程配置存在（驳回目标要按这条流程生成）',
      /用印/.test(flowMeta) && flowMeta.includes('v'), 'document_type#3 → flow_config#' + dtFlow + ' = ' + flowMeta);

    const siteResp = await page.goto(PAGE, { waitUntil: 'networkidle2' });
    check('页面 HTTP 200', siteResp && siteResp.status() === 200, 'status=' + (siteResp && siteResp.status()));
    const g = await loginViaGate(page, 'linjl');
    check('登录门禁进入系统（林经理）', g.ok, g.who || g.reason);

    /* ================= 二、首页「我的待办」与 hero 同口径 ================= */
    section('二、首页「我的待办」卡片 = /api/todos（不再与 hero 数字打架）');
    await clickMenu(page, MENU_HOME);
    const heroTodo = await page.evaluate(() => {
      const vis = e => e.getClientRects().length > 0;
      const p = [...document.querySelectorAll('.hero p')].filter(vis)[0];
      if (!p) return null;
      const b = p.querySelector('b');
      const m = /(\d+)/.exec(b ? b.textContent : '');
      return m ? Number(m[1]) : null;
    });
    check('hero「今日有 N 项审批待处理」的数字 == /api/todos 条数',
      heroTodo !== null && heroTodo === linTodos.length,
      'hero=' + heroTodo + ' todos=' + linTodos.length);

    const card = await readHomeTodoCard(page);
    check('找得到「我的待办」卡片', card.found, card.found ? ('标题=' + card.title) : '未找到卡片');
    check('卡片条数 == min(待办数, 4)',
      card.count === Math.min(linTodos.length, 4),
      '卡片=' + card.count + ' 待办=' + linTodos.length);
    check('首条卡片的内容来自 /api/todos（标题 + 申请人 + 节点都对得上）',
      card.titles[0] === (linTodos[0] || {}).title
      && (card.metas[0] || '').includes((linTodos[0] || {}).applicantName)
      && (card.metas[0] || '').includes((linTodos[0] || {}).nodeName),
      '卡片首条=' + card.titles[0] + ' / ' + card.metas[0]);

    /* 注意：page.evaluate 里读不到外层常量（函数会被序列化），菜单名必须**当参数传进去** */
    const badge = await page.evaluate((menu) => {
      const it = [...document.querySelectorAll('.el-menu-item')].find(e => e.textContent.includes(menu));
      const b = it && it.querySelector('.el-badge__content');
      return b ? b.textContent.trim() : '';
    }, MENU_APPROVE);
    check('菜单角标与卡片同口径（都来自 todos）',
      badge === String(Math.min(linTodos.length, 99)),
      '角标=' + badge + ' todos=' + linTodos.length);

    /* ================= 三、待办卡片点得开（跨账号：待办 ≠ 可见单据） ================= */
    section('三、待办卡片点得开 —— 待办范围与 /api/documents 可见范围本来就不同');
    const zhou = await login('zhouzh');
    const zhouTodos = (await api('GET', '/api/todos', zhou.token)).body.data || [];
    const zhouDocs = await api('GET', '/api/documents?pageNum=1&pageSize=200', zhou.token);
    const zhouDocTotal = Number((zhouDocs.body.data || {}).total);
    check('周综合：有 N 条待办，但 /api/documents 可见 0 条（这正是列表依赖会翻车的地方）',
      zhouTodos.length > 0 && zhouDocTotal === 0,
      'todos=' + zhouTodos.length + ' documents.total=' + zhouDocTotal);

    const z = await loginViaGate(page, 'zhouzh');
    check('切换到周综合成功', z.ok, z.who || z.reason);
    await clickMenu(page, MENU_HOME);
    const zCard = await readHomeTodoCard(page);
    check('周综合首页也按待办渲染卡片', zCard.found && zCard.count === Math.min(zhouTodos.length, 4),
      '卡片=' + zCard.count + ' 待办=' + zhouTodos.length);

    const clicked = await clickHomeTodoCard(page);
    await sleep(2200);
    const zDrawer = await drawerText(page);
    const expectNo = (zhouTodos[0] || {}).docNo;
    check('点开待办卡片能打开详情（不是「未找到该单据」）',
      clicked && zDrawer.includes(expectNo) && !zDrawer.includes('未找到该单据'),
      '期望单号=' + expectNo + ' 抽屉首段=' + zDrawer.slice(0, 60));
    await closeEverything(page);

    /* ================= 四、驳回目标由「该单据在跑的版本」动态生成 ================= */
    section('四、驳回弹窗「指定驳回层级」不再是写死清单');
    const l2 = await loginViaGate(page, 'linjl');
    check('切回林经理', l2.ok, l2.who || l2.reason);
    await clickMenu(page, MENU_APPROVE);

    const rowHit = await page.evaluate((no) => {
      const vis = e => e.getClientRects().length > 0;
      const trs = [...document.querySelectorAll('.el-table__body tbody tr')].filter(vis);
      const tr = trs.find(t => t.textContent.includes(no));
      if (!tr) return 'no-row';
      const b = [...tr.querySelectorAll('.el-button')].find(x => x.textContent.includes('进入审批'));
      if (!b) return 'no-button';
      b.click();
      return 'ok';
    }, 'YY202609180004');
    await sleep(2200);
    check('在审批中心打开该待办（进入审批）', rowHit === 'ok', rowHit);

    const drawer2 = await drawerText(page);
    check('详情抽屉是该单据', drawer2.includes('YY202609180004'), drawer2.slice(0, 60));

    const rejectClicked = await page.evaluate(() => {
      const vis = e => e.getClientRects().length > 0;
      const dlgs = [...document.querySelectorAll('.el-drawer, .el-dialog')].filter(vis);
      const d = dlgs[dlgs.length - 1];
      if (!d) return 'no-drawer';
      const act = d.querySelector('.approval-action');
      if (!act) return 'no-action';
      const b = act.querySelector('.el-button--danger');
      if (!b) return 'no-reject-button';
      b.click();
      return 'ok';
    });
    await sleep(1400);
    const dlgOk = await page.evaluate(() => {
      const vis = e => e.getClientRects().length > 0;
      return [...document.querySelectorAll('.el-dialog')].filter(vis).some(x => x.textContent.includes('指定驳回层级'));
    });
    check('「驳回」按钮打开的是驳回弹窗（且已接上 openReject）',
      rejectClicked === 'ok' && dlgOk, rejectClicked + ' / dialog=' + dlgOk);

    /* 驳回原因必须是空的 —— openReject 统一重置过（驳回不可逆，带着上一单的理由提交是事故） */
    const reasonEmpty = await page.evaluate(() => {
      const vis = e => e.getClientRects().length > 0;
      const dlg = [...document.querySelectorAll('.el-dialog')].filter(vis).find(d => d.textContent.includes('指定驳回层级'));
      if (!dlg) return false;
      const it = [...dlg.querySelectorAll('.el-form-item')].find(i =>
        (i.querySelector('.el-form-item__label') || {}).textContent &&
        i.querySelector('.el-form-item__label').textContent.trim().replace(/[:：]/g, '') === '驳回原因');
      return it ? (it.querySelector('textarea') || {}).value === '' : false;
    });
    check('打开弹窗时「驳回原因」是空的（不会带上一单的理由）', reasonEmpty, 'val=""');

    const rt = await readRejectTargets(page);
    const opts = rt.options;
    const joined = opts.join(' | ');
    check('下拉能展开并读出选项', rt.opened === 'ok' && opts.length > 0, rt.opened + ' n=' + opts.length + ' :: ' + joined);
    check('第一项是「提交人（退回发起人，需重新提交）」',
      /提交人/.test(opts[0] || '') && (opts[0] || '').includes('退回发起人'), opts[0] || '(空)');
    check('含「综合管理部（重审）」（该单据这条流程的第 3 节点）',
      opts.some(o => o.includes('综合管理部')), joined);
    check('含「公司领导（重审）」（第 5 节点）',
      opts.some(o => o.includes('公司领导')), joined);
    check('含「用印办理（重审）」（第 6 节点，办理类节点也是合法退回目标）',
      opts.some(o => o.includes('用印办理')), joined);
    check('不含当前所在节点「直属部门负责人」（退回给自己没有意义）',
      !opts.some(o => o.includes('直属部门负责人')), joined);
    check('不含旧写死清单的「核算会计 / 内控合规 / 出纳」（那是日常付款才有的节点）',
      !opts.some(o => /核算会计|内控合规|出纳/.test(o)),
      '选项=' + opts.length + ' 项');
    check('选项数 == 流程节点(6) - 发起(1) - 网关(1) - 当前节点(1) + 提交人(1) == 4',
      opts.length === 4, '实际=' + opts.length);

    await dismissDropdown(page);
    check('取消驳回弹窗（不提交——驳回不可逆）', await closeEverything(page), '剩余可见弹窗=' + await visibleDialogs(page));
    await closeEverything(page);

    /* ================= 五、提交弹窗的流程预览 ================= */
    section('五、「提交审批」弹窗的「审批流程」行 = 服务端预览（含真实审批人）');
    await clickMenu(page, MENU_WORK);
    const openedSeal = await openSubmitByZone(page, '.zone.stamp');
    await sleep(2600);
    check('工作台「用印」分区的入口能点开发起弹窗', openedSeal, 'zone=.zone.stamp');

    const tip = await readSubmitFlowTip(page);
    check('流程预览行有内容（不是原型的写死文案）',
      tip.length > 0 && !tip.includes('核算会计 → 内控合规'), tip.slice(0, 90));
    check('预览包含该单据实际在跑的那条流程的节点名'
      + '（发起申请 / 直属部门负责人 / 综合管理部 / 公司领导 / 用印办理）',
      ['发起申请', '直属部门负责人', '综合管理部', '公司领导', '用印办理'].every(n => tip.includes(n)),
      tip.slice(0, 120));
    check('预览带上了服务端解析出的真实审批人姓名（周综合 / 张总）',
      tip.includes('周综合') && tip.includes('张总'), tip.slice(0, 120));
    check('预览里 0 处「待解析」（读错字段名时每一站都会退化成它）',
      !tip.includes('待解析'), '待解析出现 ' + (tip.match(/待解析/g) || []).length + ' 次');
    check('条件网关被标成「按条件分支」而不是伪装成人审节点',
      tip.includes('按条件分支'), tip.slice(0, 120));
    await closeEverything(page);

    section('五之二、金额变化必须重新拉预览（网关按 amount 判，只取一次会反过来误导人）');
    /* 先弄清 preview 到底返回什么 —— 这一步是**契约取证**，不是断言"我以为的行为"。
       第一次写这条断言时我假设"换个金额，返回的节点列表会变"，实测两条金额返回**完全一样**：
       接口给的是**整条配置链路**（含网关两侧的目标节点），不做分支裁剪。
       所以"金额变了为什么还要重取"的理由不是节点列表，而是**节点的审批人解析**会随上下文变
       （amount/formData 都进 AssigneeContext）。断言按实测的语义写，不按想象的写。 */
    const previewApiA = (await api('POST', '/api/flows/configs/1/preview', lin.token,
      { bizCategory: 'DAILY', amount: 500 })).body.data || {};
    const previewApiB = (await api('POST', '/api/flows/configs/1/preview', lin.token,
      { bizCategory: 'DAILY', amount: 25000 })).body.data || {};
    const namesA = (previewApiA.nodes || []).map(n => n.nodeName).join(',');
    const namesB = (previewApiB.nodes || []).map(n => n.nodeName).join(',');
    const gw = (previewApiA.nodes || []).find(n => n.nodeType === 3);
    check('接口层：preview 返回的是**完整配置链路**（网关两侧目标都在），不按金额裁剪',
      namesA === namesB && namesA.includes('公司领导（大额）') && namesA.includes('出纳付款'),
      '500: ' + namesA);
    check('网关节点带 conditionExpr（前端可据此说明"大额走公司领导"）',
      !!gw && /doc\.amount\s*>=\s*20000/.test(gw.conditionExpr || ''),
      gw ? String(gw.conditionExpr).slice(0, 70) : '未找到网关节点');

    previewReqs = 0;
    await clickMenu(page, MENU_WORK);
    await openSubmitByZone(page, '.zone.daily');
    await sleep(2600);
    const reqOpened = previewReqs;
    const filled = await fillDialogField(page, '申请金额（元）', '25000');
    await sleep(1800);
    const reqChanged = previewReqs;
    check('界面上改「申请金额」会重新请求一次预览（@change 已接上）',
      filled && reqOpened >= 1 && reqChanged > reqOpened,
      '填值=' + filled + ' 打开时=' + reqOpened + ' 改后=' + reqChanged);

    const dailyTip = await readSubmitFlowTip(page);
    check('日常付款的预览行也来自服务端（含「发起人」节点，而非空）',
      dailyTip.includes('发起人'), dailyTip.slice(0, 80));
    await closeEverything(page);

    /* ================= 六、清理与基线 ================= */
    section('六、演示库基线');
    check('全程只读：没有 console/page 错误', failures.length === 0, failures.slice(0, 3).join(' ; ') || '无');
    const after = snapshot('跑测试后');
    check('演示状态快照前后一致', after === baseline, after === baseline ? '' : ('\n  before: ' + baseline + '\n  after : ' + after));
    assertInvariants(after, check);
  } catch (e) {
    console.error('\n✗ 脚本异常中断：' + (e && e.stack ? e.stack : e));
    results.push({ name: '脚本异常中断', ok: false });
  } finally {
    await browser.close();
  }

  const pass = results.filter(r => r.ok).length;
  console.log('\n' + '='.repeat(74));
  /* 末行格式必须与 run_all_e2e.sh 的核对正则一致（`X/Y 通过（预期 N 条）`）——
     否则运行器读不到自证结论，会把"跑完了但没声明条数"当成异常套件。 */
  console.log('P1 交付评审 E2E：' + pass + '/' + results.length + ' 通过（预期 ' + EXPECTED_TOTAL + ' 条）');
  const failed = results.filter(r => !r.ok);
  if (failed.length) failed.forEach(f => console.log('  ✗ ' + f.name));
  if (results.length !== EXPECTED_TOTAL) {
    console.log('  ✗ 断言条数不符：实际 ' + results.length + '，期望 ' + EXPECTED_TOTAL
      + '（少跑=红灯：说明有分支没执行到）');
    process.exit(1);
  }
  process.exit(failed.length ? 1 : 0);
})();

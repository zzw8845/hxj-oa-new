/*
 * 管理端 UI 端到端：人员 / 角色 / 流程三块的真实可用性。
 *
 * 这轮改动的核心是把三个 notImplemented 桩换成真实后端调用，
 * 所以验证重点不是"页面能打开"，而是：
 *   1. 弹窗字段与后端契约对得上（部门/角色传 ID 与编码，不再是中文名）
 *   2. 下拉选项来自后端（权限点目录、流程节点模板库）
 *   3. 列表展示的是后端真实下发的数据（部门名、岗位名、角色名、数据范围、成员）
 *   4. 保存动作真的落库（走一次完整的新增员工闭环）
 */
const puppeteer = require('puppeteer-core');
const { execSync } = require('child_process');
/** 最小 SQL 助手：本套件不需要 hygiene，但用印闭环的收尾必须能物理还原状态 */
function sql(stmt) {
  return execSync('mysql -uroot haixiajin_oa -N -B -e ' + JSON.stringify(stmt),
    { encoding: 'utf-8' }).trim();
}
const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE = 'http://127.0.0.1:8080/oa.html';
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* 预期断言总数：脚本正常跑完必须**恰好**产出这么多条。
   为什么要把这个数写死在代码里：本项目出过一次「假绿灯」—— 上游某条断言依赖的接口被回退后
   抛错中断，导致其后 16 条断言（含整条审计留痕链路）**从未执行**，而末行照样打印
   "85/85 通过"。有了这个数，任何"少跑了"都会立刻变成红灯，而不是无声无息。 */
const EXPECTED_TOTAL = 88;

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}

/* ---------- 页面内小工具（在浏览器上下文执行） ---------- */

// 按 label 文本找 el-form-item 里的原生 input
const FIND_INPUT = `
function findInput(label){
  const items = [...document.querySelectorAll('.el-dialog .el-form-item')];
  const it = items.find(i => {
    const l = i.querySelector('.el-form-item__label');
    return l && l.textContent.trim().replace(/[:：]/g,'') === label;
  });
  return it ? it.querySelector('input') : null;
}
`;

async function clickMenu(page, text) {
  await page.evaluate((t) => {
    const h = [...document.querySelectorAll('.el-menu-item, .el-sub-menu__title')].find(i => i.textContent.includes(t));
    if (h) h.click();
  }, text);
  await sleep(1800);
}

async function clickButtonByText(page, text, scope) {
  return page.evaluate((t, s) => {
    const root = s ? document.querySelector(s) : document;
    if (!root) return false;
    const b = [...root.querySelectorAll('.el-button')].find(x => x.textContent.replace(/\s+/g, '').includes(t));
    if (b) { b.click(); return true; }
    return false;
  }, text.replace(/\s+/g, ''), scope || null);
}

// 打开 el-select 并选中指定文本的选项
async function pickSelect(page, label, optionText) {
  await page.evaluate(new Function('label', `
    ${FIND_INPUT}
    const items = [...document.querySelectorAll('.el-dialog .el-form-item')];
    const it = items.find(i => {
      const l = i.querySelector('.el-form-item__label');
      return l && l.textContent.trim().replace(/[:：]/g,'') === label;
    });
    if (it) {
      const w = it.querySelector('.el-select__wrapper') || it.querySelector('.el-select');
      if (w) w.click();
    }
  `), label);
  await sleep(600);
  return page.evaluate((t) => {
    const opts = [...document.querySelectorAll('.el-select-dropdown__item')].filter(o => o.offsetParent !== null);
    const o = opts.find(x => x.textContent.trim() === t) || opts[0];
    if (o) { o.click(); return o.textContent.trim(); }
    return null;
  }, optionText);
}

async function fillInput(page, label, value) {
  return page.evaluate(new Function('label', 'value', `
    ${FIND_INPUT}
    const el = findInput(label);
    if (!el) return false;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  `), label, value);
}

// 关掉当前所有可见弹窗。
// 必要性：弹窗是 append-to-body 且带遮罩的，一旦上一个用例失败把弹窗留在打开状态，
// 后续点 tab、点菜单都会被遮罩吃掉 —— 失败会像滚雪球一样扩散成一片假阴性。
async function closeDialogs(page) {
  const n = await page.evaluate(() => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null);
    let hit = 0;
    dlgs.forEach(d => {
      const b = [...d.querySelectorAll('.el-button')].find(x => /取消|关闭/.test(x.textContent));
      if (b) { b.click(); hit++; }
    });
    return hit;
  });
  await sleep(700);
  return n;
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: EXEC, headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1680, height: 1050 });

  // 记录所有 /api 请求：用来证明人员表格走的是**服务端分页**接口，
  // 而不是把全量拉下来在本地切片（那样搜索只能搜到已加载的那一批人）。
  const apiCalls = [];
  page.on('request', r => {
    const u = r.url() || '';
    if (u.indexOf('/api/') >= 0) apiCalls.push(u);
  });

  const errs = [];
  page.on('console', m => { if (m.type() === 'error') errs.push('[console] ' + m.text().slice(0, 220)); });
  page.on('pageerror', e => errs.push('[pageerror] ' + e.message.slice(0, 220)));
  page.on('requestfailed', r => errs.push('[reqfail] ' + r.method() + ' ' + r.url().slice(0, 110)));

  console.log('\n=== 1. 加载与登录 ===');
  const resp = await page.goto(PAGE, { waitUntil: 'networkidle2', timeout: 40000 });
  check('页面 HTTP 200', resp && resp.status() === 200, 'status=' + (resp && resp.status()));
  await sleep(2600);

  let loggedIn = false;
  for (const c of await page.$$('.lg-chips button')) {
    const t = await c.evaluate(e => e.textContent);
    if (t.includes('admin') || t.includes('管理员')) { await c.click(); loggedIn = true; break; }
  }
  check('选中演示账号 admin 登录', loggedIn);
  await sleep(4200);
  const who = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  check('已进入系统', who.length > 0, who);

  console.log('\n=== 2. 人员管理 ===');
  await clickMenu(page, '权限管理');
  await sleep(1200);

  const headers = await page.$$eval('.el-table__header th', els => els.map(e => e.textContent.trim())).catch(() => []);
  check('人员表头含「登录账号」列', headers.includes('登录账号'), headers.join(' | '));
  check('人员表头含「分配角色」列', headers.includes('分配角色'), headers.join(' | '));

  const rows = await page.$$eval('.el-table__body tbody tr', els => els.length).catch(() => 0);
  check('人员表有真实数据行', rows >= 8, '行数=' + rows);

  /* ---- 人员管理：服务端分页 + 服务端搜索 ----
     判定不靠"看起来能翻页"，靠**请求**：
      · 加载时打过 /api/users/page，说明表格数据源是分页接口而非全量；
      · 输入关键字后又打了一次带 keyword 的 /api/users/page，说明搜索在后端做
        —— 若只是前端过滤，这里不会有新请求，且只能搜到已加载的那一批人。 */
  const pageCalls = apiCalls.filter(u => u.indexOf('/api/users/page') >= 0);
  check('人员表格走的是服务端分页接口（加载时打过 /api/users/page）', pageCalls.length >= 1,
    '命中 ' + pageCalls.length + ' 次' + (pageCalls[0] ? '，首条=' + pageCalls[0].slice(-80) : ''));

  const kwBox = 'input[placeholder^="搜索姓名"]';
  const hasKwBox = await page.$(kwBox);
  check('人员表格带搜索框（按姓名/工号/账号搜）', !!hasKwBox);
  if (hasKwBox){
    const before = apiCalls.filter(u => u.indexOf('/api/users/page') >= 0 && u.indexOf('keyword=') >= 0).length;
    await page.click(kwBox, { clickCount: 3 }).catch(function(){});
    await page.type(kwBox, '黄小明', { delay: 60 }).catch(function(){});
    await sleep(1400);
    const after = apiCalls.filter(u => u.indexOf('/api/users/page') >= 0 && u.indexOf('keyword=') >= 0);
    check('★ 搜索走后端：输入关键字后确实请求了带 keyword 的分页接口', after.length > before,
      '带 keyword 的请求数 ' + before + ' → ' + after.length + (after.length ? '，末条=' + after[after.length-1].slice(-90) : ''));

    const filteredRows = await page.$$eval('.el-table__body tbody tr', els => els.length).catch(() => -1);
    check('★ 搜索后表格收敛到过滤结果（黄小明应为 1 行）', filteredRows === 1, '行数=' + filteredRows);

    /* 必须清空并等它刷新：不清的话表格一直停在"只有 1 行"的过滤态，
       后面那些「角色列/部门列/新建员工/删除员工」的断言就全在 1 行的表上跑 ——
       一次新增断言把自己后面的旧断言全打挂，看着像大面积回归，其实只是没收尾。 */
    /* 直接置空并派发 input：用"三击全选 + Backspace"不可靠（实测只删掉一个字符，
       剩「黄小」仍然只匹配到 1 行，看起来就像清空失败）。Vue 的 v-model 靠 input
       事件更新，所以手动派发一个最稳。 */
    await page.$eval(kwBox, function(el){
      el.value = '';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }).catch(function(){});
    await sleep(1400);
    const restored = await page.$$eval('.el-table__body tbody tr', els => els.length).catch(() => -1);
    check('清空关键字后表格恢复（否则后续断言都在过滤态上跑）', restored >= 8, '行数=' + restored);
  } else {
    check('（跳过）没有搜索框', false);
    check('（跳过）没有搜索框，无法验证清空', false);
  }

  const roleCells = await page.$$eval('.el-table__body tbody tr', trs =>
    trs.map(tr => (tr.querySelectorAll('td')[5] || {}).textContent || '').map(s => s.trim())
  ).catch(() => []);
  const withRole = roleCells.filter(t => t && t !== '—' && t.length > 0);
  check('「分配角色」列显示后端角色名（不再空白）', withRole.length >= 7,
    '有角色的行数=' + withRole.length + '，例：' + roleCells.slice(0, 3).join(' / '));

  // 列序：姓名(0) 工号(1) 登录账号(2) 部门(3) 岗位(4) 分配角色(5) 状态(6) 操作(7)
  // 注意 trim 要作用在每个单元格上：写成 trs.map(...).trim() 会对数组调 trim，
  // 直接抛 TypeError 并被 .catch 吞成空数组，表现成「整列为空」的假阴性。
  const deptCells = await page.$$eval('.el-table__body tbody tr', trs =>
    trs.map(tr => ((tr.querySelectorAll('td')[3] || {}).textContent || '').trim())
  ).catch(() => []);
  check('「部门」列显示后端部门名', deptCells.filter(Boolean).length >= 8, deptCells.slice(0, 4).join(' / '));

  // 打开「增加员工」弹窗，核对字段
  await clickButtonByText(page, '增加员工');
  await sleep(1000);
  const dlgLabels = await page.evaluate(() =>
    [...document.querySelectorAll('.el-dialog .el-form-item__label')].map(e => e.textContent.trim().replace(/[:：]/g, ''))
  );
  for (const need of ['姓名', '工号', '登录账号', '初始密码', '所属部门', '岗位', '分配角色']) {
    check('  弹窗含字段「' + need + '」', dlgLabels.includes(need), dlgLabels.join(' | '));
  }
  const accountCount = dlgLabels.filter(l => l === '登录账号').length;
  check('  「登录账号」字段不重复（修掉了二次注入）', accountCount === 1, '出现 ' + accountCount + ' 次');
  const pwdCount = dlgLabels.filter(l => l === '初始密码' || l === '登录密码').length;
  check('  密码字段不重复', pwdCount === 1, '出现 ' + pwdCount + ' 次');

  // 部门下拉必须来自后端真实部门（含"财务核算部"这类库里的名字）
  const pickedDept = await pickSelect(page, '所属部门', '财务核算部');
  check('  部门下拉选项来自后端', !!pickedDept, '选中：' + pickedDept);

  const pickedRole = await pickSelect(page, '分配角色', '普通员工');
  check('  角色下拉选项来自后端', !!pickedRole, '选中：' + pickedRole);

  await page.screenshot({ path: '/tmp/proto/shots2/admin-person.png' });

  console.log('\n=== 3. 走一次完整的新增员工（UI → 落库） ===');
  const stamp = String(Date.now()).slice(-6);
  const uiAccount = 'ui_' + stamp;
  await fillInput(page, '姓名', 'UI测试员' + stamp);
  await fillInput(page, '工号', 'UI' + stamp);
  await fillInput(page, '登录账号', uiAccount);
  await fillInput(page, '初始密码', '123456');
  // 用**已存在**的岗位名：后端会按名字解析岗位，名字不存在时会顺手新建一条岗位记录，
  // 而这个套件不负责清理它 ⇒ 每跑一次演示库就多一条「UI测试岗」（历史上就是这么堆出来的）。
  // 本套件的目的是「走通 UI → 落库」，不是测自动建岗，所以填一个真实存在的岗位。
  await fillInput(page, '岗位', '普通员工');
  await sleep(400);
  await clickButtonByText(page, '保存员工', '.el-dialog');
  await sleep(3200);

  const toast = await page.evaluate(() => {
    const m = [...document.querySelectorAll('.el-message')].map(e => e.textContent.trim());
    return m.join(' | ');
  });
  check('保存后有成功提示', toast.includes('已创建') || toast.includes('成功'), toast.slice(0, 80));



  /* 表格已是**服务端分页**：新员工不一定落在当前页。
     所以按账号走服务端搜索来定位 —— 这同时也验证了「新建 → 搜索能搜到」这条闭环。 */
  await page.$eval(kwBox, function(el, v){
    el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }, uiAccount).catch(function(){});
  await sleep(1400);
  const afterRows = await page.$$eval('.el-table__body tbody tr', trs =>
    trs.map(tr => tr.textContent)
  ).catch(() => []);
  check('新员工出现在列表中（按账号服务端搜索定位）',
    afterRows.some(t => t.includes(uiAccount)) && afterRows.length <= 2,
    '过滤后行数=' + afterRows.length);

  // 收尾：用 UI 删掉刚才新增的员工。
  // 一是顺带覆盖删除路径（含二次确认弹窗），二是别把测试数据留在演示库里。
  const delClicked = await page.evaluate((acct) => {
    const trs = [...document.querySelectorAll('.el-table__body tbody tr')];
    const tr = trs.find(t => t.textContent.includes(acct));
    if (!tr) return false;
    const b = [...tr.querySelectorAll('button')].find(x => x.textContent.includes('删除'));
    if (b) { b.click(); return true; }
    return false;
  }, uiAccount);
  await sleep(1000);
  const delConfirmed = await page.evaluate(() => {
    const box = document.querySelector('.el-message-box');
    if (!box) return false;
    const b = [...box.querySelectorAll('.el-button')].find(x => /确认删除|确定/.test(x.textContent));
    if (b) { b.click(); return true; }
    return false;
  });
  await sleep(2600);
  const rowsAfterDelete = await page.$$eval('.el-table__body tbody tr', trs => trs.map(t => t.textContent))
    .catch(() => []);
  check('删除刚建的员工（UI 闭环 + 不残留测试数据）',
    delClicked && delConfirmed && !rowsAfterDelete.some(t => t.includes(uiAccount)),
    '点删除=' + delClicked + ' 确认=' + delConfirmed + '，剩余行数=' + rowsAfterDelete.length);

  /* 清空搜索框：删除是在"按账号过滤"的视图里做的，不清空的话
     表格一直停留在空结果上，后面「角色管理」等断言又会被殃及。 */
  await page.$eval(kwBox, function(el){
    el.value = '';
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }).catch(function(){});
  await sleep(1200);

  console.log('\n=== 4. 角色管理 ===');
  // 人员弹窗保存成功后会自动关闭；这里再兜一次底，避免残留弹窗挡住 tab 切换
  await closeDialogs(page);

  // 换到「角色管理」tab。
  // 注意：角色面板在运行时被注入成 role-tree-card 树（不是模板里的 .role-grid 卡片），
  // 所以断言要打在树上真实渲染的节点 .role-tree-node.role / .role-tree-node.department 上。
  await page.evaluate(() => {
    const tab = [...document.querySelectorAll('.el-tabs__item')].find(t => t.textContent.includes('角色管理'));
    if (tab) tab.click();
  });
  await sleep(1600);

  const roleNodes = await page.evaluate(() =>
    [...document.querySelectorAll('.role-tree-node.role')].map(n => n.textContent.replace(/\s+/g, ' ').trim())
  );
  const deptNodes = await page.evaluate(() =>
    [...document.querySelectorAll('.role-tree-node.department')].map(n => n.textContent.replace(/\s+/g, ' ').trim())
  );
  check('角色树渲染出角色节点', roleNodes.length >= 8,
    '角色节点=' + roleNodes.length + '，部门节点=' + deptNodes.length + '；例：' + (roleNodes[0] || '').slice(0, 80));
  check('角色节点显示真实数据范围（非占位文案）',
    roleNodes.some(t => /本人单据|本部门单据|本中心单据|全公司单据/.test(t)),
    roleNodes.slice(0, 2).join(' || ').slice(0, 140));
  check('角色节点显示对应成员', roleNodes.some(t => t.includes('对应成员：')),
    (roleNodes.find(t => t.includes('对应成员：')) || '(无)').slice(0, 110));
  check('角色节点显示权限点中文名', roleNodes.some(t => /查看|审批|管理|发起|提交|上传/.test(t)),
    (roleNodes[0] || '').slice(0, 110));
  await page.screenshot({ path: '/tmp/proto/shots2/admin-role.png' });

  // 新增角色弹窗：权限勾选项必须来自后端权限点目录（GET /api/permissions）
  await clickButtonByText(page, '新增角色');
  await sleep(1200);
  const roleDlg = await page.evaluate(() => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null);
    const dlg = dlgs.find(d => d.textContent.includes('角色名称'));
    if (!dlg) return { found: false, labels: [], checks: [] };
    return {
      found: true,
      labels: [...dlg.querySelectorAll('.el-form-item__label')].map(e => e.textContent.trim().replace(/[:：]/g, '')),
      checks: [...dlg.querySelectorAll('.el-checkbox__label')].map(e => e.textContent.trim())
    };
  });
  check('新增角色弹窗打开且含「权限配置」',
    roleDlg.found && roleDlg.labels.some(l => l.includes('权限配置')), roleDlg.labels.join(' | '));
  check('权限勾选项来自后端权限点目录（含「用户管理/角色管理」）',
    roleDlg.checks.some(c => c.includes('用户管理')) && roleDlg.checks.some(c => c.includes('角色管理')),
    '共 ' + roleDlg.checks.length + ' 个，例：' + roleDlg.checks.slice(0, 4).join('/'));
  check('角色弹窗含「数据范围」', roleDlg.labels.some(l => l.includes('数据范围')), roleDlg.labels.join(' | '));
  await closeDialogs(page);

  console.log('\n=== 5. 流程管理 ===');
  await closeDialogs(page);
  // 菜单项文案就是「流程管理」（此前误写成「风险预警」，用户根本找不到入口）
  await clickMenu(page, '流程管理');
  await sleep(1500);
  const flowCards = await page.evaluate(() =>
    [...document.querySelectorAll('.flow-config-grid .el-card')].map(c => c.textContent.replace(/\s+/g, ' '))
  );
  check('流程卡片渲染', flowCards.length >= 3, '卡片数=' + flowCards.length);
  check('流程卡片显示审批链路', flowCards.some(c => c.includes('→')),
    (flowCards[0] || '').slice(0, 110));

  // 菜单文案必须和页面标题一致，否则「要找流程管理」的人会找不到入口
  const flowTitle = await page.$eval('.topbar h1', e => e.textContent.trim()).catch(() => '');
  check('流程管理页标题与菜单文案一致', flowTitle === '流程管理', 'topbar h1=' + flowTitle);

  await clickButtonByText(page, '修改流程');
  await sleep(1200);
  const flowDlg = await page.evaluate(() => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null);
    const dlg = dlgs[dlgs.length - 1];
    if (!dlg) return { labels: [], selects: 0, text: '', full: '' };
    const full = dlg.textContent.replace(/\s+/g, ' ');
    return {
      labels: [...dlg.querySelectorAll('.el-form-item__label')].map(e => e.textContent.trim().replace(/[:：]/g, '')),
      selects: dlg.querySelectorAll('.el-select').length,
      // text 只用于失败时的片段回显；内容断言必须用 full ——
      // 弹窗现在有节点行，固定截断 240 字会把底部的版本提示切掉（曾经误报过一次）
      text: full.slice(0, 240),
      full: full
    };
  });
  check('流程弹窗含「关联单据类型」', flowDlg.labels.some(l => l.includes('关联单据类型')), flowDlg.labels.join(' | '));  check('流程弹窗含「审批节点」', flowDlg.labels.some(l => l.includes('审批节点')), flowDlg.labels.join(' | '));
  check('弹窗提示了版本影响', /新版本|部署/.test(flowDlg.full || ''), (flowDlg.text || '').slice(0, 120));

  // 节点下拉必须来自后端模板库（说明文案里带"取发起人所在部门"这类规则描述）
  await page.evaluate(() => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null);
    const dlg = dlgs[dlgs.length - 1];
    const items = [...dlg.querySelectorAll('.el-form-item')];
    const it = items.find(i => (i.querySelector('.el-form-item__label') || {}).textContent
      && i.querySelector('.el-form-item__label').textContent.includes('审批节点'));
    if (it) {
      const w = it.querySelector('.el-select__wrapper');
      if (w) w.click();
    }
  });
  await sleep(900);
  const nodeOpts = await page.evaluate(() =>
    [...document.querySelectorAll('.el-select-dropdown__item')].filter(o => o.offsetParent !== null)
      .map(o => o.textContent.replace(/\s+/g, ' ').trim())
  );
  check('节点下拉来自后端模板库', nodeOpts.length >= 10, '选项数=' + nodeOpts.length);
  check('节点选项带审批人规则说明',
    nodeOpts.some(o => /取发起人所在部门|按发起人部门|沿用已有/.test(o)),
    nodeOpts.slice(0, 3).join(' || ').slice(0, 150));

  /* ---- 条件分支编辑器（本次新增：客户可自助配"金额超过多少走谁"） ----
     只打开看渲染与交互，**不保存** —— 保存会生成新版本并污染演示流程，
     写路径与清理由 verify_flow_branch_admin 覆盖。 ---- */
  const branchUi = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    if (!dlg) return { rows: 0, gwRows: 0, branchRows: 0, tags: [] };
    return {
      rows: dlg.querySelectorAll('.node-row').length,
      gwRows: dlg.querySelectorAll('.node-row.is-gw').length,
      branchRows: dlg.querySelectorAll('.node-row.is-gw .branch-row').length,
      tags: [...dlg.querySelectorAll('.node-row .el-tag')].map(e => e.textContent.trim())
    };
  });
  check('流程弹窗按「节点行」渲染（不再是一个多选下拉）', branchUi.rows >= 3,
    JSON.stringify(branchUi));
  check('演示流程的条件分支节点被识别，并展开出分支条件行',
    branchUi.gwRows >= 1 && branchUi.branchRows >= 2, JSON.stringify(branchUi));
  check('节点行标出类型（含「条件分支」）',
    branchUi.tags.includes('条件分支') && branchUi.tags.length >= 3, branchUi.tags.join('/'));

  /* 分支目标下拉必须只列「本条件分支之后」的节点 —— 这是禁止回跳、
     从结构上排除死循环的界面体现。只认 "3. 节点名" 这种编号选项，
     以免和上一步还开着的节点名下拉串味。 */
  const gwSeq = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    const gw = dlg.querySelector('.node-row.is-gw');
    if (!gw) return null;
    const w = gw.querySelectorAll('.branch-row .el-select__wrapper');
    if (w.length) w[0].click();
    return parseInt(gw.querySelector('.node-seq').textContent.trim(), 10);
  });
  await sleep(900);
  const numberedTargets = await page.evaluate(() =>
    [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter(o => o.offsetParent !== null)
      .map(o => o.textContent.replace(/\s+/g, ' ').trim())
      .filter(t => /^\d+\./.test(t))
  );
  check('分支目标只列条件分支之后的节点（禁止回跳 → 结构上排除死循环）',
    numberedTargets.length >= 1 && numberedTargets.every(t => parseInt(t, 10) > gwSeq),
    '网关在第 ' + gwSeq + ' 行，可选目标=' + numberedTargets.join(' || ').slice(0, 150));

  /* 「＋ 添加条件分支」的一次点击：应同时补上承接节点，并把「否则」指向它 */
  await page.keyboard.press('Escape').catch(() => {});
  await sleep(400);
  const addClicked = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    const b = [...dlg.querySelectorAll('.node-add button')].find(x => x.textContent.includes('添加条件分支'));
    if (!b) return false;
    b.click();
    return true;
  });
  await sleep(700);
  const afterAdd = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    const rows = [...dlg.querySelectorAll('.node-row')];
    const gw = rows[rows.length - 2];              // 新增的条件分支，其后是承接节点
    const msg = [...document.querySelectorAll('.el-message')].map(e => e.textContent).join(' ');
    if (!gw) return { rows: rows.length, gw: false, branchRows: 0, pickers: 0, msg: msg };
    return {
      rows: rows.length,
      gw: gw.classList.contains('is-gw'),
      branchRows: gw.querySelectorAll('.branch-row').length,
      pickers: gw.querySelectorAll('.branch-row .el-select__wrapper').length,
      msg: msg
    };
  });
  check('「＋ 添加条件分支」按钮存在且可点', addClicked, '');
  check('新增条件分支：行数 +2（条件分支 + 承接节点）',
    afterAdd.rows === branchUi.rows + 2, '由 ' + branchUi.rows + ' → ' + afterAdd.rows);
  check('新增的条件分支自带条件行与目标选择器',
    afterAdd.gw && afterAdd.branchRows === 2 && afterAdd.pickers === 2, JSON.stringify(afterAdd));
  check('新增时说明了为什么多出一个承接节点',
    /承接节点/.test(afterAdd.msg || ''), (afterAdd.msg || '').slice(0, 90));

  await page.screenshot({ path: '/tmp/proto/shots2/admin-flow.png' });

  /* ---- 节点指派界面（本次新增：接现成的 PUT /configs/{id}/assignees） ----
     只打开看渲染，**不保存** —— 改审批人的写路径由 verify_flow_assignee_admin 覆盖
     （那条脚本自己会还原演示库规则，UI 这里点保存会污染演示数据）。 ---- */
  await closeDialogs(page);
  const asgEntry = await page.evaluate(() => {
    const b = [...document.querySelectorAll('.el-main button')].find(x => x.textContent.includes('节点指派'));
    if (b) { b.click(); return true; }
    return false;
  });
  await sleep(2200);
  check('流程卡片有「节点指派」入口', asgEntry, '');
  const asgDlg = await page.evaluate(() => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null);
    const dlg = dlgs.find(d => d.textContent.includes('节点指派'));
    if (!dlg) return { open: false, blocks: 0, types: 0 };
    return {
      open: true,
      blocks: [...dlg.querySelectorAll('.el-table')].length,
      types: [...dlg.querySelectorAll('.el-select')].length
    };
  });
  check('节点指派弹窗按节点列出规则编辑器', asgDlg.open && asgDlg.blocks >= 3, JSON.stringify(asgDlg));
  await closeDialogs(page);

  console.log('\n=== 6. 用印台账与归还闭环（后端 seal_apply/seal_record 的可视化验证） ===');
  await page.reload({ waitUntil: 'networkidle2' });
  await sleep(2600);
  const sealMenuHit = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.el-menu .el-menu-item, .el-menu li')];
    const h = items.find(i => i.getClientRects().length > 0 && i.textContent.includes('用印台账'));
    if (h) { h.click(); return h.textContent.trim(); }
    return '';
  });
  await sleep(2200);
  check('菜单可进入「用印台账」', sealMenuHit.length > 0, sealMenuHit);

  const sealTitle = await page.evaluate(() => {
    const h = document.querySelector('.el-main h2');
    return h ? h.textContent.trim() : '';
  });
  check('  页面标题为「用印台账」', sealTitle === '用印台账', sealTitle);

  const sealCalls = apiCalls.filter(u => u.indexOf('/api/seals') >= 0);
  check('  台账数据来自服务端接口 /api/seals（不是前端造的假数据）',
    sealCalls.length >= 1, '命中 ' + sealCalls.length + ' 次');

  /**
   * 按按钮文案找台账行。
   *
   * ⚠ 不能图省事用"第一行"：台账按创建时间倒序，第一行可能是**上一次跑测试留下来的**
   * 已归还记录（实测踩过：断言因此失败，而功能其实是对的）。
   * 断言必须对起始状态鲁棒 —— 找"当前处于目标状态的那一行"。
   */
  const findSealRow = (btnText) => page.evaluate((t) => {
    const trs = [...document.querySelectorAll('.el-table__body tbody tr')];
    for (const tr of trs) {
      const btns = [...tr.querySelectorAll('button')].map(b => b.textContent.trim());
      if (btns.some(b => b.indexOf(t) >= 0)) {
        return { text: tr.textContent.replace(/\s+/g, ' ').trim(), buttons: btns };
      }
    }
    return null;
  }, btnText);
  const readFirstSealRow = () => page.evaluate(() => {
    const tr = document.querySelector('.el-table__body tbody tr');
    if (!tr) return null;
    return {
      text: tr.textContent.replace(/\s+/g, ' ').trim(),
      buttons: [...tr.querySelectorAll('button')].map(b => b.textContent.trim())
    };
  });

  const row0 = await findSealRow('登记用印');
  check('★ 存在「待用印」记录且带「登记用印」按钮',
    !!row0 && row0.text.indexOf('待用印') >= 0 && row0.buttons.some(b => b.indexOf('登记用印') >= 0),
    row0 ? (row0.buttons.join('/') + ' | ' + row0.text.slice(0, 60)) : '(台账为空)');

  const sealedBefore = sql('SELECT COUNT(*) FROM seal_record');
  // 点「登记用印」
  await page.evaluate(() => {
    const trs = [...document.querySelectorAll('.el-table__body tbody tr')];
    const tr = trs.find(t => [...t.querySelectorAll('button')].some(b => b.textContent.includes('登记用印')));
    const b = tr && [...tr.querySelectorAll('button')].find(x => x.textContent.includes('登记用印'));
    if (b) b.click();
  });
  await sleep(2200);
  const row1 = await findSealRow('归还');
  check('★ 点「登记用印」后状态变为「已用印」（并写了用印时间）',
    !!row1 && row1.text.indexOf('已用印') >= 0 && /\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(row1.text),
    row1 ? row1.text.slice(0, 90) : '(无行)');

  const sealedAfter = sql('SELECT COUNT(*) FROM seal_record');
  check('  后端台账 seal_record 落了一条 use 记录',
    Number(sealedAfter) === Number(sealedBefore) + 1,
    sealedBefore + ' → ' + sealedAfter);

  // 点「归还」
  await page.evaluate(() => {
    const trs = [...document.querySelectorAll('.el-table__body tbody tr')];
    const tr = trs.find(t => [...t.querySelectorAll('button')].some(b => b.textContent.includes('归还')));
    const b = tr && [...tr.querySelectorAll('button')].find(x => x.textContent.includes('归还'));
    if (b) b.click();
  });
  await sleep(2200);
  const row2 = await readFirstSealRow();
  check('★ 点「归还」后状态变为「已归还」且显示「闭环已完成」',
    !!row2 && row2.text.indexOf('已归还') >= 0 && row2.text.indexOf('闭环已完成') >= 0,
    row2 ? row2.text.slice(0, 90) : '(无行)');

  /* 收尾：**重置全部**而不是只还原刚操作的那一条。
     只还原一条的话，上一次异常中断留下的脏数据会一直躺在库里，
     把下一次的断言带偏（实测踩过一次）。演示基线就是"全部待用印、无动作记录"。 */
  sql("DELETE FROM seal_record");
  sql("UPDATE seal_apply SET return_status=0, seal_time=NULL, return_at=NULL");
  check('收尾：台账全部还原为「待用印」、seal_record 清空（E2E 不留残渣）',
    sql('SELECT COUNT(*) FROM seal_record') === '0'
    && sql('SELECT COUNT(*) FROM seal_apply WHERE return_status<>0') === '0',
    'record=' + sql('SELECT COUNT(*) FROM seal_record')
    + ' 非待用印=' + sql('SELECT COUNT(*) FROM seal_apply WHERE return_status<>0'));

  console.log('\n=== 7. 委托 / 批量审批 / 超时升级 的可视化操作 ===');
  await page.reload({ waitUntil: 'networkidle2' });
  await sleep(2600);

  /* ---- 我的委托：走一遍**真实闭环**（新建 → 生效中 → 撤销 → 清理）。
     这些接口此前只有后端没有界面；这里用界面真操作一遍，并在收尾**只删自己创建的那条**。 ---- */
  await clickMenu(page, '我的委托');
  await sleep(1800);
  const delegTitle = await page.evaluate(() => {
    const h = document.querySelector('.el-main h2');
    return h ? h.textContent.trim() : '';
  });
  check('「我的委托」页可进入', delegTitle === '我的委托', delegTitle);

  await clickButtonByText(page, '新建委托', '.el-main');
  await sleep(1200);
  const openedDlg = await page.evaluate(() => {
    const d = [...document.querySelectorAll('.el-dialog')].filter(x => x.getClientRects().length).pop();
    if (!d) return null;
    return {
      title: (d.querySelector('.el-dialog__title') || {}).textContent || '',
      labels: [...d.querySelectorAll('.el-form-item__label')].map(x => x.textContent.trim())
    };
  });
  check('「新建委托」弹窗字段齐全（受托人/起止时间/范围/说明）',
    !!openedDlg && openedDlg.title.indexOf('委托') >= 0
    && openedDlg.labels.some(l => l.indexOf('受托人') >= 0)
    && openedDlg.labels.some(l => l.indexOf('生效开始') >= 0)
    && openedDlg.labels.some(l => l.indexOf('说明') >= 0),
    openedDlg ? openedDlg.labels.join('/') : '(未打开)');

  const pickedDelegate = await pickSelect(page, '受托人（代办人）', '林经理');
  check('能选中受托人', !!pickedDelegate, pickedDelegate || '(没选上)');

  await clickButtonByText(page, '保存', '.el-dialog');
  await sleep(2000);
  const newDelegId = sql("SELECT id FROM flow_delegation WHERE deleted=0 ORDER BY id DESC LIMIT 1");
  check('★ 通过界面真的建出了委托（已落库）', Number(newDelegId || 0) > 0, 'id=' + newDelegId);

  const mineRows = await page.$$eval('.el-main .el-table__body tbody tr', trs => trs.map(t => t.textContent));
  check('★ 委托出现在「我设置的」列表里且状态为「生效中」',
    mineRows.some(t => t.indexOf('生效中') >= 0), '行数=' + mineRows.length);

  await clickButtonByText(page, '撤销', '.el-main');
  await sleep(900);
  await page.evaluate(() => {
    const box = document.querySelector('.el-message-box');
    if (!box) return;
    const b = [...box.querySelectorAll('.el-button')].find(x => /确定|确认/.test(x.textContent));
    if (b) b.click();
  });
  await sleep(1800);
  check('★ 撤销后后端状态变为「已撤销」',
    sql('SELECT status FROM flow_delegation WHERE id=' + newDelegId) === '0',
    'status=' + sql('SELECT status FROM flow_delegation WHERE id=' + newDelegId));

  // 收尾：**只删本次创建的 id**，不整表删（那会动别人的数据）
  sql('DELETE FROM flow_delegation WHERE id=' + newDelegId);
  check('收尾：本次创建的委托已物理删除',
    sql('SELECT COUNT(*) FROM flow_delegation WHERE deleted=0') === '0',
    '剩余=' + sql('SELECT COUNT(*) FROM flow_delegation WHERE deleted=0'));

  /* ---- 待我审批：批量通过的入口控件（真正批掉的闭环由接口级用例覆盖） ---- */
  await clickMenu(page, '待我审批');
  await sleep(1800);
  const approveCtl = await page.evaluate(() => ({
    selectionCol: !!document.querySelector('.el-table__header .el-checkbox'),
    headBtns: [...document.querySelectorAll('.page-head button')].map(b => b.textContent.trim())
  }));
  check('待我审批页有「多选」列', approveCtl.selectionCol, '表头按钮=' + approveCtl.headBtns.join('/'));
  check('待我审批页有「批量通过」按钮',
    approveCtl.headBtns.some(t => t.indexOf('批量通过') >= 0), approveCtl.headBtns.join('/'));

  /* ---- 流程管理：超时升级入口（**不点**，它会全公司扫描） ---- */
  await clickMenu(page, '流程管理');
  await sleep(1800);
  const flowBtns = await page.$$eval('.page-head button', bs => bs.map(b => b.textContent.trim()));
  check('流程管理页有「执行超时升级盘点」按钮',
    flowBtns.some(t => t.indexOf('超时升级') >= 0), flowBtns.join('/'));

  console.log('\n=== 7. 主数据 / 表单模板 / 审计日志（三块补齐的可视化界面） ===');
  await page.reload({ waitUntil: 'networkidle2' });
  await sleep(2600);

  /* ---- 修改密码入口（只开/关验证渲染；改密写路由由本地 API 回路测试覆盖，
          这里绝不能真改 —— 改了演示库基线的 123456，全量 E2E 都会崩） ---- */
  const pwdBtn = await page.evaluate(() =>
    [...document.querySelectorAll('.top-actions button')].some(b => (b.textContent || '').includes('钥')));
  check('顶栏有「修改密码」入口（钥 按钮）', pwdBtn, '');
  if (pwdBtn) {
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('.top-actions button')].find(x => (x.textContent || '').includes('钥'));
      if (b) b.click();
    });
    await sleep(900);
    const pwdDlg = await page.evaluate(() => {
      const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.textContent.includes('修改密码') && d.offsetParent !== null);
      return dlg ? { open: true, inputs: dlg.querySelectorAll('input[type=password]').length,
        cancellable: [...dlg.querySelectorAll('button')].some(b => b.textContent.trim() === '取消') } : { open: false };
    });
    check('「修改密码」对话框打开（含 当前/新/确认 三个密码框）',
      pwdDlg.open && pwdDlg.inputs === 3, '打开=' + pwdDlg.open + ' 密码框=' + pwdDlg.inputs);
    check('普通入口可取消（非强制模式）', pwdDlg.cancellable, '');
    await page.evaluate(() => {
      const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.textContent.includes('修改密码') && d.offsetParent !== null);
      const c = dlg && [...dlg.querySelectorAll('button')].find(b => b.textContent.trim() === '取消');
      if (c) c.click();
    });
    await sleep(500);
  }

  /* ---- 主数据：四个 tab 都要有真实数据。**全程只读** —— 部门/岗位/字典/单据类型是
     演示库基线的一部分（post=8 等），写操作的闭环由 verify_master_data_api / verify_doc_type_admin 覆盖。 ---- */
  await clickMenu(page, '主数据');
  await sleep(2000);
  const mdHead = await page.evaluate(() => ({
    h2: (document.querySelector('.el-main h2') || {}).textContent || '',
    tabs: [...document.querySelectorAll('.el-tabs__item')].map(t => t.textContent.trim()),
    treeNodes: [...document.querySelectorAll('.el-tree .el-tree-node__content')].length
  }));
  check('「主数据」页可进入（含 部门/岗位/数据字典/单据类型 四个 tab）',
    mdHead.h2 === '主数据' && mdHead.tabs.length === 4 && mdHead.tabs.includes('单据类型'), 'tabs=' + mdHead.tabs.join('/'));
  check('★ 部门树渲染出真实节点', mdHead.treeNodes > 0, '节点数=' + mdHead.treeNodes);
  check('主数据页有「新增一级部门」入口（admin 有 system:dept）',
    await page.evaluate(() => [...document.querySelectorAll('.el-main button')].some(b => b.textContent.includes('新增一级部门'))), '');

  await page.evaluate(() => {
    const t = [...document.querySelectorAll('.el-tabs__item')].find(x => x.textContent.includes('岗位'));
    if (t) t.click();
  });
  await sleep(1000);
  const postRows = await page.$$eval('.el-main .el-table__body tbody tr', trs => trs.length);
  check('★ 岗位 tab 有数据行', postRows > 0, '行数=' + postRows);

  await page.evaluate(() => {
    const t = [...document.querySelectorAll('.el-tabs__item')].find(x => x.textContent.includes('字典'));
    if (t) t.click();
  });
  await sleep(1000);
  const dictRows = await page.$$eval('.el-main .el-table__body tbody tr', trs => trs.length);
  check('★ 数据字典 tab 有数据行', dictRows > 0, '行数=' + dictRows);

  /* ---- 表单模板：版本列表（只看，不启用/不删除 —— 状态变更由接口用例覆盖） ---- */
  await clickMenu(page, '表单模板');
  await sleep(2000);
  const tplHead = await page.evaluate(() => (document.querySelector('.el-main h2') || {}).textContent || '');
  check('「表单模板」页可进入且自动选中第一个单据类型', tplHead.trim() === '表单模板', tplHead);
  await sleep(600);
  const tplInfo = await page.evaluate(() => ({
    rows: [...document.querySelectorAll('.el-main .el-table__body tbody tr')].length,
    hasActive: [...document.querySelectorAll('.el-main .el-table .el-tag')].some(t => t.textContent.trim() === '生效'),
    hasView: [...document.querySelectorAll('.el-main .el-table button')].some(b => b.textContent.includes('查看'))
  }));
  check('★ 表单模板版本列表出现「生效」版本', tplInfo.rows > 0 && tplInfo.hasActive,
    '行数=' + tplInfo.rows);
  check('表单模板行有「查看」入口（草稿才显示 编辑/启用/删除）', tplInfo.hasView, '');

  /* ---- 字段权限编辑界面（只打开验证渲染，不保存 —— 写回路由 verify_form_template_api 覆盖） ---- */
  const fpEntry = await page.evaluate(() =>
    [...document.querySelectorAll('.el-main .el-table button')].some(b => b.textContent.includes('字段权限')));
  check('表单模板行有「字段权限」入口（全状态可用，后端不限制）', fpEntry, '');
  if (fpEntry) {
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('.el-main .el-table button')]
        .find(x => x.textContent.includes('字段权限'));
      if (b) b.click();
    });
    await sleep(1600);
    const fpDlg = await page.evaluate(() => {
      const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.textContent.includes('字段权限 · ') && d.offsetParent !== null);
      if (!dlg) return { open: false };
      return {
        open: true,
        fieldRows: dlg.querySelectorAll('.el-table__body tbody tr').length,
        switches: dlg.querySelectorAll('.el-table .el-switch').length,
        hasN1: [...dlg.querySelectorAll('.el-select')].length > 0,
        hasAlert: !!dlg.querySelector('.el-alert')
      };
    });
    check('★ 点击「字段权限」弹出编辑矩阵（按 schema 字段逐行渲染）',
      fpDlg.open && fpDlg.fieldRows > 0 && fpDlg.switches >= fpDlg.fieldRows,
      '打开=' + fpDlg.open + ' 字段行=' + fpDlg.fieldRows + ' 开关=' + fpDlg.switches);
    check('对话框有节点选择器与缺省口径说明（* 优先级 / 未配置缺省）',
      fpDlg.hasN1 && fpDlg.hasAlert, '');
    await page.evaluate(() => {
      const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.textContent.includes('字段权限 · ') && d.offsetParent !== null);
      const cancel = dlg && [...dlg.querySelectorAll('button')].find(b => b.textContent.trim() === '取消');
      if (cancel) cancel.click();
    });
    await sleep(600);
  }

  /* ---- 审计日志：真实数据 + 模块筛选（只读查询，随便查） ---- */
  await clickMenu(page, '审计日志');
  await sleep(2400);
  const audit1 = await page.evaluate(() => ({
    h2: (document.querySelector('.el-main h2') || {}).textContent || '',
    rows: [...document.querySelectorAll('.el-main .el-table__body tbody tr')].length,
    total: parseInt(((document.querySelector('.el-pagination__total') || {}).textContent || '共 0 条').replace(/\D/g, ''), 10)
  }));
  check('「审计日志」页可进入', audit1.h2 === '审计日志', audit1.h2);
  check('★ 审计日志渲染出真实记录（后端强制分页）', audit1.rows > 0 && audit1.total > 0,
    '本页=' + audit1.rows + ' 总数=' + audit1.total);

  // 选模块 auth → 查询 → 本页所有行的模块列都应为 auth（验证服务端筛选真的下推了）
  await page.evaluate(() => {
    const sel = [...document.querySelectorAll('.el-main .filters .el-select')][0];
    const w = sel && (sel.querySelector('.el-select__wrapper') || sel);
    if (w) w.click();
  });
  await sleep(800);
  await page.evaluate(() => {
    const opt = [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter(x => x.offsetParent !== null)
      .find(x => x.textContent.trim() === 'auth');
    if (opt) opt.click();
  });
  await sleep(500);
  await clickButtonByText(page, '查询', '.el-main');
  await sleep(1600);
  const audit2 = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.el-main .el-table__body tbody tr')];
    const mods = rows.map(r => {
      const tag = r.querySelector('.el-tag');
      return tag ? tag.textContent.trim() : '';
    });
    return { rows: rows.length, allAuth: mods.length > 0 && mods.every(m => m === 'auth'), sample: mods.slice(0, 3) };
  });
  check('★ 审计模块筛选下推到服务端（选 auth 后本页行全部为 auth）',
    audit2.rows > 0 && audit2.allAuth, '样例=' + audit2.sample.join('/'));

  console.log('\n=== 7. 控制台 ===');
  const realErrs = errs.filter(e => !/favicon/.test(e));
  check('无控制台错误 / 无失败请求', realErrs.length === 0, realErrs.slice(0, 3).join(' | ') || '(干净)');

  await browser.close();

  const pass = results.filter(r => r.ok).length;
  const total = results.length;
  /* 自证闸门：条数必须与预期一致。少了就说明有断言被删除、被注释，或上游抛错后
     其后断言被静默跳过 —— 这三种情况都不该以"通过"收场。 */
  const countOk = total === EXPECTED_TOTAL;
  console.log('\n============================================');
  console.log('  管理端 UI 验证：' + pass + '/' + total + ' 通过（预期 ' + EXPECTED_TOTAL + ' 条）');
  if (pass !== total) {
    console.log('  失败项：');
    results.filter(r => !r.ok).forEach(r => console.log('    - ' + r.name));
  }
  if (!countOk) {
    console.log('  ❌ 断言条数异常：实际 ' + total + ' 条，预期 ' + EXPECTED_TOTAL + ' 条');
    console.log('     条数变少通常意味着上游抛错后，其后断言被静默跳过（假绿灯）。');
  }
  console.log('============================================');
  process.exit((pass === total && countOk) ? 0 : 1);
})().catch(e => { console.error('ERR [断言未跑完，本次结果无效]', e.message); process.exit(1); });

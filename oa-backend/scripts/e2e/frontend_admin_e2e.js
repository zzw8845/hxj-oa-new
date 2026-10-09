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
/* 截图目录必须先建：此前这里直接写死 /tmp/proto/shots2/xxx.png，而 /tmp 会被系统清理 ——
   目录一旦不在，套件就在"断言跑到一半"时抛 ENOENT 中断，末行报的是
   "[断言未跑完，本次结果无效]"。这个报错跟被测功能毫无关系，却会让人误以为后端坏了。
   截图是套件自己的产物，目录就该由套件自己保证存在。 */
const fs = require('fs');
const SHOTS = '/tmp/proto/shots2';
fs.mkdirSync(SHOTS, { recursive: true });
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
const EXPECTED_TOTAL = 108;

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

/* 用登录表单直接换账号（登录芯片 + 顶栏切换下拉均已从页面删除）。
   为什么必须真登录一次而不是只改 localStorage 里的 token：登录时后端把
   角色权限快照进 JWT（硬约束 2），「临时授权 → 切 linjl」这条链必须拿到
   **新签发的 JWT**，快照里才有新权限。所以清 token → 刷新回登录页 →
   填表 → 点登录，完整走一遍 doLogin。 */
async function loginViaForm(page, account, password) {
  await page.evaluate(() => localStorage.removeItem('hxj_oa_token'));   // 与页面 TOKEN_KEY 一致
  await page.reload({ waitUntil: 'networkidle2' });
  await sleep(2600);
  const ok = await page.evaluate((acc, pwd) => {
    const set = (ph, v) => {
      const el = [...document.querySelectorAll('input')].find(i => i.placeholder === ph);
      if (!el) return false;
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    };
    if (!set('请输入登录账号', acc) || !set('请输入登录密码', pwd)) return false;
    const b = [...document.querySelectorAll('.el-button')].find(x => x.textContent.replace(/\s+/g, '') === '登录');
    if (!b) return false;
    b.click();
    return true;
  }, account, password);
  await sleep(4200);   // 登录 + afterLogin 全量拉数据
  return ok;
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

  const loggedIn = await loginViaForm(page, 'admin', '123456');
  check('登录表单填 admin/123456 登录', loggedIn);
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
    /* 关键字不能写死（黄小明在重建后的 R01 库里根本不存在，断言全靠运气）：
       动态取第一行的姓名来搜 —— 过滤后首行仍含该关键字即证明搜索真的过滤了。 */
    const firstName = await page.$$eval('.el-table__body tbody tr', trs =>
      trs.length ? ((trs[0].querySelectorAll('td')[0] || {}).textContent || '').trim() : ''
    ).catch(() => '');
    check('  取到首行姓名作为搜索关键字', firstName.length > 0, 'keyword=' + firstName);
    const before = apiCalls.filter(u => u.indexOf('/api/users/page') >= 0 && u.indexOf('keyword=') >= 0).length;
    await page.click(kwBox, { clickCount: 3 }).catch(function(){});
    await page.type(kwBox, firstName, { delay: 60 }).catch(function(){});
    await sleep(1400);
    const after = apiCalls.filter(u => u.indexOf('/api/users/page') >= 0 && u.indexOf('keyword=') >= 0);
    check('★ 搜索走后端：输入关键字后确实请求了带 keyword 的分页接口', after.length > before,
      '带 keyword 的请求数 ' + before + ' → ' + after.length + (after.length ? '，末条=' + after[after.length-1].slice(-90) : ''));

    const filtered = await page.$$eval('.el-table__body tbody tr', trs =>
      trs.map(tr => ((tr.querySelectorAll('td')[0] || {}).textContent || '').trim())
    ).catch(() => []);
    check('★ 搜索后表格收敛到过滤结果（每行都命中关键字）',
      filtered.length >= 1 && filtered.every(t => t.indexOf(firstName) >= 0),
      '行数=' + filtered.length + '，首行=' + (filtered[0] || '(空)'));

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

  await page.screenshot({ path: SHOTS + '/admin-person.png' });

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
  /* 「管理范围」是 C3 引入的**第二个正交维度**（能管哪些人的账号），与数据范围（能看多少单据）
     不是一回事，后端也刻意做成两个独立子资源。这里断言它真的渲染出来了 ——
     只做后端不做界面的话，客户在界面上根本看不到、配不了，等于没交付。
     ⚠ 断言打在 role-tree 的节点上：角色面板在运行时被注入成 role-tree-card，
     模板里的 .role-grid 卡片是死代码（改了不会生效）。 */
  const withAdminScope = roleNodes.filter(t => /管理范围：/.test(t));
  check('角色节点显示「管理范围」（与数据范围并列的第二维度）', withAdminScope.length >= 8,
    '含该字段=' + withAdminScope.length + '/' + roleNodes.length);
  check('管理范围显示的是后端中文标签（不是 none/all 这类编码）',
    withAdminScope.some(t => /管理范围：(不管人|本部门及下级|全公司)/.test(t)),
    (withAdminScope.find(t => /管理范围：(不管人|本部门及下级|全公司)/.test(t)) || '(无)').slice(0, 120));
  await page.screenshot({ path: SHOTS + '/admin-role.png' });

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
  /* 新建态**故意**不给「管理范围」：它的缺省语义是"不管人"（最窄），不像数据范围那样
     缺省会静默降级成"仅本人"（对管理员是危险的收权）、必须一起给。
     少一个能在新建时被顺手勾上的授权项，比"两个字段看着整齐"更重要 —— 这条断言就是把这个
     决定固化下来，谁把控件挪进新建弹窗就会红。 */
  check('新建态不出现「管理范围」（后端设计：新建不给授权项）',
    !roleDlg.labels.some(l => l.includes('管理范围')), roleDlg.labels.join(' | '));
  await closeDialogs(page);

  // 编辑态才有「管理范围」。这里**只读**：打开角色的「配置权限」核对回显值，
  // 不做任何写入 —— 写路径的完整闭环（改→保存→落库→改回）由
  // scripts/verify_admin_scope.py 在接口层覆盖，UI 层只保证"入口在、值对、说明在"。
  //
  // ⚠ 必须**点名**打开哪个角色，不能"取第一个带按钮的节点"：
  // 第一个节点是超级管理员，它的说明文案是"不允许收窄"那一句，
  // 用 /账号/ 之类的宽松正则去匹配会把这条断言变成永远为真的假绿
  // （本项目栽过同类问题：断言比它声称的要宽，等于没断言）。
  const openRoleConfigAndReadScope = async function (roleName) {
    await page.evaluate((name) => {
      const nodes = [...document.querySelectorAll('.role-tree-node.role')];
      const node = nodes.find(n => n.textContent.includes(name))
                || nodes.find(n => [...n.querySelectorAll('.el-button')].some(b => b.textContent.includes('配置权限')));
      if (!node) return;
      const b = [...node.querySelectorAll('.el-button')].find(x => x.textContent.includes('配置权限'));
      if (b) b.click();
    }, roleName);
    await sleep(1200);
    const r = await page.evaluate(() => {
      const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null);
      const dlg = dlgs.find(d => d.textContent.includes('角色名称'));
      if (!dlg) return { item: false, value: '', hint: '', disabled: false };
      const it = [...dlg.querySelectorAll('.el-form-item')].find(i => {
        const l = i.querySelector('.el-form-item__label');
        return l && l.textContent.trim().replace(/[:：]/g, '').startsWith('管理范围');
      });
      if (!it) return { item: false, value: '', hint: '', disabled: false };
      const w = it.querySelector('.el-select__wrapper');
      return { item: true, value: w ? w.textContent.trim() : '',
               disabled: !!(w && w.classList.contains('is-disabled')),
               hint: (it.querySelector('small') || {}).textContent || '' };
    });
    await closeDialogs(page);
    return r;
  };

  const auditScope = await openRoleConfigAndReadScope('审计管理员');
  check('编辑角色弹窗含「管理范围」且回显后端值',
    !!auditScope.item && /不管人|本部门及下级|全公司/.test(auditScope.value || ''),
    JSON.stringify(auditScope).slice(0, 150));
  // 两个维度最容易混，普通说明里必须点出"和上面的数据范围不是一回事"，
  // 否则管理员会以为改了数据范围就等于改了管理范围。
  check('普通角色的说明写明了与「数据范围」的区别（非技术人员能看懂）',
    /数据范围/.test(auditScope.hint || ''), (auditScope.hint || '(无)').slice(0, 130));

  const adminScope = await openRoleConfigAndReadScope('超级管理员');
  check('内置 ADMIN 回显「全公司」', adminScope.value === '全公司', '界面="' + adminScope.value + '"');
  // 「点了没反应」是最差的一种交互：这里必须既禁用、又写清为什么。
  check('内置 ADMIN 的管理范围被禁用，且写明原因',
    adminScope.disabled === true && /收窄/.test(adminScope.hint || ''),
    'disabled=' + adminScope.disabled + '，说明=' + (adminScope.hint || '(无)').slice(0, 110));

  console.log('\n=== 5. 审批配置（2026-10-09 方案A：流程管理+表单模板+节点指派 三合一） ===');
  await closeDialogs(page);
  await clickMenu(page, '审批配置');
  await sleep(1500);
  // 默认页签是「表单模板」，流程断言前先切到「审批流程」页签
  await page.evaluate(() => {
    const t = [...document.querySelectorAll('.el-tabs__item')].find(x => x.textContent.trim() === '审批流程');
    if (t) t.click();
  });
  await sleep(1200);
  const flowCards = await page.evaluate(() =>
    [...document.querySelectorAll('.bizcfg-flow')].map(c => c.textContent.replace(/\s+/g, ' '))
  );
  check('流程卡片渲染（按单据类型过滤）', flowCards.length >= 1, '卡片数=' + flowCards.length);
  const chainNodes = await page.evaluate(() =>
    [...document.querySelectorAll('.bizcfg-chain-node')].map(c => c.textContent.replace(/\s+/g, ' ').trim())
  );
  check('流程卡片显示审批链路（节点 + 每节点审批人摘要）', chainNodes.length >= 3,
    (chainNodes.slice(0, 3).join(' | ')).slice(0, 150));

  // 菜单文案必须和页面标题一致，否则「要找审批配置」的人会找不到入口
  const flowTitle = await page.$eval('.topbar h1', e => e.textContent.trim()).catch(() => '');
  check('审批配置页标题与菜单文案一致', flowTitle === '审批配置', 'topbar h1=' + flowTitle);

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
  check('流程弹窗含「关联单据类型」', flowDlg.labels.some(l => l.includes('关联单据类型')), flowDlg.labels.join(' | '));
  /* 2026-10-08 编辑器画布化（对标钉钉）：旧的「审批节点」多选下拉没了，
     改成竖排节点卡画布，表单 label 也换成「审批流程图」。 */
  check('流程弹窗含「审批流程图」（画布式编辑器）', flowDlg.labels.some(l => l.includes('审批流程图')), flowDlg.labels.join(' | '));
  check('弹窗提示了版本影响', /新版本|部署/.test(flowDlg.full || ''), (flowDlg.text || '').slice(0, 120));

  // 节点下拉必须来自后端模板库（说明文案里带"取发起人所在部门"这类规则描述）。
  // 画布化后这个下拉长在**每个节点卡内部**：点第一张普通节点卡的 select。
  // 排除 .dt-startcard（发起卡）和 .dt-endcard（结束卡）——它们没有节点下拉。
  // 泳道里的目标卡不在 .dt-node 下，不会被误点。
  await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    const card = dlg && dlg.querySelector('.dt-node .dt-card:not(.dt-startcard):not(.dt-endcard)');
    const w = card && card.querySelector('.el-select__wrapper');
    if (w) w.click();
  });
  await sleep(900);
  const nodeOpts = await page.evaluate(() =>
    [...document.querySelectorAll('.el-select-dropdown__item')].filter(o => o.offsetParent !== null)
      .map(o => o.textContent.replace(/\s+/g, ' ').trim())
  );
  check('节点下拉来自后端模板库', nodeOpts.length >= 2, '选项数=' + nodeOpts.length);
  check('节点选项带审批人规则说明',
    nodeOpts.some(o => /取发起人所在部门|按发起人部门|沿用已有/.test(o)),
    nodeOpts.slice(0, 3).join(' || ').slice(0, 150));

  /* ---- 条件分支编辑器（2026-10-08 画布化后按 .dt-* 断言） ----
     只打开看渲染与交互，**不保存** —— 保存会生成新版本并污染演示流程，
     写路径与清理由 verify_flow_branch_admin 覆盖。
     画布 DOM：.dt-node（主线节点卡）· .dt-branch（分支泳道组）·
     .dt-lane（单条泳道，排除 .dt-lane-add 那张「＋添加分支」卡）。 ---- */
  const canvasUi = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    if (!dlg) return { nodeCards: 0, branches: 0, lanes: 0, laneHeads: [] };
    return {
      nodeCards: dlg.querySelectorAll('.dt-node .dt-card:not(.dt-startcard):not(.dt-endcard)').length,
      branches: dlg.querySelectorAll('.dt-branch').length,
      lanes: dlg.querySelectorAll('.dt-lane:not(.dt-lane-add)').length,
      laneHeads: [...dlg.querySelectorAll('.dt-lane-head')].map(e => e.textContent.trim().slice(0, 6))
    };
  });
  check('流程弹窗按「画布节点卡」渲染（竖排卡片，不再是多选下拉）', canvasUi.nodeCards >= 1,
    JSON.stringify(canvasUi));
  check('演示流程的条件分支渲染为泳道（条件 + 否则）',
    canvasUi.branches >= 1 && canvasUi.lanes >= 2, JSON.stringify(canvasUi));
  check('泳道头标出条件序号与「否则」',
    canvasUi.laneHeads.some(h => h.indexOf('条件') === 0) && canvasUi.laneHeads.some(h => h.indexOf('否则') === 0),
    canvasUi.laneHeads.join(' | '));

  /* 分支「流向节点」下拉必须只列「本条件分支之后」的节点 —— 这是禁止回跳、
     从结构上排除死循环的界面体现。选项 label 是 "4. 节点名" 这种编号格式
     （branchTargets 生成），只认编号项，以免和节点名下拉串味。
     反回跳的界面下界：分支最早也只能出现在第 2 行（第 1 行是发起节点），
     所以任何可选目标的编号必须 ≥ 3 —— 出现 1. 或 2. 就是能回跳，直接红。 */
  await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    const lanes = dlg ? [...dlg.querySelectorAll('.dt-lane:not(.dt-lane-add)')] : [];
    /* 泳道里可能有多个 select：目标卡里的「节点名」下拉 + 泳道自身的「流向节点」下拉。
       要点**最后一个**（流向），点错就会拿到不编号的节点名选项，断言全盘皆输。 */
    const lane = lanes[lanes.length - 1];
    const ws = lane ? lane.querySelectorAll('.el-select__wrapper') : [];
    if (ws.length) ws[ws.length - 1].click();
  });
  await sleep(900);
  const numberedTargets = await page.evaluate(() =>
    [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter(o => o.offsetParent !== null)
      .map(o => o.textContent.replace(/\s+/g, ' ').trim())
      .filter(t => /^\d+\./.test(t))
  );
  check('分支目标只列条件分支之后的节点（禁止回跳 → 结构上排除死循环）',
    numberedTargets.length >= 1 && numberedTargets.every(t => parseInt(t, 10) >= 3),
    '可选目标=' + numberedTargets.join(' || ').slice(0, 150));

  /* 「＋ 添加条件分支」的一次点击：画布上应出现新的泳道组，两条泳道
     （条件 + 否则）共同指向自动补上的承接节点。 */
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
    const branches = dlg ? [...dlg.querySelectorAll('.dt-branch')] : [];
    const last = branches[branches.length - 1];
    const msg = [...document.querySelectorAll('.el-message')].map(e => e.textContent).join(' ');
    return {
      branches: branches.length,
      lanes: last ? last.querySelectorAll('.dt-lane:not(.dt-lane-add)').length : 0,
      laneCards: last ? last.querySelectorAll('.dt-lane .dt-card').length : 0,
      flowTargets: last ? last.querySelectorAll('.dt-lane > .el-select').length : 0,
      condPickers: last ? last.querySelectorAll('.dt-lane .dt-cond-row .el-select__wrapper').length : 0,
      msg: msg
    };
  });
  check('「＋ 添加条件分支」按钮存在且可点', addClicked, '');
  check('新增条件分支：画布出现一个新的泳道组',
    afterAdd.branches === canvasUi.branches + 1, '由 ' + canvasUi.branches + ' → ' + afterAdd.branches);
  /* dtInsertAfter('gw') 的行为：两条泳道（条件 + 否则）+ 自动补一个承接节点作为
     共同去处 ⇒ 每条泳道里各有 1 张目标卡 + 1 个「流向节点」下拉。
     条件泳道还带「字段/怎么比」两个结构化下拉 ⇒ condPickers ≥ 2。
     若再退回"手写表达式"，这里会归零 —— 变红就是在提醒别回退。 */
  check('新分支自带「条件 + 否则」两条泳道，且承接节点已就位（目标卡 + 流向下拉各 2）',
    afterAdd.lanes === 2 && afterAdd.laneCards >= 2 && afterAdd.flowTargets === 2, JSON.stringify(afterAdd));
  check('条件泳道自带「字段/怎么比」结构化下拉（不再要求手写表达式）',
    afterAdd.condPickers >= 2, '条件下拉数=' + afterAdd.condPickers);
  check('新增时说明了为什么多出一个承接节点',
    /承接节点/.test(afterAdd.msg || ''), (afterAdd.msg || '').slice(0, 90));

  await page.screenshot({ path: SHOTS + '/admin-flow.png' });

  /* ---- 审批人内嵌编辑器（2026-10-09 二次收敛：对齐钉钉，点流程节点卡直接展开，无独立页签） ----
     只打开看渲染，**不保存** —— 改审批人的写路径由 verify_flow_assignee_admin 覆盖
     （那条脚本自己会还原演示库规则，UI 这里点保存会污染演示数据）。 ---- */
  await closeDialogs(page);
  const asgEntry = await page.evaluate(() => {
    const node = [...document.querySelectorAll('.bizcfg-chain-node')]
      .filter(e => e.offsetParent !== null)
      .find(e => !e.className.includes('is-static'));
    if (node) { node.click(); return true; }
    return false;
  });
  await sleep(2500);
  check('点流程节点卡可展开审批人编辑器', asgEntry, '');
  const asgPanel = await page.evaluate(() => {
    const editor = [...document.querySelectorAll('.bizcfg-node-editor')].find(e => e.offsetParent !== null);
    if (!editor) return { open: false, blocks: 0, types: 0 };
    return {
      open: true,
      blocks: [...editor.querySelectorAll('.el-table')].length,
      types: [...editor.querySelectorAll('.el-select')].length
    };
  });
  check('审批人编辑器内嵌在流程块里且按节点列出规则（不再弹窗/页签）', asgPanel.open && asgPanel.blocks >= 3, JSON.stringify(asgPanel));
  const noAsgTab = await page.evaluate(() =>
    ![...document.querySelectorAll('.el-tabs__item')].some(x => x.textContent.trim() === '审批人'));
  check('独立「审批人」页签已移除（钉钉式节点即配置）', noAsgTab, '');
  await closeDialogs(page);

  console.log('\n=== 6. 用印台账与归还闭环（C4/D8 后：admin 无权 → 临时授权 DEPT_HEAD → linjl 跑闭环） ===');
  /* C4/D8 出口 B（2026-09-28）把 document:approve:seal 从 ADMIN 摘除后，本段验证两件事：
     ① **权限守卫**：admin 打开台账页 → 前端根本不发 /api/seals（守卫在 refreshSeals 里，
        无权不白打必然 403 的请求）—— 这本身就是"ADMIN 转纯管理账号"的 UI 证据；
     ② **闭环功能**：用印的登记/归还闭环不能因为没有持权账号就失去 UI 覆盖 ——
        按 verify_seal_api.py 的夹具模式临时授权 DEPT_HEAD，切 linjl（登录时权限快照生效）跑闭环，收尾撤销。 */
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

  const adminSealCalls = apiCalls.filter(u => u.indexOf('/api/seals') >= 0);
  check('★ admin 已无用印权：台账接口不被请求（前端守卫生效，不白打 403）',
    adminSealCalls.length === 0, '命中 ' + adminSealCalls.length + ' 次');
  const adminSealRows = await page.$$eval('.el-main .el-table__body tbody tr', els => els.length).catch(() => -1);
  check('★ admin 无权时台账为空（守卫置空，不是造的假数据）', adminSealRows === 0, '行数=' + adminSealRows);

  /* ---- 临时授权夹具（与 verify_seal_api.py 同一模式）：<库内第一个部门主管角色> ← document:approve:seal ----
     角色/账号**动态从库里取**，不写死 linjl/DEPT_HEAD —— 库是谁由初始化脚本决定
     （R01 多轮测试库、交付空库、旧演示库各不相同），写死的人名在重建后的库里必然失效。
     先预清理（防上次异常中断遗留），再注入；登录时后端把权限快照进 JWT ——
     所以授权必须发生在该账号登录**之前**。 */
  const SEAL_PERM = 'document:approve:seal';
  const sealU = sql("SELECT u.account, r.code FROM sys_user u JOIN user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.deleted=0 AND u.account<>'admin' AND r.deleted=0 AND r.code LIKE '%\\_HEAD' ORDER BY u.id LIMIT 1");
  const sealParts = sealU.split('\t');
  const sealAccount = sealParts[0] || '';
  const sealRoleCode = sealParts[1] || '';
  check('  从库里取到临时授权对象（非 admin 的部门主管）', !!sealAccount && !!sealRoleCode,
    'account=' + sealAccount + ' role=' + sealRoleCode);
  sql("DELETE rp FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.code='" + sealRoleCode + "' AND rp.perm_code='" + SEAL_PERM + "'");
  sql("INSERT INTO role_permission (role_id, perm_code) SELECT id, '" + SEAL_PERM + "' FROM sys_role WHERE code='" + sealRoleCode + "' AND deleted=0");
  check('临时授权已注入（' + sealRoleCode + ' ← document:approve:seal）',
    sql("SELECT COUNT(*) FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.code='" + sealRoleCode + "' AND rp.perm_code='" + SEAL_PERM + "' AND rp.deleted=0") === '1');

  /* 台账必须有「待用印」行才能走登记/归还闭环。库里一行都没有时（R01 重建库
     只有 DAILY 类型、根本提交不出用印单），用 SQL 夹具补一条绑到 sealRoleCode
     同部门单据上的待用印 —— 登记与归还**动作**仍走真实 UI + /api/seals，
     夹具只负责"台账里有一行可操作"。行留着不清理：它就是下次运行的基线。 */
  if (Number(sql("SELECT COUNT(*) FROM seal_apply WHERE deleted=0 AND return_status=0")) === 0) {
    /* 台账动作（登记/归还）要求单据是 **SEAL 类**（requireSealDocument 校验），
       R01 重建库只有 DAILY 类型 ⇒ 夹具自建一张 SEAL 类单据（挂在 sealAccount 同部门名下）
       + 对应的待用印行。登记与归还**动作**仍走真实 UI + /api/seals；
       收尾会把这组夹具行删掉，不留残渣。 */
    const fxDocNo = 'E2SEAL' + stamp;
    sql("INSERT INTO document (doc_no, company_id, doc_type_id, business_category, title, applicant_id, applicant_name, dept_id, dept_name, amount, reason, status, current_node_key, current_node_name, created_at, updated_at, created_by, updated_by, deleted) "
      + "SELECT '" + fxDocNo + "', u.company_id, 25, 'SEAL', 'E2E用印闭环夹具', u.id, u.real_name, u.dept_id, d.name, 0, 'E2E 夹具单据（自动清理）', 3, 'end', '办结', NOW(), NOW(), u.id, u.id, 0 "
      + "FROM sys_user u JOIN department d ON d.id=u.dept_id WHERE u.account='" + sealAccount + "' LIMIT 1");
    const fxDoc = sql("SELECT id FROM document WHERE doc_no='" + fxDocNo + "' LIMIT 1");
    check('  夹具：已自建 SEAL 类夹具单据', !!fxDoc, 'document_id=' + (fxDoc || '(创建失败)'));
    if (fxDoc) {
      sql("INSERT INTO seal_apply (document_id, seal_project, seal_dept_id, seal_type, seal_reason, return_status, deleted, created_at, updated_at) "
        + "SELECT " + fxDoc + ", 'E2E用印夹具', u.dept_id, '公章', 'E2E 闭环夹具（可清理）', 0, 0, NOW(), NOW() FROM document d "
        + "JOIN sys_user u ON u.id=d.applicant_id WHERE d.id=" + fxDoc
        + " AND NOT EXISTS (SELECT 1 FROM seal_apply sa WHERE sa.document_id=d.id AND sa.deleted=0)");
    }
  }
  check('  夹具：台账已有一条「待用印」',
    Number(sql("SELECT COUNT(*) FROM seal_apply WHERE deleted=0 AND return_status=0")) >= 1,
    '待用印行数=' + sql("SELECT COUNT(*) FROM seal_apply WHERE deleted=0 AND return_status=0"));

  const pickedLin = await loginViaForm(page, sealAccount, '123456');
  const whoLin = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  check('已切换为 ' + sealAccount + '（重新登录，权限快照生效）',
    !!pickedLin && whoLin.length > 0, pickedLin + ' / ' + whoLin);

  await clickMenu(page, '用印台账');
  await sleep(2200);
  const linSealCalls = apiCalls.filter(u => u.indexOf('/api/seals') >= 0);
  check(sealAccount + ' 台账数据来自服务端接口 /api/seals（不是前端造的假数据）',
    linSealCalls.length >= 1, '命中 ' + linSealCalls.length + ' 次');

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
     把下一次的断言带偏（实测踩过一次）。演示基线就是"全部待用印、无动作记录"。
     同时撤销临时授权（精确到 DEPT_HEAD × seal 权限点这一条，不碰别的配置），
     并**切回 admin** —— 第 7 段的委托/主数据断言是管理端视角。 */
  sql("DELETE FROM seal_record");
  sql("UPDATE seal_apply SET return_status=0, seal_time=NULL, return_at=NULL");
  /* 夹具残渣清理：本套件自建的 SEAL 夹具单据与其台账行（只删自己的，doc_no 前缀隔离） */
  sql("DELETE FROM seal_apply WHERE seal_project='E2E用印夹具'");
  sql("DELETE FROM document WHERE doc_no LIKE 'E2SEAL%'");
  sql("DELETE rp FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.code='" + sealRoleCode + "' AND rp.perm_code='" + SEAL_PERM + "'");
  check('收尾：台账还原 + 夹具已清 + 临时授权已撤销（演示库不留数据/配置残渣）',
    sql('SELECT COUNT(*) FROM seal_record') === '0'
    && sql('SELECT COUNT(*) FROM seal_apply WHERE return_status<>0') === '0'
    && sql("SELECT COUNT(*) FROM document WHERE doc_no LIKE 'E2SEAL%'") === '0'
    && sql("SELECT COUNT(*) FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.code='" + sealRoleCode + "' AND rp.perm_code='" + SEAL_PERM + "' AND rp.deleted=0") === '0',
    'record=' + sql('SELECT COUNT(*) FROM seal_record')
    + ' 非待用印=' + sql('SELECT COUNT(*) FROM seal_apply WHERE return_status<>0')
    + ' 夹具单残留=' + sql("SELECT COUNT(*) FROM document WHERE doc_no LIKE 'E2SEAL%'")
    + ' 授权残留=' + sql("SELECT COUNT(*) FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.code='" + sealRoleCode + "' AND rp.perm_code='" + SEAL_PERM + "' AND rp.deleted=0"));

  const pickedAdmin = await loginViaForm(page, 'admin', '123456');
  check('已切回 admin（第 7 段继续管理端视角）', !!pickedAdmin, pickedAdmin ? '' : '(切换失败)');

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

  /* 受托人**不能选自己**（后端明确拒绝自委托），也不能写死人名（库里是谁由初始化决定）。
     打开下拉后跳过当前登录人（topbar .who 里含其姓名），选第一个别人。 */
  await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(d => d.offsetParent !== null).pop();
    const w = dlg && dlg.querySelector('.el-select__wrapper');
    if (w) w.click();
  });
  await sleep(600);
  const delegateName = await page.evaluate(() => {
    const opts = [...document.querySelectorAll('.el-select-dropdown__item')].filter(o => o.offsetParent !== null);
    const who = document.querySelector('.topbar .who');
    const meText = who ? who.textContent.trim() : '';
    const o = opts.find(x => {
      const n = x.textContent.trim().split('（')[0];
      return n && meText.indexOf(n) < 0;
    });
    if (o) { o.click(); return o.textContent.trim(); }
    return null;
  });
  check('能选中受托人（且不是自己）', !!delegateName, delegateName || '(没选上)');

  const delegBase = Number(sql('SELECT COALESCE(MAX(id),0) FROM flow_delegation'));
  await clickButtonByText(page, '保存', '.el-dialog');
  await sleep(2000);
  /* 必须和保存前的基线比：直接取 MAX(id) 会把历史上残留的委托行误认成本次新建的。 */
  const newDelegId = sql("SELECT id FROM flow_delegation WHERE id>" + delegBase + " AND deleted=0 ORDER BY id DESC LIMIT 1");
  check('★ 通过界面真的建出了委托（已落库）',
    Number(newDelegId || 0) > delegBase, '基线=' + delegBase + ' 新id=' + newDelegId);

  const mineRows = await page.$$eval('.el-main .el-table__body tbody tr', trs => trs.map(t => t.textContent));
  const delegateRealName = (delegateName || '').split('（')[0];
  check('★ 委托出现在「我设置的」列表里且状态为「生效中」',
    mineRows.some(t => t.indexOf('生效中') >= 0 && delegateRealName && t.indexOf(delegateRealName) >= 0),
    '行数=' + mineRows.length + ' 受托人=' + delegateRealName);

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

  // 收尾：**只删本次创建的 id**，不整表删（那会动别人的数据）；其余行是库的既有基线
  sql('DELETE FROM flow_delegation WHERE id=' + newDelegId);
  check('收尾：本次创建的委托已物理删除',
    Number(sql('SELECT COUNT(*) FROM flow_delegation WHERE id=' + newDelegId)) === 0,
    '基线=' + delegBase + ' 现存=' + sql('SELECT COUNT(*) FROM flow_delegation WHERE deleted=0'));

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

  /* ---- 风险预警：超时升级入口（2026-10-09 从流程管理页迁来，**不点**，它会全公司扫描） ---- */
  await clickMenu(page, '风险预警');
  await sleep(1800);
  const riskHeadBtns = await page.$$eval('.page-head button', bs => bs.map(b => b.textContent.trim()));
  check('风险预警页有「立即处理超时单据」按钮',
    riskHeadBtns.some(t => t.indexOf('超时') >= 0), riskHeadBtns.join('/'));

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
  check('「主数据」页可进入（含 部门/岗位/数据字典/公司信息 四个 tab；单据类型已并入审批配置）',
    mdHead.h2 === '主数据' && mdHead.tabs.length === 4 && !mdHead.tabs.includes('单据类型')
      && mdHead.tabs.includes('公司信息'), 'tabs=' + mdHead.tabs.join('/'));
  check('★ 部门树渲染出真实节点', mdHead.treeNodes > 0, '节点数=' + mdHead.treeNodes);
  check('主数据页有「新增一级部门」入口（admin 有 system:dept）',
    await page.evaluate(() => [...document.querySelectorAll('.el-main button')].some(b => b.textContent.includes('新增一级部门'))), '');

  /* ---- 公司信息 tab（2026-09-28 新增）：断言读的是**后端真值**（GET /api/company），
     不是模板占位。只验"tab 在不在"会漏掉"接口没接上、框里是空的"这一类问题。 ---- */
  await page.evaluate(() => {
    const t = [...document.querySelectorAll('.el-tabs__item')].find(x => x.textContent.includes('公司信息'));
    if (t) t.click();
  });
  await sleep(900);
  const companyTab = await page.evaluate(() => ({
    vals: [...document.querySelectorAll('.el-main input')].map(i => i.value).filter(Boolean),
    hasSave: [...document.querySelectorAll('.el-main button')].some(b => b.textContent.trim() === '保存')
  }));
  check('★ 主数据「公司信息」回填的是后端真值（GET /api/company，非硬编码）',
    companyTab.vals.some(v => v.indexOf('海峡金') >= 0), 'vals=' + JSON.stringify(companyTab.vals));
  check('主数据「公司信息」有保存入口（admin 有 system:company）', companyTab.hasSave, '');

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

  /* ---- 审批配置 → 表单模板页签：版本列表（只看，不启用/不删除 —— 状态变更由接口用例覆盖） ---- */
  await clickMenu(page, '审批配置');
  await sleep(2000);
  const tplHead = await page.evaluate(() => (document.querySelector('.el-main h2') || {}).textContent || '');
  check('「审批配置」页可进入且默认页签为表单模板（自动选中第一个单据类型）', tplHead.trim() === '审批配置', tplHead);
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

  // 选模块「认证」(auth) → 查询 → 本页所有行的模块列都应为「认证」
  //（2026-10-09 起模块/动作列与筛选下拉均为中文显示，值仍是英文编码 auth）
  await page.evaluate(() => {
    const sel = [...document.querySelectorAll('.el-main .filters .el-select')][0];
    const w = sel && (sel.querySelector('.el-select__wrapper') || sel);
    if (w) w.click();
  });
  await sleep(800);
  await page.evaluate(() => {
    const opt = [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter(x => x.offsetParent !== null)
      .find(x => x.textContent.trim() === '认证');
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
    return { rows: rows.length, allAuth: mods.length > 0 && mods.every(m => m === '认证'), sample: mods.slice(0, 3) };
  });
  check('★ 审计模块筛选下推到服务端（选「认证」后本页行全部为「认证」）',
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

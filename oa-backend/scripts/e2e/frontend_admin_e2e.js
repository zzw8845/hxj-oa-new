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
const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE = 'http://127.0.0.1:8080/oa.html';
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* 预期断言总数：脚本正常跑完必须**恰好**产出这么多条。
   为什么要把这个数写死在代码里：本项目出过一次「假绿灯」—— 上游某条断言依赖的接口被回退后
   抛错中断，导致其后 16 条断言（含整条审计留痕链路）**从未执行**，而末行照样打印
   "85/85 通过"。有了这个数，任何"少跑了"都会立刻变成红灯，而不是无声无息。 */
const EXPECTED_TOTAL = 38;

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
  await fillInput(page, '岗位', 'UI测试岗');
  await sleep(400);
  await clickButtonByText(page, '保存员工', '.el-dialog');
  await sleep(3200);

  const afterRows = await page.$$eval('.el-table__body tbody tr', trs =>
    trs.map(tr => tr.textContent)
  ).catch(() => []);
  check('新员工出现在列表中', afterRows.some(t => t.includes(uiAccount)),
    '列表行数=' + afterRows.length);

  const toast = await page.evaluate(() => {
    const m = [...document.querySelectorAll('.el-message')].map(e => e.textContent.trim());
    return m.join(' | ');
  });
  check('保存后有成功提示', toast.includes('已创建') || toast.includes('成功'), toast.slice(0, 80));

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
    if (!dlg) return { labels: [], selects: 0, text: '' };
    return {
      labels: [...dlg.querySelectorAll('.el-form-item__label')].map(e => e.textContent.trim().replace(/[:：]/g, '')),
      selects: dlg.querySelectorAll('.el-select').length,
      text: dlg.textContent.replace(/\s+/g, ' ').slice(0, 240)
    };
  });
  check('流程弹窗含「关联单据类型」', flowDlg.labels.some(l => l.includes('关联单据类型')), flowDlg.labels.join(' | '));
  check('流程弹窗含「审批节点」', flowDlg.labels.some(l => l.includes('审批节点')), flowDlg.labels.join(' | '));
  const flowDlgText = flowDlg.text || '';
  check('弹窗提示了版本影响', flowDlgText.includes('新版本') || flowDlgText.includes('部署'), flowDlgText.slice(0, 120));

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
  await page.screenshot({ path: '/tmp/proto/shots2/admin-flow.png' });

  console.log('\n=== 6. 控制台 ===');
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

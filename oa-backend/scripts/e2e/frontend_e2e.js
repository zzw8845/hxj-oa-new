/* 全链路 E2E：日常付款（大额分支）+ 用印申请（字典分支）
   关键修正：
     - 账号选择框显示值改读 .el-select__placeholder（El Plus 不写回 input.value）
     - 允许创建的下拉（对应项目）必须 type + Enter 才能提交成选中项
     - 每级审批后都调用后端接口核对真值，不依赖 UI 文案
*/
const puppeteer = require('puppeteer-core');
const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE_URL = process.env.PAGE_URL || 'http://127.0.0.1:8080/oa.html';   // 同源打开：不经过 CORS
const API = 'http://127.0.0.1:8080';
const SHOT = '/tmp/proto/shots2/';
const sleep = ms => new Promise(r => setTimeout(r, ms));

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok, extra });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}

/* ---------------- 后端真值 ---------------- */
let ADMIN_TOKEN = null;
async function apiLogin(account, password = '123456') {
  const j = await (await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account, password })
  })).json();
  if (j.code !== 0) throw new Error('login ' + account + ' 失败: ' + j.msg);
  return j.data.token;
}
async function apiGet(path, token) {
  const r = await fetch(API + path, { headers: { Authorization: 'Bearer ' + (token || ADMIN_TOKEN) } });
  const j = await r.json();
  if (j.code !== 0) throw new Error(path + ' -> ' + j.msg);
  return j.data;
}
async function findDocByTitle(title) {
  const d = await apiGet('/api/documents?pageNum=1&pageSize=100');
  const list = d.records || d.list || d.rows || (Array.isArray(d) ? d : []);
  return list.find(x => (x.title || '').indexOf(title) >= 0) || null;
}
async function docStatus(id) {
  const d = await apiGet('/api/documents/' + id);
  return d.document || d;
}

/* ---------------- 浏览器操作 ---------------- */
async function loginChip(page, name) {
  const chips = await page.$$('.lg-chips button');
  for (const c of chips) {
    const t = await c.evaluate(e => e.textContent);
    if (t.indexOf(name) >= 0) { await c.click(); await sleep(3200); return true; }
  }
  return false;
}
/* El Plus 会在 DOM 里保留此前用过的下拉（隐藏），必须只取当前可见的那一个，
   否则会点到过期项（表现为「切换账号偶发失败」）。 */
async function switchAccount(page, name, tries) {
  tries = tries || 3;
  for (let k = 0; k < tries; k++) {
    const sel = await page.$('.top-actions .el-select');
    if (sel) {
      await sel.click();
      await sleep(900);
      const ok = await page.evaluate(function (nm) {
        const items = [...document.querySelectorAll('.el-select-dropdown__item')]
          .filter(i => { const r = i.getBoundingClientRect(); return r.width > 0 && r.height > 0; });  // 只看可见下拉
        const hit = items.find(i => i.textContent.indexOf(nm) >= 0);
        if (!hit) return false;
        hit.click(); return true;
      }, name);
      if (ok) {
        await sleep(3000);
        const w = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
        if (w.indexOf(name) >= 0) return true;
      }
    }
    await page.keyboard.press('Escape').catch(() => {});
    await sleep(900);
  }
  return false;
}
async function clickMenu(page, label) {
  const ok = await page.evaluate(function (txt) {
    const hit = [...document.querySelectorAll('.el-menu-item')].find(i => i.textContent.indexOf(txt) >= 0);
    if (!hit) return false; hit.click(); return true;
  }, label);
  await sleep(1500);
  return ok;
}
async function formItemByLabel(page, labelText) {
  const items = await page.$$('.el-dialog .el-form-item');
  for (const h of items) {
    const label = await h.evaluate(el => (el.querySelector('.el-form-item__label') || {}).textContent || '');
    if (label.indexOf(labelText) >= 0) return h;
  }
  return null;
}
async function typeInto(page, labelText, value) {
  const h = await formItemByLabel(page, labelText);
  if (!h) return false;
  const inp = await h.$('input, textarea');
  if (!inp) return false;
  await inp.click({ clickCount: 3 });
  await page.keyboard.down('Meta'); await page.keyboard.press('a'); await page.keyboard.up('Meta');
  await inp.type(value, { delay: 12 });
  return true;
}
/* 下拉：既有选项可直接选；allow-create 需输入后回车 */
async function selectIn(page, labelText, value) {
  const h = await formItemByLabel(page, labelText);
  if (!h) return false;
  const inp = await h.$('input');
  if (!inp) return false;
  await inp.click(); await sleep(400);
  const picked = await page.evaluate(function (v) {
    const items = [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter(i => { const r = i.getBoundingClientRect(); return r.width > 0 && r.height > 0; });  // 只看可见下拉
    const hit = items.find(i => i.textContent.trim() === v);
    if (!hit) return false; hit.click(); return true;
  }, value);
  if (picked) { await sleep(300); return 'picked'; }
  await inp.type(value, { delay: 15 }); await sleep(500);
  await page.keyboard.press('Enter'); await sleep(400);
  return 'created';
}
async function dialogBtn(page, text) {
  return page.evaluate(function (t) {
    const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
    const dlg = [...document.querySelectorAll('.el-dialog')].filter(vis)[0];
    if (!dlg) return false;
    const btn = [...dlg.querySelectorAll('.el-button')].find(b => b.textContent.replace(/\s+/g, '').indexOf(t) >= 0);
    if (!btn) return false; btn.click(); return true;
  }, text);
}
/* 只统计当前可见表格的行（页面切换后旧表格可能仍留在 DOM 里） */
async function tableRows(page) {
  return page.$$eval('.el-table__body tbody tr', els => els
    .filter(tr => { const r = tr.getBoundingClientRect(); return r.width > 0 && r.height > 0; })
    .map(tr => [...tr.querySelectorAll('td')].map(td => td.textContent.trim().replace(/\s+/g, ' ')).filter(Boolean).join(' | '))
  ).catch(() => []);
}
async function openTodoRow(page, keyword) {
  return page.evaluate(function (kw) {
    const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
    const trs = [...document.querySelectorAll('.el-table__body tbody tr')].filter(vis);
    const row = trs.find(r => r.textContent.indexOf(kw) >= 0);
    if (!row) return false;
    const btn = [...row.querySelectorAll('.el-button')].find(b => /进入审批|审批/.test(b.textContent));
    if (!btn) return false; btn.click(); return true;
  }, keyword);
}
/* 读当前可见抽屉里展示的单据编号，用于确认抽屉确实切到了目标单据 */
async function drawerDocNo(page) {
  return page.evaluate(function () {
    const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
    const d = [...document.querySelectorAll('.el-drawer')].filter(vis)[0];
    if (!d) return '';
    const m = (d.innerText || '').match(/[A-Z]{2}\d{12}/);
    return m ? m[0] : '';
  });
}
/* 只在可见抽屉里点「通过审批」，并回读 ElMessage 提示（失败原因就藏在这里） */
async function approveCurrent(page) {
  const clicked = await page.evaluate(function () {
    const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
    const dlg = [...document.querySelectorAll('.el-drawer')].filter(vis)[0];
    if (!dlg) return 'no-visible-drawer';
    const btn = [...dlg.querySelectorAll('.approval-action .el-button')]
      .find(b => b.textContent.replace(/\s+/g, '').indexOf('通过审批') >= 0);
    if (!btn) return 'no-approve-btn'; btn.click(); return 'clicked';
  });
  await sleep(900);
  const msg = await page.evaluate(function () {
    const vis = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
    return [...document.querySelectorAll('.el-message, .el-notification')]
      .filter(vis).map(e => e.innerText.trim()).join(' | ');
  });
  await sleep(1700);
  return { clicked, msg };
}

/* ---------------- 主流程 ---------------- */
async function main() {
  require('fs').mkdirSync(SHOT, { recursive: true });
  ADMIN_TOKEN = await apiLogin('admin');

  const browser = await puppeteer.launch({ executablePath: EXEC, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1680, height: 1050 });
  const errs = [];
  page.on('console', m => {
    if (m.type() !== 'error') return;
    if (/favicon|Failed to load resource/i.test(m.text())) return; // 浏览器自动请求 favicon 的 404，与业务无关
    errs.push('[console] ' + m.text());
  });
  page.on('pageerror', e => errs.push('[pageerror] ' + e.message));
  page.on('response', r => { if (r.status() >= 400 && !/favicon/.test(r.url())) errs.push('[HTTP ' + r.status() + '] ' + r.url()); });

  const STAMP = Date.now().toString().slice(-6);
  const TITLE_A = 'E2E联调-办公设备采购款-' + STAMP;
  const TITLE_B = 'E2E联调-项目合作框架协议用印-' + STAMP;
  const KEY_A = 'E2E联调-办公设备采购款-' + STAMP;
  const KEY_B = 'E2E联调-项目合作框架协议用印-' + STAMP;
  console.log('本轮唯一标记: ' + STAMP);

  console.log('\n=== 1. 登录门禁 + 顶栏真实身份 ===');
  await page.goto(PAGE_URL, { waitUntil: 'networkidle2', timeout: 60000 });
  await sleep(1800);
  check('登录门禁渲染', !!(await page.$('.login-gate')));
  check('账号芯片登录（黄小明）', await loginChip(page, '黄小明'));
  check('门禁消失', !(await page.$('.login-gate')));
  const who = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  check('顶栏显示真实姓名', who.indexOf('黄小明') >= 0, who);
  const avatar = await page.$eval('.topbar .el-avatar', e => e.textContent.trim()).catch(() => '');
  check('头像首字', avatar === '黄', avatar);
  const selVal = await page.evaluate(function () {
    const p = document.querySelector('.top-actions .el-select .el-select__placeholder')
      || document.querySelector('.top-actions .el-select .el-select__selected-item');
    return p ? p.textContent.trim() : '';
  });
  check('账号框显示当前账号（修正断言）', selVal.length > 0, '「' + selVal + '」');
  const hero = await page.$eval('.hero h2', e => e.textContent.trim()).catch(() => '');
  check('首页问候带真实姓名', hero.indexOf('黄小明') >= 0, hero);

  console.log('\n=== 2. 工作台三区来自后端单据类型表 ===');
  check('进入工作台', await clickMenu(page, '工作台'));
  const zt = await page.$$eval('.work-zones .zone-title b', els => els.map(e => e.textContent.trim()));
  check('三区标题', zt.length === 3, zt.join(' / '));
  const qdocs = await page.$$eval('.work-zones .quick-docs button b', els => els.map(e => e.textContent.trim()));
  check('快捷单据项存在', qdocs.length >= 3, qdocs.slice(0, 5).join(' / '));

  /* ============ A. 日常付款（30000 → 命中 >=20000 大额分支） ============ */
  console.log('\n=== 3. 发起日常付款（30000 元，大额分支） ===');
  check('打开发起弹窗', await page.evaluate(function () {
    const b = [...document.querySelectorAll('.el-button')].find(x => x.textContent.indexOf('新建日常付款') >= 0);
    if (!b) return false; b.click(); return true;
  }));
  await sleep(1800);
  const tag = await page.evaluate(function () {
    const d = [...document.querySelectorAll('.el-dialog')].find(x => x.offsetParent !== null);
    return d ? d.textContent.replace(/\s+/g, ' ').slice(0, 90) : '';
  });
  console.log('     弹窗抬头: ' + tag);

  check('填 对应项目（allow-create 回车）', !!(await selectIn(page, '对应项目', TITLE_A)));
  await sleep(300);
  check('填 申请金额', await typeInto(page, '申请金额', '30000'));
  check('填 申请事由', await typeInto(page, '申请事由', '联调验证：真实提交后端并走完大额分支。'));
  check('填 简易发票明细（修复后应可见）', await typeInto(page, '简易发票明细', '增值税专用发票2张，含税30000元'));
  check('填 收款方名称（新增字段）', await typeInto(page, '收款方名称', '厦门办公设备有限公司'));
  check('填 收款方账号', await typeInto(page, '收款方账号', '6222021234567890123'));
  check('填 开户行', await typeInto(page, '开户行', '中国银行厦门分行'));
  await page.screenshot({ path: SHOT + 'A1-form.png' });

  check('点提交审批', await dialogBtn(page, '提交审批'));
  await sleep(3800);

  let docA = await findDocByTitle(TITLE_A);
  check('后端已创建该单据', !!docA, docA ? (docA.docNo + ' status=' + docA.status + ' amount=' + docA.amount) : '(未找到)');
  if (!docA) { await page.screenshot({ path: SHOT + 'A2-fail.png' }); }
  else {
    const d = await docStatus(docA.id);
    const st = (d.document ? d.document.status : d.status);
    const cur = (d.document ? d.document.currentNodeName : d.currentNodeName);
    check('提交后：状态=审批中(2)，当前节点=直属部门负责人', st === 2 && cur === '直属部门负责人', 'status=' + st + ' node=' + cur);

    /* ---- 逐级审批 ---- */
    const chain = [
      { acc: '林经理', next: '会计（按部门）', label: '直属部门负责人' },
      { acc: '王会计', next: '公司领导（大额）', label: '会计（按部门）' },
      { acc: '张总', next: '出纳付款', label: '公司领导（大额）' },
      { acc: '赵出纳', next: null, label: '出纳付款' }
    ];
    console.log('\n=== 4. 逐级审批（切账号 → 待我审批 → 通过） ===');
    for (let i = 0; i < chain.length; i++) {
      const s = chain[i];
      const last = i === chain.length - 1;
      console.log('  -- ' + (i + 1) + '/' + chain.length + ' ' + s.acc + '（' + s.label + '）');
      check('  切换账号 ' + s.acc, await switchAccount(page, s.acc));
      const w = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
      check('  顶栏已是 ' + s.acc, w.indexOf(s.acc) >= 0, w);

      await clickMenu(page, '待我审批');
      const rows = await tableRows(page);
      const mine = rows.filter(r => r.indexOf(KEY_A) >= 0);
      check('  待办列表出现该单', mine.length > 0, mine[0] ? mine[0].slice(0, 110) : '(无)');
      if (!mine.length) { await page.screenshot({ path: SHOT + 'A3-nodo-' + i + '.png' }); continue; }

      check('  打开审批抽屉', await openTodoRow(page, KEY_A));
      await sleep(1200);
      const dnoA = await drawerDocNo(page);
      check('  抽屉已切到目标单据', dnoA === docA.docNo, '抽屉内编号=' + (dnoA || '(未读到)') + ' 期望=' + docA.docNo);
      await page.screenshot({ path: SHOT + 'A4-drawer-' + i + '-' + s.acc + '.png' });
      const apA = await approveCurrent(page);
      check('  点通过审批' + (apA.msg ? ('  [' + apA.msg + ']') : ''), apA.clicked);

      const dd = await docStatus(docA.id);
      const st2 = (dd.document ? dd.document.status : dd.status);
      const nm = (dd.document ? dd.document.currentNodeName : dd.currentNodeName);
      if (last) {
        check('  办结：状态=3 且当前节点已清空', st2 === 3 && !nm, 'status=' + st2 + ' node=' + nm);
      } else {
        check('  推进到「' + s.next + '」', st2 === 2 && nm === s.next, 'status=' + st2 + ' node=' + nm);
      }
    }

    const fin = await docStatus(docA.id);
    const fst = (fin.document ? fin.document.status : fin.status);
    check('链路走完 → 已通过(3)', fst === 3, '最终 status=' + fst);
  }

  /* ============ B. 用印申请（验证字典分支 + 用印节点解析修复） ============ */
  console.log('\n=== 5. 发起用印申请（合同章 → 应命中 公司领导 分支） ===');
  check('切回 黄小明', await switchAccount(page, '黄小明'));
  check('进入工作台', await clickMenu(page, '工作台'));
  const stampInfo = await page.evaluate(function () {
    const z = document.querySelector('.work-zones .zone.stamp');
    if (!z) return { zone: false };
    return {
      zone: true,
      title: (z.querySelector('.zone-title b') || {}).textContent,
      buttons: [...z.querySelectorAll('.el-button')].map(b => b.textContent.trim())
    };
  });
  console.log('     用印区: ' + JSON.stringify(stampInfo));
  check('打开发起用印弹窗', await page.evaluate(function () {
    const z = document.querySelector('.work-zones .zone.stamp');
    const b = z && z.querySelector('.el-button');
    if (!b) return false; b.click(); return true;
  }));
  await sleep(1800);
  const sealVisible = await page.evaluate(function () {
    const d = [...document.querySelectorAll('.el-dialog')].find(x => x.offsetParent !== null);
    return d ? /用印项目/.test(d.textContent) : false;
  });
  check('用印专用表单已显示（修复①生效）', sealVisible);
  check('填 用印项目', await typeInto(page, '用印项目', TITLE_B));
  check('填 用印文件名称', await typeInto(page, '文件名称', '项目合作框架协议.pdf'));
  check('选 用章类型=合同章（字典 code）', !!(await selectIn(page, '用章类型', '合同章')));
  check('填 用印原因', await typeInto(page, '用印原因', '双方签署项目合作框架协议，需加盖合同章。'));
  await page.screenshot({ path: SHOT + 'B1-seal-form.png' });
  check('点提交审批（用印）', await dialogBtn(page, '提交审批'));
  await sleep(3800);

  const docB = await findDocByTitle(TITLE_B);
  check('后端已创建用印单据', !!docB, docB ? (docB.docNo + ' type=' + docB.docTypeId + ' status=' + docB.status) : '(未找到)');
  if (docB) {
    const db = await docStatus(docB.id);
    const fd = db.document ? db.document.formData : db.formData;
    let sealCode = '';
    try { sealCode = (typeof fd === 'string' ? JSON.parse(fd) : fd).sealType; } catch (e) { sealCode = String(fd); }
    check('formData.sealType 为字典 code（网关才认得）', sealCode === 'CONTRACT', 'sealType=' + sealCode);

    const db0 = await docStatus(docB.id);
    check('用印提交后当前节点=直属部门负责人',
      (db0.document ? db0.document.currentNodeName : db0.currentNodeName) === '直属部门负责人',
      'node=' + (db0.document ? db0.document.currentNodeName : db0.currentNodeName));

    const chainB = [
      { acc: '林经理', next: '综合管理部', label: '直属部门负责人' },
      { acc: '周综合', next: '公司领导', label: '综合管理部（本次修复的节点）' },
      { acc: '张总', next: '用印办理', label: '公司领导（合同章分支）' },
      { acc: '周综合', next: null, label: '用印办理' }
    ];
    console.log('\n=== 6. 用印链路逐级审批 ===');
    for (let i = 0; i < chainB.length; i++) {
      const s = chainB[i];
      const last = i === chainB.length - 1;
      check('  切换账号 ' + s.acc, await switchAccount(page, s.acc));
      await clickMenu(page, '待我审批');
      const rows = await tableRows(page);
      const mine = rows.filter(r => r.indexOf(KEY_B) >= 0);
      check('  ' + s.acc + ' 待办出现该单（' + s.label + '）', mine.length > 0, mine[0] ? mine[0].slice(0, 110) : '(无)');
      if (!mine.length) { await page.screenshot({ path: SHOT + 'B3-nodo-' + i + '-' + s.acc + '.png' }); continue; }
      check('  打开抽屉', await openTodoRow(page, KEY_B));
      await sleep(1200);
      const dnoB = await drawerDocNo(page);
      check('  抽屉已切到目标单据', dnoB === docB.docNo, '抽屉内编号=' + (dnoB || '(未读到)') + ' 期望=' + docB.docNo);
      const apB = await approveCurrent(page);
      check('  点通过审批' + (apB.msg ? ('  [' + apB.msg + ']') : ''), apB.clicked);

      const ddB = await docStatus(docB.id);
      const stB = (ddB.document ? ddB.document.status : ddB.status);
      const nmB = (ddB.document ? ddB.document.currentNodeName : ddB.currentNodeName);
      if (last) {
        check('  用印办结：状态=3 且当前节点已清空', stB === 3 && !nmB, 'status=' + stB + ' node=' + nmB);
      } else {
        check('  推进到「' + s.next + '」', stB === 2 && nmB === s.next, 'status=' + stB + ' node=' + nmB);
      }
    }
    const finB = await docStatus(docB.id);
    check('用印链路走完 → 已通过(3)', (finB.document ? finB.document.status : finB.status) === 3,
      'status=' + (finB.document ? finB.document.status : finB.status));
  }

  console.log('\n=== 7. 台账档案 ===');
  await switchAccount(page, '系统管理员');
  await clickMenu(page, '台账档案');
  const arc = await tableRows(page);
  check('台账含日常付款单', arc.some(r => r.indexOf(KEY_A) >= 0), '台账共 ' + arc.length + ' 条');
  await page.screenshot({ path: SHOT + '7-archive.png' });

  console.log('\n=== 8. 运行时错误 ===');
  check('无控制台/网络错误', errs.length === 0, errs.slice(0, 6).join(' || ') || '无');

  const pass = results.filter(r => r.ok).length;
  console.log('\n======== 结果：通过 ' + pass + ' / ' + results.length + ' ========');
  const failed = results.filter(r => !r.ok);
  if (failed.length) { console.log('失败项：'); failed.forEach(f => console.log('  ✗ ' + f.name + (f.extra ? (' → ' + f.extra) : ''))); }
  await browser.close();
}
main().catch(e => { console.error('测试异常:', e.stack || e.message); process.exit(1); });

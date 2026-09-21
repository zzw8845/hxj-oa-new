/*
 * 看板真值 + 用印资料清单 + 关联前置单据 全链路 UI 端到端
 *
 * 为什么需要这一层：
 *   本轮改的三件事，共同点是"接口层证明不了"——
 *     A. 看板上的数字到底是真值还是写死的常量（接口对 ≠ 界面用了它）
 *     B. 用印申请的资料清单首项是不是真的上传控件（模板按下标判定时会渲染成关联按钮）
 *     C. 「关联前置单据」选完能不能落库、能不能在详情里看见
 *
 * 覆盖：
 *   A. 工作看板「单据状态分布」== 后端统计真值（且与当前用户行级数据范围一致），不是写死的 27/154/5
 *   B. 两个菜单角标 == 真实未读通知数 / 真实待办数；为 0 时隐藏（用两个账号覆盖 可见/隐藏 两个分支）
 *   C. 用印申请：资料清单首项是「用印文件附件」的真实上传控件（不是「关联单据」按钮），能选中文件
 *   D. 日常付款：关联前置单据 → 提交 → document_link 落库 → 详情「关联单据」tab 展示单号与标题
 *
 * 数据卫生：
 *   走真实演示流程，结束时把产生的单据连同 Flowable HI/RU、关联行、通知一起清掉，
 *   并在清理前后对比"演示状态快照"（含 notification 与孤儿数）。
 */
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const os = require('os');
const path = require('path');

const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE = 'http://127.0.0.1:8080/oa.html';
const API = 'http://127.0.0.1:8080';
const PW = '123456';
const sleep = ms => new Promise(r => setTimeout(r, ms));

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}
function section(t) { console.log('\n' + '='.repeat(74) + '\n' + t + '\n' + '='.repeat(74)); }

/* ---------------- 数据库 ----------------
   走共用卫生模块：快照字段与不变量断言只允许在 _hygiene.js 里定义一处，
   避免各脚本各抄一份导致字段漂移、断言静默跳过。 */
const { sqlSafe, snapshot, assertInvariants, uidOf } = require('./_hygiene.js');

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
const VIS = 'e => e.getClientRects().length > 0';

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
async function clickButtonByText(page, text) {
  const ok = await page.evaluate((t) => {
    const b = [...document.querySelectorAll('.el-button')]
      .find(x => x.getClientRects().length > 0 && x.textContent.replace(/\s+/g, '').includes(t));
    if (b) { b.click(); return true; }
    return false;
  }, text.replace(/\s+/g, ''));
  await sleep(1600);
  return ok;
}
async function latestToast(page) {
  return page.evaluate(() => [...document.querySelectorAll('.el-message')]
    .map(e => e.textContent.trim()).join(' | '));
}
async function clearToasts(page) {
  await page.evaluate(() => document.querySelectorAll('.el-message').forEach(e => e.remove()));
}
/** ElMessage 3 秒自动消失：必须轮询，不能 sleep 完再读 */
async function waitToast(page, timeoutMs = 8000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const t = await latestToast(page);
    if (t) return t;
    await sleep(250);
  }
  return '';
}
async function closeDialogs(page) {
  await page.evaluate(() => {
    [...document.querySelectorAll('.el-dialog, .el-drawer')].filter(d => d.offsetParent !== null)
      .forEach(d => {
        const b = [...d.querySelectorAll('.el-button')].find(x => /取消|关闭|^×$/.test(x.textContent.trim()));
        if (b) b.click();
      });
  });
  await sleep(700);
}
const ACCOUNT_NAME = {
  admin: '系统管理员', huangxm: '黄小明', linjl: '林经理', wangkj: '王会计',
  zhangzong: '张总', zhaocs: '赵出纳', chennk: '陈内控',
  zhouzh: '周综合', lifinance: '李财务'
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
async function fillInDialog(page, label, value) {
  return page.evaluate((label, value) => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
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
/** 「对应项目」是 allow-create 下拉，只输入不算选中，必须回车 */
async function pickProject(page, text) {
  const opened = await page.evaluate(() => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
    const dlg = dlgs[dlgs.length - 1];
    if (!dlg) return false;
    const it = [...dlg.querySelectorAll('.el-form-item')].find(i => {
      const l = i.querySelector('.el-form-item__label');
      return l && l.textContent.trim().replace(/[:：]/g, '') === '对应项目';
    });
    if (!it) return false;
    const w = it.querySelector('.el-select__wrapper') || it.querySelector('.el-select');
    if (!w) return false;
    w.click();
    return true;
  });
  if (!opened) return { ok: false, value: '' };
  await sleep(600);
  await page.keyboard.type(text, { delay: 15 });
  await sleep(800);
  await page.keyboard.press('Enter');
  await sleep(600);
  const value = await page.evaluate(() => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
    const dlg = dlgs[dlgs.length - 1];
    const it = dlg && [...dlg.querySelectorAll('.el-form-item')].find(i => {
      const l = i.querySelector('.el-form-item__label');
      return l && l.textContent.trim().replace(/[:：]/g, '') === '对应项目';
    });
    if (!it) return '';
    const sel = it.querySelector('.el-select__selected-item');
    const ph = it.querySelector('.el-select__placeholder');
    let txt = sel ? sel.textContent.trim() : '';
    if (!txt && ph && !ph.classList.contains('is-transparent')) txt = ph.textContent.trim();
    const inp = it.querySelector('input');
    return (txt || (inp ? inp.value : '')).trim();
  });
  return { ok: value.length > 0, value };
}
async function clickInDialog(page, text) {
  const ok = await page.evaluate((t) => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
    const dlg = dlgs[dlgs.length - 1];
    if (!dlg) return false;
    const b = [...dlg.querySelectorAll('.el-button')]
      .find(x => x.textContent.replace(/\s+/g, '').includes(t));
    if (!b) return false;
    b.click();
    return true;
  }, text.replace(/\s+/g, ''));
  return ok;
}
async function clickVisibleTab(page, text) {
  const ok = await page.evaluate((t) => {
    const tab = [...document.querySelectorAll('.el-tabs__item')]
      .filter(e => e.getClientRects().length > 0)
      .find(x => x.textContent.includes(t));
    if (!tab) return false;
    tab.click();
    return true;
  }, text);
  await sleep(900);
  return ok;
}
async function openDocByNo(page, docNo) {
  const ok = await page.evaluate((no) => {
    const tr = [...document.querySelectorAll('.el-table__body tbody tr')]
      .find(t => t.textContent.includes(no));
    if (!tr) return false;
    tr.click();
    return true;
  }, docNo);
  await sleep(1600);
  return ok;
}

/* 演示状态快照：用 _hygiene.js 的统一版本（字段顺序与不变量只此一处定义） */

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'oa-dash-'));
const PNG_PATH = path.join(TMP, '用印文件.png');
fs.writeFileSync(PNG_PATH, Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
  'base64'));

(async () => {
  const browser = await puppeteer.launch({
    executablePath: EXEC, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1100 });

  const failures = [];
  page.on('pageerror', e => failures.push('pageerror: ' + e.message));
  const badResponses = [];
  page.on('response', r => {
    if (r.status() >= 400 && !/\/api\/permissions/.test(r.url())) {
      badResponses.push(r.status() + ' ' + r.request().method() + ' ' + r.url().replace(API, ''));
    }
  });

  let docId = null, docNo = '';
  const baseline = snapshot('跑测试前');

  try {
    /* ================= 一、准备 ================= */
    section('一、准备');
    const tok = await login('huangxm');
    const huangId = uidOf('huangxm');
    const zhouId = uidOf('zhouzh');
    check('接口登录成功', !!tok);
    check('拿到黄小明 / 周综合 的 userId', /^\d+$/.test(huangId) && /^\d+$/.test(zhouId),
      'huangxm=' + huangId + ' zhouzh=' + zhouId);

    const resp = await page.goto(PAGE, { waitUntil: 'networkidle2' });
    check('页面 HTTP 200', resp && resp.status() === 200, 'status=' + (resp && resp.status()));
    const g = await loginViaGate(page, 'huangxm');
    check('登录门禁进入系统', g.ok, g.who || g.reason);

    /* ================= 二、看板状态分布 = 后端真值 ================= */
    section('二、工作看板「单据状态分布」是真实数据');

    // 后端真值：黄小明是 SELF 范围，只能看到自己的单据
    const st = await api('GET', '/api/documents/stats', tok);
    const s = st.body.data || {};
    check('GET /api/documents/stats 可用', st.status === 200 && st.body.code === 0,
      JSON.stringify(s));
    check('  统计带上了行级数据范围（SELF：只算本人单据）',
      String(s.total) === sqlSafe(`SELECT COUNT(*) FROM document WHERE deleted=0 AND applicant_id=${huangId}`),
      '接口 total=' + s.total + ' 本人单据=' +
      sqlSafe(`SELECT COUNT(*) FROM document WHERE deleted=0 AND applicant_id=${huangId}`));
    check('  统计与「我发起的」列表条数一致',
      String(s.total) === String((await api('GET', '/api/documents?pageNum=1&pageSize=1&scope=mine', tok)).body.data.total),
      'stats=' + s.total);

    await clickMenu(page, '工作看板');
    const grid = await page.evaluate(() => {
      const g = [...document.querySelectorAll('.status-grid')].filter(e => e.getClientRects().length > 0)[0];
      if (!g) return null;
      return [...g.children].map(d => ({
        label: (d.querySelector('span') || {}).textContent ? d.querySelector('span').textContent.trim() : '',
        num: (d.querySelector('b') || {}).textContent ? d.querySelector('b').textContent.trim() : ''
      }));
    });
    check('工作看板渲染出状态分布三格', !!grid && grid.length === 3,
      grid ? grid.map(x => x.label + '=' + x.num).join(' / ') : '(没找到 .status-grid)');

    if (grid && grid.length === 3) {
      const map = {};
      grid.forEach(x => { map[x.label] = Number(x.num); });
      check('  「审批中」= 后端 running（不是写死的 27）',
        map['审批中'] === s.running, '界面=' + map['审批中'] + ' 后端=' + s.running);
      check('  「已通过」= 后端 approved（不是写死的 154）',
        map['已通过'] === s.approved, '界面=' + map['已通过'] + ' 后端=' + s.approved);
      check('  「已驳回」= 后端 rejected（不是写死的 5）',
        map['已驳回'] === s.rejected, '界面=' + map['已驳回'] + ' 后端=' + s.rejected);
      check('  三格数字与写死的假值完全不同',
        !(map['审批中'] === 27 && map['已通过'] === 154 && map['已驳回'] === 5),
        JSON.stringify(map));
    }

    /* ================= 三、菜单角标 = 真实值 ================= */
    section('三、菜单角标是真实数据（可见/隐藏两个分支都覆盖）');

    const readBadges = () => page.evaluate(() => {
      const out = {};
      [...document.querySelectorAll('.el-menu-item')].forEach(mi => {
        const label = (mi.textContent.match(/[\u4e00-\u9fa5]/g) || []).join('');
        const b = mi.querySelector('.el-badge__content');
        // 值为 0 时 ElBadge 不渲染 sup（showZero 默认 false），统一归一成 {text:'', visible:false}，
        // 避免「取到 null」和「值为 0」两种情况在断言里被混为一谈
        out[label] = b
          ? { text: b.textContent.trim(), visible: b.getClientRects().length > 0 }
          : { text: '', visible: false };
      });
      return out;
    });

    /* 侧栏文案：运行时补丁把「审批中心」改名成了「待我审批」（pageNames.approve），
       所以取值必须用「待我审批」——按源码里的旧名字取键会永远取不到。 */
    const MENU_WORK = '工作台', MENU_APPROVE = '待我审批';
    const unreadH = (await api('GET', '/api/notifications/unread-count', tok)).body.data;
    const todoH = ((await api('GET', '/api/todos', tok)).body.data || []).length;
    let bd = await readBadges();
    check('黄小明：工作台角标 = 真实未读通知数', Number(bd[MENU_WORK].text || 0) === Number(unreadH),
      '界面=' + JSON.stringify(bd[MENU_WORK]) + ' 接口=' + unreadH);
    check('  未读 > 0 时角标可见', bd[MENU_WORK].visible === (Number(unreadH) > 0),
      '未读=' + unreadH + ' 可见=' + bd[MENU_WORK].visible);
    check('黄小明：待我审批角标 = 真实待办数', Number(bd[MENU_APPROVE].text || 0) === Number(todoH),
      '界面=' + JSON.stringify(bd[MENU_APPROVE]) + ' 接口=' + todoH);
    check('  待办 = 0 时角标隐藏（不是显示一个 0）',
      bd[MENU_APPROVE].visible === (todoH > 0),
      '待办=' + todoH + ' 可见=' + bd[MENU_APPROVE].visible);
    check('  角标数字不是写死的 8 / 5',
      bd[MENU_WORK].text !== '8' && bd[MENU_APPROVE].text !== '5',
      JSON.stringify(bd));

    // 换一个"有待办"的账号，覆盖角标可见的分支
    const gz = await loginViaGate(page, 'zhouzh');
    const tokZ = await login('zhouzh');
    const todoZ = ((await api('GET', '/api/todos', tokZ)).body.data || []).length;
    const unreadZ = (await api('GET', '/api/notifications/unread-count', tokZ)).body.data;
    bd = await readBadges();
    check('周综合：待我审批角标 = 真实待办数且可见',
      Number(bd[MENU_APPROVE].text || 0) === Number(todoZ)
      && bd[MENU_APPROVE].visible === (todoZ > 0),
      '登录=' + gz.who + ' 界面=' + JSON.stringify(bd[MENU_APPROVE]) + ' 接口=' + todoZ);
    check('周综合：工作台角标 = 真实未读数（无未读则隐藏）',
      Number(bd[MENU_WORK].text || 0) === Number(unreadZ)
      && bd[MENU_WORK].visible === (Number(unreadZ) > 0),
      '界面=' + JSON.stringify(bd[MENU_WORK]) + ' 接口=' + unreadZ);

    /* ================= 四、用印申请：资料清单首项必须是上传控件 ================= */
    section('四、用印申请的资料清单首项是真实上传控件');

    const back = await loginViaGate(page, 'huangxm');
    await clickMenu(page, '工作台');
    const openedSeal = await clickButtonByText(page, '新建用印申请');
    check('工作台「新建用印申请」能打开提交弹窗', openedSeal);

    const sealItems = await page.evaluate(() => {
      const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
      const dlg = dlgs[dlgs.length - 1];
      if (!dlg) return null;
      return [...dlg.querySelectorAll('.attachment-item')].map(e => ({
        text: e.textContent.replace(/\s+/g, ' ').trim().slice(0, 30),
        hasFile: !!e.querySelector('input[type=file]'),
        hasLinkBtn: [...e.querySelectorAll('.el-button')].some(b => /关联单据|重新关联/.test(b.textContent))
      }));
    });
    check('用印申请资料清单只有 1 项（用印文件附件）', !!sealItems && sealItems.length === 1,
      sealItems ? sealItems.map(x => x.text).join(' / ') : '(没找到 .attachment-item)');
    if (sealItems && sealItems.length) {
      check('  首项是真实上传控件（不再是「关联单据」按钮）', sealItems[0].hasFile,
        'hasFile=' + sealItems[0].hasFile + ' hasLinkBtn=' + sealItems[0].hasLinkBtn);
      check('  首项没有「关联单据」按钮', !sealItems[0].hasLinkBtn, sealItems[0].text);
    }

    // 真的塞一个文件进去，证明这条路是通的（不是"有个 input 但没接线"）
    const h = await page.evaluateHandle(() => {
      const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
      const dlg = dlgs[dlgs.length - 1];
      const it = dlg && [...dlg.querySelectorAll('.attachment-item')][0];
      return it ? it.querySelector('input[type=file]') : null;
    });
    const fileEl = h.asElement();
    if (fileEl) {
      await fileEl.uploadFile(PNG_PATH);
      await sleep(900);
    }
    const sealListText = await page.evaluate(() => {
      const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
      const dlg = dlgs[dlgs.length - 1];
      const l = dlg && dlg.querySelector('.attachment-item .el-upload-list');
      return l ? l.textContent.replace(/\s+/g, ' ').trim() : '';
    });
    check('  用印文件能选中并出现在列表里', sealListText.includes('用印文件'),
      sealListText.slice(0, 90) || '(列表为空)');
    await closeDialogs(page);

    /* ================= 五、关联前置单据全链路 ================= */
    section('五、关联前置单据：选择 → 提交 → 落库 → 详情可见');

    await clickMenu(page, '工作台');
    await clickButtonByText(page, '新建日常付款');

    const linkItem = await page.evaluate(() => {
      const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
      const dlg = dlgs[dlgs.length - 1];
      if (!dlg) return null;
      const it = [...dlg.querySelectorAll('.attachment-item')]
        .find(e => e.textContent.includes('关联前置单据'));
      if (!it) return null;
      const b = it.querySelector('.el-button');
      if (!b) return null;
      b.click();
      return it.textContent.replace(/\s+/g, ' ').trim().slice(0, 60);
    });
    check('日常付款资料清单首项是「关联前置单据」且有关联按钮', !!linkItem, linkItem || '(没找到)');
    await sleep(1800);

    const cand = await page.evaluate(() => {
      const vis = e => e.getClientRects().length > 0;
      // 注意：不能按「弹窗文本里含『关联前置单据』」来找 —— 提交弹窗的附件清单里
      // 也有这几个字，会先命中它，然后在里面找表格行永远为空（第一版就栽在这）。
      // 判据改成：这个可见弹窗里存在一行带「选择关联」按钮的记录。
      for (const dlg of [...document.querySelectorAll('.el-dialog')].filter(vis)) {
        const tr = dlg.querySelector('.el-table__body tbody tr');
        if (!tr) continue;
        const b = [...tr.querySelectorAll('.el-button')].find(x => x.textContent.includes('选择关联'));
        if (!b) continue;
        const tds = [...tr.querySelectorAll('td')].map(t => t.textContent.trim());
        b.click();
        return { docNo: tds[0], name: tds[1] };
      }
      return null;
    });
    check('关联弹窗列出了候选前置单据（已审批/已归档）', !!cand,
      cand ? cand.docNo + ' · ' + cand.name : '(无候选行)');
    await sleep(1000);
    const linkToast = await waitToast(page, 6000);
    check('  点「选择关联」后给出已关联提示', linkToast.includes('已关联'), linkToast.slice(0, 80));
    check('  提交弹窗里回显了被关联单据', await page.evaluate((no) => {
      if (!no) return false;
      return [...document.querySelectorAll('.el-dialog')]
        .filter(d => d.getClientRects().length > 0)
        .some(d => d.textContent.includes(no));
    }, cand && cand.docNo), cand && cand.docNo);

    const proj = await pickProject(page, 'E2E关联单据验证单');
    const f2 = await fillInDialog(page, '申请金额（元）', '3600');
    const f3 = await fillInDialog(page, '申请事由', '验证关联前置单据全链路，跑完即清理');
    const f4 = await fillInDialog(page, '收款方名称', 'E2E测试收款单位');
    const f5 = await fillInDialog(page, '收款方账号', '6222020200115566');
    const f6 = await fillInDialog(page, '开户行', '中国建设银行');
    await sleep(400);
    check('  提交弹窗必填项都填上了', proj.ok && f2 && f3 && f4 && f5 && f6,
      '项目=' + JSON.stringify(proj.value) + ' 金额=' + f2 + ' 事由=' + f3
      + ' 收款=' + f4 + ' 账号=' + f5 + ' 开户行=' + f6);

    const createResp = page.waitForResponse(
      r => /\/api\/documents$/.test(r.url()) && r.request().method() === 'POST', { timeout: 20000 }
    ).catch(() => null);
    await clearToasts(page);
    await clickInDialog(page, '提交审批');
    const cr = await createResp;
    const crJson = cr ? await cr.json().catch(() => null) : null;
    if (crJson && crJson.code === 0 && crJson.data) {
      docId = crJson.data.id;
      docNo = crJson.data.docNo || '';
    }
    const subToast = await waitToast(page, 12000);
    check('提交成功', subToast.includes('已提交审批'), subToast.slice(0, 120));
    check('  单据已落库', !!docId && !!docNo, 'id=' + docId + ' docNo=' + docNo);
    if (!subToast.includes('已提交审批')) throw new Error('提交未成功：' + (subToast || '(无提示)'));

    // 落库复核：document_link 指向的正是界面上选的那张单
    const linkedId = cand ? sqlSafe(`SELECT id FROM document WHERE doc_no='${cand.docNo}' AND deleted=0`) : '';
    const linkRows = sqlSafe(
      `SELECT CONCAT(linked_id,'|',link_type) FROM document_link WHERE document_id=${docId}`);
    check('document_link 落了关联行（不是只存在前端）', !!linkRows && linkRows !== '',
      'linked_id|type = ' + (linkRows || '(空)'));
    check('  关联的正是界面上选中的那张单', linkRows.split('|')[0] === String(linkedId),
      '落库=' + linkRows.split('|')[0] + ' 期望=' + linkedId + ' (' + (cand && cand.docNo) + ')');

    // 接口层的关联信息已补齐编号/标题
    const det = await api('GET', '/api/documents/' + docId, tok);
    const links = (det.body.data || {}).links || [];
    check('详情接口回传了带编号/标题的关联单据', links.length === 1
      && links[0].linkedDocNo === (cand && cand.docNo) && !!links[0].linkedTitle,
      JSON.stringify(links));

    // 界面层：详情抽屉「关联单据」tab 里看得见
    await closeDialogs(page);
    await clickMenu(page, '工作台');
    const openedDoc = await openDocByNo(page, docNo);
    check('从「我已发起的单据」打开详情', openedDoc, 'docNo=' + docNo);
    const tabOk = await clickVisibleTab(page, '关联单据');
    check('详情抽屉里有「关联单据」tab', tabOk);
    const linkTab = await page.evaluate((no) => {
      const p = [...document.querySelectorAll('.el-tab-pane')]
        .filter(e => e.getClientRects().length > 0)
        .find(x => x.textContent.includes(no));
      return p ? p.textContent.replace(/\s+/g, ' ').trim().slice(0, 130) : '';
    }, cand && cand.docNo);
    check('  详情里看得见被关联单据的编号与标题', linkTab.includes(cand && cand.docNo),
      linkTab || '(该 tab 里没有这张单号)');

    /* ================= 六、控制台 ================= */
    section('六、控制台');
    const noise = badResponses.filter(x => /403 .*\/api\/permissions/.test(x));
    const real = badResponses.filter(x => !/403 .*\/api\/permissions/.test(x));
    check('无未预期的失败请求 / JS 异常', real.length === 0 && failures.length === 0,
      real.concat(failures).join(' ; ').slice(0, 160) || '(干净)');
    if (noise.length) console.log('    · 既有噪音 ' + noise.length + ' 条：' + noise[0]);

  } catch (e) {
    console.error('\n[E2E 中断] ' + e.message);
    console.error(e);
  } finally {
    await browser.close().catch(() => {});

    /* ================= 七、清理 ================= */
    section('七、清理测试数据');
    if (docId) {
      const procInsts = sqlSafe(
        `SELECT p.PROC_INST_ID_ FROM ACT_HI_PROCINST p WHERE p.BUSINESS_KEY_='${docNo}'`);
      const pids = (procInsts || '').split('\n').map(s => s.trim())
        .filter(s => /^[0-9a-fA-F-]{8,}$/.test(s));
      for (const t of ['ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
                       'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG']) {
        for (const pid of pids) sqlSafe(`DELETE FROM ${t} WHERE PROC_INST_ID_='${pid}'`);
      }
      for (const t of ['ACT_RU_IDENTITYLINK', 'ACT_RU_ACTINST', 'ACT_RU_TASK',
                       'ACT_RU_VARIABLE', 'ACT_RU_EVENT_SUBSCR']) {
        for (const pid of pids) sqlSafe(`DELETE FROM ${t} WHERE PROC_INST_ID_='${pid}'`);
      }
      for (const pid of pids) sqlSafe(`DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${pid}' AND PARENT_ID_ IS NOT NULL`);
      for (const pid of pids) sqlSafe(`DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${pid}'`);
      for (const pid of pids) sqlSafe(`DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${pid}'`);
      // 关联行 + 通知必须一起清：否则 document_link 残留、通知表只增不减，
      // 而通知一旦显示成未读角标，"幽灵角标"就会一直挂在那里
      sqlSafe(`DELETE FROM document_link WHERE document_id=${docId}`);
      sqlSafe(`DELETE FROM notification WHERE biz_type='document' AND biz_id=${docId}`);
      sqlSafe(`DELETE FROM attachment WHERE document_id=${docId}`);
      sqlSafe(`DELETE FROM flow_instance_node WHERE document_id=${docId}`);
      sqlSafe(`DELETE FROM flow_instance WHERE document_id=${docId}`);
      sqlSafe(`DELETE FROM document WHERE id=${docId}`);
      console.log(`  已清理用例单据 #${docId}（${docNo}），含 ${pids.length} 个流程实例历史、关联行与通知`);
    }
    // 夹具文件（本用例只生成本地临时文件，不进存储目录）
    try { fs.rmSync(TMP, { recursive: true, force: true }); } catch (e) { /* ignore */ }

    const after = snapshot('清理后');
    check('演示状态快照与跑测试前完全一致', after === baseline,
      after === baseline ? '一致' : ('前=' + baseline + ' 后=' + after));
    // 绝对不变量：before/after 对比证明不了"基线本身是干净的"（before=after=脏 时对比照样一致），
    // 所以对必须恒为 0 / 必须相等的探针额外断言。见 _hygiene.js。
    assertInvariants(after, check);
    check('临时文件清理', !fs.existsSync(TMP));

    const passed = results.filter(r => r.ok).length;
    console.log('\n' + '='.repeat(74));
    console.log(`  看板真值 / 用印附件 / 关联单据 UI 验证：${passed}/${results.length} 通过`);
    const bad = results.filter(r => !r.ok);
    if (bad.length) {
      console.log('  失败项：');
      bad.forEach(r => console.log('    - ' + r.name));
    }
    console.log('='.repeat(74));
    process.exit(bad.length ? 1 : 0);
  }
})();

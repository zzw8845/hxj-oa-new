/*
 * 附件链路 UI 端到端（真实浏览器，不用假数据）
 *
 * 为什么必须有这一层：
 *   verify_attachment_api.py 只能证明「接口是对的」。
 *   但本轮改动里最有价值也最容易被吃掉的，是"界面上到底有没有那个上传控件"——
 *   事实上有一次就差点被吃掉：详情抽屉的模板补丁在挂载前把 .approval-action 里的
 *   el-upload 删掉了（当年它是个摆设，删掉是对的），结果后端拦得住、界面上却无处可传。
 *   纯接口测试永远发现不了这种问题。
 *
 * 覆盖：
 *   A. 发起端：工作台「新建日常付款」→ 提交弹窗上传附件 → 提交审批
 *   B. 详情抽屉「附件资料」：真实文件名/大小/上传人；点下载拿到的字节与上传一致
 *   C. 审批中的单据不给删除入口（附件属于审批留痕）
 *   D. 草稿单据可删除附件，删完回到空态
 *   E. 办理节点：按钮文案带「（必填）」、不带凭证点通过被拦下、补传凭证后通过
 *
 * 数据卫生：
 *   用真实演示流程（日常付款审批流程 v1）跑，所以必须在结束时把产生的单据连同
 *   Flowable 历史一起清掉，并在清理前后各拍一次"演示状态快照"做对比。
 *   快照不一致就判失败 —— 不能为了跑测试把演示库弄脏。
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

/* 预期断言总数：脚本正常跑完必须**恰好**产出这么多条。
   为什么要把这个数写死在代码里：本项目出过一次「假绿灯」—— 上游某条断言依赖的接口被回退后
   抛错中断，导致其后 16 条断言（含整条审计留痕链路）**从未执行**，而末行照样打印
   "85/85 通过"。有了这个数，任何"少跑了"都会立刻变成红灯，而不是无声无息。 */
const EXPECTED_TOTAL = 56;

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}
function section(t) { console.log('\n' + '='.repeat(74) + '\n' + t + '\n' + '='.repeat(74)); }

/* ---------------- 数据库（只用于清理与快照，不改业务数据） ----------------
   走共用卫生模块：快照字段与不变量断言只在 _hygiene.js 定义一处。 */
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
  return { token: r.body.data.token, userId: r.body.data.userId || (r.body.data.user && r.body.data.user.id) };
}
/** 以某个账号上传一个附件（夹具用） */
async function apiUpload(token, filePath, fields) {
  const fd = new FormData();
  fd.append('file', new Blob([fs.readFileSync(filePath)]), path.basename(filePath));
  for (const [k, v] of Object.entries(fields || {})) fd.append(k, String(v));
  const res = await fetch(API + '/api/attachments', {
    method: 'POST', headers: { 'Authorization': 'Bearer ' + token }, body: fd
  });
  return { status: res.status, body: await res.json() };
}

/* ---------------- 浏览器小工具 ---------------- */
async function clickMenu(page, text) {
  const candidates = Array.isArray(text) ? text : [text];
  const hit = await page.evaluate((list) => {
    const items = [...document.querySelectorAll('.el-menu-item, .el-sub-menu__title')];
    for (const t of list) {
      const h = items.find(i => i.textContent.includes(t));
      if (h) { h.click(); return t; }
    }
    return '';
  }, candidates);
  await sleep(1600);
  return hit;
}
async function clickButtonByText(page, text, scope) {
  return page.evaluate((t, s) => {
    const root = s ? document.querySelector(s) : document;
    if (!root) return false;
    const b = [...root.querySelectorAll('.el-button')]
      .find(x => x.textContent.replace(/\s+/g, '').includes(t));
    if (b) { b.click(); return true; }
    return false;
  }, text.replace(/\s+/g, ''), scope || null);
}
/* 填表统一走 fillInDialog：作用域是"最后一个可见弹窗"。
   （曾经这里有一个按选择器传作用域的 fillInput，用 '.el-dialog' 时会命中隐藏的旧弹窗，
    填了等于没填且不报错 —— 已删除，避免再被误用。） */

async function latestToast(page) {
  return page.evaluate(() => [...document.querySelectorAll('.el-message')]
    .map(e => e.textContent.trim()).join(' | '));
}
async function clearToasts(page) {
  await page.evaluate(() => document.querySelectorAll('.el-message').forEach(e => e.remove()));
}

/** 等第一条提示出现。
    ElMessage 默认 3 秒自动消失 —— 先 sleep(4500) 再读，读到的永远是空字符串，
    于是"提交失败"会被表现成"没有任何提示"，非常误导。 */
async function waitToast(page, timeoutMs = 8000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const t = await latestToast(page);
    if (t) return t;
    await sleep(250);
  }
  return '';
}

/* 页面上同时存在多个 .el-dialog（提交单、关联单据、流程预览…），关掉的那些只是 display:none。
   document.querySelector('.el-dialog') 拿到的极可能是隐藏的旧弹窗 ——
   表现就是"表单填了却没生效""点了提交没反应"，且不报任何错，非常难查。
   统一改成"最后一个可见的弹窗"。 */

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

/**
 * 「对应项目」是 allow-create 的可搜索下拉（前面的补丁把原来的「申请事项」输入框改成了下拉，
 * 绑定 submitForm.title）。所以不能当普通 input 填 —— 要先把下拉打开，
 * 再输入自定义值并回车，allow-create 才会把它建成一个选项。
 */
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
  if (!opened) return { ok: false, value: '', reason: '找不到「对应项目」下拉' };
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
    /* el-select 选中后展示的是 .el-select__selected-item 里的 label，
       内部那个 filter input 的 value 会被清空 —— 拿 input.value 判断"填没填上"必定误判。
       未选中时展示的是 .el-select__placeholder，Element Plus 会给它加 is-transparent，
       所以要排除掉，否则空值会被当成已填。 */
    const sel = it.querySelector('.el-select__selected-item');
    const ph = it.querySelector('.el-select__placeholder');
    let txt = sel ? sel.textContent.trim() : '';
    if (!txt && ph && !ph.classList.contains('is-transparent')) txt = ph.textContent.trim();
    const inp = it.querySelector('input');
    return (txt || (inp ? inp.value : '')).trim();
  });
  return { ok: value.length > 0, value };
}

/** 在最后一个可见弹窗里点按钮；返回是否点到（false 要当失败报出来，不能静默） */
async function clickInDialog(page, text) {
  return page.evaluate((t) => {
    const dlgs = [...document.querySelectorAll('.el-dialog')].filter(d => d.getClientRects().length > 0);
    const dlg = dlgs[dlgs.length - 1];
    if (!dlg) return false;
    const b = [...dlg.querySelectorAll('.el-button')]
      .find(x => x.textContent.replace(/\s+/g, '').includes(t));
    if (!b) return false;
    b.click();
    return true;
  }, text.replace(/\s+/g, ''));
}

/** 在"第一个可见的 containerSel"里点按钮（详情抽屉的审批动作区用）。
 *  审批动作区里同时有「驳回 / 加签 / 通过但补材料 / 通过审批」四个按钮，
 *  用 includes('通过') 会先命中「通过但补材料」，把补充材料弹窗当成了审批 ——
 *  所以优先精确匹配，匹配不到再退回包含匹配。 */
async function clickInVisible(page, containerSel, text) {
  return page.evaluate((sel, t) => {
    const root = [...document.querySelectorAll(sel)].filter(e => e.getClientRects().length > 0)[0];
    if (!root) return false;
    const norm = s => s.replace(/\s+/g, '');
    const btns = [...root.querySelectorAll('.el-button')];
    const b = btns.find(x => norm(x.textContent) === t) || btns.find(x => norm(x.textContent).includes(t));
    if (!b) return false;
    b.click();
    return true;
  }, containerSel, text.replace(/\s+/g, ''));
}

/** 列出审批动作区所有按钮文案（诊断用：点错按钮时能一眼看出来） */
async function actionButtons(page) {
  return page.evaluate(() => {
    const root = [...document.querySelectorAll('.approval-action')].filter(e => e.getClientRects().length > 0)[0];
    if (!root) return [];
    return [...root.querySelectorAll('.el-button')].map(b => b.textContent.replace(/\s+/g, '').trim());
  });
}

/** 点详情抽屉里的 tab（页面别处也有 tab，必须限定可见的） */
async function clickVisibleTab(page, text) {
  return page.evaluate((t) => {
    const tab = [...document.querySelectorAll('.el-tabs__item')]
      .filter(e => e.getClientRects().length > 0)
      .find(x => x.textContent.includes(t));
    if (!tab) return false;
    tab.click();
    return true;
  }, text);
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

/**
 * 往 Element Plus 的 el-upload 里塞文件。
 *
 * 两个坑：
 *  1. 组件把真实 input 藏起来（display:none）。用 JS 造 File 对象再派发 change 是没用的 ——
 *     那样绕过了 input，Vue 拿不到 raw File，上传时 FormData 里是空的。
 *     必须对 input 元素调用 uploadFile（CDP 直接塞文件，不需要元素可见）。
 *  2. 页面里同时存在多个容器（弹窗、抽屉），关掉的那些只是 display:none，
 *     querySelector 照样能选中，很容易把文件塞进一个看不见的控件里，
 *     然后对着"文件没出现在列表里"查半天。所以这里先按可见性过滤。
 */
async function uploadInto(page, containerSel, filePath, index) {
  const h = await page.evaluateHandle((sel, idx) => {
    const vis = e => e.getClientRects().length > 0;
    const roots = [...document.querySelectorAll(sel)].filter(vis);
    const root = (typeof idx === 'number') ? roots[idx] : roots[0];
    return root ? root.querySelector('input[type=file]') : null;
  }, containerSel, typeof index === 'number' ? index : null);
  const el = h.asElement();
  if (!el) return false;
  await el.uploadFile(filePath);
  await sleep(900);
  return true;
}

/**
 * 登录门禁：账号按钮上只渲染中文姓名，account 只用作 :key（不渲染到 textContent），
 * 所以必须按姓名匹配 —— 用 'huangxm' 找按钮永远找不到人（这一坑先踩过一次）。
 * 点账号按钮等价于点「登 录」：fillLogin 内部就是填账号密码 + doLogin。
 */
const ACCOUNT_NAME = {
  admin: '系统管理员', huangxm: '黄小明', linjl: '林经理', wangkj: '王会计',
  zhangzong: '张总', zhaocs: '赵出纳', chennk: '陈内控',
  zhouzh: '周综合', lifinance: '李财务'
};
async function loginViaGate(page, account) {
  const name = ACCOUNT_NAME[account] || account;
  const hasGate = await page.evaluate(() => !!document.querySelector('.lg-chips button'));
  if (!hasGate) {
    // 还留着上一次的登录态 → 清掉再刷新，回到门禁
    await page.evaluate(() => localStorage.clear());
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(2600);
  }
  // 门禁是 v-if="!me" 渲染的，刷新后要等它出来
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

/**
 * 在「我已发起的单据」/「待我审批」列表里按单据编号打开详情。
 * 必须用 doc_no 匹配而不是数字 id：演示库里单据已有 40+ 张，
 * 数字 id 会命中金额、日期等无关单元格，点到别的单上（假阴性极难查）。
 */
async function openDocByNo(page, docNo, preferButtonText) {
  return page.evaluate((no, pref) => {
    const trs = [...document.querySelectorAll('.el-table__body tbody tr')];
    const tr = trs.find(t => t.textContent.includes(no));
    if (!tr) return false;
    if (pref) {
      const b = [...tr.querySelectorAll('.el-button')].find(x => x.textContent.includes(pref));
      if (b) { b.click(); return true; }
    }
    tr.click();
    return true;
  }, docNo, preferButtonText || null);
}

/* ---------------- 造一个 1x1 PNG / 一个小 txt ---------------- */
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'oa-e2e-'));
const PNG_PATH = path.join(TMP, '申请资料样本.png');
const PNG2_PATH = path.join(TMP, '付款回单.png');
const PNG_BYTES = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
  'base64');
fs.writeFileSync(PNG_PATH, PNG_BYTES);
fs.writeFileSync(PNG2_PATH, PNG_BYTES);

/* ---------------- 演示状态快照 ----------------
   用 _hygiene.js 的统一版本：字段顺序与「污染探针」不变量只此一处定义，
   避免各脚本各抄一份导致字段漂移、断言静默跳过。
   探针含义（值得记住）：
     · ACT_RE_PROCDEF —— 用例若临时部署过流程，定义了却不清，演示流程列表就会被撑大
     · orphan_hi_inst —— 单据被删而流程实例还在（BUSINESS_KEY_ = doc_no，不是 document.id）
   这两个指标曾经真的抓到过漂移：某次跑完 ACT_HI_PROCINST 悄悄 32→33，
   而 before/after 都记 33、对比"一致"，只有独立查孤儿数才暴露出来。 */

/* 守护：E2E 不许往用户真实的 ~/Downloads 写文件（浏览器下载必须落在 TMP）。
   只统计本用例会产生的文件名模式，避免被用户自己的文件干扰。 */
const DL_DIR = path.join(os.homedir(), 'Downloads');
const DL_PAT = /^(申请资料样本|付款回单|用印文件).*\.png$|^未确认 .*\.crdownload$/;
function countDownloadsDir() {
  try { return fs.readdirSync(DL_DIR).filter(n => DL_PAT.test(n)).length; }
  catch (e) { return -1; }
}
const dlCountBefore = countDownloadsDir();

(async () => {
  const browser = await puppeteer.launch({
    executablePath: EXEC, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1680, height: 1050 });

  /* 把浏览器下载目录指到用例自己的临时目录。
     不指的话，点「下载」触发的真实浏览器下载会落到用户真实的 ~/Downloads，
     每跑一次就积一批 `申请资料样本 (N).png` / `未确认 *.crdownload` —— 跑 E2E 不该动用户的文件。
     指向 TMP 后，这些文件随 TMP 一起被删掉，且已有「临时文件清理」断言兜底。 */
  try {
    const cdp = await page.createCDPSession();
    try {
      await cdp.send('Browser.setDownloadBehavior',
        { behavior: 'allow', downloadPath: TMP, eventsEnabled: false });
    } catch (e) {
      await cdp.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: TMP });
    }
  } catch (e) {
    console.log('  ⚠ 未能重定向下载目录（' + e.message.split('\n')[0] + '），请检查 ~/Downloads 是否被写入');
  }

  // 记录应用侧真正收到的 Blob 大小（见下载断言的注释）
  await page.evaluateOnNewDocument(() => {
    window.__blobSizes = [];
    const orig = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function (b) {
      try { window.__blobSizes.push(b && typeof b.size === 'number' ? b.size : -1); } catch (e) {}
      return orig(b);
    };
  });

  const errs = [];
  const badResponses = [];
  page.on('console', m => { if (m.type() === 'error') errs.push('[console] ' + m.text().slice(0, 200)); });
  page.on('pageerror', e => errs.push('[pageerror] ' + e.message.slice(0, 200)));
  page.on('requestfailed', r => {
    if (!/favicon/.test(r.url())) errs.push('[reqfail] ' + r.method() + ' ' + r.url().slice(0, 100));
  });
  /* 控制台里 "Failed to load resource ... 403" 这条**不含 URL**，
     只靠文本过滤根本分不出是哪个接口 —— 必须另外记录响应状态与地址。 */
  page.on('response', r => {
    if (r.status() >= 400) badResponses.push(r.status() + ' ' + r.request().method() + ' ' + r.url());
  });

  let docId = null;            // 主用例单据
  let docNo = '';              // 主用例单据编号（列表里按它定位行）  let draftDocId = null;       // 「草稿可删附件」夹具
  let draftAttId = null;
  const before = snapshot('跑测试前');

  try {
    /* ================= 一、准备：夹具与登录 ================= */
    section('一、准备：夹具与登录');

    const huang = await login('huangxm');
    check('huangxm 登录（普通员工 / 数据范围仅本人）', !!huang.token);

    // 夹具：一张草稿 + 一个附件。界面上没有「保存草稿」按钮（那是另一个待办），
    // 所以草稿只能用接口造；后面 UI 验的是"删除入口".
    const created = await api('POST', '/api/documents', huang.token, {
      docTypeId: 1,
      formData: { title: 'E2E草稿夹具（跑完即删）', amount: 1234, reason: 'E2E 夹具' },
      title: 'E2E草稿夹具（跑完即删）', amount: 1234, reason: 'E2E 夹具', priority: 0
    });
    let draftDocNo = '';
    if (created.body.code === 0) {
      draftDocId = created.body.data.id;
      draftDocNo = created.body.data.docNo || '';
      const up = await apiUpload(huang.token, PNG_PATH, { documentId: draftDocId, bizType: 'apply' });
      if (up.body.code === 0) draftAttId = up.body.data.id;
    }
    check('草稿夹具（草稿 + 1 个附件）就绪', !!draftDocId && !!draftAttId,
      'docId=' + draftDocId + ' attId=' + draftAttId + ' docNo=' + draftDocNo);

    const resp = await page.goto(PAGE, { waitUntil: 'networkidle2', timeout: 40000 });
    check('页面 HTTP 200', resp && resp.status() === 200, 'status=' + (resp && resp.status()));
    await page.evaluate(() => localStorage.clear());
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(2600);

    const li = await loginViaGate(page, 'huangxm');
    check('登录门禁进入系统（huangxm / 普通员工 · 数据范围仅本人）', li.ok,
      li.ok ? li.who : li.reason);
    // 登录失败后面的用例全是假阴性，直接抛出去，别把 18 条连带失败当结论
    if (!li.ok) throw new Error('登录未成功，后续用例无意义：' + li.reason);

    /* ================= 二、草稿：附件可删 ================= */
    section('二、草稿单据：附件删除入口可用');

    await clickMenu(page, '工作台');
    await sleep(1400);

    // 工作台没有子 tab：「我已发起的单据」表格就在页面上，直接按单号找行就行
    const draftRowClicked = await openDocByNo(page, draftDocNo);
    await sleep(2400);
    check('从「我已发起的单据」打开草稿详情', draftRowClicked, 'docNo=' + draftDocNo);

    await clickVisibleTab(page, '附件资料');
    await sleep(1000);

    const draftFiles = await page.$$eval('.file-card', els => els.map(e => e.textContent.replace(/\s+/g, ' ').trim()));
    check('附件卡片渲染出夹具附件', draftFiles.length === 1,
      draftFiles[0] ? draftFiles[0].slice(0, 110) : '(空)');
    check('  卡片显示上传人（后端下发，不是前端拼的）',
      (draftFiles[0] || '').includes('黄小明'), (draftFiles[0] || '').slice(0, 90));
    check('  卡片显示可读大小（不是裸字节数）',
      /B|KB|MB/.test(draftFiles[0] || ''), (draftFiles[0] || '').slice(0, 90));

    const draftHasDelete = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.file-card')][0];
      return !!c && [...c.querySelectorAll('.el-button')].some(b => b.textContent.includes('删除'));
    });
    check('草稿状态给出「删除」入口', draftHasDelete);

    if (draftHasDelete) {
      await page.evaluate(() => {
        const c = [...document.querySelectorAll('.file-card')][0];
        const b = [...c.querySelectorAll('.el-button')].find(x => x.textContent.includes('删除'));
        if (b) b.click();
      });
      await sleep(2600);
      const afterDel = await page.$$eval('.file-card', els => els.length).catch(() => -1);
      const emptyShown = await page.evaluate(() =>
        !!document.querySelector('.el-tab-pane.el-empty, .el-empty'));
      check('删除后回到空态', afterDel === 0 || emptyShown,
        '剩余卡片=' + afterDel + '，出现空态=' + emptyShown);
      const goneInDb = sqlSafe('SELECT COUNT(*) FROM attachment WHERE id=' + draftAttId + ' AND deleted=0');
      check('  数据库里也确实删了（不是只藏了 UI）', goneInDb === '0', 'remaining=' + goneInDb);
    }
    await closeDialogs(page);

    /* ================= 三、发起端：提交弹窗上传附件 ================= */
    section('三、发起端：提交弹窗上传附件 → 提交审批');

    await clickMenu(page, '工作台');
    await sleep(1000);

    const opened = await clickButtonByText(page, '新建日常付款');
    await sleep(1600);
    check('工作台「新建日常付款」能打开提交弹窗', opened);

    const dlgLabels = await page.evaluate(() =>
      [...document.querySelectorAll('.el-dialog .el-form-item__label')]
        .map(e => e.textContent.trim().replace(/[:：]/g, '')));
    check('  弹窗含「附件与关联资料」区块',
      dlgLabels.some(l => l.includes('附件')), dlgLabels.join(' | ').slice(0, 150));

    const attachItems = await page.$$eval('.el-dialog .attachment-item', els =>
      els.map(e => e.textContent.replace(/\s+/g, ' ').trim().slice(0, 40)));
    check('  附件按单据类型的资料清单展开（不是固定一条）', attachItems.length >= 2,
      attachItems.join(' / ').slice(0, 140));

    // 第 1 项是「关联前置单据」（按钮），从第 2 项起才是上传
    const uploadSlots = await page.$$eval('.el-dialog .attachment-item', els =>
      els.map((e, i) => ({ i, input: !!e.querySelector('input[type=file]') })));
    const firstUploadIdx = (uploadSlots.find(s => s.input) || {}).i;
    check('  资料清单里存在真实的上传控件（input[type=file] 已接线）',
      firstUploadIdx !== undefined, '首个可上传项下标=' + firstUploadIdx);

    if (firstUploadIdx !== undefined) {
      const okUp = await uploadInto(page, '.el-dialog .attachment-item', PNG_PATH, firstUploadIdx);
      check('  选中文件成功（el-upload 的 on-change 收到 raw File）', okUp);
      const listText = await page.evaluate((idx) => {
        const vis = e => e.getClientRects().length > 0;
        const it = [...document.querySelectorAll('.el-dialog .attachment-item')].filter(vis)[idx];
        const l = it && it.querySelector('.el-upload-list');
        return l ? l.textContent.replace(/\s+/g, ' ').trim() : '';
      }, firstUploadIdx);
      check('  UI 上能看到已选文件名', listText.includes('申请资料样本'),
        listText.slice(0, 90) || '(列表为空)');
    }

    // 表单模板里 收款单位/收款账号/开户行 是后端强制必填，前端弹窗上对应「收款方名称/收款方账号/开户行」，
    // 不填就会被后端表单校验打回来（这一步是靠打印真实提示才发现的，别想当然只填"看得见"的字段）
    const proj = await pickProject(page, 'E2E附件链路验证单');
    const f2 = await fillInDialog(page, '申请金额（元）', '8000');
    const f3 = await fillInDialog(page, '申请事由', '验证附件上传/下载链路，跑完即清理');
    const f4 = await fillInDialog(page, '收款方名称', 'E2E测试收款单位');
    const f5 = await fillInDialog(page, '收款方账号', '6222020200112233');
    const f6 = await fillInDialog(page, '开户行', '中国工商银行');
    await sleep(400);
    check('  提交弹窗必填项都填上了', proj.ok && f2 && f3 && f4 && f5 && f6,
      '对应项目=' + JSON.stringify(proj.value) + ' 金额=' + f2 + ' 事由=' + f3
      + ' 收款单位=' + f4 + ' 账号=' + f5 + ' 开户行=' + f6);

    // 直接从创建接口的响应里拿 id / docNo：
    // 比事后用 SQL 按标题捞更准，也不会被"同名历史单据"干扰
    const createResp = page.waitForResponse(
      r => /\/api\/documents$/.test(r.url()) && r.request().method() === 'POST', { timeout: 20000 }
    ).catch(() => null);

    await clearToasts(page);
    const submitted = await clickInDialog(page, '提交审批');
    check('  点到了「提交审批」按钮', submitted);

    const cr = await createResp;
    const crJson = cr ? await cr.json().catch(() => null) : null;
    if (crJson && crJson.code === 0 && crJson.data) {
      docId = crJson.data.id;
      docNo = crJson.data.docNo || '';
    }

    const submitToast = await waitToast(page, 12000);
    check('提交成功（附件先落库、再走流程）',
      submitToast.includes('已提交审批'), submitToast.slice(0, 130));
    check('  单据已落库', !!docId && !!docNo, 'id=' + docId + ' docNo=' + docNo);
    if (!submitToast.includes('已提交审批')) {
      throw new Error('提交未成功：' + (submitToast || '(没有任何提示)'));
    }
    const savedTitle = docId ? sqlSafe(`SELECT title FROM document WHERE id=${docId}`) : '';
    check('  落库的申请事项就是界面上填的那个', savedTitle === 'E2E附件链路验证单',
      '库中 title=' + JSON.stringify(savedTitle));

    /* ================= 四、附件资料 Tab：下载 ================= */
    section('四、详情抽屉：附件真实可下载');

    await clickMenu(page, '工作台');
    await sleep(1400);

    const rowClicked = await openDocByNo(page, docNo);
    await sleep(2600);
    check('打开刚提交的单据详情', rowClicked, 'docNo=' + docNo);

    await clickVisibleTab(page, '附件资料');
    await sleep(1000);

    const tabLabel = await page.evaluate(() => {
      const t = [...document.querySelectorAll('.el-tabs__item')]
        .filter(e => e.getClientRects().length > 0)
        .find(x => x.textContent.includes('附件资料'));
      return t ? t.textContent.trim() : '';
    });
    check('「附件资料 (1)」数量来自后端真实附件', tabLabel.includes('(1)'), tabLabel);

    const files = await page.$$eval('.file-card', els => els.map(e => e.textContent.replace(/\s+/g, ' ').trim()));
    check('卡片展示文件名 / 业务类型 / 上传人 / 大小',
      files.length === 1 && files[0].includes('申请资料样本') && files[0].includes('申请资料')
      && files[0].includes('黄小明') && /B|KB/.test(files[0]),
      (files[0] || '').slice(0, 140));

    const noDeleteWhileRunning = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.file-card')][0];
      return !!c && ![...c.querySelectorAll('.el-button')].some(b => b.textContent.includes('删除'));
    });
    check('审批中的单据不提供删除入口（附件属留痕）', noDeleteWhileRunning);

    // 点下载：真发一次带 Authorization 的请求，比对字节
    const dlPromise = page.waitForResponse(
      r => /\/api\/attachments\/\d+\/download/.test(r.url()), { timeout: 15000 }
    ).catch(() => null);
    const dlClicked = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.file-card')][0];
      if (!c) return false;
      const b = [...c.querySelectorAll('.el-button')].find(x => x.textContent.includes('下载'));
      if (b) { b.click(); return true; }
      return false;
    });
    check('附件卡片上有「下载」按钮', dlClicked);
    const dl = await dlPromise;
    /* 用 puppeteer 的 response.buffer() 量下载字节量到的是 0 ——
       因为应用把 blob 交给 <a download> 触发保存后，这次 fetch 的响应体在页面侧已经被消费掉，
       从协议层再读就是空的，会误判成"下载了空文件"。
       改成在页面里给 URL.createObjectURL 打桩，直接记录应用真实拿到的 Blob 大小。 */
    const blobSizes = await page.evaluate(() => window.__blobSizes || []).catch(() => []);
    const dlLen = blobSizes.length ? blobSizes[blobSizes.length - 1] : -1;
    check('点「下载」真的发起了下载请求且成功',
      !!dl && dl.status() === 200, dl ? 'HTTP ' + dl.status() : '(没抓到请求)');
    check('  应用真实拿到的字节与上传一致（不是空文件/错误页）',
      dlLen === PNG_BYTES.length,
      '应用收到 ' + dlLen + ' 字节 / 上传 ' + PNG_BYTES.length + ' 字节；历史记录=' + JSON.stringify(blobSizes));

    await closeDialogs(page);

    /* ================= 五、推进到办理节点 ================= */
    section('五、把单据推进到办理节点（出纳付款）');

    const HOP = ['linjl', 'wangkj', 'zhangzong', 'chennk', 'zhouzh', 'lifinance'];
    const sessions = {};
    for (const a of HOP) sessions[a] = await login(a);
    const zhao = await login('zhaocs');

    let reachedHandle = false;
    for (let round = 0; round < 6 && !reachedHandle; round++) {
      let advanced = false;
      for (const a of HOP) {
        const t = await api('GET', '/api/todos?limit=200', sessions[a].token);
        const row = (t.body.data || []).find(x => String(x.documentId) === String(docId)
          || String(x.id) === String(docId) || String(x.docId) === String(docId));
        if (!row) continue;
        const r = await api('POST', '/api/todos/approve', sessions[a].token,
          { taskId: row.taskId, action: 'approve', comment: 'E2E 前置节点自动通过' });
        console.log('    · ' + a + ' 处理「' + (row.nodeName || row.node || '?') + '」→ ' + (r.body.msg || r.body.code));
        advanced = true;
      }
      const zt = await api('GET', '/api/todos?limit=200', zhao.token);
      const zRow = (zt.body.data || []).find(x => String(x.documentId) === String(docId)
        || String(x.id) === String(docId) || String(x.docId) === String(docId));
      if (zRow) {
        reachedHandle = true;
        console.log('    · 已到办理节点：「' + (zRow.nodeName || '?') + '」requireAttachment=' + zRow.requireAttachment);
      }
      if (!advanced && !reachedHandle) break;
    }
    check('单据推进到出纳付款（办理节点，凭证必填）', reachedHandle);

    /* ================= 六、办理节点：凭证必填 ================= */
    section('六、办理节点：凭证必填在界面上的真实表现');

    // 换账号：清 token + 刷新，回到登录门禁
    await page.evaluate(() => localStorage.clear());
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(2600);
    const lz = await loginViaGate(page, 'zhaocs');
    check('切换到出纳 zhaocs', lz.ok, lz.ok ? lz.who : lz.reason);
    if (!lz.ok) throw new Error('切换账号未成功：' + lz.reason);

    // 侧边菜单文案被前面的补丁改成了「待我审批」（原型期叫「审批中心」），两个都试
    const menuHit = await clickMenu(page, ['待我审批', '审批中心']);
    check('进入待办列表（菜单入口）', !!menuHit, '点中的菜单=' + menuHit);

    const todoRowClicked = await openDocByNo(page, docNo, '进入审批');
    await sleep(2600);
    check('审批中心能打开该单（待办里有它）', todoRowClicked, 'docNo=' + docNo);

    const actionBox = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.approval-action')].filter(e => e.getClientRects().length > 0)[0];
      if (!b) return { present: false };
      return {
        present: true,
        text: b.textContent.replace(/\s+/g, ' ').trim().slice(0, 200),
        hasUpload: !!b.querySelector('.el-upload'),
        hasFileInput: !!b.querySelector('input[type=file]')
      };
    });
    check('审批动作区渲染出来', actionBox.present, (actionBox.text || '').slice(0, 90));
    // —— 这就是被历史补丁吃掉过的那个控件 ——
    check('  审批动作区里有真实上传控件（历史补丁没吃掉它）', actionBox.hasUpload,
      'el-upload=' + actionBox.hasUpload + ' input[type=file]=' + actionBox.hasFileInput);
    check('  按钮文案带「（必填）」（由后端 requireAttachment 驱动）',
      (actionBox.text || '').includes('（必填）'), (actionBox.text || '').slice(0, 90));
    check('  给出不可通过的说明文案',
      (actionBox.text || '').includes('必须上传凭证') || (actionBox.text || '').includes('否则无法通过'),
      (actionBox.text || '').slice(0, 90));

    // 不带凭证点通过 → 必须被拦（本地预检 + 后端权威双重）
    const btns = await actionButtons(page);
    check('  审批动作区按钮清单（诊断）', btns.length >= 2, btns.join(' / '));

    await clearToasts(page);
    const clickedApprove = await clickInVisible(page, '.approval-action', '通过审批');
    check('  点到了审批动作区的「通过审批」', clickedApprove);
    const blockedToast = await waitToast(page, 6000);
    check('不带凭证点「通过」被拦下', /需要上传.*凭证|办理凭证/.test(blockedToast),
      blockedToast.slice(0, 130));

    const stillOpen = await page.evaluate(() => {
      const d = [...document.querySelectorAll('.el-drawer, .el-dialog')].filter(e => e.getClientRects().length > 0)[0];
      return !!d;
    });
    check('  被拦后详情仍在（流程没有被推进）', stillOpen);

    // 补传凭证
    const voucherOk = await uploadInto(page, '.approval-action', PNG2_PATH);
    check('在审批动作区补传付款回单', voucherOk);
    const voucherName = await page.evaluate(() => {
      const l = document.querySelector('.approval-action .el-upload-list');
      return l ? l.textContent.replace(/\s+/g, ' ').trim() : '';
    });
    check('  UI 上能看到已选凭证文件', voucherName.includes('付款回单'), voucherName.slice(0, 90) || '(空)');

    await clearToasts(page);
    const clickedApprove2 = await clickInVisible(page, '.approval-action', '通过审批');
    check('  补传后再点「通过审批」', clickedApprove2);
    const okToast = await waitToast(page, 12000);
    check('补传凭证后「通过」成功', okToast.includes('审批已通过'), okToast.slice(0, 120));

    await closeDialogs(page);

    /* ================= 七、落库复核 ================= */
    section('七、落库复核（不只看界面，也看数据）');

    const attRows = sqlSafe(
      `SELECT biz_type, IFNULL(node_key,'-') FROM attachment WHERE document_id=${docId} AND deleted=0 ORDER BY id`);
    console.log('    附件记录（biz_type / node_key）：\n' + attRows.split('\n').map(s => '      ' + s).join('\n'));
    check('申请资料 + 办理凭证都落到了同一张单据上',
      attRows.includes('apply') && attRows.includes('receipt'), attRows.replace(/\n/g, ' ; '));
    check('  办理凭证绑定了流程节点（不是只挂在单据上）',
      /receipt\s+\S+/.test(attRows) && !/receipt\s+-/.test(attRows), attRows.replace(/\n/g, ' ; '));

    /* document.status 语义（DocumentService）：0 草稿 / 1 待审 / 2 审批中 / 3 已通过(办结) /
       4 驳回 / 5 撤回 / 6 归档。出纳付款是末节点，通过后整条流程走完 → 3。
       （这里曾把 2 当成「办结」，2 其实是「审批中」，断言常量写错。） */
    const docState = sqlSafe(`SELECT status FROM document WHERE id=${docId}`);
    check('单据已办结（status=3 已通过）', docState === '3', 'status=' + docState);

    const diskLeft = sqlSafe(`SELECT COUNT(*) FROM attachment WHERE document_id=${docId} AND deleted=0`);

    /* ================= 八、控制台 ================= */
    section('八、控制台');
    /* 已知噪音（与本次改动无关，单独列出、不计失败）：
       非管理员登录后页面仍会去拉 GET /api/permissions（权限点目录，仅管理员可读）→ 403。
       它会让控制台常挂一条红字，但功能不受影响。 */
    const knownNoise = b => /\/api\/permissions/.test(b);
    const noise = badResponses.filter(knownNoise);
    const unexpected = badResponses.filter(b => !knownNoise(b));
    const pageErrs = errs.filter(e => /pageerror|reqfail/.test(e));
    check('无未预期的失败请求 / JS 异常', unexpected.length === 0 && pageErrs.length === 0,
      (unexpected.concat(pageErrs)).slice(0, 3).join(' | ') || '(干净)');
    console.log('    · 既有噪音 ' + noise.length + ' 条：' + (noise[0] || '(无)').slice(0, 110));

  } catch (e) {
    check('用例执行未抛异常', false, e.message);
    console.error(e);
  } finally {
    await browser.close().catch(() => {});

    /* ================= 九、清理 ================= */
    section('九、清理测试数据（演示库不能被跑测试弄脏）');

    if (docId) {
      /* 流程实例在 ACT_HI_PROCINST 里是用 BUSINESS_KEY_ = 单据编号 关联的（不是 document.id），
         列名是 PROC_INST_ID_。ACT_HI_ENTITYLINK 没有 PROC_INST_ID_ 列，别一起循环。 */
      const procInsts = sqlSafe(
        `SELECT p.PROC_INST_ID_ FROM ACT_HI_PROCINST p WHERE p.BUSINESS_KEY_='${docNo}'`);
      const pids = (procInsts || '').split('\n').map(s => s.trim()).filter(s => /^[0-9a-fA-F-]{8,}$/.test(s));
      // Flowable 历史：单据行删掉后这些记录就成了不可达的孤儿数据，一并清干净
      for (const t of ['ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
                       'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG']) {
        for (const pid of pids) sqlSafe(`DELETE FROM ${t} WHERE PROC_INST_ID_='${pid}'`);
      }
      /* 运行时表也要清，而且这一步是"守住演示待办数"的关键：
         用例正常跑完时单据确实办结了（status=3），Flowable 会自己收掉 ACT_RU_*；
         但只要用例中途失败（比如卡在出纳付款没点通过），实例就还"活着"——这时
         只删业务表和 ACT_HI_* 的话，ACT_RU_TASK 会留下一条，演示待办数被悄悄 +1
         （第一次跑完 5→6 就是这么来的，靠快照对比才抓到）。所以按 PROC_INST_ID_
         无条件清一遍，删 0 行也无害。ACT_RU_ENTITYLINK 没有 PROC_INST_ID_ 列，不能一起循环。 */
      for (const t of ['ACT_RU_IDENTITYLINK', 'ACT_RU_ACTINST', 'ACT_RU_TASK',
                       'ACT_RU_VARIABLE', 'ACT_RU_EVENT_SUBSCR']) {
        for (const pid of pids) sqlSafe(`DELETE FROM ${t} WHERE PROC_INST_ID_='${pid}'`);
      }
      // 执行树是自引用的：先删子节点再删父节点，否则外键会挡
      for (const pid of pids) sqlSafe(`DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${pid}' AND PARENT_ID_ IS NOT NULL`);
      for (const pid of pids) sqlSafe(`DELETE FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${pid}'`);
      for (const pid of pids) sqlSafe(`DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${pid}'`);
      // 业务表
      sqlSafe(`DELETE FROM attachment WHERE document_id=${docId}`);
      sqlSafe(`DELETE FROM document_link WHERE document_id=${docId}`);
      sqlSafe(`DELETE FROM flow_instance_node WHERE document_id=${docId}`);
      sqlSafe(`DELETE FROM flow_instance WHERE document_id=${docId}`);
      // 通知也要清：每次审批动作后端都会给发起人写一条通知。单据删了通知不删，
      // 就会变成"指向已删单据的孤儿通知"——前端现在有未读角标，直接就是幽灵数字。
      sqlSafe(`DELETE FROM notification WHERE biz_type='document' AND biz_id=${docId}`);
      sqlSafe(`DELETE FROM document WHERE id=${docId}`);
      console.log('  已清理主用例单据 #' + docId + '（' + docNo + '）'
        + (pids.length ? '，含 ' + pids.length + ' 个流程实例历史' : ''));
    }
    if (draftDocId) {
      sqlSafe(`DELETE FROM attachment WHERE document_id=${draftDocId}`);
      sqlSafe(`DELETE FROM flow_instance_node WHERE document_id=${draftDocId}`);
      sqlSafe(`DELETE FROM flow_instance WHERE document_id=${draftDocId}`);
      sqlSafe(`DELETE FROM notification WHERE biz_type='document' AND biz_id=${draftDocId}`);
      sqlSafe(`DELETE FROM document WHERE id=${draftDocId}`);
      console.log('  已清理草稿夹具 #' + draftDocId);
    }
    // 落盘文件：按字符截断的 file_key 无法可靠反查，直接按目录扫本次产生的文件更稳
    try {
      const root = path.resolve(__dirname, '../../../oa-backend/data/attachments');
      const seq = { n: 0 };
      (function walk(dir) {
        if (!fs.existsSync(dir)) return;
        for (const f of fs.readdirSync(dir)) {
          const p = path.join(dir, f);
          const st = fs.statSync(p);
          if (st.isDirectory()) { walk(p); continue; }
          if (st.size === PNG_BYTES.length && st.mtimeMs > Date.now() - 30 * 60 * 1000) { fs.unlinkSync(p); seq.n++; }
        }
      })(root);
      console.log('  从存储目录清掉 ' + seq.n + ' 个本次产生的 1x1 PNG（按大小+时间匹配，不碰其它文件）');
    } catch (e) { console.log('  (存储目录清理跳过：' + e.message + ')'); }

    const after = snapshot('清理后');
    const clean = after === before;
    check('演示状态快照与跑测试前完全一致', clean, clean ? '一致' : ('前 ' + before + ' | 后 ' + after));

    /* 绝对不变量（比 before/after 对比更强）：
       对比只能证明"本次没新增污染"——如果基线本身就是脏的，before=after=脏 也会判"一致"。
       下面几条对"必须为 0 / 必须相等"的探针做绝对断言，这样才能抓到历史遗留的霉点。
       实现统一在 _hygiene.js，避免各脚本抄出不同版本。 */
    assertInvariants(after, check);

    const tmpCleaned = (() => { try { fs.rmSync(TMP, { recursive: true, force: true }); return true; } catch (e) { return false; } })();
    check('临时文件清理', tmpCleaned);
    const dlAfter = countDownloadsDir();
    check('未往用户 ~/Downloads 写测试文件（下载已重定向到临时目录）',
      dlAfter === dlCountBefore, '跑前=' + dlCountBefore + ' 跑后=' + dlAfter);

    const pass = results.filter(r => r.ok).length;
    const total = results.length;
    // 自证闸门：条数必须与预期一致（少跑 = 有断言被删除、注释，或中断后静默跳过）
    const countOk = total === EXPECTED_TOTAL;
    console.log('\n' + '='.repeat(74));
    console.log('  附件链路 UI 验证：' + pass + '/' + total + ' 通过（预期 ' + EXPECTED_TOTAL + ' 条）');
    if (pass !== total) {
      console.log('  失败项：');
      results.filter(r => !r.ok).forEach(r => console.log('    - ' + r.name));
    }
    if (!countOk) {
      console.log('  ❌ 断言条数异常：实际 ' + total + ' 条，预期 ' + EXPECTED_TOTAL + ' 条');
      console.log('     条数变少通常意味着上游抛错后，其后断言被静默跳过（假绿灯）。');
    }
    console.log('='.repeat(74));
    process.exit((pass === total && countOk) ? 0 : 1);
  }
})();

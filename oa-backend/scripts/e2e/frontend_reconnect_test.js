/* 验证：① 后端不可达时的提示与重试按钮；② 后端恢复后自动重连；③ 正常场景 file:// 直开可登录
 *
 * 前置条件（重要）：C 段用 file:// 打开页面直连后端，属于跨源请求。
 * 后端默认只放行本机 http 源（安全默认），跑 C 段前请用放行模式启动：
 *     OA_CORS_PERMISSIVE=true NO_OPEN=1 bash 启动联调版.command
 * 否则 C 段会因为浏览器拦跨域而失败 —— 那是**预期行为**，不是缺陷（官方推荐走同源地址
 * http://127.0.0.1:8080/oa.html，同源不触发 CORS 校验）。A/B 段不受影响。
 *
 * 探测端点：登录页用 /api/ping 判断连通性（早前是 /v3/api-docs，该端点会暴露完整接口契约，已关闭）。 */
const puppeteer = require('puppeteer-core');
const http = require('http');
const { pathToFileURL } = require('url');
const EXEC = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FILE = '/Users/zhouzewei/WorkBuddy/2026-09-18-15-53-12/海峡金OA审批系统-联调版.html';
const FAKE = 8099;
const sleep = ms => new Promise(r => setTimeout(r, ms));

let fake = null;
const startFake = () => new Promise(res => {
  fake = http.createServer((q, s) => {
    s.writeHead(200, {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*'
    });
    s.end('{}');
  });
  fake.listen(FAKE, '127.0.0.1', res);
});
const stopFake = () => new Promise(res => fake ? fake.close(res) : res());

let pass = 0, fail = 0;
const check = (name, ok, extra) => { (ok ? pass++ : fail++); console.log((ok ? '  ✅ ' : '  ❌ ') + name + (extra ? '  [' + extra + ']' : '')); };

(async () => {
  const b = await puppeteer.launch({ executablePath: EXEC, headless: true, args: ['--no-sandbox'] });

  /* ---------- A/B：后端不可达 → 恢复 ---------- */
  console.log('\n=== A. 后端不可达（模拟你遇到的情况） ===');
  const p = await b.newPage(); await p.setViewport({ width: 1400, height: 950 });
  let probes = 0;
  p.on('request', r => { if (r.url().indexOf(':' + FAKE) >= 0 && r.url().indexOf('/api/ping') >= 0) probes++; });
  await p.evaluateOnNewDocument(() => { window.HXJ_API_BASE = 'http://127.0.0.1:8099'; });
  await p.goto(pathToFileURL(FILE).href, { waitUntil: 'domcontentloaded' });
  await sleep(1500);

  const s1 = await p.evaluate(() => ({
    status: (document.querySelector('.lg-backend') || {}).innerText || '',
    retry: !!(document.querySelector('.lg-retry')),
    retryText: (document.querySelector('.lg-retry') || {}).textContent || ''
  }));
  check('提示「未连接」', s1.status.indexOf('未连接') >= 0, s1.status.replace(/\s+/g, ' '));
  check('出现「重试连接」按钮', s1.retry && s1.retryText.indexOf('重试') >= 0, s1.retryText);

  await p.evaluate(() => {
    const bs = [...document.querySelectorAll('.lg-card .el-button')];
    const btn = bs[bs.length - 1] || bs[0]; if (btn) btn.click();
  });
  await sleep(1500);
  const alert = await p.evaluate(() => {
    const a = document.querySelector('.lg-card .el-alert__title'); return a ? a.textContent.trim() : '';
  });
  check('登录失败有明确报错', alert.indexOf('连不上后端') >= 0, alert.slice(0, 70));

  const before = probes;
  await sleep(7000);
  check('未连接时每 3s 自动重试', probes - before >= 2, '7s 内探测 ' + (probes - before) + ' 次');

  console.log('\n=== B. 后端恢复 → 自动重连、报错自动清除 ===');
  await startFake();
  await sleep(4500);
  const s2 = await p.evaluate(() => ({
    status: (document.querySelector('.lg-backend') || {}).innerText || '',
    alert: (document.querySelector('.lg-card .el-alert__title') || {}).textContent || '',
    retryText: (document.querySelector('.lg-retry') || {}).textContent || ''
  }));
  check('状态自动变为「已连接」', s2.status.indexOf('已连接') >= 0, s2.status.replace(/\s+/g, ' '));
  check('过期报错自动清除', s2.alert === '', 'alert="' + s2.alert + '"');
  await stopFake();

  /* ---------- C：正常场景（file:// 直开 + 真实后端） ---------- */
  console.log('\n=== C. 正常场景：file:// 直开走真实后端 ===');
  const p2 = await b.newPage(); await p2.setViewport({ width: 1680, height: 1050 });
  const errs = [];
  p2.on('pageerror', e => errs.push(e.message));
  p2.on('response', r => { if (r.url().indexOf('8080') >= 0 && r.status() >= 400) errs.push(r.status() + ' ' + r.url()); });
  await p2.goto(pathToFileURL(FILE).href, { waitUntil: 'networkidle2' });
  await sleep(2500);
  const s3 = await p2.evaluate(() => (document.querySelector('.lg-backend') || {}).innerText || '');
  check('页面已连接后端', s3.indexOf('已连接') >= 0, s3.replace(/\s+/g, ' '));

  await p2.evaluate(() => {
    const inp = document.querySelectorAll('.lg-card .el-input__inner');
    const set = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); };
    if (inp[0]) set(inp[0], 'admin');
    if (inp[1]) set(inp[1], '123456');
  });
  await sleep(400);
  await p2.evaluate(() => {
    const bs = [...document.querySelectorAll('.lg-card .el-button')];
    const btn = bs[bs.length - 1] || bs[0]; if (btn) btn.click();
  });
  await sleep(4000);
  const home = await p2.evaluate(() => ({
    gateGone: !document.querySelector('.login-gate') || getComputedStyle(document.querySelector('.login-gate')).display === 'none',
    who: (document.querySelector('.topbar .who') || document.querySelector('.who') || {}).textContent || '',
    rows: document.querySelectorAll('.el-table__body tbody tr').length,
    hero: (document.querySelector('.hero') || {}).innerText || ''
  }));
  check('登录成功、门禁关闭', home.gateGone, 'who=' + home.who.trim());
  check('顶栏显示真实登录人', /系统管理员|admin/i.test(home.who), home.who.trim().slice(0, 40));
  check('页面无 JS 报错 / 4xx', errs.length === 0, errs.slice(0, 2).join(' | ') || '无');

  await p2.screenshot({ path: '/tmp/proto/shot-after-login.png' });
  await b.close();
  console.log('\n==============================================');
  console.log('  结果：' + pass + ' 通过 / ' + fail + ' 失败');
  console.log('==============================================');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('ERR', e.message); process.exit(1); });

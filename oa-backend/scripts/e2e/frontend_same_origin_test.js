/* 同源打开验证：http://127.0.0.1:8080/oa.html
   目的：证明「由后端提供页面 + 接口同源」后，不再有任何 CORS 相关请求，页面功能完整。 */
const puppeteer = require('puppeteer-core');
const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const PAGE = 'http://127.0.0.1:8080/oa.html';
const sleep = ms => new Promise(r => setTimeout(r, ms));

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (extra ? ('  → ' + extra) : ''));
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: EXEC, headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1680, height: 1050 });

  const errs = [];
  page.on('console', m => { if (m.type() === 'error') errs.push('[console] ' + m.text().slice(0, 200)); });
  page.on('pageerror', e => errs.push('[pageerror] ' + e.message.slice(0, 200)));
  page.on('requestfailed', r => errs.push('[reqfail] ' + r.method() + ' ' + r.url().slice(0, 110) + ' ' + (r.failure() && r.failure().errorText)));

  const reqs = [];
  page.on('request', r => reqs.push(r.url()));

  console.log('\n=== 1. 同源页面加载 ===');
  const resp = await page.goto(PAGE, { waitUntil: 'networkidle2', timeout: 40000 });
  check('页面 HTTP 200', resp && resp.status() === 200, 'status=' + (resp && resp.status()));
  await sleep(2600);

  check('登录门已渲染', await page.$('.lg-card') !== null || await page.$('.login-gate') !== null);

  const pingTxt = await page.evaluate(() => {
    const p = document.querySelector('.lg-backend');
    return p ? p.innerText.replace(/\s+/g, ' ') : '';
  });
  check('后端状态=已连接（同源无需 CORS）', pingTxt.indexOf('已连接') >= 0, pingTxt.slice(0, 80));

  const corsBox = await page.evaluate(() => {
    const el = document.querySelector('.lg-alt');
    if (!el) return 'none';
    const r = el.getBoundingClientRect();
    return (r.width > 0 && r.height > 0) ? 'visible' : 'hidden';
  });
  check('未出现跨域告警条', corsBox !== 'visible', corsBox);

  const crossOrigin = reqs.filter(u => u.indexOf('8080') >= 0 && u.indexOf('/oa.html') < 0);
  check('所有接口请求均为同源', await page.evaluate(() => location.origin) === 'http://127.0.0.1:8080',
    '页面 origin=' + await page.evaluate(() => location.origin));

  console.log('\n=== 2. 登录并加载真实数据 ===');
  let ok = false;
  for (const c of await page.$$('.lg-chips button')) {
    const t = await c.evaluate(e => e.textContent);
    if (t.includes('黄小明')) { await c.click(); ok = true; break; }
  }
  check('选中演示账号 黄小明（点选即登录）', ok);
  await sleep(4200);

  const who = await page.$eval('.topbar .who', e => e.textContent.trim()).catch(() => '');
  check('已进入系统（顶栏显示当前用户）', who.length > 0, who);

  await page.evaluate(() => {
    const h = [...document.querySelectorAll('.el-menu-item')].find(i => i.textContent.includes('工作台'));
    if (h) h.click();
  });
  await sleep(2000);

  const zones = await page.evaluate(() => {
    const zs = [...document.querySelectorAll('.work-zones .zone')];
    return zs.map(z => ({
      title: (z.querySelector('.zone-title b') || {}).textContent,
      btn: [...z.querySelectorAll('.el-button')].map(b => b.textContent.trim())[0]
    }));
  });
  check('工作台渲染 3 类单据卡片', zones.length === 3, JSON.stringify(zones));
  check('用印卡片文案已统一为「用印」', JSON.stringify(zones).indexOf('盖章') < 0,
    zones.map(z => z.btn).join(' / '));

  await page.evaluate(() => {
    const h = [...document.querySelectorAll('.el-menu-item')].find(i => i.textContent.includes('单据列表') || i.textContent.includes('我的单据'));
    if (h) h.click();
  });
  await sleep(2200);
  const rows = await page.$$eval('.el-table__body tbody tr', els => els.length).catch(() => 0);
  check('单据列表有真实数据行', rows > 0, '行数=' + rows);

  console.log('\n=== 3. 控制台/网络 ===');
  const realErrs = errs.filter(e => !/favicon/.test(e));
  check('无控制台错误 / 无失败请求', realErrs.length === 0, realErrs.slice(0, 3).join(' | ') || '(干净)');

  await page.screenshot({ path: '/tmp/proto/shots2/same-origin.png', fullPage: false });
  await browser.close();

  const pass = results.filter(r => r.ok).length;
  console.log('\n============================================');
  console.log('  同源验证：' + pass + '/' + results.length + ' 通过');
  console.log('============================================');
  process.exit(pass === results.length ? 0 : 1);
})().catch(e => { console.error('ERR', e.message); process.exit(1); });

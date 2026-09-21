const puppeteer = require('puppeteer-core');
const {pathToFileURL} = require('url');
const EXEC = '/Users/zhouzewei/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing';
const FILE = '/Users/zhouzewei/WorkBuddy/2026-09-18-15-53-12/海峡金OA审批系统-联调版.html';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const out = [];
const ck = (n, ok, ex) => { out.push(ok); console.log((ok ? '  ✓ ' : '  ✗ ') + n + (ex ? '  → ' + ex : '')); };
(async () => {
  const b = await puppeteer.launch({executablePath: EXEC, headless: true, args: ['--no-sandbox']});
  const p = await b.newPage(); await p.setViewport({width: 1680, height: 1050});
  const errs = [];
  p.on('pageerror', e => errs.push(e.message.slice(0,120)));
  await p.goto(pathToFileURL(FILE).href, {waitUntil: 'networkidle2'});
  await sleep(2600);
  ck('file:// 页面可加载', (await p.$('.lg-card')) !== null);
  const t = await p.evaluate(() => (document.querySelector('.lg-backend')||{}).innerText || '');
  ck('后端状态=已连接（file:// 下 CORS 正常）', t.indexOf('已连接') >= 0, t.replace(/\s+/g,' ').slice(0,70));
  for (const c of await p.$$('.lg-chips button')) { const x = await c.evaluate(e=>e.textContent); if (x.includes('黄小明')) { await c.click(); break; } }
  await sleep(4200);
  const who = await p.$eval('.topbar .who', e => e.textContent.trim()).catch(()=> '');
  ck('file:// 下登录并加载真实数据', who.length > 0, who);
  ck('无页面脚本错误', errs.length === 0, errs.join(' | ') || '(干净)');
  await p.screenshot({path: '/tmp/proto/shots2/file-mode.png'});
  await b.close();
  console.log('  file:// 通过 ' + out.filter(Boolean).length + '/' + out.length);
  process.exit(out.every(Boolean) ? 0 : 1);
})().catch(e => { console.error('ERR', e.message); process.exit(1); });

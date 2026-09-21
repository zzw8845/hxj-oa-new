# -*- coding: utf-8 -*-
"""
接口级验证：登出**真的**吊销 token。

关注点不是"登出返回 200"，而是这三件容易被糊弄过去的事：
  1. 登出后，那张 token 立刻不能再用（无状态 JWT 在到期前本来一直有效）；
  2. 登出后**重新登录**拿到的新 token 必须可用
     —— 若实现成"按 userId 拉黑"，这里会变成"登出后再也登不进来"；
  3. 作废的只是被登出的那一张，别的会话不受影响
     —— 用两个账号各登一次，登出 A 不应影响 B。

注意本地是进程内实现（oa.redis.enabled=false），单实例；
线上是 Redis 实现，蓝绿两实例共享黑名单，行为应一致。
"""
import json
import os
import urllib.error
import urllib.request

os.environ['no_proxy'] = '127.0.0.1,localhost,::1'
os.environ['NO_PROXY'] = os.environ['no_proxy']
B = 'http://127.0.0.1:8080'

EXPECTED_TOTAL = 11

PASS, FAIL = [], []


def call(method, path, token=None, body=None):
    req = urllib.request.Request(B + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            raw = r.read().decode('utf-8')
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, (json.loads(raw) if raw else {})
        except Exception:
            return e.code, {'raw': raw}
    except urllib.error.URLError as e:
        return 0, {'raw': str(e.reason)}


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print('%s %s%s' % ('[PASS]' if cond else '[FAIL]', name, ('  -> ' + detail) if detail else ''))


def login(account):
    st, r = call('POST', '/api/auth/login', body={'account': account, 'password': '123456'})
    return (r.get('data') or {}).get('token') if st == 200 and r.get('code') == 0 else None


def usable(token):
    st, _ = call('GET', '/api/auth/me', token=token)
    return st == 200


print('=' * 72)
print('登出吊销验证')
print('=' * 72)

# ---- 一、基本链路 ----
tk1 = login('admin')
check('admin 登录成功', bool(tk1))
check('登录后的 token 可用（/api/auth/me）', usable(tk1))

st, r = call('POST', '/api/auth/logout', token=tk1)
check('登出接口返回成功', st == 200 and r.get('code') == 0, 'HTTP %d / %s' % (st, r.get('msg', '')))

check('★ 登出后旧 token 立刻不可用', not usable(tk1))

st2, r2 = call('GET', '/api/auth/me', token=tk1)
detail = (r2.get('msg') or '')[:40]
check('★ 且提示是「已退出」而不是泛泛的「未登录」',
      st2 == 401 and ('退出' in detail or '登出' in detail or '已登出' in detail),
      'HTTP %d / %s' % (st2, detail))

# ---- 二、重新登录必须还能登进来（防"按用户拉黑"实现）----
tk2 = login('admin')
check('★ 登出后重新登录成功（新 token 可用）', bool(tk2) and usable(tk2))

# ---- 三、只作废被登出的那一张 ----
tkB = login('huangxm')
check('另一个账号（huangxm）登录成功', bool(tkB))
call('POST', '/api/auth/logout', token=tk2)
check('★ 登出 A 之后，A 的新 token 不可用', not usable(tk2))
check('★ 登出 A 之后，B 的 token 仍然可用（只作废被登出的那一张）', usable(tkB))

# ---- 四、边界 ----
st_n, _ = call('POST', '/api/auth/logout')
check('不带 token 调登出不报错（登出是"尽力作废"）', st_n in (200, 401), 'HTTP %d' % st_n)

tk3 = login('admin')
call('POST', '/api/auth/logout', token=tk3)
check('★ 重复登出同一张 token 仍是已登出状态', not usable(tk3))

print()
print('=' * 72)
total = len(PASS) + len(FAIL)
print('结果：通过 %d 项，失败 %d 项（断言总数 %d，预期 %d）'
      % (len(PASS), len(FAIL), total, EXPECTED_TOTAL))
if total != EXPECTED_TOTAL:
    print('✗ 断言条数与预期不符 —— 当事故查')
if FAIL:
    print('失败清单：')
    for f in FAIL:
        print('  - ' + f)
print('=' * 72)
raise SystemExit(1 if (FAIL or total != EXPECTED_TOTAL) else 0)

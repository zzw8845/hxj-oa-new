# -*- coding: utf-8 -*-
"""
附件接口验证。

这一轮补的是「三处 el-upload 纯摆设 + 后端根本没有上传接口」的 P0 欠账，
所以验证重点有三类：

1. **真的能用**：上传后文件确实落盘、下载回来的字节与上传的一致、预览类型正确。
2. **拦住不该进来的**：非白名单扩展名、超大文件、空文件、未知业务类型。
3. **拦住不该看到的**：越权下载 403；以及一条「存储键被写成 ../../etc/passwd」的
   脏数据 —— 这是本地存储实现里最容易漏掉的任意文件读取漏洞。
4. **凭证必填是服务端权威判定**：绕过界面直接调审批接口也必须被拦住。

临时单据类型 / 临时流程 / 临时单据全部用完即清，演示数据全程不被触碰。
用法：python3 scripts/verify_attachment_api.py
"""
import base64
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

os.environ['no_proxy'] = '127.0.0.1,localhost,::1'
os.environ['NO_PROXY'] = os.environ['no_proxy']
B = 'http://127.0.0.1:8080'
DB = 'haixiajin_oa'

PASS, FAIL = [], []
TMP_CODE = 'ATT_TMP_TYPE'

# 1x1 透明 PNG
PNG = base64.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==')


# --------------------------------------------------------------------- 基础设施
def sql(stmt):
    return subprocess.run(['mysql', '-uroot', DB, '-e', stmt],
                          capture_output=True, text=True)


def sql_scalar(stmt):
    r = subprocess.run(['mysql', '-uroot', DB, '-N', '-e', stmt],
                       capture_output=True, text=True)
    return (r.stdout.strip().splitlines() or [''])[0]


def call(method, path, token=None, body=None):
    req = urllib.request.Request(B + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            raw = r.read().decode('utf-8')
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, (json.loads(raw) if raw else {})
        except Exception:
            return e.code, {'raw': raw}


def upload(path_extra, token, fields, filename, content, mime='image/png'):
    """手工拼 multipart —— 标准库没有现成的表单编码，而 multipart 的 boundary 必须自己造。"""
    boundary = '----OAVerify' + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(('--%s\r\n' % boundary).encode())
        parts.append(('Content-Disposition: form-data; name="%s"\r\n\r\n' % k).encode('utf-8'))
        parts.append(str(v).encode('utf-8'))
        parts.append(b'\r\n')
    if filename is not None:
        parts.append(('--%s\r\n' % boundary).encode())
        parts.append(('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode('utf-8'))
        parts.append(('Content-Type: %s\r\n\r\n' % mime).encode())
        parts.append(content)
        parts.append(b'\r\n')
    parts.append(('--%s--\r\n' % boundary).encode())
    body = b''.join(parts)

    req = urllib.request.Request(B + path_extra, method='POST', data=body)
    req.add_header('Content-Type', 'multipart/form-data; boundary=' + boundary)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            raw = r.read().decode('utf-8')
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, (json.loads(raw) if raw else {})
        except Exception:
            return e.code, {'raw': raw}


def fetch_raw(path, token):
    """下载：返回 (status, bytes, headers)。"""
    req = urllib.request.Request(B + path)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print('%s %s%s' % ('[PASS]' if cond else '[FAIL]', name, ('  -> ' + detail) if detail else ''))


# --------------------------------------------------------------------- 一、登录
print('=' * 74)
print('一、准备：登录与临时单据类型')
print('=' * 74)

st, r = call('POST', '/api/auth/login', body={'account': 'admin', 'password': '123456'})
check('admin 登录', st == 200 and r.get('code') == 0, r.get('msg', ''))
admin = r['data']['token']
admin_id = r['data']['user']['userId']

st, r = call('GET', '/api/users', token=admin)
users = r.get('data') or []

# 两类账号用途完全不同，不能随手挑一个：
#   outsider —— 数据范围 SELF 且不是流程参与者，用来验证「越权」确实被 403 拦住。
#               随便挑个 GM 当越权账号是错的：他本来就该看到全公司的单据。
#   handler  —— 数据范围 COMPANY，用来承接办理节点任务。不能选 SELF 账号：
#               待办列表自身也受数据范围过滤，SELF 账号即使被指派了节点也看不到待办。
#               更不能选发起人本人 —— AssigneeResolver 会把发起人从候选人里剔除（不审批自己），
#               节点会因为「无处理人」被引擎自动跳过。
accounts = {}
for u in users:
    st, r = call('POST', '/api/auth/login', body={'account': u.get('account'), 'password': '123456'})
    if st == 200 and r.get('code') == 0:
        me = r['data']['user']
        accounts[me['userId']] = {'token': r['data']['token'], 'account': me['account'],
                                  'realName': me.get('realName'), 'scope': me.get('dataScope'),
                                  'roles': me.get('roleCodes') or []}

outsider = next((v for k, v in accounts.items() if k != admin_id and v['scope'] == 'SELF'), None)
handler = next((v for k, v in accounts.items()
                if k != admin_id and v['scope'] == 'COMPANY' and 'CASHIER' in v['roles']), None)
if handler is None:
    handler = next((v for k, v in accounts.items() if k != admin_id and v['scope'] == 'COMPANY'), None)
handler_id = next((k for k, v in accounts.items() if v is handler), None)

outsider_token = (outsider or {}).get('token')
handler_token = (handler or {}).get('token')
print('      越权测试账号：%s（范围 %s，角色 %s）' % (
    (outsider or {}).get('account'), (outsider or {}).get('scope'), (outsider or {}).get('roles')))
print('      办理节点处理人：%s（范围 %s，角色 %s，id=%s）' % (
    (handler or {}).get('account'), (handler or {}).get('scope'), (handler or {}).get('roles'), handler_id))
check('两类测试账号就位（越权=SELF，办理=COMPANY 且非发起人）',
      outsider_token is not None and handler_token is not None)

# 预清理：上一次运行若中途失败会留下残留，这里一并扫掉，保证脚本可重复执行
_left = sql_scalar("SELECT id FROM document_type WHERE code='%s'" % TMP_CODE)
if _left:
    for _o in subprocess.run(
            ['mysql', '-uroot', DB, '-N', '-e',
             "SELECT id FROM document WHERE doc_type_id=%s" % _left],
            capture_output=True, text=True).stdout.split():
        sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % _o)
        sql("DELETE FROM attachment WHERE document_id=%s" % _o)
        sql("DELETE FROM document_link WHERE document_id=%s" % _o)
        sql("DELETE FROM flow_instance_node WHERE document_id=%s" % _o)
        sql("DELETE FROM flow_instance WHERE document_id=%s" % _o)
        sql("DELETE FROM document WHERE id=%s" % _o)
    sql("DELETE FROM flow_node_assignee WHERE node_id IN (SELECT id FROM flow_config_node "
        "WHERE flow_config_id IN (SELECT id FROM flow_config WHERE doc_type_id=%s))" % _left)
    sql("DELETE FROM flow_config_node WHERE flow_config_id IN "
        "(SELECT id FROM flow_config WHERE doc_type_id=%s)" % _left)
    sql("DELETE FROM flow_config WHERE doc_type_id=%s" % _left)
    sql("DELETE FROM form_template WHERE doc_type_id=%s" % _left)
    sql("DELETE FROM document_type WHERE id=%s" % _left)
    print('      预清理：发现上次残留的临时单据类型 #%s，已清除' % _left)

# 预清理（Flowable 侧）：临时流程的部署，以及所有"单据已经不在了"的孤儿流程历史。
# 这一层是必须的：只删业务表的话，ACT_HI_PROCINST 里会一条条堆起来，
# 每跑一次验证多一条，时间久了没人说得清哪些历史是演示数据、哪些是测试垃圾。
_HI_TABLES = ('ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
              'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG')
_orphan = [x for x in sql_scalar(
    "SELECT IFNULL(GROUP_CONCAT(p.PROC_INST_ID_),'') FROM ACT_HI_PROCINST p "
    "LEFT JOIN document d ON d.doc_no=p.BUSINESS_KEY_ WHERE d.id IS NULL").split(',') if x]
for _pid in _orphan:
    for _t in _HI_TABLES:
        sql("DELETE FROM %s WHERE PROC_INST_ID_='%s'" % (_t, _pid))
    sql("DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='%s'" % _pid)
if _orphan:
    print('      预清理：清掉 %d 条孤儿流程历史' % len(_orphan))

_deps = [x for x in sql_scalar(
    "SELECT IFNULL(GROUP_CONCAT(DISTINCT DEPLOYMENT_ID_),'') FROM ACT_RE_PROCDEF "
    "WHERE KEY_='%s_V1'" % TMP_CODE).split(',') if x]
if _deps:
    _in = ','.join("'%s'" % d for d in _deps)
    sql("DELETE FROM ACT_PROCDEF_INFO WHERE PROC_DEF_ID_ IN "
        "(SELECT ID_ FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_ IN (%s))" % _in)
    sql("DELETE FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_ IN (%s)" % _in)
    sql("DELETE FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_ IN (%s)" % _in)
    sql("DELETE FROM ACT_RE_DEPLOYMENT WHERE ID_ IN (%s)" % _in)
    print('      预清理：撤掉 %d 个上次残留的临时流程部署' % len(_deps))

# 临时单据类型 + 复制一份表单模板（createDraft 要求单据类型必须有生效模板）
sql("DELETE FROM form_template WHERE doc_type_id IN (SELECT id FROM document_type WHERE code='%s')" % TMP_CODE)
sql("DELETE FROM document_type WHERE code='%s'" % TMP_CODE)
sql("INSERT INTO document_type (company_id, code, name, category, status, sort_no) "
    "VALUES (1,'%s','附件验证临时单据','DAILY',1,98)" % TMP_CODE)
tmp_id = sql_scalar("SELECT id FROM document_type WHERE code='%s'" % TMP_CODE)
sql("INSERT INTO form_template (company_id, doc_type_id, name, version, schema_json, status) "
    "SELECT company_id, %s, '附件验证模板', 1, schema_json, 1 FROM form_template "
    "WHERE doc_type_id=1 LIMIT 1" % tmp_id)
tpl_id = sql_scalar("SELECT id FROM form_template WHERE doc_type_id=%s LIMIT 1" % tmp_id)
check('临时单据类型与表单模板就绪（隔离测试用）', bool(tmp_id) and bool(tpl_id),
      'docTypeId=%s templateId=%s' % (tmp_id, tpl_id))

tmp_flow = None
if tmp_id:
    st, r = call('POST', '/api/flows/configs', token=admin, body={
        'name': '附件验证临时流程', 'docTypeId': int(tmp_id),
        'nodeItems': [
            {'nodeName': '发起人', 'nodeType': 5},
            # 指给出纳（非发起人），才能由脚本以该账号驱动这一次办理
            {'nodeName': '出纳付款', 'nodeType': 4, 'ruleType': 'user',
             'ruleValue': json.dumps({'userIds': [handler_id]})},
        ]})
    if st == 200 and r.get('code') == 0:
        tmp_flow = r['data']
    check('临时流程创建并部署（含办理节点）', tmp_flow is not None,
          'deployStatus=%s msg=%s' % ((tmp_flow or {}).get('deployStatus'), r.get('msg', '')))

# 节点规格核对：办理节点默认必填凭证，发起/审批节点默认不必填（= 存量流程行为不变）
if tmp_flow:
    st, r = call('GET', '/api/flows/configs/%s' % tmp_flow['id'], token=admin)
    _nodes = ((r.get('data') or {}).get('nodes')) or []
    _by_name = {n.get('nodeName'): n for n in _nodes}
    check('办理节点默认 requireAttachment=1（凭证必填落到配置上，不是写死在页面）',
          (_by_name.get('出纳付款') or {}).get('requireAttachment') == 1,
          str({k: v.get('requireAttachment') for k, v in _by_name.items()}))
    check('发起节点默认 requireAttachment=0（存量流程行为不变）',
          not (_by_name.get('发起人') or {}).get('requireAttachment'),
          '发起人=%s' % (_by_name.get('发起人') or {}).get('requireAttachment'))

FORM = {'title': '附件验证单', 'amount': 100, 'payType': 'CASH',
        'payeeName': '张三', 'payeeAccount': '6222000000000000', 'payeeBank': '工商银行',
        'reason': '验证附件能力'}

doc_id = None
if tmp_id:
    st, r = call('POST', '/api/documents', token=admin, body={
        'docTypeId': int(tmp_id), 'formData': FORM, 'title': FORM['title'],
        'amount': FORM['amount'], 'reason': FORM['reason']})
    if st == 200 and r.get('code') == 0:
        doc_id = r['data']['id']
    check('创建临时单据草稿', doc_id is not None, 'docId=%s msg=%s' % (doc_id, r.get('msg', '')))

# --------------------------------------------------------------------- 二、基础能力
print()
print('=' * 74)
print('二、上传 / 列表 / 下载 / 预览')
print('=' * 74)

att = None
if doc_id:
    st, r = upload('/api/attachments', admin,
                   {'documentId': doc_id, 'bizType': 'apply'}, '凭证样本.png', PNG, 'image/png')
    att = r.get('data') if st == 200 and r.get('code') == 0 else None
    check('上传附件', att is not None, 'HTTP %d / %s' % (st, r.get('msg', '')))
    if att:
        check('  返回业务字段（中文类型名/可读大小/可预览标记）',
              att.get('bizTypeName') == '申请资料' and att.get('sizeText')
              and att.get('previewable') is True,
              'bizTypeName=%s size=%s previewable=%s' % (
                  att.get('bizTypeName'), att.get('sizeText'), att.get('previewable')))
        check('  不泄露存储键 fileKey', 'fileKey' not in att,
              '返回字段：%s' % sorted(att.keys()))

        key = sql_scalar("SELECT file_key FROM attachment WHERE id=%s" % att['id'])
        disk = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            'oa-backend', 'data', 'attachments', key)
        check('  文件真的落盘（数据库键 → 磁盘文件）', os.path.isfile(disk),
              '%s（%d 字节）' % (key, os.path.getsize(disk) if os.path.isfile(disk) else -1))

    st, r = call('GET', '/api/attachments?documentId=%s' % doc_id, token=admin)
    lst = r.get('data') or []
    check('附件列表', st == 200 and len(lst) == 1, '共 %d 个' % len(lst))

    st, r = call('GET', '/api/documents/%s' % doc_id, token=admin)
    detail_atts = ((r.get('data') or {}).get('attachments')) or []
    check('单据详情内联带出附件', len(detail_atts) == 1 and detail_atts[0].get('fileName') == '凭证样本.png',
          str([a.get('fileName') for a in detail_atts]))

if att:
    st, body, hd = fetch_raw('/api/attachments/%s/download' % att['id'], admin)
    check('下载内容与上传字节一致', st == 200 and body == PNG,
          'HTTP %d / %d 字节 / %s' % (st, len(body), hd.get('Content-Type')))
    check('下载响应头做了防嗅探与落盘处理',
          'attachment' in (hd.get('Content-Disposition') or '')
          # 全局 server.servlet.encoding.force=true 会给所有响应补上 ;charset=UTF-8，
          # 二进制流带上它无害（浏览器对 octet-stream 忽略 charset），因此按前缀判断
          and (hd.get('Content-Type') or '').startswith('application/octet-stream')
          and hd.get('X-Content-Type-Options') == 'nosniff',
          'Content-Type=%s' % (hd.get('Content-Type') or ''))
    check('  中文文件名用 RFC 5987 编码（不出现乱码直出）',
          "filename*=UTF-8''" in (hd.get('Content-Disposition') or ''))

    st, body, hd = fetch_raw('/api/attachments/%s/preview' % att['id'], admin)
    check('图片可在线预览', st == 200 and (hd.get('Content-Type') or '').startswith('image/png'),
          'HTTP %d / %s' % (st, hd.get('Content-Type')))

# 非图片类型的预览必须被拒绝（否则 SVG/HTML 之类会被浏览器当页面渲染）
if doc_id:
    st, r = upload('/api/attachments', admin,
                   {'documentId': doc_id, 'bizType': 'apply'}, '说明.txt', '纯文本'.encode('utf-8'), 'text/plain')
    txt_att = r.get('data') if st == 200 and r.get('code') == 0 else None
    check('上传 txt（白名单内）成功', txt_att is not None, r.get('msg', ''))
    if txt_att:
        st2, body2, hd2 = fetch_raw('/api/attachments/%s/preview' % txt_att['id'], admin)
        check('  txt 预览被拒绝（只允许图片/PDF 内联）', st2 == 200 and b'code' in body2[:60],
              'HTTP %d / %s' % (st2, body2[:80]))
        check('  但可以正常下载', fetch_raw('/api/attachments/%s/download' % txt_att['id'], admin)[0] == 200)

# --------------------------------------------------------------------- 三、输入校验
print()
print('=' * 74)
print('三、输入校验：不该进来的必须被拦')
print('=' * 74)

if doc_id:
    st, r = upload('/api/attachments', admin,
                   {'documentId': doc_id, 'bizType': 'apply'}, '木马.exe', b'MZ\x90\x00', 'application/octet-stream')
    check('非白名单扩展名 .exe 被拒绝', st == 200 and r.get('code') != 0, r.get('msg', ''))

    st, r = upload('/api/attachments', admin,
                   {'documentId': doc_id, 'bizType': 'apply'}, '无扩展名', b'data', 'application/octet-stream')
    check('无扩展名文件被拒绝', st == 200 and r.get('code') != 0, r.get('msg', ''))

    big = b'\x00' * (21 * 1024 * 1024)
    st, r = upload('/api/attachments', admin,
                   {'documentId': doc_id, 'bizType': 'apply'}, '超大.pdf', big, 'application/pdf')
    check('超过 20MB 被拒绝，且给出可读原因（不是「服务异常」）',
          r.get('code') not in (0, None) and ('上限' in (r.get('msg') or '')),
          'HTTP %d / %s' % (st, r.get('msg', '')))

    st, r = upload('/api/attachments', admin,
                   {'documentId': doc_id, 'bizType': 'apply'}, '空.png', b'', 'image/png')
    check('空文件被拒绝', st == 200 and r.get('code') != 0, r.get('msg', ''))

    st, r = upload('/api/attachments', admin,
                   {'documentId': doc_id, 'bizType': 'bogus'}, 'a.png', PNG, 'image/png')
    check('未知业务类型被拒绝', st == 200 and r.get('code') != 0, r.get('msg', ''))

    st, r = upload('/api/attachments', None, {'bizType': 'apply'}, 'a.png', PNG, 'image/png')
    check('未登录上传被拒绝 → 401', st == 401, 'HTTP %d / %s' % (st, r.get('msg', '')))

# --------------------------------------------------------------------- 四、越权
print()
print('=' * 74)
print('四、越权：附件继承单据的可见性')
print('=' * 74)

if outsider_token and att:
    st, r, _h = fetch_raw('/api/attachments/%s/download' % att['id'], outsider_token)
    st_json = json.loads(r.decode('utf-8')) if r[:1] == b'{' else {}
    check('无权用户下载他人单据附件 → 403',
          st_json.get('code') == 403 or st == 403,
          'HTTP %d / %s' % (st, st_json.get('msg', '')))

    st, r = upload('/api/attachments', outsider_token,
                   {'documentId': doc_id, 'bizType': 'apply'}, '偷传.png', PNG, 'image/png')
    check('无权用户向他人单据上传 → 403', r.get('code') == 403 or st == 403,
          'HTTP %d / %s' % (st, r.get('msg', '')))

    st, r = call('GET', '/api/attachments?documentId=%s' % doc_id, token=outsider_token)
    check('无权用户列他人单据附件 → 403', r.get('code') == 403 or st == 403,
          'HTTP %d / %s' % (st, r.get('msg', '')))

    st, r = call('DELETE', '/api/attachments/%s' % att['id'], token=outsider_token)
    check('无权用户删除他人附件 → 403', r.get('code') == 403 or st == 403,
          'HTTP %d / %s' % (st, r.get('msg', '')))

# 存储键被写成穿越路径的脏数据 —— 本地存储实现里最容易漏的任意文件读取漏洞
sql("INSERT INTO attachment (company_id, document_id, biz_type, file_name, file_key, file_size, "
    "uploader_id, uploader_name, deleted) VALUES (1, %s, 'apply', '穿越.txt', "
    "'../../../../../../etc/passwd', 100, %s, '越权测试', 0)" % (doc_id or 1, admin_id))
evil_id = sql_scalar("SELECT id FROM attachment WHERE file_name='穿越.txt' ORDER BY id DESC LIMIT 1")
st, body, _ = fetch_raw('/api/attachments/%s/download' % evil_id, admin)
is_passwd = b'root:' in body
check('存储键含 ../../ 的脏数据被拦下（无任意文件读取）',
      not is_passwd, 'HTTP %d，响应是否包含 /etc/passwd 内容=%s' % (st, is_passwd))
sql("DELETE FROM attachment WHERE id=%s" % evil_id)

# --------------------------------------------------------------------- 五、删除规则
print()
print('=' * 74)
print('五、删除规则：草稿可删，提交后属于留痕不可删')
print('=' * 74)

if txt_att:
    st, r = call('DELETE', '/api/attachments/%s' % txt_att['id'], token=admin)
    check('本人删除草稿附件', st == 200 and r.get('code') == 0, r.get('msg', ''))
    check('  记录已移除', not sql_scalar("SELECT id FROM attachment WHERE id=%s "
                                     "AND deleted=0" % txt_att['id']))
    key = sql_scalar("SELECT file_key FROM attachment WHERE id=%s" % txt_att['id'])
    disk = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        'oa-backend', 'data', 'attachments', key)
    check('  磁盘文件同步清理', not os.path.isfile(disk), key)

# --------------------------------------------------------------------- 六、凭证必填
print()
print('=' * 74)
print('六、办理节点凭证必填（服务端权威判定）')
print('=' * 74)

task_id = None
node_key = None
if doc_id and tmp_flow and handler_token:
    st, r = call('POST', '/api/documents/%s/submit' % doc_id, token=admin)
    check('提交临时单据（任务落到出纳的办理节点）', st == 200 and r.get('code') == 0, r.get('msg', ''))
    if st == 200 and r.get('code') == 0:
        node_key = r['data'].get('currentNodeKey')

    st, r = call('GET', '/api/todos', token=handler_token)
    todos = [t for t in (r.get('data') or []) if t.get('documentId') == doc_id]
    t0 = todos[0] if todos else None
    check('办理人待办出现该任务', t0 is not None,
          '待办条数=%d nodeName=%s' % (len(todos), (t0 or {}).get('nodeName')))
    check('  待办暴露 requireAttachment 标记（前端据此显示「必填」）',
          t0 is not None and t0.get('requireAttachment') is True,
          'requireAttachment=%s' % (t0 or {}).get('requireAttachment'))
    if t0:
        task_id = t0.get('taskId')
        node_key = t0.get('nodeKey') or node_key

if task_id:
    st, r = call('POST', '/api/todos/approve', token=handler_token,
                 body={'taskId': task_id, 'action': 'approve', 'comment': '不带凭证直接通过'})
    blocked = r.get('code') not in (0, None) and '凭证' in (r.get('msg') or '')
    check('绕过界面直接调用、不带凭证 → 被服务端拦下', blocked,
          'HTTP %d / %s' % (st, r.get('msg', '')))

    # 确认真的没被放过去（任务还挂在待办里）
    st, r = call('GET', '/api/todos', token=handler_token)
    still = any(t.get('taskId') == task_id for t in (r.get('data') or []))
    check('  被拦后任务仍留在待办（流程未被推进）', still)

    st, r = upload('/api/attachments', handler_token,
                   {'documentId': doc_id, 'nodeKey': node_key, 'bizType': 'receipt'},
                   '付款回单.png', PNG, 'image/png')
    voucher = r.get('data') if st == 200 and r.get('code') == 0 else None
    check('审批人在「审批中」的单据上补传凭证（bizType=receipt）', voucher is not None,
          r.get('msg', ''))
    if voucher:
        check('  凭证业务类型中文名正确', voucher.get('bizTypeName') == '付款回单',
              str(voucher.get('bizTypeName')))
        check('  凭证绑定到了该流程节点', voucher.get('nodeKey') == node_key,
              'nodeKey=%s' % voucher.get('nodeKey'))

    st, r = call('POST', '/api/todos/approve', token=handler_token,
                 body={'taskId': task_id, 'action': 'approve', 'comment': '凭证齐全，通过'})
    check('补上凭证后可以正常通过', st == 200 and r.get('code') == 0, r.get('msg', ''))

# 办结后不允许再追加附件
if doc_id:
    st, r = call('GET', '/api/documents/%s' % doc_id, token=admin)
    final_status = ((r.get('data') or {}).get('document') or {}).get('status')
    if final_status in (3, 6):
        st, r = upload('/api/attachments', admin,
                       {'documentId': doc_id, 'bizType': 'apply'}, '事后补.png', PNG, 'image/png')
        check('办结后追加附件被拒绝', r.get('code') not in (0, None), r.get('msg', ''))
    else:
        check('办结后追加附件被拒绝', False, '单据未办结，status=%s' % final_status)

# --------------------------------------------------------------------- 七、清理
print()
print('=' * 74)
print('七、清理与演示数据复核')
print('=' * 74)

if doc_id:
    # 单据与附件是本次临时数据，直接物理清除（含走一遍存储清理）
    keys = subprocess.run(
        ['mysql', '-uroot', DB, '-N', '-e',
         "SELECT file_key FROM attachment WHERE document_id=%s" % doc_id],
        capture_output=True, text=True).stdout.split()
    root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        'oa-backend', 'data', 'attachments')
    removed = 0
    for k in keys:
        p = os.path.join(root, k)
        if os.path.isfile(p):
            os.remove(p)
            removed += 1
    # Flowable 侧也要清。此前只删业务表，留下 ACT_HI_PROCINST 之类的历史，
    # 表现为"库里 document=46 但流程历史有 35 条、其中 3 条对应已删单据的孤儿"。
    # 流程实例在 ACT_HI_PROCINST 里靠 BUSINESS_KEY_=单据编号 关联（不是 document.id），
    # 列名是 PROC_INST_ID_；ACT_HI_ENTITYLINK 没有 PROC_INST_ID_ 列，不要一起循环。
    doc_no = sql_scalar("SELECT doc_no FROM document WHERE id=%s" % doc_id)
    pids = [x for x in sql_scalar(
        "SELECT IFNULL(GROUP_CONCAT(PROC_INST_ID_),'') FROM ACT_HI_PROCINST "
        "WHERE BUSINESS_KEY_='%s'" % doc_no).split(',') if x]
    for t in ('ACT_HI_ACTINST', 'ACT_HI_DETAIL', 'ACT_HI_TASKINST', 'ACT_HI_IDENTITYLINK',
              'ACT_HI_COMMENT', 'ACT_HI_VARINST', 'ACT_HI_TSK_LOG'):
        for pid in pids:
            sql("DELETE FROM %s WHERE PROC_INST_ID_='%s'" % (t, pid))
    for pid in pids:
        sql("DELETE FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='%s'" % pid)

    # 临时流程的部署要连 ACT_RE_PROCDEF / ACT_GE_BYTEARRAY 一起撤，
    # 否则 ACT_RE_PROCDEF 里会永远留着 ATT_TMP_TYPE_V1（每个版本一条）。
    dep_ids = [x for x in sql_scalar(
        "SELECT IFNULL(GROUP_CONCAT(DISTINCT DEPLOYMENT_ID_),'') FROM ACT_RE_PROCDEF "
        "WHERE KEY_='%s_V1'" % TMP_CODE).split(',') if x]
    if dep_ids:
        in_clause = ','.join("'%s'" % d for d in dep_ids)
        sql("DELETE FROM ACT_PROCDEF_INFO WHERE PROC_DEF_ID_ IN "
            "(SELECT ID_ FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_ IN (%s))" % in_clause)
        sql("DELETE FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_ IN (%s)" % in_clause)
        sql("DELETE FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_ IN (%s)" % in_clause)
        sql("DELETE FROM ACT_RE_DEPLOYMENT WHERE ID_ IN (%s)" % in_clause)

    # 通知必须跟着单据一起清：Flowable 每推进一步都会给发起人写一条
    # 「您的单据有新进展/已通过」。前端现在有未读角标，漏清就会变成"幽灵角标"。
    sql("DELETE FROM notification WHERE biz_type='document' AND biz_id=%s" % doc_id)
    sql("DELETE FROM attachment WHERE document_id=%s" % doc_id)
    sql("UPDATE flow_instance_node SET deleted=1 WHERE document_id=%s" % doc_id)
    sql("DELETE FROM flow_config_node WHERE flow_config_id IN "
        "(SELECT id FROM flow_config WHERE doc_type_id=%s)" % tmp_id)
    sql("DELETE FROM flow_config WHERE doc_type_id=%s" % tmp_id)
    sql("DELETE FROM document WHERE id=%s" % doc_id)
    sql("DELETE FROM form_template WHERE doc_type_id=%s" % tmp_id)
    sql("DELETE FROM document_type WHERE code='%s'" % TMP_CODE)
    print('      已清理：临时单据 #%s、临时流程、临时单据类型、%d 个流程实例历史、%d 个流程部署、%d 个落盘文件'
          % (doc_id, len(pids), len(dep_ids), removed))

leftover = sql_scalar("SELECT id FROM document_type WHERE code='%s'" % TMP_CODE)
check('临时数据清理干净', not leftover)

st, r = call('GET', '/api/flows/configs', token=admin)
demo = [c for c in (r.get('data') or []) if c.get('docTypeId') in (1, 2, 3)]
check('演示流程未被本次验证改动',
      len(demo) == 3 and all(c.get('version') == 1 for c in demo),
      ', '.join('%s(v%s)' % (c.get('name'), c.get('version')) for c in demo))

st, r = call('GET', '/api/todos', token=admin)
check('演示待办未受影响', st == 200 and r.get('code') == 0, '待办 %d 条' % len(r.get('data') or []))

print()
print('=' * 74)
print('结果：通过 %d 项，失败 %d 项' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败清单：')
    for f in FAIL:
        print('  - ' + f)
print('=' * 74)

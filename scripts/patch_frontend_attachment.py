#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把联调版前端的附件能力接上真实后端。

背景：此前三处 el-upload 都是 action="#" + :auto-upload="false" 的纯摆设 ——
用户选了文件，什么都不会发生，而后端根本没有上传接口。

本脚本是一个「先校验命中数、全中才写入」的幂等补丁：
任何一个锚点没命中（说明前端结构变了），整体不落盘，避免半截修改更难排查。
重复执行安全：已打过的补丁锚点会消失，脚本会识别为 done 并跳过。

用法：python3 scripts/patch_frontend_attachment.py
"""
import sys
import os

TARGET = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      '海峡金OA审批系统-联调版.html')

# ---------------------------------------------------------------------------
# 补丁定义：(编号, 说明, 原文, 新文)
# ---------------------------------------------------------------------------
PATCHES = []


def add(tag, desc, old, new):
    PATCHES.append((tag, desc, old, new))


# --- 1. api 层：附件四个方法 + 两个专用 HTTP 函数 ---------------------------
add(
    '1', 'api 层补附件方法，并新增 httpForm / httpBlob',
    """  retireFlow:   function(id){ return http('/api/flows/configs/' + id, {method:'DELETE'}); }
};""",
    """  retireFlow:   function(id){ return http('/api/flows/configs/' + id, {method:'DELETE'}); },
  uploadAttachment: function(file, opt){
    opt = opt || {};
    const fd = new FormData();
    fd.append('file', file);
    if (opt.documentId) fd.append('documentId', opt.documentId);
    if (opt.nodeKey)    fd.append('nodeKey', opt.nodeKey);
    fd.append('bizType', opt.bizType || 'apply');
    return httpForm('/api/attachments', fd);
  },
  attachments:  function(documentId){ return http('/api/attachments', {params:{documentId:documentId}}); },
  deleteAttachment: function(id){ return http('/api/attachments/' + id, {method:'DELETE'}); }
};

/* 附件上传不能复用 http —— 它固定发 Content-Type: application/json，
   而 multipart 必须让浏览器自己生成带 boundary 的头，手写会覆盖掉 boundary，
   后端就只能收到「解析不出文件」的 400。 */
async function httpForm(path, formData){
  const headers = {};
  const tk = getToken();
  if (tk) headers['Authorization'] = 'Bearer ' + tk;
  let res;
  try {
    res = await fetch(API_BASE + path, {method:'POST', headers:headers, body:formData});
  } catch (e) {
    const reachable = await probeReachable();
    const err = new Error(reachable ? CORS_HINT : OFFLINE_HINT);
    err.network = true; err.cors = reachable;
    throw err;
  }
  if (res.status === 401){ if (onUnauthorized) onUnauthorized(); throw new Error('未登录或登录已过期'); }
  let json;
  try { json = await res.json(); }
  catch (e) { throw new Error('上传响应解析失败（HTTP ' + res.status + '）'); }
  if (json.code !== 0){
    const err = new Error(json.msg || ('上传失败 HTTP ' + res.status));
    err.code = json.code;
    throw err;
  }
  return json.data;
}

/* 附件下载/预览必须带 Authorization 头（后端要按单据可见性鉴权），
   所以不能用 <a href> 或 window.open 直接指向接口地址 —— 那样没有请求头，只会拿到 401。
   统一 fetch 成 blob，再在本地触发下载 / 预览。 */
async function httpBlob(path){
  const headers = {};
  const tk = getToken();
  if (tk) headers['Authorization'] = 'Bearer ' + tk;
  const res = await fetch(API_BASE + path, {headers:headers});
  if (res.status === 401){ if (onUnauthorized) onUnauthorized(); throw new Error('未登录或登录已过期'); }
  if (!res.ok){
    let msg = '下载失败 HTTP ' + res.status;
    try { const j = await res.json(); if (j && j.msg) msg = j.msg; } catch(e){}
    throw new Error(msg);
  }
  return res.blob();
}"""
)

# --- 2. 附件状态、处理器，以及 allFiles 改读后端真实数据 --------------------
add(
    '2', '附件状态与处理器；附件 tab 数据源改为后端附件',
    """  const allFiles = computed(function(){
    const s = selected.value;
    if (!s || !s.nodes) return [];
    return s.nodes.reduce(function(acc, nd){
      (nd.files || []).forEach(function(name){
        acc.push({name:name, node:nd.name, uploader:nd.person || s.applicant, time:nd.time || '待上传'});
      });
      return acc;
    }, []);
  });""",
    """  /* ---------- 附件 ---------- */
  /* 提交弹窗里的 el-upload 是 :auto-upload="false"，此处只负责把用户选中的文件收集起来。
     真正的上传发生在点「提交审批」之后 —— 那时才有单据 ID。
     顺序不能反：attachment.document_id 要指向一张真实存在的单据。 */
  const applyFiles = ref({});
  const onApplyFileChange = function(i, file, fileList){
    const last = (fileList && fileList.length) ? fileList[fileList.length - 1] : file;
    applyFiles.value = Object.assign({}, applyFiles.value, {[i]: (last && last.raw) || last});
  };
  const onApplyFileRemove = function(i){
    const next = Object.assign({}, applyFiles.value);
    delete next[i];
    applyFiles.value = next;
  };

  /* 审批动作区的凭证 */
  const approvalFiles = ref([]);
  const onVoucherChange = function(file, fileList){
    approvalFiles.value = (fileList || []).map(function(f){ return (f && f.raw) || f; });
  };

  /* 按节点名推断凭证的业务类型：出纳付款要回单、用印办理要盖章件、其余算普通审批凭证 */
  const voucherBizType = function(nodeName){
    const n = nodeName || '';
    if (n.indexOf('出纳') >= 0 || n.indexOf('付款') >= 0 || n.indexOf('回单') >= 0) return 'receipt';
    if (n.indexOf('用印') >= 0 || n.indexOf('章') >= 0) return 'seal';
    return 'approve';
  };

  const saveBlob = function(blob, fileName){
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = fileName || 'attachment';
    document.body.appendChild(a); a.click(); a.remove();
    /* objectURL 不主动 revoke 会让 blob 常驻内存 */
    setTimeout(function(){ URL.revokeObjectURL(url); }, 4000);
  };

  const findFile = function(id){
    return ((selected.value && selected.value.attachments) || []).find(function(x){ return x.id === id; }) || null;
  };

  const downloadAttachment = async function(id){
    try {
      const blob = await httpBlob('/api/attachments/' + id + '/download');
      const f = findFile(id);
      saveBlob(blob, f ? f.fileName : 'attachment');
    } catch(e){ ElementPlus.ElMessage.error('下载失败：' + e.message); }
  };

  const previewAttachment = async function(id){
    try {
      const blob = await httpBlob('/api/attachments/' + id + '/preview');
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(function(){ URL.revokeObjectURL(url); }, 60000);
    } catch(e){ ElementPlus.ElMessage.error('预览失败：' + e.message); }
  };

  const removeAttachment = async function(id){
    try {
      await api.deleteAttachment(id);
      ElementPlus.ElMessage.success('附件已删除');
      if (selected.value) await openDetail(selected.value);
    } catch(e){ ElementPlus.ElMessage.error('删除失败：' + e.message); }
  };

  /* 只有本人上传、且单据还没进入流程时才给删除入口（与后端规则一致） */
  const canDeleteFile = function(f){
    const s = selected.value;
    if (!s || !me.value) return false;
    const editable = s.statusCode === 0 || s.statusCode === 4 || s.statusCode === 5;
    return editable && f.uploaderId === me.value.userId;
  };

  /* 附件 tab 的数据源改为后端返回的真实附件。
     此前是从流转节点里的字符串文件名聚出来的，只能看、点不了、没有 ID。 */
  const allFiles = computed(function(){
    const list = (selected.value && selected.value.attachments) || [];
    return list.map(function(a){
      return {
        id: a.id,
        name: a.fileName,
        node: a.bizTypeName || a.bizType || '',
        uploader: a.uploaderName || '',
        uploaderId: a.uploaderId,
        time: fmtDateTime(a.createdAt),
        sizeText: a.sizeText || '',
        previewable: !!a.previewable
      };
    });
  });"""
)

# --- 3. 提交弹窗里的附件上传接线 -------------------------------------------
add(
    '3', '提交弹窗 el-upload 绑定 on-change 收集文件',
    """<el-upload v-else action="#" :auto-upload="false" :limit="1"><el-button type="primary" plain>＋ 上传文件</el-button></el-upload>""",
    """<el-upload v-else action="#" :auto-upload="false" :limit="1" :on-change="onApplyFileChange.bind(null, i)" :on-remove="onApplyFileRemove.bind(null, i)"><el-button type="primary" plain>＋ 上传文件</el-button><template #tip><span style="font-size:11px;color:#909399">提交时统一上传</span></template></el-upload>"""
)

# --- 4. 审批凭证上传接线 ---------------------------------------------------
add(
    '4', '审批动作区 el-upload 接线，「(必填)」改由后端配置驱动',
    """<el-upload action="#" :auto-upload="false"><el-button>＋ 上传{{selected.current}}凭证（必填）</el-button></el-upload>""",
    """<el-upload action="#" :auto-upload="false" :limit="3" :on-change="onVoucherChange" :on-remove="onVoucherChange"><el-button>＋ 上传{{selected.current}}凭证{{selected.requireAttachment?'（必填）':''}}</el-button><template #tip><span style="font-size:11px;color:#909399" v-if="selected.requireAttachment">该节点办理必须上传凭证，否则无法通过</span></template></el-upload>"""
)

# --- 5. 附件 tab 的卡片：真实下载/预览/删除 + 空态 -------------------------
add(
    '5', '附件卡片接真实下载/预览/删除，并补空态',
    """<div class="file-card" v-for="f in allFiles"><i>▧</i><div><b>{{f.name}}</b><span>{{f.node}} · {{f.uploader}} · {{f.time}}</span></div><el-button link type="primary">预览</el-button><el-button link>下载</el-button></div>""",
    """<el-empty v-if="!allFiles.length" description="暂无附件" :image-size="70"></el-empty><div class="file-card" v-for="f in allFiles" :key="f.id"><i>▧</i><div><b>{{f.name}}</b><span>{{f.node}} · {{f.uploader}} · {{f.time}} · {{f.sizeText}}</span></div><el-button link type="primary" v-if="f.previewable" @click="previewAttachment(f.id)">预览</el-button><el-button link @click="downloadAttachment(f.id)">下载</el-button><el-button link type="danger" v-if="canDeleteFile(f)" @click="removeAttachment(f.id)">删除</el-button></div>"""
)

# --- 6. 审批通过：先上传凭证，再走审批 -------------------------------------
add(
    '6', '审批通过前上传凭证，并对必填节点本地拦截',
    """  const approve = async function(){
    if (!needTask()) return;
    loading.value = true;
    try {
      await api.approve({taskId:selected.value.pendingTaskId, action:'approve', comment:approvalComment.value || '同意'});""",
    """  const approve = async function(){
    if (!needTask()) return;
    /* 界面上这句「(必填)」是提示，真正拦住的是后端；这里做本地预检只是为了少一次失败往返 */
    const alreadyOnNode = ((selected.value.attachments) || []).some(function(a){
      return a.nodeKey && a.nodeKey === selected.value.viewingNodeKey;
    });
    if (selected.value.requireAttachment && !approvalFiles.value.length && !alreadyOnNode){
      ElementPlus.ElMessage.warning('「' + (selected.value.current || '当前节点') + '」需要上传办理凭证后才能通过');
      return;
    }
    loading.value = true;
    try {
      /* 凭证必须先落库再审批：后端在通过动作里按「单据 + 节点」查附件，查不到会直接拒绝 */
      for (let vi = 0; vi < approvalFiles.value.length; vi++){
        await api.uploadAttachment(approvalFiles.value[vi], {
          documentId: selected.value.docId,
          nodeKey: selected.value.viewingNodeKey,
          bizType: voucherBizType(selected.value.current)
        });
      }
      approvalFiles.value = [];
      await api.approve({taskId:selected.value.pendingTaskId, action:'approve', comment:approvalComment.value || '同意'});"""
)

# --- 7. setup 返回值补上新暴露的名字 ---------------------------------------
add(
    '7', 'setup 返回值暴露附件相关方法',
    "    roleTreeData, allFiles, canApprove, canReject, canWithdraw, loading, detailLoading, totalDocs,",
    """    roleTreeData, allFiles, canApprove, canReject, canWithdraw, loading, detailLoading, totalDocs,
    applyFiles, approvalFiles, onApplyFileChange, onApplyFileRemove, onVoucherChange,
    downloadAttachment, previewAttachment, removeAttachment, canDeleteFile,"""
)

# --- 8. 提交流程：先建草稿、再传附件、最后提交 -----------------------------
add(
    '8a', '提交时草稿只建一次（ensureDraft）',
    "      const created = await api.createDoc({",
    """      const created = await ensureDraft(function(){ return api.createDoc({"""
)

add(
    '8b', '提交前上传已选附件，失败则中止提交',
    """        priority: 0
      });
      await api.submitDoc(created.id);""",
    """        priority: 0
      }); });
      /* 附件必须在 submit 之前挂上去：attachment.document_id 指向真实单据，
         而单据一旦进入流程就属于审批留痕，后端不再允许增删附件。
         任一附件失败就中止提交（草稿已被 ensureDraft 记住，重试不会重复建单）。 */
      const failed = [];
      const pendingIdx = Object.keys(applyFiles.value).sort(function(a,b){ return Number(a)-Number(b); });
      for (let pi = 0; pi < pendingIdx.length; pi++){
        const fi = pendingIdx[pi];
        try {
          await api.uploadAttachment(applyFiles.value[fi], {documentId: created.id, bizType: 'apply'});
        } catch(ue){
          failed.push((attachmentRules.value[fi] || ('附件' + (Number(fi)+1))) + '：' + ue.message);
        }
      }
      if (failed.length){
        throw new Error('附件上传失败，单据未提交（草稿已保存，修正后可再次提交）—— ' + failed.join('；'));
      }
      await api.submitDoc(created.id);
      applyFiles.value = {};
      submitForm.draftId = null;"""
)

# --- 9. 新增 ensureDraft；打开弹窗时重置草稿与附件暂存 ---------------------
add(
    '9a', '新增 ensureDraft：附件失败重试时复用同一张草稿',
    "  const submitRequest = async function(){",
    """  /* 提交过程中若附件上传失败会中止，而此时草稿已经建好了。
     记住它，用户重试时直接复用，否则每点一次「提交审批」就会多出一张草稿单。 */
  const ensureDraft = async function(build){
    if (submitForm.draftId){
      return {id: submitForm.draftId, docNo: submitForm.draftNo};
    }
    const created = await build();
    submitForm.draftId = created.id;
    submitForm.draftNo = created.docNo;
    return created;
  };

  const submitRequest = async function(){"""
)

add(
    '9b', '打开提交弹窗时重置草稿与附件暂存',
    """    Object.assign(submitForm, {
      code: makeCode(), type: realName, typeId: t ? t.id : null,""",
    """    submitForm.draftId = null; submitForm.draftNo = null; applyFiles.value = {};
    Object.assign(submitForm, {
      code: makeCode(), type: realName, typeId: t ? t.id : null,"""
)

# --- 10. 提交弹窗关闭即销毁，避免 el-upload 残留上一轮选中的文件 -----------
add(
    '10', '提交弹窗加 destroy-on-close，清掉 el-upload 内部文件列表',
    """<el-dialog v-model="submitVisible" :title="submitForm.type" width="760px">""",
    """<el-dialog v-model="submitVisible" :title="submitForm.type" width="760px" destroy-on-close>"""
)


# ---------------------------------------------------------------------------
def main():
    with open(TARGET, encoding='utf-8') as fp:
        src = fp.read()

    before_len = len(src)
    out = src
    report = []
    missed = []

    for tag, desc, old, new in PATCHES:
        hits = out.count(old)
        if hits == 0 and out.count(new) > 0:
            report.append((tag, desc, 'done', '已应用过，跳过'))
            continue
        if hits != 1:
            missed.append((tag, desc, hits))
            continue
        out = out.replace(old, new, 1)
        report.append((tag, desc, 'ok', '命中 1 处'))

    print('=' * 74)
    for tag, desc, status, note in report:
        print('  [%-7s] %-45s %s' % (status, desc, note))
    if missed:
        print('-' * 74)
        print('  以下锚点未命中，本次不写入任何改动：')
        for tag, desc, hits in missed:
            print('  [MISS   ] 补丁 %s 命中 %d 处 —— %s' % (tag, hits, desc))
        print('=' * 74)
        print('  结果：未修改文件（char %d）' % before_len)
        sys.exit(1)

    print('=' * 74)
    if len(out) == before_len:
        print('  结果：无需改动（全部补丁此前已应用）')
    else:
        with open(TARGET, 'w', encoding='utf-8') as fp:
            fp.write(out)
        print('  结果：已写入 %s' % os.path.basename(TARGET))
        print('  char %d → %d' % (before_len, len(out)))
    print('=' * 74)


if __name__ == '__main__':
    main()

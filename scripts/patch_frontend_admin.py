# -*- coding: utf-8 -*-
"""
把联调版前端的管理端桩函数替换为真实后端调用。

背景：三块管理页（人员/角色/流程）的 UI 早就做好了，保存按钮一直是
notImplemented 桩，因为它们等的写接口此前不存在。后端补齐后，这里做对接。

脚本用「短且唯一」的锚点做替换，每处替换都校验命中数，避免把长压缩行改错。
"""
import io
import sys

PATH = '/Users/zhouzewei/WorkBuddy/2026-09-18-15-53-12/海峡金OA审批系统-联调版.html'

s = io.open(PATH, encoding='utf-8').read()
before_len = len(s)

REPLACEMENTS = []


def rep(name, old, new):
    REPLACEMENTS.append((name, old, new))


# ---------------------------------------------------------------- 1. API 层
rep(
    'api-新增管理端接口',
    r"""  permissions:  function(){ return http('/api/auth/permissions'); }
};""",
    r"""  permissions:  function(){ return http('/api/auth/permissions'); },

  /* ---- 管理端接口（本轮补齐：此前前端只有读接口，写操作是桩）---- */
  allPermissions: function(){ return http('/api/permissions'); },
  nodeTemplates:  function(){ return http('/api/flows/node-templates'); },

  createUser:   function(body){ return http('/api/users', {method:'POST', body:body}); },
  updateUser:   function(id, body){ return http('/api/users/' + id, {method:'PUT', body:body}); },
  setUserRoles: function(id, roleCodes){ return http('/api/users/' + id + '/roles', {method:'PUT', body:{roleCodes:roleCodes}}); },
  deleteUser:   function(id){ return http('/api/users/' + id, {method:'DELETE'}); },

  createRole:   function(body){ return http('/api/roles', {method:'POST', body:body}); },
  updateRole:   function(id, body){ return http('/api/roles/' + id, {method:'PUT', body:body}); },
  setRolePerms: function(id, permCodes){ return http('/api/roles/' + id + '/permissions', {method:'PUT', body:{permCodes:permCodes}}); },
  setRoleScope: function(id, scopeType, deptIds){ return http('/api/roles/' + id + '/data-scope', {method:'PUT', body:{scopeType:scopeType, scopeDeptIds:deptIds||[]}}); },
  deleteRole:   function(id){ return http('/api/roles/' + id, {method:'DELETE'}); },

  createFlow:   function(body){ return http('/api/flows/configs', {method:'POST', body:body}); },
  updateFlow:   function(id, body){ return http('/api/flows/configs/' + id, {method:'PUT', body:body}); },
  retireFlow:   function(id){ return http('/api/flows/configs/' + id, {method:'DELETE'}); }
};""",
)

# ---------------------------------------------------------------- 2. 状态模型
rep(
    'newPerson 模型 + 新增管理端状态',
    r"""const newPerson = reactive({name:'', jobNo:'', account:'', password:'', department:'', post:'', position:'普通员工', permissions:[]});""",
    r"""const newPerson = reactive({name:'', jobNo:'', account:'', password:'', deptId:null, post:'', roleCode:''});
  /* ---- 管理端状态（本轮新增）---- */
  const editingPerson = ref(null);        // 正在编辑的员工；null 表示新建
  const personSaving = ref(false);        // 提交中，用于按钮 loading 防重复点击
  const allPermissions = ref([]);         // 全量权限点（GET /api/permissions）
  const nodeTemplateList = ref([]);       // 流程节点模板库（含各自的审批人规则说明）
  const roleAssignVisible = ref(false);
  const roleAssignTarget = ref(null);
  const roleAssignCodes = ref([]);
  /* 数据范围用后端编码，避免前端再维护一份中文映射 */
  const scopeOptions = [
    {value:'self', label:'本人单据'},
    {value:'dept', label:'本部门单据'},
    {value:'center', label:'本中心单据'},
    {value:'custom_dept', label:'指定部门单据'},
    {value:'company', label:'全公司单据'}
  ];
  const deptOptions = computed(function(){ return deptFlat.value.map(function(d){ return {id:d.id, name:d.name}; }); });""",
)

# ---------------------------------------------------------------- 3. toUser
rep(
    'toUser 用后端下发的部门名/岗位名/角色',
    r"""  const toUser = function(u){
    return {id:u.id, name:u.realName, jobNo:u.jobNo, account:u.account,
            department:deptNameById(u.deptId), deptId:u.deptId, postId:u.postId,
            post:'', position:'', permissions:[], status:u.status, lastLoginAt:u.lastLoginAt};
  };""",
    r"""  const toUser = function(u){
    return {id:u.id, name:u.realName, jobNo:u.jobNo, account:u.account,
            department:u.deptName || deptNameById(u.deptId), deptId:u.deptId, postId:u.postId,
            post:u.postName || '',
            position:(u.roleNames && u.roleNames.length) ? u.roleNames.join('、') : '—',
            roleCodes:u.roleCodes || [], roleNames:u.roleNames || [],
            status:u.status, lastLoginAt:u.lastLoginAt};
  };""",
)

# ---------------------------------------------------------------- 4. loadBase
rep(
    'loadBase 加载权限点与节点模板，角色映射取真实字段',
    r"""      api.dicts().catch(function(){ return {}; })
    ]);
    documentTypes.value = results[0] || [];
    allUsers.value = results[1] || [];
    allRoles.value = results[2] || [];
    deptFlat.value = flattenDept(results[3] || []);
    Object.assign(dictMap, results[4] || {});
    departments.value = deptFlat.value.map(function(d){ return d.name; });
    people.value = allUsers.value.map(toUser);
    roleConfigs.value = allRoles.value.map(function(r){
      return {code:r.code, name:r.name, department:deptNameById(r.deptId) || '全公司',
              post:r.postName || '', scope:r.remark || '（后端未下发行级范围字段）',
              members:[], permissions:[], isBuiltin:r.isBuiltin === 1};
    });""",
    r"""      api.dicts().catch(function(){ return {}; }),
      api.allPermissions().catch(function(){ return []; }),
      api.nodeTemplates().catch(function(){ return []; })
    ]);
    documentTypes.value = results[0] || [];
    allUsers.value = results[1] || [];
    allRoles.value = results[2] || [];
    deptFlat.value = flattenDept(results[3] || []);
    Object.assign(dictMap, results[4] || {});
    allPermissions.value = results[5] || [];
    nodeTemplateList.value = results[6] || [];
    departments.value = deptFlat.value.map(function(d){ return d.name; });
    people.value = allUsers.value.map(toUser);
    roleConfigs.value = allRoles.value.map(function(r){
      return {id:r.id, code:r.code, name:r.name,
              department:r.deptName || deptNameById(r.deptId) || '全公司',
              deptId:r.deptId, post:r.postName || '',
              scopeType:r.scopeType || 'self', scope:r.scopeLabel || '本人单据',
              scopeDeptIds:r.scopeDeptIds || [],
              members:r.members || [],
              permCodes:r.permCodes || [], permissions:r.permNames || [],
              isBuiltin:r.isBuiltin === 1};
    });""",
)

# ---------------------------------------------------------------- 5. 人员弹窗
rep(
    '人员弹窗-部门下拉改用 deptId',
    r"""v-model="newPerson.department"><el-option v-for="d in departments" :label="d" :value="d"></el-option></el-select>""",
    r"""v-model="newPerson.deptId" placeholder="请选择部门" style="width:100%"><el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id"></el-option></el-select>""",
)
rep(
    '人员弹窗-角色下拉改用 roleCode',
    r"""v-model="newPerson.position"><el-option v-for="r in roleConfigs" :label="r.name" :value="r.name"></el-option></el-select>""",
    r"""v-model="newPerson.roleCode" placeholder="请选择角色" style="width:100%"><el-option v-for="r in roleConfigs" :key="r.code" :label="r.name" :value="r.code"></el-option></el-select>""",
)
rep(
    '人员弹窗-密码占位与底部按钮',
    r"""<el-form-item label="登录密码"><el-input v-model="newPerson.password" type="password" show-password autocomplete="new-password" placeholder="请设置初始密码"></el-input></el-form-item><el-form-item label="所属部门">""",
    r"""<el-form-item label="初始密码"><el-input v-model="newPerson.password" type="password" show-password autocomplete="new-password" :placeholder="editingPerson?'留空表示不修改密码':'请设置初始密码'"></el-input></el-form-item><el-form-item label="所属部门">""",
)
rep(
    '人员弹窗-底部按钮支持编辑态',
    r"""<el-button @click="personVisible=false">取消</el-button><el-button type="primary" @click="addPerson">保存员工</el-button>""",
    r"""<el-button @click="closePerson">取消</el-button><el-button type="primary" :loading="personSaving" @click="addPerson">{{editingPerson?'保存修改':'保存员工'}}</el-button>""",
)
rep(
    '修掉人员弹窗登录账号/密码字段重复注入',
    r"""const personDialog=document.querySelector('el-dialog[title="增加人员与审批权限"]');personDialog?.querySelector('.form2')?.insertAdjacentHTML('beforeend',`<el-form-item label="登录账号"><el-input v-model="newPerson.account" autocomplete="off" placeholder="请输入员工登录账号"></el-input></el-form-item><el-form-item label="登录密码"><el-input v-model="newPerson.password" type="password" show-password autocomplete="new-password" placeholder="请设置初始密码"></el-input></el-form-item>`);""",
    r"""/* 登录账号/初始密码字段已由 personSetupDialog 的完整 innerHTML 重写覆盖，
     此处不再二次追加（原实现会让这两个字段在弹窗里各出现两遍）。 */""",
)

# ---------------------------------------------------------------- 6. 人员表格操作列
rep(
    '人员表格-操作列按钮接真实事件',
    r"""<el-table-column label="操作" min-width="120"><template #default><el-button link type="primary">编辑员工</el-button><el-button link>调整角色</el-button></template></el-table-column>""",
    r"""<el-table-column label="操作" min-width="200"><template #default="s"><el-button link type="primary" @click="editPerson(s.row)">编辑员工</el-button><el-button link @click="adjustRole(s.row)">调整角色</el-button><el-button link type="danger" @click="deletePerson(s.row)">删除</el-button></template></el-table-column>""",
)

# ---------------------------------------------------------------- 7. 角色弹窗
rep(
    '角色弹窗-部门下拉改用 deptId',
    r"""v-model="editingRole.department" style="width:100%"><el-option v-for="d in departments" :label="d" :value="d"></el-option></el-select>""",
    r"""v-model="editingRole.deptId" clearable placeholder="不选=全公司" style="width:100%"><el-option v-for="d in deptOptions" :key="d.id" :label="d.name" :value="d.id"></el-option></el-select>""",
)
rep(
    '角色弹窗-数据范围改用后端编码',
    r"""v-model="editingRole.scope" style="width:100%"><el-option v-for="s in ['本人单据','本部门单据','全公司财务单据','全公司业务单据','全部部门与全部节点']" :label="s" :value="s"></el-option></el-select>""",
    r"""v-model="editingRole.scopeType" style="width:100%"><el-option v-for="s in scopeOptions" :key="s.value" :label="s.label" :value="s.value"></el-option></el-select>""",
)
rep(
    '角色弹窗-权限勾选改用后端全量权限点',
    r"""<el-form-item label="权限配置"><el-checkbox-group v-model="editingRole.permissions"><el-checkbox v-for="p in ['查看本人表单','查看本部门表单','查看全部表单','提交全部表单','部门负责人审批','核算会计审批','内控合规审批','出纳审批','审批全部节点','上传审批凭证','配置流程与权限']" :label="p">{{p}}</el-checkbox></el-checkbox-group></el-form-item>""",
    r"""<el-form-item label="权限配置（来自后端权限点目录；保存后该角色成员需重新登录才生效）"><el-checkbox-group v-model="editingRole.permCodes"><el-checkbox v-for="p in allPermissions" :key="p.code" :label="p.code">{{p.name}}</el-checkbox></el-checkbox-group></el-form-item>""",
)
rep(
    '角色弹窗-底部按钮 + 新增「调整员工角色」弹窗',
    r"""<el-button @click="roleVisible=false">取消</el-button><el-button type="primary" @click="saveRole">保存角色</el-button></template></el-dialog>`);""",
    r"""<el-button v-if="!editingRole?.isNew" type="danger" plain @click="removeRole">删除角色</el-button><el-button @click="roleVisible=false">取消</el-button><el-button type="primary" :loading="personSaving" @click="saveRole">保存角色</el-button></template></el-dialog>`);
/* 调整员工角色（行内入口） */
document.querySelector('#app').insertAdjacentHTML('beforeend',`<el-dialog v-model="roleAssignVisible" :title="'调整角色：'+(roleAssignTarget?.name||'')" width="520px" append-to-body><el-form label-position="top"><el-form-item label="分配角色（可多选）"><el-select v-model="roleAssignCodes" multiple placeholder="请选择角色" style="width:100%"><el-option v-for="r in roleConfigs" :key="r.code" :label="r.name" :value="r.code"></el-option></el-select></el-form-item></el-form><template #footer><el-button @click="roleAssignVisible=false">取消</el-button><el-button type="primary" :loading="personSaving" @click="confirmAssignRoles">保存</el-button></template></el-dialog>`);""",
)

# ---------------------------------------------------------------- 8. 流程弹窗
rep(
    '流程弹窗-流程分类换成关联单据类型',
    r"""<el-form-item label="流程分类"><el-select v-model="editingFlow.category"><el-option label="日常审批" value="日常审批"></el-option><el-option label="业务单据" value="业务单据"></el-option></el-select></el-form-item>""",
    r"""<el-form-item label="关联单据类型"><el-select v-model="editingFlow.docTypeId" placeholder="该流程服务的单据类型" style="width:100%"><el-option v-for="d in documentTypes" :key="d.id" :label="d.name" :value="d.id"></el-option></el-select></el-form-item>""",
)
rep(
    '流程弹窗-节点下拉改用后端模板库',
    r"""v-model="editingFlow.nodes" multiple filterable allow-create default-first-option style="width:100%"><el-option v-for="n in ['发起人','部门负责人','核算会计','内控合规/公司负责人','总经办','公司章管理人','出纳','发起人回传盖章文件']" :key="n" :label="n" :value="n"></el-option></el-select>""",
    r"""v-model="editingFlow.nodes" multiple filterable allow-create default-first-option style="width:100%" placeholder="按审批顺序选择节点"><el-option v-for="n in nodeTemplateList" :key="n.name" :label="n.name" :value="n.name"><span>{{n.name}}</span><span style="float:right;color:#909399;font-size:12px;margin-left:16px">{{n.ruleLabel}}</span></el-option></el-select>""",
)
rep(
    '流程弹窗-节点下方补充版本影响说明',
    r"""<span style="float:right;color:#909399;font-size:12px;margin-left:16px">{{n.ruleLabel}}</span></el-option></el-select></el-form-item>""",
    r"""<span style="float:right;color:#909399;font-size:12px;margin-left:16px">{{n.ruleLabel}}</span></el-option></el-select><el-alert title="保存后会生成新版本并自动部署；已在审批中的单据仍走原版本，不受影响。" type="info" :closable="false" style="margin-top:10px"></el-alert></el-form-item>""",
)
rep(
    '流程弹窗-底部按钮',
    r"""<el-button @click="flowVisible=false">取消</el-button><el-button type="primary" @click="saveFlow">保存流程</el-button>""",
    r"""<el-button v-if="!editingFlow?.isNew" type="danger" plain @click="removeFlow">废弃流程</el-button><el-button @click="flowVisible=false">取消</el-button><el-button type="primary" :loading="personSaving" @click="saveFlow">保存并部署</el-button>""",
)
rep(
    '流程弹窗-补一条影响说明',
    r"""<el-form-item label="审批节点（按选择顺序排列，可输入新节点）"><el-select""",
    r"""<el-form-item label="审批节点（按审批顺序选择，可输入新节点）"><el-select""",
)

# ---------------------------------------------------------------- 9. 写操作实现
rep(
    '桩函数 -> 真实管理端调用',
    r"""  const addPerson = function(){ notImplemented('新建员工（含账号、密码、岗位与角色分配）', 'POST /api/users（密码加密 + 角色绑定）'); };
  const saveFlow  = function(){ notImplemented('新增/修改流程配置并重新部署 BPMN', 'POST /api/flows/configs、PUT /api/flows/configs/{id}、POST /api/flows/configs/{id}/deploy'); };
  const saveRole  = function(){ notImplemented('新增角色、配置角色权限与数据范围', 'POST /api/roles、PUT /api/roles/{id}/permissions、PUT /api/roles/{id}/data-scope'); };""",
    r"""  /* ---------- 管理端写操作：已接入真实后端 ---------- */

  /** 统一包装：后端返回的 400/403 文案原样透出，前端不自己编错误信息 */
  const adminCall = async function(fn, okMsg){
    personSaving.value = true;
    try {
      const r = await fn();
      if (okMsg) ElementPlus.ElMessage.success(okMsg);
      return r;
    } catch (e){
      ElementPlus.ElMessage.error(e.message || '操作失败');
      throw e;
    } finally {
      personSaving.value = false;
    }
  };

  const resetPerson = function(){
    editingPerson.value = null;
    newPerson.name=''; newPerson.jobNo=''; newPerson.account=''; newPerson.password='';
    newPerson.deptId=null; newPerson.post=''; newPerson.roleCode='';
  };
  const editPerson = function(row){
    editingPerson.value = row;
    newPerson.name=row.name; newPerson.jobNo=row.jobNo; newPerson.account=row.account;
    newPerson.password=''; newPerson.deptId=row.deptId||null;
    newPerson.post=row.post||'';
    newPerson.roleCode=(row.roleCodes && row.roleCodes[0]) || '';
    personVisible.value = true;
  };
  const closePerson = function(){ personVisible.value=false; resetPerson(); };

  const addPerson = async function(){
    if (!newPerson.name || !newPerson.jobNo || !newPerson.account){
      ElementPlus.ElMessage.warning('姓名、工号、登录账号均为必填'); return;
    }
    if (!editingPerson.value && !newPerson.password){
      ElementPlus.ElMessage.warning('新建员工必须设置初始密码'); return;
    }
    if (!newPerson.deptId){ ElementPlus.ElMessage.warning('请选择所属部门'); return; }
    const body = {
      realName:newPerson.name, jobNo:newPerson.jobNo, account:newPerson.account,
      deptId:newPerson.deptId, postName:newPerson.post || null,
      roleCodes:newPerson.roleCode ? [newPerson.roleCode] : []
    };
    if (newPerson.password) body.password = newPerson.password;
    try {
      if (editingPerson.value){
        await adminCall(function(){ return api.updateUser(editingPerson.value.id, body); }, '员工信息已更新');
      } else {
        await adminCall(function(){ return api.createUser(body); }, '员工已创建，可用该账号登录');
      }
      personVisible.value = false; resetPerson();
      await loadBase();
    } catch(e){ /* 已在 adminCall 提示 */ }
  };

  const deletePerson = async function(row){
    try {
      await ElementPlus.ElMessageBox.confirm(
        '确认删除员工「' + row.name + '（' + row.account + '）」？删除后该账号将无法登录。',
        '删除员工', {type:'warning', confirmButtonText:'确认删除', cancelButtonText:'取消'});
    } catch(e){ return; }
    try {
      await adminCall(function(){ return api.deleteUser(row.id); }, '员工已删除');
      await loadBase();
    } catch(e){}
  };

  const adjustRole = function(row){
    roleAssignTarget.value = row;
    roleAssignCodes.value = (row.roleCodes || []).slice();
    roleAssignVisible.value = true;
  };
  const confirmAssignRoles = async function(){
    try {
      await adminCall(function(){ return api.setUserRoles(roleAssignTarget.value.id, roleAssignCodes.value); }, '角色已调整');
      roleAssignVisible.value = false;
      await loadBase();
    } catch(e){}
  };

  const saveRole = async function(){
    const r = editingRole.value;
    if (!r.name){ ElementPlus.ElMessage.warning('角色名称不能为空'); return; }
    try {
      if (r.isNew){
        await adminCall(function(){
          return api.createRole({name:r.name, deptId:r.deptId||null, postName:r.post||null,
                                 scopeType:r.scopeType||'self', permCodes:r.permCodes||[]});
        }, '角色已创建');
      } else {
        await adminCall(function(){
          return api.updateRole(r.id, {name:r.name, deptId:r.deptId||null, postName:r.post||null});
        }, null);
        await adminCall(function(){ return api.setRolePerms(r.id, r.permCodes||[]); }, null);
        await adminCall(function(){ return api.setRoleScope(r.id, r.scopeType||'self', r.scopeDeptIds||[]); },
          '已保存。该角色下已登录的成员需重新登录后权限才会生效');
      }
      roleVisible.value = false;
      await loadBase();
    } catch(e){}
  };

  const removeRole = async function(){
    const r = editingRole.value;
    try {
      await ElementPlus.ElMessageBox.confirm('确认删除角色「' + r.name + '」？', '删除角色',
        {type:'warning', confirmButtonText:'确认删除', cancelButtonText:'取消'});
    } catch(e){ return; }
    try {
      await adminCall(function(){ return api.deleteRole(r.id); }, '角色已删除');
      roleVisible.value = false;
      await loadBase();
    } catch(e){}
  };

  const saveFlow = async function(){
    const f = editingFlow.value;
    if (!f.type){ ElementPlus.ElMessage.warning('流程名称不能为空'); return; }
    if (!f.docTypeId){ ElementPlus.ElMessage.warning('请选择该流程关联的单据类型'); return; }
    if (!f.nodes || f.nodes.length < 2){
      ElementPlus.ElMessage.warning('审批节点至少两个（含发起节点）'); return;
    }
    const body = {name:f.type, docTypeId:f.docTypeId, nodes:f.nodes};
    try {
      if (f.isNew){
        await adminCall(function(){ return api.createFlow(body); }, '流程已创建并部署');
      } else {
        await adminCall(function(){ return api.updateFlow(f.id, body); }, '流程已保存为新版本并部署');
      }
      flowVisible.value = false;
      await loadBase();
    } catch(e){}
  };

  const removeFlow = async function(){
    const f = editingFlow.value;
    try {
      await ElementPlus.ElMessageBox.confirm(
        '确认废弃流程「' + f.type + '」？若仍有单据在审批中，系统会拒绝该操作。', '废弃流程',
        {type:'warning', confirmButtonText:'确认废弃', cancelButtonText:'取消'});
    } catch(e){ return; }
    try {
      await adminCall(function(){ return api.retireFlow(f.id); }, '流程已废弃');
      flowVisible.value = false;
      await loadBase();
    } catch(e){}
  };""",
)

rep(
    '编辑/新建弹窗的初始模型',
    r"""  const editFlow = function(flow){ editingFlow.value = Object.assign({}, flow, {nodes:(flow.nodes||[]).slice(), isNew:false}); flowVisible.value = true; };
  const newFlow  = function(){ editingFlow.value = {type:'', category:'日常审批', nodes:['发起人'], isNew:true}; flowVisible.value = true; };
  const editRole = function(item){
    editingRole.value = Object.assign({}, item, {permissions:(item.permissions||[]).slice(), isNew:false});
    roleVisible.value = true;
  };
  const newRole  = function(){
    editingRole.value = {name:'', department:'', post:'', scope:'', permissions:[], isNew:true};
    roleVisible.value = true;
  };""",
    r"""  const editFlow = function(flow){
    editingFlow.value = Object.assign({}, flow, {
      nodes:(flow.nodes||[]).slice(),
      docTypeId: flow.docTypeId || (documentTypes.value[0]||{}).id,
      isNew:false
    });
    flowVisible.value = true;
  };
  const newFlow  = function(){
    editingFlow.value = {type:'', nodes:['发起人'], docTypeId:(documentTypes.value[0]||{}).id, isNew:true};
    flowVisible.value = true;
  };
  const editRole = function(item){
    editingRole.value = Object.assign({}, item, {
      permCodes:(item.permCodes||[]).slice(),
      scopeDeptIds:(item.scopeDeptIds||[]).slice(),
      isNew:false
    });
    roleVisible.value = true;
  };
  const newRole  = function(){
    editingRole.value = {name:'', code:'', deptId:null, post:'', scopeType:'self',
                         permCodes:[], scopeDeptIds:[], isNew:true};
    roleVisible.value = true;
  };""",
)

# ---------------------------------------------------------------- 10. 模板暴露
rep(
    'setup 返回值补充新变量与函数',
    r"""    submitForm, attachmentRules, people, newPerson, roleConfigs, flowConfigs,""",
    r"""    submitForm, attachmentRules, people, newPerson, roleConfigs, flowConfigs,
    allPermissions, nodeTemplateList, deptOptions, scopeOptions,
    editingPerson, personSaving, roleAssignVisible, roleAssignTarget, roleAssignCodes,
    editPerson, closePerson, adjustPerson:addPerson, addPerson, adjustRole, confirmAssignRoles,
    deletePerson, removeRole, removeFlow,""",
)

# ---------------------------------------------------------------- 执行
failed = []
for name, old, new in REPLACEMENTS:
    cnt = s.count(old)
    if cnt == 0:
        failed.append(name)
        print('[MISS] %s' % name)
        continue
    s = s.replace(old, new, 1)
    print('[ OK ] %s (命中 %d 处，替换 1 处)' % (name, cnt))

if failed:
    print('\n!! 以下替换未命中锚点，文件未写入：')
    for f in failed:
        print('   -', f)
    sys.exit(1)

io.open(PATH, 'w', encoding='utf-8').write(s)
print('\n写入完成：%d -> %d 字符' % (before_len, len(s)))

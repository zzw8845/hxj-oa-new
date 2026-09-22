-- =============================================================================
-- 海峡金 OA 审批系统 — 初始化种子数据
-- 版本：2026-09-18
-- 范围：首批 3 种单据闭环（日常付款 / 员工报销 / 用印申请）
-- 说明：所有账号初始密码均为 123456（BCrypt）
-- 用法：mysql -uroot haixiajin_oa < seed_data.sql
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE `user_role`;
TRUNCATE TABLE `role_permission`;
TRUNCATE TABLE `role_data_scope`;
TRUNCATE TABLE `flow_node_assignee`;
TRUNCATE TABLE `flow_config_node`;
TRUNCATE TABLE `flow_config`;
TRUNCATE TABLE `form_field_permission`;
TRUNCATE TABLE `form_template`;
TRUNCATE TABLE `document_type`;
TRUNCATE TABLE `user_post`;
TRUNCATE TABLE `sys_user`;
TRUNCATE TABLE `sys_role`;
TRUNCATE TABLE `sys_permission`;
TRUNCATE TABLE `sys_dict`;
TRUNCATE TABLE `post`;
TRUNCATE TABLE `department`;
TRUNCATE TABLE `company`;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- 1. 公司
-- ---------------------------------------------------------------------------
INSERT INTO `company` (`id`,`code`,`name`,`short_name`,`status`,`sort_no`) VALUES
(1,'HXJ','福建海峡金投资有限公司','海峡金',1,10);

-- ---------------------------------------------------------------------------
-- 2. 部门（中心 → 二级部门，path 物化路径）
-- ---------------------------------------------------------------------------
INSERT INTO `department` (`id`,`company_id`,`parent_id`,`code`,`name`,`dept_type`,`level`,`path`,`leader_id`,`sort_no`) VALUES
(1,1,0,'D001','总经理室',           1,1,'/1/', 2,10),
(2,1,0,'D002','财务中心',           1,1,'/2/', 3,20),
(3,1,2,'D003','财务核算部',         2,2,'/2/3/', 3,21),
(4,1,2,'D004','资金结算部',         2,2,'/2/4/', 5,22),
(5,1,0,'D005','内控合规中心',       1,1,'/5/', 6,30),
(6,1,0,'D006','综合管理中心',       1,1,'/6/', 8,40),
(7,1,6,'D007','行政管理部',         2,2,'/6/7/', 8,41),
(8,1,0,'D008','业务一部',           1,1,'/8/', 7,50);

-- ---------------------------------------------------------------------------
-- 3. 岗位
-- ---------------------------------------------------------------------------
INSERT INTO `post` (`id`,`company_id`,`code`,`name`,`sort_no`) VALUES
(1,1,'P_GM',         '总经理',        10),
(2,1,'P_FIN_DIR',    '财务总监',      20),
(3,1,'P_ACCOUNTANT', '核算会计',      30),
(4,1,'P_CASHIER',    '出纳',          40),
(5,1,'P_INTERNAL',   '内控专员',      50),
(6,1,'P_DEPT_HEAD',  '部门负责人',    60),
(7,1,'P_STAFF',      '普通员工',      70),
(8,1,'P_ADMIN',      '行政专员',      80);

-- ---------------------------------------------------------------------------
-- 4. 用户（密码统一 123456）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_user` (`id`,`company_id`,`job_no`,`account`,`password`,`real_name`,`phone`,`email`,`dept_id`,`post_id`,`status`,`pwd_reset_flag`) VALUES
(1,1,'HXJ001','admin',    '$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','系统管理员','13800000001','admin@hxj.com',   6,1,1,0),
(2,1,'HXJ002','zhangzong','$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','张总',      '13800000002','gm@hxj.com',      1,1,1,0),
(3,1,'HXJ003','lifinance','$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','李财务',    '13800000003','fd@hxj.com',      2,2,1,0),
(4,1,'HXJ004','wangkj',   '$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','王会计',    '13800000004','kj@hxj.com',      3,3,1,0),
(5,1,'HXJ005','zhaocs',   '$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','赵出纳',    '13800000005','cs@hxj.com',      4,4,1,0),
(6,1,'HXJ006','chennk',   '$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','陈内控',    '13800000006','nk@hxj.com',      5,5,1,0),
(7,1,'HXJ007','linjl',    '$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','林经理',    '13800000007','jl@hxj.com',      8,6,1,0),
(8,1,'HXJ008','zhouzh',   '$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','周综合',    '13800000008','zh@hxj.com',      6,6,1,0),
(9,1,'HXJ009','huangxm',  '$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','黄小明',    '13800000009','hxm@hxj.com',     8,7,1,0);

INSERT INTO `user_post` (`user_id`,`post_id`,`dept_id`,`is_primary`) VALUES
(1,1,6,1),(2,1,1,1),(3,2,2,1),(4,3,3,1),(5,4,4,1),(6,5,5,1),(7,6,8,1),(8,6,6,1),(9,7,8,1);

-- ---------------------------------------------------------------------------
-- 5. 角色（内置）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role` (`id`,`company_id`,`code`,`name`,`dept_id`,`post_name`,`is_builtin`,`remark`) VALUES
(1,1,'ADMIN',         '超级管理员',   NULL,'系统管理员',1,'拥有全部权限'),
(2,1,'GM',            '公司领导',     1,  '总经理',    1,'大额审批节点'),
(3,1,'FIN_DIRECTOR',  '财务总监',     2,  '财务总监',  1,'财务终审'),
(4,1,'ACCOUNTANT',    '核算会计',     3,  '核算会计',  1,'按部门分派核算'),
(5,1,'CASHIER',       '出纳',         4,  '出纳',      1,'付款/回单办理'),
(6,1,'INTERNAL_CTRL', '内控合规',     5,  '内控专员',  1,'内控核验'),
(7,1,'DEPT_HEAD',     '部门负责人',   NULL,'部门负责人',1,'本部门审批'),
(8,1,'EMPLOYEE',      '普通员工',     NULL,'普通员工',  1,'发起单据、查看本人');

-- ---------------------------------------------------------------------------
-- 6. 权限点（稳定英文 code，中文仅作展示）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission` (`code`,`name`,`perm_type`,`parent_code`,`sort_no`) VALUES
('document:menu',           '单据中心',       1, NULL,              10),
('todo:menu',               '我的待办',       1, NULL,              20),
('ledger:menu',             '表单台账',       1, NULL,              30),
('dashboard:menu',          '数据看板',       1, NULL,              40),
('admin:menu',              '系统管理',       1, NULL,              90),

('document:create',         '发起单据',       2, 'document:menu',   11),
('document:view:self',      '查看本人单据',   3, 'document:menu',   12),
('document:view:dept',      '查看本部门单据', 3, 'document:menu',   13),
('document:view:company',   '查看全公司单据', 3, 'document:menu',   14),
('document:export',         '导出台账',       2, 'ledger:menu',     31),

('document:approve',        '审批单据',       3, 'todo:menu',       21),
('document:approve:leader', '领导审批',       3, 'todo:menu',       22),
('document:approve:accountant','会计审批',     3, 'todo:menu',       23),
('document:approve:cashier','出纳付款办理',   3, 'todo:menu',       24),
('document:approve:seal',   '用印办理',       3, 'todo:menu',       25),
('document:supplement',     '要求补充材料',   2, 'todo:menu',       26),
('document:countersign',    '加签',           2, 'todo:menu',       27),

('system:user',             '用户管理',       2, 'admin:menu',      91),
('system:role',             '角色管理',       2, 'admin:menu',      92),
('system:dept',             '部门管理',       2, 'admin:menu',      93),
('system:flow',             '流程配置',       2, 'admin:menu',      94),
('system:form',             '表单配置',       2, 'admin:menu',      95),
('system:dict',             '字典管理',       2, 'admin:menu',      96),
('system:audit',            '审计日志',       2, 'admin:menu',      97),
('system:docType',          '单据类型管理',   2, 'admin:menu',      98);

-- ---------------------------------------------------------------------------
-- 7. 角色-权限
-- ---------------------------------------------------------------------------
-- ADMIN：全部
INSERT INTO `role_permission` (`role_id`,`perm_code`)
SELECT 1, `code` FROM `sys_permission` WHERE `deleted`=0;

-- GM 公司领导
INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
(2,'document:menu'),(2,'todo:menu'),(2,'ledger:menu'),(2,'dashboard:menu'),
(2,'document:view:company'),(2,'document:export'),(2,'document:approve'),(2,'document:approve:leader');

-- FIN_DIRECTOR 财务总监
INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
(3,'document:menu'),(3,'todo:menu'),(3,'ledger:menu'),(3,'dashboard:menu'),
(3,'document:view:company'),(3,'document:export'),(3,'document:approve'),(3,'document:approve:leader'),
(3,'document:approve:accountant');

-- ACCOUNTANT 核算会计
INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
(4,'document:menu'),(4,'todo:menu'),(4,'ledger:menu'),
(4,'document:view:company'),(4,'document:export'),(4,'document:approve'),(4,'document:approve:accountant'),
(4,'document:supplement');

-- CASHIER 出纳
INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
(5,'document:menu'),(5,'todo:menu'),(5,'ledger:menu'),
(5,'document:view:company'),(5,'document:approve'),(5,'document:approve:cashier');

-- INTERNAL_CTRL 内控
INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
(6,'document:menu'),(6,'todo:menu'),(6,'ledger:menu'),(6,'dashboard:menu'),
(6,'document:view:company'),(6,'document:approve'),(6,'document:supplement');

-- DEPT_HEAD 部门负责人
INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
(7,'document:menu'),(7,'todo:menu'),(7,'ledger:menu'),
(7,'document:create'),(7,'document:view:self'),(7,'document:view:dept'),
(7,'document:approve'),(7,'document:approve:leader');

-- EMPLOYEE 普通员工
INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
(8,'document:menu'),(8,'todo:menu'),
(8,'document:create'),(8,'document:view:self'),(8,'document:approve');

-- ---------------------------------------------------------------------------
-- 8. 角色数据范围（行级权限，拦截器据此拼 where）
-- ---------------------------------------------------------------------------
INSERT INTO `role_data_scope` (`role_id`,`scope_type`,`company_ids`,`dept_ids`) VALUES
(1,'company',   NULL, NULL),
(2,'company',   NULL, NULL),
(3,'company',   NULL, NULL),
(4,'company',   NULL, NULL),
(5,'company',   NULL, NULL),
(6,'company',   NULL, NULL),
(7,'dept',      NULL, NULL),
(8,'self',      NULL, NULL);

-- ---------------------------------------------------------------------------
-- 9. 用户-角色
-- ---------------------------------------------------------------------------
INSERT INTO `user_role` (`user_id`,`role_id`) VALUES
(1,1),(2,2),(3,3),(4,4),(5,5),(6,6),(7,7),(8,7),(9,8);

-- ---------------------------------------------------------------------------
-- 10. 数据字典
-- ---------------------------------------------------------------------------
INSERT INTO `sys_dict` (`company_id`,`dict_type`,`dict_code`,`dict_label`,`sort_no`) VALUES
(NULL,'seal_type',    'OFFICIAL',  '公章',        10),
(NULL,'seal_type',    'CONTRACT',  '合同章',      20),
(NULL,'seal_type',    'LEGAL',     '法人章',      30),
(NULL,'seal_type',    'FINANCE',   '财务专用章',  40),
(NULL,'seal_type',    'INVOICE',   '发票专用章',  50),
(NULL,'pay_type',     'GOODS',     '货款',        10),
(NULL,'pay_type',     'SERVICE',   '服务费',      20),
(NULL,'pay_type',     'RENT',      '租金',        30),
(NULL,'pay_type',     'OTHER',     '其他',        90),
(NULL,'expense_type', 'TRAVEL',    '差旅费',      10),
(NULL,'expense_type', 'OFFICE',    '办公费',      20),
(NULL,'expense_type', 'ENTERTAIN', '业务招待费',  30),
(NULL,'expense_type', 'TRANSPORT', '交通费',      40),
(NULL,'expense_type', 'OTHER',     '其他',        90),
(NULL,'project',      'HXJ-2026-01','海峡金数字经济产业园一期',10),
(NULL,'project',      'HXJ-2026-02','供应链金融平台建设',      20),
(NULL,'project',      'HXJ-2026-03','日常运营',                90);

-- ---------------------------------------------------------------------------
-- 11. 单据类型（首批 3 种）
-- ---------------------------------------------------------------------------
INSERT INTO `document_type` (`id`,`company_id`,`code`,`name`,`category`,`must_link_prev`,`status`,`sort_no`) VALUES
(1,1,'DAILY_PAYMENT',    '日常付款申请', 'DAILY',     0,1,10),
(2,1,'REIMBURSE_EMPLOYEE','员工报销',    'REIMBURSE', 0,1,20),
(3,1,'SEAL_APPLY',       '用印申请',     'SEAL',      0,1,30);

-- ---------------------------------------------------------------------------
-- 12. 表单模板（动态表单 Schema）
-- ---------------------------------------------------------------------------
-- 12.1 日常付款
INSERT INTO `form_template` (`id`,`company_id`,`doc_type_id`,`name`,`version`,`schema_json`,`status`,`effective_from`) VALUES
(1,1,1,'日常付款申请单',1,'{
  "docType":"DAILY_PAYMENT","layout":"two-column",
  "fields":[
    {"key":"title","label":"申请事项","type":"text","required":true,"maxLength":128,"colSpan":2,"placeholder":"请填写本次付款的申请事项"},
    {"key":"amount","label":"付款金额(元)","type":"money","required":true,"min":0.01,"precision":2,"colSpan":1},
    {"key":"payType","label":"付款类型","type":"select","required":true,"dictType":"pay_type","colSpan":1},
    {"key":"payeeName","label":"收款单位","type":"text","required":true,"colSpan":1},
    {"key":"payeeAccount","label":"收款账号","type":"text","required":true,"rule":"bankAccount","colSpan":1},
    {"key":"payeeBank","label":"开户行","type":"text","required":true,"colSpan":1},
    {"key":"expectedDate","label":"期望付款日期","type":"date","colSpan":1},
    {"key":"project","label":"对应项目","type":"select","dictType":"project","required":false,"colSpan":1},
    {"key":"reason","label":"申请事由","type":"textarea","required":true,"maxLength":1000,"colSpan":2},
    {"key":"invoices","label":"发票明细","type":"invoiceGroup","required":false,"colSpan":2},
    {"key":"attachments","label":"附件","type":"attachment","required":false,"maxCount":10,"colSpan":2}
  ],
  "rules":[
    {"when":"payType == ''OTHER''","then":{"show":["reason"]}}
  ]
}',1,NOW()),

-- 12.2 员工报销
(2,1,2,'员工报销单',1,'{
  "docType":"REIMBURSE_EMPLOYEE","layout":"two-column",
  "fields":[
    {"key":"title","label":"报销事由","type":"text","required":true,"maxLength":128,"colSpan":2},
    {"key":"amount","label":"报销金额(元)","type":"money","required":true,"min":0.01,"precision":2,"colSpan":1},
    {"key":"expenseType","label":"费用类型","type":"select","required":true,"dictType":"expense_type","colSpan":1},
    {"key":"occurDate","label":"费用发生日期","type":"date","required":true,"colSpan":1},
    {"key":"project","label":"对应项目","type":"select","dictType":"project","required":false,"colSpan":1},
    {"key":"reason","label":"补充说明","type":"textarea","required":false,"maxLength":1000,"colSpan":2},
    {"key":"invoices","label":"发票明细","type":"invoiceGroup","required":true,"colSpan":2},
    {"key":"attachments","label":"附件(发票影像)","type":"attachment","required":true,"maxCount":20,"colSpan":2}
  ]
}',1,NOW()),

-- 12.3 用印申请
(3,1,3,'用印申请单',1,'{
  "docType":"SEAL_APPLY","layout":"two-column",
  "fields":[
    {"key":"sealProject","label":"用印项目","type":"text","required":true,"maxLength":128,"colSpan":2},
    {"key":"fileName","label":"用印文件名称","type":"text","required":true,"maxLength":255,"colSpan":2},
    {"key":"sealType","label":"用章类型","type":"select","required":true,"dictType":"seal_type","colSpan":1},
    {"key":"copies","label":"份数","type":"number","required":true,"min":1,"max":999,"colSpan":1},
    {"key":"isCarryOut","label":"是否外带","type":"select","required":true,
      "options":[{"value":"N","label":"否"},{"value":"Y","label":"是"}],"colSpan":1},
    {"key":"expectDate","label":"期望用印日期","type":"date","required":false,"colSpan":1},
    {"key":"reason","label":"用印事由","type":"textarea","required":true,"maxLength":1000,"colSpan":2},
    {"key":"attachments","label":"附件","type":"attachment","required":false,"maxCount":10,"colSpan":2}
  ],
  "rules":[
    {"when":"isCarryOut == ''Y''","then":{"require":["expectDate"]}}
  ]
}',1,NOW());

-- ---------------------------------------------------------------------------
-- 13. 字段级权限（列级：同一模板在不同节点 可见/可编辑 不同）
--     约定：查不到记录 → 发起节点 n1 全字段可编辑，其余节点只读可见
-- ---------------------------------------------------------------------------
INSERT INTO `form_field_permission` (`template_id`,`node_key`,`field_key`,`visible`,`editable`) VALUES
-- 日常付款：出纳节点补充回单信息
(1,'n5','payeeAccount',1,1),
(1,'n5','payeeBank',   1,1),
-- 用印申请：综合管理部核验用章类型与份数
(3,'n3','sealType',    1,1),
(3,'n3','copies',      1,1);

-- ---------------------------------------------------------------------------
-- 14. 流程定义（flow_config）
-- ---------------------------------------------------------------------------
INSERT INTO `flow_config` (`id`,`company_id`,`doc_type_id`,`name`,`category`,`version`,`status`,`effective_from`) VALUES
(1,1,1,'日常付款审批流程','DAILY',      1,1,NOW()),
(2,1,2,'员工报销审批流程','REIMBURSE',   1,1,NOW()),
(3,1,3,'用印申请审批流程','SEAL',        1,1,NOW());

-- ---------------------------------------------------------------------------
-- 15. 流程节点定义（node_key 即 BPMN 的 userTask/element id）
--     node_type: 1审批 2抄送 3条件网关 4办理 5发起
--     condition_expr: 网关分支数组 [{"expr":"...","target":"n4"},{"default":true,"target":"n5"}]
-- ---------------------------------------------------------------------------
INSERT INTO `flow_config_node` (`flow_config_id`,`node_key`,`node_name`,`node_type`,`seq_no`,`condition_expr`,`allow_countersign`,`allow_reject`,`sla_hours`) VALUES
-- 日常付款：发起 → 部门负责人 → 会计(按部门) → [金额≥2万] 公司领导 → 出纳付款
(1,'n1','发起申请',        5,1,NULL,0,0,NULL),
(1,'n2','直属部门负责人',  1,2,NULL,1,1,24.00),
(1,'n3','会计（按部门）',  1,3,NULL,1,1,24.00),
(1,'gw1','金额分支',       3,4,'[{"expr":"doc.amount >= 20000","target":"n4"},{"default":true,"target":"n5"}]',0,0,NULL),
(1,'n4','公司领导（大额）',1,5,NULL,1,1,48.00),
(1,'n5','出纳付款',        4,6,NULL,0,1,24.00),

-- 员工报销：发起 → 部门负责人 → 会计(按部门) → [金额≥1万] 财务总监 → 出纳付款
(2,'n1','发起申请',        5,1,NULL,0,0,NULL),
(2,'n2','直属部门负责人',  1,2,NULL,1,1,24.00),
(2,'n3','会计（按部门）',  1,3,NULL,1,1,24.00),
(2,'gw1','金额分支',       3,4,'[{"expr":"doc.amount >= 10000","target":"n4"},{"default":true,"target":"n5"}]',0,0,NULL),
(2,'n4','财务总监',        1,5,NULL,1,1,48.00),
(2,'n5','出纳付款',        4,6,NULL,0,1,24.00),

-- 用印申请：发起 → 部门负责人 → 综合管理部 → [公章/合同章] 公司领导 → 用印办理
(3,'n1','发起申请',        5,1,NULL,0,0,NULL),
(3,'n2','直属部门负责人',  1,2,NULL,1,1,24.00),
(3,'n3','综合管理部',      1,3,NULL,0,1,24.00),
(3,'gw1','用章类型分支',   3,4,'[{"expr":"doc.sealType == ''OFFICIAL'' || doc.sealType == ''CONTRACT''","target":"n4"},{"default":true,"target":"n5"}]',0,0,NULL),
(3,'n4','公司领导',        1,5,NULL,1,1,48.00),
(3,'n5','用印办理',        4,6,NULL,0,1,24.00);

-- ---------------------------------------------------------------------------
-- 16. 节点指派规则（运行时按单据上下文解析「谁来审」）
--     rule_type: initiator_leader/dept_role/biztype_role/role/user/condition
-- ---------------------------------------------------------------------------
INSERT INTO `flow_node_assignee` (`node_id`,`rule_type`,`rule_value`,`sign_mode`,`sort_no`)
SELECT n.`id`, a.`rule_type`, a.`rule_value`, a.`sign_mode`, 0
FROM `flow_config_node` n
JOIN (
  -- ---------------- 日常付款 (flow_config_id=1) ----------------
  SELECT 1 AS cfg, 'n2' AS nk, 'initiator_leader' AS rule_type, '{"fallbackRoleCode":"DEPT_HEAD"}' AS rule_value, 1 AS sign_mode UNION ALL
  SELECT 1,'n3','dept_role','{"roleCode":"ACCOUNTANT","fallback":"company"}',1 UNION ALL
  SELECT 1,'n4','role','{"roleCode":"GM"}',1 UNION ALL
  SELECT 1,'n5','role','{"roleCode":"CASHIER"}',1 UNION ALL
  -- ---------------- 员工报销 (flow_config_id=2) ----------------
  SELECT 2,'n2','initiator_leader','{"fallbackRoleCode":"DEPT_HEAD"}',1 UNION ALL
  SELECT 2,'n3','dept_role','{"roleCode":"ACCOUNTANT","fallback":"company"}',1 UNION ALL
  SELECT 2,'n4','role','{"roleCode":"FIN_DIRECTOR"}',1 UNION ALL
  SELECT 2,'n5','role','{"roleCode":"CASHIER"}',1 UNION ALL
  -- ---------------- 用印申请 (flow_config_id=3) ----------------
  SELECT 3,'n2','initiator_leader','{"fallbackRoleCode":"DEPT_HEAD"}',1 UNION ALL
  -- 综合管理部核验：周综合(DEPT_HEAD)实际归属 dept 6 综合管理中心，deptId 必须与其 dept_id 一致，
  -- 否则该节点候选人解析为空(unresolved)，用印流程会卡死无人可审。
  SELECT 3,'n3','dept_role','{"roleCode":"DEPT_HEAD","deptId":6}',1 UNION ALL
  SELECT 3,'n4','role','{"roleCode":"GM"}',1 UNION ALL
  SELECT 3,'n5','user','{"userIds":[8]}',1
) a ON a.`cfg` = n.`flow_config_id` AND a.`nk` = n.`node_key`
WHERE n.`deleted` = 0;

-- ---------------------------------------------------------------------------
-- 17. 回填单据类型默认模板与流程
-- ---------------------------------------------------------------------------
UPDATE `document_type` SET `form_template_id` = 1, `flow_config_id` = 1 WHERE `id` = 1;
UPDATE `document_type` SET `form_template_id` = 2, `flow_config_id` = 2 WHERE `id` = 2;
UPDATE `document_type` SET `form_template_id` = 3, `flow_config_id` = 3 WHERE `id` = 3;

-- ---------------------------------------------------------------------------
-- 18. 校验
-- ---------------------------------------------------------------------------
SELECT '公司' AS 对象, COUNT(*) AS 数量 FROM `company`
UNION ALL SELECT '部门', COUNT(*) FROM `department`
UNION ALL SELECT '岗位', COUNT(*) FROM `post`
UNION ALL SELECT '用户', COUNT(*) FROM `sys_user`
UNION ALL SELECT '角色', COUNT(*) FROM `sys_role`
UNION ALL SELECT '权限点', COUNT(*) FROM `sys_permission`
UNION ALL SELECT '角色权限', COUNT(*) FROM `role_permission`
UNION ALL SELECT '单据类型', COUNT(*) FROM `document_type`
UNION ALL SELECT '表单模板', COUNT(*) FROM `form_template`
UNION ALL SELECT '字段权限', COUNT(*) FROM `form_field_permission`
UNION ALL SELECT '流程定义', COUNT(*) FROM `flow_config`
UNION ALL SELECT '流程节点', COUNT(*) FROM `flow_config_node`
UNION ALL SELECT '指派规则', COUNT(*) FROM `flow_node_assignee`
UNION ALL SELECT '字典项', COUNT(*) FROM `sys_dict`;

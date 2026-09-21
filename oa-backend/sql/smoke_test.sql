-- =============================================================================
-- 冒烟测试：验证 Schema 可用性
--   - 覆盖核心链路：组织 → 权限 → 表单模板 → 流程配置 → 单据 → 流程实例 → 待办
--   - 重点验证：JSON 字段读写、唯一约束、逻辑关联、业务查询
-- =============================================================================
USE `haixiajin_oa`;

-- 组织 -------------------------------------------------------------------------
INSERT INTO `company` (`code`, `name`, `short_name`) VALUES
  ('HXJ', '海峡金', '海峡金'),
  ('HXJ-GYL', '海峡金供应链', '供应链');

INSERT INTO `department` (`company_id`,`parent_id`,`code`,`name`,`dept_type`,`level`,`path`,`leader_id`) VALUES
  (1, 0, 'FIN_CENTER', '财务中心', 1, 1, '/1/', 3),
  (1, 1, 'FIN_DEPT',   '财务部',   2, 2, '/1/2/', 4);

INSERT INTO `post` (`company_id`,`code`,`name`) VALUES
  (1, 'DEPT_LEADER', '部门负责人岗'),
  (1, 'ACCOUNTANT',  '财务核算岗');

INSERT INTO `sys_user` (`company_id`,`job_no`,`account`,`password`,`real_name`,`dept_id`,`post_id`) VALUES
  (1, 'HXJ001', 'linanran',      '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '林安然', 2, 2),
  (1, 'HXJ018', 'zhangmingyuan', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '张明远', 2, 1);

-- 权限 -------------------------------------------------------------------------
INSERT INTO `sys_role` (`company_id`,`code`,`name`,`dept_id`,`is_builtin`) VALUES
  (1, 'SUPER_ADMIN', '超级管理员', 1, 1),
  (1, 'ACCOUNTANT',  '核算会计',   2, 1),
  (1, 'DEPT_LEADER', '部门负责人', 2, 1);

INSERT INTO `sys_permission` (`code`,`name`,`perm_type`) VALUES
  ('document:view:self', '查看本人表单',   3),
  ('document:view:dept', '查看本部门表单', 3),
  ('document:view:all',  '查看全部表单',   3),
  ('document:create',    '提交单据',       3),
  ('document:approve',   '审批',           3),
  ('document:export',    '导出台账',       2),
  ('flow:config',        '配置流程与权限', 2),
  ('seal:apply',         '用印申请',       3);

INSERT INTO `role_permission` (`role_id`,`perm_code`) VALUES
  (1,'document:view:all'), (1,'document:create'), (1,'document:approve'), (1,'document:export'), (1,'flow:config'),
  (2,'document:view:all'), (2,'document:approve'),
  (3,'document:view:dept'),(3,'document:approve');

INSERT INTO `role_data_scope` (`role_id`,`scope_type`,`company_ids`,`dept_ids`) VALUES
  (1, 'company',     NULL, NULL),
  (2, 'custom_dept', JSON_ARRAY(1,2), JSON_ARRAY(2)),
  (3, 'dept',        NULL, NULL);

INSERT INTO `user_role` (`user_id`,`role_id`) VALUES (1,1), (2,3);

-- 单据类型 → 表单模板 → 流程配置 ------------------------------------------------
INSERT INTO `document_type` (`company_id`,`code`,`name`,`category`,`must_link_prev`) VALUES
  (1, 'PURCHASE',         '采购申请', 'DAILY',     0),
  (1, 'EXPENSE_REIMBURSE','费用报销', 'REIMBURSE', 1),
  (1, 'SEAL_APPLY',       '用印申请', 'SEAL',      0);

INSERT INTO `form_template` (`company_id`,`doc_type_id`,`name`,`version`,`schema_json`,`status`) VALUES
  (1, 1, '采购申请表单', 1, JSON_OBJECT(
      'version', 1,
      'fields', JSON_ARRAY(
        JSON_OBJECT('key','company',        'label','所属公司',    'type','select',  'source','dict:company','required',true),
        JSON_OBJECT('key','project',        'label','对应项目',    'type','select',  'source','dict:project','required',true),
        JSON_OBJECT('key','amount',         'label','申请金额(元)','type','money',   'required',true),
        JSON_OBJECT('key','invoiceSummary', 'label','简易发票明细','type','textarea'),
        JSON_OBJECT('key','needPostMaterial','label','付款后置材料','type','switch')
      ),
      'rules', JSON_ARRAY(
        JSON_OBJECT('when','$.needPostMaterial === true','require',JSON_ARRAY('postMaterialDeadline'))
      )
  ), 1);

INSERT INTO `form_field_permission` (`template_id`,`node_key`,`field_key`,`visible`,`editable`) VALUES
  (1,'*', 'amount',         1, 0),
  (1,'*', 'invoiceSummary', 1, 0),
  (1,'n4','amount',         1, 1);

INSERT INTO `flow_config` (`company_id`,`doc_type_id`,`name`,`category`,`version`,`status`) VALUES
  (1, 1, '采购申请流程', 'DAILY', 1, 1);

UPDATE `document_type` SET `form_template_id`=1, `flow_config_id`=1 WHERE `id`=1;

INSERT INTO `flow_config_node` (`flow_config_id`,`node_key`,`node_name`,`node_type`,`seq_no`,`condition_expr`,`sla_hours`) VALUES
  (1,'n1','直属主管',      1, 1, NULL,                    8.00),
  (1,'n2','会计主管&内控', 1, 2, NULL,                    8.00),
  (1,'n3','执行总经理',    1, 3, 'amount >= 20000',      24.00),
  (1,'n4','出纳',          4, 4, NULL,                    8.00);

INSERT INTO `flow_node_assignee` (`node_id`,`rule_type`,`rule_value`,`sign_mode`) VALUES
  (1,'initiator_leader', NULL,                                                  1),
  (2,'dept_role',        JSON_OBJECT('roleCode','ACCOUNTANT','byDept',true),    1),
  (3,'condition',        JSON_OBJECT('expr','amount >= 20000','roleCode','GM'), 1),
  (4,'role',             JSON_OBJECT('roleCode','CASHIER'),                     1);

-- 单据 → 流程实例 → 待办 --------------------------------------------------------
INSERT INTO `document`
  (`doc_no`,`company_id`,`doc_type_id`,`business_category`,`title`,`applicant_id`,`applicant_name`,
   `dept_id`,`dept_name`,`amount`,`reason`,`invoice_summary`,`form_data`,
   `form_template_id`,`form_template_ver`,`need_post_material`,`status`,
   `current_node_key`,`current_node_name`,`submitted_at`)
VALUES
  ('FK202608260001',1,1,'DAILY','办公用品采购',1,'林安然',2,'财务部',2350.00,
   '因办公需要采购办公用品','增值税普通发票3张',
   JSON_OBJECT('company','海峡金','project','办公用品采购','amount',2350.00,
               'invoiceSummary','增值税普通发票3张','needPostMaterial',false),
   1,1,0,2,'n1','直属主管',NOW());

INSERT INTO `flow_instance` (`document_id`,`flow_config_id`,`flow_config_version`,`status`,`current_node_key`) VALUES
  (1,1,1,1,'n1');

INSERT INTO `flow_instance_node`
  (`instance_id`,`document_id`,`node_key`,`node_name`,`node_type`,`seq_no`,`status`,`assignee_id`,`assignee_name`,`deadline`) VALUES
  (1,1,'n1','直属主管',1,1,0,2,'张明远', DATE_ADD(NOW(), INTERVAL 8 HOUR));

UPDATE `document` SET `flow_instance_id`=1 WHERE `id`=1;

-- 用印 / 字典 / 编码序列 / 审计 / 通知 ------------------------------------------
INSERT INTO `seal_apply` (`document_id`,`seal_project`,`seal_dept_id`,`seal_type`,`seal_reason`) VALUES
  (1,'客户合同用印',2,'合同章','客户合同签署');

INSERT INTO `sys_dict` (`company_id`,`dict_type`,`dict_code`,`dict_label`) VALUES
  (1,'seal_type','OFFICIAL','公章'),
  (1,'seal_type','CONTRACT','合同章'),
  (1,'project',   'OFFICE',  '办公用品采购');

INSERT INTO `code_sequence` (`company_id`,`biz_prefix`,`period`,`current_val`) VALUES (1,'FK','20260826',1);

INSERT INTO `audit_log` (`company_id`,`user_id`,`user_name`,`module`,`action`,`biz_id`,`detail`) VALUES
  (1,1,'林安然','document','submit',1, JSON_OBJECT('from','草稿','to','待审批'));

INSERT INTO `notification` (`company_id`,`receiver_id`,`title`,`notify_type`,`biz_type`,`biz_id`) VALUES
  (1,2,'有一张待审批单据','todo','document',1);


-- =============================================================================
-- 验证查询
-- =============================================================================
SELECT '=== 1. 待办查询（按审批人） ===' AS ``;
SELECT n.`document_id`, d.`doc_no`, d.`title`, n.`node_name`, n.`assignee_name`, n.`deadline`
FROM `flow_instance_node` n JOIN `document` d ON d.`id` = n.`document_id`
WHERE n.`assignee_id` = 2 AND n.`status` = 0;

SELECT '=== 2. JSON 字段取值（动态表单） ===' AS ``;
SELECT `doc_no`,
       `form_data`->>'$.project'  AS project,
       `form_data`->>'$.amount'   AS amount,
       JSON_LENGTH(`form_data`)   AS field_count
FROM `document`;

SELECT '=== 3. 表单 Schema 结构校验 ===' AS ``;
SELECT `name`, `version`,
       JSON_LENGTH(`schema_json`->'$.fields') AS field_cnt,
       `schema_json`->'$.fields[0].key'       AS first_field
FROM `form_template`;

SELECT '=== 4. 角色 × 权限点 ===' AS ``;
SELECT r.`name` AS role_name, GROUP_CONCAT(p.`code` ORDER BY p.`code`) AS perms
FROM `sys_role` r
JOIN `role_permission` rp ON rp.`role_id` = r.`id`
JOIN `sys_permission`  p  ON p.`code` = rp.`perm_code`
GROUP BY r.`id`, r.`name`;

SELECT '=== 5. 数据范围（行级） ===' AS ``;
SELECT r.`name`, s.`scope_type`, s.`company_ids`, s.`dept_ids`
FROM `sys_role` r JOIN `role_data_scope` s ON s.`role_id` = r.`id`;

SELECT '=== 6. 流程节点 + 指派规则 ===' AS ``;
SELECT c.`seq_no`, c.`node_name`, c.`node_type`, c.`condition_expr`,
       a.`rule_type`, a.`rule_value`
FROM `flow_config_node` c
LEFT JOIN `flow_node_assignee` a ON a.`node_id` = c.`id`
WHERE c.`flow_config_id` = 1
ORDER BY c.`seq_no`;

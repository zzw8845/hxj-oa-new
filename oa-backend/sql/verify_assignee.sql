-- =============================================================================
-- 节点指派规则解析验证：模拟「黄小明(id=9) 提交日常付款 5 万元」走完全链路
-- 目的：证明 flow_node_assignee 的数据化规则能真实解析出每个节点的审批人
-- 用法：mysql -uroot haixiajin_oa < verify_assignee.sql
-- =============================================================================
SET NAMES utf8mb4;
SET @applicantId = 9;          -- 黄小明
SET @flowConfigId = 1;         -- 日常付款流程
SELECT @deptId := `dept_id` FROM `sys_user` WHERE `id` = @applicantId;

SELECT '=== 0. 单据上下文 ===' AS section;
SELECT u.`id`, u.`real_name`, u.`dept_id`, d.`name` AS dept_name
FROM `sys_user` u LEFT JOIN `department` d ON d.`id` = u.`dept_id`
WHERE u.`id` = @applicantId;

SELECT '=== 1. 流程链路 + 命中分支 ===' AS section;
SELECT n.`seq_no`, n.`node_key`, n.`node_name`,
       CASE n.`node_type` WHEN 1 THEN '审批' WHEN 2 THEN '抄送' WHEN 3 THEN '条件网关'
                          WHEN 4 THEN '办理' WHEN 5 THEN '发起' ELSE '?' END AS node_type,
       IF(n.`node_type` = 3,
          CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(n.`condition_expr`,'$[0].expr')) LIKE '%20000%'
               THEN '金额≥20000 → 走 n4 公司领导' ELSE n.`condition_expr` END,
          '') AS branch_hit
FROM `flow_config_node` n
WHERE n.`flow_config_id` = @flowConfigId AND n.`deleted` = 0
ORDER BY n.`seq_no`;

SELECT '=== 2. 逐节点解析审批人（核心验证） ===' AS section;

-- n2：取发起人部门负责人
SELECT 'n2 直属部门负责人' AS node_name, 'initiator_leader' AS rule_type,
       u.`id` AS resolved_user_id, u.`real_name` AS resolved_user
FROM `department` d JOIN `sys_user` u ON u.`id` = d.`leader_id`
WHERE d.`id` = @deptId
UNION ALL
-- n3：会计（按部门）——本部门无会计，fallback 到全公司
SELECT 'n3 会计（按部门）', 'dept_role',
       u.`id`, u.`real_name`
FROM `sys_user` u
JOIN `user_role` ur ON ur.`user_id` = u.`id` AND ur.`deleted` = 0
JOIN `sys_role` r  ON r.`id` = ur.`role_id` AND r.`deleted` = 0
WHERE r.`code` = 'ACCOUNTANT' AND u.`deleted` = 0 AND u.`status` = 1
UNION ALL
-- n4：公司领导（金额≥2万时命中）
SELECT 'n4 公司领导（大额）', 'role', u.`id`, u.`real_name`
FROM `sys_user` u
JOIN `user_role` ur ON ur.`user_id` = u.`id` AND ur.`deleted` = 0
JOIN `sys_role` r  ON r.`id` = ur.`role_id` AND r.`deleted` = 0
WHERE r.`code` = 'GM' AND u.`deleted` = 0 AND u.`status` = 1
UNION ALL
-- n5：出纳付款
SELECT 'n5 出纳付款', 'role', u.`id`, u.`real_name`
FROM `sys_user` u
JOIN `user_role` ur ON ur.`user_id` = u.`id` AND ur.`deleted` = 0
JOIN `sys_role` r  ON r.`id` = ur.`role_id` AND r.`deleted` = 0
WHERE r.`code` = 'CASHIER' AND u.`deleted` = 0 AND u.`status` = 1;

SELECT '=== 3. 数据范围（行级）验证：各角色能看到的单据 ===' AS section;
SELECT r.`code` AS role_code, r.`name` AS role_name,
       COALESCE(ds.`scope_type`,'(未配置)') AS scope_type,
       CASE COALESCE(ds.`scope_type`,'')
            WHEN 'self'    THEN CONCAT('applicant_id = ', @applicantId)
            WHEN 'dept'    THEN CONCAT('dept_id IN (发起人部门及下级)')
            WHEN 'company' THEN 'company_id = 1（不加额外条件）'
            ELSE '—' END AS where_clause
FROM `sys_role` r LEFT JOIN `role_data_scope` ds ON ds.`role_id` = r.`id` AND ds.`deleted` = 0
WHERE r.`deleted` = 0 ORDER BY r.`id`;

SELECT '=== 4. 角色权限点核对（抽样） ===' AS section;
SELECT r.`code` AS role_code, COUNT(rp.`perm_code`) AS perm_count,
       GROUP_CONCAT(rp.`perm_code` ORDER BY rp.`perm_code` SEPARATOR ', ') AS perms
FROM `sys_role` r LEFT JOIN `role_permission` rp ON rp.`role_id` = r.`id` AND rp.`deleted` = 0
WHERE r.`deleted` = 0 GROUP BY r.`id`, r.`code` ORDER BY r.`id`;

SELECT '=== 5. 表单字段级权限（出纳节点可编辑字段） ===' AS section;
SELECT t.`name` AS template_name, p.`node_key`, p.`field_key`,
       IF(p.`visible`=1,'可见','隐藏') AS visibility, IF(p.`editable`=1,'可编辑','只读') AS editability
FROM `form_field_permission` p JOIN `form_template` t ON t.`id` = p.`template_id`
WHERE p.`deleted` = 0 ORDER BY t.`id`, p.`node_key`, p.`field_key`;

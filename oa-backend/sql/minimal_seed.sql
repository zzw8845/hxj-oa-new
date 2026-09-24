-- =============================================================================
-- 海峡金 OA — 最小可用初始数据（「全新系统只有一个 admin 超管」）
--
--   用途：客户从零开始使用的起点。除 admin 外**没有任何业务数据**，
--         部门 / 岗位 / 角色 / 权限 / 字典 / 单据类型 / 表单模板 / 流程
--         全部由 admin 在界面上新建。
--
--   前置：先灌 init_database.sql（仅表结构，70 张表）
--   用法：mysql -uroot <库名> < minimal_seed.sql
--
--   内容：公司 ×1 + admin ×1 + ADMIN 角色 ×1 + 权限点全量 + 角色绑定 + 数据范围
--
--   【为什么权限点必须一起灌】
--     admin 的超管权限不是硬编码的：PermInterceptor 只校验 JWT 里的 permCodes，
--     而 permCodes 来自 role_permission × sys_permission。
--     只建用户不建权限点，admin 能登录但每个接口都返回 403。
--
--   【为什么数据范围必须一起灌】
--     role_data_scope 缺失时，ADMIN 的数据范围会落到默认值 self（本人单据），
--     admin 将看不到别人提交的单据、看板统计也只统计自己 —— 表现是
--     「功能都能点，但数据对不上」，排查成本很高。这里显式给 company。
--
--   【默认密码】admin / 123456（BCrypt），首次登录请立即修改
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE `user_role`;
TRUNCATE TABLE `role_permission`;
TRUNCATE TABLE `role_data_scope`;
TRUNCATE TABLE `sys_permission`;
TRUNCATE TABLE `sys_user`;
TRUNCATE TABLE `sys_role`;
TRUNCATE TABLE `company`;

SET FOREIGN_KEY_CHECKS = 1;

-- 1. 公司（admin 必须挂在某个 company_id 下）
INSERT INTO `company` (`id`,`code`,`name`,`short_name`,`status`,`sort_no`) VALUES
(1,'HXJ','福建海峡金投资有限公司','海峡金',1,10);

-- 2. 唯一账号 admin / 123456
--    不挂部门、不挂岗位（dept_id / post_id 允许 NULL）——
--    这也意味着此时提单时 applicantDeptId 为空，"直属部门负责人"类节点解析不到人。
--    正式使用前请先在界面上建部门并把自己挂进去。
INSERT INTO `sys_user` (`id`,`company_id`,`job_no`,`account`,`password`,`real_name`,`status`,`pwd_reset_flag`) VALUES
(1,1,'HXJ001','admin','$2b$10$bgqRHMUVHhhEwFx7fWUrEurdhdAakCc.dXW34iMO/gyOSyDuKM.Hu','系统管理员',1,0);

-- 3. 超级管理员角色
INSERT INTO `sys_role` (`id`,`company_id`,`code`,`name`,`post_name`,`is_builtin`,`remark`) VALUES
(1,1,'ADMIN','超级管理员','系统管理员',1,'拥有全部权限');

-- 4. 权限点全量（功能模块级稳定 code；新增功能模块需要在此追加并重新绑定）
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

-- 5. ADMIN 角色绑定全部权限点
INSERT INTO `role_permission` (`role_id`,`perm_code`)
SELECT 1, `code` FROM `sys_permission` WHERE `deleted` = 0;

-- 6. ADMIN 数据范围 = 全公司（缺这条会静默降级为 self）
INSERT INTO `role_data_scope` (`role_id`,`scope_type`,`company_ids`,`dept_ids`) VALUES
(1,'company',NULL,NULL);

-- 7. admin 挂 ADMIN 角色
INSERT INTO `user_role` (`user_id`,`role_id`) VALUES (1,1);

-- =============================================================================
-- 灌完后数据库状态（用于核对）
--   company=1  sys_user=1  sys_role=1  sys_permission=25  role_permission=25
--   role_data_scope=1  user_role=1
--   department=0  post=0  sys_dict=0  document_type=0  form_template=0
--   flow_config=0  document=0
-- =============================================================================

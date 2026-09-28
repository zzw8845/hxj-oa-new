-- =============================================================================
-- 海峡金 OA — 最小可用初始数据（「全新系统只有一个 admin 超管」）
--
--   用途：客户从零开始使用的起点。除 admin 与**两个内置角色定义**外，
--         **没有任何业务数据** —— 部门 / 岗位 / 字典 / 单据类型 / 表单模板 / 流程 /
--         业务角色（部门负责人·会计·出纳…）全部由 admin 在界面上新建。
--
--   前置：先灌 init_database.sql（仅表结构，71 张表）
--   用法：mysql -uroot <库名> < minimal_seed.sql
--
--   内容：公司 ×1 + admin ×1 + 内置角色 ×2（ADMIN / AUDIT_ADMIN）
--         + 权限点全量 + 角色绑定 + 数据范围 + 管理范围
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
--   【为什么内置角色必须在这里建，而不是由 admin 在界面上建】
--     RoleAdminService.create() 强制 is_builtin = 0 —— **应用层造不出内置角色**。
--     而 is_builtin = 1 正是三条护栏的开关：内置角色不可删除、权限点不可清空、
--     数据范围不可设为「仅本人」。产品自带的角色若缺了它，等于可以被随手删掉或掏空。
--     ⇒ 与「权限点没有写接口」同理：这是**引导集**，只能由种子建立。
--
--   【为什么没有审计员的账号】
--     只给角色、不给账号 —— 审计员由谁担任是客户的治理决策，不是产品的默认值。
--     种子里多一个带默认口令的账号 = 一个没人负责的凭据，是净风险。
--     admin 建号时在「分配角色」里勾上「审计管理员」即可（角色编码 AUDIT_ADMIN）。
--
--   【默认密码】admin / 123456（BCrypt），首次登录请立即修改
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE `user_role`;
TRUNCATE TABLE `role_permission`;
TRUNCATE TABLE `role_data_scope`;
TRUNCATE TABLE `role_admin_scope`;
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

-- 3. 内置角色（产品自带，is_builtin=1）
--    ADMIN       —— 超级管理员，绑全部权限点（见第 5 段）
--    AUDIT_ADMIN —— 审计管理员，**只读审计日志**：不审批、不管用户/角色/流程
--    ⚠ 二者都不是「业务角色」。业务角色由 admin 在界面上新建。
INSERT INTO `sys_role` (`id`,`company_id`,`code`,`name`,`post_name`,`is_builtin`,`remark`) VALUES
(1,1,'ADMIN','超级管理员','系统管理员',1,'拥有全部权限'),
(2,1,'AUDIT_ADMIN','审计管理员','审计员',1,'只读审计日志：不审批、不管用户/角色/流程');

-- 4. 权限点全量（功能模块级稳定 code；新增功能模块需要在此追加并重新绑定）
--    flow:intervene:transfer 是「流程干预」类动作：把任务**交还给正确的人**，
--    本身不产生任何审批结论（P3：审批决定权永不授予管理员）。
--    它挂在 admin:menu 下而不是 todo:menu 下，是为了让"这是管理动作、不是日常审批动作"
--    在角色配置界面上直接可见 —— 混在待办组里容易被顺手勾给业务角色。
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
('system:docType',          '单据类型管理',   2, 'admin:menu',      98),
('system:company',          '公司信息管理',   2, 'admin:menu',      99),
('flow:intervene:transfer', '转办（流程干预）', 2, 'admin:menu',     100);

-- 5. ADMIN 角色绑定全部权限点
INSERT INTO `role_permission` (`role_id`,`perm_code`)
SELECT 1, `code` FROM `sys_permission` WHERE `deleted` = 0;

-- 5b. AUDIT_ADMIN 只绑两个点：审计日志 + 它的父菜单
--     为什么连 admin:menu 一起给 —— 它是**菜单分组点**（perm_type=1，后端 0 引用），
--     只决定「系统管理」分组在角色配置树上是否成组；不给会让审计日志成为孤儿子节点。
--     ⚠ 绝不给 document:approve* / system:user / system:role / system:flow：
--     审计员只**审阅**，既不产生审批结论、也不改变任何定义（三员分立的第一条）。
INSERT INTO `role_permission` (`role_id`,`perm_code`)
SELECT 2, `code` FROM `sys_permission` WHERE `code` IN ('admin:menu', 'system:audit');

-- 6. 数据范围
--    ADMIN       = 全公司（缺这条会静默降级为 self，见开头说明）
--    AUDIT_ADMIN = 全公司：审计是**公司级只读**职能；且内置角色不允许设为 self
--      （RoleAdminService 护栏），显式给值比留空更不容易被误读成「忘了配」。
--    ⚠ 审计日志查询目前**不走** DataScopeHelper（AuditLogService.query 无行级过滤）——
--      这条范围对「看审计日志」本身不产生作用，它约束的是将来给该角色补业务读权限的场景。
INSERT INTO `role_data_scope` (`role_id`,`scope_type`,`company_ids`,`dept_ids`) VALUES
(1,'company',NULL,NULL),
(2,'company',NULL,NULL);

-- 6b. 管理范围（能【管】哪些部门的人）—— 与上面第 6 段是**两个正交维度**，别混：
--       role_data_scope  = 能【看】多少单据 / 台账   → DataScopeHelper（行级过滤）
--       role_admin_scope = 能【管】哪些人的账号     → AdminScopeHelper（用户管理的读+写）
--     ADMIN       = all          —— 与改造前行为等价（超管本来就能管所有人），零收权
--     AUDIT_ADMIN = dept_subtree —— 审计员当前**不持 system:user**，这一行不改变现状；
--       显式给值的意义是声明"若将来把人员管理授予审计员，只能在本人部门子树内管人"，
--       不留「是忘了配还是真不给」的歧义（同第 6 段给 AUDIT_ADMIN 显式 company 的理由）。
--     ⚠ 其余业务角色**刻意不配**：它们都不持 system:user，配了是死配置；
--       而缺省语义 = none（最窄）⇒ 新建的自定义角色默认管不到任何人，想要就得显式配。
INSERT INTO `role_admin_scope` (`role_id`,`scope_type`) VALUES
(1,'all'),
(2,'dept_subtree');

-- 7. admin 挂 ADMIN 角色
INSERT INTO `user_role` (`user_id`,`role_id`) VALUES (1,1);

-- =============================================================================
-- 灌完后数据库状态（用于核对）
--   company=1  sys_user=1  sys_role=2  sys_permission=27  role_permission=29
--   role_data_scope=2  role_admin_scope=2  user_role=1
--   （role_permission = 27 全量给 ADMIN + 2 给 AUDIT_ADMIN）
--   department=0  post=0  sys_dict=0  document_type=0  form_template=0
--   flow_config=0  document=0
-- =============================================================================

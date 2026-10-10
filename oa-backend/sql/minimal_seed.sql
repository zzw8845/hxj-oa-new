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
--   内容：公司 ×1 + admin ×1 + 内置角色 ×1（ADMIN）
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
--    ADMIN —— 超级管理员，绑全部权限点（见第 5 段）。
--    ⚠ 它不是「业务角色」。业务角色（含审计等专项角色）由 admin 在界面上新建。
--    注：审计职能没有独立内置角色 —— 审计日志的操作点 system:audit 与菜单项 admin:audit
--    都在权限点全量里，客户想要专职审计岗时，界面建角色勾上这两个点即可。
INSERT INTO `sys_role` (`id`,`company_id`,`code`,`name`,`post_name`,`is_builtin`,`remark`) VALUES
(1,1,'ADMIN','超级管理员','系统管理员',1,'拥有全部权限');

-- 4. 权限点全量（功能模块级稳定 code；新增功能模块需要在此追加并重新绑定）
--    【三层结构（2026-10-09 拍板「不打补丁」，与若依 M/C/F 同构）】
--    · 组点（perm_type=1，parent_code=NULL，5 个 *:menu）：**纯目录节点**，
--      只用来给权限集界面提供一级分组、给菜单项当父级 —— 不参与角色绑定；
--    · 菜单项（perm_type=1，parent_code=组点，12 个）：**可绑定**。角色勾了哪个菜单项，
--      该角色登录后侧边栏就显示哪个菜单（可见性唯一事实源，AuthService#loadMenus
--      直接查绑定结果下发）。编码全冒号统一（2026-10-10 对齐若依）：菜单项 =
--      模块:页面（document:work / admin:audit），操作点 = 模块:动作（document:create，
--      可三级 document:approve:cashier）；前端在菜单边界把 code 映射到自己的页面路由。
--      home（首页）不入库：登录落地页人人可见，由 loadMenus 固定注入。
--    · 操作点（perm_type=2/3，22 个）：**可绑定**，parent_code = 所属**菜单项**
--      （不是组点）—— 每个操作点归到它实际被使用的页面下，权限集界面按
--      「菜单 → 操作」树形渲染，菜单与操作天然分层，不靠标签/改名来区分。
--    【flow:intervene:transfer 归属】流程卡死/超期后的**干预处置**动作（把任务交还
--    正确的人，本身不产生审批结论，P3），语义上归「风险预警」页面 —— 预警即处置。
INSERT INTO `sys_permission` (`code`,`name`,`perm_type`,`parent_code`,`sort_no`) VALUES
('document:menu',           '单据中心',       1, NULL,              10),
('todo:menu',               '我的待办',       1, NULL,              20),
('ledger:menu',             '表单台账',       1, NULL,              30),
('dashboard:menu',          '数据看板',       1, NULL,              40),
('admin:menu',              '系统管理',       1, NULL,              90),
('document:work',           '工作台',         1, 'document:menu',   1),
('document:forms',          '全部表单',       1, 'document:menu',   2),
('document:delegation',     '我的委托',       1, 'document:menu',   3),
('todo:approve',            '待我审批',       1, 'todo:menu',       1),
('ledger:archive',          '台账档案',       1, 'ledger:menu',     1),
('ledger:seal',             '用印台账',       1, 'ledger:menu',     2),
('ledger:risk',             '风险预警',       1, 'ledger:menu',     3),
('dashboard:board',         '工作看板',       1, 'dashboard:menu',  1),
('admin:permission',        '权限管理',       1, 'admin:menu',      1),
('admin:bizconfig',         '审批配置',       1, 'admin:menu',      2),
('admin:masterdata',        '主数据',         1, 'admin:menu',      3),
('admin:audit',             '审计日志',       1, 'admin:menu',      4),
-- 单据中心 → 工作台/全部表单
('document:create',         '发起单据',       2, 'document:work',   1),
('document:view:self',      '查看本人单据',   3, 'document:forms',  1),
('document:view:dept',      '查看本部门单据', 3, 'document:forms',  2),
('document:view:company',   '查看全公司单据', 3, 'document:forms',  3),
-- 表单台账 → 台账档案/风险预警
('document:export',         '导出台账',       2, 'ledger:archive',  1),
('flow:intervene:transfer', '转办（流程干预）', 2, 'ledger:risk',   1),
-- 我的待办 → 待我审批
('document:approve',        '审批单据',       3, 'todo:approve',    1),
('document:approve:leader', '领导审批',       3, 'todo:approve',    2),
('document:approve:accountant','会计审批',     3, 'todo:approve',   3),
('document:approve:cashier','出纳付款办理',   3, 'todo:approve',    4),
('document:approve:seal',   '用印办理',       3, 'todo:approve',    5),
('document:supplement',     '要求补充材料',   2, 'todo:approve',    6),
('document:countersign',    '加签',           2, 'todo:approve',    7),
-- 系统管理 → 权限管理/审批配置/主数据/审计日志
('system:user',             '用户管理',       2, 'admin:permission', 1),
('system:role',             '角色管理',       2, 'admin:permission', 2),
('system:dept',             '部门管理',       2, 'admin:permission', 3),
('system:perm',             '权限点管理',     2, 'admin:permission', 4),
('system:flow',             '流程配置',       2, 'admin:bizconfig',  1),
('system:form',             '表单配置',       2, 'admin:bizconfig',  2),
('system:docType',          '单据类型管理',   2, 'admin:bizconfig',  3),
('system:dict',             '字典管理',       2, 'admin:masterdata', 1),
('system:company',          '公司信息管理',   2, 'admin:masterdata', 2),
('system:audit',            '审计日志查看',   2, 'admin:audit',      1);

-- 5. ADMIN 角色绑定权限点 —— **系统层 + 只读**，不含业务动作（C4/D8 出口 B，2026-09-28）
--    管理员是纯管理账号：不发起、不审批任何单据（P3：审批决定权永不给管理员）。
--    摘掉 8 个业务动作：document:create / approve / approve:leader|accountant|cashier|seal /
--    countersign / supplement。保留：document:menu / view:* / export —— 只读与导出，
--    管理员排查问题时**看得见**单据，但动不了它。
--    ⚠ 与 DocumentController 的 @RequirePerm("document:create") 门控是一对：
--    只摘配置不补门控 = 账面收权（权限点拦不住请求）；只补门控不摘配置 = 账面合规。
--    ⚠ 组点（perm_type=1 且 parent_code 为空）不参与绑定（纯目录，2026-10-09）：
--    绑了也没有任何语义，绑上反而制造「绑了组点没绑菜单项」的歧义状态。
INSERT INTO `role_permission` (`role_id`,`perm_code`)
SELECT 1, `code` FROM `sys_permission`
WHERE `deleted` = 0
  AND NOT (`perm_type` = 1 AND `parent_code` IS NULL)
  AND `code` NOT IN ('document:create','document:approve','document:approve:leader',
                     'document:approve:accountant','document:approve:cashier',
                     'document:approve:seal','document:countersign','document:supplement');

-- 6. 数据范围
--    ADMIN = 全公司（缺这条会静默降级为 self，见开头说明）。
--    其余角色**刻意不配**：缺省语义 = self（最窄），要放宽就在角色配置里显式选。
INSERT INTO `role_data_scope` (`role_id`,`scope_type`,`company_ids`,`dept_ids`) VALUES
(1,'company',NULL,NULL);

-- 6b. 管理范围（能【管】哪些部门的人）—— 与上面第 6 段是**两个正交维度**，别混：
--       role_data_scope  = 能【看】多少单据 / 台账   → DataScopeHelper（行级过滤）
--       role_admin_scope = 能【管】哪些人的账号     → AdminScopeHelper（用户管理的读+写）
--     ADMIN = all —— 与改造前行为等价（超管本来就能管所有人），零收权
--     ⚠ 其余角色**刻意不配**：它们都不持 system:user，配了是死配置；
--       而缺省语义 = none（最窄）⇒ 新建的自定义角色默认管不到任何人，想要就得显式配。
INSERT INTO `role_admin_scope` (`role_id`,`scope_type`) VALUES
(1,'all');

-- 7. admin 挂 ADMIN 角色
INSERT INTO `user_role` (`user_id`,`role_id`) VALUES (1,1);

-- =============================================================================
-- 灌完后数据库状态（用于核对）
--   company=1  sys_user=1  sys_role=1  sys_permission=40  role_permission=27
--   role_data_scope=1  role_admin_scope=1  user_role=1
--   （sys_permission = 5 组点 + 12 菜单项 + 23 操作点；
--     role_permission = ADMIN 27（40 − 5 组点不绑 − 8 业务动作））
--   department=0  post=0  sys_dict=0  document_type=0  form_template=0
--   flow_config=0  document=0
-- =============================================================================

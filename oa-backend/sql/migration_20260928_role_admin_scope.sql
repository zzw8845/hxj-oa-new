-- =============================================================================
-- 2026-09-28  分级管理员（C3）：新增 role_admin_scope 表
--
--   【它解决什么】在此之前，「管理员」是一个全有或全无的角色（ADMIN 持全部 27 个
--     权限点）。只要给他 system:user，他就能看到并操作**全公司**每一个账号 ——
--     分公司 / 事业部想要一个「只管自己这摊人」的人事管理员，只能靠新建角色
--     再手工约束，而系统里没有任何地方承载这个约束。
--
--   【它不是什么】它与 role_data_scope **正交**，不是同一件事的两个档位：
--       role_data_scope  = 能【看】多少数据（单据、台账）→ 行级过滤 DataScopeHelper
--       role_admin_scope = 能【管】哪些人（账号的增删改查）→ AdminScopeHelper
--     所以本表既不能合并进 role_data_scope，也不能拿 sys_role.dept_id 顶替
--     （那个字段的语义是「角色归属部门」，供流程 dept_role 指派使用）。
--     详见 sql/schema.sql 里本表的注释块。
--
--   【三档，且刻意只有三档】none / dept_subtree / all。
--     没有 dept_ids：三档都有消费点，不留「有列无消费点」的空转配置（硬约束 18）。
--     将来真需要「指定部门集合」时再加列。
--
--   【缺省 = 不授权】表里没有某个角色的行 ⇒ 该角色管理范围 = none（最窄）。
--     与 role_data_scope 缺省降级 self 同一哲学：漏配不该被解释成放权。
--
--   【这次只给两个内置角色配初值】ADMIN = all；AUDIT_ADMIN = dept_subtree。
--     其余 7 个内置角色**刻意不配** —— 它们都不持 system:user，调不到用户管理接口，
--     配了也是死配置。这也正是"新增客户自定义角色默认管不到人"的来源：
--     新建角色默认无行 ⇒ none。
--
--   【在途影响】zero。本表只被「用户管理」的读写路径消费，不参与任何已部署流程的
--     审批人解析，也不改变现有账号的任何行为（ADMIN 拿 all，与改造前等价）。
--
--   【幂等】CREATE TABLE IF NOT EXISTS + INSERT ... WHERE NOT EXISTS。
--     重复执行不会报错；已手工调整过的角色不会被脚本覆盖回默认值。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `role_admin_scope` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id`     BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
  `scope_type`  VARCHAR(32)  NOT NULL                   COMMENT '管理范围 none=不能管任何人 / dept_subtree=本部门及下级 / all=全公司',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_admin_scope` (`role_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色管理范围（能管哪些部门的人）';

-- ADMIN = all：与改造前行为完全等价（超管本来就能管所有人），不构成任何收权。
INSERT INTO `role_admin_scope` (`role_id`, `scope_type`)
SELECT r.id, 'all' FROM `sys_role` r
 WHERE r.`deleted` = 0 AND r.`code` = 'ADMIN'
   AND NOT EXISTS (SELECT 1 FROM `role_admin_scope` s
                    WHERE s.`role_id` = r.`id` AND s.`deleted` = 0);

-- AUDIT_ADMIN = dept_subtree：审计员**当前不持 system:user**，这一行不改变任何现状。
-- 显式给值的意义是声明"若将来把人员管理授予审计员，它只能在本人部门子树内管人"，
-- 而不是留给读者一个"是忘了配还是真不给"的歧义（同 role_data_scope 给 AUDIT_ADMIN 显式 company）。
INSERT INTO `role_admin_scope` (`role_id`, `scope_type`)
SELECT r.id, 'dept_subtree' FROM `sys_role` r
 WHERE r.`deleted` = 0 AND r.`code` = 'AUDIT_ADMIN'
   AND NOT EXISTS (SELECT 1 FROM `role_admin_scope` s
                    WHERE s.`role_id` = r.`id` AND s.`deleted` = 0);

-- 核对：应输出两行（ADMIN=all / AUDIT_ADMIN=dept_subtree）
SELECT r.`code`, s.`scope_type`
  FROM `role_admin_scope` s
  JOIN `sys_role` r ON r.`id` = s.`role_id` AND r.`deleted` = 0
 WHERE s.`deleted` = 0
 ORDER BY r.`id`;

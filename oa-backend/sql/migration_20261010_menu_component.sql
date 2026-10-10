-- ============================================================================
-- 迁移：sys_permission 增加 component 列（前端页面标识，配置期接线）
-- 日期：2026-10-10
-- 说明：
--   · component = 前端页面标识（仅菜单项有值），前端据此挂载页面组件——
--     「菜单管理加一行 = 点亮一个已有页面」；页面代码本身仍随前端发版。
--   · 幂等：列已存在则跳过 ALTER；回填按 code 精确映射，重复执行结果一致。
--   · 新库直接跑 init_database.sql（已含此列），无需本脚本。
-- ============================================================================

-- ① 加列（不存在才加；MySQL 8 无 ADD COLUMN IF NOT EXISTS，用 INFORMATION_SCHEMA 判断）
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'sys_permission'
                     AND COLUMN_NAME = 'component');
SET @ddl = IF(@col_exists = 0,
              'ALTER TABLE `sys_permission` ADD COLUMN `component` VARCHAR(64) NULL COMMENT ''前端页面标识（仅菜单项有值）：前端据此挂载页面组件，加一行=点亮一个已有页面'' AFTER `parent_code`',
              'SELECT ''column component already exists'' AS note');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ② 回填 12 个菜单项的页面标识（末段 = 页面 key，与前端 CODE_TO_PAGE 一致）
UPDATE `sys_permission` SET `component` = 'work'       WHERE `code` = 'document:work';
UPDATE `sys_permission` SET `component` = 'forms'      WHERE `code` = 'document:forms';
UPDATE `sys_permission` SET `component` = 'delegation' WHERE `code` = 'document:delegation';
UPDATE `sys_permission` SET `component` = 'approve'    WHERE `code` = 'todo:approve';
UPDATE `sys_permission` SET `component` = 'archive'    WHERE `code` = 'ledger:archive';
UPDATE `sys_permission` SET `component` = 'seal'       WHERE `code` = 'ledger:seal';
UPDATE `sys_permission` SET `component` = 'risk'       WHERE `code` = 'ledger:risk';
UPDATE `sys_permission` SET `component` = 'board'      WHERE `code` = 'dashboard:board';
UPDATE `sys_permission` SET `component` = 'permission' WHERE `code` = 'admin:permission';
UPDATE `sys_permission` SET `component` = 'bizconfig'  WHERE `code` = 'admin:bizconfig';
UPDATE `sys_permission` SET `component` = 'masterdata' WHERE `code` = 'admin:masterdata';
UPDATE `sys_permission` SET `component` = 'audit'      WHERE `code` = 'admin:audit';

-- ③ 核对：菜单项应有 12 行且 component 全部非空
SELECT COUNT(*) AS menu_items,
       SUM(`component` IS NOT NULL) AS with_component
FROM `sys_permission`
WHERE `perm_type` = 1 AND `parent_code` IS NOT NULL AND `deleted` = 0;

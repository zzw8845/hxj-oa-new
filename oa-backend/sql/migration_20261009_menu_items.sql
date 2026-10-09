-- =============================================================================
-- 海峡金 OA 审批系统 — 迁移脚本 2026-10-09：菜单可配化（sys_permission 菜单树）
--
--   背景：侧边栏 12 个页面菜单项一直写死在前端，库里只有 5 个组点（一级目录）。
--   本脚本把菜单结构补进 sys_permission（perm_type=1 的二级行，挂组点下），
--   此后菜单可见性 = 角色绑定的菜单项，权限管理界面直接可勾（对齐若依 sys_menu 思路，
--   但沿用我们自己的单表体系：perm_type 1菜单/2按钮/3接口 ↔ 若依 M/C/F 同构）。
--
--   【幂等】全部 INSERT IGNORE / UPDATE，可重复执行。
--   【回滚】菜单行用 DELETE FROM sys_permission WHERE perm_type=1 AND parent_code IS NOT NULL
--           AND code IN (...)；组点绑定回收为逻辑删除（deleted=1），改回 0 即恢复。
--
--   ⚠ 执行后需重启后端（AuthSnapshotCache 按进程缓存权限快照），用户须重新登录。
-- =============================================================================

-- 1. 补 12 个页面菜单项（perm_type=1，挂各自组点下；code=前端页面 key）----------
INSERT IGNORE INTO `sys_permission` (`code`,`name`,`perm_type`,`parent_code`,`sort_no`) VALUES
('work',       '工作台',   1, 'document:menu',  1),
('forms',      '全部表单', 1, 'document:menu',  2),
('delegation', '我的委托', 1, 'document:menu',  3),
('approve',    '待我审批', 1, 'todo:menu',      1),
('archive',    '台账档案', 1, 'ledger:menu',    1),
('seal',       '用印台账', 1, 'ledger:menu',    2),
('risk',       '风险预警', 1, 'ledger:menu',    3),
('board',      '工作看板', 1, 'dashboard:menu', 1),
('permission', '权限管理', 1, 'admin:menu',     1),
('bizconfig',  '审批配置', 1, 'admin:menu',     2),
('masterdata', '主数据',   1, 'admin:menu',     3),
('audit',      '审计日志', 1, 'admin:menu',     4);

-- 1b. 名称对齐：approve 的展示名与页面/看板统一为「待我审批」。
--     旧版前端用 JS 运行时把「审批中心」改成「待我审批」，菜单驱动化后
--     名称事实源在本表，改库即改 UI，不再需要前端改名逻辑。
UPDATE `sys_permission` SET `name` = '待我审批'
WHERE `code` = 'approve' AND `name` = '审批中心';

-- 2. 组点绑定等价展开为菜单项绑定（旧语义「绑组点=整组可见」）
--    管理四项逐项映射其操作点 —— 与旧前端「组点+细粒度」双条件**逐项等价**：
--    permission←user/role/dept · bizconfig←flow/form/docType ·
--    masterdata←dept/dict/company · audit←audit。
--    不能只判「有 admin:menu 下任一操作点」就全给四项：审计员持 system:audit
--    会被误放进权限管理/主数据（比旧行为放宽，违背三员分立）。
INSERT IGNORE INTO `role_permission` (`role_id`,`perm_code`)
SELECT DISTINCT rp.`role_id`, m.`code`
FROM `role_permission` rp
JOIN `sys_permission` g ON g.`code` = rp.`perm_code`
                       AND g.`perm_type` = 1 AND g.`parent_code` IS NULL AND g.`deleted` = 0
JOIN `sys_permission` m ON m.`parent_code` = g.`code`
                       AND m.`perm_type` = 1 AND m.`deleted` = 0
WHERE rp.`deleted` = 0
  AND ( m.`code` NOT IN ('permission','bizconfig','masterdata','audit')
        OR (m.`code` = 'permission' AND EXISTS (SELECT 1 FROM `role_permission` rp2
              JOIN `sys_permission` op ON op.`code` = rp2.`perm_code`
              WHERE rp2.`role_id` = rp.`role_id` AND rp2.`deleted` = 0
                AND op.`code` IN ('system:user','system:role','system:dept')))
        OR (m.`code` = 'bizconfig' AND EXISTS (SELECT 1 FROM `role_permission` rp2
              JOIN `sys_permission` op ON op.`code` = rp2.`perm_code`
              WHERE rp2.`role_id` = rp.`role_id` AND rp2.`deleted` = 0
                AND op.`code` IN ('system:flow','system:form','system:docType')))
        OR (m.`code` = 'masterdata' AND EXISTS (SELECT 1 FROM `role_permission` rp2
              JOIN `sys_permission` op ON op.`code` = rp2.`perm_code`
              WHERE rp2.`role_id` = rp.`role_id` AND rp2.`deleted` = 0
                AND op.`code` IN ('system:dept','system:dict','system:company')))
        OR (m.`code` = 'audit' AND EXISTS (SELECT 1 FROM `role_permission` rp2
              JOIN `sys_permission` op ON op.`code` = rp2.`perm_code`
              WHERE rp2.`role_id` = rp.`role_id` AND rp2.`deleted` = 0
                AND op.`code` = 'system:audit')) );

-- 2b. 修正上一版宽条件误放的行：仅持 audit 操作点却被放进 permission/bizconfig/masterdata
--     的绑定（逻辑删除，改回 0 可恢复；逐项条件已保证今后不会再产生）。
--     先物化到临时表再 UPDATE —— 绕开 MySQL-1093（UPDATE 目标表不得出现在子查询 FROM 中）。
DROP TEMPORARY TABLE IF EXISTS `tmp_bad_menu_binds`;
CREATE TEMPORARY TABLE `tmp_bad_menu_binds` AS
SELECT rp3.`id` AS bad_id
FROM `role_permission` rp3
JOIN `sys_permission` m3 ON m3.`code` = rp3.`perm_code`
       AND m3.`code` IN ('permission','bizconfig','masterdata')
       AND m3.`perm_type` = 1
WHERE rp3.`deleted` = 0
  AND NOT EXISTS (SELECT 1 FROM `role_permission` rp2
        JOIN `sys_permission` op ON op.`code` = rp2.`perm_code`
        WHERE rp2.`role_id` = rp3.`role_id` AND rp2.`deleted` = 0
          AND op.`parent_code` = 'admin:menu' AND op.`perm_type` <> 1
          AND ( (m3.`code` = 'permission' AND op.`code` IN ('system:user','system:role','system:dept'))
             OR (m3.`code` = 'bizconfig'  AND op.`code` IN ('system:flow','system:form','system:docType'))
             OR (m3.`code` = 'masterdata' AND op.`code` IN ('system:dept','system:dict','system:company')) ));

UPDATE `role_permission` rp
JOIN `tmp_bad_menu_binds` b ON b.bad_id = rp.`id`
SET rp.`deleted` = 1, rp.`updated_at` = CURRENT_TIMESTAMP;
DROP TEMPORARY TABLE IF EXISTS `tmp_bad_menu_binds`;

-- 3. 审计管理员特判：持 system:audit 的角色补绑 audit 菜单项（新 seed 口径等价）
INSERT IGNORE INTO `role_permission` (`role_id`,`perm_code`)
SELECT rp.`role_id`, 'audit'
FROM `role_permission` rp
JOIN `sys_permission` p ON p.`code` = 'audit' AND p.`perm_type` = 1 AND p.`deleted` = 0
WHERE rp.`perm_code` = 'system:audit' AND rp.`deleted` = 0;

-- 4. 组点退化为纯目录节点：回收全部组点绑定（逻辑删除，改回 deleted=0 即恢复）
--    组点此后只给权限集界面当分组标题、给 loadMenus 当分组数据，绑定与否无任何语义；
--    绑着会造成「绑了组点没绑菜单项」的歧义状态（声明的控制必须有引用点）。
UPDATE `role_permission` SET `deleted` = 1, `updated_at` = CURRENT_TIMESTAMP
WHERE `perm_code` IN ('document:menu','todo:menu','ledger:menu','dashboard:menu','admin:menu')
  AND `deleted` = 0;

-- =============================================================================
-- 执行后核对：
--   SELECT COUNT(*) FROM sys_permission WHERE perm_type=1;              -- 应为 17（5 组点 + 12 菜单项）
--   SELECT perm_code FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id
--    WHERE r.code='ADMIN' AND rp.deleted=0 AND rp.perm_code IN
--    ('work','forms','delegation','approve','archive','seal','risk','board',
--     'permission','bizconfig','masterdata','audit');                    -- ADMIN 应 12 行全在
--   SELECT perm_code FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id
--    WHERE r.code='AUDIT_ADMIN' AND rp.deleted=0 AND rp.perm_code IN
--    ('audit','permission','bizconfig','masterdata');                   -- 审计员应只有 audit
--   SELECT COUNT(*) FROM role_permission WHERE deleted=0 AND perm_code
--    IN ('document:menu','todo:menu','ledger:menu','dashboard:menu','admin:menu'); -- 应为 0
-- =============================================================================

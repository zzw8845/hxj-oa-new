-- =============================================================================
-- migration_20260928_c4_sod_admin.sql
-- C4（职责分离）+ D8（admin 定位）出口 B：ADMIN 转为**纯管理账号**
--
-- 用户拍板（2026-09-28）：管理员不参与业务 —— 不发起、不审批任何单据。
--   · 摘掉 ADMIN 的 8 个业务动作权限点（发单 + 审批全家族）；
--   · 保留 document:menu / view:* / export（只读与导出，排查问题时看得见、动不了）；
--   · admin 账号**无需部门**（不发起单据，「有发单权没部门」的矛盾随发单权一起消失）。
--
-- ⚠ 这条 migration 只动**配置**，必须与代码门控成对出现：
--   DocumentController 的创建 / 改草稿 / 提交三个端点已挂 @RequirePerm("document:create")。
--   只跑本脚本不升级代码 = 账面收权（权限点拦不住请求，实测 POST /api/documents 照样通）。
--
-- 幂等：可重复执行；已摘过的库再跑是 0 行受影响。
-- 回滚：把下面 DELETE 换成对应 INSERT（perm_code 清单同本文件），或整体随版本回退。
-- =============================================================================

DELETE FROM `role_permission`
WHERE `role_id` = (SELECT `id` FROM (SELECT `id` FROM `sys_role`
                     WHERE `code` = 'ADMIN' AND `deleted` = 0 LIMIT 1) t)
  AND `perm_code` IN (
    'document:create',
    'document:approve',
    'document:approve:leader',
    'document:approve:accountant',
    'document:approve:cashier',
    'document:approve:seal',
    'document:countersign',
    'document:supplement'
  );

-- 核对（应返回 19）：
-- SELECT COUNT(*) FROM role_permission rp JOIN sys_role r ON r.id=rp.role_id
--  WHERE r.code='ADMIN' AND rp.deleted=0;

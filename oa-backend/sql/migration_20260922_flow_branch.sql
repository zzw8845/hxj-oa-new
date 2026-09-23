-- =============================================================================
-- 2026-09-22  条件分支可配置：flow_config_node.condition_expr 加宽
--
--   为什么需要：条件分支以前只能靠种子 SQL 写一个表达式字符串，界面上无法编辑。
--     补上写路径（PUT/POST /api/flows/configs 带 branches）之后，一个网关的分支
--     会被序列化成 JSON 数组存在这一列：
--       [{"expr":"doc.amount >= 20000","target":"n4"},{"default":true,"target":"n5"}]
--     VARCHAR(512) 只够放约 10 条分支，再多就会撞上 MySQL 的 DataIntegrityViolation，
--     用户看到的就是一句没有信息量的 500。加宽到 1024，并在服务层按 1000 先拦一道，
--     把"塞不下"变成一句能看懂的提示。
--
--   【结构变更，影响在途单据吗】不影响：这一列是**编译期输入**——
--     BpmnGenerator 在部署那一刻读它生成 BPMN XML，运行时引擎不再回头读它。
--     加宽列宽更不改变任何已部署流程的行为。
--     新增分支仍然必须走"新建版本"路径（改结构 ⇒ 新版本），见 FlowConfigAdminService。
--
--   【幂等】这里用 information_schema 判断列宽，只有没加宽过才执行 ALTER。
--     MySQL 不支持 ADD/MODIFY COLUMN IF NOT EXISTS，而迁移脚本被重复执行
--     （本机 + 测试库 + 以后的生产库）是常态，不该因为跑第二遍就报错中断。
-- =============================================================================

SET @ddl := (
  SELECT IF(COALESCE(MAX(CHARACTER_MAXIMUM_LENGTH), 0) < 1024,
            'ALTER TABLE `flow_config_node` MODIFY COLUMN `condition_expr` VARCHAR(1024) NULL COMMENT ''分支条件表达式（条件网关用）：JSON 数组，如 [{"expr":"doc.amount >= 20000","target":"n4"},{"default":true,"target":"n5"}]''',
            'DO 0')
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'flow_config_node'
     AND COLUMN_NAME = 'condition_expr');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 核对：应输出 varchar(1024)
SELECT COLUMN_NAME, COLUMN_TYPE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'flow_config_node'
   AND COLUMN_NAME = 'condition_expr';

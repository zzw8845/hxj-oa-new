-- =============================================================================
-- 海峡金 OA 审批系统 — 迁移脚本：接入 Flowable 流程引擎
-- 版本：2026-09-18
-- 说明：schema.sql 已包含最新结构（新建库直接用 schema.sql 即可）。
--       本脚本用于【已存在旧库】的存量升级，可重复执行（幂等）。
-- 用法：mysql -uroot haixiajin_oa < migration_20260918_flowable.sql
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 幂等 DDL 工具过程：列 / 索引不存在才创建
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `sp_add_col_if_absent`;
DROP PROCEDURE IF EXISTS `sp_add_index_if_absent`;

DELIMITER $$
CREATE PROCEDURE `sp_add_col_if_absent`(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_col) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$

CREATE PROCEDURE `sp_add_index_if_absent`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$
DELIMITER ;

-- ---------------------------------------------------------------------------
-- 1. flow_config：绑定 Flowable 流程定义
-- ---------------------------------------------------------------------------
CALL sp_add_col_if_absent('flow_config', 'engine_type',
  "`engine_type` VARCHAR(16) NOT NULL DEFAULT 'flowable' COMMENT '流程引擎类型 flowable/native' AFTER `effective_to`");
CALL sp_add_col_if_absent('flow_config', 'proc_def_key',
  "`proc_def_key` VARCHAR(128) NULL COMMENT 'Flowable 流程定义KEY' AFTER `engine_type`");
CALL sp_add_col_if_absent('flow_config', 'proc_def_id',
  "`proc_def_id` VARCHAR(128) NULL COMMENT 'Flowable 流程定义ID（key:version:id）' AFTER `proc_def_key`");
CALL sp_add_col_if_absent('flow_config', 'deployment_id',
  "`deployment_id` VARCHAR(128) NULL COMMENT 'Flowable 部署ID' AFTER `proc_def_id`");
CALL sp_add_col_if_absent('flow_config', 'bpmn_xml',
  "`bpmn_xml` LONGTEXT NULL COMMENT 'BPMN 2.0 XML 快照' AFTER `deployment_id`");
CALL sp_add_col_if_absent('flow_config', 'deploy_status',
  "`deploy_status` TINYINT NOT NULL DEFAULT 0 COMMENT '部署状态 0未部署 1已部署 2失败' AFTER `bpmn_xml`");
CALL sp_add_col_if_absent('flow_config', 'deploy_message',
  "`deploy_message` VARCHAR(500) NULL COMMENT '部署失败原因' AFTER `deploy_status`");
CALL sp_add_index_if_absent('flow_config', 'uk_flow_cfg_procdef',
  'UNIQUE KEY `uk_flow_cfg_procdef` (`proc_def_key`, `deleted`)');

-- ---------------------------------------------------------------------------
-- 2. flow_instance：绑定 Flowable 流程实例
-- ---------------------------------------------------------------------------
CALL sp_add_col_if_absent('flow_instance', 'proc_inst_id',
  "`proc_inst_id` VARCHAR(64) NULL COMMENT 'Flowable 流程实例ID' AFTER `current_node_key`");
CALL sp_add_col_if_absent('flow_instance', 'business_key',
  "`business_key` VARCHAR(64) NULL COMMENT 'Flowable 业务键（=doc_no）' AFTER `proc_inst_id`");
CALL sp_add_index_if_absent('flow_instance', 'uk_inst_proc',
  'UNIQUE KEY `uk_inst_proc` (`proc_inst_id`)');

-- ---------------------------------------------------------------------------
-- 3. flow_instance_node：绑定 Flowable 任务
-- ---------------------------------------------------------------------------
CALL sp_add_col_if_absent('flow_instance_node', 'task_id',
  "`task_id` VARCHAR(64) NULL COMMENT 'Flowable 任务ID' AFTER `candidate_ids`");
CALL sp_add_col_if_absent('flow_instance_node', 'node_source',
  "`node_source` TINYINT NOT NULL DEFAULT 1 COMMENT '节点来源 1引擎投影 2手工补录' AFTER `task_id`");
CALL sp_add_index_if_absent('flow_instance_node', 'idx_node_task',
  'KEY `idx_node_task` (`task_id`)');

-- ---------------------------------------------------------------------------
-- 清理工具过程
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `sp_add_col_if_absent`;
DROP PROCEDURE IF EXISTS `sp_add_index_if_absent`;

-- ---------------------------------------------------------------------------
-- 4. 校验
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('flow_config','flow_instance','flow_instance_node')
  AND COLUMN_NAME IN ('engine_type','proc_def_key','proc_def_id','deployment_id','bpmn_xml','deploy_status',
                      'proc_inst_id','business_key','task_id','node_source')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

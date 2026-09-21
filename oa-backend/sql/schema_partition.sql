-- =============================================================================
-- 大表分区改造（可选，单表千万行以上时启用）
-- MySQL 分区硬约束：分区键必须包含在每个唯一索引中
--   => 一旦分区，唯一索引只能保证「分区内唯一」，跨分区唯一性失效
--   => 因此 doc_no 的全局唯一不能依赖分区表的唯一索引，必须由 code_sequence 保证
-- =============================================================================
USE `haixiajin_oa`;


-- -----------------------------------------------------------------------------
-- 1. audit_log 按月分区【推荐】
--    审计日志只追加、无唯一性诉求、按时间查询/归档，是分区的最佳候选
-- -----------------------------------------------------------------------------
ALTER TABLE `audit_log`
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (`id`, `created_at`)
  PARTITION BY RANGE (TO_DAYS(`created_at`)) (
    PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
    PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION pmax    VALUES LESS THAN (MAXVALUE)
  );


-- -----------------------------------------------------------------------------
-- 2. 按月滚动新增分区（配合定时任务，在每月初执行）
--    例：为 2027-01 新增分区，并从 pmax 中切出
-- -----------------------------------------------------------------------------
-- ALTER TABLE `audit_log` REORGANIZE PARTITION pmax INTO (
--   PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
--   PARTITION pmax    VALUES LESS THAN (MAXVALUE)
-- );


-- -----------------------------------------------------------------------------
-- 3. 冷数据归档（按月分区可直接 DROP，秒级完成，不产生大事务）
-- -----------------------------------------------------------------------------
-- ALTER TABLE `audit_log` DROP PARTITION p202607;


-- -----------------------------------------------------------------------------
-- 4. document 分区【谨慎评估，默认不启用】
--    权衡：
--      + 单据表数据量最大，分区后按时间范围查询显著提速，归档便捷
--      - 主键必须改为 (id, created_at)，uk_document_no 必须带上 created_at，
--        导致 doc_no 跨分区唯一性失效（须改由 code_sequence + 分布式锁保证）
--      - 现有二级索引需重新评估
--    结论：单据量 < 1000 万时可先用索引 + 归档表；超过后再启用本方案
-- -----------------------------------------------------------------------------
-- ALTER TABLE `document`
--   DROP PRIMARY KEY,
--   DROP INDEX `uk_document_no`,
--   ADD PRIMARY KEY (`id`, `created_at`),
--   ADD UNIQUE KEY `uk_document_no` (`doc_no`, `deleted`, `created_at`)
--   PARTITION BY RANGE (TO_DAYS(`created_at`)) (
--     PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
--     PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
--     PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
--     PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
--     PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
--     PARTITION pmax    VALUES LESS THAN (MAXVALUE)
--   );


-- -----------------------------------------------------------------------------
-- 5. 验证分区生效
-- -----------------------------------------------------------------------------
SELECT `partition_name`, `table_rows`, `partition_expression`
FROM `information_schema`.`partitions`
WHERE `table_schema` = 'haixiajin_oa' AND `table_name` = 'audit_log'
ORDER BY `partition_ordinal_position`;

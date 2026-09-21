-- =============================================================================
-- 2026-09-20  附件能力补齐
--
--   flow_config_node.require_attachment —— 该节点办理时是否必须上传凭证。
--
--   为什么放在流程节点上，而不是写死在页面里：
--     「上传付款回单」的必填性是业务规则，随流程配置而变；写进前端就是硬编码，
--     换个流程就得改代码。放到节点上，配置改一行即生效。
--
--   安全性：这是「运行时规则」字段（与 allow_reject、allow_countersign 同类）。
--     改它不需要重新部署 BPMN，也不影响在途单据 —— 校验发生在审批动作那一刻，
--     按当前配置读表。所以可以就地更新存量节点，不违反「改结构必须新建版本」的约束。
--
--   兼容性：DEFAULT 0 保证所有存量节点行为完全不变；
--     只把办理类节点（node_type = 4）置为 1 —— 出纳付款要回单、用印办理要盖章件。
-- =============================================================================

ALTER TABLE `flow_config_node`
  ADD COLUMN `require_attachment` TINYINT NOT NULL DEFAULT 0
  COMMENT '该节点办理是否必须上传凭证 0否 1是' AFTER `allow_reject`;

UPDATE `flow_config_node`
   SET `require_attachment` = 1
 WHERE `node_type` = 4
   AND `deleted` = 0;

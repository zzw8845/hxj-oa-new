package com.hxj.oa.document.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.service.DocumentService;
import com.hxj.oa.flow.event.FlowLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 流程事件 → 单据状态回写。
 *
 * 这是「状态机闭合」的关键一环：
 * 原型里 approve/reject 只弹消息、不改状态，导致台账永远停在审批中。
 * 这里把引擎的每次推进都落成单据状态的确定性变更。
 *
 * 注意：必须用显式 UpdateWrapper 的 set() 而不是实体 updateById()。
 * MyBatis-Plus 默认字段策略是 NOT_NULL，updateById() 会静默忽略 null 字段，
 * 导致「办结/驳回后把当前节点置空」这类操作实际不生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowLifecycleListener {

    private final DocumentMapper documentMapper;

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onFlowEvent(FlowLifecycleEvent event) {
        Document doc = documentMapper.selectById(event.getDocumentId());
        if (doc == null) {
            log.warn("流程事件找不到单据 documentId={} stage={}", event.getDocumentId(), event.getStage());
            return;
        }

        var upd = Wrappers.<Document>lambdaUpdate()
                .eq(Document::getId, doc.getId())
                .set(Document::getFlowInstanceId, event.getInstanceId());

        switch (event.getStage()) {
            case STARTED, APPROVED, AUTO_SKIPPED -> {
                // 中间节点通过：状态维持审批中，刷新「当前所处节点」
                upd.set(Document::getStatus, DocumentService.STATUS_RUNNING)
                   .set(Document::getCurrentNodeKey, event.getNodeKey())
                   // nodeKey 为空说明后面已无任务（即将办结），节点信息一并清空
                   .set(Document::getCurrentNodeName,
                        event.getNodeKey() == null ? null : event.getNodeName());
            }
            case REJECTED -> {
                upd.set(Document::getStatus, DocumentService.STATUS_REJECTED)
                   .set(Document::getCurrentNodeKey, null)
                   .set(Document::getCurrentNodeName, null)
                   .set(Document::getClosedAt, LocalDateTime.now());
            }
            case FINISHED -> {
                upd.set(Document::getStatus, DocumentService.STATUS_APPROVED)
                   .set(Document::getCurrentNodeKey, null)
                   .set(Document::getCurrentNodeName, null)
                   .set(Document::getClosedAt, LocalDateTime.now());
            }
            default -> {
                return;
            }
        }
        documentMapper.update(null, upd);
        log.debug("单据状态回写 docNo={} stage={} node={} nodeName={}",
                doc.getDocNo(), event.getStage(), event.getNodeKey(), event.getNodeName());
    }
}

package com.hxj.oa.flow.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 流程生命周期事件。
 * flow 模块只管流程，单据状态由 document 模块监听本事件后更新，避免双向依赖。
 */
@Getter
public class FlowLifecycleEvent extends ApplicationEvent {

    public enum Stage {
        /** 流程启动 */
        STARTED,
        /** 节点通过 */
        APPROVED,
        /** 被驳回 */
        REJECTED,
        /** 流程结束（全部节点通过） */
        FINISHED,
        /** 节点因无处理人自动跳过 */
        AUTO_SKIPPED
    }

    private final Stage stage;
    private final Long documentId;
    private final Long instanceId;
    private final String nodeKey;
    private final String nodeName;
    private final String comment;
    private final Long operatorId;
    private final String operatorName;

    public FlowLifecycleEvent(Object source, Stage stage, Long documentId, Long instanceId,
                              String nodeKey, String nodeName, String comment,
                              Long operatorId, String operatorName) {
        super(source);
        this.stage = stage;
        this.documentId = documentId;
        this.instanceId = instanceId;
        this.nodeKey = nodeKey;
        this.nodeName = nodeName;
        this.comment = comment;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
    }
}

package com.hxj.oa.flow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FlowInstanceNodeMapper extends BaseMapper<FlowInstanceNode> {

    /** 某人的待办节点（引擎已认领/待认领的审批任务） */
    @Select("""
            SELECT n.* FROM flow_instance_node n
            WHERE n.deleted = 0 AND n.assignee_id = #{userId}
              AND n.node_type IN (1, 4) AND n.status IN (0, 1)
            ORDER BY n.created_at DESC
            """)
    List<FlowInstanceNode> selectTodoByAssignee(@Param("userId") Long userId);

    /** 某单据的完整流转记录（按顺序，用于「查看流程」弹窗） */
    @Select("""
            SELECT n.* FROM flow_instance_node n
            WHERE n.deleted = 0 AND n.document_id = #{documentId}
            ORDER BY n.seq_no ASC, n.id ASC
            """)
    List<FlowInstanceNode> selectHistoryByDocument(@Param("documentId") Long documentId);

    /** 某实例当前进行中的节点（用于推进/终止判断） */
    @Select("""
            SELECT n.* FROM flow_instance_node n
            WHERE n.deleted = 0 AND n.instance_id = #{instanceId} AND n.status IN (0, 1)
            ORDER BY n.seq_no ASC
            """)
    List<FlowInstanceNode> selectActiveByInstance(@Param("instanceId") Long instanceId);

    /**
     * 全公司进行中的审批节点（不限承办人），供风险预警做超期/临期盘点。
     *
     * <p>口径与 {@link #selectTodoByAssignee} 保持一致（node_type/status 同条件），
     * 区别只是不按 assignee_id 过滤 —— 风险预警看的是「全公司有哪些节点压在手里」。
     *
     * <p><b>为什么不直接查 ACT_RU_TASK</b>：Flowable 的运行时任务表与业务节点表通过
     * task_id 关联，但单据被撤回/驳回后引擎任务会被删除、而节点行的 task_id 仍留着旧值
     * （实测有 4 条这样的历史行）。以 ACT_RU_TASK 为准会随引擎内部状态漂移；
     * 以节点 status 为准并再用**单据状态**兜底（见 RiskService），口径才是稳定的。
     *
     * <p>deadline 为空的节点也要返回：它们同样占着审批人的手，属于风险披露范围
     * （「没有时限」本身就是一种风险）。
     */
    @Select("""
            SELECT n.* FROM flow_instance_node n
            WHERE n.deleted = 0 AND n.node_type IN (1, 4) AND n.status IN (0, 1)
            ORDER BY n.deadline IS NULL, n.deadline ASC, n.id ASC
            """)
    List<FlowInstanceNode> selectActiveNodes();
}

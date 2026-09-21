package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.flow.dto.AssigneeContext;
import com.hxj.oa.flow.dto.FlowPreviewVO;
import com.hxj.oa.flow.entity.FlowConfig;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.entity.FlowNodeAssignee;
import com.hxj.oa.flow.mapper.FlowConfigMapper;
import com.hxj.oa.flow.mapper.FlowConfigNodeMapper;
import com.hxj.oa.flow.mapper.FlowNodeAssigneeMapper;
import com.hxj.oa.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流程配置查询与预览。
 * 「预览」是本系统给业务方的一把尺子：把链路和每个节点实际会由谁审一次算清楚，
 * 避免上线后才发现「会计（按部门）」解析不出人。
 */
@Service
@RequiredArgsConstructor
public class FlowConfigService {

    private final FlowConfigMapper configMapper;
    private final FlowConfigNodeMapper nodeMapper;
    private final FlowNodeAssigneeMapper assigneeMapper;
    private final AssigneeResolver assigneeResolver;
    private final UserService userService;

    public List<FlowConfig> listByDocType(Long docTypeId) {
        return configMapper.selectList(Wrappers.<FlowConfig>lambdaQuery()
                .eq(docTypeId != null, FlowConfig::getDocTypeId, docTypeId)
                .ne(FlowConfig::getStatus, 2)
                .orderByAsc(FlowConfig::getDocTypeId)
                .orderByDesc(FlowConfig::getVersion));
    }

    public FlowConfig getById(Long id) {
        FlowConfig cfg = configMapper.selectById(id);
        if (cfg == null) {
            throw BizException.notFound("流程配置不存在: " + id);
        }
        return cfg;
    }

    public List<FlowConfigNode> nodesOf(Long flowConfigId) {
        return nodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, flowConfigId)
                .orderByAsc(FlowConfigNode::getSeqNo));
    }

    public Map<Long, List<FlowNodeAssignee>> assigneesOf(List<FlowConfigNode> nodes) {
        if (nodes.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = nodes.stream().map(FlowConfigNode::getId).toList();
        List<FlowNodeAssignee> rules = assigneeMapper.selectList(Wrappers.<FlowNodeAssignee>lambdaQuery()
                .in(FlowNodeAssignee::getNodeId, ids));
        return rules.stream().collect(java.util.stream.Collectors.groupingBy(FlowNodeAssignee::getNodeId));
    }

    /** 按指定单据上下文预览整条链路 */
    public FlowPreviewVO preview(Long flowConfigId, AssigneeContext ctx) {
        FlowConfig cfg = getById(flowConfigId);
        List<FlowConfigNode> nodes = nodesOf(flowConfigId);
        Map<Long, List<FlowNodeAssignee>> ruleMap = assigneesOf(nodes);

        FlowPreviewVO vo = new FlowPreviewVO();
        vo.setFlowConfigId(cfg.getId());
        vo.setFlowName(cfg.getName());
        vo.setProcDefKey(cfg.getProcDefKey());
        vo.setVersion(cfg.getVersion());
        vo.setDeployStatus(cfg.getDeployStatus());

        List<FlowPreviewVO.NodePreview> list = new ArrayList<>();
        for (FlowConfigNode n : nodes) {
            FlowPreviewVO.NodePreview np = new FlowPreviewVO.NodePreview();
            np.setNodeKey(n.getNodeKey());
            np.setNodeName(n.getNodeName());
            np.setNodeType(n.getNodeType());
            np.setSeqNo(n.getSeqNo());
            np.setConditionExpr(n.getConditionExpr());
            np.setSlaHours(n.getSlaHours());

            // 只有审批(1)与办理(4)节点需要解析处理人
            if (n.getNodeType() != null && (n.getNodeType() == 1 || n.getNodeType() == 4)) {
                AssigneeResolver.ResolveResult r = assigneeResolver.resolve(n.getId(), ctx);
                np.setCandidateIds(r.getCandidateIds());
                np.setCandidateNames(r.getCandidateIds().stream()
                        .map(userService::nameOf).filter(java.util.Objects::nonNull).toList());
                np.setHitRules(r.getHitRules());
                np.setSelfSkipped(r.isSelfSkipped());
                np.setUnresolved(r.isEmpty());
            } else {
                np.setCandidateIds(List.of());
                np.setCandidateNames(List.of());
                np.setHitRules(List.of());
            }
            // 该节点是否配置了指派规则（规则缺失是最常见的配置错误）
            np.setUnresolved(np.isUnresolved() || ruleMap.getOrDefault(n.getId(), List.of()).isEmpty());
            list.add(np);
        }
        vo.setNodes(list);
        return vo;
    }
}

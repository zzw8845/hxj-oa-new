package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.flow.entity.FlowConfig;
import com.hxj.oa.flow.entity.FlowConfigNode;
import com.hxj.oa.flow.mapper.FlowConfigMapper;
import com.hxj.oa.flow.mapper.FlowConfigNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 流程部署：配置层 → BPMN XML → Flowable 引擎。
 *
 * 部署产物回写到 flow_config（proc_def_id / deployment_id / bpmn_xml），
 * 这样业务侧能随时看到「线上跑的到底是哪个版本的哪份 XML」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowDeployService {

    public static final int DEPLOY_NONE = 0;
    public static final int DEPLOY_OK = 1;
    public static final int DEPLOY_FAIL = 2;

    private final RepositoryService repositoryService;
    private final BpmnGenerator bpmnGenerator;
    private final FlowConfigMapper configMapper;
    private final FlowConfigNodeMapper nodeMapper;

    /** 部署（或重新部署）指定流程配置，返回更新后的配置 */
    @Transactional(rollbackFor = Exception.class)
    public FlowConfig deploy(Long flowConfigId) {
        FlowConfig cfg = configMapper.selectById(flowConfigId);
        if (cfg == null) {
            throw BizException.notFound("流程配置不存在: " + flowConfigId);
        }
        List<FlowConfigNode> nodes = nodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, flowConfigId)
                .orderByAsc(FlowConfigNode::getSeqNo));
        if (nodes.isEmpty()) {
            throw BizException.of("流程 %s 未配置任何节点，无法部署", cfg.getName());
        }
        if (cfg.getProcDefKey() == null || cfg.getProcDefKey().isBlank()) {
            cfg.setProcDefKey(buildProcDefKey(cfg));
        }

        String xml = bpmnGenerator.generate(cfg, nodes);
        FlowConfig upd = new FlowConfig();
        upd.setId(cfg.getId());
        upd.setProcDefKey(cfg.getProcDefKey());
        upd.setBpmnXml(xml);

        try {
            Deployment deployment = repositoryService.createDeployment()
                    .name(cfg.getName() + " v" + cfg.getVersion())
                    .category(cfg.getCategory())
                    .addString(cfg.getProcDefKey() + ".bpmn20.xml", xml)
                    .deploy();

            ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .processDefinitionKey(cfg.getProcDefKey())
                    .singleResult();
            if (pd == null) {
                throw new IllegalStateException("部署成功但未找到流程定义，请检查 BPMN 是否包含 process 元素");
            }

            upd.setDeploymentId(deployment.getId());
            upd.setProcDefId(pd.getId());
            upd.setDeployStatus(DEPLOY_OK);
            upd.setDeployMessage("部署成功：" + pd.getId());
            configMapper.updateById(upd);

            log.info("流程部署成功 flowConfigId={} procDefKey={} procDefId={}",
                    cfg.getId(), cfg.getProcDefKey(), pd.getId());
        } catch (Exception e) {
            upd.setDeployStatus(DEPLOY_FAIL);
            upd.setDeployMessage(truncate(e.getMessage(), 500));
            configMapper.updateById(upd);
            log.error("流程部署失败 flowConfigId={} key={}", cfg.getId(), cfg.getProcDefKey(), e);
            throw new BizException("流程部署失败：" + e.getMessage());
        }
        return configMapper.selectById(cfg.getId());
    }

    /** 生成流程定义 KEY：单据类型编码 + 版本，如 DAILY_PAYMENT_V1 */
    private String buildProcDefKey(FlowConfig cfg) {
        String docTypeCode = configMapper.selectDocTypeCode(cfg.getDocTypeId());
        String base = (docTypeCode == null || docTypeCode.isBlank())
                ? ("FLOW_" + cfg.getDocTypeId())
                : docTypeCode;
        return base + "_V" + cfg.getVersion();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 校验 BPMN 是否可用（不部署，仅解析） */
    public void validate(Long flowConfigId) {
        FlowConfig cfg = configMapper.selectById(flowConfigId);
        if (cfg == null) {
            throw BizException.notFound("流程配置不存在: " + flowConfigId);
        }
        List<FlowConfigNode> nodes = nodeMapper.selectList(Wrappers.<FlowConfigNode>lambdaQuery()
                .eq(FlowConfigNode::getFlowConfigId, flowConfigId));
        cfg.setProcDefKey(cfg.getProcDefKey() == null ? buildProcDefKey(cfg) : cfg.getProcDefKey());
        bpmnGenerator.generate(cfg, nodes);
    }
}

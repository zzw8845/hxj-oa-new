package com.hxj.oa.flow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.util.JsonColumn;
import com.hxj.oa.flow.dto.AssigneeContext;
import com.hxj.oa.flow.entity.FlowNodeAssignee;
import com.hxj.oa.flow.mapper.FlowNodeAssigneeMapper;
import com.hxj.oa.system.mapper.SysUserMapper;
import com.hxj.oa.system.service.DepartmentService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 节点指派规则解析器 —— 本系统的技术核心。
 *
 * 节点不挂「允许审批的角色」，而挂「指派规则」，运行时按单据上下文解析出真正的处理人。
 * 这样才能表达原型里那些写不出来的流程：「会计（按部门）」「公司领导（大额）」「直属主管」。
 *
 * 支持 6 种规则（flow_node_assignee.rule_type）：
 * <pre>
 *   initiator_leader  取发起人部门负责人（可用 deptId 覆盖）
 *   dept_role         部门 + 角色，支持 fallback=company 降级
 *   biztype_role      按业务类型映射角色
 *   role              直接按角色编码
 *   user              指定具体人
 *   condition         条件触发（满足表达式才生成节点）
 * </pre>
 *
 * 硬规则：申请人不能审批自己的单据。解析结果会自动剔除 applicantId，
 * 若剔除后为空，由调用方决定「自动通过」还是「人工兜底」（见 FlowRuntimeService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssigneeResolver {

    private final FlowNodeAssigneeMapper assigneeMapper;
    private final SysUserMapper userMapper;
    private final DepartmentService departmentService;

    @Data
    @Builder
    public static class ResolveResult {
        /** 最终候选人（已剔除申请人本人） */
        private List<Long> candidateIds;
        /** 命中的规则类型（便于排查「为什么是他审」） */
        private List<String> hitRules;
        /** 是否因为「申请人即审批人」而被全部跳过 */
        private boolean selfSkipped;
        /** 会签方式：1 或签 2 会签 3 依次审批（取第一条命中规则） */
        private Integer signMode;

        public boolean isEmpty() {
            return candidateIds == null || candidateIds.isEmpty();
        }
    }

    public ResolveResult resolve(Long nodeId, AssigneeContext ctx) {
        List<FlowNodeAssignee> rules = assigneeMapper.selectList(Wrappers.<FlowNodeAssignee>lambdaQuery()
                .eq(FlowNodeAssignee::getNodeId, nodeId)
                .orderByAsc(FlowNodeAssignee::getSortNo));

        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        List<String> hitRules = new ArrayList<>();
        Integer signMode = 1;

        for (FlowNodeAssignee rule : rules) {
            Map<String, Object> param = JsonColumn.toMap(rule.getRuleValue());
            List<Long> resolved = switch (rule.getRuleType()) {
                case "initiator_leader" -> byInitiatorLeader(param, ctx);
                case "dept_role" -> byDeptRole(param, ctx);
                case "biztype_role" -> byBizTypeRole(param, ctx);
                case "role" -> byRole(param, ctx);
                case "user" -> byUser(param);
                case "condition" -> byCondition(param, ctx);
                default -> {
                    log.warn("未知的指派规则类型: {}", rule.getRuleType());
                    yield List.of();
                }
            };
            if (!resolved.isEmpty()) {
                hitRules.add(rule.getRuleType());
                if (signMode == 1 && rule.getSignMode() != null) {
                    signMode = rule.getSignMode();
                }
                ids.addAll(resolved);
            }
        }

        boolean selfSkipped = false;
        if (ctx.getApplicantId() != null && ids.contains(ctx.getApplicantId())) {
            ids.remove(ctx.getApplicantId());
            selfSkipped = true;
        }

        // 防御纵深：解析出来的人必须**仍然可用**（在岗且未删除）。
        // 放在出口而不是每条规则里 —— 将来新增一种规则不会因为"忘了校验"而静默漏掉。
        // 为什么必须做：AssigneeTaskListener 只要 size()==1 就 setAssignee，
        // 空列表有 auto_skip 兜底，而"非空废人"会被静默永久卡死。
        if (!ids.isEmpty()) {
            int before = ids.size();
            ids.retainAll(userMapper.selectAliveIds(new ArrayList<>(ids)));
            if (ids.size() < before) {
                log.warn("节点 {} 解析出的审批人中有 {} 个已停用/已删除，已剔除：剩余 {} 人",
                        nodeId, before - ids.size(), ids.size());
            }
        }

        if (ids.isEmpty()) {
            log.warn("节点 {} 未解析出任何审批人 ctx(applicant={}, dept={}, category={})",
                    nodeId, ctx.getApplicantId(), ctx.getApplicantDeptId(), ctx.getBizCategory());
        } else {
            log.debug("节点 {} 解析出审批人 {} 命中规则 {}", nodeId, ids, hitRules);
        }

        return ResolveResult.builder()
                .candidateIds(new ArrayList<>(ids))
                .hitRules(hitRules)
                .selfSkipped(selfSkipped)
                .signMode(signMode)
                .build();
    }

    // ------------------------------------------------------------------ 规则实现

    /** 取发起人部门负责人；部门为空时按 fallbackRoleCode 兜底 */
    private List<Long> byInitiatorLeader(Map<String, Object> param, AssigneeContext ctx) {
        // 不能写成 Objects.requireNonNullElse(param, ctx)：两者都为 null 时它**直接抛 NPE**
        // —— 这是「流程预览 500」的确切根因（规则里没配 deptId，而申请人自己还没有部门）。
        Long deptId = JsonColumn.toLong(param, "deptId");
        if (deptId == null) {
            deptId = ctx.getApplicantDeptId();
        }
        if (deptId != null) {
            Long leaderId = departmentService.leaderIdOf(deptId);
            if (leaderId != null) {
                return List.of(leaderId);
            }
        }
        String fallbackRole = JsonColumn.str(param, "fallbackRoleCode");
        if (fallbackRole != null) {
            return userMapper.selectUserIdsByRole(fallbackRole, ctx.getCompanyId());
        }
        return List.of();
    }

    /** 部门 + 角色；本部门无该角色时可降级到全公司 */
    private List<Long> byDeptRole(Map<String, Object> param, AssigneeContext ctx) {
        String roleCode = JsonColumn.str(param, "roleCode");
        if (roleCode == null) {
            return List.of();
        }
        Long deptId = JsonColumn.toLong(param, "deptId");
        List<Long> result;
        if (deptId != null) {
            // 规则显式指定部门（如「综合管理部用印管理」）
            result = userMapper.selectUserIdsByDeptAndRole(deptId, roleCode);
            if (result.isEmpty() && "company".equals(JsonColumn.str(param, "fallback"))) {
                result = userMapper.selectUserIdsByRole(roleCode, ctx.getCompanyId());
            }
        } else {
            // 按发起人所在部门分派（如「会计（按部门）」）
            result = ctx.getApplicantDeptId() == null
                    ? List.of()
                    : userMapper.selectUserIdsByDeptAndRole(ctx.getApplicantDeptId(), roleCode);
            if (result.isEmpty() && "company".equals(JsonColumn.str(param, "fallback"))) {
                result = userMapper.selectUserIdsByRole(roleCode, ctx.getCompanyId());
            }
        }
        return result;
    }

    /** 按业务类型选角色，如 {"DAILY":"ACCOUNTANT","SEAL":"DEPT_HEAD"} */
    private List<Long> byBizTypeRole(Map<String, Object> param, AssigneeContext ctx) {
        Map<String, Object> mapping = JsonColumn.toMap(JsonColumn.str(param, "bizRoleMap"));
        String roleCode = mapping == null ? null : (String) mapping.get(ctx.getBizCategory());
        if (roleCode == null) {
            roleCode = JsonColumn.str(param, "defaultRoleCode");
        }
        return roleCode == null ? List.of() : userMapper.selectUserIdsByRole(roleCode, ctx.getCompanyId());
    }

    private List<Long> byRole(Map<String, Object> param, AssigneeContext ctx) {
        String roleCode = JsonColumn.str(param, "roleCode");
        return roleCode == null ? List.of() : userMapper.selectUserIdsByRole(roleCode, ctx.getCompanyId());
    }

    private List<Long> byUser(Map<String, Object> param) {
        return new ArrayList<>(JsonColumn.toLongList(param, "userIds"));
    }

    /**
     * 条件触发：满足表达式时，把 targetRoleCode / targetUserIds 加入候选人。
     * 表达式只支持简单比较（金额阈值、等值判断），复杂条件走条件网关。
     */
    private List<Long> byCondition(Map<String, Object> param, AssigneeContext ctx) {
        String expr = JsonColumn.str(param, "expr");
        if (!matches(expr, ctx)) {
            return List.of();
        }
        List<Long> users = new ArrayList<>(JsonColumn.toLongList(param, "userIds"));
        String roleCode = JsonColumn.str(param, "roleCode");
        if (roleCode != null) {
            users.addAll(userMapper.selectUserIdsByRole(roleCode, ctx.getCompanyId()));
        }
        return users;
    }

    /** 极简表达式求值：支持 "amount >= 20000"、"sealType == 'OFFICIAL'"、"amount > 1000 && amount < 5000" */
    private boolean matches(String expr, AssigneeContext ctx) {
        if (expr == null || expr.isBlank()) {
            return true;
        }
        for (String part : expr.split("&&")) {
            if (!evalSingle(part.trim(), ctx)) {
                return false;
            }
        }
        return true;
    }

    private boolean evalSingle(String expr, AssigneeContext ctx) {
        String op;
        if (expr.contains(">=")) {
            op = ">=";
        } else if (expr.contains("<=")) {
            op = "<=";
        } else if (expr.contains("==")) {
            op = "==";
        } else if (expr.contains("!=")) {
            op = "!=";
        } else if (expr.contains(">")) {
            op = ">";
        } else if (expr.contains("<")) {
            op = "<";
        } else {
            return true;
        }

        String[] kv = expr.split(java.util.regex.Pattern.quote(op), 2);
        String left = kv[0].trim();
        String right = kv[1].trim().replace("'", "").replace("\"", "");
        Object actual = left.equals("amount") ? ctx.getAmount() : ctx.var(left);
        if (actual == null) {
            return false;
        }

        boolean numeric = actual instanceof Number || isNumeric(String.valueOf(actual));
        if (numeric && !"==".equals(op) && !"!=".equals(op)) {
            double a = Double.parseDouble(String.valueOf(actual));
            double b;
            try {
                b = Double.parseDouble(right);
            } catch (NumberFormatException e) {
                return false;
            }
            return switch (op) {
                case ">=" -> a >= b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                case "<" -> a < b;
                default -> false;
            };
        }

        String a = String.valueOf(actual);
        return switch (op) {
            case "==" -> a.equals(right);
            case "!=" -> !a.equals(right);
            default -> false;
        };
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 批量解析多个节点（流程预览用） */
    public Map<String, List<Long>> resolveAll(List<Long> nodeIds, AssigneeContext ctx) {
        return nodeIds.stream().collect(Collectors.toMap(
                String::valueOf,
                id -> resolve(id, ctx).getCandidateIds(),
                (a, b) -> a,
                LinkedHashMap::new));
    }
}

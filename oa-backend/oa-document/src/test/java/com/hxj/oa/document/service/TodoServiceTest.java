package com.hxj.oa.document.service;

import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.entity.DocumentType;
import com.hxj.oa.document.mapper.AttachmentMapper;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import com.hxj.oa.document.mapper.NotificationMapper;
import com.hxj.oa.flow.dto.TodoVO;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import com.hxj.oa.flow.service.FlowRuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 我的待办可见性单测。
 *
 * <h2>这里锁住的是一个「反直觉的正确决定」</h2>
 * {@code TodoService} 里曾经也有一段与 {@code DocumentService.assertVisible} 同款的
 * 数据范围复刻，DEPT/CENTER/CUSTOM_DEPT 分支同样带恒真条件
 * （{@code user.getDeptPath().length() > 1}）。它看起来是个越权漏洞，但实际不是：
 * 待办的数据源 {@code selectTodoByAssignee} 的唯一条件就是 {@code assignee_id = 我}，
 * 所以待办里的每一张单据，当前用户都是它的**指定承办人** —— 而「承办人可见」正是
 * 详情口径里明确的放行分支。在这里叠加数据范围，只会**隐藏用户必须处理的待办**。
 *
 * <p>实测：zhouzh（DEPT 范围，部门 6）名下 4 条待办全部落在 dept 8，严格按部门子树判定
 * 会让这 4 条一起消失。所以那段过滤已被删除，本测试把这个结论固定下来 ——
 * 将来若有人「顺手把范围判定补回去」，这里会红。
 */
@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock private FlowInstanceNodeMapper instanceNodeMapper;
    @Mock private DocumentMapper documentMapper;
    @Mock private DocumentTypeMapper docTypeMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private FlowRuntimeService flowRuntimeService;

    @InjectMocks private TodoService todoService;

    /** 演示库角色镜像：周综合 DEPT_HEAD，部门 6「综合管理中心」，path /6/ */
    private static LoginUser zhouzh() {
        return LoginUser.builder()
                .userId(8L).account("zhouzh").realName("周综合")
                .companyId(1L).deptId(6L).deptPath("/6/")
                .dataScope(DataScopeType.DEPT)
                .roleCodes(Set.of("DEPT_HEAD"))
                .build();
    }

    private static FlowInstanceNode todoNode(long nodeId, long docId, long assigneeId) {
        FlowInstanceNode n = new FlowInstanceNode();
        n.setId(nodeId);
        n.setInstanceId(100L + nodeId);
        n.setDocumentId(docId);
        n.setNodeKey("n2");
        n.setNodeName("部门负责人审批");
        n.setNodeType(1);
        n.setSeqNo(1);
        n.setStatus(0);
        n.setAssigneeId(assigneeId);
        n.setTaskId("task-" + nodeId);
        return n;
    }

    private static Document doc(long id, Long deptId, int status) {
        Document d = new Document();
        d.setId(id);
        d.setCompanyId(1L);
        d.setDeptId(deptId);
        d.setDeptName("业务一部");
        d.setApplicantId(9L);
        d.setApplicantName("黄小明");
        d.setDocTypeId(10L);
        d.setDocNo("FK202601010001");
        d.setTitle("采购服务器设备款");
        d.setStatus(status);
        return d;
    }

    private static DocumentType docType() {
        DocumentType dt = new DocumentType();
        dt.setId(10L);
        dt.setCode("DAILY_PAYMENT");
        dt.setName("日常付款");
        dt.setFlowConfigId(null); // findConfigNode 返回 null，走默认动作开关
        return dt;
    }

    @Test
    @DisplayName("★跨部门待办仍然可见：我是指定承办人，就不该被数据范围挡住")
    void crossDeptTodoIsNotHiddenByDataScope() {
        // 待办落在 dept 8（业务一部），而 zhouzh 的数据范围是部门 6 的子树 —— 单据不在他范围内
        when(instanceNodeMapper.selectTodoByAssignee(8L)).thenReturn(List.of(todoNode(32L, 26L, 8L)));
        when(documentMapper.selectById(26L)).thenReturn(doc(26L, 8L, DocumentService.STATUS_RUNNING));
        when(docTypeMapper.selectById(10L)).thenReturn(docType());

        List<TodoVO> todos = todoService.myTodo(zhouzh(), 20);

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).getDocumentId()).isEqualTo(26L);
        assertThat(todos.get(0).getDeptName()).isEqualTo("业务一部");
    }

    @Test
    @DisplayName("多条跨部门待办全部保留（对应演示库 zhouzh 的 4 条）")
    void allCrossDeptTodosSurvive() {
        when(instanceNodeMapper.selectTodoByAssignee(8L)).thenReturn(List.of(
                todoNode(32L, 26L, 8L),
                todoNode(44L, 32L, 8L),
                todoNode(50L, 34L, 8L),
                todoNode(61L, 38L, 8L)));
        when(documentMapper.selectById(26L)).thenReturn(doc(26L, 8L, DocumentService.STATUS_RUNNING));
        when(documentMapper.selectById(32L)).thenReturn(doc(32L, 8L, DocumentService.STATUS_RUNNING));
        when(documentMapper.selectById(34L)).thenReturn(doc(34L, 8L, DocumentService.STATUS_RUNNING));
        when(documentMapper.selectById(38L)).thenReturn(doc(38L, 8L, DocumentService.STATUS_RUNNING));
        when(docTypeMapper.selectById(10L)).thenReturn(docType());

        assertThat(todoService.myTodo(zhouzh(), 20)).hasSize(4);
    }

    @Test
    @DisplayName("单据已办结的待办视为过期，仍要被过滤掉（这条过滤是真实生效的）")
    void finishedDocumentTodoIsFiltered() {
        when(instanceNodeMapper.selectTodoByAssignee(8L)).thenReturn(List.of(todoNode(32L, 26L, 8L)));
        when(documentMapper.selectById(26L)).thenReturn(doc(26L, 8L, DocumentService.STATUS_APPROVED));

        assertThat(todoService.myTodo(zhouzh(), 20)).isEmpty();
    }

    @Test
    @DisplayName("limit 生效：超出上限时按顺序截断")
    void limitIsApplied() {
        when(instanceNodeMapper.selectTodoByAssignee(8L)).thenReturn(List.of(
                todoNode(32L, 26L, 8L),
                todoNode(44L, 32L, 8L)));
        when(documentMapper.selectById(26L)).thenReturn(doc(26L, 8L, DocumentService.STATUS_RUNNING));
        when(docTypeMapper.selectById(10L)).thenReturn(docType());

        assertThat(todoService.myTodo(zhouzh(), 1)).hasSize(1);
    }
}

package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.util.DataScopeHelper;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.mapper.AttachmentMapper;
import com.hxj.oa.document.mapper.DocumentLinkMapper;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.mapper.DocumentTypeMapper;
import com.hxj.oa.flow.entity.FlowInstanceNode;
import com.hxj.oa.flow.mapper.FlowConfigMapper;
import com.hxj.oa.flow.mapper.FlowInstanceNodeMapper;
import com.hxj.oa.flow.service.FlowRuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 单据可见性判定单测 —— 横向越权（IDOR）的回归防线。
 *
 * <h2>这个测试在防什么</h2>
 * {@code DocumentService.assertVisible} 曾被实现成「用一段 Java switch 复刻数据范围」，
 * 其中 DEPT/CENTER/CUSTOM_DEPT 分支的第二个条件是
 * {@code user.getDeptPath() != null && user.getDeptPath().length() > 1} —— 它**只和用户有关**，
 * 只要用户挂在任何一个部门下就恒为 true。结果是任何 dept 范围的用户，只要拿到单据 ID，
 * 就能读到自己列表里根本不存在的单据（详情 + 附件列表 + 附件下载）。
 *
 * <p>根因不是写错了一个条件，而是「列表走 SQL 片段、详情走 Java 分支」两套实现并存。
 * 现在 {@code assertVisible} 改为把 {@link DataScopeHelper#buildClause} 生成的片段
 * 原样套回本单据，因此本测试有两个层面的断言：
 *
 * <ol>
 *   <li><b>行为层</b>：跨部门（且非参与人）必须 403；部门内/本人/管理员/参与者必须放行。</li>
 *   <li><b>结构层</b>：真正发给数据库的 {@code Wrapper} 里，必须逐字包含
 *       {@code DataScopeHelper.buildClause("", user)} —— 这条断言让「口径漂移」在编译期之后
 *       依然能被测试拦住；只要有人再往 assertVisible 里塞第二种判定，它就会红。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceVisibilityTest {

    @Mock private DocumentMapper documentMapper;
    @Mock private DocumentTypeMapper docTypeMapper;
    @Mock private DocumentLinkMapper linkMapper;
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private FormTemplateService formTemplateService;
    @Mock private DocNoGenerator docNoGenerator;
    @Mock private FlowRuntimeService flowRuntimeService;
    @Mock private FlowConfigMapper flowConfigMapper;
    @Mock private FlowInstanceNodeMapper flowInstanceNodeMapper;

    @InjectMocks private DocumentService documentService;

    // ============================================================ 夹具

    private static LoginUser user(DataScopeType scope, Long userId, Long companyId,
                                  Long deptId, String deptPath, String... roles) {
        return LoginUser.builder()
                .userId(userId).account("u").realName("测试用户")
                .companyId(companyId).deptId(deptId).deptPath(deptPath)
                .dataScope(scope)
                .roleCodes(Set.of(roles))
                .build();
    }

    private static Document doc(long id, Long companyId, Long deptId, Long applicantId) {
        Document d = new Document();
        d.setId(id);
        d.setCompanyId(companyId);
        d.setDeptId(deptId);
        d.setApplicantId(applicantId);
        d.setDocNo("FK202601010001");
        d.setStatus(DocumentService.STATUS_RUNNING);
        return d;
    }

    /** 演示库角色镜像：周综合 DEPT_HEAD，部门 6「综合管理中心」，path /6/ */
    private static LoginUser zhouzh() {
        return user(DataScopeType.DEPT, 8L, 1L, 6L, "/6/", "DEPT_HEAD");
    }

    /** 业务一部（dept 8）的单据，申请人黄小明（user 9） */
    private static Document otherDeptDoc() {
        return doc(1L, 1L, 8L, 9L);
    }

    private static BizException assertForbidden(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).as("应抛出 BizException").isInstanceOf(BizException.class);
        return (BizException) t;
    }

    // ============================================================ 行为层

    @Nested
    @DisplayName("数据范围内 / 外")
    class Scope {

        @Test
        @DisplayName("★越权回归：DEPT 范围用户读跨部门单据，且他不是流程参与者 → 403")
        void crossDeptDocumentIsForbidden() {
            when(documentMapper.selectCount(any())).thenReturn(0L);
            when(flowInstanceNodeMapper.selectHistoryByDocument(1L)).thenReturn(List.of());

            BizException ex = assertForbidden(() -> documentService.assertVisible(otherDeptDoc(), zhouzh()));

            assertThat(ex.getCode()).isEqualTo(403);
            assertThat(ex.getMessage()).contains("无权");
        }

        @Test
        @DisplayName("DEPT 范围用户读本部门子树内的单据 → 放行（同一段代码不能一律拒绝）")
        void sameDeptDocumentIsVisible() {
            when(documentMapper.selectCount(any())).thenReturn(1L);

            assertThatCode(() -> documentService.assertVisible(otherDeptDoc(),
                    user(DataScopeType.DEPT, 7L, 1L, 8L, "/8/", "DEPT_HEAD")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SELF 范围用户读本人发起的单据 → 放行")
        void ownDocumentIsVisibleToSelf() {
            when(documentMapper.selectCount(any())).thenReturn(1L);

            assertThatCode(() -> documentService.assertVisible(otherDeptDoc(),
                    user(DataScopeType.SELF, 9L, 1L, 8L, "/8/", "EMPLOYEE")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SELF 范围用户读别人发起的单据 → 403（夹具里 userId 必须≠申请人）")
        void othersDocumentIsForbiddenToSelf() {
            when(documentMapper.selectCount(any())).thenReturn(0L);
            when(flowInstanceNodeMapper.selectHistoryByDocument(anyLong())).thenReturn(List.of());

            // 单据申请人是 9（黄小明），这里换成 7（林经理）—— 否则测的是「本人可见」，白测
            BizException ex = assertForbidden(() -> documentService.assertVisible(otherDeptDoc(),
                    user(DataScopeType.SELF, 7L, 1L, 8L, "/8/", "EMPLOYEE")));

            assertThat(ex.getCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("COMPANY 范围同公司可见（哪怕部门完全不同）")
        void companyScopeSeesSameCompany() {
            when(documentMapper.selectCount(any())).thenReturn(1L);

            assertThatCode(() -> documentService.assertVisible(otherDeptDoc(),
                    user(DataScopeType.COMPANY, 5L, 1L, 4L, "/2/4/", "CASHIER")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("COMPANY 范围也跨不过公司边界 → 403")
        void companyScopeIsolatesCompanies() {
            Document otherCompanyDoc = doc(2L, 2L, 8L, 9L);
            when(documentMapper.selectCount(any())).thenReturn(0L);
            when(flowInstanceNodeMapper.selectHistoryByDocument(anyLong())).thenReturn(List.of());

            BizException ex = assertForbidden(() -> documentService.assertVisible(otherCompanyDoc,
                    user(DataScopeType.COMPANY, 5L, 1L, 4L, "/2/4/", "CASHIER")));
            assertThat(ex.getCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("CUSTOM_DEPT 同样走片段判定，不享受额外豁免")
        void customDeptIsAlsoRestricted() {
            when(documentMapper.selectCount(any())).thenReturn(0L);
            when(flowInstanceNodeMapper.selectHistoryByDocument(1L)).thenReturn(List.of());

            BizException ex = assertForbidden(() -> documentService.assertVisible(otherDeptDoc(),
                    user(DataScopeType.CUSTOM_DEPT, 8L, 1L, 6L, "/6/", "DEPT_HEAD")));
            assertThat(ex.getCode()).isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("流程参与者放行（审批人不能被挡在门外）")
    class Participant {

        @Test
        @DisplayName("跨部门单据但我是其审批人（assignee）→ 放行")
        void assigneeCanOpen() {
            when(documentMapper.selectCount(any())).thenReturn(0L);
            FlowInstanceNode node = new FlowInstanceNode();
            node.setDocumentId(1L);
            node.setAssigneeId(8L);
            when(flowInstanceNodeMapper.selectHistoryByDocument(1L)).thenReturn(List.of(node));

            assertThatCode(() -> documentService.assertVisible(otherDeptDoc(), zhouzh()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("跨部门单据但我在候选处理人（会签/或签）里 → 放行")
        void candidateCanOpen() {
            when(documentMapper.selectCount(any())).thenReturn(0L);
            FlowInstanceNode node = new FlowInstanceNode();
            node.setDocumentId(1L);
            node.setAssigneeId(2L);
            node.setCandidateIds("[2,8]");
            when(flowInstanceNodeMapper.selectHistoryByDocument(1L)).thenReturn(List.of(node));

            assertThatCode(() -> documentService.assertVisible(otherDeptDoc(), zhouzh()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("候选人是别人（不含我的 ID）→ 仍然 403，不能因为「有人候选」就放开")
        void unrelatedCandidateDoesNotGrantAccess() {
            when(documentMapper.selectCount(any())).thenReturn(0L);
            FlowInstanceNode node = new FlowInstanceNode();
            node.setDocumentId(1L);
            node.setAssigneeId(2L);
            node.setCandidateIds("[2,4]");
            when(flowInstanceNodeMapper.selectHistoryByDocument(1L)).thenReturn(List.of(node));

            BizException ex = assertForbidden(() -> documentService.assertVisible(otherDeptDoc(), zhouzh()));
            assertThat(ex.getCode()).isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("管理员与无限制场景")
    class Bypass {

        @Test
        @DisplayName("ADMIN 直接放行，一次库都不用查（避免管理端被范围判定拖慢）")
        void adminBypassesWithoutQuery() {
            assertThatCode(() -> documentService.assertVisible(otherDeptDoc(),
                    user(DataScopeType.COMPANY, 1L, 1L, 6L, "/6/", "ADMIN")))
                    .doesNotThrowAnyException();
            verifyNoInteractions(documentMapper);
        }

        @Test
        @DisplayName("buildClause 返回 null（无任何行级限制）时视为可见 —— 与列表「不加过滤」严格一致")
        void nullClauseMeansUnrestricted() {
            LoginUser noCompany = user(DataScopeType.COMPANY, 5L, null, 8L, "/8/", "GM");
            assertThat(DataScopeHelper.buildClause("", noCompany)).isNull();

            assertThatCode(() -> documentService.assertVisible(otherDeptDoc(), noCompany))
                    .doesNotThrowAnyException();
            verifyNoInteractions(documentMapper);
        }

        @Test
        @DisplayName("count 返回 null（驱动异常等）不得当成放行")
        void nullCountIsDeny() {
            when(documentMapper.selectCount(any())).thenReturn(null);
            when(flowInstanceNodeMapper.selectHistoryByDocument(1L)).thenReturn(List.of());

            assertThat(assertForbidden(() -> documentService.assertVisible(otherDeptDoc(), zhouzh())).getCode())
                    .isEqualTo(403);
        }
    }

    // ============================================================ 结构层：口径同源

    @Nested
    @DisplayName("同源保证：详情用的 SQL 片段必须逐字就是列表用的那一个")
    class SameSource {

        @Test
        @DisplayName("★发给数据库的条件里逐字包含 DataScopeHelper.buildClause(\"\", user)")
        void usesExactlyTheListClause() {
            LoginUser u = zhouzh();
            when(documentMapper.selectCount(any())).thenReturn(0L);
            when(flowInstanceNodeMapper.selectHistoryByDocument(anyLong())).thenReturn(List.of());

            assertForbidden(() -> documentService.assertVisible(otherDeptDoc(), u));

            ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(documentMapper).selectCount(captor.capture());
            String sql = ((QueryWrapper<Document>) captor.getValue()).getSqlSegment();

            // 逐字包含 —— 只要有人再在 assertVisible 里写第二套判定，这条就会红
            assertThat(sql).contains(DataScopeHelper.buildClause("", u));
            assertThat(sql).contains("dept_id IN (SELECT id FROM department");
            assertThat(sql).contains("path LIKE '/6/%'");
            // 且必须真的按单据主键取，不能退化成扫全表
            assertThat(sql).contains("id");
        }

        @Test
        @DisplayName("跨部门判定不依赖用户的 deptPath 长度 —— 正是旧实现恒真的来源")
        void doesNotDependOnDeptPathLength() {
            // 两个用户除了 deptPath 之外完全一样；旧实现在这里都会放行，
            // 新实现必须两者一致地依赖 SQL 片段结果（都是 0 → 都 403）。
            when(documentMapper.selectCount(any())).thenReturn(0L);
            when(flowInstanceNodeMapper.selectHistoryByDocument(anyLong())).thenReturn(List.of());

            LoginUser withPath = user(DataScopeType.DEPT, 8L, 1L, 6L, "/6/", "DEPT_HEAD");
            LoginUser withDeepPath = user(DataScopeType.DEPT, 8L, 1L, 6L, "/1/6/9/", "DEPT_HEAD");

            assertThat(assertForbidden(() -> documentService.assertVisible(otherDeptDoc(), withPath)).getCode())
                    .isEqualTo(403);
            assertThat(assertForbidden(() -> documentService.assertVisible(otherDeptDoc(), withDeepPath)).getCode())
                    .isEqualTo(403);
        }
    }
}

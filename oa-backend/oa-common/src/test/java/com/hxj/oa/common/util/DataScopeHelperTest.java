package com.hxj.oa.common.util;

import com.hxj.oa.common.security.DataScopeType;
import com.hxj.oa.common.security.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DataScopeHelper} 行级数据范围片段生成器单测。
 *
 * <p>这里的断言刻意是**逐字符比对生成的 SQL**，而不是「看着差不多」。
 * 原因：这个片段是全系统行级权限的**唯一防线**（MySQL 侧没有 RLS 兜底），
 * 而它被 4 个查询入口共用（单据列表、看板统计、台账导出、风险预警）。
 * 一旦片段形状变了，四个入口的可见性会**同时**改变，且不会有任何编译期提示。
 *
 * <p>配套的 {@code DocumentServiceVisibilityTest} 负责证明「详情口径也复用这里」，
 * 两个文件合起来锁住「列表可见 ⟺ 详情可见」。
 */
class DataScopeHelperTest {

    private static LoginUser user(DataScopeType scope, Long companyId, Long deptId, String deptPath) {
        return LoginUser.builder()
                .userId(9L)
                .account("u")
                .realName("测试用户")
                .companyId(companyId)
                .deptId(deptId)
                .deptPath(deptPath)
                .dataScope(scope)
                .build();
    }

    // ============================================================ 基础形状

    @Test
    @DisplayName("user 为 null：返回永假条件，而不是 null（null 会被调用方当成「无限制」放行）")
    void nullUserIsDenyAll() {
        assertThat(DataScopeHelper.buildClause("", null)).isEqualTo("1 = 0");
    }

    @Test
    @DisplayName("SELF：公司条件 + 申请人本人")
    void selfScope() {
        String clause = DataScopeHelper.buildClause("", user(DataScopeType.SELF, 1L, 8L, "/8/"));
        assertThat(clause).isEqualTo("company_id = 1 AND applicant_id = 9");
    }

    @Test
    @DisplayName("dataScope 为 null 时按最窄的 SELF 处理（授予不明 → 拒绝，不放大）")
    void nullScopeFallsBackToSelf() {
        String clause = DataScopeHelper.buildClause("", user(null, 1L, 8L, "/8/"));
        assertThat(clause).isEqualTo("company_id = 1 AND applicant_id = 9");
    }

    @Test
    @DisplayName("COMPANY：只保留多公司隔离，不再叠加其它限制")
    void companyScope() {
        String clause = DataScopeHelper.buildClause("", user(DataScopeType.COMPANY, 1L, 8L, "/8/"));
        assertThat(clause).isEqualTo("company_id = 1");
    }

    @Test
    @DisplayName("无公司归属 + COMPANY：无任何限制，返回 null（调用方必须判空后不加过滤）")
    void companyScopeWithoutCompanyIdReturnsNull() {
        assertThat(DataScopeHelper.buildClause("", user(DataScopeType.COMPANY, null, 8L, "/8/")))
                .isNull();
    }

    @Test
    @DisplayName("无公司归属 + SELF：仍然受申请人限制，不会退化成无限制")
    void noCompanyButSelfStillRestricted() {
        assertThat(DataScopeHelper.buildClause("", user(DataScopeType.SELF, null, 8L, "/8/")))
                .isEqualTo("applicant_id = 9");
    }

    @Test
    @DisplayName("别名前缀逐处生效（列表查询用 \"d\"）")
    void aliasAppliedToEveryColumn() {
        String clause = DataScopeHelper.buildClause("d", user(DataScopeType.SELF, 1L, 8L, "/8/"));
        assertThat(clause).isEqualTo("d.company_id = 1 AND d.applicant_id = 9");
    }

    @Test
    @DisplayName("返回值不含前导 AND —— 否则拼接后会出现 \"AND AND\"，分页 count SQL 会被改写成畸形语句")
    void neverStartsWithAnd() {
        for (DataScopeType t : DataScopeType.values()) {
            String clause = DataScopeHelper.buildClause("", user(t, 1L, 8L, "/8/"));
            assertThat(clause).isNotNull();
            assertThat(clause).doesNotStartWith("AND");
            assertThat(clause).doesNotStartWith("and");
        }
    }

    // ============================================================ 部门子树（越权回归核心）

    @Nested
    @DisplayName("DEPT / CENTER：本部门及下级（物化路径子树）")
    class DeptSubtree {

        @Test
        @DisplayName("有物化路径时用 path LIKE '{用户路径}%' 一次圈定子树（含自身）")
        void usesMaterializedPath() {
            String expected = "company_id = 1 AND dept_id IN ("
                    + "SELECT id FROM department WHERE deleted = 0 AND company_id = 1 AND path LIKE '/8/%')";
            assertThat(DataScopeHelper.buildClause("", user(DataScopeType.DEPT, 1L, 8L, "/8/")))
                    .isEqualTo(expected);
            assertThat(DataScopeHelper.buildClause("", user(DataScopeType.CENTER, 1L, 8L, "/8/")))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("★越权回归：DEPT 片段必须真的把单据限制在部门子树内，绝不能退化成「只有公司条件」")
        void deptClauseNeverDegeneratesToCompanyOnly() {
            LoginUser u = user(DataScopeType.DEPT, 1L, 6L, "/6/");
            String clause = DataScopeHelper.buildClause("", u);

            // 修复前 DocumentService.assertVisible 用 Java 分支复刻了这段判定，其中
            // 「部门相等 || user.getDeptPath().length() > 1」的后半段与单据无关、恒为 true。
            // 那个 bug 在 SQL 片段层面的等价形态就是「只剩公司条件」——这里把它钉死。
            assertThat(clause).isNotEqualTo("company_id = 1");
            assertThat(clause).contains("dept_id IN (SELECT id FROM department");
            assertThat(clause).contains("path LIKE '/6/%'");
            // 部门 8（业务一部）不在 /6/ 子树里，片段中不应出现它的任何痕迹
            assertThat(clause).doesNotContain("/8/");
        }

        @Test
        @DisplayName("路径为根 \"/\" 时不做子树匹配，退回精确部门（避免 LIKE '/%' 命中全表）")
        void rootPathFallsBackToExactDept() {
            String clause = DataScopeHelper.buildClause("", user(DataScopeType.DEPT, 1L, 8L, "/"));
            assertThat(clause).isEqualTo("company_id = 1 AND dept_id IN ("
                    + "SELECT id FROM department WHERE deleted = 0 AND company_id = 1 AND id = 8)");
        }

        @Test
        @DisplayName("路径为空时退回精确部门")
        void nullPathFallsBackToExactDept() {
            assertThat(DataScopeHelper.buildClause("", user(DataScopeType.DEPT, 1L, 8L, null)))
                    .contains("AND id = 8)");
        }

        @Test
        @DisplayName("路径含非法字符（潜在注入）时白名单校验拒绝，退回精确部门 —— 绝不拼进 SQL")
        void unsafePathIsRejectedByWhitelist() {
            String evil = "/8/' OR '1'='1";
            String clause = DataScopeHelper.buildClause("", user(DataScopeType.DEPT, 1L, 8L, evil));
            assertThat(clause).doesNotContain("OR");
            assertThat(clause).doesNotContain(evil);
            assertThat(clause).contains("AND id = 8)");
        }

        @Test
        @DisplayName("路径与部门 ID 都为空时子查询恒假，永不放开")
        void noPathNoDeptDeniesAll() {
            String clause = DataScopeHelper.buildClause("", user(DataScopeType.DEPT, 1L, null, null));
            assertThat(clause).isEqualTo("company_id = 1 AND dept_id IN ("
                    + "SELECT id FROM department WHERE deleted = 0 AND company_id = 1 AND 1 = 0)");
        }
    }

    // ============================================================ 自定义部门集合

    @Nested
    @DisplayName("CUSTOM_DEPT：按指定部门集合")
    class CustomDept {

        @Test
        @DisplayName("给了集合就用集合，不再算子树的子查询")
        void usesProvidedDeptIds() {
            Set<Long> ids = new LinkedHashSet<>();
            ids.add(3L);
            ids.add(4L);
            String clause = DataScopeHelper.buildClause("", user(DataScopeType.CUSTOM_DEPT, 1L, 2L, "/2/"), ids);
            assertThat(clause).isEqualTo("company_id = 1 AND dept_id IN (3,4)");
        }

        @Test
        @DisplayName("集合为空（null 或空集）时回退到用户自己的部门子树 —— 与列表 4 个调用点同源，不会放开")
        void emptySetFallsBackToOwnSubtree() {
            String expected = "company_id = 1 AND dept_id IN ("
                    + "SELECT id FROM department WHERE deleted = 0 AND company_id = 1 AND path LIKE '/2/%')";
            assertThat(DataScopeHelper.buildClause("", user(DataScopeType.CUSTOM_DEPT, 1L, 2L, "/2/"), null))
                    .isEqualTo(expected);
            assertThat(DataScopeHelper.buildClause("", user(DataScopeType.CUSTOM_DEPT, 1L, 2L, "/2/"), Set.of()))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("集合里只有 null 元素时退化为 IN (0)（恒假），不会拼出空 IN () 语法错误")
        void nullElementsBecomeZero() {
            Set<Long> ids = new LinkedHashSet<>();
            ids.add(null);
            String clause = DataScopeHelper.buildClause("", user(DataScopeType.CUSTOM_DEPT, 1L, 2L, "/2/"), ids);
            assertThat(clause).isEqualTo("company_id = 1 AND dept_id IN (0)");
        }

        @Test
        @DisplayName("两参重载（列表/统计/台账/风险 4 个入口都用它）等价于 scopeDeptIds=null")
        void twoArgOverloadEqualsNullScopeIds() {
            LoginUser u = user(DataScopeType.CUSTOM_DEPT, 1L, 2L, "/2/");
            assertThat(DataScopeHelper.buildClause("", u))
                    .isEqualTo(DataScopeHelper.buildClause("", u, null));
        }
    }
}

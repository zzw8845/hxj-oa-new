package com.hxj.oa.flow.service;

import com.hxj.oa.common.exception.BizException;

/**
 * 条件表达式的安全校验器 —— 这是条件分支的<b>安全边界</b>，不是格式校验。
 *
 * <h3>为什么必须做白名单，而不是"过滤危险字符"</h3>
 * 表达式最终会被 {@code BpmnGenerator} 包进 {@code <conditionExpression>}，
 * 交给 Flowable 的 UEL（JUEL）求值。JUEL 能调方法、能引用类型
 * （{@code ${''.getClass()}}、{@code T(java.lang.Runtime)} 之类），
 * 所以<b>把用户输入原样拼进条件表达式，等于开了一个任意代码执行入口</b>。
 * 黑名单永远堵不完，这里改成"只放行一个极小的语法子集"：
 *
 * <pre>
 *   expr    := or
 *   or      := and ( '||' and )*
 *   and     := unary ( '&amp;&amp;' unary )*
 *   unary   := '(' or ')' | compare
 *   compare := field op literal
 *   field   := 'doc' ('.' ident)+      // 强制 doc. 前缀
 *   ident   := [A-Za-z_][A-Za-z0-9_]*
 *   op      := == | != | &gt;= | &lt;= | &gt; | &lt;
 *   literal := '单引号字符串' | 数字（可带小数/负号） | true | false
 * </pre>
 *
 * 结果：<b>方法调用（因为没有 '(' 跟在标识符后）、类型引用、成员链之外的任何符号都进不来</b>。
 * 顺带堵住了两条旁路：
 * <ul>
 *   <li>{@code ]} 一律不允许 → 不可能出现 {@code ]]>} 提前闭合 CDATA
 *       （BpmnGenerator 是用 CDATA 包表达式的，这是实打实的 XML 注入点）；</li>
 *   <li>字符串字面量里不允许 {@code $ { } \ } → 不存在"闭合后又开一层 UEL"的构造。</li>
 * </ul>
 *
 * <p>字段必须带 {@code doc.} 前缀，与库里既有数据写法一致
 * （BpmnGenerator 会把前缀拍平成顶层变量名）。这也顺手排除了
 * {@code title.toString} 这类"看着像字段、其实是方法名"的路径。
 *
 * <p>不支持的特性（如需请走代码，不要放开这里）：数组下标 {@code doc.items[0]}、
 * 函数调用、算术运算。前两者会破坏上面几条安全前提。
 */
public final class ConditionExprValidator {

    /** 表达式长度上限：库里 condition_expr 是 VARCHAR，写不进去会变成难看的 500 */
    private static final int MAX_LENGTH = 300;

    private final String src;
    private int pos;

    private ConditionExprValidator(String src) {
        this.src = src;
    }

    /**
     * 校验条件表达式，不通过直接抛 {@link BizException}（HTTP 200 + body code 400）。
     *
     * @param expr 形如 {@code doc.amount >= 20000}、{@code doc.sealType == 'OFFICIAL' || doc.amount < 5000}
     */
    public static void validate(String expr) {
        if (expr == null || expr.isBlank()) {
            throw BizException.of("条件表达式不能为空");
        }
        String s = expr.trim();
        if (s.length() > MAX_LENGTH) {
            throw BizException.of("条件表达式过长（最多 %d 个字符，当前 %d 个）", MAX_LENGTH, s.length());
        }
        ConditionExprValidator v = new ConditionExprValidator(s);
        v.parseOr();
        v.skipWs();
        if (v.pos < v.src.length()) {
            throw BizException.of("条件表达式第 %d 个字符处无法识别：「%s」"
                    + "（只支持 doc.字段 与 值 的比较，可用 && / || / 括号 组合）", v.pos + 1, v.rest());
        }
    }

    /* ------------------------------------------------------------ 语法 */

    private void parseOr() {
        parseAnd();
        while (eat("||")) {
            parseAnd();
        }
    }

    private void parseAnd() {
        parseUnary();
        while (eat("&&")) {
            parseUnary();
        }
    }

    private void parseUnary() {
        skipWs();
        if (eat("(")) {
            parseOr();
            expect(")", "右括号");
            return;
        }
        parseCompare();
    }

    private void parseCompare() {
        parseField();
        readOperator();
        parseLiteral();
    }

    private void parseField() {
        skipWs();
        if (!src.startsWith("doc", pos) || (pos + 3 < src.length() && isIdentChar(src.charAt(pos + 3)))) {
            throw BizException.of("条件左边必须是以 doc. 开头的字段（如 doc.amount），"
                    + "第 %d 个字符处是：「%s」", pos + 1, rest());
        }
        pos += 3;
        if (pos >= src.length() || src.charAt(pos) != '.') {
            throw BizException.of("条件字段缺少小数点，应写成 doc.xxx 的形式");
        }
        while (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            readIdent();
        }
    }

    private void readIdent() {
        if (pos >= src.length() || !isIdentStart(src.charAt(pos))) {
            throw BizException.of("条件字段名不合法（第 %d 个字符）", pos + 1);
        }
        while (pos < src.length() && isIdentChar(src.charAt(pos))) {
            pos++;
        }
    }

    private String readOperator() {
        skipWs();
        for (String op : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
            if (src.startsWith(op, pos)) {
                pos += op.length();
                return op;
            }
        }
        throw BizException.of("条件表达式缺少比较运算符（==、!=、>、>=、<、<=），第 %d 个字符处是：「%s」",
                pos + 1, rest());
    }

    private void parseLiteral() {
        skipWs();
        if (pos >= src.length()) {
            throw BizException.of("条件表达式在比较运算符后面缺少比较值");
        }
        char c = src.charAt(pos);
        if (c == '\'') {
            readString();
            return;
        }
        if (c == '-' || Character.isDigit(c)) {
            readNumber();
            return;
        }
        if (src.startsWith("true", pos)) {
            pos += 4;
            return;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return;
        }
        throw BizException.of("比较值只支持：单引号字符串、数字、true/false（第 %d 个字符处是：「%s」）",
                pos + 1, rest());
    }

    private void readString() {
        pos++;                       // 跳过开头的单引号
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\'') {
                if (pos == start) {
                    throw BizException.of("字符串比较值不能为空");
                }
                pos++;
                return;
            }
            if (c == ']' || c == '\\' || c == '$' || c == '{' || c == '}' || c < 0x20) {
                throw BizException.of("字符串比较值不能包含字符「%s」（第 %d 个字符）", c, pos + 1);
            }
            pos++;
        }
        throw BizException.of("字符串比较值缺少右单引号");
    }

    private void readNumber() {
        if (src.charAt(pos) == '-') {
            pos++;
        }
        int intDigits = 0;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
            intDigits++;
        }
        if (intDigits == 0) {
            throw BizException.of("数字比较值不合法");
        }
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            int frac = 0;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
                frac++;
            }
            if (frac == 0) {
                throw BizException.of("数字比较值的小数点后面缺少数字");
            }
        }
    }

    /* ------------------------------------------------------------ 扫描辅助 */

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    /** 精确匹配才前进，否则位置不动 */
    private boolean eat(String literal) {
        skipWs();
        if (src.startsWith(literal, pos)) {
            pos += literal.length();
            return true;
        }
        return false;
    }

    private void expect(String literal, String what) {
        if (!eat(literal)) {
            throw BizException.of("条件表达式缺少%s「%s」（第 %d 个字符）", what, literal, pos + 1);
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 出错时把光标附近的内容回显出来，避免用户对着长表达式自己数第几个字符 */
    private String rest() {
        int end = Math.min(src.length(), pos + 16);
        return src.substring(Math.min(pos, src.length()), end);
    }
}

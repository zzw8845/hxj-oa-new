package com.hxj.oa.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * 授权拦截器：校验 {@link RequirePerm} 声明的权限点。
 *
 * <p>与 {@link AuthInterceptor} 的分工：
 * <ul>
 *   <li>AuthInterceptor —— <b>你是谁</b>：解析 token、写入 UserContext，失败返回 401</li>
 *   <li>PermInterceptor —— <b>你能不能做这件事</b>：校验权限点，失败返回 403</li>
 * </ul>
 *
 * <p>之所以能零成本校验：{@link LoginUser} 里已经带了 {@code permCodes}，
 * 而它随 token 一起下发/解析，无需回查 role_permission 表。
 * 代价见 {@link RequirePerm} 的注释（权限变更需重新登录）。
 *
 * <p>未标注 {@link RequirePerm} 的接口 = 只要登录即可访问，行为与加固前一致，
 * 因此本拦截器是<b>纯增量</b>的，不会影响既有接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 预检请求与静态资源直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !(handler instanceof HandlerMethod hm)) {
            return true;
        }

        // 方法级注解优先，其次类级；@AliasFor 等元注解也一并识别
        RequirePerm ann = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequirePerm.class);
        if (ann == null) {
            ann = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequirePerm.class);
        }
        if (ann == null) {
            return true;
        }

        LoginUser user = UserContext.get();
        if (user == null) {
            // 正常情况下 AuthInterceptor 已经拦下；这里兜底，避免因拦截器顺序调整而漏校验
            write(response, 401, "未登录或登录已过期");
            return false;
        }

        String[] need = ann.value();
        Set<String> owned = user.getPermCodes();
        boolean pass = (ann.logic() == RequirePerm.Logic.AND)
                ? owned.containsAll(Arrays.asList(need))
                : Arrays.stream(need).anyMatch(owned::contains);

        if (!pass) {
            log.warn("权限不足 userId={} account={} 需要={} 拥有角色={}",
                    user.getUserId(), user.getAccount(), Arrays.toString(need), user.getRoleCodes());
            write(response, 403, "无权限执行该操作（需要权限点：" + String.join("、", need) + "）");
            return false;
        }
        return true;
    }

    private void write(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code == 401
                ? HttpServletResponse.SC_UNAUTHORIZED
                : HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 与 GlobalExceptionHandler 保持同一个响应体结构，前端按 code 分支即可
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, msg)));
    }
}

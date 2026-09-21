package com.hxj.oa.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.common.util.JwtUtils;
import com.hxj.oa.system.auth.TokenRevocationStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 认证拦截器：解析 Bearer Token 并写入 UserContext。
 * 无状态 JWT，不做服务端会话；请求结束即清理 ThreadLocal。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "Authorization";
    public static final String PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;
    private final TokenRevocationStore revocationStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            try {
                JwtUtils.ParsedToken parsed = jwtUtils.parseFull(header.substring(PREFIX.length()).trim());
                // 登出吊销：这是全链路里唯一额外的一次查询，其余权限校验仍走 token、零查库。
                // 代价与取舍见 TokenRevocationStore（Redis 故障时失败开放）。
                if (parsed.tokenId() != null && revocationStore.isRevoked(parsed.tokenId())) {
                    writeRevoked(response);
                    return false;
                }
                UserContext.set(parsed.user());
                return true;
            } catch (Exception e) {
                // 落到下面的 401 分支，统一让前端跳登录
                log.debug("token 解析失败: {}", e.getMessage());
            }
        }
        writeUnauthorized(response);
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程复用场景下必须清理，否则会造成越权
        UserContext.clear();
    }

    /**
     * 已登出的 token：与"未登录"分开报，因为两者的处置完全不同 ——
     * 前者是用户主动退出的，前端不该弹出"登录已过期"这种误导性提示。
     */
    private void writeRevoked(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(401, "该登录已退出，请重新登录")));
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(401, "未登录或登录已过期")));
    }
}

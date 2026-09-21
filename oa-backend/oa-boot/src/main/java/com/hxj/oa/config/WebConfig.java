package com.hxj.oa.config;

import com.hxj.oa.security.AuthInterceptor;
import com.hxj.oa.security.PermInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Web 配置：CORS + 认证拦截器 + 联调页静态资源。
 *
 * <p>原型改造说明：联调页改为「由后端同源提供」（{@code http://127.0.0.1:8080/oa.html}），
 * 页面与接口同源，浏览器根本不做跨域校验，彻底消除 file:// / CORS 扩展改写响应头那一类故障。
 * 因此默认不再需要放开任意 origin。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final PermInterceptor permInterceptor;

    /**
     * 免登录白名单。
     *
     * <p>只列**真实存在**的端点。此前这里放着 {@code /actuator/health}、{@code /swagger-ui/**}、
     * {@code /doc.html} —— 这四处并未引入对应依赖，实测只会返回 500/302，属于"看起来收了口、
     * 实际攻击面清单是错的"。而 {@code /v3/api-docs/**} 曾真实暴露完整 OpenAPI 契约（70KB），
     * 现已随 springdoc 一起关闭（见 application.yml 的 springdoc 段）。
     */
    private static final List<String> WHITELIST = List.of(
            "/api/auth/login",
            "/api/ping",
            "/error"
    );

    /**
     * 拦截器链：先认证（你是谁），后授权（你能不能做）。
     * 顺序必须固定：PermInterceptor 依赖 AuthInterceptor 写入的 UserContext。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(WHITELIST)
                .order(1);
        // 只对标注了 @RequirePerm 的接口生效，未标注的接口行为不变
        registry.addInterceptor(permInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(WHITELIST)
                .order(2);
    }

    /**
     * 联调页的静态资源映射。
     * 注意：application.yml 里 spring.web.resources.add-mappings=false（关闭了默认的 /** 静态兜底），
     * 所以这里必须显式注册，否则请求 /oa.html 会抛 NoHandlerFoundException。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/oa.html", "/index.html")
                .addResourceLocations("classpath:/static/");
    }

    /**
     * CORS 配置。
     *
     * <p>默认（{@code oa.cors.permissive=false}）只放行**本机联调地址**，而不是 {@code "*"}。
     * 登录页改成同源提供后，正常联调路径下浏览器压根不走 CORS 校验，收紧不影响使用。
     *
     * <p>{@code allowCredentials} 设为 false：接口鉴权走 {@code Authorization: Bearer}
     * （令牌存 localStorage，不是 Cookie），浏览器不会自动携带任何凭据，
     * 放开它只有风险没有收益。
     *
     * <p>若仍需用 {@code file://} 直接打开页面联调（历史方式，已不推荐），
     * 启动前 {@code export OA_CORS_PERMISSIVE=true} 即可临时放行任意 origin。
     *
     * <p>部署到服务器后前端若另起域名，用 {@code OA_CORS_ALLOWED_ORIGINS} 传入逗号分隔的白名单，
     * 避免"为了放行一个域名把开关打到 permissive"这种把口子开成全网的操作。
     */
    @Bean
    public CorsFilter corsFilter(@Value("${oa.cors.permissive:false}") boolean permissive,
                                @Value("${oa.cors.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        if (permissive) {
            log.warn("CORS 处于放行模式（OA_CORS_PERMISSIVE=true）：允许任意 origin —— 仅限本地联调，切勿用于生产");
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOriginPatterns(originPatterns(allowedOrigins));
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        // 台账导出的文件名在响应头里，前端要读
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * 本机地址 + 运维通过 {@code OA_CORS_ALLOWED_ORIGINS} 追加的白名单。
     *
     * <p>使用 {@code setAllowedOriginPatterns} 而非 {@code setAllowedOrigins}：前者支持
     * {@code http://127.0.0.1:[*]} 这种端口通配，而本机联调的端口是随机的（8080/5173/…），
     * 写死端口会导致"页面能打开、接口全跨域失败"这种极难自查的现象。
     */
    private List<String> originPatterns(String allowedOrigins) {
        List<String> patterns = new ArrayList<>(List.of(
                "http://127.0.0.1:[*]",
                "http://localhost:[*]",
                "http://[::1]:[*]"));
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String o : allowedOrigins.split(",")) {
                String t = o.trim();
                if (!t.isEmpty()) {
                    patterns.add(t);
                }
            }
            log.info("CORS 额外放行来源：{}", allowedOrigins.trim());
        }
        return patterns;
    }
}

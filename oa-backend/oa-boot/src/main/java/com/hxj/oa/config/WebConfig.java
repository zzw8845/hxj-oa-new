package com.hxj.oa.config;

import com.hxj.oa.security.AuthInterceptor;
import com.hxj.oa.security.PermInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 配置：CORS + 认证拦截器 + 联调页静态资源。
 *
 * 原型改造说明：前端原型是本地打开的 HTML（origin 为 file:// 或 localhost），
 * 必须放开 CORS，否则浏览器直接连后端会被拦。生产请把 allowedOriginPatterns 收敛为白名单。
 *
 * 联调页改为「由后端同源提供」的原因：file:// 属于 opaque origin，
 * 浏览器扩展或抓包代理一旦改写 CORS 响应头，页面就会被整片拦死；
 * 而 http://127.0.0.1:8080/oa.html 与接口同源，浏览器根本不做跨域校验，
 * 彻底消除这一类故障。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final PermInterceptor permInterceptor;

    /** 免登录白名单 */
    private static final List<String> WHITELIST = List.of(
            "/api/auth/login",
            "/actuator/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error",
            "/doc.html"
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

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}

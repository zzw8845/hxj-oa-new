package com.hxj.oa.config;

import com.hxj.oa.common.util.JwtUtils;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** JWT 与密码加密配置 */
@Configuration
public class SecurityBeanConfig {

    /**
     * 密码加密：BCrypt。
     * 原型里密码是明文存储的，这里改为强哈希，杜绝拖库即明文的风险。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtUtils jwtUtils(JwtProperties props) {
        return new JwtUtils(props.getSecret(), props.getExpireMinutes() * 60_000L);
    }

    @Data
    @ConfigurationProperties(prefix = "oa.jwt")
    public static class JwtProperties {
        /** 至少 32 字节，生产环境务必用环境变量注入 */
        private String secret = "haixiajin-oa-default-secret-please-change-in-production";
        private long expireMinutes = 720;
    }

    @Bean
    public JwtProperties jwtProperties() {
        return new JwtProperties();
    }
}

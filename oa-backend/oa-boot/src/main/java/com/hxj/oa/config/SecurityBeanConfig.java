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

    /**
     * JWT 工具。
     *
     * <p><b>fail-fast</b>：密钥未配置直接启动失败，绝不回落到内置默认值。
     * 原实现给了一个 54 字节的默认密钥，恰好能通过 {@link JwtUtils} 的「>= 32 字节」校验，
     * 于是"忘记配置"这件事**不会报任何错**，服务照常起来对外发令牌 —— 而这串默认值随公开仓库公开，
     * 等于把管理员令牌的伪造权一起公开了。宁可起不来，也不要静默地不安全。
     *
     * <p>本地联调无需手动配置：`启动联调版.command` 会自动生成并复用本地密钥文件。
     */
    @Bean
    public JwtUtils jwtUtils(JwtProperties props) {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    未配置 JWT 签名密钥，服务拒绝启动。
                    请通过环境变量注入：export OA_JWT_SECRET=<至少 32 字节的随机串>
                    本地联调可直接运行「启动联调版.command」，它会自动生成并复用本地密钥。""");
        }
        return new JwtUtils(secret, props.getExpireMinutes() * 60_000L);
    }

    @Data
    @ConfigurationProperties(prefix = "oa.jwt")
    public static class JwtProperties {
        /** 必须由环境变量 OA_JWT_SECRET 注入；无默认值，未配置即启动失败 */
        private String secret;
        private long expireMinutes = 720;
    }

    @Bean
    public JwtProperties jwtProperties() {
        return new JwtProperties();
    }
}

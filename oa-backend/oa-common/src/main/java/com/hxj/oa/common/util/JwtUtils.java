package com.hxj.oa.common.util;

import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 生成与解析。
 *
 * 设计取舍：把完整的授权信息（角色/权限/数据范围）编进 token，避免每次请求回查库。
 * 代价是权限变更后需重新登录才生效。生产环境建议改为「token 只带 userId + Redis 存授权快照」，
 * 见报告 10.x 的落地建议。
 */
public class JwtUtils {

    private final SecretKey key;
    private final long expireMillis;
    private static final String CLAIM_USER = "u";

    public JwtUtils(String secret, long expireMillis) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret 长度不足 32 字节，存在被暴力破解风险");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMillis = expireMillis;
    }

    public String generate(LoginUser user) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JsonUtils.toJson(user).getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim(CLAIM_USER, payload)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String payload = claims.get(CLAIM_USER, String.class);
            if (payload == null) {
                throw BizException.unauthorized("token 内容不完整");
            }
            String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            return JsonUtils.parse(json, LoginUser.class);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.unauthorized("登录已过期或 token 无效");
        }
    }

    public long getExpireMillis() {
        return expireMillis;
    }
}

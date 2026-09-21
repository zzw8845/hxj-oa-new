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
import java.util.UUID;

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
                // jti：登出吊销靠它定位**这一个** token。
                // 刻意不做"按 userId 拉黑" —— 那会把登出后重新登录拿到的新 token 也一起废掉，
                // 表现为"登出后再也登不进来"。按 token 拉黑则只作废当前这一张。
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析结果除用户信息外，还带出"这一个"token 的 jti 与到期时间（登出吊销要用）。 */
    public record ParsedToken(LoginUser user, String tokenId, Date expiration) {
    }

    public ParsedToken parseFull(String token) {
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
            return new ParsedToken(JsonUtils.parse(json, LoginUser.class), claims.getId(), claims.getExpiration());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.unauthorized("登录已过期或 token 无效");
        }
    }

    /** 只要用户信息的兼容入口（既有调用方与单测仍用它，语义不变）。 */
    public LoginUser parse(String token) {
        return parseFull(token).user();
    }

    public long getExpireMillis() {
        return expireMillis;
    }
}

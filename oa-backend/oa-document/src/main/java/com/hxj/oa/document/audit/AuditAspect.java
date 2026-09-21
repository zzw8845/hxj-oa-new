package com.hxj.oa.document.audit;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.common.util.JsonUtils;
import com.hxj.oa.document.entity.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计切面：拦截标注了 {@link Audit} 的方法，把「谁、什么时候、对哪个业务对象、做了什么、成没成」写进 audit_log。
 *
 * <p>三条纪律，改动前务必先读：
 * <ol>
 *   <li><b>绝不因为审计失败而让业务失败</b>。整段采集 + 落库都包在 try/catch 里；
 *       落库那层（{@link AuditLogService#record}）自己还会再吞一次。</li>
 *   <li><b>不记录请求体原文</b>。登录接口的请求体里有明文密码，
 *       整包序列化请求参数等于把密码写进审计表。只记录白名单字段（见 {@link #requestAction}）。</li>
 *   <li><b>失败也要留痕</b>。异常路径下把异常摘要写进 detail 并置 success=false ——
 *       「谁尝试改了什么但没改成」正是内控最关心的记录。</li>
 * </ol>
 *
 * <p>依赖 {@code spring-boot-starter-aop}（aspectjweaver）。缺这个依赖时本类不报错**也不生效**，
 * 表现为审计表一行都不写 —— 这是最难发现的一类故障，排查时先确认
 * {@code BOOT-INF/lib/aspectjweaver-*.jar} 在不在 fat jar 里。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuditAspect {

    /** detail 里异常摘要的截断长度，避免一条异常把整列撑爆 */
    private static final int ERROR_MAX = 500;

    private final AuditLogService auditLogService;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint pjp, Audit audit) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                save(pjp, audit, result, error, System.currentTimeMillis() - start);
            } catch (Throwable t) {
                // 第二道兜底：采集阶段自身出错（反射、请求上下文缺失等）也绝不能影响业务
                log.error("审计采集失败 module={} action={}: {}", audit.module(), audit.action(), t.toString());
            }
        }
    }

    // ------------------------------------------------------------------ 采集

    private void save(ProceedingJoinPoint pjp, Audit audit, Object result, Throwable error, long costMs) {
        HttpServletRequest request = currentRequest();
        LoginUser me = resolveUser(result);

        Object data = unwrap(result);
        Long bizId = resolveBizId(data, pjp.getArgs());

        AuditLog entry = new AuditLog();
        entry.setModule(audit.module());
        entry.setAction(audit.action());
        entry.setBizId(bizId);
        if (me != null) {
            entry.setCompanyId(me.getCompanyId());
            entry.setUserId(me.getUserId());
            entry.setUserName(me.getRealName());
        }
        if (request != null) {
            entry.setIp(clientIp(request));
            entry.setUserAgent(request.getHeader("User-Agent"));
        }
        entry.setDetail(buildDetail(pjp, request, data, error, costMs));

        boolean ok = auditLogService.record(entry);
        if (!ok && log.isDebugEnabled()) {
            log.debug("审计未落库 module={} action={}", audit.module(), audit.action());
        }
    }

    private String buildDetail(ProceedingJoinPoint pjp, HttpServletRequest request,
                               Object data, Throwable error, long costMs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("success", error == null);
        detail.put("costMs", costMs);
        if (request != null) {
            detail.put("uri", request.getRequestURI());
            detail.put("method", request.getMethod());
        } else {
            detail.put("method", pjp.getSignature().getName());
        }
        // 审批类接口的「动作」（通过/驳回/要求补料）在请求体里，是审计的关键字段
        String reqAction = requestAction(pjp.getArgs());
        if (reqAction != null) {
            detail.put("reqAction", reqAction);
        }
        // 审批结果的节点名/单据号等，有就带上，便于排障
        String docNo = stringGetter(data, "getDocNo");
        if (docNo != null) {
            detail.put("docNo", docNo);
        }
        if (error != null) {
            String msg = error.getMessage() == null ? error.toString() : error.getMessage();
            detail.put("error", msg.length() <= ERROR_MAX ? msg : msg.substring(0, ERROR_MAX));
        }
        return JsonUtils.toJson(detail);
    }

    /**
     * 身份解析：常规接口取 UserContext；登录接口例外。
     *
     * <p>{@code /api/auth/login} 在 AuthInterceptor 的白名单里（那里本来就没有登录态），
     * 所以 UserContext 是空的。但登录响应体里带着刚建立的身份（{@code LoginResp.user}），
     * 补上它才能记下「谁在什么时候登录成功」—— 登录留痕本身就是安全审计的必需项。
     * 登录失败时拿不到身份，此时 userId 为空但 IP/UA/失败原因仍在，同样有价值。
     */
    private LoginUser resolveUser(Object result) {
        LoginUser ctx = UserContext.get();
        if (ctx != null) {
            return ctx;
        }
        Object data = unwrap(result);
        if (data instanceof LoginUser lu) {
            return lu;
        }
        Object nested = invokeGetter(data, "getUser");
        return nested instanceof LoginUser lu ? lu : null;
    }

    /** 从返回值里取 R 的 data；非 R 原样返回 */
    private Object unwrap(Object result) {
        if (result instanceof R<?> r) {
            return r.getData();
        }
        return result;
    }

    /**
     * 业务对象 ID：优先 documentId（audit_log.biz_id 的注释就写明是「如单据ID」），
     * 其次 id，最后退化为第一个数值型入参（删改接口常用 {@code @PathVariable Long id}）。
     */
    private Long resolveBizId(Object data, Object[] args) {
        Long docId = longGetter(data, "getDocumentId");
        if (docId != null) {
            return docId;
        }
        Long id = longGetter(data, "getId");
        if (id != null) {
            return id;
        }
        if (args != null) {
            for (Object a : args) {
                if (a instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return null;
    }

    /** 请求体里的动作字段（ApprovalRequest.action）。白名单式取值，不整包序列化请求参数。 */
    private String requestAction(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a == null || a instanceof MultipartFile) {
                continue;
            }
            String action = stringGetter(a, "getAction");
            if (action != null) {
                return action;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 反射小工具（一律吞异常，取不到就返回 null）

    private Object invokeGetter(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(getter);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long longGetter(Object target, String getter) {
        Object v = invokeGetter(target, getter);
        return v instanceof Number n ? n.longValue() : null;
    }

    private String stringGetter(Object target, String getter) {
        Object v = invokeGetter(target, getter);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    /** 取真实客户端 IP：代理链路上 remoteAddr 会是网关地址，优先信任 X-Forwarded-For 的第一跳 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}

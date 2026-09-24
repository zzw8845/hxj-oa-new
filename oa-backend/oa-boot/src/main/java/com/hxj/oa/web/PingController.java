package com.hxj.oa.web;

import com.hxj.oa.common.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连通性探测端点（免登录白名单）。
 *
 * <p>为什么需要它：登录页要显示「已连接 / 未连接」并在后端晚启动时自动重连，
 * 此前是靠请求 {@code /v3/api-docs} 实现的 —— 而那个端点会把**完整接口契约**
 * 一并暴露给未认证访客。用这个只回一个常量、不查库、不碰鉴权链的轻量端点替代后，
 * 探测能力保留，攻击面消失。
 *
 * <p>刻意不加 {@code @RequirePerm}，也不读取任何用户相关数据：它只回答"服务活着吗"。
 */
@RestController
public class PingController {

    /** 服务探活（不校验登录态，也不读任何用户数据） */
    @GetMapping("/api/ping")
    public R<String> ping() {
        return R.ok("pong");
    }
}

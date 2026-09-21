package com.hxj.oa.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 联调页入口：让 http://127.0.0.1:8080/ 直接跳到页面。
 *
 * 用重定向而不是返回视图，避免额外引入模板引擎；
 * 目标 /oa.html 由 WebConfig#addResourceHandlers 提供。
 */
@Controller
public class PageEntryController {

    @GetMapping("/")
    public String index() {
        return "redirect:/oa.html";
    }
}

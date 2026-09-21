package com.hxj.oa.system.controller;

import com.hxj.oa.common.api.R;
import com.hxj.oa.system.entity.SysDict;
import com.hxj.oa.system.service.DictService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 字典管理：读接口对全体登录用户开放（前端启动预热一次），
 * 写接口统一挂 {@code system:dict} 权限点。
 */
@RestController
@RequestMapping("/api/dicts")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;

    /** 全量字典（按类型分组），前端启动时预热一次 */
    @GetMapping
    public R<Map<String, List<SysDict>>> all() {
        return R.ok(dictService.listAllGrouped());
    }

    @GetMapping("/{dictType}")
    public R<List<SysDict>> byType(@PathVariable String dictType) {
        return R.ok(dictService.listByType(dictType));
    }

}

package com.hxj.oa.system.controller;

import com.hxj.oa.common.api.R;
import com.hxj.oa.system.entity.SysDict;
import com.hxj.oa.system.service.DictService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 字典管理：读接口对全体登录用户开放（前端启动预热一次）。
 *
 * <p><b>当前本控制器只有读接口，没有写接口</b>（写接口曾实现过又被回退）。
 * 若要新增 POST/PUT/DELETE，必须同时满足三件事，缺一不可：
 * <ol>
 *   <li>挂 {@code system:dict} 权限点（写接口绝不能只靠登录态）；</li>
 *   <li>补 {@code @Audit(module = "system")}（主数据变更必须留痕）；</li>
 *   <li>补回对应的接口级用例（写接口的越权与唯一性只有用例能钉住）。</li>
 * </ol>
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

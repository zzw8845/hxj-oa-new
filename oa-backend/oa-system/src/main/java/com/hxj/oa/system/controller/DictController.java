package com.hxj.oa.system.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.system.dto.DictSaveReq;
import com.hxj.oa.system.entity.SysDict;
import com.hxj.oa.system.service.DictService;
import com.hxj.oa.system.service.OrgAdminService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 字典管理
 *
 * <p>读接口对全体登录用户开放（前端启动预热一次），写接口挂 {@code system:dict}。
 *
 * <p>与 {@link DepartmentController} 同一套约定：写接口必须同时满足
 * 挂权限点、补 {@code @Audit(module = "system")}、有接口级用例三件事。
 *
 * <p>注意 {@code SysDict.companyId} 恒为 NULL（全局字典，与库中既有 17 条一致），
 * 而 MySQL 唯一索引认为多个 NULL 互不相同 ⇒ {@code uk_dict} **不会**拦住重复的
 * (dictType, dictCode)。查重由 {@code OrgAdminService#assertDictFree} 显式做。
 */
@RestController
@RequestMapping("/api/dicts")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;
    private final OrgAdminService orgAdminService;

    /** 全量字典（按 dictType 分组的 Map），前端启动时预热一次 */
    @GetMapping
    public R<Map<String, List<SysDict>>> all() {
        return R.ok(dictService.listAllGrouped());
    }

    /**
     * 按类型取字典项（下拉框数据源）。
     *
     * @param dictType 字典类型，如 docCategory、businessCategory
     */
    @GetMapping("/{dictType}")
    public R<List<SysDict>> byType(@PathVariable String dictType) {
        return R.ok(dictService.listByType(dictType));
    }

    /**
     * 新建字典项。
     *
     * <p>dictType + dictCode 是唯一键，重复会报错；companyId 由后端固定为 NULL（全局字典）。
     */
    @PostMapping
    @RequirePerm("system:dict")
    @Audit(module = "system", action = "createDict")
    public R<SysDict> create(@Valid @RequestBody DictSaveReq req) {
        return R.ok(orgAdminService.createDict(req));
    }

    /**
     * 修改字典项（局部编辑：只传要改的字段）。
     *
     * <p>改了 dictType 或 dictCode 会重新查重。
     *
     * @param id 字典项 ID
     */
    @PutMapping("/{id}")
    @RequirePerm("system:dict")
    @Audit(module = "system", action = "updateDict")
    public R<SysDict> update(@PathVariable Long id, @Valid @RequestBody DictSaveReq req) {
        return R.ok(orgAdminService.updateDict(id, req));
    }

    /**
     * 删除字典项（逻辑删除）。
     *
     * <p>字典项没有引用关系，所以不做引用守卫（前端按 dictType 现取列表）。
     *
     * @param id 字典项 ID
     */
    @DeleteMapping("/{id}")
    @RequirePerm("system:dict")
    @Audit(module = "system", action = "deleteDict")
    public R<Void> delete(@PathVariable Long id) {
        orgAdminService.deleteDict(id);
        return R.ok(null, "已删除");
    }

}

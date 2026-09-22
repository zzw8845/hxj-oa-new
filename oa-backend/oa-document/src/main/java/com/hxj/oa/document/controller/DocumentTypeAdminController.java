package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.dto.DocumentTypeSaveReq;
import com.hxj.oa.document.dto.DocumentTypeVO;
import com.hxj.oa.document.service.DocumentTypeAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 单据类型管理（主数据维护）。
 *
 * <p>与 {@code DictController} 同一套约定：读接口对全体登录用户开放
 * （{@code /api/document-types/enabled} 是发起菜单的数据源），写接口挂 {@code system:docType}，
 * 写操作全部进审计。业务类型的中文标签由本控制器下发（{@code /categories}），
 * 前端不再维护写死映射。
 */
@RestController
@RequestMapping("/api/document-types")
@RequiredArgsConstructor
public class DocumentTypeAdminController {

    private final DocumentTypeAdminService adminService;

    /** 全量（含停用），管理页用 */
    @GetMapping
    public R<List<DocumentTypeVO>> list() {
        return R.ok(adminService.listAll(UserContext.require().getCompanyId()));
    }

    /** 启用中的类型（发起菜单 / 筛选项数据源），带 categoryLabel */
    @GetMapping("/enabled")
    public R<List<DocumentTypeVO>> enabled() {
        return R.ok(adminService.listEnabled(UserContext.require().getCompanyId()));
    }

    /** 业务大类白名单（键=编码，值=中文标签），前端下拉与标签渲染的唯一来源 */
    @GetMapping("/categories")
    public R<Map<String, String>> categories() {
        return R.ok(adminService.categories());
    }

    @PostMapping
    @RequirePerm("system:docType")
    @Audit(module = "system", action = "createDocType")
    public R<DocumentTypeVO> create(@Valid @RequestBody DocumentTypeSaveReq req) {
        return R.ok(adminService.create(req, UserContext.require()), "单据类型已创建");
    }

    @PutMapping("/{id}")
    @RequirePerm("system:docType")
    @Audit(module = "system", action = "updateDocType")
    public R<DocumentTypeVO> update(@PathVariable Long id, @Valid @RequestBody DocumentTypeSaveReq req) {
        return R.ok(adminService.update(id, req), "单据类型已更新");
    }

    @DeleteMapping("/{id}")
    @RequirePerm("system:docType")
    @Audit(module = "system", action = "deleteDocType")
    public R<Void> delete(@PathVariable Long id) {
        adminService.delete(id);
        return R.ok(null, "单据类型已删除");
    }
}

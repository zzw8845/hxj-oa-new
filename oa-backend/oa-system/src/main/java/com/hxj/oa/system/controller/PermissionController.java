package com.hxj.oa.system.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.system.dto.PermissionSaveReq;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.mapper.SysPermissionMapper;
import com.hxj.oa.system.service.PermissionAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限点目录。
 *
 * <p>与 {@code /api/auth/permissions} 的区别：那个返回的是<b>当前用户自己</b>拥有的权限点
 * （用于前端按钮级控制）；这个返回的是<b>系统全部</b>权限点（用于给角色勾选可分配的权限）。
 * 二者用途不同，不能互相替代——管理员权限不全时，前者拿不到完整可分配列表。
 *
 * <p>写接口（新增/编辑/删除）即「权限点管理」功能：客户管理员可在界面上自助扩展
 * 菜单项与操作点，不必发版。所有写操作挂 {@code system:perm} 单独门控——
 * 能管角色的角色（system:role）不必然能改权限点目录，两个授权面分开给。
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final SysPermissionMapper permissionMapper;
    private final PermissionAdminService permissionAdminService;

    /** 全量权限点，按 sortNo 排序，前端按 permType 分「菜单/操作点」分组渲染 */
    @GetMapping
    @RequirePerm(value = {"system:role", "system:user", "system:perm"}, logic = RequirePerm.Logic.OR)
    public R<List<SysPermission>> all() {
        return R.ok(permissionMapper.selectList(Wrappers.<SysPermission>lambdaQuery()
                .orderByAsc(SysPermission::getSortNo)));
    }

    /**
     * 新增权限点。编码规则：全冒号风格 {@code 模块:页面}（菜单项）/ {@code 模块:动作}（操作点）；
     * 一级（parentCode 为空）只允许目录（类型 1）。
     */
    @PostMapping
    @RequirePerm("system:perm")
    @Audit(module = "permission", action = "createPerm")
    public R<SysPermission> create(@Valid @RequestBody PermissionSaveReq req) {
        return R.ok(permissionAdminService.create(req), "权限点已创建，给角色勾选后重新登录生效");
    }

    /**
     * 编辑权限点。⚠ code 创建后不可改——它是角色绑定、JWT permCodes、
     * 后端 {@code @RequirePerm} 注解的三方契约。
     */
    @PutMapping("/{id}")
    @RequirePerm("system:perm")
    @Audit(module = "permission", action = "updatePerm")
    public R<SysPermission> update(@PathVariable Long id, @Valid @RequestBody PermissionSaveReq req) {
        return R.ok(permissionAdminService.update(id, req), "权限点已更新，相关角色成员重新登录生效");
    }

    /**
     * 删除权限点。守卫：有子级、或仍被角色绑定的一律拒绝——
     * 删掉被绑定的权限点会让绑定行悬空、成员 JWT 里的 code 变成"看起来还在"的死串。
     */
    @DeleteMapping("/{id}")
    @RequirePerm("system:perm")
    @Audit(module = "permission", action = "deletePerm")
    public R<Void> delete(@PathVariable Long id) {
        permissionAdminService.delete(id);
        return R.ok(null, "权限点已删除");
    }
}

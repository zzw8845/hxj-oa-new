package com.hxj.oa.system.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.security.RequirePerm;
import com.hxj.oa.system.entity.SysPermission;
import com.hxj.oa.system.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限点目录。
 *
 * <p>与 {@code /api/auth/permissions} 的区别：那个返回的是<b>当前用户自己</b>拥有的权限点
 * （用于前端按钮级控制）；这个返回的是<b>系统全部</b>权限点（用于给角色勾选可分配的权限）。
 * 二者用途不同，不能互相替代——管理员权限不全时，前者拿不到完整可分配列表。
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final SysPermissionMapper permissionMapper;

    /** 全量权限点，按 sortNo 排序，前端按 permType 分「菜单/按钮/接口」分组渲染 */
    @GetMapping
    @RequirePerm(value = {"system:role", "system:user"}, logic = RequirePerm.Logic.OR)
    public R<List<SysPermission>> all() {
        return R.ok(permissionMapper.selectList(Wrappers.<SysPermission>lambdaQuery()
                .orderByAsc(SysPermission::getSortNo)));
    }
}

package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.system.entity.SysRole;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.entity.UserRole;
import com.hxj.oa.system.mapper.SysRoleMapper;
import com.hxj.oa.system.mapper.SysUserMapper;
import com.hxj.oa.system.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户与角色查询 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    public SysUser getById(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.notFound("用户不存在: " + id);
        }
        return u;
    }

    public List<SysUser> listByCompany(Long companyId) {
        return userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(companyId != null, SysUser::getCompanyId, companyId)
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getDeptId, SysUser::getId));
    }

    public List<SysRole> listRoles(Long companyId) {
        return roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(companyId != null, SysRole::getCompanyId, companyId)
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getId));
    }

    /** 批量取用户ID → 姓名，避免 N+1 */
    public Map<Long, String> nameMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .select(SysUser::getId, SysUser::getRealName)
                        .in(SysUser::getId, userIds))
                .stream().collect(Collectors.toMap(SysUser::getId, SysUser::getRealName, (a, b) -> a));
    }

    /** 批量取 用户ID → 角色编码 */
    public Map<Long, List<String>> roleCodesOf(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UserRole> links = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                .in(UserRole::getUserId, userIds));
        if (links.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> roleIds = links.stream().map(UserRole::getRoleId).distinct().toList();
        Map<Long, String> roleCodeMap = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getCode));
        return links.stream().collect(Collectors.groupingBy(UserRole::getUserId,
                Collectors.mapping(l -> roleCodeMap.getOrDefault(l.getRoleId(), ""), Collectors.toList())));
    }

    /** 取用户姓名（可能为空，不抛异常） */
    public String nameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser u = userMapper.selectById(userId);
        return u == null ? null : u.getRealName();
    }
}

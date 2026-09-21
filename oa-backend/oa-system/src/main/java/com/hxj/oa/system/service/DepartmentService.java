package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.system.dto.DeptTreeVO;
import com.hxj.oa.system.entity.Department;
import com.hxj.oa.system.entity.SysUser;
import com.hxj.oa.system.mapper.DepartmentMapper;
import com.hxj.oa.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 组织架构服务。数据范围与「取发起人主管」都依赖这里的部门树。
 */
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentMapper departmentMapper;
    private final SysUserMapper userMapper;

    public List<Department> listByCompany(Long companyId) {
        return departmentMapper.selectList(Wrappers.<Department>lambdaQuery()
                .eq(companyId != null, Department::getCompanyId, companyId)
                .eq(Department::getStatus, 1)
                .orderByAsc(Department::getSortNo));
    }

    /** 构建部门树 */
    public List<DeptTreeVO> tree(Long companyId) {
        List<Department> all = listByCompany(companyId);
        Map<Long, String> leaderNames = loadLeaderNames(all);

        Map<Long, DeptTreeVO> map = new LinkedHashMap<>();
        for (Department d : all) {
            DeptTreeVO vo = toVO(d);
            vo.setLeaderName(leaderNames.get(d.getLeaderId()));
            map.put(d.getId(), vo);
        }
        List<DeptTreeVO> roots = new ArrayList<>();
        for (DeptTreeVO vo : map.values()) {
            if (vo.getParentId() == null || vo.getParentId() == 0 || !map.containsKey(vo.getParentId())) {
                roots.add(vo);
            } else {
                map.get(vo.getParentId()).getChildren().add(vo);
            }
        }
        return roots;
    }

    private Map<Long, String> loadLeaderNames(List<Department> depts) {
        Set<Long> ids = depts.stream().map(Department::getLeaderId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .select(SysUser::getId, SysUser::getRealName)
                        .in(SysUser::getId, ids))
                .stream().collect(Collectors.toMap(SysUser::getId, SysUser::getRealName, (a, b) -> a));
    }

    private DeptTreeVO toVO(Department d) {
        DeptTreeVO vo = new DeptTreeVO();
        vo.setId(d.getId());
        vo.setParentId(d.getParentId());
        vo.setCode(d.getCode());
        vo.setName(d.getName());
        vo.setDeptType(d.getDeptType());
        vo.setLevel(d.getLevel());
        vo.setPath(d.getPath());
        vo.setLeaderId(d.getLeaderId());
        return vo;
    }

    public Department getById(Long id) {
        Department d = departmentMapper.selectById(id);
        if (d == null) {
            throw BizException.notFound("部门不存在: " + id);
        }
        return d;
    }

    /** 本部门及全部下级 ID */
    public List<Long> selfAndDescendantIds(Long companyId, Long deptId) {
        Department d = getById(deptId);
        return departmentMapper.selectSelfAndDescendantIds(companyId, deptId, d.getPath());
    }

    /** 取部门负责人（流程「直属主管」规则） */
    public Long leaderIdOf(Long deptId) {
        if (deptId == null) {
            return null;
        }
        return userMapper.selectLeaderIdByDeptId(deptId);
    }
}

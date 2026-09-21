package com.hxj.oa.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.system.entity.SysDict;
import com.hxj.oa.system.mapper.SysDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 数据字典 */
@Service
@RequiredArgsConstructor
public class DictService {

    private final SysDictMapper dictMapper;

    public List<SysDict> listByType(String dictType) {
        return dictMapper.selectList(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, dictType)
                .eq(SysDict::getStatus, 1)
                .orderByAsc(SysDict::getSortNo));
    }

    /** 一次性返回全部字典，供前端启动时预热 */
    public Map<String, List<SysDict>> listAllGrouped() {
        return dictMapper.selectList(Wrappers.<SysDict>lambdaQuery()
                        .eq(SysDict::getStatus, 1)
                        .orderByAsc(SysDict::getSortNo))
                .stream()
                .collect(Collectors.groupingBy(SysDict::getDictType, Collectors.toList()));
    }
}

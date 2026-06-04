package com.flowplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.entity.SysDept;
import com.flowplatform.mapper.SysDeptMapper;
import com.flowplatform.service.SysDeptService;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements SysDeptService {

    @Override
    public List<SysDept> getDeptTree() {
        List<SysDept> allDepts = list(new LambdaQueryWrapper<SysDept>().eq(SysDept::getStatus, 1).orderByAsc(SysDept::getSortOrder));
        Map<Long, List<SysDept>> childrenMap = allDepts.stream()
                .filter(d -> d.getParentId() != null && d.getParentId() > 0)
                .collect(Collectors.groupingBy(SysDept::getParentId));
        allDepts.forEach(dept -> dept.setChildren(childrenMap.getOrDefault(dept.getId(), new ArrayList<>())));
        return allDepts.stream()
                .filter(d -> d.getParentId() == null || d.getParentId() == 0)
                .collect(Collectors.toList());
    }
}

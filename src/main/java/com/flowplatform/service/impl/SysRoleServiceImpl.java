package com.flowplatform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.entity.SysRole;
import com.flowplatform.mapper.SysRoleMapper;
import com.flowplatform.service.SysRoleService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    @Override
    public List<SysRole> findByUserId(Long userId) {
        return baseMapper.selectListByUserId(userId);
    }
}

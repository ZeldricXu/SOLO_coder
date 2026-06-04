package com.flowplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.entity.SysUser;
import com.flowplatform.mapper.SysUserMapper;
import com.flowplatform.service.SysUserService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Override
    public SysUser findByUsername(String username) {
        return getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    @Override
    public List<String> getRoleCodes(Long userId) {
        return baseMapper.selectRoleCodesByUserId(userId);
    }

    @Override
    public List<String> getRoleNames(Long userId) {
        return baseMapper.selectRoleNamesByUserId(userId);
    }

    @Override
    public SysUser getUserWithDetails(Long userId) {
        SysUser user = getById(userId);
        if (user != null) {
            user.setRoleCodes(getRoleCodes(userId));
            user.setRoleNames(getRoleNames(userId));
        }
        return user;
    }
}

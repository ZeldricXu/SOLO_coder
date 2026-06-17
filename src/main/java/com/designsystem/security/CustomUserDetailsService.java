package com.designsystem.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.designsystem.entity.SysRole;
import com.designsystem.entity.SysUser;
import com.designsystem.entity.SysUserRole;
import com.designsystem.mapper.SysRoleMapper;
import com.designsystem.mapper.SysUserMapper;
import com.designsystem.mapper.SysUserRoleMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;

    public CustomUserDetailsService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        List<SysUserRole> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, user.getId()));
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        List<SysRole> roles = roleIds.isEmpty() ? new ArrayList<>() : roleMapper.selectBatchIds(roleIds);
        List<String> roleCodes = roles.stream().map(SysRole::getRoleCode).collect(Collectors.toList());

        CustomUserDetails userDetails = new CustomUserDetails();
        userDetails.setUserId(user.getId());
        userDetails.setUsername(user.getUsername());
        userDetails.setPassword(user.getPassword());
        userDetails.setNickname(user.getNickname());
        userDetails.setEmail(user.getEmail());
        userDetails.setRoles(roleCodes);
        userDetails.setPermissions(new ArrayList<>());
        userDetails.setStatus(user.getStatus());

        return userDetails;
    }
}

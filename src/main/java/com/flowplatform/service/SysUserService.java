package com.flowplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flowplatform.entity.SysUser;
import java.util.List;

public interface SysUserService extends IService<SysUser> {
    SysUser findByUsername(String username);
    List<String> getRoleCodes(Long userId);
    List<String> getRoleNames(Long userId);
    SysUser getUserWithDetails(Long userId);
}

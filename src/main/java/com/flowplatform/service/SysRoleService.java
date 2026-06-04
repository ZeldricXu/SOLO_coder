package com.flowplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flowplatform.entity.SysRole;

import java.util.List;

public interface SysRoleService extends IService<SysRole> {
    List<SysRole> findByUserId(Long userId);
}

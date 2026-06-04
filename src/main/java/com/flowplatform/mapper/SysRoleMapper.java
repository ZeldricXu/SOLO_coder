package com.flowplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowplatform.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT r.* FROM sys_role r INNER JOIN sys_user_role ur ON r.id = ur.role_id WHERE ur.user_id = #{userId}")
    List<SysRole> selectListByUserId(Long userId);
}

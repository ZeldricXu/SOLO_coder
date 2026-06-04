package com.flowplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowplatform.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT r.role_code FROM sys_user_role ur JOIN sys_role r ON ur.role_id = r.id WHERE ur.user_id = #{userId}")
    List<String> selectRoleCodesByUserId(Long userId);

    @Select("SELECT r.role_name FROM sys_user_role ur JOIN sys_role r ON ur.role_id = r.id WHERE ur.user_id = #{userId}")
    List<String> selectRoleNamesByUserId(Long userId);
}

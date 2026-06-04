package com.flowplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowplatform.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {

    @Select("SELECT config_value FROM sys_config WHERE config_key = #{key} LIMIT 1")
    String getValueByKey(String key);
}
